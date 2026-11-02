/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Moving light on the bottom bar: every 4.2 s a streak leaves the centre and runs out to both
 * ends along the bar's curved top edge, while the glows above and below the speed breathe.
 * Design area 1280 x 150 at y=560. Does not take touches.
 */
public class DockGlowView extends View {
    private static final float OY = 560f, DW = 1280f, PERIOD = 4.2f;
    // the bar's top edge, measured from the artwork: distance from the nearest screen edge -> y
    private static final float[][] EDGE = { {0, 631}, {8, 623}, {16, 617}, {24, 612}, {32, 608}, {40, 604}, {48, 602},
            {64, 598}, {80, 596}, {100, 595}, {300, 594}, {520, 593}, {640, 592} };

    private float k = 1f;
    private boolean on = true, running;
    private final long t0 = SystemClock.uptimeMillis();
    private int glowCol = 0xFF46AAFF, coreCol = 0xFFCDF0FF;

    private final Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG), thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG), top = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient gWide, gThin;
    private final Matrix m = new Matrix();
    private final Path path = new Path();

    public DockGlowView(Context c) {
        super(c);
        wide.setStyle(Paint.Style.STROKE); wide.setStrokeCap(Paint.Cap.ROUND);
        thin.setStyle(Paint.Style.STROKE); thin.setStrokeCap(Paint.Cap.ROUND);
        build();
    }

    public void setAccent(Accent a) { glowCol = a.glow(); coreCol = a.core(); build(); invalidate(); }

    public void setOn(boolean b) { on = b; if (b) start(); else { stop(); invalidate(); } }

    public void start() { if (on && !running) { running = true; invalidate(); } }

    public void stop() { running = false; }

    public boolean isActive() { return running && on; }

    private static int clear(int c) { return c & 0x00FFFFFF; }

    private void build() {
        gWide = new LinearGradient(0, 0, 1f, 0, new int[] { clear(glowCol), glowCol, clear(glowCol) }, null, Shader.TileMode.CLAMP);
        gThin = new LinearGradient(0, 0, 1f, 0, new int[] { clear(coreCol), coreCol, clear(coreCol) }, null, Shader.TileMode.CLAMP);
        wide.setShader(gWide);
        thin.setShader(gThin);
        halo.setShader(new RadialGradient(0, 0, 1f, glowCol, clear(glowCol), Shader.TileMode.CLAMP));
        base.setShader(new RadialGradient(0, 0, 1f, glowCol, clear(glowCol), Shader.TileMode.CLAMP));
        top.setShader(new RadialGradient(0, 0, 1f, coreCol, clear(glowCol), Shader.TileMode.CLAMP));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0) k = w / DW; }

    private static float edgeY(float x) {
        float d = Math.min(x, 1280f - x);
        for (int i = 1; i < EDGE.length; i++) {
            if (d <= EDGE[i][0]) {
                float[] a = EDGE[i - 1], b = EDGE[i];
                return a[1] + (b[1] - a[1]) * (d - a[0]) / (b[0] - a[0]);
            }
        }
        return 592f;
    }

    private void spot(Canvas c, float x, float a, float len) {
        float x0 = Math.max(0, x - len), x1 = Math.min(1280f, x + len);
        path.reset();
        boolean first = true;
        for (float X = x0; ; X += 6f) {
            if (X > x1) X = x1;
            float Y = edgeY(X) - OY;
            if (first) { path.moveTo(X * k, Y * k); first = false; } else path.lineTo(X * k, Y * k);
            if (X >= x1) break;
        }
        m.setScale(2 * len * k, 1f);
        m.postTranslate((x - len) * k, 0);
        gWide.setLocalMatrix(m);
        gThin.setLocalMatrix(m);
        wide.setShader(gWide); // re-set: on Android 7 the paint keeps the old matrix otherwise
        thin.setShader(gThin);
        wide.setStrokeWidth(9f * k); wide.setAlpha(Math.round(255 * 0.35f * a));
        thin.setStrokeWidth(2f * k); thin.setAlpha(Math.round(255 * a));
        c.drawPath(path, wide);
        c.drawPath(path, thin);
        halo.setAlpha(Math.round(255 * 0.25f * a));
        c.save();
        c.translate(x * k, (edgeY(x) - OY) * k);
        c.scale(60f * k, 60f * k);
        c.drawCircle(0, 0, 1f, halo);
        c.restore();
    }

    private void ellipse(Canvas c, Paint p, float cx, float cy, float rx, float ry, float alpha) {
        p.setAlpha(Math.round(255 * alpha));
        c.save();
        c.translate(cx * k, (cy - OY) * k);
        c.scale(rx * k, ry * k);
        c.drawCircle(0, 0, 1f, p);
        c.restore();
    }

    @Override protected void onDraw(Canvas c) {
        if (!on || !running) return;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float ph = (t % PERIOD) / PERIOD;
        if (ph < 0.8f) {
            float e = ph / 0.8f, dx = 640f * (float) Math.pow(e, 1.25);
            float a = e < 0.08f ? e / 0.08f : e > 0.7f ? (1f - e) / 0.3f : 1f;
            spot(c, 640f + dx, 0.9f * a, 110f);
            spot(c, 640f - dx, 0.9f * a, 110f);
        }
        float lvl = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / PERIOD + Math.PI / 2);
        ellipse(c, base, 640f, 684f, 120f, 120f * 0.18f, 0.35f * lvl);
        ellipse(c, top, 640f, 592f, 140f, 140f * 0.12f, 0.3f * lvl);
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
