package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.IOException;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private boolean isPaused = false;

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
        
        tvRecipe.setText("RECIPE: DEFAULT");
        tvRecipe.setTextColor(Color.WHITE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        // SAFE INIT: We open the camera, but we don't touch settings yet
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            sendSonyBroadcast(true);
        } catch (Exception e) {
            tvRecipe.setText("CAMERA ERROR");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();

        // DELETE: Immediate Safe Exit
        if (scanCode == ScalarInput.ISV_KEY_DELETE) {
            sendSonyBroadcast(false);
            finish();
            return true;
        }

        // MENU/ENTER: Launch Native Sony Controls (Avoids Custom UI Crashes)
        if (scanCode == ScalarInput.ISV_KEY_MENU || scanCode == ScalarInput.ISV_KEY_ENTER) {
            try {
                startActivity(new Intent("com.sony.scalar.app.setting.SETTING"));
            } catch (Exception e) {}
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (mCamera != null && !isPaused) {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();
                syncDisplay();
            }
        } catch (Exception e) {}
    }

    private void syncDisplay() {
        // Safe UI Update: No complex loops to trigger the watchdog
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            int iso = pm.getISOSensitivity();
            tvISO.setText("ISO: " + (iso == 0 ? "AUTO" : iso));
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvExposure.setText("EV: " + (p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPaused = true;
        if (mCameraEx != null) {
            mCamera.stopPreview();
            mCameraEx.release();
            mCameraEx = null;
        }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}