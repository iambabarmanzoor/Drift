/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Appearance: a panel on the right of the home screen, so every change is visible behind it
 * straight away. Colour, music card side, road motion and dock glow.
 * The panel is 452 x 688 at x=812, y=16 on the design canvas; taps outside it reach the home screen.
 */
public class AppearancePanel extends HomeLayout {
    private final SettingsPanel.Host host;
    private final Card card;
    private boolean open;

    public AppearancePanel(Context c, SettingsPanel.Host host) {
        super(c);
        this.host = host;
        setVisibility(GONE);
        card = new Card(c);
        add(card, 812, 16, 452, 688);
    }

    public boolean isOpen() { return open; }

    /** With the music card on the right, the panel sits on the left so the card stays visible. */
    public void setOnLeft(boolean left) { onLeft = left; move(card, left ? 16 : 812, 16, 452, 688); }
    private boolean onLeft;

    public void setAccent(Accent a) { card.acc = a.glow(); card.invalidate(); }

    public void refresh() { card.invalidate(); }

    public void show() {
        open = true;
        setVisibility(VISIBLE);
        card.setAlpha(0f);
        card.setTranslationX((onLeft ? -30 : 30) * scale());
        card.animate().alpha(1f).translationX(0).setDuration(200).withLayer().start();
        card.invalidate();
    }

    public void close() {
        if (!open) return;
        open = false;
        card.animate().alpha(0f).translationX((onLeft ? -30 : 30) * scale()).setDuration(150).withLayer().withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }

    // design coordinates inside the 452 x 688 card
    private static final float SEG_X = 32, SEG_W = 388, SEG_H = 56;
    private static final float Y_MOTION = 378, Y_GLOW = 492, Y_SIDE = 606;
    private static final int T_NONE = -1, T_BACK = 100, T_CLOSE = 101;

    private class Card extends View {
        int acc = 0xFF46AAFF;
        private float k = 1f;
        private int down = T_NONE;
        private final RectF r = new RectF();
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), topLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segBg = new Paint(Paint.ANTI_ALIAS_FLAG), segLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selFill = new Paint(Paint.ANTI_ALIAS_FLAG), selLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), press = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG), xbg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG), sec = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segText = new Paint(Paint.ANTI_ALIAS_FLAG), name = new Paint(Paint.ANTI_ALIAS_FLAG);

        Card(Context c) {
            super(c);
            bg.setColor(0xF0080C12);
            line.setStyle(Paint.Style.STROKE); line.setColor(0x17FFFFFF);
            segBg.setColor(0x0DFFFFFF);
            segLine.setStyle(Paint.Style.STROKE); segLine.setColor(0x14FFFFFF);
            selLine.setStyle(Paint.Style.STROKE);
            ring.setStyle(Paint.Style.STROKE); ring.setColor(0xFFFFFFFF);
            press.setColor(0x1AFFFFFF);
            icon.setColor(0xFFFFFFFF); icon.setStyle(Paint.Style.STROKE); icon.setStrokeCap(Paint.Cap.ROUND);
            xbg.setColor(0x12FFFFFF);
            head.setTypeface(Fonts.light(c)); head.setColor(0xFFFFFFFF);
            sec.setTypeface(Fonts.semibold(c)); sec.setColor(0xFF8A93A3); sec.setLetterSpacing(0.22f);
            segText.setTypeface(Fonts.regular(c)); segText.setTextAlign(Paint.Align.CENTER);
            name.setTypeface(Fonts.regular(c)); name.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setHapticFeedbackEnabled(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0) k = w / 452f; }

        private float swX(int i) { return 24 + (i % 4) * 101 + 50.5f; }
        private float swY(int i) { return 136 + (i / 4) * 96 + 29; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(k / 2, k / 2, w - k / 2, h - k / 2);
            c.drawRoundRect(r, 24 * k, 24 * k, bg);
            line.setStrokeWidth(Math.max(1f, k));
            c.drawRoundRect(r, 24 * k, 24 * k, line);
            topLine.setShader(new LinearGradient(40 * k, 0, w - 40 * k, 0, new int[] { acc & 0x00FFFFFF, (acc & 0x00FFFFFF) | 0xCC000000, acc & 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
            c.drawRect(40 * k, 0, w - 40 * k, Math.max(1f, k), topLine);

            // header: back, title, close
            if (down == T_BACK) c.drawCircle(48 * k, 52 * k, 24 * k, press);
            icon.setStrokeWidth(2.4f * k);
            c.drawLine(53 * k, 43 * k, 44 * k, 52 * k, icon);
            c.drawLine(44 * k, 52 * k, 53 * k, 61 * k, icon);
            head.setTextSize(30 * k);
            c.drawText("Appearance", 84 * k, (34 + 28) * k, head);
            xbg.setColor(down == T_CLOSE ? 0x24FFFFFF : 0x12FFFFFF);
            c.drawCircle(400 * k, 52 * k, 28 * k, xbg);
            icon.setStrokeWidth(2.2f * k);
            float d = 7.8f * k;
            c.drawLine(400 * k - d, 52 * k - d, 400 * k + d, 52 * k + d, icon);
            c.drawLine(400 * k - d, 52 * k + d, 400 * k + d, 52 * k - d, icon);

            // colours
            sec.setTextSize(11.5f * k);
            c.drawText("COLOUR", 32 * k, 121 * k, sec);
            int cur = host.accent();
            name.setTextSize(13 * k);
            for (int i = 0; i < Accent.ALL.length; i++) {
                float cx = swX(i) * k, cy = swY(i) * k;
                swatch.setColor(Accent.ALL[i].shift(0xFF46AAFF));
                c.drawCircle(cx, cy, 26 * k, swatch);
                if (i == cur) { ring.setStrokeWidth(2 * k); c.drawCircle(cx, cy, 30 * k, ring); }
                if (down == i) c.drawCircle(cx, cy, 30 * k, press);
                name.setColor(i == cur ? 0xFFFFFFFF : 0xFF9AA3B2);
                c.drawText(Accent.ALL[i].name, cx, cy + (29 + 25) * k, name);
            }

            segment(c, "ROAD MOTION", Y_MOTION, new String[] { "Off", "Follow speed", "Always" }, host.roadMode(), 10);
            segment(c, "DOCK GLOW", Y_GLOW, new String[] { "Off", "On" }, host.dockGlow() ? 1 : 0, 20);
            segment(c, "MUSIC CARD", Y_SIDE, new String[] { "Left", "Right" }, host.cardRight() ? 1 : 0, 30);
        }

        private void segment(Canvas c, String title, float y, String[] items, int sel, int idBase) {
            c.drawText(title, 32 * k, (y - 15) * k, sec);
            r.set(SEG_X * k, y * k, (SEG_X + SEG_W) * k, (y + SEG_H) * k);
            c.drawRoundRect(r, 16 * k, 16 * k, segBg);
            segLine.setStrokeWidth(Math.max(1f, k));
            c.drawRoundRect(r, 16 * k, 16 * k, segLine);
            float iw = (SEG_W - 8) / items.length;
            segText.setTextSize(17 * k);
            for (int i = 0; i < items.length; i++) {
                float x0 = SEG_X + 4 + i * iw;
                r.set(x0 * k, (y + 4) * k, (x0 + iw) * k, (y + SEG_H - 4) * k);
                if (i == sel) {
                    selFill.setColor((acc & 0x00FFFFFF) | 0x38000000);
                    c.drawRoundRect(r, 12 * k, 12 * k, selFill);
                    selLine.setColor((acc & 0x00FFFFFF) | 0x99000000);
                    selLine.setStrokeWidth(Math.max(1f, k));
                    c.drawRoundRect(r, 12 * k, 12 * k, selLine);
                } else if (down == idBase + i) c.drawRoundRect(r, 12 * k, 12 * k, press);
                segText.setColor(i == sel ? 0xFFFFFFFF : 0xFF9AA3B2);
                c.drawText(items[i], r.centerX(), r.centerY() + 6 * k, segText);
            }
        }

        private int hit(float px, float py) {
            float x = px / k, y = py / k;
            if (dist(x, y, 48, 52) < 30) return T_BACK;
            if (dist(x, y, 400, 52) < 32) return T_CLOSE;
            for (int i = 0; i < Accent.ALL.length; i++) if (dist(x, y, swX(i), swY(i)) < 40) return i;
            int s = segHit(x, y, Y_MOTION, 3); if (s >= 0) return 10 + s;
            s = segHit(x, y, Y_GLOW, 2); if (s >= 0) return 20 + s;
            s = segHit(x, y, Y_SIDE, 2); if (s >= 0) return 30 + s;
            return T_NONE;
        }

        private int segHit(float x, float y, float top, int n) {
            if (y < top - 6 || y > top + SEG_H + 6 || x < SEG_X || x > SEG_X + SEG_W) return -1;
            return Math.min(n - 1, (int) ((x - SEG_X - 4) / ((SEG_W - 8) / n)));
        }

        private float dist(float x, float y, float cx, float cy) { float dx = x - cx, dy = y - cy; return (float) Math.sqrt(dx * dx + dy * dy); }

        private void act(int t) {
            if (t == T_BACK) { host.openSettings(); return; }
            if (t == T_CLOSE) { host.closePanels(); return; }
            if (t >= 0 && t < Accent.ALL.length) host.setAccent(t);
            else if (t >= 10 && t < 13) host.setRoadMode(t - 10);
            else if (t == 20 || t == 21) host.setDockGlow(t == 21);
            else if (t == 30 || t == 31) host.setCardRight(t == 31);
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down = hit(e.getX(), e.getY()); invalidate(); return true;
                case MotionEvent.ACTION_UP: {
                    int t = hit(e.getX(), e.getY());
                    int was = down;
                    down = T_NONE; invalidate();
                    if (t == was && t != T_NONE) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); act(t); }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: down = T_NONE; invalidate(); return true;
            }
            return true;
        }
    }
}
