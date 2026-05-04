package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    private String currentGrainTexturePath = "";

    // Result returned by loadFromCam() so ImageProcessor knows what was loaded.
    public static class CamLoadResult {
        public boolean lutLoaded   = false;
        public boolean grainLoaded = false;
    }

    // Cache key for .cam loading: "<path>|<fileSize>" — avoids re-opening the ZIP
    // on every shot when the same .cam is in use.
    private String currentCamKey        = "";
    private boolean lastCamLutLoaded    = false;
    private boolean lastCamGrainLoaded  = false;

    private native boolean loadLutNative(String filePath);
    private native boolean loadGrainTextureNative(String filePath);

    // Signature matches C++ exactly: 16 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity,
        int grain, int grainSize, int vignette, int rollOff,
        int colorChrome, int chromeBlue, int shadowToe,
        int subtractiveSat, int halation, int bloom,
        int advancedGrainExperimental, int jpegQuality,
        boolean applyCrop, int numCores
    );

    /**
     * Loads either a .cube/.cub or .png HaldCLUT from the SD card.
     */
    public boolean loadLut(File lutFile, String lutName) {
        String safeName = lutName != null ? lutName : "OFF";
        String path = lutFile != null ? lutFile.getAbsolutePath() : "";
        boolean noLut = "OFF".equalsIgnoreCase(safeName) || "NONE".equalsIgnoreCase(path) || path.length() == 0;

        if (noLut) {
            if ("OFF".equals(currentLutName)) return true;
            loadLutNative("");
            currentLutName = "OFF";
            return true;
        }

        String lutKey = safeName + "|" + path;
        if (lutKey.equals(currentLutName)) return true;
        if (loadLutNative(path)) {
            currentLutName = lutKey;
            return true;
        }
        currentLutName = "";
        return false;
    }

    public boolean loadLut(String lutPath, String lutName) {
        String safePath = lutPath != null ? lutPath : "";
        File lutFile = safePath.length() > 0 && !"NONE".equalsIgnoreCase(safePath) ? new File(safePath) : null;
        return loadLut(lutFile, lutName);
    }

    public boolean applyLutToJpeg(String in, String out, int scale, int opacity,
                                  int grain, int grainSize, int vignette, int rollOff,
                                  int colorChrome, int chromeBlue, int shadowToe,
                                  int subtractiveSat, int halation, int bloom,
                                  int advancedGrainExperimental,
                                  int quality,
                                  boolean applyCrop, int numCores) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette,
                                 rollOff, colorChrome, chromeBlue, shadowToe,
                                 subtractiveSat, halation, bloom,
                                 advancedGrainExperimental, quality,
                                 applyCrop, numCores);
    }

    // Public wrapper to load the grain texture safely (loose-file path).
    public boolean loadGrainTexture(File texFile) {
        if (texFile == null || !texFile.exists()) return false;
        String texPath = texFile.getAbsolutePath();
        if (texPath.equals(currentGrainTexturePath)) return true;
        if (loadGrainTextureNative(texPath)) {
            currentGrainTexturePath = texPath;
            return true;
        }
        return false;
    }

    /**
     * Opens a .cam bundle (renamed ZIP) and loads its LUT and grain into native
     * memory via the same file-based loaders used for loose SD card assets.
     * Assets are extracted to temp files in cacheDir, loaded, then deleted.
     *
     * Expected ZIP structure:
     *   recipe.json          declares "lutEntry" and "grainEntry" filenames
     *   lut.cube / lut.png   the LUT (named by lutEntry)
     *   grain.png            the grain texture (named by grainEntry, optional)
     *
     * Cached by camPath + file size so the ZIP is only opened once per recipe switch.
     */
    public CamLoadResult loadFromCam(String camPath, File cacheDir) {
        CamLoadResult result = new CamLoadResult();
        java.io.File camFile = new java.io.File(camPath);
        if (!camFile.exists()) {
            DebugLog.write("CAM: file not found: " + camPath);
            return result;
        }

        // Cache check — if same .cam and same size, native already has it loaded.
        String cacheKey = camPath + "|" + camFile.length();
        if (cacheKey.equals(currentCamKey)) {
            result.lutLoaded   = lastCamLutLoaded;
            result.grainLoaded = lastCamGrainLoaded;
            return result;
        }

        String lutEntry   = null;
        String grainEntry = null;
        File tempLutFile   = null;
        File tempGrainFile = null;

        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(camFile);

            // 1. Read recipe.json to discover which entries hold the LUT and grain.
            java.util.zip.ZipEntry je = zf.getEntry("recipe.json");
            if (je != null) {
                byte[] jsonBytes = readZipEntry(zf, je);
                try {
                    org.json.JSONObject json = new org.json.JSONObject(new String(jsonBytes, "UTF-8"));
                    lutEntry   = json.optString("lutEntry",   null);
                    grainEntry = json.optString("grainEntry", null);
                } catch (Exception e) {
                    DebugLog.write("CAM: recipe.json parse error: " + e.getMessage());
                }
            } else {
                DebugLog.write("CAM: no recipe.json in bundle");
            }

            // 2. Extract LUT to temp file, load via the same native loader as loose files.
            if (lutEntry != null) {
                java.util.zip.ZipEntry le = zf.getEntry(lutEntry);
                if (le != null) {
                    String ext = lutEntry.contains(".") ? lutEntry.substring(lutEntry.lastIndexOf('.')) : ".cube";
                    tempLutFile = new File(cacheDir, "cam_lut_tmp" + ext);
                    writeBytesToFile(tempLutFile, readZipEntry(zf, le));
                    result.lutLoaded = loadLutNative(tempLutFile.getAbsolutePath());
                    // Invalidate the Java LUT cache so a subsequent loose-file load works correctly.
                    currentLutName = "";
                } else {
                    DebugLog.write("CAM: lutEntry \"" + lutEntry + "\" not found in ZIP");
                }
            }

            // 3. Extract grain to temp file, load via the same native loader as loose files (optional).
            if (grainEntry != null) {
                java.util.zip.ZipEntry ge = zf.getEntry(grainEntry);
                if (ge != null) {
                    tempGrainFile = new File(cacheDir, "cam_grain_tmp.png");
                    writeBytesToFile(tempGrainFile, readZipEntry(zf, ge));
                    result.grainLoaded = loadGrainTextureNative(tempGrainFile.getAbsolutePath());
                    // Invalidate the Java grain cache so a subsequent loose-file load works correctly.
                    currentGrainTexturePath = "";
                } else {
                    DebugLog.write("CAM: grainEntry \"" + grainEntry + "\" not found in ZIP");
                }
            }

            zf.close();

        } catch (Exception e) {
            DebugLog.write("CAM: ZIP open failed: " + e.getMessage());
            return result;
        } finally {
            // Always remove temp files — they were only needed for the native load call.
            if (tempLutFile != null) tempLutFile.delete();
            if (tempGrainFile != null) tempGrainFile.delete();
        }

        // Update cache.
        currentCamKey      = cacheKey;
        lastCamLutLoaded   = result.lutLoaded;
        lastCamGrainLoaded = result.grainLoaded;

        DebugLog.write("CAM: loaded \"" + camPath + "\" lut=" + result.lutLoaded + " grain=" + result.grainLoaded);
        return result;
    }

    /** Reads all bytes from a ZipEntry using random-access ZipFile. */
    private static byte[] readZipEntry(java.util.zip.ZipFile zf, java.util.zip.ZipEntry entry)
            throws java.io.IOException {
        java.io.InputStream is = zf.getInputStream(entry);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    /** Writes a byte array to a file. */
    private static void writeBytesToFile(File f, byte[] data) throws java.io.IOException {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        try { fos.write(data); } finally { fos.close(); }
    }
}
