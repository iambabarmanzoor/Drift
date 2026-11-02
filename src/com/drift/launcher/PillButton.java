/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Bottom-bar controls: APPS (grid + label), THEME (accent dot), SETTINGS (outline gear). Design height 84. */
public class PillButton extends View {
    public static final int APPS = 0, THEME = 1, SETTINGS = 2;
    private static final float DH = 84f;
    private final int kind;
    private Palette p = Palette.ALL[0];
    private float k = 1f;
    private boolean down;
    private final Glass glass = new Glass();
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PillButton(Context c, int kind) {
        super(c);
        this.kind = kind;
        white.setColor(Palette.TEXT);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Palette.TEXT);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(0x10FFFFFF);
        pressed.setColor(0x0FFFFFFF);
        hole.setColor(Palette.SURFACE);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setColor(Palette.TEXT);
        setClickable(true);
        setLongClickable(kind == THEME);
        setContentDescription(kind == APPS ? "All apps" : kind == THEME ? "Change accent colour" : "Drift settings");
    }

    public void setPalette(Palette pal) { p = pal; accent.setColor(pal.acc); invalidate(); }

    public void setBadge(String b) { }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (h == 0) return;
        k = h / DH;
        glass.layout(w, h, k, 18);
        stroke.setStrokeWidth(1.6f * k);
        label.setTextSize(17 * k);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), cy = getHeight() / 2f;
        glass.draw(c);
        if (down) c.drawRoundRect(glass.r, glass.radius, glass.radius, pressed);
        if (kind == APPS) {
            float tw = label.measureText("All apps"), total = 22 * k + 12 * k + tw;
            float x0 = (w - total) / 2f;
            Icons.grid(c, x0 + 11 * k, cy, 22 * k, white);
            c.drawText("All apps", x0 + 34 * k, cy + 6 * k, label);
        } else if (kind == THEME) {
            c.drawCircle(w / 2f, cy, 13 * k, ring);
            c.drawCircle(w / 2f, cy, 9 * k, accent);
        } else {
            Icons.gear(c, w / 2f, cy, 30 * k, white, hole);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: down = true; invalidate(); break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: down = false; invalidate(); break;
        }
        return super.onTouchEvent(e);
    }
}
