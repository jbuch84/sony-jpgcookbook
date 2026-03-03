package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {

    static {
        System.loadLibrary("native-lib");
    }

    private String currentLutName = "";

    private native boolean loadLutNative(String filePath);
    private native byte[] processImageNative(byte[] jpegData);

    public String getCurrentLutName() {
        return currentLutName;
    }

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName)) return true;

        if (loadLutNative(cubeFile.getAbsolutePath())) {
            currentLutName = lutName;
            return true;
        }
        return false;
    }

    public byte[] applyLutToJpeg(byte[] rawJpegData) {
        if (rawJpegData == null || rawJpegData.length == 0) return null;
        return processImageNative(rawJpegData);
    }
}