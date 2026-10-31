package com.dualstack.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.LinearLayout;

final class DualPaneLayout extends LinearLayout {
    private final Paint shadow = new Paint();
    private LinearGradient light, dark;
    private int edge, spread;

    DualPaneLayout(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        edge = w / 2;
        spread = Ui.dp(getContext(), 5);
        light = new LinearGradient(edge - spread, 0, edge, 0,
                new int[]{0x00000000, 0x04000000, 0x0d000000}, null, Shader.TileMode.CLAMP);
        dark = new LinearGradient(edge - spread, 0, edge, 0,
                new int[]{0x00000000, 0x12000000, 0x30000000}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (light == null) return;
        shadow.setShader(Ui.dark ? dark : light);
        canvas.drawRect(edge - spread, 0, edge, getHeight(), shadow);
    }
}
