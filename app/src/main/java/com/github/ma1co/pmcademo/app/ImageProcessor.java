package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

public class ImageProcessor {

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        // Log to verify we are actually trying to load
        Log.d("filmOS", "ImageProcessor: Triggering LUT Preload for " + name);
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        Log.d("filmOS", "ImageProcessor.processJpeg called!");
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            if (mCallback != null) mCallback.onPreloadStarted();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String path = params[0];
            if (path == null || path.equals("NONE")) return false;
            // This calls your LutEngine.java which calls the native-lib.cpp
            return LutEngine.loadLut(path);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            Log.d("filmOS", "PreloadLutTask finished. Success: " + success);
            if (mCallback != null) mCallback.onPreloadFinished(success);
        }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath;
        private String outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
            this.inPath = inPath;
            this.outDir = outDir;
            this.qualityIndex = qualityIndex;
            this.profile = profile;
        }

        @Override
        protected void onPreExecute() {
            if (mCallback != null) mCallback.onProcessStarted();
        }

        @Override
        protected String doInBackground(Void... voids) {
            Log.d("filmOS", "ProcessTask.doInBackground started.");
            
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            // 8.3 FILENAME FIX: Keep it short for the Sony FUSE driver
            String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
            String finalOutPath = new File(dir, "F" + timeTag.substring(timeTag.length() - 7) + ".JPG").getAbsolutePath();

            String fileToProcess = inPath;
            int scaleDenom = 1;
            boolean usedThumbnail = false;

            // THUMBNAIL RIPPING: This is what gave you the speed before
            if (qualityIndex == 0) {
                try {
                    ExifInterface exif = new ExifInterface(inPath);
                    byte[] thumb = exif.getThumbnail();
                    if (thumb != null) {
                        // Temp file must also follow 8.3 naming!
                        File temp = new File(outDir, "TEMP_RIP.JPG");
                        FileOutputStream fos = new FileOutputStream(temp);
                        fos.write(thumb);
                        fos.close();
                        fileToProcess = temp.getAbsolutePath();
                        usedThumbnail = true;
                        scaleDenom = 1; // It's already small
                        Log.d("filmOS", "Using ripped thumbnail for processing.");
                    } else {
                        scaleDenom = 4;
                    }
                } catch (Exception e) {
                    Log.e("filmOS", "Thumbnail rip failed, falling back to scaleDenom 4");
                    scaleDenom = 4;
                }
            } else if (qualityIndex == 1) {
                scaleDenom = 2; 
            }

            // SONY FUSE WORKAROUND: Pre-create the file in Java so C++ can open it
            try {
                FileOutputStream touch = new FileOutputStream(finalOutPath);
                touch.write(1); 
                touch.close();
                Log.d("filmOS", "Pre-created output file: " + finalOutPath);
            } catch (Exception e) {
                Log.e("filmOS", "Pre-create failed: " + e.getMessage());
            }

            Log.d("filmOS", "Calling Native Engine with scaleDenom: " + scaleDenom);
            boolean success = LutEngine.processImageNative(
                    fileToProcess, finalOutPath, scaleDenom,
                    profile.opacity, profile.grain, profile.grainSize,
                    profile.vignette, profile.rollOff
            );
            
            Log.d("filmOS", "Native Engine returned: " + success);

            if (usedThumbnail) new File(fileToProcess).delete();

            if (success) {
                // Broadcast to tell the camera a new file exists
                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                return finalOutPath;
            } else {
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultPath) {
            if (mCallback != null) mCallback.onProcessFinished(resultPath);
        }
    }
}