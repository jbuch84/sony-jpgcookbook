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
    private boolean isMenuEditing = false; // NEW: Tracks Tabbed Menu State
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
    
    private BroadcastReceiver alphaWifiReceiver;
    private BroadcastReceiver alphaDirectStateReceiver;
    private BroadcastReceiver alphaGroupCreateSuccessReceiver;
    
    private boolean isHomeWifiRunning = false;
    private boolean isHotspotRunning = false;

    public static final int DIAL_MODE_SHUTTER = 0;
    public static final int DIAL_MODE_APERTURE = 1;
    public static final int DIAL_MODE_ISO = 2;
    public static final int DIAL_MODE_EXPOSURE = 3;
    public static final int DIAL_MODE_REVIEW = 4;
    public static final int DIAL_MODE_RTL = 5;
    public static final int DIAL_MODE_PASM = 6;
    public static final int DIAL_MODE_FOCUS = 7;
    private int mDialMode = DIAL_MODE_RTL;

    private float lastKnownFocusRatio = 0.5f;
    private float lastKnownAperture = 2.8f;
    private boolean cachedIsManualFocus = false;

    private int lastBatteryLevel = 100;
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                lastBatteryLevel = (level * 100) / scale;
                if (tvBattery != null) tvBattery.setText(lastBatteryLevel + "%");
            }
        }
    };

    private Handler uiHandler = new Handler();
    
    private Runnable applySettingsRunnable = new Runnable() {
        @Override
        public void run() {
            applyProfileSettings();
        }
    };
    
    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode && !isProcessing && hasSurface && mCamera != null) {
                boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;
                
                if (s1_1_free && s1_2_free) {
                    if (afOverlay != null && afOverlay.isPolling()) {
                        afOverlay.stopFocus(mCamera);
                        // BATTERY FIX: Only trigger HUD update when returning from a Focus search, nowhere else.
                        updateMainHUD();
                    }
                    if (tvTopStatus.getVisibility() != View.VISIBLE) {
                        setHUDVisibility(View.VISIBLE);
                    }
                }
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
        for (String r : possibleRoots) {
            File f = new File(r + "/DCIM/100MSDCF");
            if (f.exists()) {
                sonyDCIMPath = f.getAbsolutePath();
                break;
            }
        }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        setupEngines();
        triggerLutPreload();
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { 
                isProcessing = true; 
                runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } }); 
            }
            @Override public void onProcessFinished(String result) { 
                isProcessing = false; 
                runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setTextColor(Color.WHITE); updateMainHUD(); } }); 
            }
        });

        mScanner = new SonyFileScanner(sonyDCIMPath, new SonyFileScanner.ScannerCallback() {
            @Override public boolean isReadyToProcess() { return isReady && !isProcessing && profiles[currentSlot].lutIndex != 0; }
            @Override public void onNewPhotoDetected(final String filePath) {
                processWhenFileReady(filePath);
            }
        });
    }

    private void processWhenFileReady(final String path) {
        isProcessing = true; 
        runOnUiThread(new Runnable() { 
            public void run() { 
                tvTopStatus.setText("SAVING TO SD..."); 
                tvTopStatus.setTextColor(Color.YELLOW); 
                updateMainHUD(); 
            } 
        });
        
        final File f = new File(path);
        final long[] lastSize = {-1};
        final int[] retries = {0};
        
        Runnable checker = new Runnable() {
            @Override
            public void run() {
                long currentSize = f.length();
                if (currentSize > 0 && currentSize == lastSize[0]) {
                    File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                    mProcessor.processJpeg(path, outDir.getAbsolutePath(), qualityIndex, profiles[currentSlot]);
                } else if (retries[0] < 30) { 
                    lastSize[0] = currentSize;
                    retries[0]++;
                    uiHandler.postDelayed(this, 500);
                } else {
                    isProcessing = false;
                    updateMainHUD();
                }
            }
        };
        uiHandler.postDelayed(checker, 500);
    }

    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this);
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));

        gridLines = new GridLinesView(this);
        mainUIContainer.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));

        cinemaMattes = new CinemaMatteView(this);
        mainUIContainer.addView(cinemaMattes, new FrameLayout.LayoutParams(-1, -1));

        tvTopStatus = new TextView(this);
        tvTopStatus.setTextColor(Color.WHITE);
        tvTopStatus.setTextSize(20);
        tvTopStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvTopStatus.setGravity(Gravity.CENTER);
        tvTopStatus.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topParams.setMargins(0, 15, 0, 0);
        mainUIContainer.addView(tvTopStatus, topParams);

        LinearLayout rightBar = new LinearLayout(this);
        rightBar.setOrientation(LinearLayout.VERTICAL);
        rightBar.setGravity(Gravity.RIGHT);
        
        LinearLayout batteryArea = new LinearLayout(this);
        batteryArea.setOrientation(LinearLayout.HORIZONTAL);
        batteryArea.setGravity(Gravity.CENTER_VERTICAL);
        
        tvBattery = new TextView(this);
        tvBattery.setTextColor(Color.WHITE);
        tvBattery.setTextSize(18);
        tvBattery.setTypeface(Typeface.DEFAULT_BOLD);
        tvBattery.setPadding(0, 0, 10, 0);
        batteryArea.addView(tvBattery);

        View batteryIcon = new View(this) {
            @Override protected void onDraw(Canvas canvas) { drawSonyBattery(canvas, this); }
        };
        batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(45, 22));
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
        FrameLayout.LayoutParams fmParams = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fmParams.setMargins(0, 0, 0, 100); 
        mainUIContainer.addView(focusMeter, fmParams);

        llBottomBar = new LinearLayout(this);
        llBottomBar.setOrientation(LinearLayout.HORIZONTAL);
        llBottomBar.setGravity(Gravity.CENTER);
        
        tvValShutter = createBottomText();
        tvValAperture = createBottomText();
        tvValIso = createBottomText();
        tvValEv = createBottomText();

        llBottomBar.addView(tvValShutter);
        llBottomBar.addView(tvValAperture);
        llBottomBar.addView(tvValIso);
        llBottomBar.addView(tvValEv);

        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        botParams.setMargins(0, 0, 0, 25);
        mainUIContainer.addView(llBottomBar, botParams);

        afOverlay = new ProReticleView(this);
        mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));

        menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setBackgroundColor(Color.argb(250, 15, 15, 15)); 
        menuContainer.setPadding(20, 20, 20, 20); 
        
        menuHeaderLayout = new LinearLayout(this);
        menuHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        menuHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        menuHeaderLayout.setPadding(10, 0, 10, 15);

        tvMenuTitle = new TextView(this);
        tvMenuTitle.setTextSize(22); 
        tvMenuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvMenuTitle.setTextColor(Color.WHITE);
        menuHeaderLayout.addView(tvMenuTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

        LinearLayout pagesLayout = new LinearLayout(this);
        pagesLayout.setOrientation(LinearLayout.HORIZONTAL);
        pagesLayout.setGravity(Gravity.RIGHT);
        for(int i=0; i<4; i++) {
            tvPageNumbers[i] = new TextView(this);
            tvPageNumbers[i].setText(String.valueOf(i+1));
            tvPageNumbers[i].setTextSize(20); 
            tvPageNumbers[i].setTypeface(Typeface.DEFAULT_BOLD);
            tvPageNumbers[i].setPadding(15, 0, 15, 0);
            pagesLayout.addView(tvPageNumbers[i]);
        }
        menuHeaderLayout.addView(pagesLayout, new LinearLayout.LayoutParams(-2, -2));
        menuContainer.addView(menuHeaderLayout);

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, 2);
        divParams.setMargins(0, 0, 0, 15);
        menuContainer.addView(headerDivider, divParams);

        for (int i = 0; i < 7; i++) { 
            menuRows[i] = new LinearLayout(this);
            menuRows[i].setOrientation(LinearLayout.HORIZONTAL);
            menuRows[i].setGravity(Gravity.CENTER_VERTICAL);
            menuRows[i].setPadding(10, 0, 10, 0);
            
            menuContainer.addView(menuRows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            
            menuLabels[i] = new TextView(this); 
            menuLabels[i].setTextSize(18); 
            menuLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            
            menuValues[i] = new TextView(this); 
            menuValues[i].setTextSize(18); 
            menuValues[i].setGravity(Gravity.RIGHT);
            
            menuRows[i].addView(menuLabels[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
            menuRows[i].addView(menuValues[i], new LinearLayout.LayoutParams(-2, -2));

            if (i < 6) {
                View divider = new View(this); divider.setBackgroundColor(Color.DKGRAY);
                menuContainer.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            }
        }
        
        menuContainer.setVisibility(View.GONE);
        rootLayout.addView(menuContainer, new FrameLayout.LayoutParams(-1, -1));

        playbackContainer = new FrameLayout(this);
        playbackContainer.setBackgroundColor(Color.BLACK);
        playbackContainer.setVisibility(View.GONE);
        
        playbackImageView = new ImageView(this);
        playbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playbackContainer.addView(playbackImageView, new FrameLayout.LayoutParams(-1, -1));
        
        tvPlaybackInfo = new TextView(this);
        tvPlaybackInfo.setTextColor(Color.WHITE);
        tvPlaybackInfo.setTextSize(18);
        tvPlaybackInfo.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams pbInfoParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        pbInfoParams.setMargins(0, 30, 30, 0);
        playbackContainer.addView(tvPlaybackInfo, pbInfoParams);
        
        rootLayout.addView(playbackContainer, new FrameLayout.LayoutParams(-1, -1));

        updateMainHUD();
    }

    private String formatSign(int val) {
        if (val == 0) return "0";
        return val > 0 ? "+" + val : String.valueOf(val);
    }
    
    private String formatAB(int val) {
        if (val == 0) return "0";
        return val > 0 ? "A" + val : "B" + Math.abs(val);
    }
    
    private String formatGM(int val) {
        if (val == 0) return "0";
        return val > 0 ? "G" + val : "M" + Math.abs(val);
    }

    private TextView createBottomText() {
        TextView tv = new TextView(this);
        tv.setTextSize(26);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setShadowLayer(4, 0, 0, Color.BLACK);
        tv.setPadding(20, 0, 20, 0); 
        return tv;
    }

    private TextView createSideTextIcon(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); 
        tv.setTextColor(Color.WHITE); 
        tv.setTextSize(22); 
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); 
        tv.setPadding(25, 15, 25, 15); 
        tv.setBackgroundColor(Color.argb(140, 40, 40, 40));
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, 0, 15);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void drawSonyBattery(Canvas canvas, View v) {
        Paint p = new Paint(); p.setAntiAlias(true); p.setStrokeWidth(2);
        p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE);
        
        canvas.drawRect(2, 2, v.getWidth() - 8, v.getHeight() - 2, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(v.getWidth() - 8, v.getHeight()/2 - 4, v.getWidth() - 2, v.getHeight()/2 + 4, p);
        
        int barColor = (lastBatteryLevel < 15) ? Color.RED : Color.WHITE;
        p.setColor(barColor);
        int fillW = (v.getWidth() - 14);
        if (lastBatteryLevel > 10) canvas.drawRect(6, 6, 6 + (fillW/3) - 2, v.getHeight() - 6, p);
        if (lastBatteryLevel > 40) canvas.drawRect(6 + (fillW/3) + 2, 6, 6 + (2*fillW/3) - 2, v.getHeight() - 6, p);
        if (lastBatteryLevel > 70) canvas.drawRect(6 + (2*fillW/3) + 2, 6, v.getWidth() - 12, v.getHeight() - 6, p);
    }

    private void refreshPlaybackFiles() {
        playbackFiles.clear();
        File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (outDir.exists() && outDir.listFiles() != null) {
            for (File f : outDir.listFiles()) {
                if (f.getName().toUpperCase().endsWith(".JPG")) playbackFiles.add(f);
            }
        }
        java.util.Collections.sort(playbackFiles, new java.util.Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
    }

    private void showPlaybackImage(int index) {
        if (playbackFiles.isEmpty()) { tvPlaybackInfo.setText("NO GRADED PHOTOS"); return; }
        
        if (index < 0) index = playbackFiles.size() - 1;
        if (index >= playbackFiles.size()) index = 0;
        playbackIndex = index;
        File imgFile = playbackFiles.get(playbackIndex);
        
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null;
        }
        System.gc();
        
        try {
            if (imgFile.length() == 0) {
                tvPlaybackInfo.setText((index + 1) + " / " + playbackFiles.size() + "\n[ERROR: 0-BYTE FILE]");
                return;
            }
            ExifInterface exif = new ExifInterface(imgFile.getAbsolutePath());
            String fnum = exif.getAttribute("FNumber");
            String speed = exif.getAttribute("ExposureTime");
            String iso = exif.getAttribute("ISOSpeedRatings");
            
            String speedStr = "--s";
            if (speed != null) {
                try {
                    double s = Double.parseDouble(speed);
                    if (s < 1.0) speedStr = "1/" + Math.round(1.0 / s) + "s";
                    else speedStr = Math.round(s) + "s";
                } catch (Exception e) {}
            }
            
            String apStr = fnum != null ? "f/" + fnum : "f/--";
            String isoStr = iso != null ? "ISO " + iso : "ISO --";

            String metaText = (playbackIndex + 1) + " / " + playbackFiles.size() + "\n" + imgFile.getName() + "\n" + apStr + " | " + speedStr + " | " + isoStr;
            tvPlaybackInfo.setText(metaText);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; 
            BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            
            int scale = 1; 
            while ((options.outWidth / scale) > 1200 || (options.outHeight / scale) > 1200) { scale *= 2; }
            
            options.inJustDecodeBounds = false; options.inSampleSize = scale; options.inPreferQualityOverSpeed = true; 
            Bitmap rawBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            if (rawBitmap == null) return;
            
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationAngle = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;
            
            Matrix matrix = new Matrix(); 
            if (rotationAngle != 0) matrix.postRotate(rotationAngle);
            matrix.postScale(0.8888f, 1.0f); 

            currentPlaybackBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), matrix, true); 
            if (currentPlaybackBitmap != rawBitmap) rawBitmap.recycle();
            
            playbackImageView.setImageBitmap(currentPlaybackBitmap);
        } catch (Exception e) { tvPlaybackInfo.setText("DECODE ERROR"); }
    }

    private void exitPlayback() {
        playbackContainer.setVisibility(View.GONE); mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        isPlaybackMode = false;
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null;
        }
        System.gc();
    }

    private File getLutDir() {
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (!lutDir.exists()) lutDir = new File("/mnt/sdcard/LUTS");
        return lutDir;
    }
    
    private void refreshRecipes() {
        String[] savedPaths = new String[10];
        for(int i=0; i<10; i++) {
            if (profiles[i].lutIndex >= 0 && profiles[i].lutIndex < recipePaths.size()) {
                savedPaths[i] = recipePaths.get(profiles[i].lutIndex);
            } else {
                savedPaths[i] = "NONE";
            }
        }
        
        scanRecipes();
        
        for(int i=0; i<10; i++) {
            int idx = recipePaths.indexOf(savedPaths[i]);
            profiles[i].lutIndex = (idx != -1) ? idx : 0;
        }
    }

    private void savePreferences() {
        try {
            File lutDir = getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            if (!backupFile.exists()) backupFile.createNewFile();
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            sb.append("prefs=").append(prefShowFocusMeter).append(",").append(prefShowCinemaMattes).append(",").append(prefShowGridLines).append("\n");
            for(int i=0; i<10; i++) {
                sb.append(i).append(",").append(recipePaths.get(profiles[i].lutIndex)).append(",")
                  .append(profiles[i].opacity).append(",").append(profiles[i].grain).append(",")
                  .append(profiles[i].grainSize).append(",").append(profiles[i].rollOff).append(",")
                  .append(profiles[i].vignette).append(",")
                  .append(profiles[i].whiteBalance).append(",")
                  .append(profiles[i].wbShift).append(",")
                  .append(profiles[i].dro).append(",")
                  .append(profiles[i].wbShiftGM).append(",")
                  .append(profiles[i].contrast).append(",")
                  .append(profiles[i].saturation).append(",")
                  .append(profiles[i].sharpness).append("\n"); 
            }
            fos.write(sb.toString().getBytes()); fos.flush(); fos.getFD().sync(); fos.close();
        } catch (Exception e) {}
    }

    private void loadPreferences() {
        File backupFile = new File(getLutDir(), "RTLBAK.TXT");
        if (backupFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(backupFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("prefs=")) {
                        String[] p = line.split("=")[1].split(",");
                        if (p.length >= 3) {
                            prefShowFocusMeter = Boolean.parseBoolean(p[0]);
                            prefShowCinemaMattes = Boolean.parseBoolean(p[1]);
                            prefShowGridLines = Boolean.parseBoolean(p[2]);
                        }
                    }
                    else {
                        String[] parts = line.split(",");
                        if (parts.length >= 6) {
                            int idx = Integer.parseInt(parts[0]); 
                            int foundIndex = recipePaths.indexOf(parts[1]);
                            profiles[idx].lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            profiles[idx].opacity = Integer.parseInt(parts[2]); 
                            if (profiles[idx].opacity <= 5) profiles[idx].opacity = 100;
                            profiles[idx].grain = Math.min(5, Integer.parseInt(parts[3]));
                            if (parts.length >= 7) {
                                profiles[idx].grainSize = Math.min(2, Integer.parseInt(parts[4]));
                                profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[5])); 
                                profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[6]));
                            }
                            if (parts.length >= 10) {
                                profiles[idx].whiteBalance = parts[7];
                                profiles[idx].wbShift = Integer.parseInt(parts[8]);
                                profiles[idx].dro = parts[9];
                            }
                            if (parts.length >= 11) {
                                profiles[idx].wbShiftGM = Integer.parseInt(parts[10]);
                            }
                            if (parts.length >= 14) {
                                profiles[idx].contrast = Integer.parseInt(parts[11]);
                                profiles[idx].saturation = Integer.parseInt(parts[12]);
                                profiles[idx].sharpness = Integer.parseInt(parts[13]);
                            }
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
            Camera.Parameters p = mCamera.getParameters();
            RTLProfile prof = profiles[currentSlot];
            
            List<String> wbs = p.getSupportedWhiteBalance();
            String targetWb = "auto";
            if ("DAY".equals(prof.whiteBalance)) targetWb = "daylight";
            else if ("SHD".equals(prof.whiteBalance)) targetWb = "shade";
            else if ("CLD".equals(prof.whiteBalance)) targetWb = "cloudy-daylight";
            else if ("INC".equals(prof.whiteBalance)) targetWb = "incandescent";
            else if ("FLR".equals(prof.whiteBalance)) targetWb = "fluorescent";
            
            if (wbs != null && wbs.contains(targetWb)) {
                p.setWhiteBalance(targetWb);
            }

            // RESTORED: sony-dro legacy fallback
            if (p.get("dro-mode") != null) {
                if ("OFF".equals(prof.dro)) {
                    p.set("dro-mode", "off");
                } else if ("AUTO".equals(prof.dro)) {
                    p.set("dro-mode", "auto");
                } else if (prof.dro.startsWith("LV")) {
                    p.set("dro-mode", "on"); 
                    try { p.set("dro-level", Integer.parseInt(prof.dro.replace("LV", ""))); } catch(Exception e){}
                }
            } else if (p.get("sony-dro") != null) {
                p.set("sony-dro", prof.dro.toLowerCase()); 
            }
            
            // RESTORED: Parameter null checks to prevent point-and-shoot camera daemon crashing
            if (p.get("contrast") != null) p.set("contrast", String.valueOf(prof.contrast));
            if (p.get("saturation") != null) p.set("saturation", String.valueOf(prof.saturation));
            if (p.get("sharpness") != null) p.set("sharpness", String.valueOf(prof.sharpness));
            
            // RESTORED: WB Shift Enable Mode
            if (p.get("white-balance-shift-mode") != null) {
                p.set("white-balance-shift-mode", (prof.wbShift != 0 || prof.wbShiftGM != 0) ? "true" : "false");
            }
            if (p.get("white-balance-shift-lb") != null) p.set("white-balance-shift-lb", String.valueOf(prof.wbShift));
            if (p.get("white-balance-shift-cc") != null) p.set("white-balance-shift-cc", String.valueOf(prof.wbShiftGM)); 
            
            mCamera.setParameters(p);
        } catch (Exception e) {}
    }

    private void setHUDVisibility(int v) { 
        tvTopStatus.setVisibility(v); 
        llBottomBar.setVisibility(v); 
        tvBattery.setVisibility(v); 
        batteryIcon.setVisibility(v); 
        tvMode.setVisibility(v); 
        tvFocusMode.setVisibility(v); 
        tvReview.setVisibility(v); 
        
        if (focusMeter != null) {
            focusMeter.setVisibility(v == View.VISIBLE && cachedIsManualFocus ? View.VISIBLE : View.GONE);
        }
    }

    // SPATIAL D-PAD: Up/Down/Left/Right explicitly maps to physical screen locations
    private void navigateHomeSpatial(int keyCode) {
        switch (mDialMode) {
            case DIAL_MODE_SHUTTER:
                if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_FOCUS;
                break;
            case DIAL_MODE_APERTURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_FOCUS;
                break;
            case DIAL_MODE_ISO:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW;
                break;
            case DIAL_MODE_EXPOSURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW;
                break;
            case DIAL_MODE_REVIEW:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_RTL;
                break;
            case DIAL_MODE_RTL:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_REVIEW;
                break;
            case DIAL_MODE_PASM:
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_FOCUS;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_RTL;
                break;
            case DIAL_MODE_FOCUS:
                if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_RTL;
                break;
        }
        updateMainHUD();
    }

    @Override 
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        
        // SHUTTER HARD-BLOCK
        if (isProcessing && (sc == ScalarInput.ISV_KEY_S1_1 || sc == ScalarInput.ISV_KEY_S1_2 || sc == ScalarInput.ISV_KEY_S2)) {
            return true; 
        }

        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            mDialMode = DIAL_MODE_RTL; 
            if (isMenuOpen) { exitMenu(); return true; }
            if (isPlaybackMode) { exitPlayback(); return true; }
            
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                setHUDVisibility(View.GONE);
            }
            if (afOverlay != null && mCamera != null) { 
                try {
                    String fm = mCamera.getParameters().getFocusMode();
                    if (!"manual".equals(fm)) afOverlay.startFocus(mCamera); 
                } catch (Exception e) {}
            }
            return super.onKeyDown(keyCode, event);
        }

        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }

        // DEDICATED PLAYBACK TOGGLE: We now use the physical PLAY button for Image Viewer
        if (sc == ScalarInput.ISV_KEY_PLAY) {
            if (isPlaybackMode) {
                exitPlayback();
            } else if (!isMenuOpen && !isProcessing) {
                enterPlayback();
            }
            return true;
        }

        if (isPlaybackMode) {
            if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { showPlaybackImage(playbackIndex + 1); return true; }
            if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { showPlaybackImage(playbackIndex - 1); return true; }
            if (sc == ScalarInput.ISV_KEY_ENTER || sc == ScalarInput.ISV_KEY_MENU) { exitPlayback(); return true; }
            return true; 
        }

        if (sc == ScalarInput.ISV_KEY_MENU) {
            if (isProcessing) return true;
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                // AF HUNTING FIX: Cancel any searching and explicitly drop to MF when entering Menu
                if (mCamera != null) {
                    try {
                        mCamera.cancelAutoFocus();
                        Camera.Parameters p = mCamera.getParameters();
                        savedFocusMode = p.getFocusMode();
                        List<String> fModes = p.getSupportedFocusModes();
                        if (fModes != null && fModes.contains("manual")) {
                            p.setFocusMode("manual");
                            mCamera.setParameters(p);
                        }
                    } catch(Exception e){}
                }
                
                refreshRecipes();
                currentPage = 1; menuSelection = 0; isMenuEditing = false;
                menuContainer.setVisibility(View.VISIBLE); mainUIContainer.setVisibility(View.GONE); renderMenu();
            } else {
                exitMenu();
            }
            return true;
        }

        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) {
                // HUD TOGGLE: Enter hides/shows UI layout composition
                displayState = (displayState == 0) ? 1 : 0; 
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
            } else {
                // SPLIT BRAIN MENU: Enter toggles Editing Mode on and off
                if (currentPage == 4) {
                    handleConnectionAction(); 
                } else {
                    isMenuEditing = !isMenuEditing;
                    renderMenu();
                }
            }
            return true;
        }

        if (!isProcessing) {
            if (isMenuOpen) {
                if (isMenuEditing) {
                    // We are actively changing a value
                    if (sc == ScalarInput.ISV_KEY_UP || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE || sc == ScalarInput.ISV_KEY_RIGHT) {
                        handleMenuChange(1); return true;
                    }
                    if (sc == ScalarInput.ISV_KEY_DOWN || sc == ScalarInput.ISV_DIAL_1_COUNTERCW || sc == ScalarInput.ISV_KEY_LEFT) {
                        handleMenuChange(-1); return true;
                    }
                } else {
                    // We are browsing tabs
                    if (sc == ScalarInput.ISV_KEY_UP) { 
                        menuSelection--; 
                        if (menuSelection < 0) menuSelection = currentItemCount - 1; 
                        renderMenu(); return true; 
                    }
                    if (sc == ScalarInput.ISV_KEY_DOWN) { 
                        menuSelection++;
                        if (menuSelection >= currentItemCount) menuSelection = 0; 
                        renderMenu(); return true; 
                    }
                    if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { 
                        currentPage = (currentPage == 1) ? 4 : currentPage - 1; 
                        menuSelection = 0;
                        renderMenu(); return true; 
                    }
                    if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { 
                        currentPage = (currentPage == 4) ? 1 : currentPage + 1; 
                        menuSelection = 0;
                        renderMenu(); return true; 
                    }
                }
            } else {
                // SPATIAL HOME NAVIGATION
                if (sc == ScalarInput.ISV_KEY_UP || sc == ScalarInput.ISV_KEY_DOWN || sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_KEY_RIGHT) {
                    navigateHomeSpatial(sc); return true;
                }
                if (sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleHardwareInput(1); return true; }
                if (sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleHardwareInput(-1); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override 
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isProcessing && (event.getScanCode() == ScalarInput.ISV_KEY_S1_1 || event.getScanCode() == ScalarInput.ISV_KEY_S1_2 || event.getScanCode() == ScalarInput.ISV_KEY_S2)) {
            return true; 
        }

        if (event.getScanCode() == ScalarInput.ISV_KEY_S1_1) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                setHUDVisibility(View.VISIBLE);
            }
            if (afOverlay != null && mCamera != null) { 
                try {
                    String fm = mCamera.getParameters().getFocusMode();
                    if (!"manual".equals(fm)) afOverlay.stopFocus(mCamera); 
                } catch (Exception e) {}
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = profiles[currentSlot];
        try {
            if (currentPage == 1) { 
                switch(menuSelection) {
                    case 0: currentSlot = (currentSlot + dir + 10) % 10; break; 
                    case 1: p.lutIndex = (p.lutIndex + dir + recipePaths.size()) % recipePaths.size(); break;
                    case 2: p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); break;
                    case 3: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break;
                    case 4: p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); break;
                    case 5: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break;
                    case 6: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break;
                }
            } else if (currentPage == 2) {
                switch(menuSelection) {
                    case 0: 
                        int wbi = java.util.Arrays.asList(wbLabels).indexOf(p.whiteBalance);
                        if(wbi == -1) wbi = 0;
                        p.whiteBalance = wbLabels[(wbi + dir + wbLabels.length) % wbLabels.length];
                        break;
                    case 1: p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dir)); break;
                    case 2: p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dir)); break;
                    case 3:
                        int droi = java.util.Arrays.asList(droLabels).indexOf(p.dro);
                        if(droi == -1) droi = 0;
                        p.dro = droLabels[(droi + dir + droLabels.length) % droLabels.length];
                        break;
                    case 4: p.contrast = Math.max(-3, Math.min(3, p.contrast + dir)); break;
                    case 5: p.saturation = Math.max(-3, Math.min(3, p.saturation + dir)); break;
                    case 6: p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir)); break;
                }
            } else if (currentPage == 3) { 
                switch(menuSelection) {
                    case 0: qualityIndex = (qualityIndex + dir + 3) % 3; break;
                    case 1: 
                        Camera.Parameters cp = mCamera.getParameters();
                        List<String> scnModes = cp.getSupportedSceneModes();
                        if (scnModes != null && scnModes.size() > 0) {
                            int idx = scnModes.indexOf(cp.getSceneMode());
                            if (idx == -1) idx = 0;
                            cp.setSceneMode(scnModes.get((idx + dir + scnModes.size()) % scnModes.size()));
                            try { mCamera.setParameters(cp); } catch(Exception e) {}
                        }
                        break;
                    case 2: prefShowFocusMeter = !prefShowFocusMeter; break;
                    case 3: prefShowCinemaMattes = !prefShowCinemaMattes; break;
                    case 4: prefShowGridLines = !prefShowGridLines; break; 
                }
            } 
        } catch (Exception e) {}
        
        renderMenu();
        uiHandler.removeCallbacks(applySettingsRunnable);
        uiHandler.postDelayed(applySettingsRunnable, 400);
    }

    private void renderMenu() {
        if (currentPage == 1) tvMenuTitle.setText("1. RTL Base");
        else if (currentPage == 2) tvMenuTitle.setText("2. Color & Tone");
        else if (currentPage == 3) tvMenuTitle.setText("3. Global View");
        else tvMenuTitle.setText("4. Network");
        
        for(int i=0; i<4; i++) {
            boolean isCurPage = (currentPage == i+1);
            tvPageNumbers[i].setTextColor(isCurPage ? Color.rgb(230, 50, 15) : Color.WHITE);
            tvPageNumbers[i].setPaintFlags(isCurPage ? Paint.UNDERLINE_TEXT_FLAG : 0);
        }

        for(int i=0; i<7; i++) menuRows[i].setVisibility(View.GONE);

        RTLProfile p = profiles[currentSlot];

        if (currentPage == 1) { 
            currentItemCount = 7;
            String[] rLabels = {"RTL Slot", "LUT", "Opacity", "Grain Amount", "Grain Size", "Highlight Roll", "Vignette"};
            String[] rValues = {
                String.valueOf(currentSlot + 1), recipeNames.get(p.lutIndex), p.opacity + "%", 
                intensityLabels[p.grain], grainSizeLabels[p.grainSize], intensityLabels[p.rollOff], intensityLabels[p.vignette]
            };
            for(int i=0; i<7; i++) {
                menuLabels[i].setText(rLabels[i]); menuValues[i].setText(rValues[i]); menuRows[i].setVisibility(View.VISIBLE);
            }
        } 
        else if (currentPage == 2) {
            currentItemCount = 7;
            String[] cLabels = {"White Balance", "WB Shift (A-B)", "WB Shift (G-M)", "DRO", "Contrast", "Saturation", "Sharpness"};
            String[] cValues = {
                p.whiteBalance, formatAB(p.wbShift), formatGM(p.wbShiftGM), p.dro,
                formatSign(p.contrast), formatSign(p.saturation), formatSign(p.sharpness)
            };
            for(int i=0; i<7; i++) {
                menuLabels[i].setText(cLabels[i]); menuValues[i].setText(cValues[i]); menuRows[i].setVisibility(View.VISIBLE);
            }
        }
        else if (currentPage == 3) { 
            currentItemCount = 5;
            String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
            String currentScene = "UNKNOWN";
            try { if(mCamera != null) { String sm = mCamera.getParameters().getSceneMode(); if(sm != null) currentScene = sm.toUpperCase(); } } catch(Exception e){}
            
            String[] gLabels = {"Global Quality", "Base Scene", "Manual Focus Meter", "Anamorphic Crop", "Rule of Thirds Grid"};
            String[] gValues = {qLabels[qualityIndex], currentScene, prefShowFocusMeter ? "ON" : "OFF", prefShowCinemaMattes ? "ON" : "OFF", prefShowGridLines ? "ON" : "OFF"};
            for(int i=0; i<5; i++) {
                menuLabels[i].setText(gLabels[i]); menuValues[i].setText(gValues[i]); menuRows[i].setVisibility(View.VISIBLE);
            }
        }
        else if (currentPage == 4) { 
            currentItemCount = 3;
            String[] cLabels = {"Camera Hotspot", "Home Wi-Fi", "Stop Networking"};
            String[] cValues = {connStatusHotspot, connStatusWifi, ""};
            for(int i=0; i<3; i++) {
                menuLabels[i].setText(cLabels[i]); 
                menuValues[i].setText(cValues[i]); 
                menuRows[i].setVisibility(View.VISIBLE);
            }
        }

        // MENU STATE FIX: Visual indicators for Browsing vs Editing
        for (int i = 0; i < currentItemCount; i++) {
            boolean sel = (i == menuSelection);
            
            if (sel) {
                if (isMenuEditing) {
                    menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.rgb(230, 50, 15)); 
                } else {
                    menuRows[i].setBackgroundColor(Color.rgb(230, 50, 15));
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.WHITE);
                }
            } else {
                menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                menuLabels[i].setTextColor(Color.WHITE);
                menuValues[i].setTextColor(Color.WHITE);
            }
            
            if (currentPage == 4 && menuValues[i].getText().toString().startsWith("http")) {
                menuValues[i].setTextColor(sel ? Color.WHITE : Color.rgb(230, 50, 15));
            }
        }
    }

    private void handleHardwareInput(int d) {
        Camera c = cameraManager.getCamera(); 
        CameraEx cx = cameraManager.getCameraEx();
        
        if (c == null || cx == null) {
            return;
        }
        
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cx.createParametersModifier(p);
        
        if (mDialMode == DIAL_MODE_RTL) { 
            recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + d); 
            applyHardwareRecipe(); 
            triggerLutPreload(); 
        }
        else if (mDialMode == DIAL_MODE_SHUTTER) { 
            if (d > 0) {
                cx.incrementShutterSpeed(); 
            } else {
                cx.decrementShutterSpeed(); 
            }
        }
        else if (mDialMode == DIAL_MODE_APERTURE) { 
            if (d > 0) {
                cx.incrementAperture(); 
            } else {
                cx.decrementAperture(); 
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
            List<String> valid = new ArrayList<String>(); 
            String[] desired = {"program-auto", "aperture-priority", "shutter-priority", "shutter-speed-priority", "manual-exposure"};
            List<String> supported = p.getSupportedSceneModes();
            if (supported != null) {
                for (String s : desired) { 
                    if (supported.contains(s)) {
                        valid.add(s); 
                    }
                }
                if (!valid.isEmpty()) {
                    int idx = valid.indexOf(p.getSceneMode()); 
                    if (idx == -1) {
                        idx = 0; 
                    }
                    p.setSceneMode(valid.get((idx + d + valid.size()) % valid.size())); 
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_FOCUS) {
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null && !focusModes.isEmpty()) {
                int idx = focusModes.indexOf(p.getFocusMode());
                p.setFocusMode(focusModes.get((idx + d + focusModes.size()) % focusModes.size()));
                try { c.setParameters(p); } catch (Exception e) {}
            }
        }
        
        // ONLY trigger HUD redraw when we actively change a setting to save Battery
        updateMainHUD();
    }

    private void updateMainHUD() {
        Camera c = cameraManager.getCamera(); 
        if (c == null) {
            return;
        }
        
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);
        
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        String displayName = name.length() > 15 ? name.substring(0, 12) + "..." : name;
        
        if (!isProcessing) {
            tvTopStatus.setText("RTL " + (recipeManager.getCurrentSlot() + 1) + " [" + displayName + "]\n" + (isReady ? "READY" : "LOADING.."));
            tvTopStatus.setTextColor(mDialMode == DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE);
        }
        
        String sm = p.getSceneMode(); 
        if ("manual-exposure".equals(sm)) {
            tvMode.setText("M"); 
        } else if ("aperture-priority".equals(sm)) {
            tvMode.setText("A"); 
        } else if ("shutter-priority".equals(sm) || "shutter-speed-priority".equals(sm)) {
            tvMode.setText("S"); 
        } else if ("program-auto".equals(sm)) {
            tvMode.setText("P");
        } else {
            tvMode.setText(sm != null ? sm.toUpperCase() : "SCN");
        }
        
        cachedAperture = pm.getAperture() / 100.0f;
        
        Pair<Integer, Integer> ss = pm.getShutterSpeed(); 
        tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\"");
        tvValAperture.setText(String.format("f%.1f", cachedAperture)); 
        tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity());
        tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        
        tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
        tvValShutter.setTextColor(mDialMode == DIAL_MODE_SHUTTER ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValAperture.setTextColor(mDialMode == DIAL_MODE_APERTURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValIso.setTextColor(mDialMode == DIAL_MODE_ISO ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValEv.setTextColor(mDialMode == DIAL_MODE_EXPOSURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvMode.setTextColor(mDialMode == DIAL_MODE_PASM ? Color.rgb(230, 50, 15) : Color.WHITE);
        
        String fm = p.getFocusMode();
        cachedIsManualFocus = "manual".equals(fm);
        
        if ("auto".equals(fm)) {
            tvFocusMode.setText("AF-S"); 
        } else if (cachedIsManualFocus) {
            tvFocusMode.setText("MF"); 
        } else if ("continuous-video".equals(fm) || "continuous-picture".equals(fm)) {
            tvFocusMode.setText("AF-C"); 
        } else {
            tvFocusMode.setText(fm != null ? fm.toUpperCase() : "AF");
        }
        
        tvFocusMode.setTextColor(mDialMode == DIAL_MODE_FOCUS ? Color.rgb(230, 50, 15) : Color.WHITE);
        
        if (focusMeter != null) {
            focusMeter.setVisibility(cachedIsManualFocus ? View.VISIBLE : View.GONE);
        }
        
        gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); 
        cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);
    }

    private void openCamera() {
        if (mCameraEx == null && hasSurface) {
            try { 
                mCameraEx = CameraEx.open(0, null); 
                mCamera = mCameraEx.getNormalCamera();
                mCameraEx.startDirectShutter(); 
                
                if (origSceneMode == null && mCamera != null) {
                    try {
                        Camera.Parameters p = mCamera.getParameters();
                        origSceneMode = p.getSceneMode();
                        origFocusMode = p.getFocusMode();
                        origWhiteBalance = p.getWhiteBalance();
                        origDroMode = p.get("dro-mode");
                        origDroLevel = p.get("dro-level");
                        origSonyDro = p.get("sony-dro");
                        origContrast = p.get("contrast");
                        origSaturation = p.get("saturation");
                        origSharpness = p.get("sharpness");
                        origWbShiftMode = p.get("white-balance-shift-mode");
                        origWbShiftLb = p.get("white-balance-shift-lb");
                        origWbShiftCc = p.get("white-balance-shift-cc");
                    } catch (Exception e) {}
                }
                
                try {
                    Class<?> apListenerClass = Class.forName("com.sony.scalar.hardware.CameraEx$ApertureChangeListener");
                    Object apProxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] { apListenerClass },
                        new java.lang.reflect.InvocationHandler() {
                            @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                                if (method.getName().equals("onApertureChange")) {
                                    runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } });
                                }
                                return null;
                            }
                        }
                    );
                    mCameraEx.getClass().getMethod("setApertureChangeListener", apListenerClass).invoke(mCameraEx, apProxy);
                } catch (Exception e) {}

                try {
                    Class<?> isoListenerClass = Class.forName("com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener");
                    Object isoProxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] { isoListenerClass },
                        new java.lang.reflect.InvocationHandler() {
                            @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                                if (method.getName().equals("onChanged")) {
                                    runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } });
                                }
                                return null;
                            }
                        }
                    );
                    mCameraEx.getClass().getMethod("setAutoISOSensitivityListener", isoListenerClass).invoke(mCameraEx, isoProxy);
                } catch (Exception e) {}

                try {
                    Class<?> listenerClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] { listenerClass },
                        new java.lang.reflect.InvocationHandler() {
                            @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                if (method.getName().equals("onChanged") && args != null && args.length == 2) {
                                    Object pos = args[0];
                                    if (pos != null) {
                                        int cur = pos.getClass().getField("currentPosition").getInt(pos);
                                        int max = pos.getClass().getField("maxPosition").getInt(pos);
                                        if (max > 0) {
                                            lastKnownFocusRatio = (float) cur / max;
                                            if (focusMeter != null && cachedIsManualFocus) { 
                                                runOnUiThread(new Runnable() { 
                                                    public void run() {
                                                        focusMeter.update(lastKnownFocusRatio, cachedAperture, true); 
                                                    }
                                                });
                                            }
                                        }
                                    }
                                }
                                return null;
                            }
                        }
                    );
                    mCameraEx.getClass().getMethod("setFocusDriveListener", listenerClass).invoke(mCameraEx, proxy);
                } catch (Exception e) {}

                CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
                mCameraEx.setAutoPictureReviewControl(apr); 
                apr.setPictureReviewTime(0);
                mCamera.setPreviewDisplay(mSurfaceView.getHolder()); 
                mCamera.startPreview(); 
                
                try {
                    Camera.Parameters params = mCamera.getParameters();
                    CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(params);
                    pm.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
                    mCamera.setParameters(params);
                } catch(Exception e) {}

                applyProfileSettings();
                syncHardwareState();
                updateMainHUD();
                
            } catch (Exception e) {} 
        }
    }

    private void closeCamera() {
        if (mCamera != null && origSceneMode != null) {
            try {
                Camera.Parameters p = mCamera.getParameters();
                if (origSceneMode != null) p.setSceneMode(origSceneMode);
                if (origFocusMode != null) p.setFocusMode(origFocusMode);
                if (origWhiteBalance != null) p.setWhiteBalance(origWhiteBalance);
                if (origDroMode != null) p.set("dro-mode", origDroMode);
                if (origDroLevel != null) p.set("dro-level", origDroLevel);
                if (origSonyDro != null) p.set("sony-dro", origSonyDro);
                if (origContrast != null) p.set("contrast", origContrast);
                if (origSaturation != null) p.set("saturation", origSaturation);
                if (origSharpness != null) p.set("sharpness", origSharpness);
                if (origWbShiftMode != null) p.set("white-balance-shift-mode", origWbShiftMode);
                if (origWbShiftLb != null) p.set("white-balance-shift-lb", origWbShiftLb);
                if (origWbShiftCc != null) p.set("white-balance-shift-cc", origWbShiftCc);
                mCamera.setParameters(p);
            } catch (Exception e) {}
        }
        
        if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; mCamera = null; }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; openCamera(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { hasSurface = false; closeCamera(); }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { 
        runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } });
    }
    
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
}