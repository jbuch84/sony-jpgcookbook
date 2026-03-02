package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;
    
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    
    private final String SONY_PATH = "/sdcard/DCIM/100MSDCF";
    private HashSet<String> knownFiles = new HashSet<String>();
    private Handler m_handler = new Handler();
    private boolean isBaking = false;

    enum DialMode { shutter, aperture, iso, exposure, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        initializeFileLibrary();
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void initializeFileLibrary() {
        File dir = new File(SONY_PATH);
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
                    File dir = new File(SONY_PATH);
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName();
                            if (name.toUpperCase().endsWith(".JPG") && !name.startsWith("COOKED_") && !knownFiles.contains(name)) {
                                knownFiles.add(name);
                                new BakeTask(name).execute();
                                break; 
                            }
                        }
                    }
                }
                m_handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private class CubeLUT {
        int size = 0;
        float[] data;
        CubeLUT(File file) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line; int idx = 0;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("LUT_3D_SIZE")) {
                        size = Integer.parseInt(line.split("\\s+")[1]);
                        data = new float[size * size * size * 3];
                    } else if (line.length() > 0 && Character.isDigit(line.charAt(0))) {
                        String[] p = line.split("\\s+");
                        if (p.length >= 3) {
                            data[idx++] = Float.parseFloat(p[0]);
                            data[idx++] = Float.parseFloat(p[1]);
                            data[idx++] = Float.parseFloat(p[2]);
                        }
                    }
                }
                br.close();
            } catch (Exception e) {}
        }
        int mapColor(int color) {
            if (size == 0) return color;
            float r = (Color.red(color) / 255.0f) * (size - 1);
            float g = (Color.green(color) / 255.0f) * (size - 1);
            float b = (Color.blue(color) / 255.0f) * (size - 1);
            int offset = ((int)r + size * ((int)g + size * (int)b)) * 3;
            return Color.rgb((int)(data[offset]*255), (int)(data[offset+1]*255), (int)(data[offset+2]*255));
        }
    }

    private class BakeTask extends AsyncTask<Void, String, Boolean> {
        String fileName;
        BakeTask(String name) { this.fileName = name; }
        @Override protected void onPreExecute() { 
            isBaking = true;
            tvRecipe.setText("SLICING & BAKING...");
            tvRecipe.setTextColor(Color.RED);
        }
        
        @Override protected Boolean doInBackground(Void... voids) {
            try {
                File lutFile = new File("/sdcard/LUTS/" + recipeList.get(recipeIndex));
                CubeLUT lut = new CubeLUT(lutFile);
                File original = new File(SONY_PATH, fileName);
                
                // THE SLICER: Use RegionDecoder to avoid loading full image into RAM
                BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(original.getAbsolutePath(), false);
                int width = decoder.getWidth();
                int height = decoder.getHeight();
                
                Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(output);
                
                int tileHeight = 500; // Small strips to keep RAM low
                for (int y = 0; y < height; y += tileHeight) {
                    int currentTileHeight = Math.min(tileHeight, height - y);
                    Rect rect = new Rect(0, y, width, y + currentTileHeight);
                    Bitmap tile = decoder.decodeRegion(rect, null).copy(Bitmap.Config.ARGB_8888, true);
                    
                    int[] pixels = new int[tile.getWidth() * tile.getHeight()];
                    tile.getPixels(pixels, 0, tile.getWidth(), 0, 0, tile.getWidth(), tile.getHeight());
                    for (int i = 0; i < pixels.length; i++) pixels[i] = lut.mapColor(pixels[i]);
                    tile.setPixels(pixels, 0, tile.getWidth(), 0, 0, tile.getWidth(), tile.getHeight());
                    
                    canvas.drawBitmap(tile, 0, y, null);
                    tile.recycle(); // Free RAM immediately
                }
                
                File cooked = new File(SONY_PATH, "COOKED_" + fileName);
                FileOutputStream fos = new FileOutputStream(cooked);
                output.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
                output.recycle();
                
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(cooked)));
                return true;
            } catch (Exception e) { return false; }
        }
        
        @Override protected void onPostExecute(Boolean success) {
            isBaking = false;
            updateRecipeDisplay();
            setDialMode(mDialMode);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(m_autoReviewControl);
            m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
            m_autoReviewControl.setPictureReviewTime(0);
            mCameraEx.setShutterSpeedChangeListener(this);
            sendSonyBroadcast(true); 
            syncUI();
        } catch (Exception e) {}
    }

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { exitApp(); return true; }
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
        else if (mDialMode == DialMode.iso) setDialMode(DialMode.exposure);
        else if (mDialMode == DialMode.exposure) setDialMode(DialMode.recipe);
        else setDialMode(DialMode.shutter);
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvExposure.setTextColor(mode == DialMode.exposure ? Color.GREEN : Color.WHITE);
        updateRecipeDisplay();
    }

    private void scanRecipes() {
        recipeList.clear();
        recipeList.add("NONE (DEFAULT)");
        File lutDir = new File("/sdcard/LUTS");
        if (lutDir.exists()) {
            File[] files = lutDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().startsWith("_") && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
                }
            }
        }
        updateRecipeDisplay();
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        String display = name.split("\\.")[0].toUpperCase();
        tvRecipe.setText("<  " + display + "  >");
        tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    private void exitApp() {
        m_handler.removeCallbacksAndMessages(null);
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("class_name", getClass().getName());
        intent.putExtra("pullingback_key", new String[] {});
        intent.putExtra("resume_key", new String[] {});
        sendBroadcast(intent);
        finish();
    }

    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) { syncUI(); }
    @Override public void surfaceCreated(SurfaceHolder h) { try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI(); } } catch (Exception e) {} }
    @Override protected void onPause() { super.onPause(); m_handler.removeCallbacksAndMessages(null); if (mCameraEx != null) { m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime); mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}