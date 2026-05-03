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

    private static void logToFile(String msg) {
        Log.e(TAG, msg);
        try {
            File logFile = new File(Filepaths.getAppDir(), "BUNDLE_DEBUG.TXT");
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(msg + "\n");
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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
                logToFile("Found bundle in " + dir.getName() + ": " + file.getName());
                extractBundle(file);
            }
        }
    }

    private static void extractBundle(File zipFile) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;
            int fileCounter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // Security: Prevent Zip-Slip vulnerability
                if (entryName.contains("..")) {
                    logToFile("Skipping malicious zip entry: " + entryName);
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
                // Using a counter so multiple files in the same dir don't collide if rename is slow.
                File tempFile = new File(parentDir, String.format("BNDL%03d.TMP", fileCounter++));

                logToFile("Extracting: " + entryName + " to " + tempFile.getAbsolutePath());

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                    fos.flush();
                    fos.getFD().sync();
                } catch (Exception e) {
                    logToFile("Failed to extract entry: " + entryName + " - Error: " + e.getMessage());
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception e) {}
                }

                if (tempFile.exists()) {
                    if (destFile.exists()) {
                        boolean delSuccess = destFile.delete();
                        logToFile("Deleted existing dest: " + destFile.getName() + " -> " + delSuccess);
                    }
                    
                    boolean renameSuccess = tempFile.renameTo(destFile);
                    
                    if (!renameSuccess) {
                        logToFile("Rename failed for: " + tempFile.getName() + " to " + destFile.getName() + ". Attempting fallback.");
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
                            fallbackFos.getFD().sync();
                            renameSuccess = true;
                            logToFile("Fallback copy succeeded for: " + destFile.getName());
                        } catch (Exception e) {
                            logToFile("Fallback copy also failed! Error: " + e.getMessage());
                        } finally {
                            try { if (fis != null) fis.close(); } catch (Exception e) {}
                            try { if (fallbackFos != null) fallbackFos.close(); } catch (Exception e) {}
                            if (renameSuccess) { tempFile.delete(); }
                        }
                    } else {
                        logToFile("Successfully moved to: " + destFile.getName());
                    }
                } else {
                    logToFile("FATAL: Temp file was never created! " + tempFile.getAbsolutePath());
                }

                zis.closeEntry();
            }
            logToFile("Successfully extracted bundle: " + zipFile.getName());
            
            // Delete the .cam file after successful extraction
            zis.close();
            zis = null;
            if (zipFile.delete()) {
                logToFile("Deleted bundle: " + zipFile.getName());
            } else {
                logToFile("Failed to delete bundle: " + zipFile.getName());
            }

        } catch (Exception e) {
            logToFile("Failed to extract bundle: " + zipFile.getName() + " - Error: " + e.getMessage());
        } finally {
            try { if (zis != null) zis.close(); } catch (Exception e) {}
        }
    }
}
