package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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

import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, 
    SonyCameraManager.CameraEventListener, InputManager.InputListener, ConnectivityManager.StatusUpdateListener {

    // --- Managers ---
    private SonyCameraManager cameraManager;
    private InputManager inputManager;
    private RecipeManager recipeManager;
    private ConnectivityManager connectivityManager;
    private MenuManager menuManager;
    
    // --- Engines ---
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;

    // --- UI Components ---
    private SurfaceView mSurfaceView;
    private FrameLayout mainUIContainer, playbackContainer;
    private LinearLayout menuContainer, llBottomBar;
    private TextView tvTopStatus, tvBattery;
    private TextView tvValShutter, tvValAperture, tvValIso, tvValEv;
    private TextView tvMode, tvFocusMode, tvReview;
    private TextView tvMenuTitle;
    private TextView[] tvPageNumbers = new TextView[4];
    private LinearLayout[] menuRows = new LinearLayout[7];
    private TextView[] menuLabels = new TextView[7];
    private TextView[] menuValues = new TextView[7];
    private BatteryView batteryIcon;
    
    // --- Playback State ---
    private ImageView playbackImageView;
    private TextView tvPlaybackInfo;
    private List<File> playbackFiles = new ArrayList<File>();
    private Bitmap currentPlaybackBitmap = null;
    private int playbackIndex = 0;

    // --- Core App State ---
    private boolean isPlaybackMode = false;
    private boolean isMenuOpen = false;
    private boolean isProcessing = false;
    private boolean isReady = false;
    private int displayState = 0; 
    
    // --- UI Toggles ---
    private boolean prefShowFocusMeter = true;
    private boolean prefShowCinemaMattes = false;
    private boolean prefShowGridLines = false;

    // --- Custom Views ---
    private GridLinesView gridLines;
    private CinemaMatteView cinemaMattes;
    private AdvancedFocusMeterView focusMeter;
    private ProReticleView afOverlay;

    private Handler uiHandler = new Handler();
    private int mDialMode = InputManager.DIAL_MODE_RTL;

    // --- Receivers ---
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                final int pct = (level * 100) / scale;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvBattery.setText(pct + "%");
                        batteryIcon.setLevel(pct);
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        cameraManager = new SonyCameraManager(this);
        inputManager = new InputManager(this);
        recipeManager = new RecipeManager();
        connectivityManager = new ConnectivityManager(this, this);
        menuManager = new MenuManager();

        recipeManager.loadPreferences();
        
        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));
        
        buildUI(rootLayout);
        setContentView(rootLayout);
        setupEngines();
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { isProcessing = true; runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } }); }
            @Override public void onProcessFinished(String res) { isProcessing = false; runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setTextColor(Color.WHITE); updateMainHUD(); } }); }
        });

        String dcim = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/100MSDCF";
        mScanner = new SonyFileScanner(dcim, new SonyFileScanner.ScannerCallback() {
            @Override public boolean isReadyToProcess() { return isReady && !isProcessing && recipeManager.getCurrentProfile().lutIndex != 0; }
            @Override public void onNewPhotoDetected(final String path) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                        mProcessor.processJpeg(path, outDir.getAbsolutePath(), recipeManager.getQualityIndex(), recipeManager.getCurrentProfile());
                    }
                });
            }
        });
        mScanner.start();
        triggerLutPreload();
    }

    private void triggerLutPreload() {
        RTLProfile p = recipeManager.getCurrentProfile();
        mProcessor.triggerLutPreload(recipeManager.getRecipePaths().get(p.lutIndex), recipeManager.getRecipeNames().get(p.lutIndex));
    }

    // --- InputManager Callbacks ---
    @Override public void onShutterHalfPressed() {
        mDialMode = InputManager.DIAL_MODE_RTL;
        if (displayState == 0 && !isMenuOpen && !isPlaybackMode) setHUDVisibility(View.GONE);
        if (afOverlay != null) afOverlay.startFocus(cameraManager.getCamera());
    }

    @Override public void onShutterHalfReleased() {
        if (displayState == 0 && !isMenuOpen && !isPlaybackMode) setHUDVisibility(View.VISIBLE);
        if (afOverlay != null) afOverlay.stopFocus(cameraManager.getCamera());
    }

    @Override public void onDeletePressed() { finish(); }

    @Override public void onMenuPressed() {
        isMenuOpen = !isMenuOpen;
        if (isMenuOpen) {
            recipeManager.scanRecipes();
            menuManager.setPage(1);
            menuContainer.setVisibility(View.VISIBLE);
            mainUIContainer.setVisibility(View.GONE);
            renderMenu();
        } else {
            menuContainer.setVisibility(View.GONE);
            mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
            recipeManager.savePreferences();
            triggerLutPreload();
            updateMainHUD();
        }
    }

    @Override public void onEnterPressed() {
        if (!isMenuOpen) {
            if (mDialMode == InputManager.DIAL_MODE_REVIEW) enterPlayback();
            else { displayState = (displayState == 0) ? 1 : 0; mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); }
        } else if (menuManager.getCurrentPage() == 4) {
            handleConnectionAction();
        }
    }

    @Override public void onUpPressed() { menuManager.moveSelection(-1); renderMenu(); }
    @Override public void onDownPressed() { menuManager.moveSelection(1); renderMenu(); }
    @Override public void onLeftPressed() { if (isMenuOpen) handleMenuHorizontal(-1); else cycleDialMode(-1); }
    @Override public void onRightPressed() { if (isMenuOpen) handleMenuHorizontal(1); else cycleDialMode(1); }
    @Override public void onDialRotated(int direction) { if (isMenuOpen) handleMenuChange(direction); else handleHardwareInput(direction); }

    private void handleMenuHorizontal(int d) { 
        if (menuManager.getSelection() == -1) { menuManager.cyclePage(d); renderMenu(); } 
        else handleMenuChange(d); 
    }

    private void handleHardwareInput(int d) {
        Camera c = cameraManager.getCamera();
        CameraEx cx = cameraManager.getCameraEx();
        if (c == null || cx == null) return;
        Camera.Parameters p = c.getParameters();
        CameraEx.ParametersModifier pm = cx.createParametersModifier(p);

        if (mDialMode == InputManager.DIAL_MODE_RTL) { recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + d); applyHardwareRecipe(); triggerLutPreload(); }
        else if (mDialMode == InputManager.DIAL_MODE_SHUTTER) { if (d > 0) cx.incrementShutterSpeed(); else cx.decrementShutterSpeed(); }
        else if (mDialMode == InputManager.DIAL_MODE_APERTURE) { if (d > 0) cx.incrementAperture(); else cx.decrementAperture(); }
        else if (mDialMode == InputManager.DIAL_MODE_ISO) {
            List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
            int idx = isos.indexOf(pm.getISOSensitivity());
            if (idx != -1) { pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); c.setParameters(p); }
        }
        else if (mDialMode == InputManager.DIAL_MODE_PASM) {
            String[] desired = {"manual-exposure", "aperture-priority", "shutter-priority", "program-auto"};
            List<String> supported = p.getSupportedSceneModes();
            List<String> valid = new ArrayList<String>();
            for(String s : desired) if(supported.contains(s)) valid.add(s);
            int idx = valid.indexOf(p.getSceneMode());
            p.setSceneMode(valid.get((idx + d + valid.size()) % valid.size()));
            c.setParameters(p);
        }
        updateMainHUD();
    }

    private void applyHardwareRecipe() {
        Camera c = cameraManager.getCamera();
        if (c == null) return;
        RTLProfile prof = recipeManager.getCurrentProfile();
        Camera.Parameters p = c.getParameters();
        
        String targetWb = "auto";
        if ("DAY".equals(prof.whiteBalance)) targetWb = "daylight";
        else if ("SHD".equals(prof.whiteBalance)) targetWb = "shade";
        else if ("CLD".equals(prof.whiteBalance)) targetWb = "cloudy-daylight";
        else if ("INC".equals(prof.whiteBalance)) targetWb = "incandescent";
        else if ("FLR".equals(prof.whiteBalance)) targetWb = "fluorescent";
        
        p.setWhiteBalance(targetWb);
        if (p.get("dro-mode") != null) {
            if ("OFF".equals(prof.dro)) p.set("dro-mode", "off");
            else if ("AUTO".equals(prof.dro)) p.set("dro-mode", "auto");
            else if (prof.dro.startsWith("LV")) { p.set("dro-mode", "on"); p.set("dro-level", prof.dro.replace("LV", "")); }
        }
        p.set("contrast", String.valueOf(prof.contrast));
        p.set("saturation", String.valueOf(prof.saturation));
        p.set("sharpness", String.valueOf(prof.sharpness));
        p.set("white-balance-shift-lb", String.valueOf(prof.wbShift));
        p.set("white-balance-shift-cc", String.valueOf(prof.wbShiftGM));
        c.setParameters(p);
    }

    // --- CameraEventListener ---
    @Override public void onCameraReady() { applyHardwareRecipe(); updateMainHUD(); }
    @Override public void onShutterSpeedChanged() { runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
    @Override public void onApertureChanged() { runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
    @Override public void onIsoChanged() { runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
    @Override public void onFocusPositionChanged(final float ratio) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (focusMeter != null && mDialMode == InputManager.DIAL_MODE_FOCUS) {
                    Camera c = cameraManager.getCamera();
                    if (c != null) {
                        float ap = cameraManager.getCameraEx().createParametersModifier(c.getParameters()).getAperture() / 100.0f;
                        focusMeter.update(ratio, ap, true);
                    }
                }
            }
        });
    }

    // --- StatusUpdateListener ---
    @Override public void onStatusUpdate(String target, String status) {
        if (isMenuOpen && menuManager.getCurrentPage() == 4) renderMenu();
    }

    // --- Playback Management ---
    private void enterPlayback() {
        playbackFiles.clear();
        File dir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) if (f.getName().toLowerCase().endsWith(".jpg")) playbackFiles.add(f);
        }
        Collections.sort(playbackFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
        
        if (playbackFiles.isEmpty()) return;
        isPlaybackMode = true;
        mainUIContainer.setVisibility(View.GONE);
        playbackContainer.setVisibility(View.VISIBLE);
        showPlaybackImage(0);
    }

    private void showPlaybackImage(int idx) {
        if (idx < 0 || idx >= playbackFiles.size()) return;
        playbackIndex = idx;
        File file = playbackFiles.get(idx);
        
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            String info = (idx + 1) + "/" + playbackFiles.size() + "\n" + file.getName();
            tvPlaybackInfo.setText(info);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            playbackImageView.setImageBitmap(bmp);
            if (currentPlaybackBitmap != null) currentPlaybackBitmap.recycle();
            currentPlaybackBitmap = bmp;
        } catch (Exception e) {}
    }

    // --- Menu Actions ---
    private void handleMenuChange(int dir) {
        RTLProfile p = recipeManager.getCurrentProfile();
        int sel = menuManager.getSelection();
        int pg = menuManager.getCurrentPage();

        if (pg == 1) { 
            switch(sel) {
                case 0: recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + dir); break;
                case 1: p.lutIndex = (p.lutIndex + dir + recipeManager.getRecipePaths().size()) % recipeManager.getRecipePaths().size(); break;
                case 2: p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); break;
                case 3: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break;
                case 4: p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); break;
                case 5: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break;
                case 6: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break;
            }
        } else if (pg == 2) { 
            switch(sel) {
                case 1: p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dir)); break;
                case 2: p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dir)); break;
                case 4: p.contrast = Math.max(-3, Math.min(3, p.contrast + dir)); break;
                case 5: p.saturation = Math.max(-3, Math.min(3, p.saturation + dir)); break;
                case 6: p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir)); break;
            }
        } else if (pg == 3) { 
            switch(sel) {
                case 0: recipeManager.setQualityIndex(recipeManager.getQualityIndex() + dir); break;
                case 2: prefShowFocusMeter = !prefShowFocusMeter; break;
                case 3: prefShowCinemaMattes = !prefShowCinemaMattes; break;
                case 4: prefShowGridLines = !prefShowGridLines; break;
            }
        }
        renderMenu(); recipeManager.savePreferences(); applyHardwareRecipe();
    }

    private void handleConnectionAction() {
        int sel = menuManager.getSelection();
        if (sel == 0) connectivityManager.startHotspot();
        else if (sel == 1) connectivityManager.startHomeWifi();
        else if (sel == 2) connectivityManager.stopNetworking();
    }

    private void renderMenu() {
        menuManager.render(tvMenuTitle, tvPageNumbers, menuRows, menuLabels, menuValues, recipeManager, connectivityManager);
    }

    // --- Lifecycle ---
    @Override public boolean onKeyDown(int k, KeyEvent e) { return inputManager.handleKeyDown(k, e) || super.onKeyDown(k, e); }
    @Override public boolean onKeyUp(int k, KeyEvent e) { return inputManager.handleKeyUp(k, e) || super.onKeyUp(k, e); }
    @Override protected void onResume() { 
        super.onResume(); 
        cameraManager.open(mSurfaceView.getHolder()); 
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
    @Override protected void onPause() { 
        super.onPause(); 
        cameraManager.close(); 
        connectivityManager.stopNetworking(); 
        recipeManager.savePreferences(); 
        unregisterReceiver(batteryReceiver);
    }

    // --- UI Setup ---
    private void setHUDVisibility(int v) {
        tvTopStatus.setVisibility(v); llBottomBar.setVisibility(v);
        tvBattery.setVisibility(v); batteryIcon.setVisibility(v);
        tvMode.setVisibility(v); tvFocusMode.setVisibility(v); tvReview.setVisibility(v);
        if (focusMeter != null && prefShowFocusMeter) focusMeter.setVisibility(v);
    }

    private void updateMainHUD() {
        Camera c = cameraManager.getCamera();
        if (c == null) return;
        Camera.Parameters p = c.getParameters();
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);
        
        RTLProfile prof = recipeManager.getCurrentProfile();
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        tvTopStatus.setText("RTL " + (recipeManager.getCurrentSlot() + 1) + " [" + (name.length() > 15 ? name.substring(0, 12) + ".." : name) + "]\n" + (isReady ? "READY" : "LOADING.."));
        tvTopStatus.setTextColor(mDialMode == InputManager.DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE);

        String sm = p.getSceneMode();
        if ("manual-exposure".equals(sm)) tvMode.setText("M");
        else if ("aperture-priority".equals(sm)) tvMode.setText("A");
        else if ("shutter-priority".equals(sm)) tvMode.setText("S");
        else tvMode.setText("P");

        Pair<Integer, Integer> ss = pm.getShutterSpeed();
        tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\"");
        tvValAperture.setText(String.format("f%.1f", pm.getAperture() / 100.0f));
        tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity());
        tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        
        tvReview.setBackgroundColor(mDialMode == InputManager.DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
        gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE);
        cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);
    }

    private void buildUI(FrameLayout root) {
        mainUIContainer = new FrameLayout(this);
        gridLines = new GridLinesView(this); mainUIContainer.addView(gridLines);
        cinemaMattes = new CinemaMatteView(this); mainUIContainer.addView(cinemaMattes);
        
        tvTopStatus = new TextView(this); tvTopStatus.setTextSize(20); tvTopStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); tp.topMargin = 15;
        mainUIContainer.addView(tvTopStatus, tp);
        
        llBottomBar = new LinearLayout(this); llBottomBar.setGravity(Gravity.CENTER);
        tvValShutter = createValText(); tvValAperture = createValText(); tvValIso = createValText(); tvValEv = createValText();
        llBottomBar.addView(tvValShutter); llBottomBar.addView(tvValAperture); llBottomBar.addView(tvValIso); llBottomBar.addView(tvValEv);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); bp.bottomMargin = 25;
        mainUIContainer.addView(llBottomBar, bp);

        focusMeter = new AdvancedFocusMeterView(this);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM); fp.bottomMargin = 100;
        mainUIContainer.addView(focusMeter, fp);
        
        afOverlay = new ProReticleView(this); mainUIContainer.addView(afOverlay);
        
        LinearLayout batteryArea = new LinearLayout(this); batteryArea.setGravity(Gravity.CENTER_VERTICAL);
        tvBattery = new TextView(this); tvBattery.setTextSize(18); batteryArea.addView(tvBattery);
        batteryIcon = new BatteryView(this); batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(45, 22));
        FrameLayout.LayoutParams batP = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT); batP.topMargin = 20; batP.rightMargin = 30;
        mainUIContainer.addView(batteryArea, batP);

        tvMode = createSideIcon("M", 20, 20); mainUIContainer.addView(tvMode);
        tvFocusMode = createSideIcon("AF-S", 20, 80); mainUIContainer.addView(tvFocusMode);
        tvReview = createSideIcon("▶", 20, 140); mainUIContainer.addView(tvReview);
        
        root.addView(mainUIContainer);
        
        menuContainer = new LinearLayout(this); menuContainer.setVisibility(View.GONE);
        menuContainer.setBackgroundColor(Color.argb(245, 15, 15, 15));
        menuContainer.setOrientation(LinearLayout.VERTICAL); menuContainer.setPadding(30, 30, 30, 30);
        tvMenuTitle = new TextView(this); tvMenuTitle.setTextSize(22); menuContainer.addView(tvMenuTitle);
        LinearLayout pages = new LinearLayout(this); pages.setGravity(Gravity.RIGHT);
        for(int i=0; i<4; i++){ tvPageNumbers[i] = new TextView(this); tvPageNumbers[i].setText(String.valueOf(i+1)); tvPageNumbers[i].setPadding(10,0,10,0); pages.addView(tvPageNumbers[i]); }
        menuContainer.addView(pages);
        for(int i=0; i<7; i++){
            menuRows[i] = new LinearLayout(this); menuLabels[i] = new TextView(this); menuValues[i] = new TextView(this);
            menuValues[i].setGravity(Gravity.RIGHT); menuRows[i].addView(menuLabels[i], new LinearLayout.LayoutParams(0,-2,1f)); menuRows[i].addView(menuValues[i]);
            menuContainer.addView(menuRows[i]);
        }
        root.addView(menuContainer);

        playbackContainer = new FrameLayout(this); playbackContainer.setVisibility(View.GONE);
        playbackImageView = new ImageView(this); playbackContainer.addView(playbackImageView);
        tvPlaybackInfo = new TextView(this); playbackContainer.addView(tvPlaybackInfo);
        root.addView(playbackContainer);
    }

    private TextView createValText() { TextView t = new TextView(this); t.setTextSize(24); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(15,0,15,0); return t; }
    private TextView createSideIcon(String txt, int x, int y) { TextView t = new TextView(this); t.setText(txt); t.setBackgroundColor(Color.argb(150, 40,40,40)); t.setPadding(20,10,20,10); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2,-2); p.leftMargin=x; p.topMargin=y; t.setLayoutParams(p); return t; }

    @Override public void surfaceCreated(SurfaceHolder h) { cameraManager.open(h); }
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
}