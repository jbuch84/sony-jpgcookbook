package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class DiptychOverlayView extends View {
    private Paint maskPaint;
    private Paint linePaint;
    private int state = 0; // 0: Need Left, 1: Need Right

    public DiptychOverlayView(Context context) {
        super(context);
        maskPaint = new Paint();
        maskPaint.setColor(Color.argb(180, 0, 0, 0)); // Semi-transparent black
        
        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(2);
    }

    public void setState(int state) {
        this.state = state;
        invalidate(); // Redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int mid = w / 2;

        if (state == 0) {
            // Mask the Right side while framing Left
            canvas.drawRect(mid, 0, w, h, maskPaint);
        } else {
            // Mask the Left side (or leave clear) while framing Right
            canvas.drawRect(0, 0, mid, h, maskPaint);
        }
        
        // Draw the center split line
        canvas.drawLine(mid, 0, mid, h, linePaint);
    }
}