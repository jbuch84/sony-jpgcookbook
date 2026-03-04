package com.github.ma1co.pmcademo.app;

import com.jpgcookbook.sony.R;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.text.Html;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    
    // UI Elements
    private FrameLayout mainUIContainer;
    private ScrollView menuScrollView;
    private TableLayout menuTable;
    private TextView tvBottomBar, tvTopStatus; 
    private TextView[] menuLabels = new TextView[10]; // Left Column
    private TextView[] menuValues = new TextView[10]; // Right Column
    
    // Playback Elements
    private FrameLayout playbackContainer;
    private ImageView playbackImageView;
    private TextView tvPlaybackInfo;
    private List<File> playbackFiles = new ArrayList<File>();
    private int playbackIndex = 0;
    private Bitmap currentPlaybackBitmap = null;
    
    // State & Engine
    private boolean isProcessing = false;
    private boolean isReady = false; 
    private boolean isMenuOpen = false;
    private boolean isPlaybackMode = false;
    private int displayState = 0; 
    
    private LutEngine mEngine = new LutEngine();
    private PreloadLutTask currentPreloadTask = null; 
    private SonyFileObserver mFileObserver;
    private String sonyDCIMPath = "";
    
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;
    private ArrayList<String> recipeList = new ArrayList<String>();

    // --- RTL Profile with 0-5 Scales ---
    class RTLProfile {
        int lutIndex = 0;
        int opacity = 5;  
        int grain = 0;    
        int rollOff = 0;  
        int vignette = 0; 
        
        // SONY PLACEHOLDERS
        String whiteBalance = "AUTO";
        int wbShift = 0;
        String dro = "OFF";
    }
    
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0; 
    private int qualityIndex = 1; 
    private int menuSelection = 0; 

    public enum DialMode { rtl, shutter, aperture, iso, exposure, review }
    private DialMode mDialMode = DialMode.rtl;

    private class SonyFileObserver extends FileObserver {
        public SonyFileObserver(String path) { super(path, FileObserver.CLOSE_WRITE); }
        @Override public void onEvent(int event, final String path) {
            if (path == null || isProcessing || !isReady) return;
            if (path.toUpperCase().endsWith(".JPG") && !path.startsWith("PRCS")) {
                final String fullPath = sonyDCIMPath + "/" + path;
                runOnUiThread(new Runnable() { @Override public void run() { new ProcessTask().execute(fullPath); }});
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootLayout);

        scanRecipes();
        for(int i=0; i<10; i++) profiles[i] = new RTLProfile();
        loadPreferences();

        buildUI(rootLayout);

        String[] possibleRoots = { Environment.getExternalStorageDirectory().getAbsolutePath(), "/mnt/sdcard", "/storage/sdcard0", "/sdcard" };
        for (String r : possibleRoots) {
            File f = new File(r + "/DCIM/100MSDCF");
            if (f.exists()) { sonyDCIMPath = f.getAbsolutePath(); break; }
        }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        triggerLutPreload();
    }

    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this);
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));

        tvTopStatus = new TextView(this);
        tvTopStatus.setTextColor(Color.WHITE);
        tvTopStatus.setTextSize(20);
        tvTopStatus.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        topParams.setMargins(30, 30, 0, 0);
        mainUIContainer.addView(tvTopStatus, topParams);

        tvBottomBar = new TextView(this);
        tvBottomBar.setTextSize(18);
        tvBottomBar.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        botParams.setMargins(0, 0, 0, 30);
        mainUIContainer.addView(tvBottomBar, botParams);

        // THE TABULAR MENU
        menuScrollView = new ScrollView(this);
        menuScrollView.setBackgroundColor(Color.argb(230, 15, 15, 15));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(700, 350, Gravity.CENTER);
        
        menuTable = new TableLayout(this);
        menuTable.setColumnStretchable(1, true); // Pushes values to the far right edge
        menuTable.setPadding(40, 40, 40, 40);
        menuScrollView.addView(menuTable);

        for (int i = 0; i < 10; i++) {
            TableRow row = new TableRow(this);
            row.setPadding(0, 10, 0, 10);
            
            menuLabels[i] = new TextView(this);
            menuLabels[i].setTextSize(20);
            menuLabels[i].setPadding(0, 0, 40, 0); 
            
            menuValues[i] = new TextView(this);
            menuValues[i].setTextSize(20);
            menuValues[i].setGravity(Gravity.RIGHT);
            
            row.addView(menuLabels[i]);
            row.addView(menuValues[i]);
            menuTable.addView(row);
        }
        menuScrollView.setVisibility(View.GONE);
        rootLayout.addView(menuScrollView, scrollParams);
        
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
        renderMenu();
    }

    private void refreshPlaybackFiles() {
        playbackFiles.clear();
        File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (outDir.exists()) {
            File[] files = outDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().toUpperCase().endsWith(".JPG")) playbackFiles.add(f);
                }
            }
        }
        java.util.Collections.sort(playbackFiles, new java.util.Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
    }

    private void showPlaybackImage(int index) {
        if (playbackFiles.isEmpty()) {
            tvPlaybackInfo.setText("NO GRADED PHOTOS");
            return;
        }
        if (index < 0) index = 0;
        if (index >= playbackFiles.size()) index = playbackFiles.size() - 1;
        playbackIndex = index;
        
        File imgFile = playbackFiles.get(playbackIndex);
        
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null);
            currentPlaybackBitmap.recycle();
            currentPlaybackBitmap = null;
        }
        
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
        
        int scale = 1;
        while ((options.outWidth / scale) > 1200 || (options.outHeight / scale) > 1200) {
            scale *= 2;
        }
        
        options.inJustDecodeBounds = false;
        options.inSampleSize = scale;
        
        try {
            Bitmap rawBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            
            ExifInterface exif = new ExifInterface(imgFile.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationAngle = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;

            if (rotationAngle != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationAngle);
                currentPlaybackBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), matrix, true);
                if (currentPlaybackBitmap != rawBitmap) {
                    rawBitmap.recycle();
                }
            } else {
                currentPlaybackBitmap = rawBitmap;
            }

            playbackImageView.setImageBitmap(currentPlaybackBitmap);
            tvPlaybackInfo.setText((playbackIndex + 1) + " / " + playbackFiles.size() + "\n" + imgFile.getName());
        } catch (Exception e) {
            tvPlaybackInfo.setText("DECODE ERROR");
        } catch (OutOfMemoryError e) {
            tvPlaybackInfo.setText("MEMORY ERROR");
        }
    }

    private void exitPlayback() {
        playbackContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        isPlaybackMode = false;
        
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null);
            currentPlaybackBitmap.recycle();
            currentPlaybackBitmap = null;
        }
    }

    private File getLutDir() {
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (!lutDir.exists()) lutDir = new File("/mnt/sdcard/LUTS");
        return lutDir;
    }

    private void savePreferences() {
        // Internal App Memory
        SharedPreferences.Editor editor = getSharedPreferences("RTL_PREFS", MODE_PRIVATE).edit();
        editor.putBoolean("has_saved", true);
        editor.putInt("qualityIndex", qualityIndex);
        editor.putInt("currentSlot", currentSlot);
        for(int i=0; i<10; i++) {
            editor.putString("slot_" + i + "_lutName", recipeList.get(profiles[i].lutIndex));
            editor.putInt("slot_" + i + "_opac", profiles[i].opacity);
            editor.putInt("slot_" + i + "_grain", profiles[i].grain);
            editor.putInt("slot_" + i + "_roll", profiles[i].rollOff);
            editor.putInt("slot_" + i + "_vig", profiles[i].vignette);
        }
        editor.commit(); 

        // FAT32 8.3 FILENAME FIX applied here!
        try {
            File lutDir = getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            if (!backupFile.exists()) backupFile.createNewFile();

            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            for(int i=0; i<10; i++) {
                sb.append(i).append(",")
                  .append(recipeList.get(profiles[i].lutIndex)).append(",")
                  .append(profiles[i].opacity).append(",")
                  .append(profiles[i].grain).append(",")
                  .append(profiles[i].rollOff).append(",")
                  .append(profiles[i].vignette).append("\n");
            }
            
            fos.write(sb.toString().getBytes());
            fos.flush();
            fos.getFD().sync(); // Force physical write to SD Card
            fos.close();
            
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(backupFile)));
        } catch (Exception e) {
            Log.e("COOKBOOK", "Failed to save TXT: " + e.getMessage());
        }
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences("RTL_PREFS", MODE_PRIVATE);
        boolean hasSaved = prefs.getBoolean("has_saved", false);
        
        File lutDir = getLutDir();
        // FAT32 8.3 FILENAME FIX applied here!
        File backupFile = new File(lutDir, "RTLBAK.TXT");

        if (!hasSaved && backupFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(backupFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else {
                        String[] parts = line.split(",");
                        if (parts.length == 6) {
                            int idx = Integer.parseInt(parts[0]);
                            String lutName = parts[1];
                            int foundIndex = recipeList.indexOf(lutName);
                            profiles[idx].lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            profiles[idx].opacity = Math.min(5, Integer.parseInt(parts[2]));
                            profiles[idx].grain = Math.min(5, Integer.parseInt(parts[3]));
                            profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[4]));
                            profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[5]));
                        }
                    }
                }
                br.close();
                savePreferences(); 
                return;
            } catch (Exception e) {}
        }

        qualityIndex = prefs.getInt("qualityIndex", 1);
        currentSlot = prefs.getInt("currentSlot", 0);
        for(int i=0; i<10; i++) {
            String savedLutName = prefs.getString("slot_" + i + "_lutName", "NONE");
            int foundIndex = recipeList.indexOf(savedLutName);
            profiles[i].lutIndex = (foundIndex != -1) ? foundIndex : 0; 
            profiles[i].opacity = Math.min(5, prefs.getInt("slot_" + i + "_opac", 5));
            profiles[i].grain = Math.min(5, prefs.getInt("slot_" + i + "_grain", 0));
            profiles[i].rollOff = Math.min(5, prefs.getInt("slot_" + i + "_roll", 0));
            profiles[i].vignette = Math.min(5, prefs.getInt("slot_" + i + "_vig", 0));
        }
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event.getScanCode() == ScalarInput.ISV_KEY_PLAY) return true; 
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }

        if (isPlaybackMode) {
            if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { showPlaybackImage(playbackIndex + 1); return true; }
            if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { showPlaybackImage(playbackIndex - 1); return true; }
            if (sc == ScalarInput.ISV_KEY_ENTER || sc == ScalarInput.ISV_KEY_MENU || sc == ScalarInput.ISV_KEY_PLAY) { exitPlayback(); return true; }
            return true; 
        }

        if (sc == ScalarInput.ISV_KEY_MENU) {
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                menuScrollView.setVisibility(View.VISIBLE);
                mainUIContainer.setVisibility(View.GONE);
                renderMenu();
            } else {
                menuScrollView.setVisibility(View.GONE);
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                savePreferences(); // Pushes data to RTLBAK.TXT here
                triggerLutPreload();
                updateMainHUD();
            }
            return true;
        }

        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) {
                if (mDialMode == DialMode.review) {
                    isPlaybackMode = true;
                    refreshPlaybackFiles();
                    playbackContainer.setVisibility(View.VISIBLE);
                    mainUIContainer.setVisibility(View.GONE);
                    menuScrollView.setVisibility(View.GONE);
                    showPlaybackImage(0); 
                } else {
                    displayState = (displayState == 0) ? 1 : 0;
                    mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                }
            }
            return true;
        }

        if (!isProcessing) {
            if (isMenuOpen) {
                if (sc == ScalarInput.ISV_KEY_UP) { menuSelection = (menuSelection - 1 + 10) % 10; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN) { menuSelection = (menuSelection + 1) % 10; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleMenuChange(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleMenuChange(1); return true; }
            } else {
                if (sc == ScalarInput.ISV_KEY_LEFT) { cycleMode(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT) { cycleMode(1); return true; }
                if (sc == ScalarInput.ISV_KEY_UP || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = profiles[currentSlot];
        try {
            switch(menuSelection) {
                case 0: qualityIndex = (qualityIndex + dir + 3) % 3; break;
                case 1: currentSlot = (currentSlot + dir + 10) % 10; break; 
                case 2: p.lutIndex = (p.lutIndex + dir + recipeList.size()) % recipeList.size(); break;
                case 3: p.opacity = Math.max(0, Math.min(5, p.opacity + dir)); break;
                case 4: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break;
                case 5: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break;
                case 6: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break;
                // 7, 8, 9 are Placeholders
            }
        } catch (Exception e) {}
        renderMenu();
    }

    private void renderMenu() {
        RTLProfile p = profiles[currentSlot];
        String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
        String lutName = recipeList.get(p.lutIndex).split("\\.")[0].toUpperCase();

        // TABULAR BINDING
        menuLabels[0].setText("Global Quality");  menuValues[0].setText("< " + qLabels[qualityIndex] + " >");
        menuLabels[1].setText("RTL Slot");        menuValues[1].setText("< " + (currentSlot + 1) + " >");
        menuLabels[2].setText("LUT");             menuValues[2].setText("< " + lutName + " >");
        menuLabels[3].setText("Opacity");         menuValues[3].setText("< " + p.opacity + " >");
        menuLabels[4].setText("Grain");           menuValues[4].setText("< " + p.grain + " >");
        menuLabels[5].setText("Highlight Roll");  menuValues[5].setText("< " + p.rollOff + " >");
        menuLabels[6].setText("Vignette");        menuValues[6].setText("< " + p.vignette + " >");
        menuLabels[7].setText("[LOCKED] W.Bal");  menuValues[7].setText("< " + p.whiteBalance + " >");
        menuLabels[8].setText("[LOCKED] WB Shift");menuValues[8].setText("< " + p.wbShift + " >");
        menuLabels[9].setText("[LOCKED] DRO");    menuValues[9].setText("< " + p.dro + " >");

        for (int i = 0; i < 10; i++) {
            menuLabels[i].setTextColor(i == menuSelection ? Color.GREEN : (i > 6 ? Color.DKGRAY : Color.WHITE));
            menuValues[i].setTextColor(i == menuSelection ? Color.GREEN : (i > 6 ? Color.DKGRAY : Color.WHITE));
        }

        menuScrollView.post(new Runnable() {
            @Override
            public void run() {
                if (menuLabels[menuSelection] != null) {
                    int targetTop = ((View)menuLabels[menuSelection].getParent()).getTop();
                    int itemHeight = ((View)menuLabels[menuSelection].getParent()).getHeight();
                    int scrollHeight = menuScrollView.getHeight();
                    int scrollY = targetTop - (scrollHeight / 2) + (itemHeight / 2);
                    menuScrollView.smoothScrollTo(0, Math.max(0, scrollY));
                }
            }
        });
    }

    private void cycleMode(int dir) {
        DialMode[] modes = DialMode.values();
        int ord = (mDialMode.ordinal() + dir + modes.length) % modes.length;
        mDialMode = modes[ord];
        updateMainHUD();
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            if (mDialMode == DialMode.rtl) { 
                currentSlot = (currentSlot + d + 10) % 10; 
                triggerLutPreload(); 
            }
            else if (mDialMode == DialMode.shutter) { 
                if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); 
            }
            else if (mDialMode == DialMode.aperture) { 
                if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); 
            }
            else if (mDialMode == DialMode.iso) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) { pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); mCamera.setParameters(p); }
            }
            else if (mDialMode == DialMode.exposure) { 
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); 
                mCamera.setParameters(p); 
            }
            updateMainHUD();
        } catch (Exception e) {}
    }

    private void updateMainHUD() {
        if(mCamera == null) return;
        RTLProfile p = profiles[currentSlot];
        String lutName = recipeList.get(p.lutIndex).split("\\.")[0].toUpperCase();
        tvTopStatus.setText("RTL " + (currentSlot + 1) + " [" + lutName + "]\n" + (isReady ? "READY" : "LOADING..."));
        
        try {
            Camera.Parameters params = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(params);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            String ss = speed.first == 1 && speed.second != 1 ? speed.first + "/" + speed.second : speed.first + "\"";
            String ap = "f/" + (pm.getAperture() / 100.0f);
            String iso = pm.getISOSensitivity() == 0 ? "AUTO" : String.valueOf(pm.getISOSensitivity());
            String exp = String.format("%.1f", params.getExposureCompensation() * params.getExposureCompensationStep());

            String g = "<font color='#00FF00'>";
            String w = "<font color='#FFFFFF'>";
            
            String html = (mDialMode == DialMode.rtl ? g : w) + "[RTL " + (currentSlot+1) + "]</font>   " +
                          (mDialMode == DialMode.shutter ? g : w) + "S: " + ss + "</font>   " + 
                          (mDialMode == DialMode.aperture ? g : w) + "A: " + ap + "</font>   " + 
                          (mDialMode == DialMode.iso ? g : w) + "ISO: " + iso + "</font>   " + 
                          (mDialMode == DialMode.exposure ? g : w) + "EV: " + exp + "</font>   " +
                          (mDialMode == DialMode.review ? g : w) + "[REVIEW]</font>";
                          
            tvBottomBar.setText(Html.fromHtml(html));
        } catch (Exception e) {}
    }

    private void scanRecipes() { 
        recipeList.clear(); recipeList.add("NONE"); 
        File lutDir = getLutDir();
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) if (f.length() > 10240 && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
        }
    }

    private void triggerLutPreload() {
        if (currentPreloadTask != null) currentPreloadTask.cancel(true);
        if (profiles[currentSlot].lutIndex > 0) {
            currentPreloadTask = new PreloadLutTask(); 
            currentPreloadTask.execute(profiles[currentSlot].lutIndex);
        } else {
            isReady = true; 
            updateMainHUD();
        }
    }

    private void startAutoProcessPolling() {
        isPolling = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (isPolling) {
                    try {
                        Thread.sleep(300); 
                        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                        File sonyDir = new File(dcim, "100MSDCF");
                        if (sonyDir.exists()) {
                            File[] files = sonyDir.listFiles();
                            if (files != null && files.length > 0) {
                                File newest = null; long maxModified = 0;
                                for (File f : files) {
                                    if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("PRCS") && !f.getName().startsWith("GRADED")) {
                                        if (f.lastModified() > maxModified) { maxModified = f.lastModified(); newest = f; }
                                    }
                                }
                                if (newest != null) {
                                    if (lastNewestFileTime == 0) {
                                        lastNewestFileTime = maxModified;
                                    } else if (maxModified > lastNewestFileTime) {
                                        lastNewestFileTime = maxModified;
                                        if (!isProcessing && (isReady || profiles[currentSlot].lutIndex == 0)) {
                                            final String path = newest.getAbsolutePath();
                                            runOnUiThread(new Runnable() { @Override public void run() { if (!isProcessing) new ProcessTask().execute(path); } });
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        }).start();
    }

    private class PreloadLutTask extends AsyncTask<Integer, Void, Boolean> {
        @Override protected void onPreExecute() {
            isReady = false; updateMainHUD();
        }
        @Override protected Boolean doInBackground(Integer... params) {
            File lutDir = getLutDir();
            return mEngine.loadLut(new File(lutDir, recipeList.get(params[0])), recipeList.get(params[0]));
        }
        @Override protected void onPostExecute(Boolean success) {
            if (isCancelled()) return; 
            isReady = true; updateMainHUD();
        }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        @Override protected void onPreExecute() { 
            isProcessing = true;
            tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW);
        }
        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                long lastSize = -1; int timeout = 0;
                while (timeout < 100) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; Thread.sleep(100); timeout++;
                }

                int scale = (qualityIndex == 0) ? 4 : (qualityIndex == 2 ? 1 : 2);
                File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, original.getName());

                RTLProfile p = profiles[currentSlot];
                if (mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale, p.opacity * 20, p.grain * 20, p.vignette * 20, p.rollOff * 20)) {
                    copyExif(original.getAbsolutePath(), outFile.getAbsolutePath());
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED " + (scale==1?"24MP":(scale==2?"6MP":"1.5MP"));
                }
                return "FAILED";
            } catch (Throwable t) { return "ERR"; }
        }
        @Override protected void onPostExecute(String result) {
            isProcessing = false;
            tvTopStatus.setTextColor(Color.WHITE);
            updateMainHUD(); 
        }
    }

    private void copyExif(String sourcePath, String destPath) {
        try {
            android.media.ExifInterface sourceExif = new android.media.ExifInterface(sourcePath);
            android.media.ExifInterface destExif = new android.media.ExifInterface(destPath);
            String[] tags = {"FNumber", "ExposureTime", "ISOSpeedRatings", "FocalLength", "DateTime", "Make", "Model", "WhiteBalance", "Flash"};
            for (String tag : tags) { String value = sourceExif.getAttribute(tag); if (value != null) destExif.setAttribute(tag, value); }
            destExif.saveAttributes();
        } catch (IOException e) {}
    }

    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null); mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(apr); apr.setPictureReviewTime(0);
            mCamera.setPreviewDisplay(h); mCamera.startPreview(); updateMainHUD();
        } catch (Exception e) {} 
    }
    @Override protected void onResume() { super.onResume(); if (mCamera != null) updateMainHUD(); startAutoProcessPolling(); }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) mCameraEx.release(); isPolling = false; savePreferences(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { updateMainHUD(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}