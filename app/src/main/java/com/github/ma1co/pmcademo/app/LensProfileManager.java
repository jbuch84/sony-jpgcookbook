package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LensProfileManager {

    public static class CalPoint {
        public final float ratio;     
        public final float distance;  

        public CalPoint(float ratio, float distance) {
            this.ratio = ratio;
            this.distance = distance;
        }
    }

    private File lensesDir;
    
    // Active State
    public float currentFocalLength = 50.0f;
    public float currentMaxAperture = 2.8f;
    public String currentLensName = "Unmapped Lens";
    private List<CalPoint> activePoints = new ArrayList<CalPoint>();
    private boolean hasActiveProfile = false;

    public LensProfileManager(Context context) {
        // Reverted to EXACTLY match your working GRADED folder logic
        lensesDir = new File(Environment.getExternalStorageDirectory(), "LENSES");
        if (!lensesDir.exists()) {
            lensesDir.mkdirs();
        }
    }

    // --- NEW: Generates a dummy math profile for manual lenses ---
    public List<CalPoint> generateManualDummyProfile() {
        List<CalPoint> ghostPoints = new ArrayList<CalPoint>();
        ghostPoints.add(new CalPoint(0.0f, 0.3f)); // Virtual Near
        ghostPoints.add(new CalPoint(0.5f, 2.0f)); // Virtual Mid
        ghostPoints.add(new CalPoint(1.0f, 999.0f)); // Virtual Infinity
        return ghostPoints;
    }
    
    public List<String> getAvailableLenses() {
        List<String> lenses = new ArrayList<String>();
        if (lensesDir != null && lensesDir.exists() && lensesDir.listFiles() != null) {
            for (File f : lensesDir.listFiles()) {
                if (f.getName().toLowerCase().endsWith(".lens")) {
                    lenses.add(f.getName());
                }
            }
        }
        Collections.sort(lenses); 
        return lenses;
    }

    public void saveProfileToFile(float focalLength, float maxAperture, List<CalPoint> points) {
        if (lensesDir == null || (!lensesDir.exists() && !lensesDir.mkdirs())) {
            Log.e("filmOS_Lens", "CRITICAL: Could not access LENSES directory to save.");
            return;
        }

        int focalInt = (int) focalLength;
        int apInt = (int) (maxAperture * 10); 
        String filename = focalInt + "mm" + apInt + ".lens";
        
        File outFile = new File(lensesDir, filename);

        try {
            FileWriter writer = new FileWriter(outFile);
            writer.write("FOCAL:" + focalLength + "\n");
            writer.write("APERTURE:" + maxAperture + "\n");
            
            StringBuilder ptsBuilder = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                ptsBuilder.append(points.get(i).ratio).append(",").append(points.get(i).distance);
                if (i < points.size() - 1) ptsBuilder.append(";");
            }
            writer.write("POINTS:" + ptsBuilder.toString() + "\n");
            
            writer.flush();
            writer.close();
            Log.d("filmOS_Lens", "Saved lens profile to SD Card: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("filmOS_Lens", "Failed to save lens file: " + e.getMessage());
        }
    }

    public void loadProfileFromFile(String filename) {
        if (lensesDir == null) return;
        File inFile = new File(lensesDir, filename);
        if (!inFile.exists()) {
            clearCurrentProfile();
            return;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(inFile));
            String line;
            
            float loadedFocal = 50.0f;
            float loadedAperture = 2.8f;
            List<CalPoint> loadedPoints = new ArrayList<CalPoint>();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("FOCAL:")) {
                    loadedFocal = Float.parseFloat(line.split(":")[1]);
                } else if (line.startsWith("APERTURE:")) {
                    loadedAperture = Float.parseFloat(line.split(":")[1]);
                } else if (line.startsWith("POINTS:")) {
                    String[] pairs = line.split(":")[1].split(";");
                    for (String pair : pairs) {
                        String[] parts = pair.split(",");
                        if (parts.length == 2) {
                            loadedPoints.add(new CalPoint(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
                        }
                    }
                }
            }
            reader.close();

            this.currentFocalLength = loadedFocal;
            this.currentMaxAperture = loadedAperture;
            this.currentLensName = filename.replace(".lens", "");
            this.activePoints = loadedPoints;
            this.hasActiveProfile = true;
            
            Log.d("filmOS_Lens", "Loaded profile from SD: " + filename);

        } catch (Exception e) {
            Log.e("filmOS_Lens", "Failed to load lens file: " + e.getMessage());
            clearCurrentProfile();
        }
    }

    public void clearCurrentProfile() {
        this.currentLensName = "Unmapped Lens";
        this.activePoints.clear();
        this.hasActiveProfile = false;
    }

    public boolean hasActiveProfile() {
        return hasActiveProfile && activePoints.size() >= 2;
    }

    public List<CalPoint> getCurrentPoints() {
        return activePoints;
    }

    public float getCurrentFocalLength() {
        return currentFocalLength;
    }
    
    public String getCurrentLensName() {
        return currentLensName;
    }

    public float getDistanceForRatio(float targetRatio) {
        if (activePoints.size() < 2) return -1f;
        
        for (int i = 0; i < activePoints.size() - 1; i++) {
            CalPoint p1 = activePoints.get(i);
            CalPoint p2 = activePoints.get(i + 1);
            
            if (targetRatio >= p1.ratio && targetRatio <= p2.ratio) {
                float range = p2.ratio - p1.ratio;
                float pct = (targetRatio - p1.ratio) / range;
                return p1.distance + (pct * (p2.distance - p1.distance));
            }
        }
        return -1f;
    }
}