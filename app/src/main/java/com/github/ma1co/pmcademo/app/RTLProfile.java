package com.github.ma1co.pmcademo.app;

public class RTLProfile {
    // --- NEW: Arcade-Style 8-Character Name ---
    public String profileName; 

    // Look / Base
    public int lutIndex = 0;
    public int opacity = 100;
    public int grain = 0;
    public int grainSize = 1;
    public int rollOff = 0;
    public int vignette = 0;

    // Color & Tone
    public String whiteBalance = "AUTO";
    public int wbShift = 0;
    public int wbShiftGM = 0;
    public String dro = "OFF";
    public int contrast = 0;
    public int saturation = 0;
    public int sharpness = 0;

    // Constructor sets the new dynamic default names based on the slot (e.g., "RECIPE 1")
    public RTLProfile(int slotIndex) {
        this.profileName = "RECIPE " + (slotIndex + 1);
    }
    
    // Fallback constructor just in case
    public RTLProfile() {
        this.profileName = "RECIPE";
    }
}