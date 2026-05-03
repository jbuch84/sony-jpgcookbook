package com.github.ma1co.pmcademo.app;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;

    private static void logToFile(File rootDir, String msg) {
        Log.e(TAG, msg);
        try {
            if (rootDir != null) {
                if (!rootDir.exists()) rootDir.mkdirs();
                File logFile = new File(rootDir, "BUNDLE_DEBUG.TXT");
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(msg + "\n");
                fw.close();
            }
        } catch (IOException e) {
            // Ignore log errors to prevent crashing the app
        }
    }

    public static void extractAllBundles() {
        for (File root : Filepaths.getStorageRoots()) {
            File appDir = new File(root, "JPEGCAM");
            if (appDir.exists() && appDir.isDirectory()) {
                extractBundlesInDir(appDir, appDir);
                File recipeDir = new File(appDir, "RECIPES");
                if (recipeDir.exists() && recipeDir.isDirectory()) {
                    extractBundlesInDir(recipeDir, appDir);
                }
            }
        }
    }

    private static void extractBundlesInDir(File scanDir, File targetRootDir) {
        File[] files = scanDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                logToFile(targetRootDir, "Found bundle in " + scanDir.getName() + ": " + file.getName());
                extractBundle(file, targetRootDir);
            }
        }
    }

    private static void extractBundle(File zipFile, File targetRootDir) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;
            int fileCounter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // Security: Prevent Zip-Slip vulnerability
                if (entryName.contains("..")) {
                    logToFile(targetRootDir, "Skipping malicious zip entry: " + entryName);
                    continue;
                }

                if (entry.isDirectory()) {
                    continue;
                }

                File destFile = new File(targetRootDir, entryName);
                
                // Ensure parent directories exist
                File parentDir = destFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    boolean created = parentDir.mkdirs();
                    logToFile(targetRootDir, "Created directory: " + parentDir.getAbsolutePath() + " -> " + created);
                }

                // Sony Android 2.3.7 VFAT bug: write to 8.3 temp file first, then rename.
                // Using a counter so multiple files in the same dir don't collide if rename is slow.
                File tempFile = new File(parentDir, String.format("BNDL%03d.TMP", fileCounter++));

                logToFile(targetRootDir, "Extracting: " + entryName + " to " + tempFile.getAbsolutePath());

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) {}
                } catch (Exception e) {
                    logToFile(targetRootDir, "Failed to extract entry: " + entryName + " - Error: " + e.getMessage());
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception e) {}
                }

                if (tempFile.exists()) {
                    if (destFile.exists()) {
                        boolean delSuccess = destFile.delete();
                        logToFile(targetRootDir, "Deleted existing dest: " + destFile.getName() + " -> " + delSuccess);
                    }
                    
                    boolean renameSuccess = tempFile.renameTo(destFile);
                    
                    if (!renameSuccess) {
                        logToFile(targetRootDir, "Rename failed for: " + tempFile.getName() + " to " + destFile.getName() + ". Attempting fallback.");
                        // Fallback: Copy bytes directly if renameTo fails
                        FileInputStream fis = null;
                        FileOutputStream fallbackFos = null;
                        try {
                            fis = new FileInputStream(tempFile);
                            fallbackFos = new FileOutputStream(destFile);
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int count;
                            while ((count = fis.read(buffer)) != -1) {
                                fallbackFos.write(buffer, 0, count);
                            }
                            fallbackFos.flush();
                            try { fallbackFos.getFD().sync(); } catch (Exception e2) {}
                            renameSuccess = true;
                            logToFile(targetRootDir, "Fallback copy succeeded for: " + destFile.getName());
                        } catch (Exception e) {
                            logToFile(targetRootDir, "Fallback copy also failed! Error: " + e.getMessage());
                        } finally {
                            try { if (fis != null) fis.close(); } catch (Exception e) {}
                            try { if (fallbackFos != null) fallbackFos.close(); } catch (Exception e) {}
                            if (renameSuccess) { tempFile.delete(); }
                        }
                    } else {
                        logToFile(targetRootDir, "Successfully moved to: " + destFile.getName());
                    }
                } else {
                    logToFile(targetRootDir, "FATAL: Temp file was never created! " + tempFile.getAbsolutePath());
                }

                zis.closeEntry();
            }
            logToFile(targetRootDir, "Successfully extracted bundle: " + zipFile.getName());
            
            // Delete the .cam file after successful extraction
            zis.close();
            zis = null;
            if (zipFile.delete()) {
                logToFile(targetRootDir, "Deleted bundle: " + zipFile.getName());
            } else {
                logToFile(targetRootDir, "Failed to delete bundle: " + zipFile.getName());
            }

        } catch (Exception e) {
            logToFile(targetRootDir, "Failed to extract bundle: " + zipFile.getName() + " - Error: " + e.getMessage());
        } finally {
            try { if (zis != null) zis.close(); } catch (Exception e) {}
        }
    }
}
