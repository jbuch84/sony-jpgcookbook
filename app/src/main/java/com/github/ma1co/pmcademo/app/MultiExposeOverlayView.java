package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class MultiExposeOverlayView extends View {
    private Paint thumbPaint;
    private List<Bitmap> thumbnails = new ArrayList<Bitmap>();

    public MultiExposeOverlayView(Context context) {
        super(context);
        thumbPaint = new Paint();
        thumbPaint.setFilterBitmap(true);
    }

    public void addThumbnail(Bitmap thumb) {
        if (thumb != null) {
            thumbnails.add(thumb);
            invalidate();
        }
    }

    public void clearThumbnails() {
        for (Bitmap b : thumbnails) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        thumbnails.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (thumbnails.isEmpty()) return;

        int w = getWidth();
        int h = getHeight();
        Rect dst = new Rect(0, 0, w, h);

        // Calculate opacity based on stack depth to avoid "white blowout"
        // Shot 1 is 50% opacity, Shot 2 is 33%, etc.
        for (int i = 0; i < thumbnails.size(); i++) {
            Bitmap b = thumbnails.get(i);
            if (b != null && !b.isRecycled()) {
                int alpha = 255 / (i + 2);
                thumbPaint.setAlpha(alpha);
                canvas.drawBitmap(b, null, dst, thumbPaint);
            }
        }
    }
}
