package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.*;

public class ImageProcessor {
    public interface ProcessorCallback {
        void onPreloadStarted(); void onPreloadFinished(boolean success);
        void onProcessStarted(); void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String path, String name) { new PreloadLutTask().execute(path); }
    public void processJpeg(String in, String out, int q, RTLProfile p) { new ProcessTask(in, out, q, p).execute(); }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected Boolean doInBackground(String... params) { return LutEngine.loadLut(params[0]); }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath, outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String in, String out, int q, RTLProfile p) {
            this.inPath = in; this.outDir = out; this.qualityIndex = q; this.profile = p;
        }

        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onProcessStarted(); }

        @Override protected String doInBackground(Void... voids) {
            Log.d("filmOS", "STARTING GHOST RIP...");
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            // Unique 8.3 filename
            String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
            String finalOutPath = new File(dir, "F" + timeTag.substring(timeTag.length()-7) + ".JPG").getAbsolutePath();
            
            String fileToProcess = inPath;
            int scaleDenom = (qualityIndex == 0) ? 4 : (qualityIndex == 1) ? 2 : 1;
            boolean usedTemp = false;

            // PROXY MODE: Manual Byte Rip (The "Accident" Reclaimed)
            if (qualityIndex == 0) {
                try {
                    File tempFile = new File(outDir, "TEMP.JPG");
                    if (ripSonyPreview(new File(inPath), tempFile)) {
                        fileToProcess = tempFile.getAbsolutePath();
                        scaleDenom = 1; // It's already the right size
                        usedTemp = true;
                        Log.d("filmOS", "Ghost Rip Success (1.6MP)");
                    }
                } catch (Exception e) { Log.e("filmOS", "Ghost Rip Fail: " + e.getMessage()); }
            }

            // Sony FUSE Pre-create (Write 1 byte to lock it in)
            try { FileOutputStream fos = new FileOutputStream(finalOutPath); fos.write(1); fos.close(); } catch (Exception e) {}

            Log.d("filmOS", "Handing off to Native Engine...");
            boolean success = LutEngine.processImageNative(
                    fileToProcess, finalOutPath, scaleDenom,
                    profile.opacity, profile.grain, profile.grainSize,
                    profile.vignette, profile.rollOff
            );

            if (usedTemp) new File(fileToProcess).delete();

            if (success) {
                Log.d("filmOS", "SUCCESS: " + finalOutPath);
                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                return finalOutPath;
            }
            return null;
        }

        // The "Ghost Rip" logic: Scans the header for the high-res Sony Preview JPEG
        private boolean ripSonyPreview(File source, File dest) throws IOException {
            RandomAccessFile raf = new RandomAccessFile(source, "r");
            byte[] buffer = new byte[65536]; // Scan first 64KB
            raf.read(buffer);
            
            int start = -1;
            // Skip the first FF D8 (Main image) and find the second one (Preview image)
            int foundCount = 0;
            for (int i = 0; i < buffer.length - 1; i++) {
                if ((buffer[i] & 0xFF) == 0xFF && (buffer[i+1] & 0xFF) == 0xD8) {
                    foundCount++;
                    if (foundCount == 2) { // Sony Preview usually starts at the 2nd SOI marker
                        start = i;
                        break;
                    }
                }
            }

            if (start != -1) {
                raf.seek(start);
                FileOutputStream out = new FileOutputStream(dest);
                byte[] copyBuf = new byte[1024 * 1024]; // 1MB chunks
                int bytesRead;
                while ((bytesRead = raf.read(copyBuf)) != -1) {
                    out.write(copyBuf, 0, bytesRead);
                }
                out.close();
                raf.close();
                return true;
            }
            raf.close();
            return false;
        }

        @Override protected void onPostExecute(String res) { if (mCallback != null) mCallback.onProcessFinished(res); }
    }
}