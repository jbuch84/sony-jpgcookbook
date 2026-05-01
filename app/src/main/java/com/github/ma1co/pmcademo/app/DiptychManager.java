package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

public class DiptychManager {
    public static final int STATE_NEED_FIRST = 0;
    public static final int STATE_NEED_SECOND = 1;
    public static final int STATE_STITCHING = 2;
    public static final int STATE_PROCESSING_FIRST = 3;

    private MainActivity activity;
    private DiptychOverlayView overlayView;
    private TextView tvTopStatus;

    private int state = STATE_NEED_FIRST;
    private String leftFilename = null;
    private String rightFilename = null;
    private boolean isEnabled = false;
    private boolean shot1WasLeft = true;

    public DiptychManager(MainActivity activity, FrameLayout container, TextView tvTopStatus) {
        this.activity = activity;
        this.tvTopStatus = tvTopStatus;
        this.overlayView = new DiptychOverlayView(activity);
        this.overlayView.setVisibility(View.GONE);
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
        state = STATE_NEED_FIRST;
        leftFilename = null;
        rightFilename = null;
        shot1WasLeft = true;
        if (overlayView != null) {
            overlayView.setThumbOnLeft(true); // Always start with Shot 1 on Left
            overlayView.setState(STATE_NEED_FIRST);
        }
        if (activity != null) activity.updateDiptychPreviewWindow();
    }

    public int getState() { return state; }

    public void setThumbOnLeft(boolean left) {
        // Only allow swapping if we have the first shot but not the second
        if (state == STATE_NEED_SECOND || state == STATE_PROCESSING_FIRST) {
            if (overlayView != null) overlayView.setThumbOnLeft(left);
            if (activity != null) activity.updateDiptychPreviewWindow();
        }
    }

    public boolean isThumbOnLeft() { return overlayView != null && overlayView.isThumbOnLeft(); }
    public String getLeftFilename() { return leftFilename; }
    public String getRightFilename() { return rightFilename; }

    private native boolean stitchDiptychNative(String p1, String p2, String out, boolean shot1PlacedLeft, boolean shot1WasLeft, int quality);

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        if (state == STATE_NEED_FIRST) {
            leftFilename = originalPath;
            rightFilename = null;
            state = STATE_PROCESSING_FIRST;

            // --- INSTANT PREVIEW ---
            // The Sony media scanner fires before the JPEG is fully flushed to disk,
            // so we poll for file stability (same pattern as ImageProcessor) before
            // decoding the thumbnail. A state guard prevents a stale thumb from
            // appearing if processing finishes before the poll does.
            new Thread(new Runnable() {
                public void run() {
                    try {
                        File f = new File(originalPath);
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

                    if (state != STATE_PROCESSING_FIRST && state != STATE_NEED_SECOND) return;
                    final Bitmap thumb = getDiptychThumbnail(originalPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (overlayView != null && (state == STATE_PROCESSING_FIRST || state == STATE_NEED_SECOND)) {
                                overlayView.setThumbnail(thumb);
                                overlayView.setState(state);
                            }
                        }
                    });
                }
            }).start();

            if (activity != null) activity.updateDiptychPreviewWindow();
            return true;
        } else if (state == STATE_NEED_SECOND) {
            rightFilename = originalPath;
            state = STATE_STITCHING;

            // --- RAM OPTIMIZATION ---
            // Clear the reference thumbnail immediately to free memory for the stitch.
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    if (overlayView != null) {
                        overlayView.clearThumbnail();
                        overlayView.setState(STATE_STITCHING);
                    }
                    if (activity != null) activity.updateDiptychPreviewWindow();
                }
            });
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        state = STATE_NEED_SECOND;
        shot1WasLeft = isThumbOnLeft();
        final Bitmap thumb = getDiptychThumbnail(gradedPath);
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) {
                    overlayView.setThumbnail(thumb);
                    overlayView.setState(STATE_NEED_SECOND);
                }
                activity.setProcessing(false);
                activity.armFileScanner();
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SHOT 1 SAVED. [L/R] TO SWAP SIDE.");
                    tvTopStatus.setTextColor(UiTheme.SUCCESS);
                }
                activity.updateMainHUD();
            }
        });
    }

    public void processSecondShot(final String leftPath, final String rightPath, final ProcessingQueueManager.Entry entry, final long scannerStartedMs, final long detectedMs, final long stableMs, final int scannerAttempts) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                state = STATE_STITCHING; // Re-enforce
                if (overlayView != null) {
                    overlayView.clearThumbnail();
                    overlayView.setState(STATE_STITCHING);
                }
                if (tvTopStatus != null) {
                    tvTopStatus.setText("STITCHING DIPTYCH...");
                    tvTopStatus.setTextColor(UiTheme.WARN);
                }
            }
        });

        final boolean firstShotLeft = isThumbOnLeft();
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {}

                performDiptychStitch(leftPath, rightPath, firstShotLeft, shot1WasLeft, entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
            }
        }).start();
    }

    private Bitmap getDiptychThumbnail(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 16;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap fullBm = BitmapFactory.decodeFile(path, opts);
            if (fullBm == null) return null;
            int w = fullBm.getWidth();
            int h = fullBm.getHeight();
            // CENTER CROP: Take the middle half (width/4 to width/4 + width/2)
            Bitmap cropped = Bitmap.createBitmap(fullBm, w / 4, 0, w / 2, h);
            if (cropped != fullBm) fullBm.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }
    private void performDiptychStitch(String leftPath, String rightPath, boolean shot1PlacedLeft, boolean shot1WasLeft, final ProcessingQueueManager.Entry entry, final long scannerStartedMs, final long detectedMs, final long stableMs, final int scannerAttempts) {
        try {
            System.gc();
            File fL = new File(leftPath);
            File fR = new File(rightPath);

            String originalName = fR.getName();
            String diptychName = "DIPTYCH.JPG";
            try {
                String namePart = originalName;
                int dotIdx = originalName.lastIndexOf(".");
                if (dotIdx != -1) namePart = originalName.substring(0, dotIdx);
                if (namePart.length() > 5) {
                    diptychName = "DIP" + namePart.substring(namePart.length() - 5) + ".JPG";
                } else {
                    diptychName = "DIP" + namePart + ".JPG";
                }
                if (diptychName.length() > 12) {
                    diptychName = diptychName.substring(0, 8) + ".JPG";
                }
            } catch (Exception e) {}

            File tempDir = new File(Filepaths.getAppDir(), "TEMP");
            if (!tempDir.exists()) tempDir.mkdirs();
            final File tempOut = new File(tempDir, diptychName);

            if (!fL.exists() || !fR.exists()) {
                throw new Exception("Source files missing");
            }

            final boolean success = stitchDiptychNative(leftPath, rightPath, tempOut.getAbsolutePath(), shot1PlacedLeft, shot1WasLeft, activity.getPrefJpegQuality());

            if (success && tempOut.exists()) {
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        activity.handleStitchedDiptych(tempOut.getAbsolutePath(), entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                        reset();
                    }
                });
            } else {
                throw new Exception("Stitch FAILED");
            }

        } catch (final Exception e) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("STITCH ERROR. RESETTING.");
                        tvTopStatus.setTextColor(UiTheme.ERROR);
                    }
                }
            });
            try { Thread.sleep(2000); } catch (Exception ignored) {}
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    reset();
                }
            });
        }
    }
}
