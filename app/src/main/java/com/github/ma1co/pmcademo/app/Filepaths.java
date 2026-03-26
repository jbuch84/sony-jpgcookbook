package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Filepaths {

    public static File getStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    public static List<File> getStorageRoots() {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory()); // sdcard0
        roots.add(new File("/storage/sdcard1"));              // sdcard1
        roots.add(new File("/mnt/sdcard"));
        roots.add(new File("/storage/extSdCard"));
        return roots;
    }

    public static File getAppDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM");
            if (dir.exists()) return dir;
        }
        File def = new File(getStorageRoot(), "JPEGCAM");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    public static File getLutDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/LUTS");
            if (dir.exists()) return dir;
        }
        File def = new File(getAppDir(), "LUTS");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    // --- SMART DCIM DETECTION ---
    // Searches all roots for the one currently being used by the camera for photos
    public static File getDcimDir() {
        for (File root : getStorageRoots()) {
            File dcim = new File(root, "DCIM");
            // Sony always creates a folder like '100MSDCF' or '100ALPHA' inside DCIM
            if (dcim.exists() && dcim.isDirectory()) {
                File[] subdirs = dcim.listFiles();
                if (subdirs != null) {
                    for (File s : subdirs) {
                        if (s.isDirectory() && (s.getName().contains("MSDCF") || s.getName().contains("ALPHA") || s.getName().contains("SONY"))) {
                            return dcim; // Found the active photo folder!
                        }
                    }
                }
            }
        }
        // Failsafe
        return new File(getStorageRoot(), "DCIM");
    }

    public static File getRecipeDir() { File d = new File(getAppDir(), "RECIPES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getLensesDir() { File d = new File(getAppDir(), "LENSES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getGradedDir() { File d = new File(getAppDir(), "GRADED"); if (!d.exists()) d.mkdirs(); return d; }

    public static void buildAppStructure() {
        getAppDir(); getLutDir(); getRecipeDir(); getLensesDir(); getGradedDir();
    }
}