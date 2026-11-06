package com.dualstack.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Image preview with pinch zoom (up to 8x of the fitted size), panning and double-tap toggle. */
final class ZoomImageView extends ImageView {
    static final float MAX_ZOOM = 8f;
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private float fit = 1f;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent first, MotionEvent event, float dx, float dy) {
                matrix.postTranslate(-dx, -dy); clamp(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (zoomFactor() > 1.05f) reset(); else zoom(2.5f, event.getX(), event.getY());
                return true;
            }
        });
    }
    /** Current scale relative to the fitted size; 1 means the whole image is visible. */
    float zoomFactor() { matrix.getValues(values); return values[Matrix.MSCALE_X] / fit; }
    void zoom(float factor, float x, float y) {
        float now = zoomFactor(), target = Math.max(1f, Math.min(MAX_ZOOM, now * factor));
        matrix.postScale(target / now, target / now, x, y); clamp();
    }
    void reset() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
        fit = Math.min(getWidth() / width, getHeight() / height);
        matrix.setScale(fit, fit);
        matrix.postTranslate((getWidth() - width * fit) / 2f, (getHeight() - height * fit) / 2f);
        setImageMatrix(matrix);
    }
    private void clamp() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(bounds);
        matrix.postTranslate(offset(bounds.left, bounds.right, getWidth()), offset(bounds.top, bounds.bottom, getHeight()));
        setImageMatrix(matrix);
    }
    /** Centres an axis smaller than the view, otherwise keeps the image covering the view edge to edge. */
    private static float offset(float start, float end, float size) {
        if (end - start <= size) return (size - (end - start)) / 2f - start;
        if (start > 0) return -start;
        return end < size ? size - end : 0;
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); reset(); }
    @Override public void setImageBitmap(Bitmap bitmap) { super.setImageBitmap(bitmap); reset(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
        scale.onTouchEvent(event); gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
}
