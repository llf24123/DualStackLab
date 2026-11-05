package com.dualstack.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The toolbar-coloured elastic edge belongs to one pane, not the whole screen. */
final class PullEdgeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path edge = new Path();
    private float depth;

    PullEdgeView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
    }

    void setDepth(float value) {
        depth = Math.max(0f, value);
        invalidate();
    }

    float depth() { return depth; }

    @Override protected void onDraw(Canvas canvas) {
        if (depth <= 0f) return;
        paint.setColor(Ui.INK);
        edge.rewind();
        edge.moveTo(0f, 0f);
        edge.quadTo(getWidth() / 2f, depth * 2f, getWidth(), 0f);
        edge.close();
        canvas.drawPath(edge, paint);
    }
}
