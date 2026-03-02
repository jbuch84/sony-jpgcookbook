package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceHolder mSurfaceHolder;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private List<Integer> supportedIsos;
    private int curIso;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    
    enum DialMode { shutter, aperture, iso, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void scanRecipes() {
        recipeList.clear();
        recipeList.add("NONE (DEFAULT)");

        File lutDir = new File("/sdcard/LUTS");
        if (lutDir.exists() && lutDir.isDirectory()) {
            File[] files = lutDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    // FILTER JUNK: Skip macOS hidden files (starting with _ or .)
                    if (name.startsWith("_") || name.startsWith(".")) continue;
                    
                    String upper = name.toUpperCase();
                    if (upper.contains("CUB") || upper.contains("PNG")) {
                        recipeList.add(name);
                    }
                }
            }
        }
        updateRecipeDisplay();
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        String display = name;
        
        // Strip file extension for the UI (e.g., ASH.CUBE -> ASH)
        int dot = name.lastIndexOf('.');
        if (dot > 0) display = name.substring(0, dot);
        
        tvRecipe.setText("<  " + display.toUpperCase() + "  >");
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.setShutterSpeedChangeListener(this);
            mCameraEx.startDirectShutter();
            
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(mCamera.getParameters());
            supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
            curIso = pm.getISOSensitivity();

            notifySonyStatus(true);
            syncUI();
        } catch (Exception e) {}
    }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            // Format Shutter Speed (e.g. 1/4000) using BetterManual logic
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            if (speed.first == 1 && speed.second != 2 && speed.second != 1) {
                tvShutter.setText(speed.first + "/" + speed.second);
            } else if (speed.second == 1) {
                tvShutter.setText(speed.first + "\"");
            } else {
                tvShutter.setText(String.format("%.1f\"", (float)speed.first / (float)speed.second));
            }
            
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvISO.setText(curIso == 0 ? "ISO AUTO" : "ISO " + curIso);
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { notifySonyStatus(false); finish(); return true; }
        if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int delta) {
        if (mCameraEx == null) return;
        try {
            if (mDialMode == DialMode.shutter) {
                if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
            } else if (mDialMode == DialMode.aperture) {
                if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
            } else if (mDialMode == DialMode.iso) {
                int idx = supportedIsos.indexOf(curIso);
                int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
                curIso = supportedIsos.get(next);
                Camera.Parameters p = mCamera.getParameters();
                mCameraEx.createParametersModifier(p).setISOSensitivity(curIso);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + delta + recipeList.size()) % recipeList.size();
                updateRecipeDisplay();
            }
            syncUI();
        } catch (Exception e) {}
    }

    private void cycleMode() {
        if (mDialMode == DialMode.shutter) setDialMode(DialMode.aperture);
        else if (mDialMode == DialMode.aperture) setDialMode(DialMode.iso);
        else if (mDialMode == DialMode.iso) setDialMode(DialMode.recipe);
        else setDialMode(DialMode.shutter);
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvRecipe.setTextColor(mode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    @Override
    public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) {
        syncUI();
    }

    private void notifySonyStatus(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI(); } } catch (Exception e) {}
    }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}