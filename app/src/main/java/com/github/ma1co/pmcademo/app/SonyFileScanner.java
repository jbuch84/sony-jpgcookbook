package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    private Context mContext;
    private HashSet<String> knownFiles = new HashSet<String>();
    private HandlerThread scannerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;

    private static final int POLL_INTERVAL_MS = 75;
    private static final int MAX_SCAN_ATTEMPTS = 1200;
    private static final int MAX_DEFERRED_SCAN_ATTEMPTS = 1600;
    public boolean isPolling = false;
    private int scanAttempts = 0;
    private long scanStartedMs = 0;
    private boolean waitingForReady = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath, long scannerStartedMs, long detectedMs, int attempts);
        boolean isReadyToProcess();
    }

    public SonyFileScanner(Context context, ScannerCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper()); 
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        // Build initial baseline (silent, no polling)
        backgroundHandler.post(new Runnable() {
            @Override public void run() { findNewestFile(false); }
        });
    }

    public void start() {
        stop();
        isPolling = true;
        scanAttempts = 0;
        scanStartedMs = System.currentTimeMillis();
        waitingForReady = false;
        scheduleNextPoll(0);
        Log.d("JPEG.CAM", "Scanner Woken Up: Starting extended capture window...");
    }

    public void stop() {
        isPolling = false;
        waitingForReady = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    // --- ADDED THIS METHOD TO FIX LINE 241 ERROR ---
    public void checkNow() {
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override public void run() {
                    if (!isPolling) {
                        scanStartedMs = System.currentTimeMillis();
                        scanAttempts = 0;
                    }
                    findNewestFile(true);
                }
            });
        }
    }

    public void scheduleNextPoll() {
        scheduleNextPoll(POLL_INTERVAL_MS);
    }

    private void scheduleNextPoll(long delayMs) {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    int maxAttempts = waitingForReady ? MAX_DEFERRED_SCAN_ATTEMPTS : MAX_SCAN_ATTEMPTS;
                    if (scanAttempts++ >= maxAttempts) {
                        stop();
                        Log.d("JPEG.CAM", "Scanner Timed Out: Going back to sleep.");
                        return;
                    }

                    findNewestFile(true);

                    if (isPolling) scheduleNextPoll(POLL_INTERVAL_MS);
                }
            }
        }, delayMs);
    }

    // NEW: Lightweight PNG Metadata parser (Safely handles .txt disguises)
    public static String getGrainTitle(File file) {
        String name = file.getName();
        String lowerName = name.toLowerCase(Locale.US);
        String primaryTitle = null;
        String titleFallback = null; // <--- ADDED: Declaration for the fallback
        
        if (lowerName.endsWith(".png") || lowerName.endsWith(".txt")) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] header = new byte[8];
                
                if (fis.read(header) == 8 && header[0] == (byte)137 && header[1] == 80 && header[2] == 78 && header[3] == 71) {
                    while (true) {
                        byte[] lenBuf = new byte[4];
                        if (fis.read(lenBuf) < 4) break;
                        int len = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16) | 
                                  ((lenBuf[2] & 0xFF) << 8) | (lenBuf[3] & 0xFF);
                        
                        if (len < 0 || len > 1000000) { fis.skip(len + 4); continue; }
                        
                        byte[] typeBuf = new byte[4];
                        if (fis.read(typeBuf) < 4) break;
                        String type = new String(typeBuf, "US-ASCII");

                        if (type.equals("IDAT") || type.equals("IEND")) break; 

                        if (type.equals("tEXt") || type.equals("iTXt")) {
                            byte[] data = new byte[len];
                            fis.read(data);
                            int nullIdx = -1;
                            for (int i = 0; i < len; i++) {
                                if (data[i] == 0) { nullIdx = i; break; }
                            }
                            
                            if (nullIdx > 0) {
                                String keyword = new String(data, 0, nullIdx, "UTF-8").toLowerCase(Locale.US);
                                
                                if (type.equals("tEXt")) {
                                    if (keyword.equals("title")) {
                                        titleFallback = new String(data, nullIdx + 1, len - nullIdx - 1, "ISO-8859-1");
                                    }
                                } else if (type.equals("iTXt")) {
                                    if (keyword.equals("title") && nullIdx + 2 < len && data[nullIdx + 1] == 0) {
                                        int nullCount = 0;
                                        int textStart = nullIdx + 3;
                                        for (int i = textStart; i < len; i++) {
                                            if (data[i] == 0) {
                                                nullCount++;
                                                if (nullCount == 2) {
                                                    textStart = i + 1;
                                                    break;
                                                }
                                            }
                                        }
                                        titleFallback = new String(data, textStart, len - textStart, "UTF-8");
                                    } else if (keyword.equals("xml:com.adobe.xmp")) {
                                        String xmp = new String(data, nullIdx + 1, len - nullIdx - 1, "UTF-8");
                                        int titleStart = xmp.indexOf("<dc:title>");
                                        if (titleStart != -1) {
                                            int liStart = xmp.indexOf("<rdf:li", titleStart);
                                            if (liStart != -1) {
                                                int valStart = xmp.indexOf(">", liStart) + 1;
                                                int valEnd = xmp.indexOf("</rdf:li>", valStart);
                                                if (valStart > 0 && valEnd > valStart) {
                                                    primaryTitle = xmp.substring(valStart, valEnd);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            fis.skip(len); 
                        }
                        fis.skip(4); // Skip CRC
                    }
                }
                fis.close();
            } catch (Exception e) {}
        }
        
        if (primaryTitle != null && !primaryTitle.trim().isEmpty()) {
            return primaryTitle.trim();
        }
        if (titleFallback != null && !titleFallback.trim().isEmpty()) {
            return titleFallback.trim();
        }

        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = Filepaths.getDcimDir();
        if (!dcimDir.exists() || !dcimDir.isDirectory()) return;

        boolean deferredCandidate = false;
        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory() && !dir.getName().startsWith(".")) { 
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase(Locale.US);
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS")) {
                                String currentFilePath = f.getAbsolutePath();
                                if (!knownFiles.contains(currentFilePath)) {

                                    if (f.length() < 1024) continue;

                                    if (triggerCallback && mCallback != null && !mCallback.isReadyToProcess()) {
                                        deferredCandidate = true;
                                        continue;
                                    }

                                    knownFiles.add(currentFilePath);

                                    // SUCCESS! Kill the loop immediately
                                    isPolling = false;
                                    waitingForReady = false;

                                    if (triggerCallback && mCallback != null) {
                                        final String finalPathToProcess = currentFilePath;
                                        final long startedMs = scanStartedMs > 0 ? scanStartedMs : System.currentTimeMillis();
                                        final long detectedMs = System.currentTimeMillis();
                                        final int attempts = scanAttempts;
                                        mainHandler.post(new Runnable() {
                                            @Override public void run() {
                                                mCallback.onNewPhotoDetected(finalPathToProcess, startedMs, detectedMs, attempts);
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (triggerCallback && isPolling) waitingForReady = deferredCandidate;
    }
}
