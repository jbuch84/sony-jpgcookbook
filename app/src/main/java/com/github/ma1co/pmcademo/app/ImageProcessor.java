package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import java.io.File;

public class ImageProcessor {
    private LutEngine mEngine;
    private Context mContext;
    private ProcessorCallback mCallback;

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String result);
    }

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mEngine = new LutEngine();
    }

    public void triggerLutPreload(String lutPath, String lutName) {
        new PreloadLutTask().execute(lutPath, lutName);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p, boolean applyCrop, boolean isDiptych) {
        processJpeg(originalPath, outDirPath, qualityIndex, jpegQuality, p, applyCrop, isDiptych, 0, 0, 0, 0);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p,
                            boolean applyCrop, boolean isDiptych,
                            long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        processJpeg(originalPath, outDirPath, qualityIndex, jpegQuality, p, applyCrop, isDiptych,
                null, null, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p,
                            boolean applyCrop, boolean isDiptych, String lutPath, String lutName,
                            long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        new ProcessTask(qualityIndex, jpegQuality, p, outDirPath, applyCrop, isDiptych,
                lutPath, lutName,
                scannerStartedMs, detectedMs, stableMs, scannerAttempts).execute(originalPath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return mEngine.loadLut(new File(params[0]), params[1]);
        }
        @Override protected void onPostExecute(Boolean success) { mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIdx;
        private int jpegQuality;
        private RTLProfile p;
        private String outDir;
        private boolean applyCrop; // <-- NEW
        private boolean isDiptych;
        private String lutPath;
        private String lutName;
        private long stableMs;

        public ProcessTask(int q, int jpegQuality, RTLProfile p, String out, boolean crop, boolean isDiptych,
                           String lutPath, String lutName,
                           long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
            this.qualityIdx  = q;
            this.jpegQuality = jpegQuality;
            this.p           = p;
            this.outDir      = out;
            this.applyCrop   = crop; // <-- NEW
            this.isDiptych   = isDiptych;
            this.lutPath     = lutPath;
            this.lutName     = lutName;
            this.stableMs = stableMs;
        }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) {
                    DebugLog.write("PROC ERR: file not found: " + params[0]);
                    return "ERR";
                }

                DebugLog.write("PROC START: " + original.getName()
                        + "  size=" + original.length() + "b"
                        + "  lut=" + (lutPath != null ? lutPath : "none")
                        + "  crop=" + applyCrop);

                if (stableMs <= 0) {
                    long lastSize = -1; int timeout = 0;
                    while (timeout < 50) {
                        long currentSize = original.length();
                        if (currentSize > 0 && currentSize == lastSize) break;
                        lastSize = currentSize;
                        Thread.sleep(100); timeout++;
                    }
                }

                // --- .cam bundle path ---
                boolean camGrainLoaded = false;
                if (p != null && p.camFile != null) {
                    String camPath = new File(Filepaths.getRecipeDir(), p.camFile).getAbsolutePath();
                    LutEngine.CamLoadResult cam = mEngine.loadFromCam(camPath, mContext.getCacheDir());
                    DebugLog.write("CAM LOAD: file=" + p.camFile + " lut=" + cam.lutLoaded + " grain=" + cam.grainLoaded);
                    if (!cam.lutLoaded && p.opacity > 0) {
                        DebugLog.write("CAM LOAD FAILED: no LUT in bundle");
                        return "FAILED";
                    }
                    camGrainLoaded = cam.grainLoaded;
                } else {
                    // --- Normal loose-file path ---
                    if (lutPath != null || lutName != null) {
                        boolean lutOk = mEngine.loadLut(lutPath, lutName);
                        DebugLog.write("LUT LOAD: name=\"" + lutName + "\"  path=" + lutPath + "  ok=" + lutOk);
                        if (!lutOk) return "FAILED";
                    }
                }

                File dir = new File(outDir);
                if (!dir.exists()) dir.mkdirs();

                File outFile = new File(dir, original.getName());

                // 0=1/4 RES (4), 1=HALF RES (2), 2=FULL RES (1)
                int scale = (qualityIdx == 0) ? 4 : (qualityIdx == 2 ? 1 : 2);

                int finalJpegQuality = this.jpegQuality;
                // Still enforce safe limits for downscaled proxies to save RAM
                if (scale == 4) {
                    finalJpegQuality = Math.min(85, this.jpegQuality);
                } else if (scale == 2) {
                    finalJpegQuality = Math.min(90, this.jpegQuality);
                }

                // --- DIPTYCH COMPENSATOR ---
                // Safely steps down physical effects to account for the smaller 6MP canvas
                int finalGrainSize = p.grainSize;
                int finalBloom = p.bloom;
                
                if (isDiptych) {
                    finalGrainSize = Math.max(0, p.grainSize - 1);
                    
                    int[] bloomMap = {0, 5, 6, 1, 2, 3, 4};
                    int currentBloomIdx = 0;
                    for (int i = 0; i < bloomMap.length; i++) {
                        if (bloomMap[i] == p.bloom) currentBloomIdx = i;
                    }
                    finalBloom = bloomMap[Math.max(0, currentBloomIdx - 1)];
                }

                int cxxGrainEngine = p.advancedGrainExperimental;
                if (p.grain > 0) {
                    // Try SD-card texture if .cam bundle didn't supply one.
                    boolean grainTextureReady = camGrainLoaded;
                    if (!grainTextureReady) {
                        File texFile = MenuController.getGrainTextureFile(finalGrainSize);
                        grainTextureReady = mEngine.loadGrainTexture(texFile);
                    }
                    if (grainTextureReady) {
                        cxxGrainEngine = 2;  // texture-based grain
                    } else if (cxxGrainEngine == 2) {
                        // Texture mode requested but nothing loaded — fall back to algorithmic.
                        cxxGrainEngine = 0;
                        DebugLog.write("GRAIN: no texture loaded, falling back to algorithmic grain");
                    }
                }

                // Use the CPU Engine toggle from Settings (page 6) to decide thread count.
                RecipeManager rm = ((MainActivity) mContext).getRecipeManager();
                int numCores = rm.isMultiCoreEnabled() ? Runtime.getRuntime().availableProcessors() : 1;
                Log.d("JPEG.CAM", "Processing with " + numCores + " core(s). MultiCore=" + rm.isMultiCoreEnabled());

                boolean success = mEngine.applyLutToJpeg(
                    original.getAbsolutePath(), outFile.getAbsolutePath(),
                    scale, p.opacity, p.grain, finalGrainSize, p.vignette, p.rollOff,
                    p.colorChrome, p.chromeBlue, p.shadowToe, p.subtractiveSat,
                    p.halation, finalBloom,
                    cxxGrainEngine,
                    finalJpegQuality,
                    applyCrop, numCores);  // <--- ADDED numCores HERE
                if (success) {
                    DebugLog.write("PROC END: SAVED -> " + outFile.getName());
                    return "SAVED";
                }
                DebugLog.write("PROC END: FAILED (native returned false)");
            } catch (Exception e) {
                Log.e("COOKBOOK", "Java error: " + e.getMessage());
                DebugLog.write("PROC END: EXCEPTION " + e.getMessage());
            }
            return "FAILED";
        }

        @Override protected void onPostExecute(String result) { mCallback.onProcessFinished(result); }
    }
}
