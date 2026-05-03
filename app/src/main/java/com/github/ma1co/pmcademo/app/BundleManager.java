package com.github.ma1co.pmcademo.app;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;

    public static void extractAllBundles() {
        extractBundlesInDir(Filepaths.getAppDir());
        extractBundlesInDir(Filepaths.getRecipeDir());
    }

    private static void extractBundlesInDir(File dir) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".cam")) {
                Log.d(TAG, "Found bundle in " + dir.getName() + ": " + file.getName());
                extractBundle(file);
            }
        }
    }

    private static void extractBundle(File zipFile) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // Security: Prevent Zip-Slip vulnerability
                if (entryName.contains("..")) {
                    Log.w(TAG, "Skipping malicious zip entry: " + entryName);
                    continue;
                }

                if (entry.isDirectory()) {
                    continue;
                }

                File destFile = new File(Filepaths.getAppDir(), entryName);
                
                // Ensure parent directories exist
                File parentDir = destFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                // Sony Android 2.3.7 VFAT bug: write to 8.3 temp file first, then rename.
                // Creating a new file with a long name via FileOutputStream directly can silently fail or throw.
                File tempFile = new File(parentDir, "BNDL.TMP");

                Log.d(TAG, "Extracting: " + entryName + " to " + tempFile.getAbsolutePath());

                FileOutputStream fos = null;
                BufferedOutputStream bos = null;
                try {
                    fos = new FileOutputStream(tempFile);
                    bos = new BufferedOutputStream(fos, BUFFER_SIZE);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        bos.write(buffer, 0, count);
                    }
                    bos.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract entry: " + entryName, e);
                } finally {
                    try { if (bos != null) bos.close(); } catch (Exception e) {}
                    try { if (fos != null) fos.close(); } catch (Exception e) {}
                }

                if (tempFile.exists()) {
                    if (destFile.exists()) destFile.delete();
                    tempFile.renameTo(destFile);
                    Log.d(TAG, "Successfully moved to: " + destFile.getName());
                }

                zis.closeEntry();
            }
            Log.d(TAG, "Successfully extracted bundle: " + zipFile.getName());
            
            // Delete the .cam file after successful extraction
            zis.close();
            zis = null;
            if (zipFile.delete()) {
                Log.d(TAG, "Deleted bundle: " + zipFile.getName());
            } else {
                Log.e(TAG, "Failed to delete bundle: " + zipFile.getName());
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract bundle: " + zipFile.getName(), e);
        } finally {
            try { if (zis != null) zis.close(); } catch (Exception e) {}
        }
    }
}
