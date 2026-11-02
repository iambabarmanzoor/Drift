/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Deep navy-black backdrop: a soft lift behind the road and darker swoosh shapes in the top corners. Static. */
public class BgView extends View {
    private final Paint lift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerL = new Paint(Paint.ANTI_ALIAS_FLAG), cornerR = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BgView(Context c) { super(c); }

    public void setPalette(Palette p) { }
    public void setMotion(boolean on) { }
    public void start() { }
    public void stop() { }

    private static RadialGradient ellipse(float cx, float cy, float rx, float ry, int inner) {
        RadialGradient g = new RadialGradient(0, 0, 1f, new int[] { inner, inner & 0x00FFFFFF }, new float[] { 0.35f, 1f }, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        float k = Math.min(w / 1280f, h / 720f);
        lift.setShader(ellipse(w / 2f, 230 * k, 680 * k, 440 * k, 0xFF060E1A));
        cornerL.setShader(ellipse(120 * k, -60 * k, 470 * k, 210 * k, 0xFF000103));
        cornerR.setShader(ellipse(w - 100 * k, -60 * k, 430 * k, 190 * k, 0xFF000103));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Palette.BG);
        float w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, lift);
        c.drawRect(0, 0, w, h, cornerL);
        c.drawRect(0, 0, w, h, cornerR);
    }
}
