package com.github.ma1co.pmcademo.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

public class RecipeManager {
    // --- VARIABLES ---
    private File recipeDir;
    private RTLProfile[] loadedProfiles = new RTLProfile[10];
    private int currentSlot = 0;

    private int qualityIndex = 1;
    private int multiCoreEnabled = 0; // 0 = SINGLE-CORE, 1 = MULTI-CORE
    private int prefC1 = 0;
    private int prefC2 = 0;
    private int prefC3 = 0;
    private int prefAel = 0;
    private int prefFn = 0;
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();
    private static final String[] LEGACY_GRAIN_NAMES = new String[] { "SMALL", "MED", "LARGE" };

    public RecipeManager() {
        recipeDir = new File(Filepaths.getAppDir(), "RECIPES");
        if (!recipeDir.exists()) recipeDir.mkdirs();

        DebugLog.writeStartupBanner();
        scanRecipes();
        loadPreferences();
        loadAllWorkspaces();
    }

    // --- MAINACTIVITY GETTERS & SETTERS ---
    public int getCurrentSlot() { return currentSlot; }

    public void setCurrentSlot(int slot) {
        this.currentSlot = (slot + 10) % 10;
        savePreferences();
    }

    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) {
        this.qualityIndex = (index + 3) % 3;
        savePreferences();
    }

    public boolean isMultiCoreEnabled() { return multiCoreEnabled == 1; }
    public void setMultiCoreEnabled(boolean enabled) {
        this.multiCoreEnabled = enabled ? 1 : 0;
        savePreferences();
    }

    public int getPrefC1() { return prefC1; }
    public void setPrefC1(int v) { prefC1 = v; savePreferences(); }
    public int getPrefC2() { return prefC2; }
    public void setPrefC2(int v) { prefC2 = v; savePreferences(); }
    public int getPrefC3() { return prefC3; }
    public void setPrefC3(int v) { prefC3 = v; savePreferences(); }
    public int getPrefAel() { return prefAel; }
    public void setPrefAel(int v) { prefAel = v; savePreferences(); }
    public int getPrefFn() { return prefFn; }
    public void setPrefFn(int v) { prefFn = v; savePreferences(); }

    public RTLProfile getCurrentProfile() { return loadedProfiles[currentSlot]; }
    public RTLProfile getProfile(int index) { return loadedProfiles[index]; }

    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    // --- SMART LUT SCANNER (Pretty Names + Long Filename Support) ---
    public void scanRecipes() {
        recipePaths.clear();
        recipeNames.clear();
        recipePaths.add("NONE");
        recipeNames.add("OFF");

        for (File root : Filepaths.getStorageRoots()) {
            File lutDir = new File(root, "JPEGCAM/LUTS");
            if (lutDir.exists() && lutDir.isDirectory()) {
                File[] files = lutDir.listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (File f : files) {
                        String name = f.getName();
                        String u = name.toUpperCase();

                        if (!u.startsWith(".") && !u.startsWith("_") &&
                            (u.endsWith(".CUB") || u.endsWith(".CUBE") || u.endsWith(".PNG"))) {

                            if (!recipePaths.contains(f.getAbsolutePath())) {
                                recipePaths.add(f.getAbsolutePath());

                                String prettyName = name.replaceAll("(?i)\\.(cube|cub|png)$", "");

                                if (u.endsWith(".CUBE") || u.endsWith(".CUB")) {
                                    try {
                                        BufferedReader br = new BufferedReader(new FileReader(f));
                                        String line;
                                        for (int j = 0; j < 10; j++) {
                                            line = br.readLine();
                                            if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                                String[] pts = line.split("\"");
                                                if (pts.length > 1) prettyName = pts[1];
                                                break;
                                            }
                                        }
                                        br.close();
                                    } catch (Exception e) {}
                                }
                                recipeNames.add(prettyName);
                            }
                        }
                    }
                }
            }
        }

        // Log what was found so users can share DEBUG.TXT when reporting LUT issues
        DebugLog.write("SCAN LUTS: found " + (recipePaths.size() - 1) + " file(s)");
        for (int i = 1; i < recipePaths.size(); i++) {
            DebugLog.write("  [" + i + "] name=\"" + recipeNames.get(i) + "\"  path=" + recipePaths.get(i));
        }
    }

    // --- WORKSPACE MANAGEMENT ---
    private void loadAllWorkspaces() {
        for (int i = 0; i < 10; i++) {
            String filename = String.format("R_SLOT%02d.TXT", i + 1);
            loadedProfiles[i] = loadProfileFromFile(filename, i);
        }
    }

    private RTLProfile loadProfileFromFile(String filename, int arrayIndex) {
        File file = new File(recipeDir, filename);

        RTLProfile p = new RTLProfile(arrayIndex);
        if (!file.exists()) {
            p.profileName = "SLOT " + (arrayIndex + 1);
            p.advMatrix = new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100};
            saveProfileToFile(file, p);
            return p;
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            p.profileName = json.optString("profileName", "RECIPE");
            p.camFile     = json.optString("camFile", null);   // null = normal loose-file mode
            String loadedLutName = json.optString("lutName", "OFF");
            p.lutIndex = recipeNames.indexOf(loadedLutName);
            if (p.lutIndex == -1) p.lutIndex = 0;
            p.opacity         = json.optInt("lutOpacity", 100);
            p.shadowToe       = json.optInt("shadowToe", 0);
            p.rollOff         = json.optInt("rollOff", 0);
            p.colorChrome     = json.optInt("colorChrome", 0);
            p.chromeBlue      = json.optInt("chromeBlue", 0);
            p.subtractiveSat  = json.optInt("subtractiveSat", 0);
            p.halation        = json.optInt("halation", 0);
            p.vignette        = json.optInt("vignette", 0);
            p.grain           = json.optInt("grain", 0);
            String loadedGrainName = json.optString("grainName", "NONE");
            p.grainSize = resolveGrainIndex(loadedGrainName, json.optInt("grainSize", 0));

            p.advancedGrainExperimental = json.optInt("advancedGrainExperimental", 0);
            p.bloom           = json.optInt("bloom", 0);
            p.contrast        = json.optInt("contrast", 0);
            p.saturation      = json.optInt("saturation", 0);
            p.wbShift         = json.optInt("wbShift", 0);
            p.wbShiftGM       = json.optInt("wbShiftGM", 0);
            p.colorMode       = json.optString("colorMode", "Standard");
            p.whiteBalance    = json.optString("whiteBalance", "Auto");
            p.shadingRed      = json.optInt("shadingRed", 0);
            p.shadingBlue     = json.optInt("shadingBlue", 0);
            p.colorDepthRed   = json.optInt("colorDepthRed", 0);
            p.colorDepthGreen = json.optInt("colorDepthGreen", 0);
            p.colorDepthBlue  = json.optInt("colorDepthBlue", 0);
            p.colorDepthCyan  = json.optInt("colorDepthCyan", 0);
            p.colorDepthMagenta = json.optInt("colorDepthMagenta", 0);
            p.colorDepthYellow  = json.optInt("colorDepthYellow", 0);
            p.dro             = json.optString("dro", "OFF");
            p.pictureEffect   = json.optString("pictureEffect", "off");
            p.proColorMode    = json.optString("proColorMode", "off");
            p.sharpness       = json.optInt("sharpness", 0);
            p.sharpnessGain   = json.optInt("sharpnessGain", 0);
            p.vignetteHardware = json.optInt("vignetteHardware", 0);
            JSONArray arr = json.optJSONArray("advMatrix");
            if (arr != null && arr.length() == 9) {
                for (int i = 0; i < 9; i++) p.advMatrix[i] = arr.getInt(i);
            }
        } catch (Exception e) {
            p.profileName = "ERROR";
        }
        return p;
    }

    private void saveProfileToFile(File file, RTLProfile p) {
        try {
            String lutNameToSave = (p.lutIndex >= 0 && p.lutIndex < recipeNames.size()) ? recipeNames.get(p.lutIndex) : "OFF";
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"profileName\": \"").append(p.profileName.replace("\"", "\\\"")).append("\",\n");
            if (p.camFile != null) {
                sb.append("  \"camFile\": \"").append(p.camFile.replace("\"", "\\\"")).append("\",\n");
            }
            sb.append("  \"lutName\": \"").append(lutNameToSave.replace("\"", "\\\"")).append("\",\n");
            sb.append("  \"lutOpacity\": ").append(p.opacity).append(",\n");
            sb.append("  \"shadowToe\": ").append(p.shadowToe).append(",\n");
            sb.append("  \"rollOff\": ").append(p.rollOff).append(",\n");
            sb.append("  \"colorChrome\": ").append(p.colorChrome).append(",\n");
            sb.append("  \"chromeBlue\": ").append(p.chromeBlue).append(",\n");
            sb.append("  \"subtractiveSat\": ").append(p.subtractiveSat).append(",\n");
            sb.append("  \"halation\": ").append(p.halation).append(",\n");
            sb.append("  \"vignette\": ").append(p.vignette).append(",\n");
            sb.append("  \"grain\": ").append(p.grain).append(",\n");
            sb.append("  \"grainSize\": ").append(p.grainSize).append(",\n");

            String grainNameToSave = "NONE";
            if (p.grain > 0) {
                File grainFile = MenuController.getGrainTextureFile(p.grainSize);
                if (grainFile != null) {
                    grainNameToSave = getFileStem(grainFile.getName());
                } else {
                    List<String> grainOptions = java.util.Arrays.asList(MenuController.getGrainEngineOptions());
                    if (p.grainSize >= 0 && p.grainSize < grainOptions.size()) {
                        grainNameToSave = grainOptions.get(p.grainSize);
                    }
                }
            }
            sb.append("  \"grainName\": \"").append(grainNameToSave.replace("\"", "\\\"")).append("\",\n");
            sb.append("  \"advancedGrainExperimental\": ").append(p.advancedGrainExperimental).append(",\n");

            sb.append("  \"bloom\": ").append(p.bloom).append(",\n");
            sb.append("  \"contrast\": ").append(p.contrast).append(",\n");
            sb.append("  \"saturation\": ").append(p.saturation).append(",\n");
            sb.append("  \"wbShift\": ").append(p.wbShift).append(",\n");
            sb.append("  \"wbShiftGM\": ").append(p.wbShiftGM).append(",\n");
            sb.append("  \"colorMode\": \"").append(p.colorMode).append("\",\n");
            sb.append("  \"whiteBalance\": \"").append(p.whiteBalance).append("\",\n");
            sb.append("  \"shadingRed\": ").append(p.shadingRed).append(",\n");
            sb.append("  \"shadingBlue\": ").append(p.shadingBlue).append(",\n");
            sb.append("  \"colorDepthRed\": ").append(p.colorDepthRed).append(",\n");
            sb.append("  \"colorDepthGreen\": ").append(p.colorDepthGreen).append(",\n");
            sb.append("  \"colorDepthBlue\": ").append(p.colorDepthBlue).append(",\n");
            sb.append("  \"colorDepthCyan\": ").append(p.colorDepthCyan).append(",\n");
            sb.append("  \"colorDepthMagenta\": ").append(p.colorDepthMagenta).append(",\n");
            sb.append("  \"colorDepthYellow\": ").append(p.colorDepthYellow).append(",\n");
            sb.append("  \"advMatrix\": [");
            for (int i = 0; i < 9; i++) sb.append(p.advMatrix[i]).append(i < 8 ? "," : "");
            sb.append("],\n");
            sb.append("  \"dro\": \"").append(p.dro).append("\",\n");
            sb.append("  \"pictureEffect\": \"").append(p.pictureEffect).append("\",\n");
            sb.append("  \"proColorMode\": \"").append(p.proColorMode).append("\",\n");
            sb.append("  \"sharpness\": ").append(p.sharpness).append(",\n");
            sb.append("  \"sharpnessGain\": ").append(p.sharpnessGain).append(",\n");
            sb.append("  \"vignetteHardware\": ").append(p.vignetteHardware).append("\n");
            sb.append("}");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {}
    }

    public void loadPreferences() {
        File prefsFile = new File(recipeDir, "PREFS.TXT");
        if (prefsFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(prefsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("multicore=")) multiCoreEnabled = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c1=")) prefC1 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c2=")) prefC2 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c3=")) prefC3 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("ael=")) prefAel = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("fn=")) prefFn = Integer.parseInt(line.split("=")[1]);
                }
                br.close();
            } catch (Exception e) {}
        }
    }

    private static String normalizeGrainKey(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase();
        int dot = normalized.lastIndexOf('.');
        if (dot > 0) normalized = normalized.substring(0, dot);
        normalized = normalized.replace('_', ' ');
        while (normalized.indexOf("  ") != -1) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized;
    }

    private static String getFileStem(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int findGrainIndexByName(String grainName) {
        String target = normalizeGrainKey(grainName);
        if (target.length() == 0 || "NONE".equals(target)) return -1;

        String[] grainOptions = MenuController.getGrainEngineOptions();
        for (int i = 0; i < grainOptions.length; i++) {
            if (target.equals(normalizeGrainKey(grainOptions[i]))) {
                return i;
            }

            File grainFile = MenuController.getGrainTextureFile(i);
            if (grainFile != null && target.equals(normalizeGrainKey(getFileStem(grainFile.getName())))) {
                return i;
            }
        }
        return -1;
    }

    private static int resolveLegacyGrainIndex(int legacySize) {
        int safeIndex = legacySize;
        if (safeIndex < 0) safeIndex = 0;
        if (safeIndex >= LEGACY_GRAIN_NAMES.length) safeIndex = LEGACY_GRAIN_NAMES.length - 1;

        int resolved = findGrainIndexByName(LEGACY_GRAIN_NAMES[safeIndex]);
        return resolved >= 0 ? resolved : 0;
    }

    private static int resolveGrainIndex(String loadedGrainName, int legacySize) {
        int resolved = findGrainIndexByName(loadedGrainName);
        if (resolved >= 0) return resolved;
        return resolveLegacyGrainIndex(legacySize);
    }

    public void savePreferences() {
        try {
            File prefsFile = new File(recipeDir, "PREFS.TXT");
            FileOutputStream fos = new FileOutputStream(prefsFile);
            String prefsData = "quality=" + qualityIndex + "\nslot=" + currentSlot + "\n" +
                               "multicore=" + multiCoreEnabled + "\n" +
                               "c1=" + prefC1 + "\nc2=" + prefC2 + "\nc3=" + prefC3 + "\n" +
                               "ael=" + prefAel + "\nfn=" + prefFn + "\n";
            fos.write(prefsData.getBytes());
            fos.close();

            if (loadedProfiles[currentSlot] != null) {
                String filename = String.format("R_SLOT%02d.TXT", currentSlot + 1);
                File file = new File(recipeDir, filename);
                saveProfileToFile(file, loadedProfiles[currentSlot]);
            }
        } catch (Exception e) {}
    }

    // --- VAULT DATA STRUCTURE ---
    public static class VaultItem {
        public String filename;
        public String profileName;
        public VaultItem(String fn, String pn) { filename = fn; profileName = pn; }
    }
    private List<VaultItem> vaultItems = new ArrayList<VaultItem>();

    public void scanVault() {
        vaultItems.clear();
        File[] all = recipeDir.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName().toUpperCase();

                // Regular vault recipe (.TXT, not a slot file or prefs)
                if (n.endsWith(".TXT") && !n.startsWith("R_SLOT") && !n.equals("PREFS.TXT")) {
                    String pName = n.replace(".TXT", "");
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(f));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.contains("\"profileName\"")) {
                                String[] parts = line.split("\"");
                                if (parts.length >= 4) pName = parts[3];
                                break;
                            }
                        }
                        br.close();
                    } catch (Exception e) {}
                    vaultItems.add(new VaultItem(f.getName(), pName));
                }

                // .cam bundle — display name comes from recipe.json inside the ZIP
                if (n.endsWith(".CAM")) {
                    String bundleName = f.getName().replaceAll("(?i)\\.cam$", "");
                    try {
                        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
                        java.util.zip.ZipEntry je = zf.getEntry("recipe.json");
                        if (je != null) {
                            java.io.InputStream is = zf.getInputStream(je);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[2048]; int read;
                            while ((read = is.read(buf)) != -1) baos.write(buf, 0, read);
                            is.close();
                            org.json.JSONObject json = new org.json.JSONObject(baos.toString("UTF-8"));
                            bundleName = json.optString("recipeName", bundleName);
                        }
                        zf.close();
                    } catch (Exception e) {
                        DebugLog.write("scanVault .cam read error: " + e.getMessage());
                    }
                    // VaultItem filename is the original .cam filename; prefix marks it as a bundle.
                    vaultItems.add(new VaultItem(f.getName(), "[CAM] " + bundleName));
                }
            }
        }
        if (vaultItems.isEmpty()) vaultItems.add(new VaultItem("NONE", "NO VAULT RECIPES"));
    }

    /**
     * Loads a .cam bundle into the current slot.
     * Reads recipe.json from inside the ZIP, applies all settings to the slot,
     * and stores the .cam filename so processing knows to load LUT/grain from the bundle.
     */
    public void loadCamIntoSlot(String camFilename) {
        try {
            File camFile = new File(recipeDir, camFilename);
            if (!camFile.exists()) {
                DebugLog.write("loadCamIntoSlot: file not found: " + camFilename);
                return;
            }

            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(camFile);
            java.util.zip.ZipEntry je = zf.getEntry("recipe.json");
            if (je == null) {
                zf.close();
                DebugLog.write("loadCamIntoSlot: no recipe.json in " + camFilename);
                return;
            }
            java.io.InputStream is = zf.getInputStream(je);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int read;
            while ((read = is.read(buf)) != -1) baos.write(buf, 0, read);
            is.close(); zf.close();

            org.json.JSONObject json = new org.json.JSONObject(baos.toString("UTF-8"));

            // Build a profile from the bundle's recipe.json
            RTLProfile p = new RTLProfile(currentSlot);
            p.camFile         = camFilename;
            p.profileName     = json.optString("recipeName", json.optString("profileName", camFilename));
            p.opacity         = json.optInt("lutOpacity",  json.optInt("opacity", 100));
            p.shadowToe       = json.optInt("shadowToe", 0);
            p.rollOff         = json.optInt("rollOff", 0);
            p.colorChrome     = json.optInt("colorChrome", 0);
            p.chromeBlue      = json.optInt("chromeBlue", 0);
            p.subtractiveSat  = json.optInt("subtractiveSat", 0);
            p.halation        = json.optInt("halation", 0);
            p.vignette        = json.optInt("vignette", 0);
            p.grain           = json.optInt("grain", 0);
            p.grainSize       = json.optInt("grainSize", 1);
            p.advancedGrainExperimental = json.optInt("advancedGrainExperimental", 0);
            p.bloom           = json.optInt("bloom", 0);
            p.contrast        = json.optInt("contrast", 0);
            p.saturation      = json.optInt("saturation", 0);
            p.wbShift         = json.optInt("wbShift", 0);
            p.wbShiftGM       = json.optInt("wbShiftGM", 0);
            p.colorMode       = json.optString("colorMode", "Standard");
            p.whiteBalance    = json.optString("whiteBalance", "Auto");
            p.shadingRed      = json.optInt("shadingRed", 0);
            p.shadingBlue     = json.optInt("shadingBlue", 0);
            p.colorDepthRed   = json.optInt("colorDepthRed", 0);
            p.colorDepthGreen = json.optInt("colorDepthGreen", 0);
            p.colorDepthBlue  = json.optInt("colorDepthBlue", 0);
            p.colorDepthCyan  = json.optInt("colorDepthCyan", 0);
            p.colorDepthMagenta = json.optInt("colorDepthMagenta", 0);
            p.colorDepthYellow  = json.optInt("colorDepthYellow", 0);
            p.dro             = json.optString("dro", "OFF");
            p.pictureEffect   = json.optString("pictureEffect", "off");
            p.proColorMode    = json.optString("proColorMode", "off");
            p.sharpness       = json.optInt("sharpness", 0);
            p.sharpnessGain   = json.optInt("sharpnessGain", 0);
            p.vignetteHardware= json.optInt("vignetteHardware", 0);
            org.json.JSONArray arr = json.optJSONArray("advMatrix");
            if (arr != null && arr.length() == 9) {
                for (int i = 0; i < 9; i++) p.advMatrix[i] = arr.getInt(i);
            }
            // lutIndex stays 0 (OFF) — LUT comes from the bundle, not loose files.
            // Read display names so the menu can show something meaningful.
            p.bundledLutName   = json.optString("lutName", null);
            p.bundledGrainName = json.optString("grainName", null);

            loadedProfiles[currentSlot] = p;
            savePreferences();
            DebugLog.write("loadCamIntoSlot: loaded \"" + p.profileName + "\" into slot " + (currentSlot + 1));

        } catch (Exception e) {
            DebugLog.write("loadCamIntoSlot error: " + e.getMessage());
        }
    }

    public List<VaultItem> getVaultItems() {
        if (vaultItems.isEmpty()) scanVault();
        return vaultItems;
    }

    public void deleteVaultItem(int index) {
        if (index >= 0 && index < vaultItems.size()) {
            VaultItem item = vaultItems.get(index);
            if (!item.filename.equals("NONE")) {
                File file = new File(recipeDir, item.filename);
                if (file.exists()) file.delete();
                scanVault();
            }
        }
    }

    public void previewVaultToSlot(String vaultFilename) {
        if (vaultFilename.equals("NONE") || vaultFilename.equals("NO VAULT RECIPES")) return;
        if (vaultFilename.toUpperCase().endsWith(".CAM")) {
            loadCamIntoSlot(vaultFilename);   // bundle: reads recipe.json + loads LUT/grain in-memory
        } else {
            loadedProfiles[currentSlot] = loadProfileFromFile(vaultFilename, currentSlot);
        }
    }

    public void resetCurrentSlot() {
        RTLProfile blank = new RTLProfile(currentSlot);
        blank.profileName = "SLOT " + (currentSlot + 1);
        loadedProfiles[currentSlot] = blank;
        savePreferences();
    }

    public void saveSlotToVault(String newPrettyName) {
        String targetFile = null;
        for (VaultItem item : getVaultItems()) {
            if (item.profileName.equalsIgnoreCase(newPrettyName)) {
                targetFile = item.filename;
                break;
            }
        }
        if (targetFile == null) {
            String base = newPrettyName.replaceAll("[^A-Z0-9]", "").toUpperCase();
            if (base.length() > 6) base = base.substring(0, 6);
            if (base.isEmpty()) base = "RECIPE";
            int count = 1;
            do {
                targetFile = base + String.format("%02d", count++) + ".TXT";
            } while (new File(recipeDir, targetFile).exists() && count < 100);
        }

        RTLProfile p = loadedProfiles[currentSlot];
        p.profileName = newPrettyName;
        saveProfileToFile(new File(recipeDir, targetFile), p);
        scanVault();
    }
}
