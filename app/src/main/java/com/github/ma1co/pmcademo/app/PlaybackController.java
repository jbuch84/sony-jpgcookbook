package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * JPEG.CAM Controller: Photo Playback
 *
 * Owns the in-camera review UI for processed JPEGs.
 */
public class PlaybackController {

    public interface HostCallback {
        View getMainUIContainer();
        int getDisplayState();
    }

    private static final String TAG = "JPEG.CAM";
    private static final int GRID_COLUMNS = 3;
    private static final int GRID_ROWS = 2;
    private static final int GRID_PAGE_SIZE = GRID_COLUMNS * GRID_ROWS;
    private static final int THUMB_REQ_WIDTH = 150;
    private static final int THUMB_REQ_HEIGHT = 84;

    private final Context      context;
    private final HostCallback host;

    private final List<File> files = new ArrayList<File>();
    private int     index         = 0;
    private boolean active        = false;
    private boolean photoOpen     = false;
    private boolean backSelected  = false;
    private boolean deleteSelected = false;
    private boolean confirmDelete = false;
    private Bitmap  currentBitmap = null;

    private final FrameLayout  container;
    private final ImageView    imageView;
    private final TextView     infoText;
    private final TextView     backText;
    private final TextView     deleteText;
    private final TextView     titleText;
    private final LinearLayout gridContainer;
    private final LinearLayout[] gridTiles  = new LinearLayout[GRID_PAGE_SIZE];
    private final ImageView[]    gridImages = new ImageView[GRID_PAGE_SIZE];
    private final TextView[]     gridLabels = new TextView[GRID_PAGE_SIZE];
    private final Bitmap[]       gridBitmaps = new Bitmap[GRID_PAGE_SIZE];

    public PlaybackController(Context context, FrameLayout rootLayout, HostCallback host) {
        this.context = context;
        this.host    = host;

        container = new FrameLayout(context);
        container.setBackgroundColor(UiTheme.SURFACE_STRONG);
        container.setVisibility(View.GONE);

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        container.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(6));
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.TOP);
        container.addView(header, headerParams);

        backText = new TextView(context);
        backText.setText("BACK");
        backText.setTextSize(14);
        backText.setTypeface(Typeface.DEFAULT_BOLD);
        backText.setGravity(Gravity.CENTER);
        backText.setPadding(dp(14), dp(7), dp(14), dp(7));
        UiTheme.pageTabPanel(backText, UiTheme.ACCENT, true, false);
        header.addView(backText, new LinearLayout.LayoutParams(dp(92), dp(40)));

        titleText = new TextView(context);
        titleText.setText("PHOTOS");
        titleText.setTextColor(UiTheme.TEXT);
        titleText.setTextSize(17);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        titleText.setPadding(dp(12), 0, 0, 0);
        titleText.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
        header.addView(titleText, new LinearLayout.LayoutParams(0, dp(40), 1f));

        deleteText = new TextView(context);
        deleteText.setText("DELETE");
        deleteText.setTextSize(14);
        deleteText.setTypeface(Typeface.DEFAULT_BOLD);
        deleteText.setGravity(Gravity.CENTER);
        deleteText.setPadding(dp(10), dp(7), dp(10), dp(7));
        UiTheme.pageTabPanel(deleteText, UiTheme.ACCENT, false, false);
        header.addView(deleteText, new LinearLayout.LayoutParams(dp(118), dp(40)));

        gridContainer = new LinearLayout(context);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(dp(14), dp(62), dp(14), dp(10));
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(gridContainer, gridParams);

        for (int r = 0; r < GRID_ROWS; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            gridContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f));
            for (int c = 0; c < GRID_COLUMNS; c++) {
                int tileIndex = r * GRID_COLUMNS + c;
                LinearLayout tile = new LinearLayout(context);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(dp(8), dp(8), dp(8), dp(6));

                ImageView thumb = new ImageView(context);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(thumb, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

                TextView label = new TextView(context);
                label.setTextSize(12);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setPadding(0, dp(5), 0, 0);
                tile.addView(label, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(26)));

                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f);
                tileParams.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(tile, tileParams);

                gridTiles[tileIndex] = tile;
                gridImages[tileIndex] = thumb;
                gridLabels[tileIndex] = label;
            }
        }

        infoText = new TextView(context);
        infoText.setTextColor(UiTheme.TEXT);
        infoText.setTextSize(17);
        infoText.setShadowLayer(3, 0, 0, UiTheme.SHADOW);
        infoText.setPadding(dp(12), dp(8), dp(12), dp(8));
        UiTheme.softPanel(infoText);
        infoText.setVisibility(View.GONE);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        infoParams.setMargins(0, dp(66), dp(22), 0);
        container.addView(infoText, infoParams);

        rootLayout.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public boolean isActive() { return active; }

    public void enter() {
        files.clear();
        File dir = Filepaths.getGradedDir();
        File[] listed = dir.exists() ? dir.listFiles() : null;
        if (listed != null) {
            for (File f : listed) {
                if (f.getName().toLowerCase().endsWith(".jpg")) files.add(f);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
        if (files.isEmpty()) return;

        active = true;
        photoOpen = false;
        backSelected = false;
        deleteSelected = false;
        confirmDelete = false;
        index = 0;
        host.getMainUIContainer().setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);
        renderGrid();
    }

    public void exit() {
        active = false;
        photoOpen = false;
        backSelected = false;
        deleteSelected = false;
        confirmDelete = false;
        container.setVisibility(View.GONE);
        host.getMainUIContainer().setVisibility(host.getDisplayState() == 0 ? View.VISIBLE : View.GONE);
        imageView.setImageDrawable(null);
        imageView.setImageBitmap(null);
        recycleCurrentBitmap();
        recycleGridBitmaps();
    }

    public void select() {
        if (!active) return;
        if (photoOpen) {
            if (deleteSelected) {
                if (confirmDelete) deleteSelectedPhoto();
                else {
                    confirmDelete = true;
                    renderPhotoHeader();
                }
            } else {
                photoOpen = false;
                backSelected = false;
                deleteSelected = false;
                confirmDelete = false;
                renderGrid();
            }
        } else if (backSelected) {
            exit();
        } else {
            showImage(index);
        }
    }

    public void navigate(int direction) {
        if (!active || files.isEmpty()) return;
        if (confirmDelete) {
            confirmDelete = false;
            if (photoOpen) renderPhotoHeader();
            else renderGrid();
            return;
        }
        if (photoOpen) {
            if (direction == 1 && backSelected) {
                backSelected = false;
                deleteSelected = true;
            } else if (direction == -1 && deleteSelected) {
                deleteSelected = false;
                backSelected = true;
            }
            renderPhotoHeader();
        } else {
            if (backSelected) {
                if (direction == 2 || direction == 1 || direction == -1) {
                    backSelected = false;
                }
                renderGrid();
                return;
            }

            int pageStart = currentPage() * GRID_PAGE_SIZE;
            int local = index - pageStart;
            int targetLocal = local;
            int row = local / GRID_COLUMNS;
            int column = local % GRID_COLUMNS;

            if (direction == -2) {
                if (row == 0) {
                    backSelected = true;
                    renderGrid();
                    return;
                }
                targetLocal = local - GRID_COLUMNS;
            } else if (direction == 2) {
                targetLocal = local + GRID_COLUMNS;
            } else if (direction == -1) {
                if (column > 0) {
                    targetLocal = local - 1;
                } else {
                    moveGridPage(-1);
                    renderGrid();
                    return;
                }
            } else if (direction == 1) {
                if (column < GRID_COLUMNS - 1 && local + 1 < GRID_PAGE_SIZE && pageStart + local + 1 < files.size()) {
                    targetLocal = local + 1;
                } else {
                    moveGridPage(1);
                    renderGrid();
                    return;
                }
            }

            int targetIndex = pageStart + targetLocal;
            if (targetLocal >= 0 && targetLocal < GRID_PAGE_SIZE && targetIndex < files.size()) {
                index = targetIndex;
            }
            renderGrid();
        }
    }

    private void renderGrid() {
        if (files.isEmpty()) return;
        photoOpen = false;
        recycleCurrentBitmap();
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        infoText.setVisibility(View.GONE);
        gridContainer.setVisibility(View.VISIBLE);
        titleText.setText(confirmDelete ? "DELETE " + files.get(index).getName() + "?" : "PHOTOS  < PAGE " + (currentPage() + 1) + " / " + pageCount() + " >");
        UiTheme.pageTabPanel(backText, UiTheme.ACCENT, backSelected, false);
        backText.setTextColor(backSelected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        deleteText.setVisibility(View.GONE);
        deleteText.setText("DELETE");
        UiTheme.pageTabPanel(deleteText, UiTheme.ACCENT, false, false);
        deleteText.setTextColor(UiTheme.TEXT_MUTED);

        int pageStart = currentPage() * GRID_PAGE_SIZE;
        recycleGridBitmaps();
        for (int i = 0; i < GRID_PAGE_SIZE; i++) {
            int fileIndex = pageStart + i;
            LinearLayout tile = gridTiles[i];
            ImageView thumb = gridImages[i];
            TextView label = gridLabels[i];

            if (fileIndex >= files.size()) {
                tile.setVisibility(View.INVISIBLE);
                thumb.setImageBitmap(null);
                label.setText("");
                continue;
            }

            File file = files.get(fileIndex);
            boolean selected = !backSelected && fileIndex == index;
            tile.setVisibility(View.VISIBLE);
            UiTheme.tilePanel(tile, UiTheme.ACCENT, selected);
            label.setText(file.getName());
            label.setTextColor(selected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);

            Bitmap thumbBitmap = decodeGridThumbnail(file);
            gridBitmaps[i] = thumbBitmap;
            thumb.setImageBitmap(thumbBitmap);
        }
    }

    private void showImage(int idx) {
        if (files.isEmpty()) return;
        idx = wrapIndex(idx);

        index = idx;
        photoOpen = true;
        backSelected = true;
        deleteSelected = false;
        confirmDelete = false;
        File file = files.get(idx);

        try {
            recycleGridBitmaps();
            recycleCurrentBitmap();
            imageView.setImageDrawable(null);
            imageView.setImageBitmap(null);
            System.gc();

            gridContainer.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            infoText.setVisibility(View.VISIBLE);
            renderPhotoHeader();

            if (file.length() == 0) {
                infoText.setText((idx + 1) + "/" + files.size() + "\n[ERROR: 0-BYTE FILE]");
                return;
            }

            String path = file.getAbsolutePath();
            ExifInterface exif  = new ExifInterface(path);
            String fnum  = exif.getAttribute("FNumber");
            String speed = exif.getAttribute("ExposureTime");
            String iso   = exif.getAttribute("ISOSpeedRatings");

            String speedStr = "--s";
            if (speed != null) {
                try {
                    double s = Double.parseDouble(speed);
                    speedStr = (s < 1.0) ? "1/" + Math.round(1.0 / s) + "s" : Math.round(s) + "s";
                } catch (Exception ignored) {}
            }
            infoText.setText(
                (idx + 1) + " / " + files.size() + "\n"
                + file.getName() + "\n"
                + (fnum != null ? "f/" + fnum : "f/--")
                + " | " + speedStr
                + " | " + (iso != null ? "ISO " + iso : "ISO --")
                + "\n\nLOW-RES PREVIEW");

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);

            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                infoText.setText((idx + 1) + " / " + files.size() + "\n[CORRUPT HEADER ERROR]");
                return;
            }

            final int reqWidth = 800, reqHeight = 600;
            int inSampleSize = 1;
            if (opts.outHeight > reqHeight || opts.outWidth > reqWidth) {
                while ((opts.outHeight / inSampleSize) > reqHeight
                    || (opts.outWidth  / inSampleSize) > reqWidth) {
                    inSampleSize *= 2;
                }
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize       = inSampleSize;
            opts.inPreferredConfig  = Bitmap.Config.RGB_565; // Halves memory footprint
            opts.inDither           = true;
            opts.inPreferQualityOverSpeed = false;
            opts.inPurgeable        = true;  // Crucial for Android 2.3 large bitmaps
            opts.inInputShareable   = true;

            Bitmap raw = null;
            try {
                raw = BitmapFactory.decodeFile(path, opts);
            } catch (OutOfMemoryError e) {
                // Fallback to even smaller size if memory is still tight
                opts.inSampleSize *= 2;
                System.gc();
                raw = BitmapFactory.decodeFile(path, opts);
            }
            if (raw == null) {
                infoText.setText((idx + 1) + " / " + files.size() + "\n[DECODE ERROR]");
                return;
            }

            Bitmap bmp = transformForDisplay(raw, exif, true);
            if (raw != bmp) raw.recycle();

            android.graphics.drawable.BitmapDrawable drawable =
                    new android.graphics.drawable.BitmapDrawable(context.getResources(), bmp);
            drawable.setDither(true);

            imageView.setImageDrawable(drawable);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            currentBitmap = bmp;

        } catch (OutOfMemoryError oom) {
            android.util.Log.e(TAG, "OOM during playback. Recovering...");
            infoText.setText((idx + 1) + " / " + files.size() + "\n[MEMORY ERROR - SKIPPED]");
            recycleCurrentBitmap();
            recycleGridBitmaps();
            System.gc();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Playback error: " + e.getMessage());
        }
    }

    private Bitmap decodeGridThumbnail(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            byte[] embedded = exif.getThumbnail();
            if (embedded != null && embedded.length > 0) {
                BitmapFactory.Options thumbOpts = new BitmapFactory.Options();
                thumbOpts.inPreferredConfig = Bitmap.Config.RGB_565;
                thumbOpts.inDither = true;
                Bitmap rawThumb = BitmapFactory.decodeByteArray(embedded, 0, embedded.length, thumbOpts);
                if (rawThumb != null) return transformForDisplay(rawThumb, exif, false);
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);

            int sample = 1;
            while ((opts.outWidth / sample) > THUMB_REQ_WIDTH || (opts.outHeight / sample) > THUMB_REQ_HEIGHT) {
                sample *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inDither = true;
            opts.inPurgeable = true;
            opts.inInputShareable = true;
            Bitmap raw = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (raw == null) return null;
            Bitmap transformed = transformForDisplay(raw, exif, false);
            if (raw != transformed) raw.recycle();
            return transformed;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap transformForDisplay(Bitmap raw, ExifInterface exif, boolean filter) {
        int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int rot = 0;
        if      (orient == ExifInterface.ORIENTATION_ROTATE_90)  rot = 90;
        else if (orient == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
        else if (orient == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;

        Matrix matrix = new Matrix();
        if (rot != 0) matrix.postRotate(rot);
        matrix.postScale(0.8888f, 1.0f);
        return Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, filter);
    }

    private int wrapIndex(int idx) {
        if (files.isEmpty()) return 0;
        while (idx < 0) idx += files.size();
        while (idx >= files.size()) idx -= files.size();
        return idx;
    }

    private int currentPage() {
        if (files.isEmpty()) return 0;
        return index / GRID_PAGE_SIZE;
    }

    private int pageCount() {
        if (files.isEmpty()) return 1;
        return (files.size() + GRID_PAGE_SIZE - 1) / GRID_PAGE_SIZE;
    }

    private void moveGridPage(int direction) {
        int page = currentPage();
        int local = index - page * GRID_PAGE_SIZE;
        int nextPage = page + (direction > 0 ? 1 : -1);
        if (nextPage < 0) nextPage = pageCount() - 1;
        if (nextPage >= pageCount()) nextPage = 0;

        int nextStart = nextPage * GRID_PAGE_SIZE;
        int nextIndex = nextStart + local;
        if (nextIndex >= files.size()) nextIndex = files.size() - 1;
        if (nextIndex < nextStart) nextIndex = nextStart;
        index = nextIndex;
    }

    private void deleteSelectedPhoto() {
        if (files.isEmpty() || index < 0 || index >= files.size()) return;
        File file = files.get(index);
        boolean deleted = file.exists() && file.delete();
        confirmDelete = false;
        deleteSelected = false;
        backSelected = false;
        if (!deleted) {
            titleText.setText("DELETE FAILED: " + file.getName());
            renderGrid();
            return;
        }
        files.remove(index);
        recycleCurrentBitmap();
        recycleGridBitmaps();
        if (files.isEmpty()) {
            exit();
            return;
        }
        if (index >= files.size()) index = files.size() - 1;
        renderGrid();
    }

    private void renderPhotoHeader() {
        if (files.isEmpty() || index < 0 || index >= files.size()) return;
        titleText.setText(confirmDelete ? "DELETE " + files.get(index).getName() + "?" : "PHOTO  " + (index + 1) + " / " + files.size());
        UiTheme.pageTabPanel(backText, UiTheme.ACCENT, backSelected, false);
        backText.setTextColor(backSelected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        deleteText.setVisibility(View.VISIBLE);
        deleteText.setText(confirmDelete ? "CONFIRM?" : "DELETE");
        UiTheme.pageTabPanel(deleteText, UiTheme.ACCENT, deleteSelected, false);
        deleteText.setTextColor(deleteSelected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
    }

    private void recycleCurrentBitmap() {
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        currentBitmap = null;
    }

    private void recycleGridBitmaps() {
        for (int i = 0; i < gridBitmaps.length; i++) {
            if (gridBitmaps[i] != null && !gridBitmaps[i].isRecycled()) gridBitmaps[i].recycle();
            gridBitmaps[i] = null;
            if (gridImages[i] != null) gridImages[i].setImageBitmap(null);
        }
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
