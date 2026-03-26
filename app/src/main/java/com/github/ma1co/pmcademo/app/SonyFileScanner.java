package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    private String lastSeenFilePath = "";
    
    private HandlerThread scannerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    private boolean isPolling = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(ScannerCallback callback) {
        this.mCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper()); 
        
        Log.d("JPEG.CAM", "SonyFileScanner initialized. Root: " + Filepaths.getDcimDir().getAbsolutePath());
        
        findNewestFile(false); 
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        start();
    }

    public void start() {
        if (!isPolling) {
            isPolling = true;
            scheduleNextPoll();
        }
    }

    public void stop() {
        isPolling = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    public void checkNow() {
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override public void run() { findNewestFile(true); }
            });
        }
    }

    private void scheduleNextPoll() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    findNewestFile(true);
                    scheduleNextPoll(); 
                }
            }
        }, 1000);
    }

    private void findNewestFile(boolean triggerCallback) {
        File searchRoot = Filepaths.getDcimDir(); 
        if (!searchRoot.exists() || !searchRoot.isDirectory()) return;

        File newestFile = null;
        long maxModified = 0;

        // BULLETPROOF SEARCH: Look at files in this directory AND 1-level deep
        List<File> allFiles = new ArrayList<File>();
        File[] level1 = searchRoot.listFiles();
        
        if (level1 != null) {
            for (File f1 : level1) {
                if (f1.isDirectory()) {
                    File[] level2 = f1.listFiles();
                    if (level2 != null) {
                        for (File f2 : level2) allFiles.add(f2);
                    }
                } else {
                    allFiles.add(f1);
                }
            }
        }

        // Find the newest valid JPEG
        for (File f : allFiles) {
            String name = f.getName().toUpperCase();
            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("TEMP_")) {
                if (f.lastModified() > maxModified) {
                    maxModified = f.lastModified();
                    newestFile = f;
                }
            }
        }

        if (newestFile != null) {
            final String currentPath = newestFile.getAbsolutePath();
            if (!currentPath.equals(lastSeenFilePath)) {
                Log.d("JPEG.CAM", "NEW FILE DETECTED: " + currentPath);
                lastSeenFilePath = currentPath;
                
                if (triggerCallback && mCallback != null) {
                    if (mCallback.isReadyToProcess()) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { mCallback.onNewPhotoDetected(currentPath); }
                        });
                    } else {
                        Log.w("JPEG.CAM", "Engine blocked processing. (LUT is 0/OFF or processor not initialized).");
                    }
                }
            }
        }
    }
}