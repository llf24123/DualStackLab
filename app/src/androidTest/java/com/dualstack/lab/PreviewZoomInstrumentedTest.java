package com.dualstack.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreviewZoomInstrumentedTest {
    @Test public void zoomStaysWithinFitAndMaximumAndKeepsImageOnScreen() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ZoomImageView view = new ZoomImageView(context);
            FrameLayout host = new FrameLayout(context); host.addView(view, new FrameLayout.LayoutParams(400, 800));
            host.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            host.layout(0, 0, 400, 800);
            view.setImageBitmap(Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888));
            assertEquals(1f, view.zoomFactor(), .001f);
            RectF fitted = bounds(view);
            assertEquals(0f, fitted.left, .5f); assertEquals(400f, fitted.right, .5f);
            assertEquals("Fitted image is centred vertically", 400f, fitted.centerY(), .5f);
            view.zoom(.5f, 200, 400);
            assertEquals("Cannot shrink below the fitted size", 1f, view.zoomFactor(), .001f);
            view.zoom(100f, 0, 0);
            assertEquals(ZoomImageView.MAX_ZOOM, view.zoomFactor(), .001f);
            RectF zoomed = bounds(view);
            assertTrue("Zoomed image must cover the view horizontally", zoomed.left <= 0 && zoomed.right >= 400);
            view.reset();
            assertEquals(1f, view.zoomFactor(), .001f);
        });
    }
    private static RectF bounds(ZoomImageView view) {
        RectF rect = new RectF(0, 0, view.getDrawable().getIntrinsicWidth(), view.getDrawable().getIntrinsicHeight());
        new Matrix(view.getImageMatrix()).mapRect(rect); return rect;
    }
}
