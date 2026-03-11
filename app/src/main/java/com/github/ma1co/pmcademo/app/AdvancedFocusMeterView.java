package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * filmOS UI: Advanced Focus Meter
 * Renders a cinematic distance scale with dynamic DOF calculation and live plot points.
 */
public class AdvancedFocusMeterView extends View {
    private Paint trackPaint, needlePaint, dofPaint, markPaint, liveTextPaint, rulerTextPaint, bgPaint, infoTextPaint;
    
    private LensMath.GaugeState currentState = null;
    private float currentRatio = 0.5f;
    private float currentAperture = 2.8f;
    private float currentFocalLength = 50.0f;
    private double currentCocMm = 0.020; // Stores the active sensor size
    private boolean isCalibrating = false;
    
    // Holds the dynamic calibration points to draw the dots
    private List<LensProfileManager.CalPoint> calPoints = new ArrayList<LensProfileManager.CalPoint>();

    public AdvancedFocusMeterView(Context context) {
        super(context);
        
        trackPaint = new Paint(); 
        trackPaint.setColor(Color.argb(150, 100, 100, 100)); 
        trackPaint.setStrokeWidth(4);
        
        dofPaint = new Paint(); 
        dofPaint.setColor(Color.argb(180, 230, 50, 15)); // filmOS Orange
        dofPaint.setStrokeWidth(12);
        dofPaint.setStrokeCap(Paint.Cap.ROUND);
        
        needlePaint = new Paint(); 
        needlePaint.setColor(Color.WHITE); 
        needlePaint.setStrokeWidth(6);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        
        markPaint = new Paint();
        markPaint.setColor(Color.rgb(200, 200, 200));
        markPaint.setAntiAlias(true);

        liveTextPaint = new Paint();
        liveTextPaint.setColor(Color.WHITE);
        liveTextPaint.setTextSize(22);
        liveTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        liveTextPaint.setAntiAlias(true);
        liveTextPaint.setTextAlign(Paint.Align.CENTER);
        liveTextPaint.setShadowLayer(4, 0, 0, Color.BLACK);

        rulerTextPaint = new Paint();
        rulerTextPaint.setColor(Color.LTGRAY);
        rulerTextPaint.setTextSize(16);
        rulerTextPaint.setAntiAlias(true);
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        rulerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        bgPaint = new Paint();
        bgPaint.setColor(Color.DKGRAY);
        bgPaint.setStrokeWidth(4);
        
        // Setup for the Telemetry readout (Top Left)
        infoTextPaint = new Paint();
        infoTextPaint.setColor(Color.LTGRAY);
        infoTextPaint.setTextSize(20);
        infoTextPaint.setAntiAlias(true);
        infoTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    // Feeds the view the UI dots AND the math result
    public void update(float ratio, float aperture, float focalLength, double cocMm, boolean isCalibrating, List<LensProfileManager.CalPoint> points) {
        this.currentRatio = ratio;
        this.currentAperture = aperture;
        this.currentFocalLength = focalLength;
        this.currentCocMm = cocMm; // Cache the sensor size for the UI
        this.isCalibrating = isCalibrating;
        this.calPoints = points;
        
        // --- CRITICAL FIX: DO NOT RUN MATH WHILE CALIBRATING ---
        // We only build the Gauge State if calibration is completely finished!
        if (!isCalibrating && points != null && points.size() >= 2) {
            currentState = LensMath.buildGaugeState(ratio, aperture, focalLength, points, currentCocMm);
        } else {
            currentState = null; // Forces the UI to hide the H-mark and orange bar
        }
        
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        
        int pad = 40;
        int trackW = w - (pad * 2);
        int y = h / 2 + 20; // Shifted down slightly to make room for text
        
        // 0. Draw Track Background
        canvas.drawLine(pad, y, w - pad, y, bgPaint);
        
        // --- 1. THE DYNAMIC DOF BAND ---
        if (currentState != null && currentState.nearMotor != null) {
            float nearX = pad + (trackW * currentState.nearMotor.floatValue());
            float farX = pad + trackW; // Default to Infinity edge
            if (currentState.farMotor != null && currentState.farMotor < 1.0) {
                farX = pad + (trackW * currentState.farMotor.floatValue());
            }
            // Draw the organically breathing orange rectangle!
            canvas.drawRect(Math.min(nearX, farX), y - 10, Math.max(nearX, farX), y + 10, dofPaint);
        }

        // --- 2. THE RULER & TICKS ---
        if (calPoints != null) {
            for (LensProfileManager.CalPoint pt : calPoints) {
                float markX = pad + (trackW * pt.ratio);
                canvas.drawCircle(markX, y, 5, markPaint);
                
                if (rulerTextPaint != null) {
                    if (pt.distance > 0f && pt.distance < 999.0f) {
                        float totalInches = pt.distance * 39.3701f;
                        int ft = (int) (totalInches / 12);
                        canvas.drawText(ft + "'", markX, y - 16, rulerTextPaint); 
                        canvas.drawText(String.format("%.1fm", pt.distance), markX, y + 28, rulerTextPaint); 
                    } else if (pt.distance >= 999.0f) {
                        canvas.drawText("INF", markX, y + 28, rulerTextPaint);
                    }
                }
            }
        }
        
        // --- 3. THE HYPERFOCAL INDICATOR ---
        if (currentState != null && currentState.hyperMotor != null) {
            float hX = pad + (trackW * currentState.hyperMotor.floatValue());
            if (rulerTextPaint != null) {
                canvas.drawText("[H]", hX, y - 30, rulerTextPaint); // Mark H on the track
            }
        }

        // --- 4. DRAW THE CURRENT FOCUS NEEDLE ---
        float currentX = pad + (trackW * currentRatio);
        canvas.drawCircle(currentX, y, 12, needlePaint);

        // --- 5. THE LIVE TEXT ENGINE (Clamped to Hyperfocal!) ---
        if (currentState != null) {
            String distStr;
            
            if (currentState.focusDist >= currentState.hyperfocalDist) {
                distStr = String.format("INF (H: %.1fm)", currentState.hyperfocalDist);
            } else if (currentState.focusDist >= 999.0) {
                distStr = "INFINITY";
            } else {
                float totalInches = (float) currentState.focusDist * 39.3701f;
                int ft = (int) (totalInches / 12);
                int in = (int) (totalInches % 12);
                distStr = String.format("%.2fm / %d'%d\"", currentState.focusDist, ft, in);
            }
            
            canvas.drawText(distStr, w / 2, y - 70, liveTextPaint);
        } else if (isCalibrating) {
            canvas.drawText("MAPPING LENS...", w / 2, y - 70, liveTextPaint);
        } else {
            canvas.drawText("UNMAPPED LENS", w / 2, y - 70, liveTextPaint);
        }
        
        // --- 6. THE TELEMETRY OVERLAY (Diagnostics) ---
        // Will output something like: "25mm | APS-C"
        String sensorType = (currentCocMm >= 0.029) ? "FULL FRAME" : "APS-C";
        String telemetryStr = String.format("%.0fmm | %s", currentFocalLength, sensorType);
        canvas.drawText(telemetryStr, pad, y - 70, infoTextPaint);
    }
}