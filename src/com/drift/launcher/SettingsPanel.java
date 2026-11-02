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
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Drift settings: a full screen of 8 big tiles in the home screen's style. Every tile shows
 * its current state; most change with one tap. Appearance opens a side panel (AppearancePanel)
 * so colour and motion changes can be seen on the home screen as you make them.
 * Laid out on the 1280 x 720 design canvas.
 */
public class SettingsPanel extends HomeLayout {
    /** What the launcher exposes to the settings screens. */
    public interface Host {
        int accent();              void setAccent(int index);
        int roadMode();            void setRoadMode(int mode);
        boolean dockGlow();        void setDockGlow(boolean on);
        boolean cardRight();       void setCardRight(boolean right);
        boolean mph();             void setMph(boolean mph);
        boolean speedAllowed();    void allowSpeed();
        int permissionsOn();       String permissionsMissing();    void openPermissions();
        String versionName();      String updateHint();            boolean updateReady();     void updateTap();
        String musicApp();         void chooseMusicApp();
        boolean musicAccess();     void openMusicAccess();
        void openDiagnostics();
        void openBluetooth();
        boolean recentsOn();       void openRecentsAccess();
        void resetShortcuts();
        void openAndroidSettings();
        void openAppearance();     void openSettings();
        void closePanels();
    }

    private final Host host;
    private final Tile[] tiles = new Tile[8];
    private boolean open;
    private final Paint bgFill = new Paint(), bgGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), foot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int acc = 0xFF46AAFF;
    private int glowFor, glowW, glowH;

    public SettingsPanel(Context c, Host host) {
        super(c);
        this.host = host;
        setWillNotDraw(false);
        setVisibility(GONE);
        setClickable(true); // nothing underneath reacts while settings are open
        bgFill.setColor(0xF6050810);
        title.setTypeface(Fonts.light(c));
        title.setColor(0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        foot.setTypeface(Fonts.semibold(c));
        foot.setColor(0xFF5E6676);
        foot.setLetterSpacing(0.28f);

        Pill android = new Pill(c, "Android settings");
        android.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { SettingsPanel.this.host.openAndroidSettings(); } });
        add(android, 938, 40, 214, 44);
        CloseButton x = new CloseButton(c);
        x.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { SettingsPanel.this.host.closePanels(); } });
        add(x, 1180, 34, 56, 56);

        float tw = (1192 - 3 * 16) / 4f, th = (500 - 16) / 2f;
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new Tile(c, i);
            add(tiles[i], 44 + (i % 4) * (tw + 16), 112 + (i / 4) * (th + 16), tw, th);
        }
    }

    public boolean isOpen() { return open; }

    public void setAccent(Accent a) {
        acc = a.glow();
        for (Tile t : tiles) t.invalidate();
        invalidate();
    }

    // system states, read once per refresh rather than on every draw (they are IPC calls)
    private boolean cMusic, cRecents, cSpeed, cLinked;
    private int cPermsOn; private String cPermsMissing = "";

    public void refresh() {
        cMusic = host.musicAccess();
        cLinked = MediaWatcher.connected;
        cPermsOn = host.permissionsOn();
        cPermsMissing = host.permissionsMissing();
        cRecents = host.recentsOn();
        cSpeed = host.speedAllowed();
        for (Tile t : tiles) t.invalidate();
    }

    public void show() {
        open = true;
        refresh();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(16 * scale());
        animate().alpha(1f).translationY(0).setDuration(200).withLayer().start();
    }

    /** Hide straight away (switching to the Appearance panel). */
    public void hideNow() { open = false; animate().cancel(); setVisibility(GONE); }

    public void close() {
        if (!open) return;
        open = false;
        animate().alpha(0f).translationY(16 * scale()).setDuration(150).withLayer().withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }

    @Override protected void onDraw(Canvas c) {
        float s = scale(), ox = offX(), oy = offY();
        c.drawRect(0, 0, getWidth(), getHeight(), bgFill);
        // soft accent light from the top, like the home screen
        if (glowFor != acc || glowW != getWidth() || glowH != getHeight()) {
            RadialGradient g = new RadialGradient(0, 0, 1f, (acc & 0x00FFFFFF) | 0x1A000000, acc & 0x00FFFFFF, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(getWidth() * 0.75f, getHeight() * 0.6f);
            m.postTranslate(getWidth() / 2f, 0);
            g.setLocalMatrix(m);
            bgGlow.setShader(g);
            glowFor = acc; glowW = getWidth(); glowH = getHeight();
        }
        c.drawRect(0, 0, getWidth(), getHeight(), bgGlow);
        title.setTextSize(38 * s);
        c.drawText("Settings", ox + 44 * s, oy + 34 * s + 38 * s * 0.93f, title);
        foot.setTextSize(11 * s);
        c.drawText("DRIFT 1.0  ·  DESIGNED AND BUILT BY BABAR MANZOOR", ox + 44 * s, oy + 661 * s, foot);
    }

    // ------------------------------------------------------------------ tiles

    private static final int APPEARANCE = 0, MUSIC = 1, SONG = 2, SPEED = 3, BT = 4, RECENTS = 5, SHORTCUTS = 6, ABOUT = 7;

    private class Tile extends View {
        private final int kind;
        private final RectF r = new RectF(), sw = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG), down = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint swOn = new Paint(Paint.ANTI_ALIAS_FLAG), swOff = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), value = new TextPaint(Paint.ANTI_ALIAS_FLAG), hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private boolean pressed, held;
        private float shW = -1; private int shAcc;
        private StaticLayout sl; private String slText; private int slW;
        private final Runnable hold = new Runnable() {
            @Override public void run() {
                held = true; pressed = false; invalidate();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                onHold();
            }
        };

        Tile(Context c, int kind) {
            super(c);
            this.kind = kind;
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x14FFFFFF);
            down.setColor(0x12FFFFFF);
            swOff.setColor(0x29FFFFFF);
            knob.setColor(0xFFFFFFFF);
            arrow.setStyle(Paint.Style.STROKE);
            arrow.setStrokeCap(Paint.Cap.ROUND);
            arrow.setColor(0xFF8A93A3);
            label.setTypeface(Fonts.semibold(c));
            label.setLetterSpacing(0.22f);
            label.setColor(0xFF8A93A3);
            value.setTypeface(Fonts.light(c));
            value.setLetterSpacing(-0.02f);
            value.setColor(0xFFFFFFFF);
            hint.setTypeface(Fonts.regular(c));
            hint.setColor(0xFF9AA3B2);
            setClickable(true);
            setHapticFeedbackEnabled(true);
        }

        private String labelText() {
            switch (kind) {
                case APPEARANCE: return "APPEARANCE";
                case MUSIC: return "MUSIC APP";
                case SONG: return "PERMISSIONS";
                case SPEED: return "SPEED";
                case BT: return "BLUETOOTH";
                case RECENTS: return "RECENT APPS";
                case SHORTCUTS: return "MUSIC CARD";
                default: return "ABOUT";
            }
        }

        private String valueText() {
            switch (kind) {
                case APPEARANCE: return Accent.get(host.accent()).name;
                case MUSIC: { String m = host.musicApp(); return m == null ? "Choose" : m; }
                case SONG: return cPermsOn == 4 ? "All on" : cPermsOn + " of 4 on";
                case SPEED: return !cSpeed ? "Off" : host.mph() ? "mph" : "km/h";
                case BT: return "Devices";
                case RECENTS: return cRecents ? "On" : "Off";
                case SHORTCUTS: return host.cardRight() ? "Right" : "Left";
                default: return "Drift " + host.versionName();
            }
        }

        private String hintText() {
            switch (kind) {
                case APPEARANCE: return "Colour, music card side, road motion and dock glow";
                case MUSIC: return "Play starts this app. Hold for music diagnostics";
                case SONG: return cPermsOn == 4 ? "Music, location, media and recent apps" : "Missing: " + cPermsMissing;
                case SPEED: return cSpeed ? "Tap to switch units. Uses the unit's GPS" : "Tap to allow location so the speed can show";
                case BT: return "Pair your phone";
                case RECENTS: return cRecents ? "Button next to Home. Tap to manage access" : "Tap to switch on the button next to Home";
                case SHORTCUTS: return "Tap to move the music card to the other side";
                default: return host.updateHint();
            }
        }

        private boolean hasArrow() { return kind == APPEARANCE || kind == MUSIC || kind == BT || kind == RECENTS; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), k = w / 286f;
            r.set(k / 2, k / 2, w - k / 2, h - k / 2);
            if (shW != w || shAcc != acc) {
                fill.setShader(new LinearGradient(0, 0, 0, h, 0x0EFFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
                    shW = w; shAcc = acc;
            }
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            if (pressed) c.drawRoundRect(r, 20 * k, 20 * k, down);
            line.setStrokeWidth(Math.max(1f, k));
            c.drawRoundRect(r, 20 * k, 20 * k, line);
            top.setShader(new LinearGradient(24 * k, 0, w - 24 * k, 0, new int[] { acc & 0x00FFFFFF, (acc & 0x00FFFFFF) | 0x8C000000, acc & 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
            c.drawRect(24 * k, 0, w - 24 * k, Math.max(1f, k), top);

            float x = 26 * k;
            label.setTextSize(11.5f * k);
            c.drawText(labelText(), x, 41 * k, label);

            value.setTextSize(34 * k);
            float vx = x;
            if (kind == APPEARANCE) {
                dot.setColor(acc);
                dot.setShadowLayer(12 * k, 0, 0, (acc & 0x00FFFFFF) | 0xB3000000);
                c.drawCircle(x + 11 * k, 86.5f * k, 11 * k, dot);
                vx += 34 * k;
            }
            CharSequence v = TextUtils.ellipsize(valueText(), value, w - vx - 26 * k, TextUtils.TruncateAt.END);
            c.drawText(v, 0, v.length(), vx, 97.5f * k, value);

            hint.setTextSize(15 * k);
            String ht = hintText();
            int lw = Math.max(1, Math.round(w - 52 * k));
            if (sl == null || !ht.equals(slText) || lw != slW) {
                sl = new StaticLayout(ht, hint, lw, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
                slText = ht; slW = lw;
            }
            c.save();
            c.translate(x, h - 26 * k - sl.getHeight());
            sl.draw(c);
            c.restore();

            if ((kind == SONG && cPermsOn < 4) || (kind == ABOUT && host.updateReady())) {
                // amber dot: something still needs switching on
                dot.setColor(0xFFFFB547);
                dot.setShadowLayer(0, 0, 0, 0);
                c.drawCircle(w - 31 * k, 33 * k, 6 * k, dot);
            } else if (false) {
                boolean on = cMusic;
                sw.set(w - 24 * k - 54 * k, 22 * k, w - 24 * k, 52 * k);
                swOn.setColor(acc);
                swOn.setShadowLayer(14 * k, 0, 0, (acc & 0x00FFFFFF) | 0x80000000);
                c.drawRoundRect(sw, 15 * k, 15 * k, on ? swOn : swOff);
                c.drawCircle(on ? sw.right - 15 * k : sw.left + 15 * k, sw.centerY(), 12 * k, knob);
            } else if (hasArrow()) {
                arrow.setStrokeWidth(2 * k);
                float ax = w - 29 * k, ay = 33 * k;
                c.drawLine(ax - 5 * k, ay - 6 * k, ax + 1 * k, ay, arrow);
                c.drawLine(ax + 1 * k, ay, ax - 5 * k, ay + 6 * k, arrow);
            }
        }

        private void onTap() {
            switch (kind) {
                case APPEARANCE: host.openAppearance(); break;
                case MUSIC: host.chooseMusicApp(); break;
                case SONG: host.openPermissions(); break;
                case SPEED: if (cSpeed) host.setMph(!host.mph()); else host.allowSpeed(); break;
                case BT: host.openBluetooth(); break;
                case RECENTS: host.openRecentsAccess(); break;
                case SHORTCUTS: host.setCardRight(!host.cardRight()); break;
                default: host.updateTap(); break;
            }
            refresh();
        }

        private void onHold() {
            if (kind == MUSIC) host.openDiagnostics();

        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true; held = false; invalidate();
                    if (kind == MUSIC) postDelayed(hold, 550);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getX() < 0 || e.getY() < 0 || e.getX() > getWidth() || e.getY() > getHeight()) {
                        removeCallbacks(hold); if (pressed) { pressed = false; invalidate(); }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(hold);
                    if (pressed && !held) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap(); }
                    pressed = false; invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(hold); pressed = false; invalidate();
                    return true;
            }
            return true;
        }

        @Override protected void onDetachedFromWindow() { removeCallbacks(hold); super.onDetachedFromWindow(); }
    }

    // ------------------------------------------------------------------ small controls (shared with AppearancePanel)

    /** Rounded text button. */
    static class Pill extends View {
        private final String text;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Pill(Context c, String text) {
            super(c);
            this.text = text;
            bg.setColor(0x0FFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x17FFFFFF);
            tp.setTypeface(Fonts.regular(c));
            tp.setColor(0xFFC4C9D4);
            tp.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setContentDescription(text);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), k = h / 44f;
            r.set(k / 2, k / 2, w - k / 2, h - k / 2);
            bg.setColor(isPressed() ? 0x1FFFFFFF : 0x0FFFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, bg);
            line.setStrokeWidth(Math.max(1f, k));
            c.drawRoundRect(r, h / 2, h / 2, line);
            tp.setTextSize(16 * k);
            c.drawText(text, w / 2, h / 2 + 5.8f * k, tp);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    /** Round button with an X. */
    static class CloseButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), x = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x14FFFFFF);
            x.setColor(0xFFFFFFFF);
            x.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setContentDescription("Close");
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), k = w / 56f, cx = w / 2f;
            bg.setColor(isPressed() ? 0x24FFFFFF : 0x12FFFFFF);
            c.drawCircle(cx, cx, cx - k / 2, bg);
            line.setStrokeWidth(Math.max(1f, k));
            c.drawCircle(cx, cx, cx - k / 2, line);
            x.setStrokeWidth(2.2f * k);
            float d = 7.8f * k;
            c.drawLine(cx - d, cx - d, cx + d, cx + d, x);
            c.drawLine(cx - d, cx + d, cx + d, cx - d, x);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }
}
