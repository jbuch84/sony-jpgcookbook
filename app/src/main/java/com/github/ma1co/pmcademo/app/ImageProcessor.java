package com.github.ma1co.pmcademo.app;

import android.media.ExifInterface;
import android.os.AsyncTask;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageProcessor {

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String resultPath);
    }

    private ProcessorCallback mCallback;

    public ImageProcessor(ProcessorCallback callback) {
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        new ProcessTask(inPath, outDir, qualityIndex, profile).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            if (mCallback != null) {
                mCallback.onPreloadStarted();
            }
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String path = params[0];
            if (path == null || path.equals("NONE")) {
                return false;
            }
            return LutEngine.loadLut(path);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (mCallback != null) {
                mCallback.onPreloadFinished(success);
            }
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
            if (mCallback != null) {
                mCallback.onProcessStarted();
            }
        }

        @Override
        protected String doInBackground(Void... voids) {
            File dir = new File(outDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String finalOutPath = new File(dir, "FILM_" + timeStamp + ".JPG").getAbsolutePath();

            String fileToProcess = inPath;
            int scaleDenom = 1;
            boolean isProxy = (qualityIndex == 0);
            boolean usedThumbnail = false;

            // THUMBNAIL HACK (Path 2): For Proxy Quality, rip the embedded 2MP JPEG directly from the EXIF header
            if (isProxy) {
                try {
                    ExifInterface exif = new ExifInterface(inPath);
                    byte[] thumbData = exif.getThumbnail();
                    if (thumbData != null && thumbData.length > 0) {
                        File tempThumb = new File(outDir, "temp_thumb.jpg");
                        FileOutputStream fos = new FileOutputStream(tempThumb);
                        fos.write(thumbData);
                        fos.close();
                        
                        fileToProcess = tempThumb.getAbsolutePath();
                        scaleDenom = 1; // The thumbnail is already ~2MP, no downscaling needed
                        usedThumbnail = true;
                    } else {
                        // Fallback: If hardware didn't embed a thumbnail, force libjpeg to scale 24MP by 1/4 (1.5MP)
                        scaleDenom = 4; 
                    }
                } catch (Exception e) {
                    scaleDenom = 4;
                }
            } else if (qualityIndex == 1) {
                scaleDenom = 2; // High: Scales 24MP by 1/2 (~6MP) natively in C++
            } else {
                scaleDenom = 1; // Ultra: Full 24MP resolution
            }

            // C++ JNI Call (Path 1 uses NEON SIMD in C++ for the heavy math)
            boolean success = LutEngine.processImageNative(
                    fileToProcess,
                    finalOutPath,
                    scaleDenom,
                    profile.opacity,
                    profile.grain,
                    profile.grainSize,
                    profile.vignette,
                    profile.rollOff
            );

            // Immediately clean up the temporary thumbnail file if we generated one
            if (usedThumbnail) {
                File temp = new File(fileToProcess);
                if (temp.exists()) {
                    temp.delete();
                }
            }

            if (success) {
                // EXIF RESTORATION: The thumbnail extraction strips EXIF tags. 
                // We must copy the camera's original tags back onto the final graded file so the Playback Viewer works.
                try {
                    ExifInterface oldExif = new ExifInterface(inPath);
                    ExifInterface newExif = new ExifInterface(finalOutPath);
                    
                    String[] tagsToCopy = {
                        ExifInterface.TAG_ORIENTATION, 
                        ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_MAKE, 
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_FLASH, 
                        ExifInterface.TAG_WHITE_BALANCE,
                        "FNumber", 
                        "ExposureTime", 
                        "ISOSpeedRatings", 
                        "FocalLength"
                    };
                    
                    for (String tag : tagsToCopy) {
                        String val = oldExif.getAttribute(tag);
                        if (val != null) {
                            newExif.setAttribute(tag, val);
                        }
                    }
                    newExif.saveAttributes();
                } catch (Exception e) {
                    // Ignore EXIF copy errors, the image itself is successfully graded and safe.
                }
                return finalOutPath;
            } else {
                // Failsafe: If C++ processing aborts (e.g., out of memory), clean up the corrupted 0-byte file
                File failedOut = new File(finalOutPath);
                if (failedOut.exists()) {
                    failedOut.delete();
                }
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultPath) {
            if (mCallback != null) {
                mCallback.onProcessFinished(resultPath);
            }
        }
    }
}