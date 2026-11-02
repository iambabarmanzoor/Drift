/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** The shared surface: flat graphite fill with a hairline border. No glow, no gradient. */
public final class Glass {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final RectF r = new RectF();
    public float radius;

    public Glass() {
        fill.setColor(Palette.SURFACE);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Palette.LINE);
    }

    public void layout(float w, float h, float k, float radiusDesign) {
        float sw = Math.max(1f, k);
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        radius = radiusDesign * k;
        border.setStrokeWidth(sw);
    }

    public void draw(Canvas c) {
        c.drawRoundRect(r, radius, radius, fill);
        c.drawRoundRect(r, radius, radius, border);
    }

    public void drawBorderOnly(Canvas c) { c.drawRoundRect(r, radius, radius, border); }
}
