// Part 1 of 1 - SonyFileScanner.java (Replace your existing file completely)
// Location: app/src/main/java/com/github/ma1co/pmcademo/app/SonyFileScanner.java

package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    private String lastSeenFilePath = "";
    
    // Threading components to keep heavy I/O off the main UI thread
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
        this.mainHandler = new Handler(Looper.getMainLooper()); // For safe UI callbacks
        
        Log.d("JPEG.CAM", "SonyFileScanner initialized. DCIM Root: " + Filepaths.getDcimDir().getAbsolutePath());
        
        // Find baseline without triggering callback (Runs on caller's thread temporarily for setup)
        findNewestFile(false); 
        
        // Setup dedicated background thread for heavy SD card polling
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        start();
    }

    public void start() {
        if (!isPolling) {
            Log.d("JPEG.CAM", "Starting file scanner background polling loop...");
            isPolling = true;
            scheduleNextPoll();
        }
    }

    public void stop() {
        Log.d("JPEG.CAM", "Stopping file scanner polling loop.");
        isPolling = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    public void checkNow() {
        Log.d("JPEG.CAM", "Hardware Broadcast caught! Forcing immediate background check...");
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    findNewestFile(true);
                }
            });
        }
    }

    private void scheduleNextPoll() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    findNewestFile(true);
                    scheduleNextPoll(); // Loop
                }
            }
        }, 1000);
    }

    /**
     * Warning: This method performs heavy file I/O and MUST be called from the background thread.
     */
    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = Filepaths.getDcimDir(); // Use dynamic root
        if (!dcimDir.exists() || !dcimDir.isDirectory()) {
            if (triggerCallback) Log.e("JPEG.CAM", "DCIM directory not found: " + dcimDir.getAbsolutePath());
            return;
        }

        File newestFile = null;
        long maxModified = 0;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory() && dir.getName().toUpperCase().endsWith("MSDCF")) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            // Look for original Sony JPEGs (Ignore our FILM_ outputs and temp files)
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("temp_")) {
                                if (f.lastModified() > maxModified) {
                                    maxModified = f.lastModified();
                                    newestFile = f;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (newestFile != null) {
            final String currentPath = newestFile.getAbsolutePath();
            if (!currentPath.equals(lastSeenFilePath)) {
                Log.d("JPEG.CAM", "NEW FILE DETECTED: " + currentPath);
                lastSeenFilePath = currentPath;
                
                if (triggerCallback && mCallback != null) {
                    // It is safe to check this boolean on the background thread
                    boolean isReady = mCallback.isReadyToProcess();
                    Log.d("JPEG.CAM", "isReadyToProcess() evaluated to: " + isReady);
                    
                    if (isReady) {
                        Log.d("JPEG.CAM", "Firing onNewPhotoDetected callback to main thread!");
                        // Post the result back to the main UI thread so the UI can be updated safely
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mCallback.onNewPhotoDetected(currentPath);
                            }
                        });
                    } else {
                        Log.w("JPEG.CAM", "Engine blocked processing. (Either LUT is 0/OFF or processor is not initialized).");
                    }
                }
            }
        }
    }
}