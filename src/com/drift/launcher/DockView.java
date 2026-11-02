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
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The curved bar along the bottom: All Apps, the car (navigation) and Settings,
 * with a blue light along its top edge. Design size 1280 x 126 (sits at y=594).
 */
public class DockView extends View {
    public static final int APPS = 0, CAR = 1, SETTINGS = 2;
    public interface Listener { void onDock(int item); }

    private static final float DW = 1280f;
    private float k = 1f;
    private Palette p = Palette.ALL[0];
    private Listener listener;
    private int pressed = -1;

    private final Path bar = new Path(), edge = new Path();
    private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rr = new RectF();

    public DockView(Context c) {
        super(c);
        edgeLine.setStyle(Paint.Style.STROKE);
        edgeGlow.setStyle(Paint.Style.STROKE);
        edgeGlow.setStrokeCap(Paint.Cap.ROUND);
        sep.setColor(0x2EFFFFFF);
        white.setColor(0xFFFFFFFF);
        cut.setColor(0xFF0B0F15);
        press.setColor(0x14FFFFFF);
        baseLine.setColor(0x12FFFFFF);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setColor(0xF5FFFFFF);
        setClickable(true);
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setPalette(Palette pal) { p = pal; rebuild(); invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        k = w / DW;
        rebuild();
    }

    private void rebuild() {
        float w = getWidth();
        if (w == 0) return;
        float top = 0, r = 64 * k, bottom = 92 * k;
        // tray shape: flat top, big rounded shoulders that run off both screen edges
        bar.reset();
        bar.moveTo(0, top + r * 0.85f);
        bar.cubicTo(0, top + r * 0.3f, r * 0.45f, top, r, top);
        bar.lineTo(w - r, top);
        bar.cubicTo(w - r * 0.45f, top, w, top + r * 0.3f, w, top + r * 0.85f);
        bar.lineTo(w, bottom);
        bar.lineTo(0, bottom);
        bar.close();
        edge.reset();
        edge.moveTo(0, top + r * 0.85f);
        edge.cubicTo(0, top + r * 0.3f, r * 0.45f, top, r, top);
        edge.lineTo(w - r, top);
        edge.cubicTo(w - r * 0.45f, top, w, top + r * 0.3f, w, top + r * 0.85f);

        barFill.setShader(new LinearGradient(0, 0, 0, bottom, 0xFF111720, 0xFF090C12, Shader.TileMode.CLAMP));
        edgeLine.setStrokeWidth(Math.max(1f, k));
        edgeLine.setShader(new LinearGradient(0, 0, w, 0,
                new int[] { Palette.alpha(p.acc, 0xB0), 0x30FFFFFF, Palette.alpha(p.hi, 0xFF), 0x30FFFFFF, Palette.alpha(p.acc, 0xB0) },
                new float[] { 0f, 0.12f, 0.5f, 0.88f, 1f }, Shader.TileMode.CLAMP));
        edgeGlow.setStrokeWidth(6 * k);
        edgeGlow.setShader(new LinearGradient(0, 0, w, 0,
                new int[] { Palette.alpha(p.acc, 0x50), 0, Palette.alpha(p.acc, 0x70), 0, Palette.alpha(p.acc, 0x50) },
                new float[] { 0f, 0.1f, 0.5f, 0.9f, 1f }, Shader.TileMode.CLAMP));

        topHalo.setShader(halo(w / 2f, 0, 300 * k, 14 * k, Palette.alpha(p.acc, 0x80)));
        topCore.setShader(new LinearGradient(w / 2f - 260 * k, 0, w / 2f + 260 * k, 0,
                new int[] { 0, Palette.alpha(p.hi, 0xFF), 0 }, null, Shader.TileMode.CLAMP));
        baseHalo.setShader(halo(641 * k, bottom, 120 * k, 12 * k, Palette.alpha(p.acc, 0x90)));
        baseCore.setShader(new LinearGradient(641 * k - 110 * k, 0, 641 * k + 110 * k, 0,
                new int[] { 0, Palette.alpha(p.hi, 0xFF), 0 }, null, Shader.TileMode.CLAMP));
        label.setTextSize(21.5f * k);
    }

    private static RadialGradient halo(float cx, float cy, float rx, float ry, int col) {
        RadialGradient g = new RadialGradient(0, 0, 1f, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    @Override protected void onDraw(Canvas c) {
        // The dock is part of the artwork; only the press highlight is drawn here.
        if (pressed < 0) return;
        float w = getWidth(), bottom = 92 * k;
        float x0 = pressed == 0 ? 0 : pressed == 1 ? 423 * k : 855 * k, x1 = pressed == 0 ? 423 * k : pressed == 1 ? 855 * k : w;
        c.save(); c.clipPath(bar); c.drawRect(x0, 0, x1, bottom, press); c.restore();
    }

    private int zone(float x) { return x < 423 * k ? APPS : x < 855 * k ? CAR : SETTINGS; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getY() < 0 || e.getY() > 96 * k) return false;
                pressed = zone(e.getX()); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                int z = zone(e.getX());
                if (z == pressed && e.getY() >= 0 && e.getY() <= getHeight() && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onDock(z);
                }
                pressed = -1; invalidate(); return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1; invalidate(); return true;
        }
        return true;
    }
}
