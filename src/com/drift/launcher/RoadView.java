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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Road motion drawn over the artwork: light running down both lane lines (the car is moving
 * forward) and the tail lights slowly breathing. Design area 400 x 560 at x=440, y=40.
 * Launcher's single ~30 fps ticker redraws it, only while the home screen is in front.
 */
public class RoadView extends View {
    public static final int OFF = 0, CRUISE = 1, MOTORWAY = 2;
    private static final float OX = 440f, OY = 40f, DW = 400f;

    private float k = 1f;
    private int mode = CRUISE;
    private boolean running;
    private final long t0 = SystemClock.uptimeMillis();
    private int glowCol = 0xFF46AAFF, coreCol = 0xFFCDF0FF;

    private final Paint[] pass = { new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG) };
    private final LinearGradient[] grad = new LinearGradient[3];
    private final Matrix m = new Matrix();
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float[] WIDTH = { 16f, 6f, 2.2f }, ALPHA = { 0.22f, 0.55f, 1f };

    public RoadView(Context c) {
        super(c);
        for (Paint p : pass) { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        lamp.setShader(new RadialGradient(0, 0, 1f, 0xFFFF3C32, 0x00FF1E1E, Shader.TileMode.CLAMP));
        trail.setShader(new LinearGradient(0, 0, 0, 1f, 0xFFFF2828, 0x00FF2828, Shader.TileMode.CLAMP));
        buildShaders();
    }

    public void setAccent(Accent a) { glowCol = a.glow(); coreCol = a.core(); buildShaders(); invalidate(); }

    public void setMode(int m) { mode = m; if (m == OFF) { stop(); invalidate(); } else start(); }

    public void start() { if (mode != OFF && !running) { running = true; invalidate(); } }

    public void stop() { running = false; }

    public boolean isActive() { return running && mode != OFF; }

    private void buildShaders() {
        for (int i = 0; i < 3; i++) {
            int c = i == 2 ? coreCol : glowCol;
            grad[i] = new LinearGradient(0, 0, 0, 1f, new int[] { c & 0x00FFFFFF, c, c & 0x00FFFFFF },
                    new float[] { 0f, 0.75f, 1f }, Shader.TileMode.CLAMP);
            pass[i].setShader(grad[i]);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0) k = w / DW; }

    private static float laneX(float y, int side) { return side < 0 ? 545f - (y - 60f) * 0.165f : 737f + (y - 60f) * 0.165f; }

    /** One light streak; ph runs 0..1 from the far end of the lane to the near end. */
    private void pulse(Canvas c, int side, float ph) {
        float e = (float) Math.pow(ph, 1.5), y = 60f + e * 430f, len = 26f + e * 80f, y0 = y - len;
        float a = ph < 0.12f ? ph / 0.12f : ph > 0.85f ? (1f - ph) / 0.15f : 1f;
        if (a <= 0) return;
        float x1 = (laneX(y0, side) - OX) * k, x2 = (laneX(y, side) - OX) * k;
        float Y0 = (y0 - OY) * k, Y1 = (y - OY) * k;
        for (int i = 0; i < 3; i++) {
            m.setScale(1f, Y1 - Y0);
            m.postTranslate(0, Y0);
            grad[i].setLocalMatrix(m);
            pass[i].setShader(grad[i]); // re-set: on Android 7 the paint keeps the old matrix otherwise
            pass[i].setAlpha(Math.round(255 * ALPHA[i] * a));
            pass[i].setStrokeWidth(WIDTH[i] * (0.6f + e * 0.6f) * k);
            c.drawLine(x1, Y0, x2, Y1, pass[i]);
        }
    }

    private void tail(Canvas c, float gx, float gy, float lvl) {
        float cx = (gx - OX) * k, cy = (gy - OY) * k;
        lamp.setAlpha(Math.round(255 * 0.55f * lvl));
        c.save();
        c.translate(cx, cy);
        c.scale(34f * 1.5f * k, 34f * 0.7f * k);
        c.drawCircle(0, 0, 1f, lamp);
        c.restore();
        trail.setAlpha(Math.round(255 * 0.28f * lvl));
        c.save();
        c.translate(cx - 14f * k, cy + 10f * k);
        c.scale(28f * k, 70f * k);
        c.drawRect(0, 0, 1f, 1f, trail);
        c.restore();
    }

    @Override protected void onDraw(Canvas c) {
        if (mode == OFF || !running) return;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float per = mode == MOTORWAY ? 0.75f : 1.5f;
        for (int i = 0; i < 2; i++) {
            float ph = ((t / per) + i * 0.5f) % 1f;
            pulse(c, -1, ph);
            pulse(c, 1, (ph + 0.25f) % 1f);
        }
        float lvl = 0.35f + 0.35f * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 2.6));
        tail(c, 588f, 452f, lvl);
        tail(c, 692f, 452f, lvl);
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
