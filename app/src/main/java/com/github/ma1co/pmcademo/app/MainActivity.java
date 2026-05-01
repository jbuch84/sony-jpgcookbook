package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback,
    SonyCameraManager.CameraEventListener, InputManager.InputListener,
    ConnectivityManager.StatusUpdateListener, PlaybackController.HostCallback,
    LensCalibrationController.HostCallback, MenuController.HostCallback,
    HudController.HostCallback {

    // --- GLOBAL DEBUG FLAG ---
    // Set to true to see diagnostic Toasts, false for clean public release
    public static final boolean DEBUG_MODE = false;
    private static final int PHOTO_READY_RETRY_MS = 75;
    private static final int PHOTO_READY_MAX_RETRIES = 240;
    private static final int PHOTO_READY_STABLE_CHECKS = 1;
    private static final int PHOTO_READY_FALLBACK_STABLE_CHECKS = 4;
    private static final int SCANNER_ARM_TIMEOUT_MS = 120000;
    private static final long QUEUE_FALLBACK_PROCESS_MS = 14000;
    private static final int PROCESSING_FREQUENCY_MANUAL = -1;

    private SonyCameraManager cameraManager;
    private InputManager inputManager;
    private RecipeManager recipeManager;
    private MatrixManager matrixManager;
    private ConnectivityManager connectivityManager;


    private Typeface digitalFont;

    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;
    private ProcessingQueueManager processingQueueManager;
    private ProcessingQueueManager.Entry pendingShotSnapshot;
    private ProcessingQueueManager.Entry activeQueueEntry;
    private boolean processingQueueActive = false;
    private boolean manualQueueProcessRequested = false;
    private int activeQueueMode = ProcessingQueueManager.MODE_AUTO;
    private int processingQueueTotal = 0;
    private int processingQueueCompleted = 0;
    private long processingQueueStartedMs = 0;
    private long processingQueueAverageMs = 0;
    private boolean captureWritePending = false;
    private int captureWriteToken = 0;
    private int pendingQueueProcessTargetCount = 0;

    private SurfaceView mSurfaceView;
    private boolean hasSurface = false;

    private FrameLayout mainUIContainer;
    private LinearLayout llBottomBar;

    private TextView tvTopStatus;
    private TextView tvBattery;
    private TextView tvValShutter;
    private TextView tvValAperture;
    private TextView tvValIso;
    private TextView tvValEv;
    private TextView tvMode;
    private TextView tvFocusMode;
    private TextView tvReview;

    // --- UNIVERSAL HUD VARIABLES ---
    private HudController hudController;


     // --- RGB MATRIX MATH ---
     // Converts hardware value (e.g., 1024) to a human percentage (e.g., 100)
    private int matrixToPercent(int hardwareValue) {
        return Math.round((hardwareValue / 1024.0f) * 100.0f);
    }
    // Converts a human percentage (e.g., 100) back to the hardware value (e.g., 1024)
    private int percentToMatrix(int percentValue) {
        return Math.round((percentValue / 100.0f) * 1024.0f);
    }


    private BatteryView batteryIcon;
    private PlaybackController playbackController;
    private MenuController menuController;
    private boolean isProcessing = false;

    public void setProcessing(boolean v) {
        this.isProcessing = v;
        updateDiptychPreviewWindow();
    }

    private boolean isReady = false;
    private int displayState = 0;


    private boolean prefShowCinemaMattes = false;
    private boolean prefShowGridLines = false;
    private int prefJpegQuality = 95;
    private int processingFrequency = 1;
    private LutEngine lutEngine;
    private DiptychManager diptychManager;
    private MultiExposeManager multiExposeManager;

    private LensProfileManager lensManager;
    private List<String> availableLenses = new ArrayList<String>();
    private int currentLensIndex = 0;

    private LensCalibrationController calibController;

    private float hardwareFocalLength = 0.0f;
    private boolean isNativeLensAttached = false;
    private boolean hasPhysicalPasmDial = false;
    private boolean isFullFrame = false;
    private BroadcastReceiver hardwareStateReceiver; // <--- ADD THIS LINE

    private float virtualAperture = 2.8f;
    private float virtualFocusRatio = 0.5f;

    private TextView tvCalibrationPrompt;

    private boolean cachedIsManualFocus = false;
    private float cachedAperture = 2.8f;
    private float cachedFocusRatio = 0.5f;

    // isMenuEditing thin proxy removed — use menuController.isEditingMode()

    private GridLinesView gridLines;
    private CinemaMatteView cinemaMattes;
    private AdvancedFocusMeterView focusMeter;
    private ProReticleView afOverlay;

    private Handler uiHandler = new Handler();
    private boolean liveHudFlashVisible = true;
    private boolean liveHudFlashRunning = false;
    private boolean liveUiSuppressed = false;
    private boolean batteryReceiverRegistered = false;
    private final Runnable liveHudFlashRunnable = new Runnable() {
        @Override public void run() {
            if (shouldPulseLiveHudSelection()) {
                liveHudFlashVisible = !liveHudFlashVisible;
                updateMainHUD();
                uiHandler.postDelayed(this, 420);
            } else {
                liveHudFlashRunning = false;
                liveHudFlashVisible = true;
            }
        }
    };

    private int getPreviewWindowWidth() {
        if (mSurfaceView != null && mSurfaceView.getWidth() > 0) return mSurfaceView.getWidth();
        if (mainUIContainer != null && mainUIContainer.getWidth() > 0) return mainUIContainer.getWidth();
        try {
            return getWindowManager().getDefaultDisplay().getWidth();
        } catch (Exception e) {
            return 0;
        }
    }

    public void updateDiptychPreviewWindow() {
        if (mSurfaceView == null) return;
        int width = getPreviewWindowWidth();
        if (width <= 0) return;

        // --- PHYSICAL ALIGNMENT ---
        // Live view is now always full-screen and centered.
        // AF/AE coordinates (-1000 to 1000) match the LCD 1:1.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        params.leftMargin = 0;
        mSurfaceView.setLayoutParams(params);
        mSurfaceView.invalidate();

        // --- HARDWARE AF/AE SYNC ---
        // Apply focus areas immediately so the user sees the 'Flexible Spot' box
        // in the correct half of the screen while framing.
        if (diptychManager != null && diptychManager.isEnabled()) {
            boolean isFirst = diptychManager.getState() == DiptychManager.STATE_NEED_FIRST || diptychManager.getState() == DiptychManager.STATE_PROCESSING_FIRST;
            boolean isSecond = diptychManager.getState() == DiptychManager.STATE_NEED_SECOND;
            
            int centerX = 0;
            if (isFirst) {
                centerX = 0;
            } else if (isSecond) {
                centerX = diptychManager.isThumbOnLeft() ? 500 : -500;
            }

            if (afOverlay != null) {
                afOverlay.setDiptychCenterX((width / 2) + ((centerX * width) / 2000));
            }

            if (cameraManager != null && cameraManager.getCamera() != null) {
                try {
                    android.hardware.Camera.Parameters p = cameraManager.getCamera().getParameters();
                    if (p.get("sony-focus-area") != null) p.set("sony-focus-area", "manual");
                    
                    java.util.List<android.hardware.Camera.Area> areas = new java.util.ArrayList<android.hardware.Camera.Area>();
                    int rectSize = 150;
                    areas.add(new android.hardware.Camera.Area(new android.graphics.Rect(centerX - rectSize, -rectSize, centerX + rectSize, rectSize), 1000));
                    
                    if (p.getMaxNumFocusAreas() > 0) p.setFocusAreas(areas);
                    if (p.getMaxNumMeteringAreas() > 0) p.setMeteringAreas(areas);
                    
                    cameraManager.getCamera().setParameters(p);
                } catch (Exception ignored) {}
            }
        }
    }

    public static final int DIAL_MODE_SHUTTER = 0;
    public static final int DIAL_MODE_APERTURE = 1;
    public static final int DIAL_MODE_ISO = 2;
    public static final int DIAL_MODE_EXPOSURE = 3;
    public static final int DIAL_MODE_REVIEW = 4;
    public static final int DIAL_MODE_RTL = 5;
    public static final int DIAL_MODE_PASM = 6;
    public static final int DIAL_MODE_FOCUS = 7;

    private int mDialMode = DIAL_MODE_RTL;
    private boolean isDialLocked = true; // <--- NEW: Defaults to locked

// --- MATRIX PRESET DATA ---
    private final String[] MATRIX_PRESET_NAMES = {"STANDARD", "GOLDEN HOUR", "PNW GREEN", "CINEMATIC", "BLEACH BYPASS", "AEROCHROME", "CUSTOM"};
    private final int[][] MATRIX_PRESET_VALUES = {
        {100, 0, 0, 0, 100, 0, 0, 0, 100},   // Standard
        {115, 5, 0, 5, 105, 0, 0, 0, 95},    // Golden Hour
        {95, 0, 0, 0, 110, 5, 0, 15, 105},   // PNW Green
        {110, -10, 0, -5, 100, 10, 0, 5, 115}, // Cinematic
        {130, 0, 0, 0, 130, 0, 0, 0, 130},   // Bleach Bypass
        {0, 140, 0, 100, 0, 0, 0, 0, 100}    // Aerochrome
    };
    private final String[] MATRIX_PRESET_NOTES = {
        "Identity Matrix. Zero color shift.",
        "Broadens the yellow spectrum. Pro Tip: If skin looks too yellow, drop R-G to 2%.",
        "Fuji-style vintage teals. Pairs best with an Amber (A2) White Balance shift.",
        "Professional Teal/Orange separation. Uses negative values to 'clean' the Red channel.",
        "High color density. WARNING: May clip highlights. Use -0.7 EV on camera.",
        "False-color Infrared swap. Turns foliage (Green) into candy-apple Red.",
        "Manual matrix override active. Row-sum balance not guaranteed."
    };


    private BroadcastReceiver sonyCameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED".equals(action)) {
                armFileScanner();
            }
        }
    };

    private Runnable applySettingsRunnable = new Runnable() {
        @Override
        public void run() { applyHardwareRecipe(); }
    };

    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !menuController.isOpen() && !playbackController.isActive() && !isProcessing && hasSurface) {
                if (cameraManager != null && cameraManager.getCamera() != null) {
                    boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                    boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;

                    if (s1_1_free && s1_2_free) {
                        if (afOverlay != null && afOverlay.isPolling()) {
                            afOverlay.stopFocus(cameraManager.getCamera());
                            requestHudUpdate();
                        }
                        if (!liveUiSuppressed && tvTopStatus != null && tvTopStatus.getVisibility() != View.VISIBLE) {
                            if (!calibController.isActive()) {
                                setHUDVisibility(View.VISIBLE);
                            }
                        }
                    }
                }
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            final int pct = normalizeBatteryPercent(level, scale, status);
            if (pct >= 0) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (tvBattery != null) tvBattery.setText(formatBatteryPercent(pct));
                        if (batteryIcon != null) batteryIcon.setLevel(pct);
                    }
                });
            }
        }
    };

    private int normalizeBatteryPercent(int level, int scale, int status) {
        if (status == BatteryManager.BATTERY_STATUS_FULL) return 100;
        if (level < 0 || scale <= 0) return -1;
        long pct = ((long) level * 100L + (scale / 2L)) / (long) scale;
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return (int) pct;
    }

    private String formatBatteryPercent(int pct) {
        if (pct < 0) return "--%";
        if (pct >= 100) return "100%";
        return pct + "%";
    }

    // --- FACTORY BURN SYSTEM ---
    private void factoryBurnMatrices() {
        if (matrixManager != null && matrixManager.getCount() == 0) {
            matrixManager.saveMatrix("STANDARD", new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100}, "Identity Matrix. Zero color shift.");
            matrixManager.saveMatrix("GOLDEN HOUR", new int[]{115, 5, 0, 5, 105, 0, 0, 0, 95}, "Broadens yellow spectrum. Drop R-G to 5% if too yellow.");
            matrixManager.saveMatrix("PAC. NW GREEN", new int[]{95, 0, 0, 0, 110, 5, 0, 15, 105}, "Vintage teals. Pairs best with Amber (A2) shift.");
            matrixManager.saveMatrix("CINEMATIC", new int[]{110, -10, 0, -5, 100, 10, 0, 5, 115}, "Teal/Orange separation. Uses negative values to clean Reds.");
            matrixManager.saveMatrix("BLEACH BYPASS", new int[]{130, 0, 0, 0, 130, 0, 0, 0, 130}, "High density. WARNING: May clip highlights. Use -0.7 EV.");
            matrixManager.saveMatrix("AEROCHROME", new int[]{0, 140, 0, 100, 0, 0, 0, 0, 100}, "False-color Infrared swap. Turns Green foliage Red.");
            matrixManager.scanMatrices(); // Reload list after saving
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // <--- NEW: Force the Android Window to 32-bit true color
        getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);

        // --- AUTOMATIC HARDWARE SCANNER ---
        // --- UNIVERSAL FEATURE DETECTION ---
        // Legacy cameras (API 10) will crash with a Dalvik "VerifyError" before the app opens
        // if we explicitly write new API methods. We use Reflection to hide it from the scanner!
        hasPhysicalPasmDial = false;
        try {
            java.lang.reflect.Method deviceHasKeyMethod = android.view.KeyCharacterMap.class.getMethod("deviceHasKey", int.class);
            boolean hasDialKey1 = (Boolean) deviceHasKeyMethod.invoke(null, 624);
            boolean hasDialKey2 = (Boolean) deviceHasKeyMethod.invoke(null, com.sony.scalar.sysutil.ScalarInput.ISV_KEY_MODE_DIAL);
            hasPhysicalPasmDial = hasDialKey1 || hasDialKey2;
        } catch (Exception e) {
            android.util.Log.e("JPEG.CAM", "Legacy API 10 Camera Detected. Relying on dynamic dial auto-discovery.");
        }

        android.util.Log.e("JPEG.CAM", "Universal Dial Detection: " + hasPhysicalPasmDial);

        // Force creation of our JPEGCAM folder skeleton immediately on boot
        Filepaths.buildAppStructure();

        // --- NEW: Extract our starter pack ---
        Filepaths.extractDefaultAssets(this);

        File thumbsDir = new File(Filepaths.getDcimDir(), ".thumbnails");
        if (!thumbsDir.exists()) thumbsDir.mkdirs();

        SharedPreferences prefs = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE);
        prefShowFocusMeter = prefs.getBoolean("focusMeter", false); // CHANGED: Default OFF
        prefShowCinemaMattes = prefs.getBoolean("cinemaMattes", false);
        prefShowGridLines = prefs.getBoolean("gridLines", true);
        prefJpegQuality = prefs.getInt("jpegQuality", 95);
        processingFrequency = normalizeProcessingFrequency(prefs.getInt("processingFrequency", 1));
        boolean prefShowDiptych = prefs.getBoolean("diptychEnabled", false);
        processingQueueManager = new ProcessingQueueManager();

        cameraManager = new SonyCameraManager(this);
        inputManager = new InputManager(this);
        recipeManager = new RecipeManager();
        matrixManager = new MatrixManager(); // <-- NEW
        matrixManager.scanMatrices();        // <-- NEW
        factoryBurnMatrices();               // <-- NEW: Generate defaults if empty!
        connectivityManager = new ConnectivityManager(this, this);

        lensManager = new LensProfileManager(this);
        availableLenses = lensManager.getAvailableLenses();

        recipeManager.loadPreferences();

        try {
            digitalFont = Typeface.createFromAsset(getAssets(), "fonts/digital-7.ttf");
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Could not load custom font. Did you add it to assets/fonts/?");
            digitalFont = null;
        }

        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));
        mSurfaceView.post(new Runnable() {
            @Override
            public void run() {
                updateDiptychPreviewWindow();
            }
        });

        buildUI(rootLayout);
        setContentView(rootLayout);
        if (prefShowDiptych && diptychManager != null) diptychManager.setEnabled(true);
        setupEngines();
        registerReceiver(sonyCameraReceiver, new IntentFilter("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED"));
        registerBatteryReceiver();
    }

    private void registerBatteryReceiver() {
        if (batteryReceiverRegistered) return;
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
        } catch (Exception e) {
            batteryReceiverRegistered = false;
        }
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); if (manualQueueProcessRequested) startQueuedProcessing(true, pendingQueueProcessTargetCount); else maybeAutoProcessQueuedPhotos(); } }); }
            @Override public void onProcessStarted() { runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setText(getProcessingStatusText()); tvTopStatus.setTextColor(UiTheme.WARN); } } }); }
        @Override public void onProcessFinished(String res) {
            if (processingQueueActive) {
                handleQueuedProcessFinished(res);
                return;
            }
            isProcessing = false;
            runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setTextColor(UiTheme.TEXT); } updateMainHUD(); } });
        }
        });

        mScanner = new SonyFileScanner(this, new SonyFileScanner.ScannerCallback() {
            @Override
            public boolean isReadyToProcess() {
                return !isProcessing && !calibController.isCalibrating();
            }
            @Override
            public void onNewPhotoDetected(final String path, final long scannerStartedMs, final long detectedMs, final int attempts) {
                processWhenFileReady(path, scannerStartedMs, detectedMs, attempts);
            }
        });

        triggerLutPreload();
    }

    private boolean hasSoftwareEffects(RTLProfile p) {
        return p != null && (p.lutIndex != 0 || p.grain != 0 || p.vignette != 0 ||
                p.rollOff != 0 || p.colorChrome != 0 || p.chromeBlue != 0 ||
                p.shadowToe != 0 || p.subtractiveSat != 0 || p.halation != 0 ||
                p.bloom != 0);
    }

    private int normalizeProcessingFrequency(int value) {
        if (value == PROCESSING_FREQUENCY_MANUAL) return PROCESSING_FREQUENCY_MANUAL;
        if (value == 3 || value == 5) return value;
        return 1;
    }

    private boolean shouldQueuePhotos() {
        return (processingFrequency == PROCESSING_FREQUENCY_MANUAL || processingFrequency > 1)
                && (diptychManager == null || !diptychManager.isEnabled());
    }

    private int currentQueueMode() {
        return processingFrequency == PROCESSING_FREQUENCY_MANUAL
                ? ProcessingQueueManager.MODE_MANUAL
                : ProcessingQueueManager.MODE_AUTO;
    }

    private ProcessingQueueManager.Entry createCurrentQueueEntry() {
        ProcessingQueueManager.Entry entry = new ProcessingQueueManager.Entry();
        entry.profile = ProcessingQueueManager.copyProfile(recipeManager.getCurrentProfile());
        entry.qualityIndex = recipeManager.getQualityIndex();
        entry.jpegQuality = prefJpegQuality;
        entry.applyCrop = prefShowCinemaMattes;
        entry.isDiptych = false;
        entry.outDirPath = Filepaths.getGradedDir().getAbsolutePath();
        entry.queueMode = currentQueueMode();

        int lutIndex = entry.profile != null ? entry.profile.lutIndex : 0;
        ArrayList<String> paths = recipeManager.getRecipePaths();
        ArrayList<String> names = recipeManager.getRecipeNames();
        if (lutIndex > 0 && paths != null && names != null && lutIndex < paths.size() && lutIndex < names.size()) {
            entry.lutPath = paths.get(lutIndex);
            entry.lutName = names.get(lutIndex);
        } else {
            entry.lutPath = "NONE";
            entry.lutName = "OFF";
        }
        return entry;
    }

    private ProcessingQueueManager.Entry consumeShotSnapshot(String path, long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        ProcessingQueueManager.Entry entry = pendingShotSnapshot != null
                ? ProcessingQueueManager.copyEntry(pendingShotSnapshot)
                : createCurrentQueueEntry();
        pendingShotSnapshot = null;
        entry.originalPath = path;
        entry.outDirPath = Filepaths.getGradedDir().getAbsolutePath();
        entry.scannerStartedMs = scannerStartedMs;
        entry.detectedMs = detectedMs;
        entry.stableMs = stableMs;
        entry.scannerAttempts = scannerAttempts;
        return entry;
    }

    private String getProcessingStatusText() {
        if (processingQueueActive && processingQueueTotal > 0 && processingQueueManager != null) {
            int current = processingQueueCompleted + 1;
            if (current < 1) current = 1;
            if (current > processingQueueTotal) current = processingQueueTotal;
            return "PROCESSING QUEUE " + current + "/" + processingQueueTotal
                    + "\n" + formatQueueEta(current);
        }
        return "PROCESSING...\n" + formatProcessingEstimateForCount(1) + " LEFT";
    }

    private String formatQueueEta(int current) {
        int remaining = processingQueueTotal - current + 1;
        if (remaining < 1) remaining = 1;
        return formatProcessingEstimateForCount(remaining) + " LEFT";
    }

    private String formatProcessingEstimateForCount(int count) {
        if (count < 1) count = 1;
        long perPhotoMs = processingQueueAverageMs > 0 ? processingQueueAverageMs : QUEUE_FALLBACK_PROCESS_MS;
        long totalSeconds = (perPhotoMs * count + 500) / 1000;
        if (totalSeconds < 1) totalSeconds = 1;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            if (seconds > 0) return "~" + minutes + " min " + seconds + " sec";
            return "~" + minutes + " min";
        }
        return "~" + seconds + " sec";
    }

    private void maybeAutoProcessQueuedPhotos() {
        if (processingFrequency > 1 && processingQueueManager != null &&
                processingQueueManager.getCountForMode(ProcessingQueueManager.MODE_AUTO) >= processingFrequency && !isProcessing && isReady) {
            startQueuedProcessing(false);
        }
    }

    private void startQueuedProcessing(boolean manual) {
        startQueuedProcessing(manual, 0);
    }

    private void startQueuedProcessing(boolean manual, int targetCount) {
        int requestedMode = manual ? ProcessingQueueManager.MODE_MANUAL : ProcessingQueueManager.MODE_AUTO;
        if (manual) manualQueueProcessRequested = true;
        if (mProcessor == null || processingQueueManager == null || processingQueueManager.getCountForMode(requestedMode) == 0) {
            manualQueueProcessRequested = false;
            pendingQueueProcessTargetCount = 0;
            return;
        }
        if (manual && targetCount > 0) pendingQueueProcessTargetCount = targetCount;
        if (isProcessing || processingQueueActive || !isReady) return;
        manualQueueProcessRequested = false;
        if (targetCount <= 0) targetCount = pendingQueueProcessTargetCount;
        pendingQueueProcessTargetCount = 0;
        processingQueueActive = true;
        activeQueueMode = requestedMode;
        int queuedCount = processingQueueManager.getCountForMode(activeQueueMode);
        processingQueueTotal = (targetCount > 0 && targetCount < queuedCount) ? targetCount : queuedCount;
        processingQueueCompleted = 0;
        processingQueueStartedMs = System.currentTimeMillis();
        processingQueueAverageMs = 0;
        isProcessing = true;
        processNextQueuedPhoto();
    }

    private void processNextQueuedPhoto() {
        if (processingQueueManager == null) return;
        activeQueueEntry = processingQueueManager.peekForMode(activeQueueMode);
        if (activeQueueEntry == null) {
            finishQueuedProcessing();
            return;
        }

        if (tvTopStatus != null) {
            tvTopStatus.setText(getProcessingStatusText());
            tvTopStatus.setTextColor(UiTheme.WARN);
        }
        File outDir = activeQueueEntry.outDirPath != null && activeQueueEntry.outDirPath.length() > 0
                ? new File(activeQueueEntry.outDirPath)
                : Filepaths.getGradedDir();
        mProcessor.processJpeg(activeQueueEntry.originalPath, outDir.getAbsolutePath(),
                activeQueueEntry.qualityIndex, activeQueueEntry.jpegQuality,
                activeQueueEntry.profile, activeQueueEntry.applyCrop, activeQueueEntry.isDiptych,
                activeQueueEntry.lutPath, activeQueueEntry.lutName,
                activeQueueEntry.scannerStartedMs, activeQueueEntry.detectedMs,
                activeQueueEntry.stableMs, activeQueueEntry.scannerAttempts);
    }

    private void handleQueuedProcessFinished(String result) {
        boolean success = result != null && result.toUpperCase().indexOf("SAVED") >= 0;
        if (success && processingQueueManager != null) {
            processingQueueManager.removeFirstForMode(activeQueueMode);
            processingQueueCompleted++;
            if (processingQueueCompleted > 0 && processingQueueStartedMs > 0) {
                processingQueueAverageMs = (System.currentTimeMillis() - processingQueueStartedMs) / processingQueueCompleted;
            }
            if (processingQueueCompleted < processingQueueTotal && processingQueueManager.getCountForMode(activeQueueMode) > 0) {
                processNextQueuedPhoto();
                return;
            }
            finishQueuedProcessing();
        } else {
            processingQueueActive = false;
            activeQueueEntry = null;
            processingQueueTotal = 0;
            processingQueueCompleted = 0;
            processingQueueStartedMs = 0;
            processingQueueAverageMs = 0;
            pendingQueueProcessTargetCount = 0;
            activeQueueMode = ProcessingQueueManager.MODE_AUTO;
            isProcessing = false;
            runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setTextColor(UiTheme.TEXT); } updateMainHUD(); } });
        }
    }

    private void finishQueuedProcessing() {
        processingQueueActive = false;
        activeQueueEntry = null;
        processingQueueTotal = 0;
        processingQueueCompleted = 0;
        processingQueueStartedMs = 0;
        processingQueueAverageMs = 0;
        pendingQueueProcessTargetCount = 0;
        activeQueueMode = ProcessingQueueManager.MODE_AUTO;
        isProcessing = false;
        triggerLutPreload();
        applyHardwareRecipe();
        syncHardwareState();
        runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setTextColor(UiTheme.TEXT); } updateMainHUD(); } });
    }

    private void processWhenFileReady(final String path, final long scannerStartedMs, final long detectedMs, final int scannerAttempts) {
        // Wrap the diagnostic in our global debug flag
        if (DEBUG_MODE) {
            android.widget.Toast.makeText(this, "File Detected! Waiting for write...", android.widget.Toast.LENGTH_SHORT).show();
        }

        final File f = new File(path);
        if (!f.exists()) return;

        captureWritePending = true;
        isProcessing = true;
        showSavingToSdStatus();

        final long[] lastSize = {-1};
        final int[] retries = {0};
        final int[] stableChecks = {0};

        Runnable checker = new Runnable() {
            @Override
            public void run() {
                if (!f.exists()) {
                    pendingShotSnapshot = null;
                    captureWritePending = false;
                    isProcessing = false;
                    updateMainHUD();
                    return;
                }

                long currentSize = f.length();
                if (currentSize > 0 && currentSize == lastSize[0]) {
                    stableChecks[0]++;
                } else {
                    stableChecks[0] = 0;
                }

                boolean stableEnough = currentSize > 0 && stableChecks[0] >= PHOTO_READY_STABLE_CHECKS;
                boolean fallbackStable = currentSize > 0 && stableChecks[0] >= PHOTO_READY_FALLBACK_STABLE_CHECKS;
                if ((stableEnough && hasJpegEndMarker(f)) || fallbackStable) {
                    captureWritePending = false;
                    long stableMs = System.currentTimeMillis();
                    ProcessingQueueManager.Entry entry = consumeShotSnapshot(path, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                    handleReadyPhotoFile(f, path, entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                } else if (retries[0] < PHOTO_READY_MAX_RETRIES) {
                    lastSize[0] = currentSize;
                    retries[0]++;
                    uiHandler.postDelayed(this, PHOTO_READY_RETRY_MS);
                } else {
                    captureWritePending = false;
                    if (f.exists() && f.length() > 0) {
                        long stableMs = System.currentTimeMillis();
                        ProcessingQueueManager.Entry entry = consumeShotSnapshot(path, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                        handleReadyPhotoFile(f, path, entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
                    } else {
                        pendingShotSnapshot = null;
                        isProcessing = false;
                        updateMainHUD();
                    }
                }
            }
        };

        uiHandler.post(checker);
    }

    private boolean hasJpegEndMarker(File file) {
        java.io.RandomAccessFile raf = null;
        try {
            if (file == null || file.length() < 2) return false;
            raf = new java.io.RandomAccessFile(file, "r");
            long len = raf.length();
            if (len < 2) return false;
            raf.seek(len - 2);
            return (raf.read() & 0xFF) == 0xFF && (raf.read() & 0xFF) == 0xD9;
        } catch (Exception e) {
            return false;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleReadyPhotoFile(File f, String path, ProcessingQueueManager.Entry entry,
                                      long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        // --- DIPTYCH INTERCEPT ---
        if (diptychManager != null && diptychManager.interceptNewFile(f.getName(), path)) {
            if (diptychManager.getState() == DiptychManager.STATE_PROCESSING_FIRST) {
                diptychManager.processFirstShot(path);
            } else if (diptychManager.getState() == DiptychManager.STATE_STITCHING) {
                diptychManager.processSecondShot(diptychManager.getLeftFilename(), path, entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
            }
            return;
        }

        // --- MULTI-EXPOSURE INTERCEPT ---
        if (multiExposeManager != null && multiExposeManager.interceptNewFile(f.getName(), path)) {
            if (multiExposeManager.getState() == MultiExposeManager.STATE_PROCESSING) {
                multiExposeManager.processFinalShot(entry, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
            }
            return;
        } else if (shouldQueuePhotos()) {
            if (processingQueueManager != null) {
                processingQueueManager.add(entry);
            }
            isProcessing = false;
            updateMainHUD();
            maybeAutoProcessQueuedPhotos();
        } else {
            File outDir = Filepaths.getGradedDir();
            mProcessor.processJpeg(path, outDir.getAbsolutePath(), entry.qualityIndex, entry.jpegQuality, entry.profile, entry.applyCrop, false,
                    entry.lutPath, entry.lutName,
                    scannerStartedMs, detectedMs, stableMs, scannerAttempts);
        }
    }

    public void handleStitchedMultiExpose(String tempPath, ProcessingQueueManager.Entry entry, long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        entry.originalPath = tempPath;
        mProcessor.processJpeg(tempPath, entry.outDirPath, entry.qualityIndex, entry.jpegQuality, entry.profile, entry.applyCrop, entry.isDiptych,
                entry.lutPath, entry.lutName,
                scannerStartedMs, detectedMs, stableMs, scannerAttempts);
        if (processingQueueManager != null) processingQueueManager.removeFirst();
    }

    public void handleStitchedDiptych(String tempPath, ProcessingQueueManager.Entry entry, long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        entry.originalPath = tempPath;
        entry.isDiptych = true;

        if (shouldQueuePhotos()) {
            if (processingQueueManager != null) {
                processingQueueManager.add(entry);
            }
            isProcessing = false;
            updateMainHUD();
            maybeAutoProcessQueuedPhotos();
        } else {
            File outDir = Filepaths.getGradedDir();
            mProcessor.processJpeg(tempPath, outDir.getAbsolutePath(), entry.qualityIndex, entry.jpegQuality, entry.profile, false, true,
                    entry.lutPath, entry.lutName,
                    scannerStartedMs, detectedMs, stableMs, scannerAttempts);
        }
    }

    private boolean isShutterInput(int sc, int keyCode) {
        return sc == ScalarInput.ISV_KEY_S1_1 || sc == ScalarInput.ISV_KEY_S1_2 || sc == ScalarInput.ISV_KEY_S2 ||
                keyCode == ScalarInput.ISV_KEY_S1_1 || keyCode == ScalarInput.ISV_KEY_S1_2 || keyCode == ScalarInput.ISV_KEY_S2;
    }

    private boolean isFullShutterInput(int sc, int keyCode) {
        return sc == ScalarInput.ISV_KEY_S1_2 || sc == ScalarInput.ISV_KEY_S2 ||
                keyCode == ScalarInput.ISV_KEY_S1_2 || keyCode == ScalarInput.ISV_KEY_S2;
    }

    private boolean shouldBlockShutterInput() {
        return isProcessing || captureWritePending;
    }

    private void showSavingToSdStatus() {
        runOnUiThread(new Runnable() {
            public void run() {
                if (mainUIContainer != null) mainUIContainer.setVisibility(View.VISIBLE);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SAVING TO SD...");
                    tvTopStatus.setTextColor(UiTheme.WARN);
                    UiTheme.softPanel(tvTopStatus);
                    tvTopStatus.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void triggerLutPreload() {
        RTLProfile p = recipeManager.getCurrentProfile();
        if (p.lutIndex == 0) {
            // No LUT selected, engine is ready immediately for other effects
            isReady = true;
            updateMainHUD();
            maybeAutoProcessQueuedPhotos();
            return;
        }
        mProcessor.triggerLutPreload(recipeManager.getRecipePaths().get(p.lutIndex), recipeManager.getRecipeNames().get(p.lutIndex));
    }

    private void refreshRecipes() {
        List<String> oldPaths = new ArrayList<String>(recipeManager.getRecipePaths());
        String[] savedPaths = new String[10];

        for (int i = 0; i < 10; i++) {
            RTLProfile p = recipeManager.getProfile(i);
            if (p.lutIndex >= 0 && p.lutIndex < oldPaths.size()) savedPaths[i] = oldPaths.get(p.lutIndex);
            else savedPaths[i] = "NONE";
        }

        recipeManager.scanRecipes();
        List<String> newPaths = recipeManager.getRecipePaths();

        for (int i = 0; i < 10; i++) {
            int idx = newPaths.indexOf(savedPaths[i]);
            recipeManager.getProfile(i).lutIndex = (idx != -1) ? idx : 0;
        }
    }

    private boolean prefShowFocusMeter = false; // CHANGED: Off by default

    @Override
    public void onShutterHalfPressed() {
        if (cameraManager != null) {
            cameraManager.clearPreviewMagnification();
        }
        if (playbackController.isActive()) { playbackController.exit(); return; }
        if (menuController.isOpen()) { menuController.close(); return; }
        if (shouldBlockShutterInput()) return;

        // mDialMode = DIAL_MODE_RTL; <-- DELETED. Cursor memory is now permanent!

        if (displayState == 0 && !menuController.isOpen()) setLiveUiSuppressed(true);
        // Diptych mode: shift AF bracket to the active (open) side before focusing
        if (afOverlay != null && diptychManager != null && diptychManager.isEnabled()) {
            boolean isFirst = diptychManager.getState() == DiptychManager.STATE_NEED_FIRST || diptychManager.getState() == DiptychManager.STATE_PROCESSING_FIRST;
            boolean isSecond = diptychManager.getState() == DiptychManager.STATE_NEED_SECOND;
            
            // --- CENTER CROP LOGIC ---
            // Shot 1: Always Center (0)
            // Shot 2: Offset to framed side (If Shot 1 is on Left, Shot 2 is on Right (+500))
            int centerX = 0;
            if (isFirst) {
                centerX = 0;
            } else if (isSecond) {
                centerX = diptychManager.isThumbOnLeft() ? 500 : -500;
            }

            int width = afOverlay.getWidth();
            if (width <= 0) width = getPreviewWindowWidth();
            
            if (isFirst || isSecond) {
                afOverlay.setDiptychCenterX((width / 2) + ((centerX * width) / 2000));
            } else {
                afOverlay.setDiptychCenterX(-1);
            }

            if (cameraManager != null && cameraManager.getCamera() != null) {
                try {
                    android.hardware.Camera.Parameters p = cameraManager.getCamera().getParameters();
                    
                    // FORCE Flexible Spot mode so coordinates work
                    if (p.get("sony-focus-area") != null) p.set("sony-focus-area", "manual");
                    
                    java.util.List<android.hardware.Camera.Area> areas = new java.util.ArrayList<android.hardware.Camera.Area>();
                    int rectSize = 150;
                    areas.add(new android.hardware.Camera.Area(new android.graphics.Rect(centerX - rectSize, -rectSize, centerX + rectSize, rectSize), 1000));
                    
                    if (p.getMaxNumFocusAreas() > 0) {
                        if (isFirst || isSecond) p.setFocusAreas(areas);
                        else p.setFocusAreas(null);
                    }
                    if (p.getMaxNumMeteringAreas() > 0) {
                        if (isFirst || isSecond) p.setMeteringAreas(areas);
                        else p.setMeteringAreas(null);
                    }
                    cameraManager.getCamera().setParameters(p);

                    // Sync Focus Magnifier absolute position
                    if (cameraManager.isPreviewMagnificationActive()) {
                        cameraManager.jumpToPreviewMagnification(centerX, 0);
                    }
                } catch (Exception ignored) {}
            }
        } else {
            if (afOverlay != null) afOverlay.setDiptychCenterX(-1);
        }
        if (cameraManager != null && cameraManager.getCamera() != null && !cachedIsManualFocus) {
            if (afOverlay != null) afOverlay.startFocus(cameraManager.getCamera());
        }
        if (displayState == 0 && !menuController.isOpen()) hideLiveViewOverlays();
    }

    @Override
    public void onShutterHalfReleased() {
        if (afOverlay != null && cameraManager != null && cameraManager.getCamera() != null) {
            afOverlay.stopFocus(cameraManager.getCamera());
            afOverlay.setDiptychCenterX(-1);
        }
        if (displayState == 0 && !menuController.isOpen() && !playbackController.isActive()) setLiveUiSuppressed(false);
    }

    public void armFileScanner() {
        if (mScanner != null) {
            if (pendingShotSnapshot == null) pendingShotSnapshot = createCurrentQueueEntry();
            if (mScanner.isPolling) mScanner.checkNow();
            else mScanner.start();
            final int token = ++captureWriteToken;
            uiHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (token == captureWriteToken && !isProcessing && pendingShotSnapshot != null) {
                        pendingShotSnapshot = null;
                        captureWritePending = false;
                        updateMainHUD();
                    }
                }
            }, SCANNER_ARM_TIMEOUT_MS);
        }
    }

    @Override
    public void onDeletePressed() {
        finish();
    }

    @Override
    public void onMenuPressed() {
        if (cameraManager != null) {
            cameraManager.clearPreviewMagnification();
        }
        if (playbackController.isActive()) { playbackController.exit(); return; }
        if (isProcessing) return;

        // 1. Contextual Back: If a HUD (like Recipe Vault) is open, close it and reveal the Menu
        if (hudController.isActive()) {
            menuController.setNamingMode(false);
            menuController.setConfirmingDelete(false);
            hudController.close();
            return;
        }

        // 2. Contextual Back: If editing a value, cancel the edit. Otherwise, close the Menu.
        if (menuController.isOpen()) {
            if (menuController.cancelAction()) {
                return; // Successfully backed out of an edit state
            }
            menuController.close();
        } else {
            // 3. Normal Open
            menuController.open();
        }
    }

    private Runnable hudUpdateRunnable = new Runnable() {
        @Override public void run() { updateMainHUD(); }
    };

    private void requestHudUpdate() {
        uiHandler.removeCallbacks(hudUpdateRunnable);
        uiHandler.postDelayed(hudUpdateRunnable, 100);
    }

    @Override
    public void onEnterPressed() {
    if (playbackController.isActive()) { playbackController.select(); return; }
    if (isProcessing) return;

    if (hudController.isActive()) {
        if (hudController.getSelection() == -2) {
            if (hudController.getMode() == 10 && menuController.isConfirmingDelete()) {
                menuController.setConfirmingDelete(false);
                hudController.setSelection(hudController.getRecipeBrowserSelectedVaultIndex());
                hudController.update();
                return;
            }
            if (hudController.getMode() == 10 && hudController.isRecipeLoadBrowser()) {
                hudController.closeRecipeLoadBrowser();
                return;
            }
            if (hudController.getMode() == 10 && hudController.isRecipeDeleteBrowser()) {
                hudController.closeRecipeDeleteBrowser();
                return;
            }
            menuController.setNamingMode(false);
            menuController.setConfirmingDelete(false);
            hudController.close();
            return;
        }

        // --- VAULT HUD (MODE 10) ---
        if (hudController.getMode() == 10) {
            if (menuController.isNamingMode()) {
                menuController.setNamingMode(false);
                String finalName = new String(menuController.getNameBuffer()).trim();
                if (finalName.isEmpty()) finalName = "CUSTOM";
                recipeManager.saveSlotToVault(finalName);
                hudController.refreshVaultItems();
                for (int i = 0; i < hudController.getVaultItems().size(); i++) {
                    if (hudController.getVaultItems().get(i).profileName.equalsIgnoreCase(finalName)) { hudController.setVaultIndex(i); break; }
                }
                hudController.update(); return;
            } else if (menuController.isConfirmingDelete()) {
                if (hudController.getSelection() == 0) {
                    int deleteIndex = hudController.getRecipeBrowserSelectedVaultIndex();
                    if (deleteIndex >= 0) recipeManager.deleteVaultItem(deleteIndex);
                    menuController.setConfirmingDelete(false);
                    hudController.refreshRecipeBrowserAfterDelete();
                    return;
                } else if (hudController.getSelection() == 1) {
                    menuController.setConfirmingDelete(false);
                    hudController.setSelection(hudController.getRecipeBrowserSelectedVaultIndex());
                    hudController.update();
                    return;
                }
            } else if (hudController.isRecipeLoadBrowser()) {
                int loadIndex = hudController.getRecipeBrowserSelectedVaultIndex();
                if (loadIndex >= 0 && loadIndex < hudController.getVaultItems().size()) {
                    RecipeManager.VaultItem item = hudController.getVaultItems().get(loadIndex);
                    if (item != null && item.filename != null && !item.filename.equals("NONE")) {
                        recipeManager.previewVaultToSlot(item.filename);
                        triggerLutPreload();
                        applyHardwareRecipe();
                        recipeManager.savePreferences();
                        hudController.close();
                    }
                    return;
                }
            } else if (hudController.isRecipeDeleteBrowser()) {
                if (hudController.getRecipeBrowserSelectedVaultIndex() >= 0) {
                    menuController.setConfirmingDelete(true);
                    hudController.beginRecipeDeleteConfirm();
                }
                return;
            } else {
                if (hudController.getSelection() == 0) {
                    menuController.setNamingMode(true);
                    RTLProfile activeProfile = recipeManager.getCurrentProfile();
                    String currentName = (activeProfile != null) ? activeProfile.profileName : "";
                    if (currentName != null && !currentName.isEmpty() && !currentName.startsWith("SLOT ")) menuController.fillNameBuffer(currentName);
                    else menuController.resetNameBuffer();
                    menuController.resetNameCursor(); hudController.update(); return;
                } else if (hudController.getSelection() == 1) {
                    hudController.openRecipeLoadBrowser();
                    return;
                } else if (hudController.getSelection() == 2) {
                    recipeManager.resetCurrentSlot();
                    triggerLutPreload(); // Ensure the hardware actually clears the current LUT
                    applyHardwareRecipe();
                    hudController.close(); // NEW: Exit HUD immediately after reset
                    return;
                } else if (hudController.getSelection() == 3) {
                    hudController.openRecipeDeleteBrowser();
                    return;
                }
            }

            // Clean up UI if we exited the HUD naturally (like hitting Confirm Load)
            if (!hudController.isActive()) {
                hudController.hideOverlays(); // Safely clear UI without firing redundant close callbacks
                menuController.getContainer().setVisibility(View.VISIBLE);
                menuController.refreshDisplay();
            }
            return;
        }

        if (hudController.isMatrixSaveAction()) {
            RTLProfile p = recipeManager.getCurrentProfile();
            if (!menuController.isNamingMode()) {
                for (int i = 0; i < matrixManager.getCount(); i++) {
                    int[] existing = matrixManager.getValues(i); boolean isMatch = true;
                    for (int j = 0; j < 9; j++) if (p.advMatrix[j] != existing[j]) { isMatch = false; break; }
                    if (isMatch) { tvTopStatus.setText("ALREADY SAVED: " + matrixManager.getNames().get(i)); tvTopStatus.setTextColor(UiTheme.SUCCESS); return; }
                }
            }
            menuController.setNamingMode(!menuController.isNamingMode());
            if (menuController.isNamingMode()) { menuController.resetNameBuffer(); menuController.resetNameCursor(); hudController.update();
            } else { String finalName = new String(menuController.getNameBuffer()).trim(); if (finalName.isEmpty()) finalName = "CUSTOM"; hudController.saveCustomMatrix(finalName); }
            return;
        }

        // Standard HUD exit — controller hides overlays and fires onHudClosed callback
        if (hudController.handleEnter()) {
            if (!hudController.isActive()) return;
            if (!hudController.isValueEditing()) recipeManager.savePreferences();
            return;
        }

        hudController.close();
        recipeManager.savePreferences();
        return;
    }

        if (menuController.dispatchHudLaunch()) return;
        if (calibController.handleEnter()) return;

        if (!menuController.isOpen()) {
            if (mDialMode == DIAL_MODE_REVIEW) {
                playbackController.enter();
            } else if (mDialMode == DIAL_MODE_FOCUS && cachedIsManualFocus) {
                // Silent Guard: Only start mapping if user has the meter visible
                if (prefShowFocusMeter) {
                    calibController.beginWaiting();
                    setHUDVisibility(View.GONE);
                    if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE);
                }
                return; // FIXED: Changed from 'return true' to just 'return'
            } else {
                // <--- CHANGED: Replaced HUD hiding with Dial Lock toggle
                isDialLocked = !isDialLocked;
                updateMainHUD();
            }
        } else {
            menuController.handleEnter();
        }
    }

    @Override
    public boolean onUpPressed() {
        if (cameraManager != null && cameraManager.isPreviewMagnificationActive()
                && !playbackController.isActive() && !menuController.isOpen() && !hudController.isActive()) {
            cameraManager.movePreviewMagnification(0, -1);
            return true;
        }

        if (playbackController.isActive()) { playbackController.navigate(-2); return true; }

        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx += 1;
            if (idx >= MenuController.CHARSET.length()) idx = 0;
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return true;
        }

        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleUp(); return true; }

        if (isProcessing || calibController.isWaiting()) return true;

        if (calibController.handleUp()) return true;

        if (menuController.isOpen()) { menuController.handleUp(); return true; }
        navigateHomeSpatial(ScalarInput.ISV_KEY_UP);
        return true;
    }

    @Override
    public boolean onDownPressed() {
        if (cameraManager != null && cameraManager.isPreviewMagnificationActive()
                && !playbackController.isActive() && !menuController.isOpen() && !hudController.isActive()) {
            cameraManager.movePreviewMagnification(0, 1);
            return true;
        }

        if (playbackController.isActive()) { playbackController.navigate(2); return true; }

        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx -= 1;
            if (idx < 0) idx = MenuController.CHARSET.length() - 1;
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return true;
        }

        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleDown(); return true; }

        if (isProcessing) return true;

        if (calibController.handleDown()) return true;

        if (menuController.isOpen()) { menuController.handleDown(); return true; }
        navigateHomeSpatial(ScalarInput.ISV_KEY_DOWN);
        return true;
    }

    @Override
    public boolean onLeftPressed() {
        if (cameraManager != null && cameraManager.isPreviewMagnificationActive()
                && !playbackController.isActive() && !menuController.isOpen() && !hudController.isActive()) {
            cameraManager.movePreviewMagnification(-1, 0);
            return true;
        }

        // --- DIPTYCH SIDE SWAP ---
        // During Shot 2 framing, Left/Right moves the PREVIEW.
        // We only do this if the HUD/Menu isn't open, so we don't 'trap' navigation.
        if (diptychManager != null && diptychManager.isEnabled() && !menuController.isOpen() && !hudController.isActive()) {
            if (diptychManager.getState() == DiptychManager.STATE_NEED_SECOND) {
                diptychManager.setThumbOnLeft(true);
                updateDiptychPreviewWindow();
                return true; // Handle purely for Diptych
            }
        }

        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            menuController.advanceNameCursor(-1);
            hudController.update();
            return true;
        }

        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleLeft(); return true; }
        if (isProcessing) return true;
        if (calibController.handleLeft()) return true;

        if (menuController.isOpen()) { menuController.handleLeft(); return true; }
        if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.max(0.0f, virtualFocusRatio - 0.02f);
            if (focusMeter != null) focusMeter.update(virtualFocusRatio, virtualAperture, lensManager.getCurrentFocalLength(), false, lensManager.getCurrentPoints(), getCircleOfConfusion());
        } else if (playbackController.isActive()) {
            playbackController.navigate(-1);
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_LEFT);
        }
        return true;
    }

    @Override
    public boolean onRightPressed() {
        if (cameraManager != null && cameraManager.isPreviewMagnificationActive()
                && !playbackController.isActive() && !menuController.isOpen() && !hudController.isActive()) {
            cameraManager.movePreviewMagnification(1, 0);
            return true;
        }

        // --- DIPTYCH SIDE SWAP ---
        // During Shot 2 framing, Left/Right moves the PREVIEW.
        // We only do this if the HUD/Menu isn't open, so we don't 'trap' navigation.
        if (diptychManager != null && diptychManager.isEnabled() && !menuController.isOpen() && !hudController.isActive()) {
            if (diptychManager.getState() == DiptychManager.STATE_NEED_SECOND) {
                diptychManager.setThumbOnLeft(false);
                updateDiptychPreviewWindow();
                return true; // Handle purely for Diptych
            }
        }

        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            menuController.advanceNameCursor(1);
            hudController.update();
            return true;
        }

        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleRight(); return true; }
        if (isProcessing) return true;
        if (calibController.handleRight()) return true;
        if (menuController.isOpen()) { menuController.handleRight(); return true; }

        if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.min(1.0f, virtualFocusRatio + 0.02f);
            if (focusMeter != null) focusMeter.update(virtualFocusRatio, virtualAperture, lensManager.getCurrentFocalLength(), false, lensManager.getCurrentPoints(), getCircleOfConfusion());
        } else if (playbackController.isActive()) {
            playbackController.navigate(1);
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_RIGHT);
        }
        return true;
    }

    @Override
    public boolean onCustomButtonReleased(String keyId) {
        if (playbackController.isActive() || menuController.isOpen() || isProcessing || calibController.isCalibrating()) {
            return false;
        }

        int action = 0;
        if (keyId.equals("C1")) action = recipeManager.getPrefC1();
        else if (keyId.equals("C2")) action = recipeManager.getPrefC2();
        else if (keyId.equals("C3")) action = recipeManager.getPrefC3();
        else if (keyId.equals("AEL")) action = recipeManager.getPrefAel();
        else if (keyId.equals("FN")) action = recipeManager.getPrefFn();

        if (action == 0) return false; // OFF

        return true; // We handled the action in our app, safely swallow the release!
    }

    private void saveAppPreferences() {
        SharedPreferences prefs = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("focusMeter",    prefShowFocusMeter);
        ed.putBoolean("cinemaMattes",  prefShowCinemaMattes);
        ed.putBoolean("gridLines",     prefShowGridLines);
        ed.putInt("jpegQuality",       prefJpegQuality);
        ed.putInt("processingFrequency", processingFrequency);
        ed.putBoolean("diptychEnabled", isPrefDiptych());
        ed.putBoolean("multiExposeEnabled", isPrefMultiExpose());
        ed.putInt("multiExposeCount", getMultiExposeCount());
        ed.putInt("multiExposeBlendMode", getMultiExposeBlendMode());
        ed.apply();
    }

    @Override public void setPrefMultiExpose(boolean v) {
        SharedPreferences prefs = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("multiExposeEnabled", v);
        ed.commit();
        if (multiExposeManager != null) {
            multiExposeManager.setEnabled(v);
        }
    }
    @Override public void    setMultiExposeCount(int count) {
        if (multiExposeManager != null) multiExposeManager.setTotalExposures(count);
    }
    @Override public void    setMultiExposeBlendMode(int mode) {
        if (multiExposeManager != null) multiExposeManager.setBlendMode(mode);
    }

    @Override
    public boolean onCustomButtonPressed(String keyId) {
        if (playbackController.isActive() || menuController.isOpen() || isProcessing || calibController.isCalibrating()) {
            return false;
        }

        int action = 0;
        if (keyId.equals("C1")) action = recipeManager.getPrefC1();
        else if (keyId.equals("C2")) action = recipeManager.getPrefC2();
        else if (keyId.equals("C3")) action = recipeManager.getPrefC3();
        else if (keyId.equals("AEL")) action = recipeManager.getPrefAel();
        else if (keyId.equals("FN")) action = recipeManager.getPrefFn();

        if (action == 0) return false; // OFF (Let Sony OS handle natively)

        if (action == 1) { // ISO MENU
            mDialMode = DIAL_MODE_ISO;
            updateMainHUD();
            return true;
        } else if (action == 2) { // FOCUS MAGNIFIER
            if (cameraManager != null) {
                cameraManager.togglePreviewMagnification();
                requestHudUpdate();
            }
            return true;
        } else if (action == 3) { // TOGGLE FOCUS METER
            setPrefFocusMeter(!isPrefFocusMeter());
            saveAppPreferences();
            updateMainHUD();
            return true;
        } else if (action == 4) { // CYCLE CREATIVE MODES
            int mode = 0;
            if (isPrefCinemaMattes()) mode = 1;
            else if (isPrefDiptych()) mode = 2;
            else if (isPrefMultiExpose()) mode = 3;

            mode = (mode + 1) % 4;

            setPrefCinemaMattes(mode == 1);
            setPrefDiptych(mode == 2);
            setPrefMultiExpose(mode == 3);
            saveAppPreferences();
            updateMainHUD();
            return true;
        } else if (action == 5) { // TOGGLE GRID LINES
            setPrefGridLines(!isPrefGridLines());
            saveAppPreferences();
            updateMainHUD();
            return true;
        } else if (action == 6) { // SHUTTER SPEED
            mDialMode = DIAL_MODE_SHUTTER;
            updateMainHUD();
            return true;
        } else if (action == 7) { // APERTURE
            mDialMode = DIAL_MODE_APERTURE;
            updateMainHUD();
            return true;
        } else if (action == 8) { // EXPOSURE COMP
            mDialMode = DIAL_MODE_EXPOSURE;
            updateMainHUD();
            return true;
        } else if (action == 9) { // RECIPE SELECTION
            mDialMode = DIAL_MODE_RTL;
            updateMainHUD();
            return true;
        }

        return false;
    }

    @Override
    public void onFrontDialRotated(int direction) {
        // If UI is open, act like a normal scroll wheel
        if (hudController.isActive() || playbackController.isActive() || menuController.isOpen()) { onControlWheelRotated(direction); return; }

        // If shooting, forcefully change Shutter Speed
        if (!isProcessing && cameraManager != null && cameraManager.getCameraEx() != null) {
            if (direction > 0) cameraManager.getCameraEx().incrementShutterSpeed();
            else cameraManager.getCameraEx().decrementShutterSpeed();
            updateMainHUD();
        }
    }

    @Override
    public void onRearDialRotated(int direction) {
        // If UI is open, act like a normal scroll wheel
        if (hudController.isActive() || playbackController.isActive() || menuController.isOpen()) { onControlWheelRotated(direction); return; }

        // If shooting, forcefully change Aperture
        if (!isProcessing && cameraManager != null && cameraManager.getCameraEx() != null) {
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                adjustVirtualAperture(direction);
            } else {
                if (direction > 0) cameraManager.getCameraEx().incrementAperture();
                else cameraManager.getCameraEx().decrementAperture();
            }
            updateMainHUD();
        }
    }

    @Override
    public void onControlWheelRotated(int direction) {
        if (cameraManager != null && cameraManager.isPreviewMagnificationActive()
                && !playbackController.isActive() && !menuController.isOpen() && !hudController.isActive()) {
            cameraManager.movePreviewMagnification(direction > 0 ? 1 : -1, 0);
            return;
        }

        // --- NEW: INTERCEPT WHEEL TURNS FOR MATRIX NAMING ---
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx = (idx + direction + MenuController.CHARSET.length()) % MenuController.CHARSET.length();
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return;
        }

        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleDial(direction); return; }

        if (playbackController.isActive()) {
            playbackController.navigate(direction);
        } else if (menuController.isOpen()) {
            menuController.handleDial(direction);
        } else if (!isProcessing) {
            handleHardwareInput(direction);
        }
    }

    private void navigateHomeSpatial(int keyCode) {
        isDialLocked = true; // <--- NEW: Always auto-lock when moving the cursor
        switch (mDialMode) {
            case DIAL_MODE_SHUTTER:
                if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_FOCUS;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_FOCUS;
                break;
            case DIAL_MODE_APERTURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_PASM;
                break;
            case DIAL_MODE_ISO:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW;
                break;
            case DIAL_MODE_EXPOSURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_REVIEW;
                break;
            case DIAL_MODE_REVIEW:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_RTL;
                break;
            case DIAL_MODE_RTL:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_REVIEW;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_PASM;
                break;
            case DIAL_MODE_PASM:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_FOCUS;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_FOCUS;
                break;
            case DIAL_MODE_FOCUS:
                if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_SHUTTER;
                break;
        }
        updateMainHUD();
    }




    private void autoEquipMatchingLens(float hwFocal) {
        availableLenses = lensManager.getAvailableLenses();
        String matchedLens = null;
        for (String lens : availableLenses) {
            if (lens.startsWith("e") || lens.startsWith("E")) {
                if (lens.contains((int)hwFocal + "mm")) {
                    matchedLens = lens;
                    break;
                }
            }
        }
        if (matchedLens != null) {
            currentLensIndex = availableLenses.indexOf(matchedLens);
            lensManager.loadProfileFromFile(matchedLens);
        } else {
            lensManager.clearCurrentProfile();
        }
    }

    private void adjustVirtualAperture(int direction) {
        float[] stops = {1.0f, 1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f};
        int currentIndex = 0;
        float minDiff = Float.MAX_VALUE;

        for (int i = 0; i < stops.length; i++) {
            float diff = Math.abs(stops[i] - virtualAperture);
            if (diff < minDiff) {
                minDiff = diff;
                currentIndex = i;
            }
        }

        currentIndex += direction;
        if (currentIndex < 0) currentIndex = 0;
        if (currentIndex >= stops.length) currentIndex = stops.length - 1;

        virtualAperture = stops[currentIndex];

        if (lensManager != null && virtualAperture < lensManager.currentMaxAperture) {
            virtualAperture = lensManager.currentMaxAperture;
        }
    }

    private void handleHardwareInput(int d) {
        if (calibController.handleDial(d)) return;
        if (isDialLocked) return; // <--- NEW: Block the dial from changing values

        if (cameraManager == null || cameraManager.getCamera() == null || cameraManager.getCameraEx() == null) return;

        Camera c = cameraManager.getCamera();
        CameraEx cx = cameraManager.getCameraEx();
        Camera.Parameters p = c.getParameters();
        CameraEx.ParametersModifier pm = cx.createParametersModifier(p);

        if (mDialMode == DIAL_MODE_RTL) {
            recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + d);
            applyHardwareRecipe();
            triggerLutPreload();
        }
        else if (mDialMode == DIAL_MODE_SHUTTER) {
            if (d > 0) cx.incrementShutterSpeed(); else cx.decrementShutterSpeed();
        }
        else if (mDialMode == DIAL_MODE_APERTURE) {
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                adjustVirtualAperture(d);
            } else {
                if (d > 0) cx.incrementAperture(); else cx.decrementAperture();
            }
        }
        else if (mDialMode == DIAL_MODE_ISO) {
            List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
            if (isos != null) {
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) {
                    pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d))));
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_EXPOSURE) {
            int ev = p.getExposureCompensation();
            p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), ev + d)));
            try { c.setParameters(p); } catch (Exception e) {}
        }
        else if (mDialMode == DIAL_MODE_PASM) {
            if (hasPhysicalPasmDial) return; // <-- NEW: Prevent software override on A7II!

            List<String> valid = new ArrayList<String>();
            String[] desired = {"program-auto", "aperture-priority", "shutter-priority", "shutter-speed", "manual-exposure","auto"};
            List<String> supported = p.getSupportedSceneModes();
            if (supported != null) {
                for (String s : desired) if (supported.contains(s)) valid.add(s);
                if (!valid.isEmpty()) {
                    int idx = valid.indexOf(p.getSceneMode());
                    if (idx == -1) idx = 0;
                    p.setSceneMode(valid.get((idx + d + valid.size()) % valid.size()));
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_FOCUS) {
            List<String> hwModes = p.getSupportedFocusModes();
            List<String> virtualModes = new ArrayList<String>();

            if (hwModes != null) {
                for (String m : hwModes) {
                    if (m.equals("manual")) {
                        availableLenses = lensManager.getAvailableLenses();
                        virtualModes.add("manual_unmapped");
                        for (String l : availableLenses) {
                            virtualModes.add("manual_" + l);
                        }
                    } else {
                        virtualModes.add(m);
                    }
                }
            }

            String currentVirtual = p.getFocusMode();
            if ("manual".equals(currentVirtual)) {
                 String loaded = lensManager.getCurrentLensName();
                 if (loaded == null || loaded.equals("Unmapped Lens") || !availableLenses.contains(loaded)) {
                     currentVirtual = "manual_unmapped";
                 } else {
                     currentVirtual = "manual_" + loaded;
                 }
            }

            int idx = virtualModes.indexOf(currentVirtual);
            if (idx == -1) idx = 0;

            String nextVirtual = virtualModes.get((idx + d + virtualModes.size()) % virtualModes.size());

            if (nextVirtual.startsWith("manual_")) {
                p.setFocusMode("manual");
                String lensFile = nextVirtual.replace("manual_", "");
                if (!lensFile.equals("unmapped")) {
                    currentLensIndex = availableLenses.indexOf(lensFile);
                    lensManager.loadProfileFromFile(lensFile);
                    if (lensManager.isCurrentProfileManual()) {
                        virtualAperture = lensManager.currentMaxAperture;
                        virtualFocusRatio = 0.5f;
                    }
                } else {
                    lensManager.clearCurrentProfile();
                }
            } else {
                // NEW: Clear inherited focus areas so Sony doesn't reject the AF-S/AF-C change
                if (android.os.Build.VERSION.SDK_INT >= 14) {
                    try {
                        if (p.getMaxNumFocusAreas() > 0) p.setFocusAreas(null);
                    } catch (Throwable t) {}
                }
                if (p.get("sony-focus-area") != null) p.set("sony-focus-area", "wide");

                p.setFocusMode(nextVirtual);
            }

            try {
                c.setParameters(p);
            } catch (Exception e) {
                android.util.Log.e("JPEG.CAM", "Failed to set focus mode: " + e.getMessage());
            }
        }
        updateMainHUD();
    }

    // --- NEW: KELVIN CYCLE HELPER ---

    private void applyHardwareRecipe() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        HardwareRecipeApplier.apply(cameraManager.getCamera(), recipeManager.getCurrentProfile());
    }

    // getWbString() removed — logic consolidated into HardwareRecipeApplier.

    private void setAutoPowerOffMode(boolean enable) {
        String mode = enable ? "APO/NORMAL" : "APO/NO";
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        sendBroadcast(intent);
    }






    // saveCurrentCustomMatrix removed — delegated to hudController.saveCustomMatrix()

    private void launchHudMode(int mode, int defaultSelection) {
        mainUIContainer.setVisibility(View.VISIBLE);
        setHUDVisibility(View.GONE);
        hudController.launch(mode, defaultSelection);
    }

    private void launchHudMode(int mode) { launchHudMode(mode, 0); }


    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this);
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));

        gridLines = new GridLinesView(this);
        mainUIContainer.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));

        cinemaMattes = new CinemaMatteView(this);
        mainUIContainer.addView(cinemaMattes, new FrameLayout.LayoutParams(-1, -1));

        tvTopStatus = new TextView(this);
        UiTheme.applyStatusText(tvTopStatus, 18, digitalFont);
        tvTopStatus.setGravity(Gravity.CENTER);
        tvTopStatus.setPadding(16, 8, 16, 8);
        UiTheme.softPanel(tvTopStatus);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topParams.setMargins(0, 15, 0, 0);
        // REVERT to mainUIContainer
        mainUIContainer.addView(tvTopStatus, topParams);

        LinearLayout rightBar = new LinearLayout(this);
        rightBar.setOrientation(LinearLayout.VERTICAL);
        rightBar.setGravity(Gravity.RIGHT);

        LinearLayout batteryArea = new LinearLayout(this);
        batteryArea.setOrientation(LinearLayout.HORIZONTAL);
        batteryArea.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);

        tvBattery = new TextView(this);
        tvBattery.setTextColor(UiTheme.ACCENT);
        tvBattery.setTextSize(14);
        tvBattery.setTypeface(Typeface.DEFAULT_BOLD);
        tvBattery.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        tvBattery.setPadding(0, 0, 5, 0);
        tvBattery.setSingleLine(true);
        tvBattery.setMinWidth(46);
        tvBattery.setText("--%");
        batteryArea.addView(tvBattery, new LinearLayout.LayoutParams(46, -2));

        batteryIcon = new BatteryView(this);
        batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(28, 12));
        rightBar.addView(batteryArea);

        tvReview = createSideTextIcon("▶");
        LinearLayout.LayoutParams rvParams = new LinearLayout.LayoutParams(-2, -2);
        rvParams.setMargins(0, 20, 0, 0);
        tvReview.setLayoutParams(rvParams);
        rightBar.addView(tvReview);

        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        rightParams.setMargins(0, 20, 30, 0);
        mainUIContainer.addView(rightBar, rightParams);

        LinearLayout leftBar = new LinearLayout(this);
        leftBar.setOrientation(LinearLayout.VERTICAL);
        tvMode = createSideTextIcon("M");
        leftBar.addView(tvMode);
        tvFocusMode = createSideTextIcon("AF-S");
        leftBar.addView(tvFocusMode);

        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        leftParams.setMargins(20, 20, 0, 0);
        mainUIContainer.addView(leftBar, leftParams);

        focusMeter = new AdvancedFocusMeterView(this);
        FrameLayout.LayoutParams fmParams = new FrameLayout.LayoutParams(-1, 140, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fmParams.setMargins(0, 0, 0, 70);
        mainUIContainer.addView(focusMeter, fmParams);

        llBottomBar = new LinearLayout(this);
        llBottomBar.setOrientation(LinearLayout.HORIZONTAL);
        llBottomBar.setGravity(Gravity.CENTER);
        llBottomBar.setPadding(8, 0, 8, 0);

        tvValShutter = createBottomText();
        tvValAperture = createBottomText();
        tvValIso = createBottomText();
        tvValEv = createBottomText();

        addBottomHudBubble(tvValShutter);
        addBottomHudBubble(tvValAperture);
        addBottomHudBubble(tvValIso);
        addBottomHudBubble(tvValEv);

        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        botParams.setMargins(0, 0, 0, 25);
        mainUIContainer.addView(llBottomBar, botParams);

        afOverlay = new ProReticleView(this);
        mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));

        tvCalibrationPrompt = new TextView(this);
        tvCalibrationPrompt.setTextColor(UiTheme.TEXT);
        tvCalibrationPrompt.setTextSize(18);
        tvCalibrationPrompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvCalibrationPrompt.setGravity(Gravity.CENTER);
        UiTheme.panel(tvCalibrationPrompt);
        tvCalibrationPrompt.setPadding(10, 15, 10, 15);
        tvCalibrationPrompt.setVisibility(View.GONE);

        FrameLayout.LayoutParams cpParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        cpParams.setMargins(0, 20, 0, 0);
        mainUIContainer.addView(tvCalibrationPrompt, cpParams);

        // Calibration controller — receives the prompt view it needs to render into
        calibController = new LensCalibrationController(tvCalibrationPrompt, this);

        // Menu controller — builds and owns its view tree
        menuController = new MenuController(this, rootLayout, this);

        // Playback viewer — PlaybackController builds and owns its views
        playbackController = new PlaybackController(this, rootLayout, this);

        // HUD controller — builds and owns its overlay views
        hudController = new HudController(this, mainUIContainer, this);

        diptychManager = new DiptychManager(this, mainUIContainer, tvTopStatus);
        multiExposeManager = new MultiExposeManager(this, mainUIContainer, tvTopStatus);
        
        SharedPreferences prefs = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE);
        multiExposeManager.setTotalExposures(prefs.getInt("multiExposeCount", 2));
        multiExposeManager.setBlendMode(prefs.getInt("multiExposeBlendMode", 0));
        multiExposeManager.setEnabled(isPrefMultiExpose());
    }


    private TextView createBottomText() {
        TextView tv = new TextView(this);
        tv.setTextSize(21);
        if (digitalFont != null) tv.setTypeface(digitalFont);
        else tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiTheme.ACCENT);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setShadowLayer(4, 0, 0, UiTheme.SHADOW);
        tv.setPadding(8, 4, 8, 4);
        UiTheme.actionPanel(tv, UiTheme.ACCENT, false, true);
        return tv;
    }

    private void addBottomHudBubble(TextView tv) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lp.setMargins(4, 0, 4, 0);
        llBottomBar.addView(tv, lp);
    }

    private TextView createSideTextIcon(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(UiTheme.TEXT);
        tv.setTextSize(20);
        if (digitalFont != null) tv.setTypeface(digitalFont);
        else tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(20, 12, 20, 12);
        UiTheme.softPanel(tv);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, 0, 15);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void styleLiveHudBubble(TextView tv, boolean selected, boolean editing) {
        if (tv == null) return;
        if (selected) {
            UiTheme.selected(tv, UiTheme.ACCENT);
            tv.setTextColor(editing ? UiTheme.WARN : UiTheme.TEXT);
            tv.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
        } else {
            UiTheme.actionPanel(tv, UiTheme.ACCENT, false, true);
            tv.setTextColor(UiTheme.ACCENT);
            tv.setShadowLayer(3, 0, 0, UiTheme.SHADOW);
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // --- 1. THE PASM DIAL SHIELD ---
        if (keyCode == 624 || keyCode == ScalarInput.ISV_KEY_MODE_DIAL ||
           (keyCode >= ScalarInput.ISV_KEY_MODE_INVALID && keyCode <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {

            if (action == android.view.KeyEvent.ACTION_DOWN) {
                if (!hasPhysicalPasmDial) hasPhysicalPasmDial = true;
                if (cameraManager != null) onHardwareStateChanged();
            }
            return true; // Bouncer defeated: Sony OS never sees the dial turn
        }

        // --- 2. THE PLAYBACK BUTTON HIJACK ---
        if (keyCode == ScalarInput.ISV_KEY_PLAY || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {

            // We only trigger our logic when the user RELEASES the button (ACTION_UP)
            // But we return 'true' for BOTH press and release to swallow it entirely.
            if (action == android.view.KeyEvent.ACTION_UP) {
                if (!isProcessing) {
                    if (playbackController.isActive()) playbackController.exit();
                    else if (!menuController.isOpen()) playbackController.enter();
                }
            }
            return true; // Bouncer defeated: Native gallery will not open
        }

        // Pass all other normal buttons (D-Pad, Center button, etc.) down to your normal listeners
        return super.dispatchKeyEvent(event);
    }

    @Override

    public boolean onKeyDown(int k, android.view.KeyEvent e) {
        int sc = e != null ? e.getScanCode() : 0;

        // UNIVERSAL CRASH PROTECTION: Swallow dial events on ALL cameras

        if (k == 624 || k == ScalarInput.ISV_KEY_MODE_DIAL ||
           (k >= ScalarInput.ISV_KEY_MODE_INVALID && k <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {

            // AUTO-DISCOVERY: If boot detection failed but they turned a physical dial,
            // lock out the software wheel permanently for this session!
            if (!hasPhysicalPasmDial) hasPhysicalPasmDial = true;

            if (cameraManager != null) onHardwareStateChanged();
            return true; // Prevents the OS from force-closing the app
        }

        if (shouldBlockShutterInput() && isShutterInput(sc, k)) return true;
        if (isFullShutterInput(sc, k) && (e == null || e.getRepeatCount() == 0)) {
            armFileScanner();
        }

        // --- FIXED: Added standard Android keycode ---

        if (k == ScalarInput.ISV_KEY_PLAY || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (playbackController.isActive()) playbackController.exit();
            else if (!menuController.isOpen() && !isProcessing) playbackController.enter();
            return true; // Swallow the press
        }

        if (inputManager != null) return inputManager.handleKeyDown(k, e) || super.onKeyDown(k, e);
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyUp(int k, android.view.KeyEvent e) {
        int sc = e != null ? e.getScanCode() : 0;
        // UNIVERSAL CRASH PROTECTION: Swallow dial events on ALL cameras
        if (k == 624 || k == ScalarInput.ISV_KEY_MODE_DIAL ||
           (k >= ScalarInput.ISV_KEY_MODE_INVALID && k <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {
            return true;
        }

        // Protect shutter inputs while processing
        if (isProcessing && isShutterInput(sc, k)) return true;

        if (isFullShutterInput(sc, k) && (e == null || e.getRepeatCount() == 0)) {
            armFileScanner();
        }

        // --- CRITICAL: Swallow the release event so the Sony OS does nothing ---
        if (k == ScalarInput.ISV_KEY_PLAY || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            return true;
        }

        // Pass everything else down the chain
        if (inputManager != null) return inputManager.handleKeyUp(k, e) || super.onKeyUp(k, e);
        return super.onKeyUp(k, e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // REMOVED: cameraManager.start() which was causing the build error!
        registerBatteryReceiver();

        // --- PREVENT PASM DIAL CRASH ON A7II ---
        // Register a receiver to swallow Sony's internal hardware state broadcasts
        // This stops ScalarABlackLauncher from force-closing the app.
        if (hardwareStateReceiver == null) {
            hardwareStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    android.util.Log.e("JPEG.CAM", "Hardware State Broadcast Intercepted!");
                    if (cameraManager != null) onHardwareStateChanged();
                }
            };
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.sony.scalar.hardware.action.MODE_DIAL_CHANGED");
        filter.addAction("com.android.server.DAConnectionManagerService.HardwareStateChanged");
        registerReceiver(hardwareStateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacksAndMessages(null);
        liveHudFlashRunning = false;
        liveHudFlashVisible = true;

        // --- NEW: Release the A7II hardware dial lock ---
        if (hardwareStateReceiver != null) {
            try {
                unregisterReceiver(hardwareStateReceiver);
            } catch (Exception e) {}
        }

        if (cameraManager != null && cameraManager.getCamera() != null) {
            try {
                Camera c = cameraManager.getCamera();
                Camera.Parameters p = c.getParameters();

                // --- 1. SAVE PASM MODE ---
                String currentPasm = p.getSceneMode();
                if (currentPasm != null) {
                    getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE)
                        .edit().putString("savedPasmMode", currentPasm).apply();
                }

                // --- 2. CLEAN FOCUS RESTORE ---
                // Put the lens back to exactly how the user had it.
                if (menuController.getSavedFocusMode() != null) {
                    p.setFocusMode(menuController.getSavedFocusMode());
                } else {
                    p.setFocusMode("auto"); // Failsafe so stock OS doesn't get stuck
                }

                // --- 3. ZERO OUT HARDWARE HACKS ---
                if (p.get("picture-effect") != null) p.set("picture-effect", "off");
                if (p.get("rgb-matrix-mode") != null) p.set("rgb-matrix-mode", "false");
                if (p.get("pro-color-mode") != null) p.set("pro-color-mode", "off");
                if (p.get("sharpness-gain-mode") != null) p.set("sharpness-gain-mode", "false");
                if (p.get("white-balance-shift-mode") != null) p.set("white-balance-shift-mode", "false");
                if (p.get("rgb-matrix") != null) p.set("rgb-matrix", "256,0,0,0,256,0,0,0,256");
                if (p.get("lens-correction-shading-color-red") != null) p.set("lens-correction-shading-color-red", "0");
                if (p.get("lens-correction-shading-color-blue") != null) p.set("lens-correction-shading-color-blue", "0");

                if (p.get("color-depth-red") != null) {
                    p.set("color-depth-red", "0"); p.set("color-depth-green", "0");
                    p.set("color-depth-blue", "0"); p.set("color-depth-cyan", "0");
                    p.set("color-depth-magenta", "0"); p.set("color-depth-yellow", "0");
                }

                c.setParameters(p);
                Log.d("JPEG.CAM", "Successfully saved mode, zeroed hardware, and restored focus.");
                Thread.sleep(200);
            } catch (Exception e) {}
        }

        if (cameraManager != null) cameraManager.close();
        try {
            unregisterReceiver(sonyCameraReceiver);
        } catch (Exception e) {}
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception e) {}
            batteryReceiverRegistered = false;
        }

        if (connectivityManager != null) connectivityManager.stopNetworking();
        if (recipeManager != null) recipeManager.savePreferences();
        setAutoPowerOffMode(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // CRITICAL: Shut down the networking and hardware antenna FIRST
        // to prevent battery drain after the app closes.
        if (connectivityManager != null) {
            connectivityManager.shutdown();
        }

        System.exit(0); // THEN exit to clear memory leaks
    }

    private void setHUDVisibility(int v) {
        if (tvTopStatus != null) {
            // FIX: If processing, FORCE visible. Otherwise, obey the display button.
            tvTopStatus.setVisibility((isProcessing || captureWritePending) ? View.VISIBLE : v);
        }
        if (llBottomBar != null) llBottomBar.setVisibility(v);
        if (tvBattery != null) tvBattery.setVisibility(v);
        if (batteryIcon != null) batteryIcon.setVisibility(v);
        if (tvMode != null) tvMode.setVisibility(v);
        if (tvFocusMode != null) tvFocusMode.setVisibility(v);
        if (tvReview != null) tvReview.setVisibility(v);
        if (focusMeter != null) {
            focusMeter.setVisibility((v == View.VISIBLE && cachedIsManualFocus && prefShowFocusMeter) ? View.VISIBLE : View.GONE);
        }
    }

    private void hideLiveViewOverlays() {
        setHUDVisibility(View.GONE);
        if (gridLines != null) gridLines.setVisibility(View.GONE);
        if (cinemaMattes != null) cinemaMattes.setVisibility(View.GONE);
        if (focusMeter != null) focusMeter.setVisibility(View.GONE);
        if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
        if (afOverlay != null) afOverlay.setVisibility(View.GONE);
        if (hudController != null) hudController.hideOverlays();
    }

    private void setLiveUiSuppressed(boolean suppressed) {
        liveUiSuppressed = suppressed;
        if (suppressed) hideLiveViewOverlays();
        else updateMainHUD();
    }

    private boolean shouldPulseLiveHudSelection() {
        return false;
    }

    private void updateLiveHudFlashTimer() {
        liveHudFlashRunning = false;
        liveHudFlashVisible = true;
        uiHandler.removeCallbacks(liveHudFlashRunnable);
    }

    public void updateMainHUD() {
        updateLiveHudFlashTimer();
        boolean liveControlEditing = !isDialLocked;

        if (cameraManager == null || cameraManager.getCamera() == null) return;

        if (liveUiSuppressed && !isProcessing) {
            hideLiveViewOverlays();
            return;
        }

        // --- 1. CLEAN DISPLAY CHECK ---
        if (hudController.isActive()) {
            // Hide the live-view bars and let HudController own its own header.
            setHUDVisibility(View.GONE);
            if (tvTopStatus != null) tvTopStatus.setVisibility(View.GONE);

            if (focusMeter != null) focusMeter.setVisibility(View.GONE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
            return;
        } else {
            // NORMAL STATE: Explicitly force the HUD to reappear
            setHUDVisibility(View.VISIBLE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
        }

        // --- 2. GATHER HARDWARE DATA ---
        Camera c = cameraManager.getCamera();
        Camera.Parameters p = c.getParameters();
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);

        RTLProfile prof = recipeManager.getCurrentProfile();
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        String displayName = name.length() > 15 ? name.substring(0, 12) + "..." : name;

        String customName = prof.profileName != null ? prof.profileName.trim() : ("RECIPE " + (recipeManager.getCurrentSlot() + 1));
        if (customName.isEmpty()) customName = "RECIPE " + (recipeManager.getCurrentSlot() + 1);

        // --- 3. UPDATE TEXT FIELDS ---
        if (!isProcessing && tvTopStatus != null) {
            if (diptychManager != null && diptychManager.isEnabled() && diptychManager.getState() == DiptychManager.STATE_NEED_SECOND) {
                tvTopStatus.setText("SHOT 1 SAVED. [L/R] TO SWAP SIDE.");
                UiTheme.softPanel(tvTopStatus);
                tvTopStatus.setTextColor(UiTheme.SUCCESS);
            } else if (diptychManager != null && diptychManager.isEnabled() && diptychManager.getState() == DiptychManager.STATE_STITCHING) {
                tvTopStatus.setText("STITCHING DIPTYCH...");
                UiTheme.softPanel(tvTopStatus);
                tvTopStatus.setTextColor(UiTheme.WARN);
            } else {
                int slotNum = recipeManager.getCurrentSlot() + 1;
                String readyText = isReady ? "READY" : "LOADING..";
                int queued = processingQueueManager != null ? processingQueueManager.getCountForMode(currentQueueMode()) : 0;
                if (queued > 0) readyText += " | Q: " + queued;
                if (processingFrequency == PROCESSING_FREQUENCY_MANUAL) readyText += " | MANUAL PROCESSING";
                else if (processingFrequency > 1) readyText += " | AUTO PROCESS AT " + processingFrequency + " SHOTS";

                if (isPrefCinemaMattes()) readyText += " | XPAN CROP";
                else if (isPrefDiptych()) readyText += " | DIPTYCH MODE";
                else if (isPrefMultiExpose()) readyText += " | MULTI-EXP MODE";

                tvTopStatus.setText("SLOT " + slotNum + ": " + customName + "\n" + readyText);

                if (mDialMode == DIAL_MODE_RTL) {
                    styleLiveHudBubble(tvTopStatus, true, liveControlEditing);
                } else if (isReady) {
                    UiTheme.softPanel(tvTopStatus);
                    tvTopStatus.setTextColor(UiTheme.SUCCESS);
                } else {
                    UiTheme.softPanel(tvTopStatus);
                    tvTopStatus.setTextColor(UiTheme.ACCENT);
                }
            }
        }

        String sm = p.getSceneMode();
        if (tvMode != null) {
            if ("manual-exposure".equals(sm)) tvMode.setText("M");
            else if ("aperture-priority".equals(sm)) tvMode.setText("A");
            else if ("shutter-priority".equals(sm) || "shutter-speed".equals(sm)) tvMode.setText("S");
            else if ("program-auto".equals(sm)) tvMode.setText("P");
            else if ("auto".equals(sm)) tvMode.setText("AUTO");
            else tvMode.setText(sm != null ? sm.toUpperCase() : "SCN");
        }

        cachedAperture = pm.getAperture() / 100.0f;
        Pair<Integer, Integer> ss = pm.getShutterSpeed();

        if (tvValAperture != null) {
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                tvValAperture.setText(String.format("f%.1f", virtualAperture));
            } else {
                tvValAperture.setText(String.format("f%.1f", cachedAperture));
            }
        }

        if (tvValShutter != null) tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\"");
        if (tvValIso != null) tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity());
        if (tvValEv != null) tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));

        if (tvReview != null) {
            styleLiveHudBubble(tvReview, mDialMode == DIAL_MODE_REVIEW, liveControlEditing);
        }

        styleLiveHudBubble(tvValShutter, mDialMode == DIAL_MODE_SHUTTER, liveControlEditing);
        styleLiveHudBubble(tvValAperture, mDialMode == DIAL_MODE_APERTURE, liveControlEditing);
        styleLiveHudBubble(tvValIso, mDialMode == DIAL_MODE_ISO, liveControlEditing);
        styleLiveHudBubble(tvValEv, mDialMode == DIAL_MODE_EXPOSURE, liveControlEditing);
        styleLiveHudBubble(tvMode, mDialMode == DIAL_MODE_PASM, liveControlEditing);

        String fm = p.getFocusMode();
        cachedIsManualFocus = "manual".equals(fm);

        if (tvFocusMode != null) {
            if ("auto".equals(fm)) tvFocusMode.setText("AF-S");
            else if (cachedIsManualFocus) {
                String rawName = lensManager != null ? lensManager.getCurrentLensName() : "Unmapped Lens";
                String lName = LensProfileManager.formatDisplayName(rawName);
                tvFocusMode.setText("MF [" + lName + "]");
            }
            else if ("continuous-video".equals(fm) || "continuous-picture".equals(fm)) tvFocusMode.setText("AF-C");
            else tvFocusMode.setText(fm != null ? fm.toUpperCase() : "AF");

            styleLiveHudBubble(tvFocusMode, mDialMode == DIAL_MODE_FOCUS, liveControlEditing);
        }

        // --- 4. UPDATE FOCUS METER ---
        if (focusMeter != null) {
            boolean shouldShow = prefShowFocusMeter && cachedIsManualFocus;
            focusMeter.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            if (shouldShow) {
                boolean cal = calibController.isCalibrating();
                float focalToUse = cal ? calibController.getDetectedFocalLength() : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                List<LensProfileManager.CalPoint> ptsToUse = cal ? calibController.getTempCalPoints() : (lensManager != null ? lensManager.getCurrentPoints() : null);
                float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualFocusRatio : cachedFocusRatio;
                float apToFeed    = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualAperture   : cachedAperture;

                focusMeter.update(ratioToFeed, apToFeed, focalToUse, cal, ptsToUse, getCircleOfConfusion());
            }
        }

        if (gridLines != null) gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE);
        if (cinemaMattes != null) cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);

        if (diptychManager != null) {
            if (diptychManager.isEnabled()) {
                // Force conflicting UI elements off while Diptych is active
                if (cinemaMattes != null) cinemaMattes.setVisibility(View.GONE);
                if (gridLines != null) gridLines.setVisibility(View.GONE);
            }
        }

        // --- 5. CALIBRATION OVERRIDES ---
        // Overrides the "Normal State" visibility if we are currently mapping a lens.
        if (calibController.isActive()) {
            setHUDVisibility(View.GONE);
            if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        hasSurface = true;
        if (cameraManager != null) cameraManager.open(h);
        updateDiptychPreviewWindow();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        hasSurface = false;
        if (cameraManager != null) cameraManager.close();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}

    @Override
    public void onHardwareStateChanged() {
        runOnUiThread(new Runnable() {
            public void run() {
                requestHudUpdate();
            }
        });
    }

    @Override
    public void onCameraReady() {
        syncHardwareState();
        if (cameraManager != null) {
            hardwareFocalLength = cameraManager.getInitialFocalLength();
            isNativeLensAttached = (hardwareFocalLength > 0.0f);
            if (isNativeLensAttached) autoEquipMatchingLens(hardwareFocalLength);

            if (cameraManager.getCamera() != null) {
                try {
                    Camera c = cameraManager.getCamera();
                    Camera.Parameters p = c.getParameters();

                    // --- NEW: CLEAN FOCUS INHERITANCE ---
                    // Sony hardware silently rejects AF mode changes if an incompatible
                    // Focus Area (like Flexible Spot) was inherited from the native OS.
                    if (android.os.Build.VERSION.SDK_INT >= 14) {
                        try {
                            if (p.getMaxNumFocusAreas() > 0) p.setFocusAreas(null);
                        } catch (Throwable t) {}
                    }
                    if (p.get("sony-focus-area") != null) p.set("sony-focus-area", "wide");
                    c.setParameters(p); // Apply clean slate immediately

                    cachedIsManualFocus = "manual".equals(p.getFocusMode());

                    // --- NEW: BULLETPROOF SENSOR DETECTION ---
                    // Older A7 cameras don't expose 'apsc-mode-supported' in parameters.
                    // We check the physical hardware model name instead.
                    String model = android.os.Build.MODEL;
                    boolean hardwareIsFullFrame = model != null && (
                        model.contains("ILCE-7") || model.contains("ILCE-9") ||
                        model.contains("ILCE-1") || model.contains("DSC-RX1")
                    );

                    // Check if the user actively turned on APS-C crop mode in the Sony menu
                    boolean isCropActive = "on".equals(p.get("sony-apsc-mode"));

                    isFullFrame = hardwareIsFullFrame && !isCropActive;

                    android.util.Log.e("JPEG.CAM", "Model: " + model + " | Sensor: " + (isFullFrame ? "FULL FRAME" : "APS-C"));
                } catch (Exception e) {
                    android.util.Log.e("JPEG.CAM", "Boot sync failed: " + e.getMessage());
                }
            }
        }

        applyHardwareRecipe();

        // Do an initial HUD draw
        updateMainHUD();

        // --- FIRMWARE RACE CONDITION FIX ---
        // The physical dial takes a moment to report its true position to the OS on boot.
        // Force a re-sync 500ms after the app opens to guarantee accuracy.
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cameraManager != null) onHardwareStateChanged();
            }
        }, 500);
    }

    @Override public void onShutterSpeedChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    @Override public void onApertureChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    @Override public void onIsoChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }

    @Override
    public void onFocusPositionChanged(final float ratio) {
        if (focusMeter != null && cachedIsManualFocus) {
            runOnUiThread(new Runnable() {
                public void run() {
                    cachedFocusRatio = ratio;
                    boolean cal = calibController.isCalibrating();
                    float focalToUse = cal ? calibController.getDetectedFocalLength() : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                    List<LensProfileManager.CalPoint> ptsToUse = cal ? calibController.getTempCalPoints() : (lensManager != null ? lensManager.getCurrentPoints() : null);
                    float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualFocusRatio : cachedFocusRatio;
                    float apToFeed    = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualAperture   : cachedAperture;
                    focusMeter.update(ratioToFeed, apToFeed, focalToUse, cal, ptsToUse, getCircleOfConfusion());
                }
            });
        }
    }

    @Override
    public void onFocalLengthChanged(final float focalLengthMm) {
        runOnUiThread(new Runnable() {
            public void run() {
                hardwareFocalLength = focalLengthMm;
                if (focalLengthMm > 0.0f) {
                    boolean wasManual = !isNativeLensAttached;
                    isNativeLensAttached = true;
                    Log.d("JPEG.CAM_Lens", "Native Lens Zoomed: " + focalLengthMm + "mm");

                    autoEquipMatchingLens(focalLengthMm);

                    if (wasManual && cameraManager != null && cameraManager.getCamera() != null) {
                        try {
                            Camera c = cameraManager.getCamera();
                            Camera.Parameters p = c.getParameters();
                            p.setFocusMode("auto");
                            c.setParameters(p);
                            cachedIsManualFocus = false;
                        } catch (Exception e) {}
                    }
                } else {
                    isNativeLensAttached = false;
                    Log.d("JPEG.CAM_Lens", "Manual Lens Detected.");
                }
                updateMainHUD();
            }
        });
    }

    @Override
    public void onStatusUpdate(final String target, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                menuController.updateConnectionStatus(target, status);
            }
        });
    }

    // --- PlaybackController.HostCallback ---
    @Override public FrameLayout getMainUIContainer() { return mainUIContainer; }
    @Override public int getDisplayState()      { return displayState; }

    // --- LensCalibrationController.HostCallback ---
    @Override public LensProfileManager getLensManager()     { return lensManager; }
    @Override public boolean isNativeLensAttached()          { return isNativeLensAttached; }
    @Override public float getHardwareFocalLength()          { return hardwareFocalLength; }
    @Override public float getCircleOfConfusion()            { return isFullFrame ? 0.030f : 0.020f; }
    @Override public float getCachedFocusRatio()             { return cachedFocusRatio; }

    @Override
    public void onCalibrationComplete(String lensFilename) {
        availableLenses = lensManager.getAvailableLenses();
        currentLensIndex = availableLenses.indexOf(lensFilename);
        if (currentLensIndex == -1) currentLensIndex = 0;
        virtualAperture = lensManager.currentMaxAperture;
        virtualFocusRatio = 0.5f;
        setHUDVisibility(View.VISIBLE);
        updateMainHUD();
    }

    @Override
    public void onCalibrationCancelled() {
        setHUDVisibility(View.VISIBLE);
        updateMainHUD();
    }

    // --- MenuController.HostCallback ---
    @Override public RecipeManager       getRecipeManager()       { return recipeManager; }
    @Override public ConnectivityManager getConnectivityManager() { return connectivityManager; }
    @Override public MatrixManager       getMatrixManager()       { return matrixManager; }
    @Override public Camera              getCamera()              { return cameraManager != null ? cameraManager.getCamera() : null; }
    @Override public String              getAppVersion()          { return getPackageManager() != null ? tryGetVersion() : "?"; }

    private String tryGetVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    @Override public boolean isPrefFocusMeter()   { return prefShowFocusMeter; }
    @Override public boolean isPrefCinemaMattes() { return prefShowCinemaMattes; }
    @Override public boolean isPrefGridLines()    { return prefShowGridLines; }
    @Override public int     getPrefJpegQuality() { return prefJpegQuality; }
    @Override public boolean isPrefDiptych()      { return diptychManager != null && diptychManager.isEnabled(); }
    @Override public boolean isPrefMultiExpose()  { return getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE).getBoolean("multiExposeEnabled", false); }
    @Override public int     getMultiExposeCount() { return multiExposeManager != null ? multiExposeManager.getTotalExposures() : 2; }
    @Override public int     getMultiExposeBlendMode() { return multiExposeManager != null ? multiExposeManager.getBlendMode() : 0; }
    @Override public int     getProcessingFrequency() { return processingFrequency; }
    @Override public int     getQueuedPhotoCount() { return processingQueueManager != null ? processingQueueManager.getCountForMode(currentQueueMode()) : 0; }
    @Override public List<ProcessingQueueManager.Entry> getQueuedPhotoEntries() {
        if (processingQueueManager == null) return new ArrayList<ProcessingQueueManager.Entry>();
        return processingQueueManager.getEntriesForMode(currentQueueMode());
    }
    @Override public ProcessingQueueManager.Entry getQueuedPhotoEntry(int index) {
        if (processingQueueManager == null) return null;
        return processingQueueManager.getEntryForMode(index, currentQueueMode());
    }
    @Override public String getProcessingEstimateText(int photoCount) {
        return formatProcessingEstimateForCount(photoCount);
    }
    @Override public void    setPrefFocusMeter(boolean v)   { prefShowFocusMeter   = v; }
    @Override public void    setPrefCinemaMattes(boolean v) { prefShowCinemaMattes = v; }
    @Override public void    setPrefGridLines(boolean v)    { prefShowGridLines    = v; }
    @Override public void    setPrefJpegQuality(int v)      { prefJpegQuality      = v; }
    @Override public void    setProcessingFrequency(int v)   { processingFrequency = normalizeProcessingFrequency(v); saveAppPreferences(); updateMainHUD(); }
    @Override public void    setPrefDiptych(boolean v)      {
        if (diptychManager != null) {
            diptychManager.setEnabled(v);
            if (!v && cameraManager != null && cameraManager.getCamera() != null) {
                try {
                    android.hardware.Camera.Parameters p = cameraManager.getCamera().getParameters();
                    // --- HARDWARE CLEANUP ---
                    // Reset AF/AE areas to null (Wide/Auto)
                    if (p.getMaxNumFocusAreas() > 0) p.setFocusAreas(null);
                    if (p.getMaxNumMeteringAreas() > 0) p.setMeteringAreas(null);
                    
                    // Revert Sony focus area to 'wide'
                    if (p.get("sony-focus-area") != null) p.set("sony-focus-area", "wide");
                    
                    cameraManager.getCamera().setParameters(p);
                    
                    if (afOverlay != null) afOverlay.setDiptychCenterX(-1);
                } catch (Exception ignored) {}
            }
        }
        updateMainHUD();
    }
    @Override public void forceProcessQueuedPhotos() {
        startQueuedProcessing(processingFrequency == PROCESSING_FREQUENCY_MANUAL);
    }
    @Override public void processSelectedQueuedPhotos(boolean[] selected) {
        if (processingQueueManager == null) return;
        int selectedCount = processingQueueManager.moveSelectedToFrontForMode(selected, ProcessingQueueManager.MODE_MANUAL);
        updateMainHUD();
        if (selectedCount > 0) startQueuedProcessing(true, selectedCount);
    }
    @Override public void clearSelectedQueuedPhotos(boolean[] selected) {
        if (processingQueueManager != null) {
            processingQueueManager.removeSelectedForMode(selected, currentQueueMode());
        }
        updateMainHUD();
    }
    @Override public void clearQueuedPhotos() {
        if (processingQueueManager != null) processingQueueManager.clearForMode(currentQueueMode());
        updateMainHUD();
    }


    @Override public void closeHud() { hudController.reset(); }

    @Override public void onMenuOpened()  { refreshRecipes(); }

    @Override public void onMenuClosed() {
        recipeManager.savePreferences();
        saveAppPreferences();
        if (isProcessing || processingQueueActive) {
            updateMainHUD();
            return;
        }
        triggerLutPreload();
        applyHardwareRecipe();
        syncHardwareState();
        updateMainHUD();
    }

    @Override public void onLutPreloadNeeded()    { triggerLutPreload(); }
    @Override public void scheduleHardwareApply() {
        uiHandler.removeCallbacks(applySettingsRunnable);
        uiHandler.postDelayed(applySettingsRunnable, 150);
    }
    @Override public void onHudModeRequested(int mode) { launchHudMode(mode); }
    @Override public void onSetAutoPowerOffMode(boolean on) { setAutoPowerOffMode(on); }

    @Override public void restoreFocusMode(String savedMode) {
        if (savedMode != null && cameraManager != null && cameraManager.getCamera() != null) {
            try {
                Camera.Parameters p = cameraManager.getCamera().getParameters();
                p.setFocusMode(savedMode);
                cameraManager.getCamera().setParameters(p);
            } catch (Exception ignored) {}
        }
    }

    // --- HudController.HostCallback ---
    @Override public TextView  getTvTopStatus()   { return tvTopStatus; }
    @Override public Typeface  getDigitalFont()   { return digitalFont; }
    @Override public Handler   getUiHandler()     { return uiHandler; }
    @Override public MenuController getMenuController() { return menuController; }
    @Override public void applyHardwareRecipeNow() { applyHardwareRecipe(); }
    @Override public void onHudClosed() {
        mainUIContainer.setVisibility(View.GONE);
        menuController.getContainer().setVisibility(View.VISIBLE);
        menuController.refreshDisplay();
    }

    private void syncHardwareState() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        Camera c = cameraManager.getCamera();
        String fMode = c.getParameters().getFocusMode();
        cachedIsManualFocus = "manual".equals(fMode);
    }
}
