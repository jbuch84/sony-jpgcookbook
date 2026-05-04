package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * JPEG.CAM Controller: Settings Menu
 *
 * Owns all state, views, navigation logic and rendering for the 8-page
 * settings menu system. Extracted from MainActivity as part of the God
 * Class decomposition.
 *
 * Architecture:
 * - Builds and owns the menuContainer view tree in its constructor
 * - Exposes open()/close() and directional navigation methods
 * - Uses HostCallback for cross-cutting concerns (preferences, hardware)
 * - CHARSET is public so HudController can reference it for naming mode
 */
public class MenuController {

    /** Character set used for on-camera name entry (menu AND HUD naming modes). */
    public static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_";
    private static final int PROCESSING_FREQUENCY_MANUAL = -1;
    private static final int MANUAL_QUEUE_PAGE_SIZE = 5;
    private static final int MANUAL_QUEUE_THUMB_WIDTH = 96;
    private static final int MANUAL_QUEUE_THUMB_HEIGHT = 54;
    private static final int MANUAL_QUEUE_THUMB_CACHE_LIMIT = 24;
    private static final String[] CUSTOM_BUTTON_LABELS = {
            "OFF",
            "ISO MENU",
            "FOCUS MAGNIFIER",
            "TOGGLE FOCUS METER",
            "CYCLE CREATIVE MODES",
            "TOGGLE GRID LINES",
            "SHUTTER SPEED",
            "APERTURE",
            "EXPOSURE COMP",
            "RECIPE SELECTION"
    };
    private static final int CUSTOM_BUTTON_ACTION_MAX = CUSTOM_BUTTON_LABELS.length - 1;

    // --- NEW: Caches the physical files so their indexes match the menu ---
    public static java.util.List<File> grainTextureFiles = new java.util.ArrayList<File>();
    private static String[] cachedGrainOptions = null;
    private static String cachedGrainSignature = "";

    private static boolean isGrainTextureFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".txt");
    }

    private static String buildGrainSignature(File[] files) {
        if (files == null) return "NULL";
        StringBuilder signature = new StringBuilder();
        for (File f : files) {
            if (!isGrainTextureFile(f)) continue;
            signature.append(f.getName())
                    .append('|').append(f.length())
                    .append('|').append(f.lastModified())
                    .append(';');
        }
        return signature.toString();
    }

    private static synchronized void refreshGrainEngineOptions(boolean force) {
        File dir = Filepaths.getGrainDir();
        File[] files = null;
        if (dir.exists() && dir.isDirectory()) {
            files = dir.listFiles();
            if (files != null) java.util.Arrays.sort(files); // Keep them alphabetical
        }

        String signature = buildGrainSignature(files);
        if (!force && cachedGrainOptions != null && signature.equals(cachedGrainSignature)) {
            return;
        }

        java.util.List<String> options = new java.util.ArrayList<String>();
        grainTextureFiles.clear();

        if (files != null) {
            for (File f : files) {
                if (isGrainTextureFile(f)) {
                    grainTextureFiles.add(f);
                    String title = SonyFileScanner.getGrainTitle(f);
                    options.add(title.toUpperCase());
                }
            }
        }

        // <--- NEW: Fallback if the user hasn't dropped any files on the SD card yet
        if (options.isEmpty()) options.add("NO FILES FOUND");

        cachedGrainSignature = signature;
        cachedGrainOptions = options.toArray(new String[0]);
    }

    public static synchronized String[] getGrainEngineOptions() {
        refreshGrainEngineOptions(false);
        return cachedGrainOptions.clone();
    }

    public static synchronized File getGrainTextureFile(int index) {
        refreshGrainEngineOptions(false);
        if (grainTextureFiles.isEmpty()) return null;
        int safeIndex = Math.max(0, Math.min(grainTextureFiles.size() - 1, index));
        return grainTextureFiles.get(safeIndex);
    }
    // --- END NEW ---

    // -----------------------------------------------------------------------
    // Host callback
    // -----------------------------------------------------------------------
    public interface HostCallback {
        RecipeManager      getRecipeManager();
        ConnectivityManager getConnectivityManager();
        MatrixManager      getMatrixManager();
        Camera             getCamera();
        String             getAppVersion();

        // Preferences — read
        boolean isPrefFocusMeter();
        boolean isPrefCinemaMattes();
        boolean isPrefDiptych();
        boolean isPrefMultiExpose();
        int     getMultiExposeCount();
        int     getMultiExposeBlendMode(); // <--- ADDED
        boolean isPrefGridLines();
        int     getPrefJpegQuality();
        int     getProcessingFrequency();
        int     getQueuedPhotoCount();
        List<ProcessingQueueManager.Entry> getQueuedPhotoEntries();
        ProcessingQueueManager.Entry getQueuedPhotoEntry(int index);
        String  getProcessingEstimateText(int photoCount);

        // Preferences — write
        void setPrefFocusMeter(boolean v);
        void setPrefCinemaMattes(boolean v);
        void setPrefDiptych(boolean v);
        void setPrefMultiExpose(boolean v);
        void setMultiExposeCount(int count);
        void setMultiExposeBlendMode(int mode); // <--- ADDED
        void setPrefGridLines(boolean v);
        void setPrefJpegQuality(int v);
        void setProcessingFrequency(int v);
        void forceProcessQueuedPhotos();
        void processSelectedQueuedPhotos(boolean[] selected);
        void clearSelectedQueuedPhotos(boolean[] selected);
        void clearQueuedPhotos();

        // UI coordination
        FrameLayout getMainUIContainer();
        int         getDisplayState();
        void        closeHud();          // hide HUD overlays when menu closes over them
        void        onMenuOpened();      // refreshRecipes + any pre-menu setup
        void        onMenuClosed();      // save prefs, triggerLutPreload, applyHardwareRecipe,
                                        // syncHardwareState, updateMainHUD
        void        onLutPreloadNeeded();
        void        scheduleHardwareApply();
        void        onHudModeRequested(int mode);
        void        onSetAutoPowerOffMode(boolean on);
        void        restoreFocusMode(String savedMode);
    }

    // -----------------------------------------------------------------------
    // Owned state
    // -----------------------------------------------------------------------
    private boolean  isOpen            = false;
    private boolean  isEditing         = false;
    private boolean  isNaming          = false;
    private boolean  isConfirmingDelete = false;
    private boolean  categoryOpen      = false;
    private int      currentMainTab    = 0;
    private int      currentPage       = 1;
    private int      selection         = 0;
    private int      headerSelection   = 1;
    private int      itemCount         = 0;
    private String   savedFocusMode    = null;
    private String   hotspotStatus     = "Press ENTER";
    private String   wifiStatus        = "Press ENTER";
    private String[] cachedColorModes  = null;

    // Shared name buffer — also used by HudController for matrix / vault naming
    private char[]   nameBuffer        = "CUSTOM      ".toCharArray();
    private int      nameCursorPos     = 0;

    // -----------------------------------------------------------------------
    // Owned views
    // -----------------------------------------------------------------------
    private final LinearLayout   container;
    private final LinearLayout[] rows   = new LinearLayout[8];
    private final TextView[]     labels = new TextView[8];
    private final TextView[]     values = new TextView[8];
    private final ImageView[]    thumbs = new ImageView[8];
    private final View[]         rowDividers = new View[7];
    private final LinearLayout   homeContainer;
    private final TextView[]     homeTiles = new TextView[4];
    private final TextView       homeQuickTitle;
    private final LinearLayout   homeProcessingFrequency;
    private final LinearLayout   homeQueueAction;
    private final TextView       homeProcessingLabel;
    private final TextView       homeProcessingValue;
    private final TextView       homeQueueLabel;
    private final TextView       homeQueueValue;
    private final LinearLayout   tabRow;
    private final TextView       tvBack, tvTabRTL, tvTabSettings, tvTabNetwork, tvTabSupport, tvTabExtra;
    private final TextView[]     pageTabs = new TextView[5];
    private final TextView       tvSubtitle;
    private final View           pageDivider;
    private final LinearLayout   supportContainer;

    private final HostCallback host;
    private final Handler mainHandler;
    private final HandlerThread thumbnailThread;
    private final Handler thumbnailHandler;
    private final HashMap<String, Bitmap> thumbnailCache = new HashMap<String, Bitmap>();
    private final HashSet<String> thumbnailLoadsInFlight = new HashSet<String>();
    private final HashSet<String> thumbnailDecodeFailures = new HashSet<String>();
    private boolean manualQueueOpen = false;
    private boolean[] manualSelected = new boolean[0];
    private int manualQueueOffset = 0;

    // -----------------------------------------------------------------------
    // Constructor — builds the full menu view tree
    // -----------------------------------------------------------------------
    public MenuController(Context ctx, FrameLayout rootLayout, HostCallback host) {
        this.host = host;
        mainHandler = new Handler(Looper.getMainLooper());
        thumbnailThread = new HandlerThread("ManualQueueThumbs");
        thumbnailThread.start();
        thumbnailHandler = new Handler(thumbnailThread.getLooper());

        container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        UiTheme.panel(container);
        container.setPadding(24, 18, 24, 18);

        homeContainer = new LinearLayout(ctx);
        homeContainer.setOrientation(LinearLayout.VERTICAL);
        homeContainer.setPadding(4, 2, 4, 4);
        TextView homeTitle = new TextView(ctx);
        homeTitle.setText("JPEG.CAM");
        homeTitle.setTextColor(UiTheme.TEXT);
        homeTitle.setTextSize(26);
        homeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        homeTitle.setGravity(Gravity.CENTER);
        homeTitle.setPadding(0, 0, 0, 10);
        homeContainer.addView(homeTitle, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tileRowTop = new LinearLayout(ctx);
        tileRowTop.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout tileRowBottom = new LinearLayout(ctx);
        tileRowBottom.setOrientation(LinearLayout.HORIZONTAL);
        homeTiles[0] = makeHomeTile(ctx);
        homeTiles[1] = makeHomeTile(ctx);
        homeTiles[2] = makeHomeTile(ctx);
        homeTiles[3] = makeHomeTile(ctx);
        tileRowTop.addView(homeTiles[0]);
        tileRowTop.addView(homeTiles[1]);
        tileRowBottom.addView(homeTiles[2]);
        tileRowBottom.addView(homeTiles[3]);
        homeContainer.addView(tileRowTop, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        homeContainer.addView(tileRowBottom, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout queueRow = new LinearLayout(ctx);
        queueRow.setOrientation(LinearLayout.VERTICAL);
        queueRow.setPadding(5, 10, 5, 0);
        homeQuickTitle = new TextView(ctx);
        homeQuickTitle.setText("PROCESSING");
        homeQuickTitle.setTextColor(UiTheme.TEXT_MUTED);
        homeQuickTitle.setTextSize(12);
        homeQuickTitle.setTypeface(Typeface.DEFAULT_BOLD);
        homeQuickTitle.setPadding(4, 0, 0, 6);
        queueRow.addView(homeQuickTitle, new LinearLayout.LayoutParams(-1, -2));
        homeProcessingFrequency = makeHomeActionRow(ctx);
        homeProcessingLabel = (TextView) homeProcessingFrequency.getChildAt(0);
        homeProcessingValue = (TextView) homeProcessingFrequency.getChildAt(1);
        homeQueueAction = makeHomeActionRow(ctx);
        homeQueueLabel = (TextView) homeQueueAction.getChildAt(0);
        homeQueueValue = (TextView) homeQueueAction.getChildAt(1);
        queueRow.addView(homeProcessingFrequency);
        queueRow.addView(homeQueueAction);
        homeContainer.addView(queueRow, new LinearLayout.LayoutParams(-1, -2));
        container.addView(homeContainer, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        // Detail header row: Back plus category page tabs
        tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tabRow.setPadding(0, 0, 0, 12);
        tvBack        = makeTabHeader(ctx, "BACK");
        tvTabRTL      = makeTabHeader(ctx, "BASE");
        tvTabSettings = makeTabHeader(ctx, "COLOR");
        tvTabNetwork  = makeTabHeader(ctx, "FX");
        tvTabSupport  = makeTabHeader(ctx, "GRAIN");
        tvTabExtra    = makeTabHeader(ctx, "ANALOG");
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(105, -2);
        backLp.setMargins(3, 0, 9, 0);
        tvBack.setLayoutParams(backLp);
        pageTabs[0] = tvTabRTL;
        pageTabs[1] = tvTabSettings;
        pageTabs[2] = tvTabNetwork;
        pageTabs[3] = tvTabSupport;
        pageTabs[4] = tvTabExtra;
        tabRow.addView(tvBack);
        tabRow.addView(tvTabRTL);
        tabRow.addView(tvTabSettings);
        tabRow.addView(tvTabNetwork);
        tabRow.addView(tvTabSupport);
        tabRow.addView(tvTabExtra);
        container.addView(tabRow);

        // Support tab content (hidden by default)
        supportContainer = new LinearLayout(ctx);
        supportContainer.setOrientation(LinearLayout.VERTICAL);
        supportContainer.setGravity(Gravity.CENTER);
        supportContainer.setVisibility(View.GONE);
        TextView tvTitle = new TextView(ctx); tvTitle.setText("JPEG.CAM");
        tvTitle.setTextColor(UiTheme.TEXT); tvTitle.setTextSize(28);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        supportContainer.addView(tvTitle);
        TextView tvSub = new TextView(ctx);
        tvSub.setText("by JPEG Cookbook \u2022 v" + host.getAppVersion());
        tvSub.setTextColor(UiTheme.TEXT_MUTED); tvSub.setTextSize(12);
        tvSub.setPadding(0, 0, 0, 20);
        supportContainer.addView(tvSub);
        ImageView qrView = new ImageView(ctx);
        qrView.setImageResource(R.drawable.qr_hub);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(10, 10, 10, 10);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(240, 240);
        qrLp.setMargins(0, 0, 0, 20); qrView.setLayoutParams(qrLp);
        supportContainer.addView(qrView);
        TextView tvDesc = new TextView(ctx);
        tvDesc.setText("Manuals, Lens Profiles, & Support");
        tvDesc.setTextColor(UiTheme.TEXT_MUTED); tvDesc.setTextSize(12);
        supportContainer.addView(tvDesc);
        container.addView(supportContainer, new LinearLayout.LayoutParams(-1, -1));

        // Page subtitle
        tvSubtitle = new TextView(ctx);
        tvSubtitle.setTextSize(18);
        tvSubtitle.setTextColor(UiTheme.TEXT);
        tvSubtitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvSubtitle.setPadding(12, 4, 12, 14);
        container.addView(tvSubtitle);

        // Divider
        pageDivider = new View(ctx);
        pageDivider.setBackgroundColor(UiTheme.BORDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, 2);
        divLp.setMargins(0, 0, 0, 15);
        container.addView(pageDivider, divLp);

        // 8 content rows
        for (int i = 0; i < 8; i++) {
            rows[i] = new LinearLayout(ctx);
            rows[i].setOrientation(LinearLayout.HORIZONTAL);
            rows[i].setGravity(Gravity.CENTER_VERTICAL);
            rows[i].setPadding(12, 0, 12, 0);
            container.addView(rows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            thumbs[i] = new ImageView(ctx);
            thumbs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbs[i].setVisibility(View.GONE);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(86, 56);
            thumbLp.setMargins(0, 0, 10, 0);
            rows[i].addView(thumbs[i], thumbLp);
            labels[i] = new TextView(ctx);
            labels[i].setTextSize(17);
            labels[i].setTypeface(Typeface.DEFAULT_BOLD);
            labels[i].setSingleLine(false);
            values[i] = new TextView(ctx);
            values[i].setTextSize(17);
            values[i].setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            values[i].setSingleLine(false);
            rows[i].addView(labels[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
            rows[i].addView(values[i], new LinearLayout.LayoutParams(-2, -2));
            if (i < 7) {
                View rowDiv = new View(ctx);
                rowDiv.setBackgroundColor(Color.argb(90, 117, 145, 140));
                rowDividers[i] = rowDiv;
                container.addView(rowDiv, new LinearLayout.LayoutParams(-1, 1));
            }
        }

        container.setVisibility(View.GONE);
        rootLayout.addView(container, new FrameLayout.LayoutParams(-1, -1));
    }

    // -----------------------------------------------------------------------
    // Public state queries
    // -----------------------------------------------------------------------
    public boolean isOpen()           { return isOpen; }
    public boolean isNamingMode()     { return isNaming; }
    public boolean isEditingMode()    { return isEditing; }
    public boolean isConfirmingDelete(){ return isConfirmingDelete; }
    public char[]  getNameBuffer()    { return nameBuffer; }
    public int     getNameCursorPos() { return nameCursorPos; }
    public String  getSavedFocusMode(){ return savedFocusMode; }
    public int     getSelection()     { return selection; }
    public int     getCurrentPage()   { return currentPage; }
    public int     getCurrentMainTab(){ return currentMainTab; }
    public LinearLayout getContainer(){ return container; }

    // Setters for HUD enter-mode interactions
    public void setNamingMode(boolean v)       { isNaming = v; }
    public void setConfirmingDelete(boolean v) { isConfirmingDelete = v; }
    public void resetNameCursor()              { nameCursorPos = 0; }
    /** Fill the 12-char name buffer in-place from a string (pads/truncates). */
    public void fillNameBuffer(String src) {
        StringBuilder sb = new StringBuilder(src != null ? src : "");
        while (sb.length() < 12) sb.append(' ');
        String padded = sb.toString().substring(0, 12);
        for (int i = 0; i < 12; i++) nameBuffer[i] = padded.charAt(i);
    }
    /** Reset name buffer to "CUSTOM      " in-place. */
    public void resetNameBuffer() { fillNameBuffer("CUSTOM"); }
    /** Re-render the menu (e.g. after returning from a HUD overlay). */
    public void refreshDisplay()               { render(); }

    /** Contextual Back: Cancels any active editing state and returns true if successful. */
    public boolean cancelAction() {
        if (manualQueueOpen) {
            closeManualQueue();
            return true;
        }
        if (isEditing || isNaming || isConfirmingDelete) {
            isEditing = false;
            isNaming = false;
            isConfirmingDelete = false;
            render();
            return true;
        }
        if (categoryOpen) {
            showHome();
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Opens the menu: saves focus mode, locks to manual, resets nav state. */
    public void open() {
        Camera cam = host.getCamera();
        if (cam != null) {
            try {
                cam.cancelAutoFocus();
                Camera.Parameters p = cam.getParameters();
                savedFocusMode = p.getFocusMode();
                List<String> fModes = p.getSupportedFocusModes();
                if (fModes != null && fModes.contains("manual")) {
                    p.setFocusMode("manual");
                    cam.setParameters(p);
                }
            } catch (Exception ignored) {}
        }
        host.onMenuOpened();
        host.closeHud();
        isOpen      = true;

        // Always reopen to the category dashboard so the menu starts at a predictable place.
        isEditing   = false;
        isNaming    = false;
        manualQueueOpen = false;
        categoryOpen = false;
        selection = 0;
        headerSelection = 1;

        container.setVisibility(View.VISIBLE);
        host.getMainUIContainer().setVisibility(View.GONE);
        render();
    }

    /** Closes the menu, restores camera state, fires post-menu host actions. */
    public void close() {
        isOpen             = false;
        isNaming           = false;
        isConfirmingDelete = false;
        manualQueueOpen    = false;
        categoryOpen       = false;
        host.closeHud();
        container.setVisibility(View.GONE);
        host.getMainUIContainer().setVisibility(
                host.getDisplayState() == 0 ? View.VISIBLE : View.GONE);
        host.restoreFocusMode(savedFocusMode);
        host.onMenuClosed();
    }

    // -----------------------------------------------------------------------
    // Directional navigation (called from MainActivity input handlers)
    // All return true if the menu consumed the event.
    // -----------------------------------------------------------------------

    public boolean handleUp() {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(1); return true; }
        if (isEditing) { handleMenuChange(1); return true; }
        if (manualQueueOpen) {
            if (itemCount <= 0) {
                selection = -2;
            } else if (selection == -2) {
                selection = itemCount - 1;
            } else if (selection <= 0) {
                selection = -2;
            } else {
                selection--;
            }
            render();
            return true;
        }
        if (isHome()) {
            moveHomeSelection(-1, 0);
            render();
            return true;
        }
        if (selection == 0)           { selection = -2; headerSelection = activeHeaderSelection(); }
        else if (selection == -2)     { selection = itemCount - 1; if (selection < 0) selection = -2; }
        else                          selection--;
        render();
        return true;
    }

    public boolean handleDown() {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(-1); return true; }
        if (isEditing) { handleMenuChange(-1); return true; }
        if (manualQueueOpen) {
            if (itemCount <= 0) {
                selection = -2;
            } else if (selection == -2) {
                selection = 0;
            } else if (selection >= itemCount - 1) {
                selection = -2;
            } else {
                selection++;
            }
            render();
            return true;
        }
        if (isHome()) {
            moveHomeSelection(1, 0);
            render();
            return true;
        }
        if (selection == -2)                    { selection = 0; if (itemCount == 0) selection = -2; }
        else if (selection == itemCount - 1)    selection = -2;
        else                                    selection++;
        render();
        return true;
    }

    public boolean handleLeft() {
        if (!isOpen) return false;
        if (manualQueueOpen) {
            manualQueueOffset -= MANUAL_QUEUE_PAGE_SIZE;
            if (manualQueueOffset < 0) manualQueueOffset = 0;
            selection = selection == -2 ? -2 : 0;
            render();
            return true;
        }
        if (isNaming) {
            nameCursorPos = Math.max(0, nameCursorPos - 1);
            render();
            return true;
        }
        if (isEditing) {
            handleMenuChange(-1);
            return true;
        }
        if (isHome()) {
            moveHomeSelection(0, -1);
            render();
            return true;
        }
        if (selection == -2) {
            moveHeaderSelection(-1);
            render();
        } else {
            moveCategoryPage(-1);
            render();
        }
        return true;
    }

    public boolean handleRight() {
        if (!isOpen) return false;
        if (manualQueueOpen) {
            int count = host.getQueuedPhotoCount();
            manualQueueOffset += MANUAL_QUEUE_PAGE_SIZE;
            if (manualQueueOffset >= count) manualQueueOffset = Math.max(0, count - MANUAL_QUEUE_PAGE_SIZE);
            selection = selection == -2 ? -2 : 0;
            render();
            return true;
        }
        if (isNaming) {
            nameCursorPos = Math.min(7, nameCursorPos + 1);
            render();
            return true;
        }
        if (isEditing) {
            handleMenuChange(1);
            return true;
        }
        if (isHome()) {
            moveHomeSelection(0, 1);
            render();
            return true;
        }
        if (selection == -2) {
            moveHeaderSelection(1);
            render();
        } else {
            moveCategoryPage(1);
            render();
        }
        return true;
    }

    public boolean handleDial(int dir) {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(dir); return true; }
        if (isEditing) { handleMenuChange(dir); return true; }
        // Wheel scrolls item selection when not editing
        if (dir > 0) handleDown(); else handleUp();
        return true;
    }

    /** Advances the name buffer cursor — also called from HUD naming mode. */
    public void advanceNameCursor(int dir) {
        nameCursorPos += dir;
        if (nameCursorPos < 0) nameCursorPos = 0;
        if (nameCursorPos > 11) nameCursorPos = 11;
    }

    /** ENTER while menu is open: toggle editing, launch HUDs, or handle connection page. */
    public boolean handleEnter() {
        if (!isOpen) return false;
        if (manualQueueOpen) return handleManualQueueEnter();
        if (isHome()) return handleHomeEnter();
        if (selection == -2) {
            if (headerSelection == 0) showHome();
            else selectHeaderPage(headerSelection - 1);
            return true;
        }
        if (currentPage == 8) { handleConnectionAction(); return true; }
        if (selection == -2) return true; // Tab level — enter does nothing
        if (selection < 0)   return true; // Subtitle row — enter does nothing
        isEditing = !isEditing;
        render();
        return true;
    }

    private void openManualQueue() {
        manualQueueOpen = true;
        categoryOpen = false;
        isEditing = false;
        isNaming = false;
        manualQueueOffset = 0;
        ensureManualSelectionSize(host.getQueuedPhotoCount());
        selection = 0;
        render();
    }

    private void closeManualQueue() {
        manualQueueOpen = false;
        isEditing = false;
        isNaming = false;
        categoryOpen = false;
        selection = 5;
        headerSelection = 1;
        render();
    }

    private boolean handleManualQueueEnter() {
        if (selection == -2) {
            closeManualQueue();
            return true;
        }
        int queueCount = host.getQueuedPhotoCount();
        ensureManualSelectionSize(queueCount);
        int photoRows = getManualVisiblePhotoCount(queueCount);
        int actionStartRow = photoRows + (queueCount == 0 ? 1 : 0);
        int processRow = actionStartRow;
        int clearSelectedRow = processRow + 1;
        int clearAllRow = clearSelectedRow + 1;

        if (selection < photoRows) {
            int idx = manualQueueOffset + selection;
            if (idx >= 0 && idx < manualSelected.length) manualSelected[idx] = !manualSelected[idx];
            render();
            return true;
        }
        if (selection == processRow) {
            if (getManualSelectedCount() > 0) {
                host.processSelectedQueuedPhotos(manualSelected);
                close();
            }
            return true;
        }
        if (selection == clearSelectedRow) {
            if (getManualSelectedCount() > 0) {
                host.clearSelectedQueuedPhotos(manualSelected);
                manualSelected = new boolean[0];
                ensureManualSelectionSize(host.getQueuedPhotoCount());
                if (manualQueueOffset >= host.getQueuedPhotoCount()) {
                    manualQueueOffset = Math.max(0, host.getQueuedPhotoCount() - MANUAL_QUEUE_PAGE_SIZE);
                }
                int newCount = host.getQueuedPhotoCount();
                int newPhotoRows = getManualVisiblePhotoCount(newCount);
                selection = newPhotoRows + (newCount == 0 ? 1 : 0) + 1;
                clearThumbnailCache();
                render();
            }
            return true;
        }
        if (selection == clearAllRow) {
            host.clearQueuedPhotos();
            manualSelected = new boolean[0];
            manualQueueOffset = 0;
            selection = 0;
            clearThumbnailCache();
            render();
            return true;
        }
        return true;
    }

    private int getManualVisiblePhotoCount(int queueCount) {
        int remaining = queueCount - manualQueueOffset;
        if (remaining < 0) remaining = 0;
        return Math.min(MANUAL_QUEUE_PAGE_SIZE, remaining);
    }

    private void ensureManualSelectionSize(int count) {
        if (count < 0) count = 0;
        if (manualSelected.length == count) return;
        boolean[] next = new boolean[count];
        int copyLen = Math.min(count, manualSelected.length);
        for (int i = 0; i < copyLen; i++) next[i] = manualSelected[i];
        manualSelected = next;
    }

    private int getManualSelectedCount() {
        int count = 0;
        for (int i = 0; i < manualSelected.length; i++) {
            if (manualSelected[i]) count++;
        }
        return count;
    }

    /** Launch HUD modes from menu — called from onEnterPressed page/selection dispatch. */
    public boolean dispatchHudLaunch() {
        if (!isOpen) return false;
        if (manualQueueOpen || !categoryOpen) return false;
        if (currentMainTab == 0 && currentPage == 1 && selection == 1) { host.onHudModeRequested(10); return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 2) { host.onHudModeRequested(6);  return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 3) { host.onHudModeRequested(3);  return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 4) { host.onHudModeRequested(9);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 0) { host.onHudModeRequested(2);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 1) { host.onHudModeRequested(7);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 2) { host.onHudModeRequested(1);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 3) { host.onHudModeRequested(0);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 0) { host.onHudModeRequested(8);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 2) { host.onHudModeRequested(4);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 1) {
            RTLProfile p = host.getRecipeManager().getCurrentProfile();
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if ("toy-camera".equals(eff)||"soft-focus".equals(eff)||"hdr-art".equals(eff)
                    ||"illust".equals(eff)||"watercolor".equals(eff)||"part-color".equals(eff)||"miniature".equals(eff)) {
                host.onHudModeRequested(5); return true;
            }
        }
        return false;
    }

    /** Status update from ConnectivityManager — routes to correct status string. */
    public void updateConnectionStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) hotspotStatus = status;
        else wifiStatus = status;
        if (isOpen && categoryOpen && currentPage == 8) render();
    }

    // -----------------------------------------------------------------------
    // Private — menu data change
    // -----------------------------------------------------------------------

    public String[] getSupportedColorModes() {
        if (cachedColorModes != null) return cachedColorModes;

        // This is the hardcoded list you were seeing!
        String[] fallback = {"Standard","Vivid","Neutral","Clear","Deep","Light","Portrait","Landscape","Sunset","Night Scene","Autumn Leaves","Mono","Sepia"};

        Camera cam = host.getCamera();
        if (cam != null) {
            try {
                Camera.Parameters p = cam.getParameters();

                // Aggressively hunt for the list of supported values across all Sony models
                String vals = p.get("creative-style-values"); // <-- Added for A7 series
                if (vals == null || vals.isEmpty()) vals = p.get("color-mode-values"); // <-- Used by APS-C
                if (vals == null || vals.isEmpty()) vals = p.get("sony-creative-style-values");
                if (vals == null || vals.isEmpty()) vals = p.get("sony-st-color-mode-values");
                if (vals == null || vals.isEmpty()) vals = p.get("sony-colormode-values");

                if (vals != null && !vals.isEmpty()) {
                    String[] split = vals.split(",");
                    for (int i = 0; i < split.length; i++) {
                        String s = split[i].trim();
                        if (s.length() > 0) {
                            // Capitalize the first letter so it looks nice in the menu
                            split[i] = s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
                        }
                    }
                    cachedColorModes = split;
                    return cachedColorModes;
                }
            } catch (Exception e) {
                // If the camera is busy and throws an error, ignore it and use fallback
            }
        }
        return fallback;
    }

    private void handleNamingChange(int dir) {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();
        String name = p.profileName != null ? p.profileName : "";
        while (name.length() < 8) name += " ";
        if (name.length() > 8) name = name.substring(0, 8);
        char c = name.charAt(nameCursorPos);
        int idx = CHARSET.indexOf(c);
        if (idx == -1) idx = 0;
        idx = (idx + dir + CHARSET.length()) % CHARSET.length();
        p.profileName = name.substring(0, nameCursorPos) + CHARSET.charAt(idx) + name.substring(nameCursorPos + 1);
        render();
    }

    private void handleMenuChange(int dir) {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();
        int sel = selection;

        if (isHome()) {
            if (sel == 4) host.setProcessingFrequency(nextProcessingFrequency(host.getProcessingFrequency(), dir));
            render();
            rm.savePreferences();
            host.scheduleHardwareApply();
            return;
        }

        if (currentPage == 1) {
            if (sel == 0 && !isNaming) {
                rm.savePreferences();
                rm.setCurrentSlot(Math.max(0, Math.min(9, rm.getCurrentSlot() + dir)));
                host.onLutPreloadNeeded();
            } else if (sel == 2) {
                String[] styles = getSupportedColorModes();
                int idx = 0; for (int i = 0; i < styles.length; i++) if (styles[i].equalsIgnoreCase(p.colorMode)) idx = i;
                p.colorMode = styles[(idx + dir + styles.length) % styles.length];
            } else if (sel == 4) {
                String[] dro = {"OFF","AUTO","LVL 1","LVL 2","LVL 3","LVL 4","LVL 5"};
                int idx = 0; for (int i = 0; i < dro.length; i++) if (dro[i].equalsIgnoreCase(p.dro)) idx = i;
                p.dro = dro[(idx + dir + dro.length) % dro.length];
            }
        } else if (currentPage == 2) {
            if (sel == 1) p.whiteBalance = cycleKelvin(p.whiteBalance, dir);
        } else if (currentPage == 3) {
            if (sel == 0) {
                String[] eff = {"off","toy-camera","pop-color","posterization","retro-photo","soft-high-key","partial-color","high-contrast-mono","soft-focus","hdr-painting","rich-tone-mono","miniature","watercolor","illustration"};
                int idx = 0; for (int i = 0; i < eff.length; i++) if (eff[i].equals(p.pictureEffect)) idx = i;
                p.pictureEffect = eff[(idx + dir + eff.length) % eff.length];
            } else if (sel == 2) p.softFocusLevel = Math.max(1, Math.min(3, p.softFocusLevel + dir));
        } else if (currentPage == 4) {
            if (sel == 0) { if (dir > 0 && p.lutIndex < rm.getRecipeNames().size()-1) p.lutIndex++; else if (dir < 0 && p.lutIndex > 0) p.lutIndex--; }
            else if (sel == 1 && p.lutIndex > 0) p.opacity = Math.max(10, Math.min(100, p.opacity + dir * 10));
            else if (sel == 2) p.grain = Math.max(0, Math.min(5, p.grain + dir));

            // <--- CHANGED: Dynamically bounds the D-Pad to the number of physical files found
            else if (sel == 3 && p.grain > 0) {
                int maxIdx = Math.max(0, grainTextureFiles.size() - 1);
                p.grainSize = Math.max(0, Math.min(maxIdx, p.grainSize + dir));
            }

            else if (sel == 4) p.vignette = Math.max(0, Math.min(5, p.vignette + dir));
        } else if (currentPage == 5) {
            if (sel == 0) p.rollOff        = Math.max(0, Math.min(5, p.rollOff + dir));
            else if (sel == 1) p.shadowToe = Math.max(0, Math.min(2, p.shadowToe + dir));
            else if (sel == 2) p.subtractiveSat = Math.max(0, Math.min(2, p.subtractiveSat + dir));
            else if (sel == 3) p.colorChrome = Math.max(0, Math.min(2, p.colorChrome + dir));
            else if (sel == 4) p.chromeBlue = Math.max(0, Math.min(2, p.chromeBlue + dir));
            else if (sel == 5) p.halation  = Math.max(0, Math.min(2, p.halation + dir));

            // Navigate bloom in logical order (OFF, Local 1/8, Full 1/8, …) even though
            // the stored value is the internal WASM representation.
            else if (sel == 6) {
                int[] bloomLogicalToInternal = {0, 5, 6, 1, 2, 3, 4};
                int[] bloomInternalToLogical = {0, 3, 4, 5, 6, 1, 2};
                int logical = bloomInternalToLogical[Math.max(0, Math.min(6, p.bloom))];
                logical = Math.max(0, Math.min(6, logical + dir));
                p.bloom = bloomLogicalToInternal[logical];
            }

        } else if (currentPage == 6) {
            if      (sel == 0) rm.setQualityIndex(Math.max(0, Math.min(2, rm.getQualityIndex() + dir)));
            else if (sel == 1) host.setPrefFocusMeter(!host.isPrefFocusMeter());
            else if (sel == 2) host.setPrefGridLines(!host.isPrefGridLines());
            else if (sel == 3) host.setPrefJpegQuality(Math.max(60, Math.min(100, host.getPrefJpegQuality() + dir * 5)));
            else if (sel == 4) rm.setMultiCoreEnabled(!rm.isMultiCoreEnabled());
            else if (sel == 5) {
                int mode = 0;
                if (host.isPrefCinemaMattes()) mode = 1;
                else if (host.isPrefDiptych()) mode = 2;
                else if (host.isPrefMultiExpose()) mode = 3;

                mode = (mode + dir + 4) % 4;

                host.setPrefCinemaMattes(mode == 1);
                host.setPrefDiptych(mode == 2);
                host.setPrefMultiExpose(mode == 3);
            }
            else if (sel == 6 && host.isPrefMultiExpose()) host.setMultiExposeCount(Math.max(2, Math.min(9, host.getMultiExposeCount() + dir)));
            else if (sel == 7 && host.isPrefMultiExpose()) host.setMultiExposeBlendMode(host.getMultiExposeBlendMode() == 0 ? 1 : 0);
        } else if (currentPage == 7) {
            if      (sel == 0) rm.setPrefC1(clampCustomButtonAction(rm.getPrefC1() + dir));
            else if (sel == 1) rm.setPrefC2(clampCustomButtonAction(rm.getPrefC2() + dir));
            else if (sel == 2) rm.setPrefC3(clampCustomButtonAction(rm.getPrefC3() + dir));
            else if (sel == 3) rm.setPrefAel(clampCustomButtonAction(rm.getPrefAel() + dir));
            else if (sel == 4) rm.setPrefFn(clampCustomButtonAction(rm.getPrefFn() + dir));
        }

        render();
        rm.savePreferences();
        host.scheduleHardwareApply();
    }

    private int nextProcessingFrequency(int current, int dir) {
        int idx = current == 3 ? 1 : (current == 5 ? 2 : (current == PROCESSING_FREQUENCY_MANUAL ? 3 : 0));
        idx = (idx + (dir >= 0 ? 1 : -1) + 4) % 4;
        if (idx == 1) return 3;
        if (idx == 2) return 5;
        if (idx == 3) return PROCESSING_FREQUENCY_MANUAL;
        return 1;
    }

    private int clampCustomButtonAction(int action) {
        return Math.max(0, Math.min(CUSTOM_BUTTON_ACTION_MAX, action));
    }

    private String customButtonLabel(int action) {
        return CUSTOM_BUTTON_LABELS[clampCustomButtonAction(action)];
    }

    private void handleConnectionAction() {
        if (selection == 0) {
            hotspotStatus = "Starting...";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().startHotspot(); host.onSetAutoPowerOffMode(false); }
        } else if (selection == 1) {
            wifiStatus = "Connecting...";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().startHomeWifi(); host.onSetAutoPowerOffMode(false); }
        } else if (selection == 2) {
            hotspotStatus = "Press ENTER";
            wifiStatus    = "Press ENTER";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().stopNetworking(); host.onSetAutoPowerOffMode(true); }
        }
        render();
    }

    // -----------------------------------------------------------------------
    // Private — rendering
    // -----------------------------------------------------------------------

    private void render() {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();

        if (isHome()) {
            renderHome();
            return;
        }

        homeContainer.setVisibility(View.GONE);
        tabRow.setVisibility(View.VISIBLE);
        tvSubtitle.setVisibility(View.VISIBLE);
        pageDivider.setVisibility(View.VISIBLE);
        supportContainer.setVisibility(View.GONE);

        // Detail page tabs
        int accent = manualQueueOpen ? UiTheme.ACCENT : currentTabAccent();
        if (manualQueueOpen) configureBackOnlyHeader(accent);
        else configurePageTabs(accent);

        // Subtitle
        if (selection == -1) UiTheme.selected(tvSubtitle, accent);
        else UiTheme.clear(tvSubtitle);
        String[] subtitles = {"","RECIPES - Identity & Base","RECIPES - Color Engine","RECIPES - Effects & Shading","RECIPES - LUTs & Grain","RECIPES - Analog Physics","SETTINGS - App Preferences","SETTINGS - Custom Buttons","NETWORK - Web Dashboard","SUPPORT - Resources"};
        if (currentPage >= 1 && currentPage <= 9) {
            tvSubtitle.setText(subtitles[currentPage]);
        }

        for (int i = 0; i < 8; i++) {
            rows[i].setVisibility(View.GONE);
            thumbs[i].setVisibility(View.GONE);
            thumbs[i].setImageBitmap(null);
            thumbs[i].setTag(null);
            if (i < rowDividers.length && rowDividers[i] != null) rowDividers[i].setVisibility(View.GONE);
        }

        if (manualQueueOpen) {
            renderManualQueue(accent);
            return;
        }

        if (currentPage == 9) {
            tvSubtitle.setVisibility(View.GONE);
            pageDivider.setVisibility(View.GONE);
            supportContainer.setVisibility(View.VISIBLE);
            itemCount = 0;
            return;
        }

        String[] amtLbls  = {"OFF","LOW","MED","HIGH","V.HIGH","MAX"};
        int ic = 0;

        if (currentMainTab == 0) {
            if (currentPage == 1) {
                ic = 5;
                String raw = p.profileName != null ? p.profileName : "";
                while (raw.length() < 8) raw += " ";
                if (raw.length() > 8) raw = raw.substring(0, 8);
                String dispName = raw;
                if (isNaming && selection == 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        char c = raw.charAt(i);
                        String cs = (c == ' ') ? "&nbsp;" : String.valueOf(c);
                        if (i == nameCursorPos) sb.append("<font color='#00FFFF'><u>").append(cs).append("</u></font>");
                        else sb.append(cs);
                    }
                    dispName = sb.toString();
                }
                String fnd = "[ " + (p.colorMode != null ? p.colorMode : "STD").toUpperCase() + " | M-CON " + String.format("%+d", p.sharpnessGain) + " ]";
                String ts  = String.format("[ %+d,  %+d,  %+d ]", p.contrast, p.saturation, p.sharpness);
                String activeName = (p.profileName != null && !p.profileName.isEmpty()) ? p.profileName : "UNNAMED";
                setRow(0, "Recipe Slot (1-10)",  String.valueOf(rm.getCurrentSlot() + 1));
                setRow(1, "Recipe Manager",      "< " + activeName + " >");
                setRow(2, "Foundation Base",       fnd);
                setRow(3, "Tone & Style",          ts);
                setRow(4, "DRO (Dynamic Range)",   p.dro != null ? p.dro.toUpperCase() : "OFF");
            } else if (currentPage == 2) {
                ic = 4;
                String ab = p.wbShift == 0 ? "0" : (p.wbShift < 0 ? "B"+Math.abs(p.wbShift) : "A"+p.wbShift);
                String gm = p.wbShiftGM == 0 ? "0" : (p.wbShiftGM < 0 ? "M"+Math.abs(p.wbShiftGM) : "G"+p.wbShiftGM);
                boolean sixStd = p.colorDepthRed==0&&p.colorDepthGreen==0&&p.colorDepthBlue==0&&p.colorDepthCyan==0&&p.colorDepthMagenta==0&&p.colorDepthYellow==0;
                boolean mtxStd = p.advMatrix[0]==100&&p.advMatrix[1]==0&&p.advMatrix[2]==0&&p.advMatrix[3]==0&&p.advMatrix[4]==100&&p.advMatrix[5]==0&&p.advMatrix[6]==0&&p.advMatrix[7]==0&&p.advMatrix[8]==100;
                String mtxStr  = mtxStd ? "[ STANDARD ]" : "[ " + getActiveMatrixName(rm.getCurrentProfile(), host.getMatrixManager()) + " ]";
                setRow(0, "White Balance Shift", "[ " + ab + ", " + gm + " ]");
                setRow(1, "White Balance",        (p.whiteBalance != null ? p.whiteBalance : "Auto").toUpperCase());
                setRow(2, "6-Axis Color Depths",  sixStd ? "[ STANDARD ]" : "[ CUSTOM ]");
                setRow(3, "BIONZ RGB Matrix",     mtxStr);
            } else if (currentPage == 3) {
                ic = 3;
                String eff = p.pictureEffect != null ? p.pictureEffect : "off";
                String param = buildEffectParam(p, eff);
                String shade = "[ R " + String.format("%+d", p.shadingRed) + " | B " + String.format("%+d", p.shadingBlue) + " ]";
                setRow(0, "Picture Effect Base", eff.toUpperCase());
                setRow(1, "Effect Tweaker",       param);
                setRow(2, "Edge Shading Editor",  shade);
            } else if (currentPage == 4) {
                ic = 5;

                // For .cam bundles the LUT lives inside the ZIP, not on the SD card.
                // Show the bundled name when available; fall back to the loose-file list.
                String lutDisplay = (p.camFile != null
                        && p.bundledLutName != null
                        && !"NONE".equalsIgnoreCase(p.bundledLutName))
                        ? p.bundledLutName
                        : rm.getRecipeNames().get(p.lutIndex);
                setRow(0, "LUT File",    lutDisplay);
                setRow(1, "LUT Opacity", p.opacity + "%");
                setRow(2, "Grain Amount",amtLbls[Math.max(0,Math.min(5,p.grain))]);

                // For .cam bundles show the bundled grain name; otherwise use the SD card list.
                String grainDisplay;
                if (p.camFile != null
                        && p.bundledGrainName != null
                        && !"NONE".equalsIgnoreCase(p.bundledGrainName)) {
                    grainDisplay = p.bundledGrainName;
                } else {
                    String[] typeLbls = getGrainEngineOptions();
                    int safeIdx = Math.max(0, Math.min(typeLbls.length - 1, p.grainSize));
                    grainDisplay = typeLbls[safeIdx];
                }
                setRow(3, "Grain Type",  grainDisplay);

                setRow(4, "Vignette",    amtLbls[Math.max(0,Math.min(5,p.vignette))]);
            } else if (currentPage == 5) {
                ic = 7; // CHANGED TO 7
                setRow(0, "Highlight Roll-Off",    amtLbls[Math.max(0,Math.min(5,p.rollOff))]);
                setRow(1, "Shadow Roll-Off (Toe)",  p.shadowToe==0?"OFF":(p.shadowToe==1?"WEAK":"FILMIC"));
                setRow(2, "Subtractive Sat",        p.subtractiveSat==0?"OFF":(p.subtractiveSat==1?"WEAK":"HEAVY"));
                setRow(3, "Color Chrome",           p.colorChrome==0?"OFF":(p.colorChrome==1?"WEAK":"STRONG"));
                setRow(4, "Chrome Blue",            p.chromeBlue==0?"OFF":(p.chromeBlue==1?"WEAK":"STRONG"));
                setRow(5, "Halation",    p.halation==0?"OFF":(p.halation==1?"WEAK":"STRONG"));

                // Bloom: RecipeStudio stores an internal WASM value (BLOOM_LOGICAL_TO_INTERNAL =
                // [0,5,6,1,2,3,4]), NOT the label index.  Reverse-map to get the display label.
                // internal→logical: 0→0, 1→3, 2→4, 3→5, 4→6, 5→1, 6→2
                String[] bloomLbls = {"OFF", "Local 1/8", "Full 1/8", "Local 1/4", "Full 1/4", "Local 1/2", "Full 1/2"};
                int[] bloomInternalToLogical = {0, 3, 4, 5, 6, 1, 2};
                int safeBloom = Math.max(0, Math.min(6, p.bloom));
                setRow(6, "Diffusion", bloomLbls[bloomInternalToLogical[safeBloom]]);
            }
        }
        if (currentPage == 6) {
            ic = 6;
            String[] qLbls = {"1/4 RES","HALF RES","FULL RES"};

            String creativeMode = "OFF";
            if (host.isPrefCinemaMattes()) creativeMode = "XPAN CROP";
            else if (host.isPrefDiptych()) creativeMode = "DIPTYCH";
            else if (host.isPrefMultiExpose()) creativeMode = "MULTI-EXP";

            setRow(0, "SW Global Resolution", qLbls[rm.getQualityIndex()]);
            setRow(1, "Manual Focus Meter",    host.isPrefFocusMeter()   ? "ON" : "OFF");
            setRow(2, "Rule of Thirds Grid",   host.isPrefGridLines()    ? "ON" : "OFF");
            setRow(3, "SW JPEG Quality",       String.valueOf(host.getPrefJpegQuality()));
            setRow(4, "CPU Engine",            rm.isMultiCoreEnabled() ? "MULTI-CORE" : "SINGLE-CORE");
            setRow(5, "Creative Modes",        creativeMode);
            
            if (host.isPrefMultiExpose()) {
                setRow(6, "  > Exposure Count", String.valueOf(host.getMultiExposeCount()) + " SHOTS");
                setRow(7, "  > Blend Mode",     host.getMultiExposeBlendMode() == 0 ? "AVERAGE" : "LIGHTEN");
                ic = 8;
            }
        } else if (currentPage == 7) {
            ic = 5;
            setRow(0, "Custom 1 (C1)", customButtonLabel(rm.getPrefC1()));
            setRow(1, "Custom 2 (C2)", customButtonLabel(rm.getPrefC2()));
            setRow(2, "Custom 3 (C3)", customButtonLabel(rm.getPrefC3()));
            setRow(3, "AEL Button",    customButtonLabel(rm.getPrefAel()));
            setRow(4, "FN Button",     customButtonLabel(rm.getPrefFn()));
        } else if (currentPage == 8) {
            ic = 3;
            setRow(0, "Camera Hotspot", hotspotStatus);
            setRow(1, "Home Wi-Fi",     wifiStatus);
            setRow(2, "Stop Networking","");
        }

        highlightRows(ic, accent);
        itemCount = ic;
    }

    private void renderManualQueue(int accent) {
        int queueCount = host.getQueuedPhotoCount();
        ensureManualSelectionSize(queueCount);
        if (manualQueueOffset >= queueCount) manualQueueOffset = Math.max(0, queueCount - MANUAL_QUEUE_PAGE_SIZE);

        int end = Math.min(queueCount, manualQueueOffset + MANUAL_QUEUE_PAGE_SIZE);
        UiTheme.clear(tvSubtitle);

        int row = 0;
        boolean thumbsLoading = false;
        for (int i = manualQueueOffset; i < end; i++) {
            ProcessingQueueManager.Entry entry = host.getQueuedPhotoEntry(i);
            String marker = i < manualSelected.length && manualSelected[i] ? "[X] " : "[ ] ";
            String imagePath = entry != null ? entry.originalPath : null;
            if (setQueueRow(row, marker + queueDisplayName(entry), queueRecipeName(entry), imagePath)) {
                thumbsLoading = true;
            }
            row++;
        }
        if (queueCount > 0) {
            setManualQueueSubtitle(queueCount, end, thumbsLoading);
        } else {
            tvSubtitle.setText("Manual Queue (EMPTY)");
        }
        if (queueCount == 0) {
            setRow(row++, "No Queued Photos", "");
        }

        int selected = getManualSelectedCount();
        setRow(row++, "Process Selected", selected > 0 ? (selected + " | " + host.getProcessingEstimateText(selected)) : "NONE");
        setRow(row++, "Clear Selected", selected > 0 ? (selected + " ITEMS") : "NONE");
        setRow(row++, "Clear All", queueCount > 0 ? (queueCount + " ITEMS") : "EMPTY");

        itemCount = row;
        if (selection >= itemCount) selection = itemCount - 1;
        if (selection < -2) selection = 0;
        if (selection < 0 && selection != -2) selection = 0;
        highlightRows(row, accent);
    }

    private void highlightRows(int ic, int accent) {
        updateRowDividers(ic);
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        for (int i = 0; i < ic; i++) {
            boolean active = manualQueueOpen || isRowActive(p, i);
            String plain = stripRowPrefix(labels[i].getText().toString());
            if (i == selection) {
                labels[i].setText(plain);
                if (!active) {
                    UiTheme.clear(rows[i]);
                    UiTheme.dimText(labels[i]);
                    UiTheme.dimText(values[i]);
                } else if (isEditing || isNaming) {
                    UiTheme.clear(rows[i]);
                    UiTheme.selectedText(labels[i]);
                    values[i].setTextColor(accent);
                    values[i].setShadowLayer(2, 0, 0, UiTheme.SHADOW);
                } else {
                    UiTheme.selected(rows[i], accent);
                    UiTheme.selectedText(labels[i]);
                    UiTheme.selectedText(values[i]);
                }
            } else {
                labels[i].setText(plain);
                UiTheme.clear(rows[i]);
                if (active) {
                    labels[i].setTextColor(UiTheme.TEXT);
                    values[i].setTextColor(UiTheme.TEXT_MUTED);
                } else {
                    UiTheme.dimText(labels[i]);
                    UiTheme.dimText(values[i]);
                }
            }
        }
    }

    private String stripRowPrefix(String text) {
        if (text == null) return "";
        if (text.startsWith("> ") || text.startsWith("  ")) return text.substring(2);
        return text;
    }

    private void setRow(int i, String label, String value) {
        labels[i].setText(label);
        values[i].setText(value);
        thumbs[i].setVisibility(View.GONE);
        thumbs[i].setImageBitmap(null);
        thumbs[i].setTag(null);
        rows[i].setVisibility(View.VISIBLE);
    }

    private boolean setQueueRow(int i, String label, String value, String imagePath) {
        setRow(i, label, value);
        thumbs[i].setVisibility(View.VISIBLE);
        if (imagePath == null || imagePath.length() == 0) return false;

        String cacheKey = getThumbnailCacheKey(imagePath);
        thumbs[i].setTag(cacheKey);
        Bitmap cached = thumbnailCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            thumbs[i].setImageBitmap(cached);
            return false;
        }
        if (thumbnailDecodeFailures.contains(cacheKey)) return false;

        requestQueueThumbnail(i, imagePath, cacheKey);
        return true;
    }

    private String getThumbnailCacheKey(String imagePath) {
        File f = new File(imagePath);
        return imagePath + "|" + f.length() + "|" + f.lastModified();
    }

    private void requestQueueThumbnail(final int row, final String imagePath, final String cacheKey) {
        if (thumbnailLoadsInFlight.contains(cacheKey)) return;
        thumbnailLoadsInFlight.add(cacheKey);
        thumbnailHandler.post(new Runnable() {
            @Override public void run() {
                final Bitmap bitmap = decodeQueueThumbnail(imagePath);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        thumbnailLoadsInFlight.remove(cacheKey);
                        if (bitmap != null) {
                            putThumbnailCache(cacheKey, bitmap);
                        } else {
                            thumbnailDecodeFailures.add(cacheKey);
                        }

                        if (manualQueueOpen) {
                            if (row >= 0 && row < thumbs.length && cacheKey.equals(thumbs[row].getTag()) && bitmap != null) {
                                thumbs[row].setImageBitmap(bitmap);
                            }
                            refreshManualQueueLoadingLabel();
                        }
                    }
                });
            }
        });
    }

    private Bitmap decodeQueueThumbnail(String imagePath) {
        Bitmap embedded = decodeExifThumbnail(imagePath);
        if (embedded != null) return embedded;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateThumbnailSample(bounds.outWidth, bounds.outHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inDither = true;
            return BitmapFactory.decodeFile(imagePath, opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeExifThumbnail(String imagePath) {
        try {
            ExifInterface exif = new ExifInterface(imagePath);
            byte[] data = exif.getThumbnail();
            if (data == null || data.length == 0) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateThumbnailSample(bounds.outWidth, bounds.outHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inDither = true;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int calculateThumbnailSample(int width, int height) {
        if (width <= 0 || height <= 0) return 8;
        int sample = 1;
        while ((width / sample) > MANUAL_QUEUE_THUMB_WIDTH * 2 || (height / sample) > MANUAL_QUEUE_THUMB_HEIGHT * 2) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private void putThumbnailCache(String cacheKey, Bitmap bitmap) {
        if (thumbnailCache.size() >= MANUAL_QUEUE_THUMB_CACHE_LIMIT) {
            thumbnailCache.clear();
            thumbnailDecodeFailures.clear();
        }
        thumbnailCache.put(cacheKey, bitmap);
    }

    private void clearThumbnailCache() {
        thumbnailCache.clear();
        thumbnailLoadsInFlight.clear();
        thumbnailDecodeFailures.clear();
    }

    private void setManualQueueSubtitle(int queueCount, int end, boolean thumbsLoading) {
        tvSubtitle.setText("Manual Queue " + (manualQueueOffset + 1) + "-" + end + "/" + queueCount
                + (thumbsLoading ? " | LOADING" : ""));
    }

    private void refreshManualQueueLoadingLabel() {
        if (!manualQueueOpen) return;
        int queueCount = host.getQueuedPhotoCount();
        if (queueCount <= 0) {
            tvSubtitle.setText("Manual Queue (EMPTY)");
            return;
        }

        int end = Math.min(queueCount, manualQueueOffset + MANUAL_QUEUE_PAGE_SIZE);
        int photoRows = getManualVisiblePhotoCount(queueCount);
        boolean thumbsLoading = false;
        for (int i = 0; i < photoRows && i < thumbs.length; i++) {
            Object tag = thumbs[i].getTag();
            if (tag != null && thumbnailLoadsInFlight.contains(String.valueOf(tag))) {
                thumbsLoading = true;
                break;
            }
        }
        setManualQueueSubtitle(queueCount, end, thumbsLoading);
    }

    private String queueDisplayName(ProcessingQueueManager.Entry entry) {
        if (entry == null || entry.originalPath == null) return "PHOTO";
        String name = new File(entry.originalPath).getName();
        if (name.length() > 12) name = name.substring(0, 9) + "...";
        return name;
    }

    private String queueRecipeName(ProcessingQueueManager.Entry entry) {
        String name = entry != null && entry.profile != null ? entry.profile.profileName : "";
        if (name == null || name.length() == 0) name = "RECIPE";
        if (name.length() > 12) name = name.substring(0, 12);
        return name.toUpperCase();
    }

    private boolean isRowActive(RTLProfile p, int i) {
        if (currentMainTab == 0 && currentPage == 3 && i == 1) {
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            return "toy-camera".equals(eff)||"soft-focus".equals(eff)||"hdr-art".equals(eff)
                    ||"illust".equals(eff)||"watercolor".equals(eff)||"part-color".equals(eff)||"miniature".equals(eff);
        }
        if (currentMainTab == 0 && currentPage == 4) {
            if (i == 1) return p.lutIndex > 0;
            if (i == 3) return p.grain > 0; // <--- RESTORED: Grays out Type if Amount is OFF
        }
        return true;
    }

    private String buildEffectParam(RTLProfile p, String eff) {
        String tone = p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "NORM";
        if ("toy-camera".equals(eff)) {
            if ("NORMAL".equals(tone)) tone = "NORM"; else if ("MAGENTA".equals(tone)) tone = "MAG";
            return "[ " + tone + " | " + String.format("%+d", p.vignetteHardware) + " ]";
        } else if ("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)) {
            return "[ LVL: " + p.softFocusLevel + " ]";
        } else if ("part-color".equals(eff)) {
            return "[ COLOR: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "RED") + " ]";
        } else if ("miniature".equals(eff)) {
            return "[ AREA: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "AUTO") + " ]";
        }
        return "N/A";
    }

    private String getActiveMatrixName(RTLProfile p, MatrixManager mm) {
        if (mm == null || mm.getCount() == 0) return "CUSTOM";
        for (int f = 0; f < mm.getCount(); f++) {
            int[] loaded = mm.getValues(f);
            boolean match = true;
            for (int i = 0; i < 9; i++) if (p.advMatrix[i] != loaded[i]) { match = false; break; }
            if (match) return mm.getNames().get(f);
        }
        return "CUSTOM";
    }

    public static String cycleKelvin(String current, int dir) {
        if (current == null) current = "Auto";
        List<String> list = new ArrayList<>();
        list.add("Auto");
        for (int i = 2500; i <= 9900; i += 100) list.add(i + "K");
        int idx = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(current)) { idx = i; break; }
        return list.get((idx + dir + list.size()) % list.size());
    }

    private int tabToFirstPage(int tab) {
        if (tab == 0) return 1;
        if (tab == 1) return 6;
        if (tab == 2) return 8;
        return 9;
    }

    private int pageToTab(int page) {
        if (page <= 5) return 0;
        if (page <= 7) return 1;
        if (page == 8) return 2;
        return 3;
    }

    private boolean isHome() {
        return !categoryOpen && !manualQueueOpen;
    }

    private boolean handleHomeEnter() {
        if (selection >= 0 && selection <= 3) {
            openCategory(selection);
            return true;
        }
        if (selection == 4) {
            isEditing = !isEditing;
            render();
            return true;
        }
        if (selection == 5) {
            if (host.getProcessingFrequency() == PROCESSING_FREQUENCY_MANUAL) {
                openManualQueue();
            } else if (host.getQueuedPhotoCount() > 0) {
                host.forceProcessQueuedPhotos();
                close();
            }
            return true;
        }
        return true;
    }

    private void openCategory(int tab) {
        categoryOpen = true;
        manualQueueOpen = false;
        isEditing = false;
        isNaming = false;
        isConfirmingDelete = false;
        currentMainTab = tab;
        currentPage = tabToFirstPage(tab);
        headerSelection = activeHeaderSelection();
        selection = -2;
        render();
    }

    private void showHome() {
        categoryOpen = false;
        manualQueueOpen = false;
        isEditing = false;
        isNaming = false;
        isConfirmingDelete = false;
        selection = 0;
        headerSelection = 1;
        render();
    }

    private void moveHomeSelection(int vertical, int horizontal) {
        if (horizontal != 0) {
            if (selection == 0) selection = 1;
            else if (selection == 1) selection = 0;
            else if (selection == 2) selection = 3;
            else if (selection == 3) selection = 2;
            else if (selection == 4) selection = 5;
            else if (selection == 5) selection = 4;
            return;
        }

        if (vertical > 0) {
            if (selection == 0) selection = 2;
            else if (selection == 1) selection = 3;
            else if (selection == 2 || selection == 3) selection = 4;
            else if (selection == 4) selection = 5;
            else selection = 0;
        } else {
            if (selection == 0 || selection == 1) selection = 5;
            else if (selection == 2) selection = 0;
            else if (selection == 3) selection = 1;
            else if (selection == 4) selection = 2;
            else selection = 4;
        }
    }

    private int[] categoryPages(int tab) {
        if (tab == 0) return new int[] {1, 2, 3, 4, 5};
        if (tab == 1) return new int[] {6, 7};
        if (tab == 2) return new int[] {8};
        return new int[] {9};
    }

    private String[] categoryPageLabels(int tab) {
        if (tab == 0) return new String[] {"BASE", "COLOR", "FX", "GRAIN", "ANALOG"};
        if (tab == 1) return new String[] {"APP", "BUTTONS"};
        if (tab == 2) return new String[] {"WEB"};
        return new String[] {"HELP"};
    }

    private int activeHeaderSelection() {
        int[] pages = categoryPages(currentMainTab);
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == currentPage) return i + 1;
        }
        return 1;
    }

    private void moveHeaderSelection(int dir) {
        int max = categoryPages(currentMainTab).length;
        headerSelection += dir;
        if (headerSelection < 0) headerSelection = max;
        if (headerSelection > max) headerSelection = 0;
    }

    private void selectHeaderPage(int pageTabIndex) {
        int[] pages = categoryPages(currentMainTab);
        if (pageTabIndex < 0 || pageTabIndex >= pages.length) return;
        currentPage = pages[pageTabIndex];
        currentMainTab = pageToTab(currentPage);
        headerSelection = pageTabIndex + 1;
        selection = -2;
        isEditing = false;
        isNaming = false;
        render();
    }

    private void moveCategoryPage(int dir) {
        int[] pages = categoryPages(currentMainTab);
        if (pages.length <= 1) return;
        int idx = 0;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == currentPage) {
                idx = i;
                break;
            }
        }
        idx = (idx + dir + pages.length) % pages.length;
        currentPage = pages[idx];
        headerSelection = idx + 1;
        selection = -2;
        isEditing = false;
        isNaming = false;
    }

    private void configurePageTabs(int accent) {
        String[] labels = categoryPageLabels(currentMainTab);
        int[] pages = categoryPages(currentMainTab);

        tabRow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tvBack.setText("BACK");
        UiTheme.pageTabPanel(tvBack, accent, selection == -2 && headerSelection == 0, false);
        tvBack.setTextColor(selection == -2 && headerSelection == 0 ? UiTheme.TEXT : UiTheme.TEXT_MUTED);

        for (int i = 0; i < pageTabs.length; i++) {
            TextView tab = pageTabs[i];
            if (i >= labels.length) {
                tab.setVisibility(View.GONE);
                continue;
            }
            tab.setVisibility(View.VISIBLE);
            tab.setText(labels[i]);
            boolean active = currentPage == pages[i];
            boolean selected = selection == -2 && headerSelection == i + 1;
            UiTheme.pageTabPanel(tab, accent, selected, active);
            tab.setTextColor(active || selected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        }
    }

    private void configureBackOnlyHeader(int accent) {
        tabRow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tvBack.setText("BACK");
        UiTheme.pageTabPanel(tvBack, accent, selection == -2, false);
        tvBack.setTextColor(selection == -2 ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        tvBack.setVisibility(View.VISIBLE);
        for (int i = 0; i < pageTabs.length; i++) {
            pageTabs[i].setVisibility(View.GONE);
        }
    }

    private void renderHome() {
        homeContainer.setVisibility(View.VISIBLE);
        tabRow.setVisibility(View.GONE);
        supportContainer.setVisibility(View.GONE);
        tvSubtitle.setVisibility(View.GONE);
        pageDivider.setVisibility(View.GONE);

        for (int i = 0; i < 8; i++) {
            rows[i].setVisibility(View.GONE);
            thumbs[i].setVisibility(View.GONE);
            thumbs[i].setImageBitmap(null);
            thumbs[i].setTag(null);
            if (i < rowDividers.length && rowDividers[i] != null) rowDividers[i].setVisibility(View.GONE);
        }

        styleHomeTile(0, "RECIPES", UiTheme.ACCENT_RECIPES, selection == 0);
        styleHomeTile(1, "SETTINGS", UiTheme.ACCENT_SETTINGS, selection == 1);
        styleHomeTile(2, "NETWORK", UiTheme.ACCENT_NETWORK, selection == 2);
        styleHomeTile(3, "SUPPORT", UiTheme.ACCENT_SUPPORT, selection == 3);

        int freq = host.getProcessingFrequency();
        String frequencyLabel = freq == PROCESSING_FREQUENCY_MANUAL ? "MANUAL" : (freq <= 1 ? "INSTANT" : (freq + " SHOTS"));
        int queueCount = host.getQueuedPhotoCount();
        styleHomeAction(homeProcessingFrequency, homeProcessingLabel, homeProcessingValue,
                "Processing Frequency", frequencyLabel,
                UiTheme.ACCENT, selection == 4, true, isEditing && selection == 4);

        String queueLabel = freq == PROCESSING_FREQUENCY_MANUAL ? "MANUAL QUEUE" : "PROCESS QUEUE";
        String queueValue = queueCount > 0 ? (queueCount + " WAITING") : "EMPTY";
        boolean queueActive = freq == PROCESSING_FREQUENCY_MANUAL || queueCount > 0;
        styleHomeAction(homeQueueAction, homeQueueLabel, homeQueueValue,
                queueLabel, queueValue,
                UiTheme.ACCENT, selection == 5, queueActive, false);
        itemCount = 6;
    }

    private void styleHomeTile(int index, String text, int accent, boolean selected) {
        TextView tile = homeTiles[index];
        tile.setText(text);
        UiTheme.tilePanel(tile, accent, selected);
        tile.setTextColor(UiTheme.TEXT);
        tile.setShadowLayer(selected ? 2 : 0, 0, 0, UiTheme.SHADOW);
    }

    private void styleHomeAction(LinearLayout view, TextView label, TextView value, String labelText, String valueText,
                                 int accent, boolean selected, boolean active, boolean editing) {
        label.setText(labelText.toUpperCase());
        value.setText(valueText.toUpperCase());
        if (selected) {
            UiTheme.selected(view, accent);
            label.setTextColor(UiTheme.TEXT);
            label.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
            value.setTextColor(editing ? UiTheme.WARN : UiTheme.TEXT);
            value.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
        } else if (!active) {
            UiTheme.actionPanel(view, accent, false, false);
            UiTheme.dimText(label);
            UiTheme.dimText(value);
        } else {
            UiTheme.actionPanel(view, accent, false, true);
            label.setTextColor(UiTheme.TEXT_MUTED);
            label.setShadowLayer(0, 0, 0, UiTheme.SHADOW);
            value.setTextColor(UiTheme.TEXT);
            value.setShadowLayer(0, 0, 0, UiTheme.SHADOW);
        }
    }

    private void updateRowDividers(int count) {
        for (int i = 0; i < rowDividers.length; i++) {
            if (rowDividers[i] != null) rowDividers[i].setVisibility(i < count - 1 ? View.VISIBLE : View.GONE);
        }
    }

    private TextView makeHomeTile(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(false);
        tv.setPadding(10, 16, 10, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.0f);
        lp.setMargins(5, 5, 5, 5);
        tv.setLayoutParams(lp);
        UiTheme.softPanel(tv);
        return tv;
    }

    private LinearLayout makeHomeActionRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12, 9, 12, 9);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, 6);
        row.setLayoutParams(lp);

        TextView label = new TextView(ctx);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        TextView value = new TextView(ctx);
        value.setTextSize(14);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        value.setSingleLine(true);

        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 0.72f));
        UiTheme.softPanel(row);
        return row;
    }

    private TextView makeTabHeader(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 9, 0, 9);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lp.setMargins(3, 0, 3, 0);
        tv.setLayoutParams(lp);
        tv.setTextColor(UiTheme.TEXT_MUTED);
        UiTheme.softPanel(tv);
        return tv;
    }

    private int currentTabAccent() {
        return tabAccent(currentMainTab);
    }

    private int tabAccent(int tab) {
        if (tab == 1) return UiTheme.ACCENT_SETTINGS;
        if (tab == 2) return UiTheme.ACCENT_NETWORK;
        if (tab == 3) return UiTheme.ACCENT_SUPPORT;
        return UiTheme.ACCENT_RECIPES;
    }
}
