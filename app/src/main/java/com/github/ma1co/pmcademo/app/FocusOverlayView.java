package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.view.View;

public class FocusOverlayView extends View {
    private Paint paint;
    private ScalarWebAPIWrapper scalarWrapper;
    private boolean isPolling = false;

    public FocusOverlayView(Context context) {
        super(context);
        paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6);
        paint.setAntiAlias(true);
        scalarWrapper = new ScalarWebAPIWrapper(context);
    }

    public void startPolling() {
        if (scalarWrapper.isAvailable()) {
            isPolling = true;
            invalidate();
        }
    }

    public void clearBoxes() {
        isPolling = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isPolling || !scalarWrapper.isAvailable()) return;

        try {
            int afStatus = scalarWrapper.getInt("afStatus");
            paint.setColor(afStatus == 1 ? Color.GREEN : Color.YELLOW);

            Camera.Area[] areas = scalarWrapper.getFocusAreas();
            if (areas != null) {
                for (Camera.Area area : areas) {
                    float dLeft = ((area.rect.left + 1000) / 2000f) * getWidth();
                    float dTop = ((area.rect.top + 1000) / 2000f) * getHeight();
                    float dRight = ((area.rect.right + 1000) / 2000f) * getWidth();
                    float dBottom = ((area.rect.bottom + 1000) / 2000f) * getHeight();
                    canvas.drawRect(dLeft, dTop, dRight, dBottom, paint);
                }
            }
        } catch (Exception e) {}
        postInvalidateDelayed(50);
    }
}