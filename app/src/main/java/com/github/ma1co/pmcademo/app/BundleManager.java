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
                    for (File root : Filepaths.getStorageRoots()) {
                        File appDir = new File(root, "JPEGCAM");
                        if (appDir.exists() && appDir.isDirectory()) {
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

    private static void scanDir(File dir, File root) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                try {
                    String id = f.getCanonicalPath();
                    if (processed.contains(id)) continue;
                    processed.add(id);
                    doExtract(f, root);
                } catch (IOException e) {}
            }
        }
    }

    private static void doExtract(File zip, File root) {
        Log.d(TAG, "--- UNPACKING: " + zip.getName() + " TO " + root.getAbsolutePath() + " ---");
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
            ZipEntry entry;
            int counter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // 1. Sanitize Entry Name (Fix for API 10 Leading Slash Bug)
                if (entryName.startsWith("/")) entryName = entryName.substring(1);
                if (entryName.startsWith("./")) entryName = entryName.substring(2);
                
                String lowerName = entryName.toLowerCase();
                if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                    zis.closeEntry();
                    continue;
                }

                // 2. Build Destination Path
                File destFile = new File(root, entryName);
                File parentDir = destFile.getParentFile();
                
                // 3. Robust Directory Creation
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                    // Sony VFAT Latency Workaround
                    try { Thread.sleep(100); } catch (Exception e) {}
                }

                // 4. Write to 8.3 TMP file
                File tempFile = new File(parentDir, String.format("T%07d.TMP", counter++));
                Log.d(TAG, "  Unzipping: " + entryName);

                FileOutputStream fos = null;
                boolean writeOk = false;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) {}
                    writeOk = true;
                } catch (Exception e) {
                    Log.e(TAG, "    Write error: " + e.getMessage());
                } finally {
                    if (fos != null) try { fos.close(); } catch (Exception e) {}
                }

                // 5. Finalize Move
                if (writeOk && tempFile.exists()) {
                    if (destFile.exists()) destFile.delete();
                    
                    if (tempFile.renameTo(destFile)) {
                        Log.d(TAG, "    OK: " + destFile.getName());
                    } else {
                        Log.w(TAG, "    Rename failed, using copy fallback...");
                        manualCopy(tempFile, destFile);
                        tempFile.delete();
                    }
                }
                zis.closeEntry();
            }
            
            zis.close(); zis = null;
            if (zip.delete()) Log.d(TAG, "Bundle deleted.");

        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR: " + e.getMessage());
        } finally {
            if (zis != null) try { zis.close(); } catch (Exception e) {}
        }
    }

    private static void manualCopy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            try { out.getFD().sync(); } catch (Exception e) {}
            Log.d(TAG, "    Fallback copy successful.");
        } catch (Exception e) {
            Log.e(TAG, "    Fallback copy failed: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
        }
    }
}
