package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class LutEngine {

    // Load the C++ Jet Engine when the app starts
    static {
        System.loadLibrary("native-lib");
    }

    private int lutSize = 0;
    private String currentLutName = "";
    
    private int[] lutR;
    private int[] lutG;
    private int[] lutB;

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // THE BRIDGE: Tells Java that C++ will handle this function
    private native void applyLutNative(Bitmap bitmap, int[] lutR, int[] lutG, int[] lutB, int lutSize);

    public String getCurrentLutName() {
        return currentLutName;
    }

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName) && lutR != null) return true;

        try {
            BufferedReader br = new BufferedReader(new FileReader(cubeFile));
            String line;
            int idx = 0;
            int expectedColors = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("LUT_3D_SIZE")) {
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        lutSize = Integer.parseInt(parts[parts.length - 1]);
                        expectedColors = lutSize * lutSize * lutSize;
                        lutR = new int[expectedColors];
                        lutG = new int[expectedColors];
                        lutB = new int[expectedColors];
                    }
                    continue;
                }
                
                char firstChar = line.charAt(0);
                if (firstChar < '0' || firstChar > '9') continue;

                if (idx < expectedColors) {
                    // EXTREME SPEED PARSER: No Regex, No String Arrays
                    float[] vals = new float[3];
                    int valIdx = 0;
                    int start = 0;
                    int len = line.length();
                    
                    while (start < len && valIdx < 3) {
                        while (start < len && line.charAt(start) == ' ') start++;
                        if (start >= len) break;
                        int end = start;
                        while (end < len && line.charAt(end) != ' ') end++;
                        vals[valIdx++] = Float.parseFloat(line.substring(start, end));
                        start = end;
                    }
                    
                    if (valIdx == 3) {
                        lutR[idx] = (int) Math.max(0, Math.min(255, vals[0] * 255.0f));
                        lutG[idx] = (int) Math.max(0, Math.min(255, vals[1] * 255.0f));
                        lutB[idx] = (int) Math.max(0, Math.min(255, vals[2] * 255.0f));
                        idx++;
                    }
                }
            }
            br.close();

            while (idx > 0 && idx < expectedColors) {
                lutR[idx] = lutR[idx - 1];
                lutG[idx] = lutG[idx - 1];
                lutB[idx] = lutB[idx - 1];
                idx++;
            }

            currentLutName = lutName;
            return true;
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void applyLutToBitmap(Bitmap bitmap, ProgressCallback callback) {
        if (lutR == null || lutSize == 0 || bitmap == null) return;

        // BOOM. The entire nested loop is replaced by one C++ call.
        applyLutNative(bitmap, lutR, lutG, lutB, lutSize);
        
        if (callback != null) {
            // Since it happens instantly, we just report 100% done
            callback.onProgress(100);
        }
    }
}