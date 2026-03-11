package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * filmOS UI: Lens Profile Manager
 * Handles piecewise linear interpolation for cinema focus mapping.
 * Saves profiles as physical .lens files on the SD card.
 */
public class LensProfileManager {
    
    public float currentFocalLength = 50.0f;
    public float getCurrentFocalLength() { return currentFocalLength; }
    
    // A simple data class to hold our mapped points
    public static class CalPoint {
        public float ratio;
        public float distance;
        public CalPoint(float r, float d) {
            this.ratio = r;
            this.distance = d;
        }
    }

    private String currentLensName = "Unknown Lens";
    private List<CalPoint> currentPoints = new ArrayList<CalPoint>();

    public LensProfileManager(Context context) {
        // Context is kept for compatibility, but we no longer use SharedPreferences
    }

    // --- 1. THE SD CARD ROUTER ---
    private File getLensDir() {
        File sdCard = Environment.getExternalStorageDirectory();
        // Creates the SDCARD/FILMOS/LENSES folder if it doesn't exist
        File filmosDir = new File(sdCard, "FILMOS/LENSES");
        if (!filmosDir.exists()) {
            filmosDir.mkdirs();
        }
        return filmosDir;
    }

    // --- 2. THE 8-CHARACTER FILENAME ENFORCER ---
    private String generateSafeFilename(String lensName) {
        if (lensName == null) return "DEFAULT.lens";
        
        // Strip out the word " Lens" so "25mm Lens" just becomes "25mm"
        String safe = lensName.replace(" Lens", "");
        
        // Strip everything except letters, numbers, and hyphens, then make uppercase
        safe = safe.replaceAll("[^a-zA-Z0-9\\-]", "").toUpperCase();
        
        // Strictly enforce the 8-character base name limit
        if (safe.length() > 8) {
            safe = safe.substring(0, 8);
        }
        
        if (safe.isEmpty()) safe = "LENS";
        
        return safe + ".lens"; 
    }

    // --- 3. THE LOADER ---
    public boolean loadProfile(String lensName) {
        currentPoints.clear();
        currentLensName = lensName;
        File file = new File(getLensDir(), generateSafeFilename(lensName));
        
        if (!file.exists()) return false;

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            
            // Read the first line which stores the focal length
            String focalLine = br.readLine();
            if (focalLine != null && focalLine.startsWith("FOCAL:")) {
                try {
                    currentFocalLength = Float.parseFloat(focalLine.replace("FOCAL:", "").trim());
                } catch (Exception e) {}
            }
            
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("FOCAL:")) continue;
                
                // Parse standard CSV format: ratio,distance
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    currentPoints.add(new CalPoint(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
                }
            }
            br.close();
            sortPoints();
            // A valid profile requires at least 2 points
            return currentPoints.size() >= 2; 
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // --- 4. THE WRITER ---
    public void saveProfile(String lensName, float focalLength, List<CalPoint> points) {
        this.currentFocalLength = focalLength;
        this.currentPoints = new ArrayList<CalPoint>(points);
        this.currentLensName = lensName;
        sortPoints();
        
        File file = new File(getLensDir(), generateSafeFilename(lensName));
        
        try {
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter osw = new OutputStreamWriter(fos);
            
            // Write the focal length as the header
            osw.write("FOCAL:" + focalLength + "\n");
            
            // Writes out clean text lines: "0.15,1.5"
            for (CalPoint pt : points) {
                osw.write(pt.ratio + "," + pt.distance + "\n");
            }
            
            osw.flush();
            osw.close();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sortPoints() {
        Collections.sort(currentPoints, new Comparator<CalPoint>() {
            @Override
            public int compare(CalPoint p1, CalPoint p2) {
                return Float.compare(p1.ratio, p2.ratio);
            }
        });
    }

    public String getCurrentLensName() {
        return currentLensName;
    }

    public List<CalPoint> getCurrentPoints() {
        return currentPoints;
    }

    public boolean hasActiveProfile() {
        return !currentPoints.isEmpty();
    }
    
    public void clearCurrent() {
        currentPoints.clear();
    }

    /**
     * The Magic Math: Piecewise Linear Interpolation.
     * Takes the physical lens ring ratio (0.0 to 1.0) and calculates the exact distance.
     */
    public float getDistanceForRatio(float ratio) {
        if (currentPoints.isEmpty()) return -1f; // No profile loaded
        if (currentPoints.size() == 1) return currentPoints.get(0).distance;
        
        // Clamp to min/max if we exceed the calibrated bounds
        if (ratio <= currentPoints.get(0).ratio) return currentPoints.get(0).distance;
        if (ratio >= currentPoints.get(currentPoints.size() - 1).ratio) return currentPoints.get(currentPoints.size() - 1).distance;

        // Find which two points we are currently between
        for (int i = 0; i < currentPoints.size() - 1; i++) {
            CalPoint p1 = currentPoints.get(i);
            CalPoint p2 = currentPoints.get(i + 1);

            if (ratio >= p1.ratio && ratio <= p2.ratio) {
                float percentageBetween = (ratio - p1.ratio) / (p2.ratio - p1.ratio);
                return p1.distance + (percentageBetween * (p2.distance - p1.distance));
            }
        }
        return -1f;
    }
}