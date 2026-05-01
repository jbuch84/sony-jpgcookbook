package com.github.ma1co.pmcademo.app;

import android.hardware.Camera;
import android.util.Pair;
import android.util.Log;
import android.view.SurfaceHolder;

import com.sony.scalar.hardware.CameraEx;

import java.util.List;

public class SonyCameraManager {
    private static final int PREVIEW_MAGNIFICATION_FOCUS_LEVEL = 100;
    private static final int PREVIEW_MAGNIFICATION_MOVE_STEP = 100;
    private static final int PREVIEW_MAGNIFICATION_MAX_COORDINATE = 1000;

    private CameraEx cameraEx;
    private Camera camera;
    private boolean previewMagnificationActive;
    private int previewMagnificationLevel = PREVIEW_MAGNIFICATION_FOCUS_LEVEL;
    private Pair<Integer, Integer> previewMagnificationCoordinates = null;
    
    private String origSceneMode;
    private String origFocusMode;
    private String origWhiteBalance;
    private String origDroMode;
    private String origDroLevel;
    private String origSonyDro;
    private String origContrast;
    private String origSaturation;
    private String origSharpness;
    private String origWbShiftMode;
    private String origWbShiftLb;
    private String origWbShiftCc;
    private String origCreativeStyle;
    private String origColorMode;
    private String origProColorMode;
    private String origPictureEffect;

    public interface CameraEventListener {
        void onCameraReady();
        void onShutterSpeedChanged();
        void onApertureChanged();
        void onIsoChanged();
        void onFocusPositionChanged(float ratio);
        void onFocalLengthChanged(float focalLengthMm); 
        void onHardwareStateChanged(); // <-- MUST BE HERE
    }

    private CameraEventListener listener;

    public SonyCameraManager(CameraEventListener listener) {
        this.listener = listener;
    }

    public Camera getCamera() { 
        return camera; 
    }
    
    public CameraEx getCameraEx() { 
        return cameraEx; 
    }

    public boolean isPreviewMagnificationActive() {
        return previewMagnificationActive;
    }

    // --- NEW: Safe one-time check for Prime Lenses on boot ---
    public float getInitialFocalLength() {
        if (camera != null) {
            try {
                return camera.getParameters().getFocalLength();
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Could not read initial focal length.");
            }
        }
        return 0.0f; // 0.0 indicates a dumb manual lens
    }

    public void open(SurfaceHolder holder) {
        if (cameraEx == null) {
            try {
                cameraEx = CameraEx.open(0, null);
                camera = cameraEx.getNormalCamera();
                
                cameraEx.startDirectShutter();
                CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
                cameraEx.setAutoPictureReviewControl(apr);
                apr.setPictureReviewTime(0);

                if (origSceneMode == null && camera != null) {
                    try {
                        Camera.Parameters p = camera.getParameters();
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
                        origCreativeStyle = p.get("creative-style");
                        origColorMode = p.get("color-mode");
                        origProColorMode = p.get("pro-color-mode");
                        origPictureEffect = p.get("picture-effect");
                    } catch (Exception e) {
                        Log.e("JPEG.CAM", "Failed to backup parameters: " + e.getMessage());
                    }
                }

                setupNativeListeners();
                
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                
                try {
                    Camera.Parameters params = camera.getParameters();
                    CameraEx.ParametersModifier pm = cameraEx.createParametersModifier(params);
                    pm.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
                    camera.setParameters(params);
                } catch(Exception e) {
                    Log.e("JPEG.CAM", "Failed to set drive mode: " + e.getMessage());
                }

                try {
                    Camera.Parameters p = camera.getParameters();
                    SonyCreativeStyleHelper.logDebug("creative-style=" + p.get("creative-style"));
                    SonyCreativeStyleHelper.logDebug("creative-style-values=" + p.get("creative-style-values"));
                    SonyCreativeStyleHelper.logDebug("creative-style-supported=" + p.get("creative-style-supported"));
                    SonyCreativeStyleHelper.logDebug("color-mode=" + p.get("color-mode"));
                    SonyCreativeStyleHelper.logDebug("color-mode-values=" + p.get("color-mode-values"));
                    SonyCreativeStyleHelper.logDebug("color-mode-supported=" + p.get("color-mode-supported"));
                    SonyCreativeStyleHelper.logDebug("pro-color-mode=" + p.get("pro-color-mode"));
                    SonyCreativeStyleHelper.logDebug("picture-effect=" + p.get("picture-effect"));
                    SonyCreativeStyleHelper.logDebug("flattened=" + p.flatten());
                } catch (Exception e) {
                    SonyCreativeStyleHelper.logError("Parameter dump failed: " + e.getMessage(), e);
                }

                if (listener != null) {
                    listener.onCameraReady();
                }
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to open camera: " + e.getMessage());
            }
        }
    }

    public void close() {
        clearPreviewMagnification();

        // 1. First, gently stop any active hardware operations
        if (camera != null) {
            try {
                camera.cancelAutoFocus();
                
                // FIX: We MUST stop the live video stream before modifying final 
                // parameters or releasing the camera. Leaving the DMA pipeline open 
                // when releasing the camera causes a guaranteed kernel panic on BIONZ!
                camera.stopPreview(); 
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to cancel AF or stop preview on close.");
            }
        }

        // 2. Restore the original standard Sony parameters
        if (camera != null && origSceneMode != null) {
            try {
                Camera.Parameters p = camera.getParameters();
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
                if (origCreativeStyle != null) p.set("creative-style", origCreativeStyle);
                if (origColorMode != null) p.set("color-mode", origColorMode);
                if (origProColorMode != null) p.set("pro-color-mode", origProColorMode);
                if (origPictureEffect != null) p.set("picture-effect", origPictureEffect);
                
                camera.setParameters(p);
                Log.d("JPEG.CAM", "Successfully restored standard Sony parameters.");
                
                // Give the BIONZ daemon time to digest these standard settings before calling release().
                Thread.sleep(300);
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to restore parameters: " + e.getMessage());
            }
        }
        
        // 3. Safely release the hardware
        if (cameraEx != null) {
            try {
                cameraEx.release();
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Error releasing CameraEx: " + e.getMessage());
            }
            cameraEx = null;
            camera = null;
        }
    }

    public boolean togglePreviewMagnification() {
        if (cameraEx == null) {
            return false;
        }

        try {
            if (previewMagnificationActive) {
                stopPreviewMagnificationInternal();
                previewMagnificationActive = false;
                previewMagnificationCoordinates = null;
                SonyCreativeStyleHelper.logDebug("Preview magnification OFF");
                return false;
            }

            Pair<Integer, Integer> target = previewMagnificationCoordinates;
            if (target == null) {
                target = Pair.create(0, 0);
            }
            setPreviewMagnificationInternal(PREVIEW_MAGNIFICATION_FOCUS_LEVEL, target);
            previewMagnificationActive = true;
            previewMagnificationLevel = PREVIEW_MAGNIFICATION_FOCUS_LEVEL;
            previewMagnificationCoordinates = target;
            SonyCreativeStyleHelper.logDebug("Preview magnification ON level="
                    + PREVIEW_MAGNIFICATION_FOCUS_LEVEL
                    + " x=" + target.first
                    + " y=" + target.second);
            return true;
        } catch (Exception e) {
            previewMagnificationActive = false;
            SonyCreativeStyleHelper.logError("Preview magnification toggle failed: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean movePreviewMagnification(int dx, int dy) {
        if (cameraEx == null || !previewMagnificationActive) {
            return false;
        }

        Pair<Integer, Integer> current = previewMagnificationCoordinates;
        if (current == null) {
            current = Pair.create(0, 0);
        }

        Pair<Integer, Integer> next = Pair.create(
                clampPreviewMagnificationCoordinate(current.first + (dx * PREVIEW_MAGNIFICATION_MOVE_STEP)),
                clampPreviewMagnificationCoordinate(current.second + (dy * PREVIEW_MAGNIFICATION_MOVE_STEP)));

        try {
            setPreviewMagnificationInternal(previewMagnificationLevel, next);
            previewMagnificationCoordinates = next;
            SonyCreativeStyleHelper.logDebug("Preview magnification move level="
                    + previewMagnificationLevel
                    + " x=" + next.first
                    + " y=" + next.second);
            return true;
        } catch (Exception e) {
            SonyCreativeStyleHelper.logError("Preview magnification move failed: " + e.getMessage(), e);
            return false;
        }
    }

    public void clearPreviewMagnification() {
        if (cameraEx == null || !previewMagnificationActive) {
            return;
        }

        try {
            stopPreviewMagnificationInternal();
        } catch (Exception e) {
            SonyCreativeStyleHelper.logError("Preview magnification stop failed: " + e.getMessage(), e);
        } finally {
            previewMagnificationActive = false;
            previewMagnificationCoordinates = null;
        }
    }

    public boolean jumpToPreviewMagnification(int x, int y) {
        if (cameraEx == null || !previewMagnificationActive) {
            return false;
        }

        Pair<Integer, Integer> next = Pair.create(
                clampPreviewMagnificationCoordinate(x),
                clampPreviewMagnificationCoordinate(y));

        try {
            setPreviewMagnificationInternal(previewMagnificationLevel, next);
            previewMagnificationCoordinates = next;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int clampPreviewMagnificationCoordinate(int value) {
        if (value > PREVIEW_MAGNIFICATION_MAX_COORDINATE) {
            return PREVIEW_MAGNIFICATION_MAX_COORDINATE;
        }
        if (value < -PREVIEW_MAGNIFICATION_MAX_COORDINATE) {
            return -PREVIEW_MAGNIFICATION_MAX_COORDINATE;
        }
        return value;
    }

    private void setPreviewMagnificationInternal(int level, Pair<Integer, Integer> coordinates) throws Exception {
        cameraEx.getClass()
                .getMethod("setPreviewMagnification", Integer.TYPE, Pair.class)
                .invoke(cameraEx, level, coordinates);
    }

    private void stopPreviewMagnificationInternal() throws Exception {
        cameraEx.getClass()
                .getMethod("stopPreviewMagnification")
                .invoke(cameraEx);
    }

    private void setupNativeListeners() {
        cameraEx.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override 
            public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) {
                if (listener != null) listener.onShutterSpeedChanged();
            }
        });

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$ApertureChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onApertureChange") && listener != null) {
                            listener.onApertureChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setApertureChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onChanged") && listener != null) {
                            listener.onIsoChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setAutoISOSensitivityListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
                        if (m.getName().equals("onChanged") && a != null && a.length == 2) {
                            Object pos = a[0];
                            int cur = pos.getClass().getField("currentPosition").getInt(pos);
                            int max = pos.getClass().getField("maxPosition").getInt(pos);
                            if (max > 0 && listener != null) {
                                listener.onFocusPositionChanged((float) cur / max);
                            }
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setFocusDriveListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocalLengthChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onFocalLengthChanged") && a.length > 0) {
                            if (listener != null) {
                                // Divide hardware's 10x value by 10 to get standard mm (e.g. 250 -> 25.0)
                                int focal10x = (Integer) a[0];
                                listener.onFocalLengthChanged(focal10x / 10.0f);
                            }
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setFocalLengthChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$SettingChangedListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onChanged") && listener != null) {
                            listener.onHardwareStateChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setSettingChangedListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$PreviewMagnificationListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if ("onChanged".equals(m.getName()) && a != null && a.length >= 4) {
                            if (a.length >= 3 && a[2] instanceof Integer) {
                                previewMagnificationLevel = ((Integer) a[2]).intValue();
                            }
                            if (a[3] instanceof Pair) {
                                @SuppressWarnings("unchecked")
                                Pair<Integer, Integer> coords = (Pair<Integer, Integer>) a[3];
                                previewMagnificationCoordinates = coords;
                            }
                        } else if ("onInfoUpdated".equals(m.getName()) && a != null && a.length >= 2) {
                            if (a[1] instanceof Pair) {
                                @SuppressWarnings("unchecked")
                                Pair<Integer, Integer> coords = (Pair<Integer, Integer>) a[1];
                                previewMagnificationCoordinates = coords;
                            }
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setPreviewMagnificationListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }
    }
}
