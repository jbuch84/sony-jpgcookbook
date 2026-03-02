package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.IOException;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener {
    private CameraEx mCameraEx;
    private SurfaceHolder mSurfaceHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        SurfaceView surfaceView = new SurfaceView(this);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        
        // SONY FIX 1: BetterManual forces this for a5100 sensor handoff
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        setContentView(surfaceView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // SONY FIX 2: Open and start direct shutter immediately
        mCameraEx = CameraEx.open(0, null);
        mCameraEx.setShutterListener(this);
        mCameraEx.startDirectShutter();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCameraEx != null) {
            mCameraEx.getNormalCamera().stopPreview();
            mCameraEx.release();
            mCameraEx = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // TRASH BUTTON: Map to finish so you can exit the app safely
        if (keyCode == ScalarInput.ISV_KEY_DELETE || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (mCameraEx != null) {
                // SONY FIX 3: Pull the normal camera instance from the Sony wrapper
                Camera cam = mCameraEx.getNormalCamera();
                cam.setPreviewDisplay(holder);
                cam.startPreview();
            }
        } catch (IOException e) {
            // Handle initialization failure
        }
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {
        // This is where we will trigger the "Baking" process once a photo is taken
        // i: 0 = success
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}
}