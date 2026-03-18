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
                String path = (p.lutIndex >= 0 && p.lutIndex < recipePaths.size()) ? recipePaths.get(p.lutIndex) : "NONE";
                String safeName = p.profileName != null ? p.profileName : "";
                
                sb.append(i).append(",").append(path).append(",") // 0, 1
                  .append(p.opacity).append(",").append(p.grain).append(",") // 2, 3
                  .append(p.grainSize).append(",").append(p.rollOff).append(",") // 4, 5
                  .append(p.vignette).append(",") // 6
                  .append(p.whiteBalance).append(",") // 7
                  .append(p.wbShift).append(",") // 8
                  .append(p.dro).append(",") // 9
                  .append(p.wbShiftGM).append(",") // 10
                  .append(p.contrast).append(",") // 11
                  .append(p.saturation).append(",") // 12
                  .append(p.sharpness).append(",") // 13
                  .append(safeName).append(",") // 14
                  
                  // --- NEW HUD HW VARIABLES ---
                  .append(p.colorMode).append(",") // 15
                  .append(p.sharpnessGain).append(",") // 16
                  
                  // Pack 6-Axis with colons
                  .append(p.colorDepthRed).append(":").append(p.colorDepthGreen).append(":")
                  .append(p.colorDepthBlue).append(":").append(p.colorDepthCyan).append(":")
                  .append(p.colorDepthMagenta).append(":").append(p.colorDepthYellow).append(",") // 17
                  
                  // Pack 9-Point Matrix with colons
                  .append(p.advMatrix[0]).append(":").append(p.advMatrix[1]).append(":")
                  .append(p.advMatrix[2]).append(":").append(p.advMatrix[3]).append(":")
                  .append(p.advMatrix[4]).append(":").append(p.advMatrix[5]).append(":")
                  .append(p.advMatrix[6]).append(":").append(p.advMatrix[7]).append(":")
                  .append(p.advMatrix[8]).append(",") // 18
                  
                  .append(p.proColorMode).append(",") // 19
                  .append(p.pictureEffect).append(",") // 20
                  .append(p.peToyCameraTone).append(",") // 21
                  .append(p.vignetteHardware).append(",") // 22
                  .append(p.softFocusLevel).append(",") // 23
                  .append(p.shadingRed).append(",") // 24
                  .append(p.shadingBlue).append("\n"); // 25
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
                    else if (!line.startsWith("prefs=")) { 
                        
                        // Use -1 to preserve empty strings (like an empty Profile Name)
                        String[] parts = line.split(",", -1); 
                        
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
                            if (parts.length >= 15) {
                                p.profileName = parts[14];
                            }
                            
                            // --- NEW HUD HW VARIABLES ---
                            if (parts.length >= 26) {
                                p.colorMode = parts[15];
                                p.sharpnessGain = Integer.parseInt(parts[16]);
                                
                                // Unpack 6-Axis
                                String[] cDepths = parts[17].split(":");
                                if (cDepths.length == 6) {
                                    p.colorDepthRed = Integer.parseInt(cDepths[0]);
                                    p.colorDepthGreen = Integer.parseInt(cDepths[1]);
                                    p.colorDepthBlue = Integer.parseInt(cDepths[2]);
                                    p.colorDepthCyan = Integer.parseInt(cDepths[3]);
                                    p.colorDepthMagenta = Integer.parseInt(cDepths[4]);
                                    p.colorDepthYellow = Integer.parseInt(cDepths[5]);
                                }
                                
                                // Unpack 9-Point Matrix
                                String[] mtx = parts[18].split(":");
                                if (mtx.length == 9) {
                                    for(int m=0; m<9; m++) {
                                        p.advMatrix[m] = Integer.parseInt(mtx[m]);
                                    }
                                }
                                
                                p.proColorMode = parts[19];
                                p.pictureEffect = parts[20];
                                p.peToyCameraTone = parts[21];
                                p.vignetteHardware = Integer.parseInt(parts[22]);
                                p.softFocusLevel = Integer.parseInt(parts[23]);
                                p.shadingRed = Integer.parseInt(parts[24]);
                                p.shadingBlue = Integer.parseInt(parts[25]);
                            }
                        }
                    }
                }
                br.close(); 
            } catch (Exception e) {}
        }
    } 

}