package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static {
        System.loadLibrary("native-lib");
    }

    public static native boolean loadLutNative(String lutPath);
    
    // FIX: Changed from private to public so ImageProcessor can call it
    public static native boolean processImageNative(
        String inPath, 
        String outPath, 
        int scaleDenom, 
        int opacity, 
        int grain, 
        int grainSize, 
        int vignette, 
        int rolloff
    );

    // FIX: Changed parameter from File to String to match ImageProcessor
    public static boolean loadLut(String lutPath) {
        if (lutPath == null || lutPath.isEmpty() || lutPath.equals("NONE")) {
            return false;
        }
        File lutFile = new File(lutPath);
        if (!lutFile.exists()) {
            return false;
        }
        return loadLutNative(lutFile.getAbsolutePath());
    }
}