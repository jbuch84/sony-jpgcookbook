package com.github.ma1co.pmcademo.app;

import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MultiExposeManager {

    public static final int STATE_IDLE = 0;
    public static final int STATE_ACCUMULATING = 1;
    public static final int STATE_PROCESSING = 2;

    private boolean enabled = false;
    private int state = STATE_IDLE;
    private int totalExposures = 2; // Default to 2 for now, can go up to 9
    private int blendMode = 0; // 0 = Average, 1 = Lighten
    private List<String> capturedFiles = new ArrayList<String>();

    private MainActivity activity;
    private ViewGroup container;
    private TextView tvTopStatus;

    public MultiExposeManager(MainActivity activity, ViewGroup container, TextView tvTopStatus) {
        this.activity = activity;
        this.container = container;
        this.tvTopStatus = tvTopStatus;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }
    
    public int getTotalExposures() { return totalExposures; }
    public void setTotalExposures(int count) {
        if (state == STATE_IDLE && count >= 2 && count <= 9) {
            this.totalExposures = count;
        }
    }

    public int getBlendMode() { return blendMode; }
    public void setBlendMode(int mode) {
        if (state == STATE_IDLE) {
            this.blendMode = mode;
        }
    }

    public int getState() { return state; }
    public int getCapturedCount() { return capturedFiles.size(); }

    public void reset() {
        state = STATE_IDLE;
        for (String f : capturedFiles) {
            new File(f).delete();
        }
        capturedFiles.clear();
        updateTopStatus();
    }

    // Called by MainActivity's PictureObserver when a new file is detected
    public boolean interceptNewFile(String filename, String fullPath) {
        if (!enabled) return false;

        capturedFiles.add(fullPath);
        
        if (capturedFiles.size() < totalExposures) {
            state = STATE_ACCUMULATING;
            updateTopStatus();
            // TODO: Decode a lightweight thumbnail and display as ghost overlay
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.setProcessing(false);
                    activity.armFileScanner();
                    activity.updateMainHUD();
                }
            });
            return true; // We intercepted it, do not process yet
        } else {
            state = STATE_PROCESSING;
            updateTopStatus();
            return true; // Trigger processing
        }
    }

    public void processFinalShot(final ProcessingQueueManager.Entry finalEntry, final long scannerStartedMs, final long detectedMs, final long stableMs, final int scannerAttempts) {
        if (!enabled || state != STATE_PROCESSING) return;

        // Start async task to blend
        final String[] paths = capturedFiles.toArray(new String[0]);
        
        File fOriginal = new File(paths[0]);
        String originalName = fOriginal.getName();
        String blendedName = "M_EXP_B.JPG";
        try {
            String namePart = originalName;
            int dotIdx = originalName.lastIndexOf(".");
            if (dotIdx != -1) namePart = originalName.substring(0, dotIdx);
            if (namePart.length() > 5) {
                blendedName = "EXP" + namePart.substring(namePart.length() - 5) + ".JPG";
            } else {
                blendedName = "EXP" + namePart + ".JPG";
            }
            if (blendedName.length() > 12) {
                blendedName = blendedName.substring(0, 8) + ".JPG";
            }
        } catch (Exception e) {}

        File tempDir = new File(Filepaths.getAppDir(), "TEMP");
        if (!tempDir.exists()) tempDir.mkdirs();
        final String blendedPath = new File(tempDir, blendedName).getAbsolutePath();
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = blendJpegsNative(paths, blendedPath, blendMode);
                if (success) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.handleStitchedMultiExpose(blendedPath, finalEntry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                            reset();
                        }
                    });
                } else {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvTopStatus.setText("MULTI-EXPOSURE BLEND FAILED");
                            tvTopStatus.setTextColor(UiTheme.WARN);
                            reset();
                        }
                    });
                }
            }
        }).start();
    }

    private void updateTopStatus() {
        if (!enabled) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvTopStatus == null) return;
                tvTopStatus.setVisibility(android.view.View.VISIBLE);
                UiTheme.softPanel(tvTopStatus);
                if (state == STATE_ACCUMULATING) {
                    tvTopStatus.setText("MULTI: " + capturedFiles.size() + " / " + totalExposures + " SHOTS SAVED.");
                    tvTopStatus.setTextColor(UiTheme.SUCCESS);
                } else if (state == STATE_PROCESSING) {
                    tvTopStatus.setText("BLENDING " + totalExposures + " EXPOSURES...");
                    tvTopStatus.setTextColor(UiTheme.WARN);
                }
            }
        });
    }

    public native boolean blendJpegsNative(String[] inputPaths, String outputPath, int blendMode);
}
