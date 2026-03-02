package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;
    
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    
    private String currentDcimPath = "";
    private HashSet<String> knownFiles = new HashSet<String>();
    private Handler m_handler = new Handler();
    private boolean isBaking = false;

    public enum DialMode { shutter, aperture, iso, exposure, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceView.getHolder().addCallback(this);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        findActiveDcimPath();
        initializeFileLibrary();
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void findActiveDcimPath() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        if (dcim.exists() && dcim.isDirectory()) {
            File[] folders = dcim.listFiles();
            ArrayList<String> sonyFolders = new ArrayList<String>();
            if (folders != null) {
                for (File f : folders) {
                    if (f.getName().endsWith("MSDCF")) sonyFolders.add(f.getAbsolutePath());
                }
            }
            if (!sonyFolders.isEmpty()) {
                Collections.sort(sonyFolders);
                currentDcimPath = sonyFolders.get(sonyFolders.size() - 1);
                return;
            }
        }
        currentDcimPath = "/sdcard/DCIM/100MSDCF";
    }

    private void initializeFileLibrary() {
        if (currentDcimPath.isEmpty()) return;
        File dir = new File(currentDcimPath);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) knownFiles.add(f.getName());
        }
        startHunterLoop();
    }

    private void startHunterLoop() {
        m_handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isBaking && recipeIndex > 0) {
                    File dir = new File(currentDcimPath);
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName();
                            if (name.toUpperCase().endsWith(".JPG") && !name.startsWith("MIRROR_") && !knownFiles.contains(name)) {
                                knownFiles.add(name);
                                new PersistenceMirrorTask(name).execute();
                                break; 
                            }
                        }
                    }
                }
                m_handler.postDelayed(this, 1500);
            }
        }, 1500);
    }

    private class PersistenceMirrorTask extends AsyncTask<Void, String, Boolean> {
        String fileName;
        PersistenceMirrorTask(String name) { this.fileName = name; }

        @Override protected void onPreExecute() { 
            isBaking = true;
            tvRecipe.setText("WAITING FOR BIONZ...");
            tvRecipe.setTextColor(Color.YELLOW);
        }

        @Override protected Boolean doInBackground(Void... voids) {
            File original = new File(currentDcimPath, fileName);
            Bitmap bmp = null;
            
            // INFINITE LOOP (With Safety Exit): Poll until the file is readable
            int attempts = 0;
            while (bmp == null && attempts < 15) { // Try for up to 30 seconds
                try {
                    Thread.sleep(2000);
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inSampleSize = 4; // 4MP is the stability sweet spot
                    bmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                    attempts++;
                } catch (Exception e) {}
            }
            
            if (bmp != null) {
                try {
                    File mirrorFile = new File(currentDcimPath, "MIRROR_" + fileName);
                    FileOutputStream fos = new FileOutputStream(mirrorFile);
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    fos.flush(); fos.close();
                    bmp.recycle();
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mirrorFile)));
                    return true;
                } catch (Exception e) {}
            }
            return false;
        }

        @Override protected void onPostExecute(Boolean success) {
            isBaking = false;
            if (success) {
                tvRecipe.setText("MIRROR SUCCESS!");
                tvRecipe.setTextColor(Color.GREEN);
            } else {
                tvRecipe.setText("BIONZ LOCK TIMEOUT");
                tvRecipe.setTextColor(Color.RED);
            }
            m_handler.postDelayed(new Runnable() {
                @Override public void run() { updateRecipeDisplay(); setDialMode(mDialMode); }
            }, 3000);
        }
    }

    // --- VIEWFIX: KEEP PREVIEW ALIVE ---
    private void reopenCamera() {
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(m_autoReviewControl);
            m_autoReviewControl.setPictureReviewTime(0);
            
            mCameraEx.setShutterSpeedChangeListener(this);
            mCamera.setPreviewDisplay(mSurfaceView.getHolder());
            mCamera.startPreview();
            syncUI();
        } catch (Exception e) {}
    }

    @Override protected void onResume() { super.onResume(); reopenCamera(); sendSonyBroadcast(true); }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            if (speed.first == 1 && speed.second != 1) tvShutter.setText(speed.first + "/" + speed.second);
            else tvShutter.setText(speed.first + "\"");
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvISO.setText("ISO " + pm.getISOSensitivity());
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { exitApp(); return true; }
        if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int d) {
        if (mCameraEx == null || isBaking) return;
        try {
            if (mDialMode == DialMode.shutter) { if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); }
            else if (mDialMode == DialMode.aperture) { if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); }
            else if (mDialMode == DialMode.recipe) { recipeIndex = (recipeIndex + d + recipeList.size()) % recipeList.size(); updateRecipeDisplay(); }
            syncUI();
        } catch (Exception e) {}
    }

    private void cycleMode() { if (isBaking) return; DialMode[] modes = DialMode.values(); int next = (mDialMode.ordinal() + 1) % modes.length; setDialMode(modes[next]); }
    private void setDialMode(DialMode mode) { mDialMode = mode; tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE); tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE); updateRecipeDisplay(); }
    private void scanRecipes() { recipeList.clear(); recipeList.add("NONE (DEFAULT)"); File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS"); if (lutDir.exists()) { File[] files = lutDir.listFiles(); if (files != null) { for (File f : files) { if (!f.getName().startsWith("_") && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName()); } } } updateRecipeDisplay(); }
    private void updateRecipeDisplay() { String name = recipeList.get(recipeIndex); String display = name.split("\\.")[0].toUpperCase(); tvRecipe.setText("<  " + display + "  >"); tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE); }
    private void sendSonyBroadcast(boolean active) { Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive"); intent.putExtra("package_name", getPackageName()); intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{}); sendBroadcast(intent); }
    private void exitApp() { m_handler.removeCallbacksAndMessages(null); Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive"); intent.putExtra("package_name", getPackageName()); intent.putExtra("class_name", getClass().getName()); intent.putExtra("pullingback_key", new String[] {}); intent.putExtra("resume_key", new String[] {}); sendBroadcast(intent); finish(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) { syncUI(); }
    @Override public void surfaceCreated(SurfaceHolder h) { try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI(); } } catch (Exception e) {} }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) { m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime); mCameraEx.release(); } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}