/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Shortcut content (icon + label) drawn over the glass tiles in the artwork.
 * Design size 188.75 x 132.75; icon 61 px centred 53.5 px from the top, label baseline at 118.
 */
public class TileView extends View {
    private static final float DH = 132.75f;
    public final int index;
    private AppRepo.App app;
    private Bitmap icon;
    private float k = 1f;
    private boolean down;

    private final RectF r = new RectF(), dst = new RectF();
    private final Paint pressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public TileView(Context c, int index, boolean unusedWide) {
        super(c);
        this.index = index;
        plus.setColor(0x80FFFFFF);
        pressed.setColor(0x14FFFFFF);
        label.setTypeface(Fonts.regular(c));
        label.setLetterSpacing(-0.054f);
        label.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setLongClickable(true);
        setHapticFeedbackEnabled(true);
    }

    public AppRepo.App app() { return app; }

    public void setPalette(Palette pal) { invalidate(); }

    public void bind(AppRepo.App a, Bitmap ic, int color) {
        app = a; icon = ic;
        setContentDescription(a == null ? "Empty shortcut" : a.label);
        invalidate();
    }

    public void setIcon(Bitmap ic, int color) { icon = ic; invalidate(); }

    static String guessCategory(AppRepo.App a) {
        String s = (a.cn.getPackageName() + " " + a.label).toLowerCase(Locale.ROOT);
        if (s.contains("map") || s.contains("navi") || s.contains("waze") || s.contains("gps")) return "NAVIGATION";
        if (s.contains("music") || s.contains("spotify") || s.contains("player") || s.contains("audio") || s.contains("poweramp")) return "MUSIC";
        if (s.contains("radio") || s.contains(".fm") || s.contains("tuner")) return "RADIO";
        if (s.contains("phone") || s.contains("dial") || s.contains("bluetooth") || s.contains(".bt")) return "PHONE";
        if (s.contains("youtube") || s.contains("video") || s.contains("netflix")) return "VIDEO";
        if (s.contains("setting")) return "SETTINGS";
        return "APP";
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (h == 0) return;
        k = h / DH;
        r.set(0, 0, w, h);
        label.setTextSize(20.55f * k);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        if (down) c.drawRoundRect(r, 15 * k, 15 * k, pressed);
        if (app == null) {
            Icons.plus(c, w / 2f, 53.5f * k, 28 * k, plus);
            label.setColor(0x80FFFFFF);
            c.drawText("Add", w / 2f, 118 * k, label);
            return;
        }
        float s = 61 * k;
        if (icon != null) {
            dst.set(w / 2 - s / 2, 53.5f * k - s / 2, w / 2 + s / 2, 53.5f * k + s / 2);
            c.drawBitmap(icon, null, dst, bmp);
        }
        label.setColor(0xFFFFFFFF);
        CharSequence t = TextUtils.ellipsize(app.label, label, w - 24 * k, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), w / 2f, 118 * k, label);
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
