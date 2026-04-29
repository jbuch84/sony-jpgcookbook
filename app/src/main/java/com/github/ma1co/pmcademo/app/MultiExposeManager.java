package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
    private int totalExposures = 2;
    private int blendMode = 0; // 0 = Average, 1 = Lighten
    private List<String> capturedFiles = new ArrayList<String>();

    private MainActivity activity;
    private MultiExposeOverlayView overlayView;
    private TextView tvTopStatus;

    public MultiExposeManager(MainActivity activity, FrameLayout container, TextView tvTopStatus) {
        this.activity = activity;
        this.tvTopStatus = tvTopStatus;
        this.overlayView = new MultiExposeOverlayView(activity);
        this.overlayView.setVisibility(View.GONE);
        container.addView(this.overlayView, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
        if (overlayView != null) overlayView.setVisibility(enabled ? View.VISIBLE : View.GONE);
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
            File file = new File(f);
            if (file.exists()) file.delete();
        }
        capturedFiles.clear();
        if (overlayView != null) overlayView.clearThumbnails();
        updateTopStatus();
    }

    public boolean interceptNewFile(String filename, final String fullPath) {
        if (!enabled) return false;

        capturedFiles.add(fullPath);
        
        if (capturedFiles.size() < totalExposures) {
            state = STATE_ACCUMULATING;
            updateTopStatus();

            // --- INSTANT GHOST PREVIEW ---
            new Thread(new Runnable() {
                public void run() {
                    try {
                        File f = new File(fullPath);
                        long lastSize = -1;
                        int timeout = 0;
                        while (timeout < 40) {
                            long sz = f.length();
                            if (sz > 0 && sz == lastSize) break;
                            lastSize = sz;
                            Thread.sleep(100);
                            timeout++;
                        }
                    } catch (Exception ignored) {}

                    if (state != STATE_ACCUMULATING) return;
                    final Bitmap thumb = getGhostThumbnail(fullPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (overlayView != null && state == STATE_ACCUMULATING) {
                                overlayView.addThumbnail(thumb);
                            }
                        }
                    });
                }
            }).start();

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.setProcessing(false);
                    activity.armFileScanner();
                    activity.updateMainHUD();
                }
            });
            return true;
        } else {
            state = STATE_PROCESSING;
            if (overlayView != null) overlayView.clearThumbnails();
            updateTopStatus();
            return true;
        }
    }

    private Bitmap getGhostThumbnail(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 16;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    public void processFinalShot(final ProcessingQueueManager.Entry finalEntry, final long scannerStartedMs, final long detectedMs, final long stableMs, final int scannerAttempts) {
        if (!enabled || state != STATE_PROCESSING) return;

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
                if (state == STATE_IDLE) {
                    tvTopStatus.setText("MULTI-EXPOSURE MODE");
                    tvTopStatus.setTextColor(UiTheme.ACCENT_RECIPES);
                } else if (state == STATE_ACCUMULATING) {
                    tvTopStatus.setText("MULTI: " + capturedFiles.size() + " / " + totalExposures + " SHOTS SAVED");
                    tvTopStatus.setTextColor(UiTheme.REC_ON);
                } else if (state == STATE_PROCESSING) {
                    tvTopStatus.setText("BLENDING " + totalExposures + " EXPOSURES...");
                    tvTopStatus.setTextColor(UiTheme.WARN);
                }
            }
        });
    }

    public native boolean blendJpegsNative(String[] inputPaths, String outputPath, int blendMode);
}
