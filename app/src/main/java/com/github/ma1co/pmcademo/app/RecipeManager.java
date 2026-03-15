package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * filmOS Manager: Recipe & Persistence
 * Extracted from MainActivity to manage LUT recipes and SD-card backups.
 */
public class RecipeManager {
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0;
    private int qualityIndex = 1;
    
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    public RecipeManager() {
        for (int i = 0; i < 10; i++) {
            profiles[i] = new RTLProfile(i); // Passes index to set default "RECIPE X"
        }
        scanRecipes();
    }

    // --- GETTERS & SETTERS ---
    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = (slot + 10) % 10; }
    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) { this.qualityIndex = (index + 3) % 3; }
    public RTLProfile getCurrentProfile() { return profiles[currentSlot]; }
    public RTLProfile getProfile(int index) { return profiles[index]; }
    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    // --- CORE LOGIC ---
    
    public void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear(); 
        recipePaths.add("NONE"); 
        recipeNames.add("NONE"); 
        
        File lutDir = getLutDir();
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) {
                String rawName = f.getName();
                String u = rawName.toUpperCase();
                
                // 1. Ignore Mac hidden dot-files (._MyLUT.cube)
                if (rawName.startsWith(".")) continue;

                // 2. Relaxed file size limit to 2KB (2048 bytes) to catch smaller LUTs!
                if (f.length() > 2048 && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) {
                    recipePaths.add(f.getAbsolutePath());
                    
                    // Scrub the extensions
                    String prettyName = u.replace(".CUB", "").replace(".CUBE", "");
                    
                    // 3. Scrub the ugly 8.3 FAT32 tilde (e.g., MYCINE~1 becomes MYCINE)
                    if (prettyName.contains("~")) {
                        prettyName = prettyName.substring(0, prettyName.indexOf("~"));
                    }
                    
                    // Attempt to extract the TITLE metadata directly from the .cube file header
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(f));
                        String line;
                        for(int j=0; j<15; j++) {
                            line = br.readLine();
                            if (line != null && line.startsWith("TITLE")) {
                                prettyName = line.replace("TITLE", "").replace("\"", "").trim().toUpperCase();
                                break;
                            }
                        }
                        br.close();
                    } catch (Exception e) {}
                    
                    recipeNames.add(prettyName);
                }
            }
        }
    }

    private File getLutDir() {
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (!lutDir.exists()) lutDir = new File("/mnt/sdcard/LUTS");
        return lutDir;
    }

    /**
     * INSTANT-SAVE: Force strict SD Card save logic.
     * Prevents data loss when Sony OS aggressively kills background apps.
     */
    public void savePreferences() {
        try {
            File lutDir = getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            
            for(int i=0; i<10; i++) {
                RTLProfile p = profiles[i];
                // Ensure we save the path, not the index, to preserve mapping if files change
                String path = (p.lutIndex >= 0 && p.lutIndex < recipePaths.size()) ? recipePaths.get(p.lutIndex) : "NONE";
                
                sb.append(i).append(",").append(path).append(",")
                  .append(p.opacity).append(",").append(p.grain).append(",")
                  .append(p.grainSize).append(",").append(p.rollOff).append(",")
                  .append(p.vignette).append(",")
                  .append(p.whiteBalance).append(",")
                  .append(p.wbShift).append(",")
                  .append(p.dro).append(",")
                  .append(p.wbShiftGM).append(",")
                  .append(p.contrast).append(",")
                  .append(p.saturation).append(",")
                  .append(p.sharpness).append(",")
                  .append(p.profileName).append("\n"); // --- NEW: Appending Custom Name
            }
            fos.write(sb.toString().getBytes()); 
            fos.flush(); 
            fos.getFD().sync(); 
            fos.close();
        } catch (Exception e) {}
    }

    public void loadPreferences() {
        File backupFile = new File(getLutDir(), "RTLBAK.TXT");
        if (backupFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(backupFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else if (!line.startsWith("prefs=")) { // UI Prefs remain in MainActivity for now
                        String[] parts = line.split(",");
                        if (parts.length >= 6) {
                            int idx = Integer.parseInt(parts[0]); 
                            int foundIndex = recipePaths.indexOf(parts[1]);
                            
                            RTLProfile p = profiles[idx];
                            p.lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            p.opacity = Integer.parseInt(parts[2]); 
                            if (p.opacity <= 5) p.opacity = 100;
                            p.grain = Math.min(5, Integer.parseInt(parts[3]));
                            
                            if (parts.length >= 7) {
                                p.grainSize = Math.min(2, Integer.parseInt(parts[4]));
                                p.rollOff = Math.min(5, Integer.parseInt(parts[5])); 
                                p.vignette = Math.min(5, Integer.parseInt(parts[6]));
                            }
                            if (parts.length >= 10) {
                                p.whiteBalance = parts[7];
                                p.wbShift = Integer.parseInt(parts[8]);
                                p.dro = parts[9];
                            }
                            if (parts.length >= 11) {
                                p.wbShiftGM = Integer.parseInt(parts[10]);
                            }
                            if (parts.length >= 14) {
                                p.contrast = Integer.parseInt(parts[11]);
                                p.saturation = Integer.parseInt(parts[12]);
                                p.sharpness = Integer.parseInt(parts[13]);
                            }
                            // --- NEW: Loading Custom Name ---
                            if (parts.length >= 15) {
                                p.profileName = parts[14];
                            }
                        }
                    }
                }
                br.close(); 
            } catch (Exception e) {}
        }
    }
}