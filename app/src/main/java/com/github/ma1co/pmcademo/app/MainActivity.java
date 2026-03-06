package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri; 
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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

import java.util.List;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.io.*;
import java.util.ArrayList;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private boolean hasSurface = false; 
    
    // THE WALLED GARDEN: Snapshot variables to protect out-of-app settings
    private String origSceneMode = null;
    private String origFocusMode = null;
    private String origWhiteBalance = null;
    private String origDroMode = null;
    private String origDroLevel = null;
    private String origSonyDro = null;
    private String origContrast = null;
    private String origSaturation = null;
    private String origSharpness = null;
    private String origWbShiftMode = null;
    private String origWbShiftLb = null;
    private String origWbShiftCc = null;
    private String origExpComp = null;
    
    private FrameLayout mainUIContainer;
    private LinearLayout menuContainer; 
    private TextView tvMenuTitle;
    private TextView[] tvPageNumbers = new TextView[4];
    private LinearLayout menuHeaderLayout;
    private LinearLayout[] menuRows = new LinearLayout[7]; 
    private TextView[] menuLabels = new TextView[7];
    private TextView[] menuValues = new TextView[7];
    
    private TextView tvTopStatus, tvBattery, tvReview, tvMode, tvFocusMode; 
    private LinearLayout llBottomBar;
    private TextView tvValShutter, tvValAperture, tvValIso, tvValEv;
    
    private FrameLayout playbackContainer;
    private ImageView playbackImageView;
    private TextView tvPlaybackInfo;
    private List<File> playbackFiles = new ArrayList<File>();
    private int playbackIndex = 0;
    private Bitmap currentPlaybackBitmap = null;
    private boolean isPlaybackMode = false;
    
    private boolean isProcessing = false;
    private boolean isReady = false; 
    private boolean isMenuOpen = false;
    private int displayState = 0; 
    
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;
    private String sonyDCIMPath = "";
    
    private ProReticleView afOverlay;
    private AdvancedFocusMeterView focusMeter; 
    private CinemaMatteView cinemaMattes;
    private GridLinesView gridLines;
    
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    private final String[] intensityLabels = {"OFF", "LOW", "LOW+", "MID", "MID+", "HIGH"};
    private final String[] grainSizeLabels = {"SM", "MED", "LG"};
    private final String[] wbLabels = {"AUTO", "DAY", "SHD", "CLD", "INC", "FLR"};
    private final String[] droLabels = {"OFF", "AUTO", "LV1", "LV2", "LV3", "LV4", "LV5"};

    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0; 
    private int qualityIndex = 1; 
    private int currentPage = 1; 
    private int menuSelection = 0; 
    private int currentItemCount = 0; 

    private boolean prefShowFocusMeter = true;
    private boolean prefShowCinemaMattes = false;
    private boolean prefShowGridLines = false;
    private String savedFocusMode = null;

    private String connStatusHotspot = "Press ENTER to Start";
    private String connStatusWifi = "Press ENTER to Start";
    private WifiManager alphaWifiManager;
    private ConnectivityManager alphaConnManager;
    private DirectManager alphaDirectManager;
    private HttpServer alphaServer;
    private BroadcastReceiver alphaWifiReceiver, alphaDirectStateReceiver, alphaGroupCreateSuccessReceiver;
    private boolean isHomeWifiRunning = false, isHotspotRunning = false;

    public static final int DIAL_MODE_SHUTTER = 0, DIAL_MODE_APERTURE = 1, DIAL_MODE_ISO = 2, DIAL_MODE_EXPOSURE = 3, DIAL_MODE_REVIEW = 4, DIAL_MODE_RTL = 5, DIAL_MODE_PASM = 6, DIAL_MODE_FOCUS = 7;
    private int mDialMode = DIAL_MODE_RTL;
    private float lastKnownFocusRatio = 0.5f, lastKnownAperture = 2.8f;
    private int lastBatteryLevel = 100;

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) { lastBatteryLevel = (level * 100) / scale; if (tvBattery != null) tvBattery.setText(lastBatteryLevel + "%"); }
        }
    };

    private Handler uiHandler = new Handler();
    private Runnable applySettingsRunnable = new Runnable() { @Override public void run() { applyProfileSettings(); savePreferences(); } };
    
    private Runnable liveUpdater = new Runnable() {
        @Override public void run() {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode && !isProcessing && hasSurface && mCamera != null) {
                boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;
                if (s1_1_free && s1_2_free) {
                    if (afOverlay != null && afOverlay.isPolling()) afOverlay.stopFocus(mCamera);
                    if (tvTopStatus.getVisibility() != View.VISIBLE) {
                        tvTopStatus.setVisibility(View.VISIBLE); llBottomBar.setVisibility(View.VISIBLE); tvBattery.setVisibility(View.VISIBLE);
                        tvMode.setVisibility(View.VISIBLE); tvFocusMode.setVisibility(View.VISIBLE); tvReview.setVisibility(View.VISIBLE);
                        if (focusMeter != null && prefShowFocusMeter) focusMeter.setVisibility(View.VISIBLE);
                    }
                }
                updateMainHUD();
            }
            uiHandler.postDelayed(this, 500); 
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File thumbsDir = new File(Environment.getExternalStorageDirectory(), "DCIM/.thumbnails");
        if (!thumbsDir.exists()) thumbsDir.mkdirs();
        
        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootLayout); 

        alphaWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        alphaConnManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        alphaDirectManager = (DirectManager) getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        alphaServer = new HttpServer(this);

        scanRecipes();
        for(int i=0; i<10; i++) profiles[i] = new RTLProfile();
        loadPreferences();
        buildUI(rootLayout);

        String[] possibleRoots = { Environment.getExternalStorageDirectory().getAbsolutePath(), "/mnt/sdcard", "/storage/sdcard0", "/sdcard" };
        for (String r : possibleRoots) { File f = new File(r + "/DCIM/100MSDCF"); if (f.exists()) { sonyDCIMPath = f.getAbsolutePath(); break; } }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        setupEngines();
        triggerLutPreload();
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean s) { isReady = true; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { isProcessing = true; runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } }); }
            @Override public void onProcessFinished(String r) { isProcessing = false; runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setTextColor(Color.WHITE); updateMainHUD(); } }); }
        });

        mScanner = new SonyFileScanner(sonyDCIMPath, new SonyFileScanner.ScannerCallback() {
            @Override public boolean isReadyToProcess() { return isReady && !isProcessing && profiles[currentSlot].lutIndex != 0; }
            @Override public void onNewPhotoDetected(final String fp) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    File out = new File(Environment.getExternalStorageDirectory(), "GRADED");
                    mProcessor.processJpeg(fp, out.getAbsolutePath(), qualityIndex, profiles[currentSlot]);
                }});
            }
        });
    }

    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this); rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));
        gridLines = new GridLinesView(this); mainUIContainer.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));
        cinemaMattes = new CinemaMatteView(this); mainUIContainer.addView(cinemaMattes, new FrameLayout.LayoutParams(-1, -1));
        tvTopStatus = new TextView(this); tvTopStatus.setTextColor(Color.WHITE); tvTopStatus.setTextSize(20); tvTopStatus.setTypeface(Typeface.DEFAULT_BOLD); tvTopStatus.setGravity(Gravity.CENTER); tvTopStatus.setShadowLayer(4, 0, 0, Color.BLACK); FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); tp.setMargins(0, 15, 0, 0); mainUIContainer.addView(tvTopStatus, tp);
        LinearLayout rb = new LinearLayout(this); rb.setOrientation(LinearLayout.VERTICAL); rb.setGravity(Gravity.RIGHT);
        LinearLayout ba = new LinearLayout(this); ba.setOrientation(LinearLayout.HORIZONTAL); ba.setGravity(Gravity.CENTER_VERTICAL);
        tvBattery = new TextView(this); tvBattery.setTextColor(Color.WHITE); tvBattery.setTextSize(18); tvBattery.setTypeface(Typeface.DEFAULT_BOLD); tvBattery.setPadding(0, 0, 10, 0); ba.addView(tvBattery);
        View bi = new View(this) { @Override protected void onDraw(Canvas c) { drawSonyBattery(c, this); } }; ba.addView(bi, new LinearLayout.LayoutParams(45, 22)); rb.addView(ba);
        tvReview = createSideTextIcon("▶"); tvReview.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { mDialMode = (mDialMode == DIAL_MODE_REVIEW) ? DIAL_MODE_RTL : DIAL_MODE_REVIEW; updateMainHUD(); } }); LinearLayout.LayoutParams rv = new LinearLayout.LayoutParams(-2, -2); rv.setMargins(0, 20, 0, 0); tvReview.setLayoutParams(rv); rb.addView(tvReview);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT); rp.setMargins(0, 20, 30, 0); mainUIContainer.addView(rb, rp);
        LinearLayout lb = new LinearLayout(this); lb.setOrientation(LinearLayout.VERTICAL);
        tvMode = createSideTextIcon("M"); tvMode.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { mDialMode = (mDialMode == DIAL_MODE_PASM) ? DIAL_MODE_RTL : DIAL_MODE_PASM; updateMainHUD(); } }); lb.addView(tvMode);
        tvFocusMode = createSideTextIcon("AF-S"); tvFocusMode.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { mDialMode = (mDialMode == DIAL_MODE_FOCUS) ? DIAL_MODE_RTL : DIAL_MODE_FOCUS; updateMainHUD(); } }); lb.addView(tvFocusMode);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT); lp.setMargins(20, 20, 0, 0); mainUIContainer.addView(lb, lp);
        focusMeter = new AdvancedFocusMeterView(this); FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); fp.setMargins(0, 0, 0, 100); mainUIContainer.addView(focusMeter, fp);
        llBottomBar = new LinearLayout(this); llBottomBar.setOrientation(LinearLayout.HORIZONTAL); llBottomBar.setGravity(Gravity.CENTER);
        tvValShutter = createBottomText(); tvValAperture = createBottomText(); tvValIso = createBottomText(); tvValEv = createBottomText();
        llBottomBar.addView(tvValShutter); llBottomBar.addView(tvValAperture); llBottomBar.addView(tvValIso); llBottomBar.addView(tvValEv);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); bp.setMargins(0, 0, 0, 25); mainUIContainer.addView(llBottomBar, bp);
        afOverlay = new ProReticleView(this); mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));
        menuContainer = new LinearLayout(this); menuContainer.setOrientation(LinearLayout.VERTICAL); menuContainer.setBackgroundColor(Color.argb(250, 15, 15, 15)); menuContainer.setPadding(20, 20, 20, 20); 
        menuHeaderLayout = new LinearLayout(this); menuHeaderLayout.setOrientation(LinearLayout.HORIZONTAL); menuHeaderLayout.setGravity(Gravity.CENTER_VERTICAL); menuHeaderLayout.setPadding(10, 0, 10, 15);
        tvMenuTitle = new TextView(this); tvMenuTitle.setTextSize(22); tvMenuTitle.setTypeface(Typeface.DEFAULT_BOLD); tvMenuTitle.setTextColor(Color.WHITE); menuHeaderLayout.addView(tvMenuTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout pl = new LinearLayout(this); pl.setOrientation(LinearLayout.HORIZONTAL); pl.setGravity(Gravity.RIGHT);
        for(int i=0; i<4; i++) { tvPageNumbers[i] = new TextView(this); tvPageNumbers[i].setText(String.valueOf(i+1)); tvPageNumbers[i].setTextSize(20); tvPageNumbers[i].setTypeface(Typeface.DEFAULT_BOLD); tvPageNumbers[i].setPadding(15, 0, 15, 0); pl.addView(tvPageNumbers[i]); }
        menuHeaderLayout.addView(pl, new LinearLayout.LayoutParams(-2, -2)); menuContainer.addView(menuHeaderLayout);
        View hd = new View(this); hd.setBackgroundColor(Color.GRAY); LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, 2); dp.setMargins(0, 0, 0, 15); menuContainer.addView(hd, dp);
        for (int i = 0; i < 7; i++) { 
            menuRows[i] = new LinearLayout(this); menuRows[i].setOrientation(LinearLayout.HORIZONTAL); menuRows[i].setGravity(Gravity.CENTER_VERTICAL); menuRows[i].setPadding(10, 0, 10, 0); menuContainer.addView(menuRows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            menuLabels[i] = new TextView(this); menuLabels[i].setTextSize(18); menuLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            menuValues[i] = new TextView(this); menuValues[i].setTextSize(18); menuValues[i].setGravity(Gravity.RIGHT);
            menuRows[i].addView(menuLabels[i], new LinearLayout.LayoutParams(0, -2, 1.0f)); menuRows[i].addView(menuValues[i], new LinearLayout.LayoutParams(-2, -2));
            if (i < 6) { View dv = new View(this); dv.setBackgroundColor(Color.DKGRAY); menuContainer.addView(dv, new LinearLayout.LayoutParams(-1, 1)); }
        }
        menuContainer.setVisibility(View.GONE); rootLayout.addView(menuContainer, new FrameLayout.LayoutParams(-1, -1));
        playbackContainer = new FrameLayout(this); playbackContainer.setBackgroundColor(Color.BLACK); playbackContainer.setVisibility(View.GONE);
        playbackImageView = new ImageView(this); playbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER); playbackContainer.addView(playbackImageView, new FrameLayout.LayoutParams(-1, -1));
        tvPlaybackInfo = new TextView(this); tvPlaybackInfo.setTextColor(Color.WHITE); tvPlaybackInfo.setTextSize(18); tvPlaybackInfo.setShadowLayer(3, 0, 0, Color.BLACK); FrameLayout.LayoutParams pbi = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT); pbi.setMargins(0, 30, 30, 0); playbackContainer.addView(tvPlaybackInfo, pbi);
        rootLayout.addView(playbackContainer, new FrameLayout.LayoutParams(-1, -1));
        updateMainHUD(); renderMenu();
    }

    private String formatSign(int val) { return val == 0 ? "0" : (val > 0 ? "+" + val : String.valueOf(val)); }
    private String formatAB(int val) { return val == 0 ? "0" : (val > 0 ? "A" + val : "B" + Math.abs(val)); }
    private String formatGM(int val) { return val == 0 ? "0" : (val > 0 ? "G" + val : "M" + Math.abs(val)); }
    private TextView createBottomText() { TextView tv = new TextView(this); tv.setTextSize(26); tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setShadowLayer(4, 0, 0, Color.BLACK); tv.setPadding(20, 0, 20, 0); return tv; }
    private TextView createSideTextIcon(String t) { TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(Color.WHITE); tv.setTextSize(22); tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tv.setPadding(25, 15, 25, 15); tv.setBackgroundColor(Color.argb(140, 40, 40, 40)); tv.setGravity(Gravity.CENTER); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, 0, 15); tv.setLayoutParams(lp); return tv; }
    private void drawSonyBattery(Canvas c, View v) { Paint p = new Paint(); p.setAntiAlias(true); p.setStrokeWidth(2); p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); c.drawRect(2, 2, v.getWidth() - 8, v.getHeight() - 2, p); p.setStyle(Paint.Style.FILL); c.drawRect(v.getWidth() - 8, v.getHeight()/2 - 4, v.getWidth() - 2, v.getHeight()/2 + 4, p); int bc = (lastBatteryLevel < 15) ? Color.RED : Color.WHITE; p.setColor(bc); int fw = (v.getWidth() - 14); if (lastBatteryLevel > 10) c.drawRect(6, 6, 6 + (fw/3) - 2, v.getHeight() - 6, p); if (lastBatteryLevel > 40) c.drawRect(6 + (fw/3) + 2, 6, 6 + (2*fw/3) - 2, v.getHeight() - 6, p); if (lastBatteryLevel > 70) c.drawRect(6 + (2*fw/3) + 2, 6, v.getWidth() - 12, v.getHeight() - 6, p); }

    private void refreshPlaybackFiles() {
        playbackFiles.clear(); File dir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (dir.exists() && dir.listFiles() != null) { for (File f : dir.listFiles()) { if (f.getName().toUpperCase().endsWith(".JPG")) playbackFiles.add(f); } }
        java.util.Collections.sort(playbackFiles, new java.util.Comparator<File>() { public int compare(File f1, File f2) { return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()); } });
    }

    private void showPlaybackImage(int index) {
        if (playbackFiles.isEmpty()) { tvPlaybackInfo.setText("NO GRADED PHOTOS"); return; }
        if (index < 0) index = 0; if (index >= playbackFiles.size()) index = playbackFiles.size() - 1; playbackIndex = index;
        File imgFile = playbackFiles.get(playbackIndex);
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) { playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null; }
        try {
            ExifInterface ex = new ExifInterface(imgFile.getAbsolutePath());
            String fnum = ex.getAttribute("FNumber"), speed = ex.getAttribute("ExposureTime"), iso = ex.getAttribute("ISOSpeedRatings");
            String spStr = "--s"; if (speed != null) { try { double s = Double.parseDouble(speed); spStr = (s < 1.0) ? "1/" + Math.round(1.0 / s) + "s" : Math.round(s) + "s"; } catch (Exception e) {} }
            tvPlaybackInfo.setText((playbackIndex + 1) + " / " + playbackFiles.size() + "\n" + imgFile.getName() + "\n" + (fnum != null ? "f/" + fnum : "f/--") + " | " + spStr + " | ISO " + (iso != null ? iso : "--"));
            BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true; BitmapFactory.decodeFile(imgFile.getAbsolutePath(), opts);
            int sc = 1; while ((opts.outWidth / sc) > 1200 || (opts.outHeight / sc) > 1200) sc *= 2; opts.inJustDecodeBounds = false; opts.inSampleSize = sc;
            Bitmap raw = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), opts);
            int rot = 0; int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rot = 90; else if (o == ExifInterface.ORIENTATION_ROTATE_180) rot = 180; else if (o == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            Matrix m = new Matrix(); if (rot != 0) m.postRotate(rot); m.postScale(0.8888f, 1.0f);
            currentPlaybackBitmap = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (currentPlaybackBitmap != raw) raw.recycle(); playbackImageView.setImageBitmap(currentPlaybackBitmap);
        } catch (Exception e) { tvPlaybackInfo.setText("DECODE ERROR"); }
    }

    private void exitPlayback() { playbackContainer.setVisibility(View.GONE); mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); isPlaybackMode = false; if (currentPlaybackBitmap != null) { playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null; } }

    private File getLutDir() { File d = new File(Environment.getExternalStorageDirectory(), "LUTS"); if (!d.exists()) d = new File("/storage/sdcard0/LUTS"); return d; }
    
    private void refreshRecipes() {
        String[] saved = new String[10]; for(int i=0; i<10; i++) { if (profiles[i].lutIndex >= 0 && profiles[i].lutIndex < recipePaths.size()) saved[i] = recipePaths.get(profiles[i].lutIndex); else saved[i] = "NONE"; }
        scanRecipes(); for(int i=0; i<10; i++) { int idx = recipePaths.indexOf(saved[i]); profiles[i].lutIndex = (idx != -1) ? idx : 0; }
    }

    private void savePreferences() {
        try {
            File lutDir = getLutDir(); if (!lutDir.exists()) lutDir.mkdirs(); 
            File bf = new File(lutDir, "RTLBAK.TXT"); if (!bf.exists()) bf.createNewFile();
            FileOutputStream fos = new FileOutputStream(bf); StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n").append("slot=").append(currentSlot).append("\n").append("prefs=").append(prefShowFocusMeter).append(",").append(prefShowCinemaMattes).append(",").append(prefShowGridLines).append("\n");
            for(int i=0; i<10; i++) { sb.append(i).append(",").append(recipePaths.get(profiles[i].lutIndex)).append(",").append(profiles[i].opacity).append(",").append(profiles[i].grain).append(",").append(profiles[i].grainSize).append(",").append(profiles[i].rollOff).append(",").append(profiles[i].vignette).append(",").append(profiles[i].whiteBalance).append(",").append(profiles[i].wbShift).append(",").append(profiles[i].dro).append(",").append(profiles[i].wbShiftGM).append(",").append(profiles[i].contrast).append(",").append(profiles[i].saturation).append(",").append(profiles[i].sharpness).append("\n"); }
            fos.write(sb.toString().getBytes()); fos.flush(); fos.getFD().sync(); fos.close();
        } catch (Exception e) {}
    }

    private void loadPreferences() {
        File bf = new File(getLutDir(), "RTLBAK.TXT");
        if (bf.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(bf)); String ln;
                while ((ln = br.readLine()) != null) {
                    if (ln.startsWith("quality=")) qualityIndex = Integer.parseInt(ln.split("=")[1]);
                    else if (ln.startsWith("slot=")) currentSlot = Integer.parseInt(ln.split("=")[1]);
                    else if (ln.startsWith("prefs=")) { String[] p = ln.split("=")[1].split(","); if (p.length >= 3) { prefShowFocusMeter = Boolean.parseBoolean(p[0]); prefShowCinemaMattes = Boolean.parseBoolean(p[1]); prefShowGridLines = Boolean.parseBoolean(p[2]); } }
                    else {
                        String[] pts = ln.split(","); if (pts.length >= 14) {
                            int idx = Integer.parseInt(pts[0]); int fnd = recipePaths.indexOf(pts[1]);
                            profiles[idx].lutIndex = (fnd != -1) ? fnd : 0; profiles[idx].opacity = Integer.parseInt(pts[2]); if (profiles[idx].opacity <= 5) profiles[idx].opacity = 100; profiles[idx].grain = Integer.parseInt(pts[3]); profiles[idx].grainSize = Integer.parseInt(pts[4]); profiles[idx].rollOff = Integer.parseInt(pts[5]); profiles[idx].vignette = Integer.parseInt(pts[6]); profiles[idx].whiteBalance = pts[7]; profiles[idx].wbShift = Integer.parseInt(pts[8]); profiles[idx].dro = pts[9]; profiles[idx].wbShiftGM = Integer.parseInt(pts[10]); profiles[idx].contrast = Integer.parseInt(pts[11]); profiles[idx].saturation = Integer.parseInt(pts[12]); profiles[idx].sharpness = Integer.parseInt(pts[13]);
                        }
                    }
                }
                br.close(); 
            } catch (Exception e) {}
        }
    }

    private void applyProfileSettings() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters(); RTLProfile prof = profiles[currentSlot];
            String twb = "auto"; if ("DAY".equals(prof.whiteBalance)) twb = "daylight"; else if ("SHD".equals(prof.whiteBalance)) twb = "shade"; else if ("CLD".equals(prof.whiteBalance)) twb = "cloudy-daylight"; else if ("INC".equals(prof.whiteBalance)) twb = "incandescent"; else if ("FLR".equals(prof.whiteBalance)) twb = "fluorescent";
            p.setWhiteBalance(twb);
            if (p.get("dro-mode") != null) { if ("OFF".equals(prof.dro)) p.set("dro-mode", "off"); else if ("AUTO".equals(prof.dro)) p.set("dro-mode", "auto"); else if (prof.dro.startsWith("LV")) { p.set("dro-mode", "on"); p.set("dro-level", Integer.parseInt(prof.dro.replace("LV", ""))); } }
            else if (p.get("sony-dro") != null) p.set("sony-dro", prof.dro.toLowerCase());
            p.set("contrast", String.valueOf(prof.contrast)); p.set("saturation", String.valueOf(prof.saturation)); p.set("sharpness", String.valueOf(prof.sharpness));
            p.set("white-balance-shift-mode", (prof.wbShift != 0 || prof.wbShiftGM != 0) ? "true" : "false");
            p.set("white-balance-shift-lb", String.valueOf(prof.wbShift)); p.set("white-balance-shift-cc", String.valueOf(prof.wbShiftGM)); 
            mCamera.setParameters(p);
        } catch (Exception e) {}
    }

    @Override 
    public boolean onKeyDown(int kc, KeyEvent ev) {
        int sc = ev.getScanCode();
        if (sc == ScalarInput.ISV_KEY_S1_1 && ev.getRepeatCount() == 0) {
            mDialMode = DIAL_MODE_RTL; 
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) { tvTopStatus.setVisibility(View.GONE); llBottomBar.setVisibility(View.GONE); tvBattery.setVisibility(View.GONE); tvMode.setVisibility(View.GONE); tvFocusMode.setVisibility(View.GONE); tvReview.setVisibility(View.GONE); if (focusMeter != null) focusMeter.setVisibility(View.GONE); }
            if (afOverlay != null && mCamera != null && !"manual".equals(mCamera.getParameters().getFocusMode())) afOverlay.startFocus(mCamera);
            return super.onKeyDown(kc, ev);
        }
        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        if (isPlaybackMode) { if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { showPlaybackImage(playbackIndex + 1); return true; } if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { showPlaybackImage(playbackIndex - 1); return true; } if (sc == ScalarInput.ISV_KEY_ENTER || sc == ScalarInput.ISV_KEY_MENU || sc == ScalarInput.ISV_KEY_PLAY) { exitPlayback(); return true; } return true; }
        if (sc == ScalarInput.ISV_KEY_MENU) {
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                if (mCamera != null) { Camera.Parameters p = mCamera.getParameters(); savedFocusMode = p.getFocusMode(); if (p.getSupportedFocusModes().contains("manual")) { p.setFocusMode("manual"); mCamera.setParameters(p); } }
                refreshRecipes(); currentPage = 1; menuSelection = 0; menuContainer.setVisibility(View.VISIBLE); mainUIContainer.setVisibility(View.GONE); renderMenu();
            } else {
                if (mCamera != null && savedFocusMode != null) { Camera.Parameters p = mCamera.getParameters(); p.setFocusMode(savedFocusMode); mCamera.setParameters(p); }
                menuContainer.setVisibility(View.GONE); mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                savePreferences(); triggerLutPreload(); updateMainHUD();
            }
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) { if (mDialMode == DIAL_MODE_REVIEW) { isPlaybackMode = true; refreshPlaybackFiles(); playbackContainer.setVisibility(View.VISIBLE); mainUIContainer.setVisibility(View.GONE); showPlaybackImage(0); } else { displayState = (displayState == 0) ? 1 : 0; mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); } }
            else { if (currentPage == 4) { if (menuSelection == 0) { if (isHomeWifiRunning) { stopAlphaOSNetworking(); uiHandler.postDelayed(new Runnable() { public void run() { startAlphaOSHotspot(); } }, 2000); } else startAlphaOSHotspot(); } else if (menuSelection == 1) { if (isHotspotRunning) { stopAlphaOSNetworking(); uiHandler.postDelayed(new Runnable() { public void run() { startAlphaOSHomeWifi(); } }, 2000); } else startAlphaOSHomeWifi(); } else if (menuSelection == 2) stopAlphaOSNetworking(); } }
            return true;
        }
        if (!isProcessing) {
            if (isMenuOpen) { if (sc == ScalarInput.ISV_KEY_UP) { menuSelection--; if (menuSelection < -1) menuSelection = currentItemCount - 1; renderMenu(); return true; } if (sc == ScalarInput.ISV_KEY_DOWN) { menuSelection++; if (menuSelection >= currentItemCount) menuSelection = -1; renderMenu(); return true; } if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { if (menuSelection == -1) { currentPage = (currentPage == 1) ? 4 : currentPage - 1; renderMenu(); } else handleMenuChange(-1); return true; } if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { if (menuSelection == -1) { currentPage = (currentPage == 4) ? 1 : currentPage + 1; renderMenu(); } else handleMenuChange(1); return true; } }
            else { if (sc == ScalarInput.ISV_KEY_LEFT) { cycleMode(-1); return true; } if (sc == ScalarInput.ISV_KEY_RIGHT) { cycleMode(1); return true; } if (sc == ScalarInput.ISV_KEY_UP || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; } if (sc == ScalarInput.ISV_KEY_DOWN || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; } }
        }
        return super.onKeyDown(kc, ev);
    }

    @Override 
    public boolean onKeyUp(int kc, KeyEvent ev) {
        if (ev.getScanCode() == ScalarInput.ISV_KEY_S1_1) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) { tvTopStatus.setVisibility(View.VISIBLE); llBottomBar.setVisibility(View.VISIBLE); tvBattery.setVisibility(View.VISIBLE); tvMode.setVisibility(View.VISIBLE); tvFocusMode.setVisibility(View.VISIBLE); if (focusMeter != null && prefShowFocusMeter) focusMeter.setVisibility(View.VISIBLE); tvReview.setVisibility(View.VISIBLE); }
            if (afOverlay != null && mCamera != null && !"manual".equals(mCamera.getParameters().getFocusMode())) afOverlay.stopFocus(mCamera);
        }
        return super.onKeyUp(kc, ev);
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = profiles[currentSlot];
        try {
            if (currentPage == 1) { switch(menuSelection) { case 0: currentSlot = (currentSlot + dir + 10) % 10; break; case 1: p.lutIndex = (p.lutIndex + dir + recipePaths.size()) % recipePaths.size(); break; case 2: p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); break; case 3: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break; case 4: p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); break; case 5: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break; case 6: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break; } }
            else if (currentPage == 2) { switch(menuSelection) { case 0: int wbi = java.util.Arrays.asList(wbLabels).indexOf(p.whiteBalance); p.whiteBalance = wbLabels[(wbi + dir + wbLabels.length) % wbLabels.length]; break; case 1: p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dir)); break; case 2: p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dir)); break; case 3: int dri = java.util.Arrays.asList(droLabels).indexOf(p.dro); p.dro = droLabels[(dri + dir + droLabels.length) % droLabels.length]; break; case 4: p.contrast = Math.max(-3, Math.min(3, p.contrast + dir)); break; case 5: p.saturation = Math.max(-3, Math.min(3, p.saturation + dir)); break; case 6: p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir)); break; } }
            else if (currentPage == 3) { switch(menuSelection) { case 0: qualityIndex = (qualityIndex + dir + 3) % 3; break; case 1: Camera.Parameters cp = mCamera.getParameters(); List<String> scn = cp.getSupportedSceneModes(); int idx = scn.indexOf(cp.getSceneMode()); cp.setSceneMode(scn.get((idx + dir + scn.size()) % scn.size())); mCamera.setParameters(cp); break; case 2: prefShowFocusMeter = !prefShowFocusMeter; break; case 3: prefShowCinemaMattes = !prefShowCinemaMattes; break; case 4: prefShowGridLines = !prefShowGridLines; break; } }
        } catch (Exception e) {}
        renderMenu(); uiHandler.removeCallbacks(applySettingsRunnable); uiHandler.postDelayed(applySettingsRunnable, 400);
    }

    private void renderMenu() {
        if (currentPage == 1) tvMenuTitle.setText("RTL (Base)"); else if (currentPage == 2) tvMenuTitle.setText("RTL (Color)"); else if (currentPage == 3) tvMenuTitle.setText("Global"); else tvMenuTitle.setText("Connect");
        for(int i=0; i<4; i++) { tvPageNumbers[i].setTextColor(currentPage == i+1 ? Color.rgb(230, 50, 15) : Color.WHITE); }
        for(int i=0; i<7; i++) menuRows[i].setVisibility(View.GONE); RTLProfile p = profiles[currentSlot];
        if (currentPage == 1) { currentItemCount = 7; String[] rl = {"Slot", "LUT", "Opacity", "Grain", "G-Size", "Roll-Off", "Vignette"}; String[] rv = {String.valueOf(currentSlot + 1), recipeNames.get(p.lutIndex), p.opacity + "%", intensityLabels[p.grain], grainSizeLabels[p.grainSize], intensityLabels[p.rollOff], intensityLabels[p.vignette]}; for(int i=0; i<7; i++) { menuLabels[i].setText(rl[i]); menuValues[i].setText(rv[i]); menuRows[i].setVisibility(View.VISIBLE); } } 
        else if (currentPage == 2) { currentItemCount = 7; String[] cl = {"WB", "WB (A-B)", "WB (G-M)", "DRO", "Contrast", "Saturation", "Sharpness"}; String[] cv = {p.whiteBalance, formatAB(p.wbShift), formatGM(p.wbShiftGM), p.dro, formatSign(p.contrast), formatSign(p.saturation), formatSign(p.sharpness)}; for(int i=0; i<7; i++) { menuLabels[i].setText(cl[i]); menuValues[i].setText(cv[i]); menuRows[i].setVisibility(View.VISIBLE); } }
        else if (currentPage == 3) { currentItemCount = 5; String[] ql = {"PROXY", "HIGH", "ULTRA"}; String[] gl = {"Quality", "Scene", "MF Meter", "Matte", "Grid"}; String[] gv = {ql[qualityIndex], "M", prefShowFocusMeter ? "ON" : "OFF", prefShowCinemaMattes ? "ON" : "OFF", prefShowGridLines ? "ON" : "OFF"}; for(int i=0; i<5; i++) { menuLabels[i].setText(gl[i]); menuValues[i].setText(gv[i]); menuRows[i].setVisibility(View.VISIBLE); } }
        else if (currentPage == 4) { currentItemCount = 3; String[] nl = {"Hotspot", "Wi-Fi", "Off"}; String[] nv = {connStatusHotspot, connStatusWifi, ""}; for(int i=0; i<3; i++) { menuLabels[i].setText(nl[i]); menuValues[i].setText(nv[i]); menuRows[i].setVisibility(View.VISIBLE); } }
        for (int i = 0; i < currentItemCount; i++) menuRows[i].setBackgroundColor(i == menuSelection ? Color.rgb(230, 50, 15) : Color.TRANSPARENT);
    }

    private void cycleMode(int dir) { mDialMode = (mDialMode + dir + 8) % 8; updateMainHUD(); }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters(); CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            if (mDialMode == DIAL_MODE_RTL) { currentSlot = (currentSlot + d + 10) % 10; applyProfileSettings(); triggerLutPreload(); savePreferences(); }
            else if (mDialMode == DIAL_MODE_SHUTTER) { if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); }
            else if (mDialMode == DIAL_MODE_APERTURE) { if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); }
            else if (mDialMode == DIAL_MODE_ISO) { List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities(); if (isos != null) { int i = isos.indexOf(pm.getISOSensitivity()); pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, i + d)))); mCamera.setParameters(p); } }
            else if (mDialMode == DIAL_MODE_EXPOSURE) { p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); mCamera.setParameters(p); }
            else if (mDialMode == DIAL_MODE_PASM) {
                // BRUTE FORCE PASM FILTER: Only allow P, A, S, M. Absolutely NO "Auto".
                String[] f = {"program-auto", "aperture-priority", "shutter-priority", "manual-exposure"};
                int curIdx = 0; String curM = p.getSceneMode(); for(int i=0; i<4; i++) { if(f[i].equals(curM)) curIdx = i; }
                p.setSceneMode(f[(curIdx + d + 4) % 4]); mCamera.setParameters(p);
            }
            else if (mDialMode == DIAL_MODE_FOCUS) { List<String> fms = p.getSupportedFocusModes(); int i = fms.indexOf(p.getFocusMode()); p.setFocusMode(fms.get((i + d + fms.size()) % fms.size())); mCamera.setParameters(p); }
            updateMainHUD();
        } catch (Exception e) {}
        uiHandler.removeCallbacks(liveUpdater); uiHandler.postDelayed(liveUpdater, 1000); 
    }

    private void updateMainHUD() {
        if(mCamera == null) return;
        RTLProfile pf = profiles[currentSlot]; String dn = recipeNames.get(pf.lutIndex); dn = dn.length() > 15 ? dn.substring(0, 12) + "..." : dn;
        if (!isProcessing) { tvTopStatus.setText("RTL " + (currentSlot + 1) + " [" + dn + "]\n" + (isReady ? "READY" : "LOADING...")); tvTopStatus.setTextColor(mDialMode == DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE); }
        tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
        gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);
        try {
            Camera.Parameters p = mCamera.getParameters(); CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            tvMode.setBackgroundColor(mDialMode == DIAL_MODE_PASM ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
            String sm = p.getSceneMode(); if (sm.equals("manual-exposure")) tvMode.setText("M"); else if (sm.equals("aperture-priority")) tvMode.setText("A"); else if (sm.equals("shutter-priority")) tvMode.setText("S"); else if (sm.equals("program-auto")) tvMode.setText("P"); else tvMode.setText("AUTO");
            tvFocusMode.setBackgroundColor(mDialMode == DIAL_MODE_FOCUS ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
            String fm = p.getFocusMode(); if (fm.equals("manual")) tvFocusMode.setText("MF"); else if (fm.equals("auto")) tvFocusMode.setText("AF-S"); else if (fm.equals("continuous-picture")) tvFocusMode.setText("AF-C"); else tvFocusMode.setText("AF");
            lastKnownAperture = pm.getAperture() / 100.0f; if ("manual".equals(fm)) focusMeter.update(lastKnownFocusRatio, lastKnownAperture, true); else focusMeter.update(0, 0, false);
            Pair<Integer, Integer> ss = pm.getShutterSpeed();
            tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\""); tvValShutter.setTextColor(mDialMode == DIAL_MODE_SHUTTER ? Color.rgb(230, 50, 15) : Color.WHITE);
            tvValAperture.setText(String.format("f%.1f", lastKnownAperture)); tvValAperture.setTextColor(mDialMode == DIAL_MODE_APERTURE ? Color.rgb(230, 50, 15) : Color.WHITE);
            tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity()); tvValIso.setTextColor(mDialMode == DIAL_MODE_ISO ? Color.rgb(230, 50, 15) : Color.WHITE);
            tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep())); tvValEv.setTextColor(mDialMode == DIAL_MODE_EXPOSURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        } catch (Exception e) {}
    }

    private void scanRecipes() { 
        recipePaths.clear(); recipeNames.clear(); recipePaths.add("NONE"); recipeNames.add("NONE");
        File ld = getLutDir(); if (ld.exists() && ld.listFiles() != null) { for (File f : ld.listFiles()) { String u = f.getName().toUpperCase(); if (f.length() > 10240 && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) { recipePaths.add(f.getAbsolutePath()); recipeNames.add(u.replace(".CUB", "").replace(".CUBE", "")); } } }
    }

    private void triggerLutPreload() { if(mProcessor != null) mProcessor.triggerLutPreload(recipePaths.get(profiles[currentSlot].lutIndex), recipeNames.get(profiles[currentSlot].lutIndex)); }

    private void openCamera() {
        if (mCameraEx == null && hasSurface) {
            try { 
                mCameraEx = CameraEx.open(0, null); mCamera = mCameraEx.getNormalCamera(); mCameraEx.startDirectShutter(); 
                if (origSceneMode == null && mCamera != null) {
                    Camera.Parameters p = mCamera.getParameters();
                    origSceneMode = p.getSceneMode(); origFocusMode = p.getFocusMode(); origWhiteBalance = p.getWhiteBalance(); origDroMode = p.get("dro-mode"); origDroLevel = p.get("dro-level"); origSonyDro = p.get("sony-dro"); origContrast = p.get("contrast"); origSaturation = p.get("saturation"); origSharpness = p.get("sharpness"); origWbShiftMode = p.get("white-balance-shift-mode"); origWbShiftLb = p.get("white-balance-shift-lb"); origWbShiftCc = p.get("white-balance-shift-cc"); origExpComp = String.valueOf(p.getExposureCompensation());
                }
                mCamera.setPreviewDisplay(mSurfaceView.getHolder()); mCamera.startPreview(); applyProfileSettings(); updateMainHUD();
            } catch (Exception e) {} 
        }
    }

    private void closeCamera() {
        if (mCamera != null && origSceneMode != null) {
            try {
                Camera.Parameters p = mCamera.getParameters();
                p.setSceneMode(origSceneMode); p.setFocusMode(origFocusMode); p.setWhiteBalance(origWhiteBalance);
                if (origDroMode != null) p.set("dro-mode", origDroMode); if (origDroLevel != null) p.set("dro-level", origDroLevel); if (origSonyDro != null) p.set("sony-dro", origSonyDro);
                p.set("contrast", origContrast); p.set("saturation", origSaturation); p.set("sharpness", origSharpness);
                p.set("white-balance-shift-mode", origWbShiftMode); p.set("white-balance-shift-lb", origWbShiftLb); p.set("white-balance-shift-cc", origWbShiftCc); p.setExposureCompensation(Integer.parseInt(origExpComp));
                mCamera.setParameters(p);
            } catch (Exception e) {}
        }
        if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; mCamera = null; }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; openCamera(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { hasSurface = false; closeCamera(); }
    @Override protected void onResume() { super.onResume(); openCamera(); registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); if (mScanner != null) mScanner.start(); uiHandler.post(liveUpdater); }
    @Override protected void onPause() { super.onPause(); closeCamera(); try { unregisterReceiver(batteryReceiver); } catch (Exception e) {} if (mScanner != null) mScanner.stop(); uiHandler.removeCallbacks(liveUpdater); stopAlphaOSNetworking(); savePreferences(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}

    private class GridLinesView extends View { private Paint p; public GridLinesView(Context c) { super(c); p = new Paint(); p.setColor(Color.argb(120, 255, 255, 255)); p.setStrokeWidth(2); } @Override protected void onDraw(Canvas c) { super.onDraw(c); int w = getWidth(), h = getHeight(), iw = w, ih = (int) (w * (2.0f / 3.0f)); if (ih > h) { ih = h; iw = (int) (h * (3.0f / 2.0f)); } int ox = (w - iw) / 2, oy = (h - ih) / 2; c.drawLine(ox + iw / 3f, oy, ox + iw / 3f, oy + ih, p); c.drawLine(ox + (iw * 2f) / 3f, oy, ox + (iw * 2f) / 3f, oy + ih, p); c.drawLine(ox, oy + ih / 3f, ox + iw, oy + ih / 3f, p); c.drawLine(ox, oy + (ih * 2f) / 3f, ox + iw, oy + (ih * 2f) / 3f, p); } }
    private class CinemaMatteView extends View { private Paint p; public CinemaMatteView(Context c) { super(c); p = new Paint(); p.setColor(Color.BLACK); p.setStyle(Paint.Style.FILL); } @Override protected void onDraw(Canvas c) { super.onDraw(c); int w = getWidth(), h = getHeight(), iw = w, ih = (int) (w * (2.0f / 3.0f)); if (ih > h) { ih = h; iw = (int) (h * (3.0f / 2.0f)); } int th = (int) (iw / 2.35f), tb = (h - th) / 2, bt = (h + th) / 2; c.drawRect(0, 0, w, tb, p); c.drawRect(0, bt, w, h, p); } }
    private class AdvancedFocusMeterView extends View { private Paint tp, np, dp, txp; private float r = 0.5f, a = 2.8f; private boolean active = false; public AdvancedFocusMeterView(Context c) { super(c); tp = new Paint(); tp.setColor(Color.argb(150, 100, 100, 100)); tp.setStrokeWidth(4); dp = new Paint(); dp.setColor(Color.rgb(230, 50, 15)); dp.setStrokeWidth(12); dp.setStrokeCap(Paint.Cap.ROUND); np = new Paint(); np.setColor(Color.WHITE); np.setStrokeWidth(6); txp = new Paint(); txp.setColor(Color.WHITE); txp.setTextSize(18); txp.setTypeface(Typeface.DEFAULT_BOLD); txp.setTextAlign(Paint.Align.CENTER); } public void update(float cur, float f, boolean ac) { this.r = cur; this.a = f; this.active = ac; invalidate(); } @Override protected void onDraw(Canvas c) { if (!active) return; int w = getWidth(), h = getHeight(), pad = 50, tw = w - (pad * 2), y = h / 2 + 10; c.drawLine(pad, y, w - pad, y, tp); float nx = pad + (tw * r), af = a / 22.0f, ds = (tw * 0.015f) + (tw * 0.35f * af * r * r); c.drawLine(nx - (ds * 0.35f), y, nx + (ds * 0.65f), y, dp); c.drawLine(nx, y - 18, nx, y + 18, np); Path path = new Path(); path.moveTo(nx, y - 24); path.lineTo(nx - 8, y - 36); path.lineTo(nx + 8, y - 36); path.close(); c.drawPath(path, np); } }
    private class ProReticleView extends View { private Paint p; private int st = 0; private boolean pol = false; public ProReticleView(Context c) { super(c); p = new Paint(); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6); p.setAntiAlias(true); } public boolean isPolling() { return pol; } public void startFocus(Camera cam) { if (cam == null) return; st = 1; cam.autoFocus(new Camera.AutoFocusCallback() { @Override public void onAutoFocus(boolean s, Camera c) { st = s ? 2 : 3; invalidate(); } }); pol = true; invalidate(); } public void stopFocus(Camera cam) { pol = false; st = 0; invalidate(); if (cam != null) cam.cancelAutoFocus(); } @Override protected void onDraw(Canvas c) { if (!pol) return; switch(st) { case 1: p.setColor(Color.YELLOW); break; case 2: p.setColor(Color.GREEN); break; case 3: p.setColor(Color.RED); break; default: p.setColor(Color.WHITE); } int cx = getWidth() / 2, cy = getHeight() / 2, s = 60, b = 15; c.drawLine(cx-s, cy-s, cx-s+b, cy-s, p); c.drawLine(cx-s, cy-s, cx-s, cy-s+b, p); c.drawLine(cx+s, cy-s, cx+s-b, cy-s, p); c.drawLine(cx+s, cy-s, cx+s, cy-s+b, p); c.drawLine(cx-s, cy+s, cx-s+b, cy+s, p); c.drawLine(cx-s, cy+s, cx-s, cy+s-b, p); c.drawLine(cx+s, cy+s, cx+s-b, cy+s, p); c.drawLine(cx+s, cy+s, cx+s, cy+s-b, p); postInvalidateDelayed(50); } }

    private void setAutoPowerOffMode(boolean enable) { Intent i = new Intent(); i.setAction("com.android.server.DAConnectionManagerService.apo"); i.putExtra("apo_info", enable ? "APO/NORMAL" : "APO/NO"); sendBroadcast(i); }
    private void updateConnectionStatus(final String t, final String s) { runOnUiThread(new Runnable() { @Override public void run() { if ("HOTSPOT".equals(t)) connStatusHotspot = s; else connStatusWifi = s; if (isMenuOpen && currentPage == 4) renderMenu(); } }); }
    private void startAlphaOSHomeWifi() { isHomeWifiRunning = true; updateConnectionStatus("WIFI", "Connecting..."); alphaWifiReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { NetworkInfo ni = alphaConnManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI); if (ni != null && ni.isConnected()) { WifiInfo wi = alphaWifiManager.getConnectionInfo(); int ip = wi.getIpAddress(); if (ip != 0) { updateConnectionStatus("WIFI", "http://" + String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff)) + ":8080"); if (!alphaServer.isAlive()) try { alphaServer.start(); } catch (Exception e) {} setAutoPowerOffMode(false); } } } }; registerReceiver(alphaWifiReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)); alphaWifiManager.setWifiEnabled(true); }
    private void startAlphaOSHotspot() { isHotspotRunning = true; updateConnectionStatus("HOTSPOT", "Starting..."); alphaDirectStateReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { if (i.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, 0) == DirectManager.DIRECT_STATE_ENABLED) { List<DirectConfiguration> cf = alphaDirectManager.getConfigurations(); if (cf != null && !cf.isEmpty()) alphaDirectManager.startGo(cf.get(cf.size() - 1).getNetworkId()); } } }; alphaGroupCreateSuccessReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { updateConnectionStatus("HOTSPOT", "http://192.168.122.1:8080"); if (!alphaServer.isAlive()) try { alphaServer.start(); } catch (Exception e) {} setAutoPowerOffMode(false); } }; registerReceiver(alphaDirectStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION)); registerReceiver(alphaGroupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION)); alphaDirectManager.setDirectEnabled(true); }
    private void stopAlphaOSNetworking() { if (alphaServer.isAlive()) alphaServer.stop(); if (isHomeWifiRunning) { unregisterReceiver(alphaWifiReceiver); isHomeWifiRunning = false; } if (isHotspotRunning) { unregisterReceiver(alphaDirectStateReceiver); unregisterReceiver(alphaGroupCreateSuccessReceiver); isHotspotRunning = false; } updateConnectionStatus("WIFI", "Press ENTER to Start"); updateConnectionStatus("HOTSPOT", "Press ENTER to Start"); setAutoPowerOffMode(true); }
}