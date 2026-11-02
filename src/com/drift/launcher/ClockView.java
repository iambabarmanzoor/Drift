/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The big clock and the date between the two rules (the rules are in the artwork).
 * Placed over the full 1280 x 720 canvas so coordinates match the reference exactly.
 */
public class ClockView extends View {
    private static final float DW = 1280f;
    private float k = 1f;
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint date = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String sTime = "", sDate = "", flash = null;
    private float dxTime, dxDate; // design units; non-zero when the clock sits on the left

    private final Runnable endFlash = new Runnable() {
        @Override public void run() { flash = null; invalidate(); }
    };

    public ClockView(Context c) {
        super(c);
        digits.setTypeface(Fonts.light(c));
        digits.setColor(0xFFFFFFFF);
        digits.setTextAlign(Paint.Align.CENTER);
        date.setTypeface(Fonts.regular(c));
        date.setColor(0xFFE4E7EC);
        date.setLetterSpacing(0.114f);
        date.setTextAlign(Paint.Align.CENTER);
    }

    public void setPalette(Palette pal) { }
    /** Clock on the left (music card on the right): mirror the clock and date with the artwork. */
    public void setLeftAligned(boolean b) { dxTime = b ? -867f : 0f; dxDate = b ? -865f : 0f; invalidate(); }
    public void setBreathing(boolean on) { }

    public void flash(String text) {
        flash = text;
        removeCallbacks(endFlash);
        postDelayed(endFlash, 2200);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0) return;
        k = w / DW;
        digits.setTextSize(115.07f * k);
        date.setTextSize(18.49f * k);
    }

    public void tick() {
        Date now = new Date();
        Locale L = Locale.getDefault();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        sTime = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", L).format(now);
        sDate = new SimpleDateFormat("EEE, d MMM", L).format(now).toUpperCase(L).replace(".", "");
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(sTime, (1073.5f + dxTime) * k, 189 * k, digits);
        String raw = flash != null ? flash : sDate;
        // keep the line between the two rules in the artwork (988 .. 1154)
        CharSequence d = TextUtils.ellipsize(raw, date, 156 * k, TextUtils.TruncateAt.END);
        c.drawText(d, 0, d.length(), (1072.5f + dxDate) * k, 226.5f * k, date);
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(endFlash); super.onDetachedFromWindow(); }
}
