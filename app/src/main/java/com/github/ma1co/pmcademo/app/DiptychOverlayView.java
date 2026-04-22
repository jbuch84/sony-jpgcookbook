package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;

public class DiptychOverlayView extends View {
    private Paint linePaint;
    private Paint thumbPaint;
    private Bitmap thumbnail;
    private boolean thumbOnLeft = true;
    private int state = 0; // 0: Need Shot 1, 1: Need Shot 2

    public DiptychOverlayView(Context context) {
        super(context);
        
        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(2);
        
        thumbPaint = new Paint();
        thumbPaint.setAlpha(140); // ~55% opacity so you can still see through it
    }

    public void setState(int state) {
        this.state = state;
        if (state == 0) {
            if (thumbnail != null && !thumbnail.isRecycled()) {
                thumbnail.recycle();
            }
            thumbnail = null;
            thumbOnLeft = true;
        }
        invalidate();
    }

    public void setThumbnail(Bitmap thumb) {
        this.thumbnail = thumb;
        invalidate();
    }

    public void setThumbOnLeft(boolean onLeft) {
        this.thumbOnLeft = onLeft;
        invalidate();
    }

    public boolean isThumbOnLeft() {
        return thumbOnLeft;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int mid = w / 2;

        if (state == 1 && thumbnail != null && !thumbnail.isRecycled()) {
            int tW = thumbnail.getWidth();
            int tH = thumbnail.getHeight();
            int tMid = tW / 2;
            
            Rect srcRect;
            Rect dstRect;
            
            if (thumbOnLeft) {
                srcRect = new Rect(0, 0, tMid, tH);
                dstRect = new Rect(0, 0, mid, h);
            } else {
                srcRect = new Rect(tMid, 0, tW, tH);
                dstRect = new Rect(mid, 0, w, h);
            }
            
            canvas.drawBitmap(thumbnail, srcRect, dstRect, thumbPaint);
        }
        
        // Always draw the center framing line
        canvas.drawLine(mid, 0, mid, h, linePaint);
    }
}