/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;

/**
 * Shown when a song has no cover: a vinyl record in the accent colour. It spins up while music
 * plays and coasts to a stop on pause, with a fixed light sheen over the grooves.
 * One instance per place it's drawn (home card, player), since each keeps its own spin.
 */
final class Vinyl {
    private float angle, v, t;
    private long last;
    private int acc;
    private float cachedR = -1;
    private RadialGradient label, shadow;
    private SweepGradient sheen;
    private final Matrix m = new Matrix();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF o = new RectF();
    private final Path clip = new Path();

    Vinyl() { s.setStyle(Paint.Style.STROKE); s.setStrokeCap(Paint.Cap.ROUND); }

    private static int a(int c, float al) { return (Math.round(Math.max(0, Math.min(1, al)) * 255) << 24) | (c & 0x00FFFFFF); }

    /** Draws into r. Returns true while it still needs frames (spinning or slowing down). */
    boolean draw(Canvas c, RectF r, float radius, int accent, boolean playing) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float target = playing ? 1f : 0f;
        v += (target - v) * Math.min(1f, dt * (playing ? 2.2f : 1.4f));
        if (!playing && v < 0.003f) v = 0;
        angle = (angle + v * dt * 360f * 0.42f) % 360f;
        t += dt;

        float W = r.width(), H = r.height(), R = Math.min(W, H) * 0.44f;
        if (accent != acc || Math.abs(R - cachedR) > 0.5f) build(accent, R);

        c.save();
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.translate(r.centerX(), r.centerY());
        // backdrop: dark glass with an accent bloom that breathes while playing
        p.setShader(null); p.setColor(0xFF0B1018);
        c.drawRect(-W / 2, -H / 2, W / 2, H / 2, p);
        float bloom = 0.10f + 0.10f * v * (0.6f + 0.4f * (float) Math.sin(t * 2.4f));
        p.setShader(new RadialGradient(0, 0, Math.max(W, H) * 0.75f, a(acc, bloom), a(acc, 0f), Shader.TileMode.CLAMP));
        c.drawRect(-W / 2, -H / 2, W / 2, H / 2, p);
        // soft shadow under the record
        p.setShader(shadow);
        c.save(); c.translate(0, R * 0.06f); c.drawCircle(0, 0, R * 1.15f, p); c.restore();
        // record body and grooves
        p.setShader(null); p.setColor(0xFF06080C);
        c.drawCircle(0, 0, R, p);
        s.setShader(null);
        s.setStrokeWidth(Math.max(0.6f, R / 90f));
        for (int i = 0; i < 26; i++) {
            float gr = R * (0.40f + 0.57f * i / 25f);
            s.setColor(i % 7 == 3 ? 0x03FFFFFF : (i % 2 == 1 ? 0x0BFFFFFF : 0x06FFFFFF));
            c.drawCircle(0, 0, gr, s);
        }
        // fixed light sheen across the grooves (the light doesn't turn, the record does)
        s.setShader(sheen);
        s.setStrokeWidth(R * 0.59f);
        c.drawCircle(0, 0, R * 0.675f, s);
        s.setShader(null);
        // rim light
        s.setColor(a(acc, 0.45f)); s.setStrokeWidth(Math.max(1f, R / 70f));
        c.drawCircle(0, 0, R, s);

        // the turning label
        c.save();
        c.rotate(angle);
        float L = R * 0.34f;
        p.setShader(label);
        c.drawCircle(0, 0, L, p);
        p.setShader(null);
        s.setStrokeWidth(Math.max(1f, L / 14f));
        o.set(-L * 0.68f, -L * 0.68f, L * 0.68f, L * 0.68f);
        s.setColor(0x8CFFFFFF); c.drawArc(o, -51.6f, 80.2f, false, s);
        s.setColor(0x47FFFFFF); c.drawArc(o, 131.8f, 45.8f, false, s);
        s.setColor(0x2E000000); s.setStrokeWidth(Math.max(0.8f, L / 30f));
        c.drawCircle(0, 0, L * 0.82f, s);
        // a glint on the outer grooves that travels with the record
        o.set(-R * 0.86f, -R * 0.86f, R * 0.86f, R * 0.86f);
        s.setColor(a(acc, 0.55f * (0.35f + 0.65f * v))); s.setStrokeWidth(Math.max(1f, R / 45f));
        c.drawArc(o, 11.5f, 31.5f, false, s);
        c.restore();

        // spindle
        p.setColor(0xFF0B1018); c.drawCircle(0, 0, R * 0.045f, p);
        s.setColor(0x59FFFFFF); s.setStrokeWidth(1f); c.drawCircle(0, 0, R * 0.045f, s);
        c.restore();
        return playing || v > 0;
    }

    private void build(int accent, float R) {
        acc = accent; cachedR = R;
        float L = R * 0.34f;
        label = new RadialGradient(-L * 0.3f, -L * 0.3f, L * 1.3f,
                new int[] { 0x8CFFFFFF, acc | 0xFF000000, a(acc, 0.55f) }, new float[] { 0f, 0.25f, 1f }, Shader.TileMode.CLAMP);
        shadow = new RadialGradient(0, 0, R * 1.15f, new int[] { 0x99000000, 0x99000000, 0x00000000 }, new float[] { 0f, 0.82f, 1f }, Shader.TileMode.CLAMP);
        sheen = new SweepGradient(0, 0,
                new int[] { 0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF, a(acc, 0.28f), 0x00FFFFFF, 0x00FFFFFF },
                new float[] { 0f, 0.06f, 0.13f, 0.5f, 0.56f, 0.63f, 1f });
        m.setRotate(-135f);
        sheen.setLocalMatrix(m);
    }
}
