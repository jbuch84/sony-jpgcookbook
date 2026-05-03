package com.github.ma1co.pmcademo.app;

import android.util.Log;
import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;
    private static boolean isRunning = false;
    private static final Set<String> processed = new HashSet<String>();

    public static synchronized void extractAllBundles() {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processed.clear();
                    // Iterate through all possible storage locations (Internal, SD1, SD2, etc.)
                    for (File root : Filepaths.getStorageRoots()) {
                        File appDir = new File(root, "JPEGCAM");
                        if (appDir.exists() && appDir.isDirectory()) {
                            // Scan both the root /JPEGCAM and /JPEGCAM/RECIPES
                            scanDir(appDir, appDir);
                            scanDir(new File(appDir, "RECIPES"), appDir);
                        }
                    }
                } finally {
                    isRunning = false;
                }
            }
        }).start();
    }

    private static void scanDir(File dir, File targetRootDir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            // Case-insensitive check and skip hidden Mac metadata files
            if (f.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                try {
                    // Ensure we don't process the same file twice if it's symlinked or double-mounted
                    String id = f.getCanonicalPath();
                    if (processed.contains(id)) continue;
                    processed.add(id);
                    doExtract(f, targetRootDir);
                } catch (IOException e) {
                    Log.e(TAG, "Path resolve error: " + e.getMessage());
                }
            }
        }
    }

    private static void doExtract(File zip, File root) {
        Log.d(TAG, "--- UNPACKING BUNDLE: " + zip.getName() + " TO DRIVE " + root.getAbsolutePath() + " ---");
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
            ZipEntry entry;
            int counter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String lowerName = entryName.toLowerCase();
                
                // Skip directories and Mac junk
                if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                    zis.closeEntry();
                    continue;
                }

                // Path Resolution: Target Root + Zip Folder Path (e.g. RECIPES/R_LOOK.TXT)
                File destFile = new File(root, entryName);
                File parentDir = destFile.getParentFile();
                
                // Robust Directory Creation (Bypasses stale VFAT directory table bugs)
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        // Retry once with a sleep (Sony filesystem driver latency workaround)
                        try { Thread.sleep(100); } catch (Exception e) {}
                        parentDir.mkdirs();
                    }
                }

                // 8.3 Temporary Filename to bypass LFN stream creation bugs
                File tempFile = new File(parentDir, String.format("T%07d.TMP", counter++));
                Log.d(TAG, "  Unzipping: " + entryName + " -> " + tempFile.getName());

                FileOutputStream fos = null;
                boolean writeSuccess = false;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    // Force the hardware to sync before closing
                    try { fos.getFD().sync(); } catch (Exception e) {}
                    writeSuccess = true;
                } catch (Exception e) {
                    Log.e(TAG, "    Write failed: " + entryName + " - " + e.getMessage());
                } finally {
                    if (fos != null) {
                        try { fos.close(); } catch (Exception e) {}
                    }
                }

                // Finalize the file on the SD card
                if (writeSuccess && tempFile.exists()) {
                    // Give the OS a moment to release the file handle completely
                    try { Thread.sleep(50); } catch (Exception e) {}
                    
                    if (destFile.exists()) destFile.delete();
                    
                    if (tempFile.renameTo(destFile)) {
                        Log.d(TAG, "    Success: Renamed to " + destFile.getName());
                    } else {
                        Log.w(TAG, "    Rename failed! Attempting direct copy fallback for " + destFile.getName());
                        // Ultimate fallback: manual byte copy into the long filename
                        manualCopy(tempFile, destFile);
                        tempFile.delete();
                    }
                }
                zis.closeEntry();
            }
            
            // Clean up resources and delete the bundle
            zis.close();
            zis = null;
            if (zip.delete()) {
                Log.d(TAG, "BUNDLE FINISHED: Removed " + zip.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "FATAL BUNDLE ERROR: " + zip.getName() + " - " + e.getMessage());
        } finally {
            if (zis != null) {
                try { zis.close(); } catch (Exception e) {}
            }
        }
    }

    private static void manualCopy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();
            try { out.getFD().sync(); } catch (Exception e) {}
            Log.d(TAG, "    Fallback copy completed successfully.");
        } catch (Exception e) {
            Log.e(TAG, "    Fallback copy failed: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
        }
    }
}
