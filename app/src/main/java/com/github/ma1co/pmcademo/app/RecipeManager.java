package com.github.ma1co.pmcademo.app;

import android.util.Log;
import java.io.*;
import java.util.ArrayList;

public class RecipeManager {
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0;
    private int qualityIndex = 1;
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    public RecipeManager() {
        for (int i = 0; i < 10; i++) profiles[i] = new RTLProfile(i);
        scanRecipes();
    }

    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = (slot + 10) % 10; }
    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) { this.qualityIndex = (index + 3) % 3; }
    public RTLProfile getCurrentProfile() { return profiles[currentSlot]; }
    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    public void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear(); 
        recipePaths.add("NONE"); 
        recipeNames.add("NONE"); 
        
        // Scan every possible storage root for the JPEGCAM/LUTS folder
        for (java.io.File root : Filepaths.getStorageRoots()) {
            java.io.File lutDir = new java.io.File(root, "JPEGCAM/LUTS");
            if (lutDir.exists() && lutDir.isDirectory()) {
                Log.d("JPEG.CAM", "Scanning: " + lutDir.getAbsolutePath());
                java.io.File[] files = lutDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        String u = f.getName().toUpperCase();
                        if (!u.startsWith(".") && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) {
                            if (!recipePaths.contains(f.getAbsolutePath())) {
                                recipePaths.add(f.getAbsolutePath());
                                // Try to find TITLE in the CUBE file, otherwise use filename
                                String name = u.replace(".CUBE", "").replace(".CUB", "");
                                try {
                                    BufferedReader br = new BufferedReader(new FileReader(f));
                                    String line;
                                    for(int j=0; j<10; j++) {
                                        line = br.readLine();
                                        if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                            name = line.split("\"")[1].toUpperCase();
                                            break;
                                        }
                                    }
                                    br.close();
                                } catch (Exception e) {}
                                recipeNames.add(name);
                            }
                        }
                    }
                }
            }
        }
    }

    public void savePreferences() {
        try {
            java.io.File backupFile = new java.io.File(Filepaths.getLutDir(), "RTLBAK.TXT");
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n").append("slot=").append(currentSlot).append("\n");
            for(int i=0; i<10; i++) {
                RTLProfile p = profiles[i];
                String path = (p.lutIndex >= 0 && p.lutIndex < recipePaths.size()) ? recipePaths.get(p.lutIndex) : "NONE";
                sb.append(i).append(",").append(path).append(",").append(p.opacity).append(",").append(p.grain).append(",")
                  .append(p.grainSize).append(",").append(p.rollOff).append(",").append(p.vignette).append(",")
                  .append(p.whiteBalance).append(",").append(p.wbShift).append(",").append(p.dro).append(",")
                  .append(p.wbShiftGM).append(",").append(p.contrast).append(",").append(p.saturation).append(",")
                  .append(p.sharpness).append(",").append(p.profileName != null ? p.profileName : "").append("\n");
            }
            fos.write(sb.toString().getBytes()); fos.flush(); fos.getFD().sync(); fos.close();
        } catch (Exception e) { Log.e("JPEG.CAM", "Save error: " + e.getMessage()); }
    }

    public void loadPreferences() {
        java.io.File backupFile = new java.io.File(Filepaths.getLutDir(), "RTLBAK.TXT");
        if (!backupFile.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(backupFile));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                else {
                    String[] parts = line.split(",", -1);
                    if (parts.length >= 14) {
                        int idx = Integer.parseInt(parts[0]);
                        RTLProfile p = profiles[idx];
                        int found = recipePaths.indexOf(parts[1]);
                        p.lutIndex = (found != -1) ? found : 0;
                        p.opacity = Integer.parseInt(parts[2]); p.grain = Integer.parseInt(parts[3]);
                        p.grainSize = Integer.parseInt(parts[4]); p.rollOff = Integer.parseInt(parts[5]);
                        p.vignette = Integer.parseInt(parts[6]); p.whiteBalance = parts[7];
                        p.wbShift = Integer.parseInt(parts[8]); p.dro = parts[9];
                        p.wbShiftGM = Integer.parseInt(parts[10]); p.contrast = Integer.parseInt(parts[11]);
                        p.saturation = Integer.parseInt(parts[12]); p.sharpness = Integer.parseInt(parts[13]);
                        if (parts.length >= 15) p.profileName = parts[14];
                    }
                }
            }
            br.close();
        } catch (Exception e) { Log.e("JPEG.CAM", "Load error: " + e.getMessage()); }
    }
}