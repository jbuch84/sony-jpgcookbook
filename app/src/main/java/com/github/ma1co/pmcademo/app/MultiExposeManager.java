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
            activity.clearProcessingState();
            return true; // We intercepted it, do not process yet
        } else {
            state = STATE_PROCESSING;
            updateTopStatus();
            return true; // Trigger processing
        }
    }

    public void processFinalShot(ProcessingQueueManager.Entry finalEntry, long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        if (!enabled || state != STATE_PROCESSING) return;

        // Start async task to blend
        final String[] paths = capturedFiles.toArray(new String[0]);
        final String blendedPath = new File(new File(paths[0]).getParent(), "M_EXP_B.JPG").getAbsolutePath();
        
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
