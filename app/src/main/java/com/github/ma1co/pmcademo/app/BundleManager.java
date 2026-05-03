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
                    // Scan all possible storage roots for JPEGCAM folders
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
                } catch (IOException e) {
                    Log.e(TAG, "Path error: " + e.getMessage());
                }
            }
        }
    }

    private static void doExtract(File zip, File root) {
        Log.d(TAG, "--- UNPACKING BUNDLE: " + zip.getName() + " on " + root.getAbsolutePath() + " ---");
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
            ZipEntry entry;
            int counter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.toLowerCase().contains("__macosx") || name.contains("/.") || name.startsWith(".")) {
                    zis.closeEntry();
                    continue;
                }

                // Path Resolution: root is /sdcard/JPEGCAM, name is RECIPES/R_LOOK.TXT
                File dest = new File(root, name);
                File parent = dest.getParentFile();
                
                // Robust Directory Creation
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        // Retry once with a delay (Sony filesystem latency)
                        try { Thread.sleep(50); } catch (Exception e) {}
                        parent.mkdirs();
                    }
                }

                // Sony VFAT Workaround: 8.3 TMP file
                File tmp = new File(parent, String.format("B%07d.TMP", counter++));
                Log.d(TAG, "  -> " + name);

                FileOutputStream fos = null;
                boolean writeOk = false;
                try {
                    fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = zis.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) {}
                    writeOk = true;
                } catch (Exception e) {
                    Log.e(TAG, "    Write failed: " + e.getMessage());
                } finally {
                    if (fos != null) try { fos.close(); } catch (Exception e) {}
                }

                // Finalize Move
                if (writeOk && tmp.exists()) {
                    if (dest.exists()) dest.delete();
                    if (!tmp.renameTo(dest)) {
                        Log.w(TAG, "    Rename failed, using byte-copy fallback for " + dest.getName());
                        copyFallback(tmp, dest);
                    }
                    tmp.delete(); 
                }
                zis.closeEntry();
            }
            
            zis.close();
            zis = null;
            if (zip.delete()) {
                Log.d(TAG, "BUNDLE CLEANUP: Deleted " + zip.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "FATAL BUNDLE ERROR: " + e.getMessage());
        } finally {
            if (zis != null) try { zis.close(); } catch (Exception e) {}
        }
    }

    private static void copyFallback(File src, File dst) {
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
