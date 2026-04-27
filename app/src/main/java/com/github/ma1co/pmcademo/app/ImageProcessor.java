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
        private boolean applyCrop;
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
            this.applyCrop   = crop;
            this.isDiptych   = isDiptych;
            this.lutPath     = lutPath;
            this.lutName     = lutName;
            this.stableMs = stableMs;
        }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                if (stableMs <= 0) {
                    long lastSize = -1; int timeout = 0;
                    while (timeout < 50) {
                        long currentSize = original.length();
                        if (currentSize > 0 && currentSize == lastSize) break;
                        lastSize = currentSize;
                        Thread.sleep(100); timeout++;
                    }
                }

                if (lutPath != null || lutName != null) {
                    if (!mEngine.loadLut(lutPath, lutName)) return "FAILED";
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
                    File texFile = MenuController.getGrainTextureFile(finalGrainSize);
                    if (mEngine.loadGrainTexture(texFile)) {
                        cxxGrainEngine = 2;
                    }
                }

                // Use the CPU Engine toggle from Settings (page 6) to decide thread count.
                // SINGLE-CORE = 1 worker (safer on older BIONZ hardware, avoids thread scheduling overhead).
                // MULTI-CORE  = all available processors (faster on cameras that handle it well).
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
                    apply