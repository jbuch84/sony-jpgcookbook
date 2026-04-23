package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

public class DiptychManager {
    private MainActivity activity;
    private DiptychOverlayView overlayView;
    private TextView tvTopStatus;

    private int state = 0; // 0: Need Shot 1, 1: Need Shot 2
    private String leftFilename = null;
    private String rightFilename = null;
    private boolean isEnabled = false;

    public DiptychManager(MainActivity activity, FrameLayout container, TextView tvTopStatus) {
        this.activity = activity;
        this.tvTopStatus = tvTopStatus;
        this.overlayView = new DiptychOverlayView(activity);
        this.overlayView.setVisibility(View.GONE);
        // ADD AT INDEX 0 to be behind HUD elements
        container.addView(this.overlayView, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) reset();
        setVisibility(enabled);
    }

    public boolean isEnabled() { return isEnabled; }

    public void setVisibility(boolean visible) {
        if (overlayView != null) overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void reset() {
        state = 0;
        leftFilename = null;
        rightFilename = null;
        if (overlayView != null) overlayView.setState(0);
    }

    public int getState() { return state; }
    public void setThumbOnLeft(boolean left) { if (overlayView != null) overlayView.setThumbOnLeft(left); }
    public boolean isThumbOnLeft() { return overlayView != null && overlayView.isThumbOnLeft(); }
    public String getLeftFilename() { return leftFilename; }
    public String getRightFilename() { return rightFilename; }

    private native boolean stitchDiptychNative(String p1, String p2, String out, boolean left, int quality);

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        if (state == 0) {
            leftFilename = filename;
            state = 1;
            // INSTANT PREVIEW: Decode from original un-graded photo immediately
            new Thread(new Runnable() {
                public void run() {
                    // Wait up to 1s for file to be ready
                    File f = new File(originalPath);
                    long last = -1;
                    for(int i=0; i<10; i++) {
                        if(f.exists() && f.length() > 0 && f.length() == last) break;
                        last = f.length();
                        try { Thread.sleep(100); } catch(Exception e) {}
                    }
                    final Bitmap thumb = getDiptychThumbnail(originalPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (overlayView != null) {
                                overlayView.setThumbnail(thumb);
                                overlayView.setState(1);
                            }
                            activity.updateMainHUD();
                        }
                    });
                }
            }).start();
            return true;
        } else if (state == 1) {
            rightFilename = filename;
            state = 2; // Stitching
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        // Unlock shutter after grading is done
        activity.runOnUiThread(new Runnable() {
            public void run() {
                activity.setProcessing(false);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SHOT 1 SAVED. [L/R] TO SWAP SIDE.");
                    tvTopStatus.setTextColor(Color.GREEN);
                }
                activity.updateMainHUD();
            }
        });
    }

    public void processSecondShot(final String gradedLeftPath, final String gradedRightPath) {
        state = 0;
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) overlayView.setState(0);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("STITCHING DIPTYCH...");
                    tvTopStatus.setTextColor(Color.YELLOW);
                }
            }
        });

        final boolean firstShotLeft = isThumbOnLeft();
        new Thread(new Runnable() {
            public void run() {
                performDiptychStitch(gradedLeftPath, gradedRightPath, firstShotLeft);
            }
        }).start();
    }

    private Bitmap getDiptychThumbnail(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) { return null; }
    }

    private void performDiptychStitch(String leftPath, String rightPath, boolean firstShotLeft) {
        try {
            System.gc();
            File finalOut = new File(Filepaths.getGradedDir(), "DIPTYCH_" + new File(rightPath).getName());
            
            // USE C++ ENGINE FOR FULL RESOLUTION STITCHING!
            boolean success = stitchDiptychNative(leftPath, rightPath, finalOut.getAbsolutePath(), firstShotLeft, activity.getPrefJpegQuality());

            if (success) {
                new File(leftPath).delete();
                new File(rightPath).delete();
            }

            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH SAVED");
                        tvTopStatus.setTextColor(Color.WHITE);
                    }
                    activity.updateMainHUD();
                }
            });
        } catch (Throwable e) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH FAILED");
                        tvTopStatus.setTextColor(Color.RED);
                    }
                    activity.updateMainHUD();
                }
            });
        }
    }
}
