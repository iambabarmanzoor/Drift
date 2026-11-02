/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Tiny vector icons drawn straight onto a canvas. No image assets needed. */
public final class Icons {
    private static final Path P = new Path();
    private static final RectF R = new RectF();

    /** Centered at cx,cy, fits in a box of size s. */
    public static void play(Canvas c, float cx, float cy, float s, Paint p) {
        P.reset();
        P.moveTo(cx - s * 0.32f, cy - s * 0.42f);
        P.lineTo(cx + s * 0.46f, cy);
        P.lineTo(cx - s * 0.32f, cy + s * 0.42f);
        P.close();
        c.drawPath(P, p);
    }

    public static void pause(Canvas c, float cx, float cy, float s, Paint p) {
        float bw = s * 0.24f, bh = s * 0.8f, r = bw * 0.35f;
        R.set(cx - s * 0.34f, cy - bh / 2, cx - s * 0.34f + bw, cy + bh / 2); c.drawRoundRect(R, r, r, p);
        R.set(cx + s * 0.10f, cy - bh / 2, cx + s * 0.10f + bw, cy + bh / 2); c.drawRoundRect(R, r, r, p);
    }

    public static void skip(Canvas c, float cx, float cy, float s, Paint p, boolean next) {
        float d = next ? 1 : -1;
        P.reset();
        P.moveTo(cx - d * s * 0.42f, cy - s * 0.38f);
        P.lineTo(cx + d * s * 0.22f, cy);
        P.lineTo(cx - d * s * 0.42f, cy + s * 0.38f);
        P.close();
        c.drawPath(P, p);
        float bx = cx + d * s * 0.30f, bw = s * 0.13f;
        R.set(Math.min(bx, bx + d * bw), cy - s * 0.38f, Math.max(bx, bx + d * bw), cy + s * 0.38f);
        c.drawRoundRect(R, bw / 3, bw / 3, p);
    }

    public static void grid(Canvas c, float cx, float cy, float s, Paint p) {
        float g = s * 0.36f, r = s * 0.11f;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * g, cy + j * g, r, p);
    }

    public static void gear(Canvas c, float cx, float cy, float s, Paint fill, Paint hole) {
        float ro = s * 0.40f, tooth = s * 0.13f;
        c.drawCircle(cx, cy, ro, fill);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            R.set(cx - tooth / 1.6f, cy - ro - tooth, cx + tooth / 1.6f, cy - ro + tooth);
            c.drawRoundRect(R, tooth / 3, tooth / 3, fill);
            c.restore();
        }
        c.drawCircle(cx, cy, s * 0.16f, hole);
    }

    public static void plus(Canvas c, float cx, float cy, float s, Paint p) {
        float t = s * 0.09f, l = s * 0.42f;
        R.set(cx - l, cy - t, cx + l, cy + t); c.drawRoundRect(R, t, t, p);
        R.set(cx - t, cy - l, cx + t, cy + l); c.drawRoundRect(R, t, t, p);
    }

    public static void close(Canvas c, float cx, float cy, float s, Paint p) {
        c.save(); c.rotate(45, cx, cy); plus(c, cx, cy, s, p); c.restore();
    }

    public static void note(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx - s * 0.18f, cy + s * 0.26f, s * 0.17f, p);
        R.set(cx - s * 0.04f, cy - s * 0.40f, cx + s * 0.06f, cy + s * 0.28f); c.drawRect(R, p);
        P.reset();
        P.moveTo(cx - s * 0.04f, cy - s * 0.40f);
        P.lineTo(cx + s * 0.36f, cy - s * 0.26f);
        P.lineTo(cx + s * 0.36f, cy - s * 0.10f);
        P.lineTo(cx + s * 0.06f, cy - s * 0.22f);
        P.close();
        c.drawPath(P, p);
    }

    /** Four-point sparkle, used on the theme button. */
    public static void sparkle(Canvas c, float cx, float cy, float s, Paint p) {
        float a = s * 0.5f, b = s * 0.12f;
        P.reset();
        P.moveTo(cx, cy - a);
        P.quadTo(cx + b * 0.4f, cy - b * 0.4f, cx + a, cy);
        P.quadTo(cx + b * 0.4f, cy + b * 0.4f, cx, cy + a);
        P.quadTo(cx - b * 0.4f, cy + b * 0.4f, cx - a, cy);
        P.quadTo(cx - b * 0.4f, cy - b * 0.4f, cx, cy - a);
        P.close();
        c.drawPath(P, p);
    }
}
