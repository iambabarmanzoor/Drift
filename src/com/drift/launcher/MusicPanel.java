/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drift Music: the full-screen player. Two glass panels on the 1280 x 720 design canvas:
 * "Now Playing" (cover, title, progress, controls, moving light waves) and the library
 * (USB stick | Internal | Playlists). The swap button trades their sides.
 * Every size and colour here comes from the approved HTML preview.
 */
public class MusicPanel extends HomeLayout implements MusicService.Listener, MusicLibrary.Listener {

    public interface Host {
        void musicSheet(String title, String[] items, Sheet.OnPick pick);
        void musicToast(String text);
        void musicClose();
        void allowStorage();
        boolean storageAllowed();
        /** GPS speed in km/h, 0 when parked or unknown. Search is only offered when parked. */
        float speedKmh();
        void musicChecks(String title, String[] labels, String[] subs, boolean[] sel, String top, Runnable onTop, Sheet.OnChecks done);
        void musicInput(String title, String initial, String ok, String note, Sheet.OnText done);
    }

    static final float DRIVING_KMH = 8f;
    static final String IC_SEARCH = "M10 3a7 7 0 0 1 5.6 11.2l5.1 5.1-1.4 1.4-5.1-5.1A7 7 0 1 1 10 3zm0 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10z";

    // ---- icon shapes (24 x 24), identical to the preview
    static final String IC_NOTE = "M20.5 2.5v13.2a3.3 3.3 0 1 1-1.8-2.95V7.3l-9 1.9v8.7a3.3 3.3 0 1 1-1.8-2.95V4.6z";
    static final String IC_HEART = "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z";
    static final String IC_SWAP = "M7 7h11.2l-2.6-2.6L17 3l5 5-5 5-1.4-1.4 2.6-2.6H7zm10 10H5.8l2.6 2.6L7 21l-5-5 5-5 1.4 1.4L5.8 15H17z";
    static final String IC_PLAY = "M8 4.5c0-.8.9-1.3 1.6-.9l10 7.5c.6.4.6 1.4 0 1.8l-10 7.5c-.7.4-1.6-.1-1.6-.9z";
    static final String IC_PREV = "M6 6h2.5v12H6zM9.5 12l9 6V6z";
    static final String IC_NEXT = "M15.5 6H18v12h-2.5zM5.5 6v12l9-6z";
    static final String IC_SHUF = "M10.6 9.2 5.4 4 4 5.4l5.2 5.2zM14.5 4l2 2L4 18.6 5.4 20 18 7.5l2 2V4zm.3 9.4-1.4 1.4 3.1 3.1-2 2.1H20v-5.5l-2 2z";
    static final String IC_REP = "M7 7h10v3l4-4-4-4v3H5v6h2zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2z";
    static final String IC_USB = "M9 1.5h6V7h1.2A1.8 1.8 0 0 1 18 8.8V20a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 20V8.8A1.8 1.8 0 0 1 7.8 7H9zm1.6 1.6V7h2.8V3.1zM7.6 8.6V20c0 .5.4.9.9.9h7c.5 0 .9-.4.9-.9V8.6zM10 11h1.5v2.5H10zm2.5 0H14v2.5h-1.5z";
    static final String IC_BELL = "M12 22.5a2.3 2.3 0 0 0 2.3-2.3H9.7a2.3 2.3 0 0 0 2.3 2.3zm6.5-6.3V11c0-3.3-1.8-5.9-4.8-6.6v-.7a1.7 1.7 0 0 0-3.4 0v.7C7.3 5.1 5.5 7.7 5.5 11v5.2L3.6 18.1v1h16.8v-1z";
    static final String IC_FOLD = "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8z";
    static final String IC_CLOCK = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16zm1 3h-2v6l5 3 1-1.7-4-2.3z";
    static final String IC_MIX = "M3 10h2v4H3zm4-3h2v10H7zm4-4h2v18h-2zm4 5h2v8h-2zm4 2h2v4h-2z";
    static final String IC_ADD = "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z";
    static final String IC_VOL = "M3 9v6h4l5 5V4L7 9zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z";

    static final int BG = 0xFF04070D;
    private final Host host;
    private final LeftCard left;
    private final RightCard right;
    private final View close;
    private final EditText query;
    private boolean open, swapped;
    private int acc = 0xFF46AAFF;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bgFill = new Paint(), glowA = new Paint(Paint.ANTI_ALIAS_FLAG), glowB = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MusicPanel(Context c, Host host) {
        super(c);
        this.host = host;
        setWillNotDraw(false);
        setVisibility(GONE);
        setClickable(true);
        bgFill.setColor(BG);
        left = new LeftCard(c);
        right = new RightCard(c);
        close = new CloseButton(c);
        add(left, 26, 22, 554, 676);
        add(right, 604, 22, 653, 676);
        add(close, 1200, 28, 58, 58);
        close.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { MusicPanel.this.host.musicClose(); } });
        query = new EditText(c);
        query.setBackground(null);
        query.setPadding(0, 0, 0, 0);
        query.setSingleLine(true);
        query.setTextColor(0xFFFFFFFF);
        query.setHintTextColor(0xFF7D8AA0);
        query.setTypeface(Fonts.regular(c));
        query.setGravity(android.view.Gravity.CENTER_VERTICAL);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // landscape keyboards go full screen by default and hide everything; keep the results visible
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        query.setVisibility(GONE);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) { right.onQuery(e.toString()); }
        });
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) { right.remember(); hideKeyboard(); return true; }
        });
        add(query, 604 + 77, 22 + 101, 417, 52);
        swapped = c.getSharedPreferences("drift", Context.MODE_PRIVATE).getBoolean("musicSwap", false);
        place(false);
    }

    public boolean isOpen() { return open; }

    public void setAccent(Accent a) { acc = a.glow(); left.rebuild(); invalidate(); left.invalidate(); right.invalidate(); }

    /** The launcher went to the background: stop animating. */
    public void paused() { left.removeCallbacks(left.frame); right.removeCallbacks(right.eqTick); removeCallbacks(driveCheck); watchVolume(false); }

    public void resumed() { if (open) { left.tick(); right.invalidate(); removeCallbacks(driveCheck); post(driveCheck); watchVolume(true); } }

    // ---- search (parked only)
    private boolean wasDriving;
    private final Runnable driveCheck = new Runnable() {
        @Override public void run() {
            boolean d = driving();
            if (d && right.finding) { right.closeFind(); host.musicToast("Search is available when parked"); }
            if (d != wasDriving) { wasDriving = d; right.invalidate(); }
            if (open) postDelayed(this, 1000);
        }
    };

    boolean driving() { return host.speedKmh() > DRIVING_KMH; }

    // ---- volume: keep the slider in step with the unit's knob / steering buttons
    private String lastSysKey, lastSysVal;
    private final android.content.BroadcastReceiver volRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) { right.invalidate(); }
    };
    /** Many head units keep their real volume in a system setting; note which one moves so we can show it. */
    private final android.database.ContentObserver sysObs = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean self, android.net.Uri uri) {
            if (uri != null) {
                String key = uri.getLastPathSegment();
                if (key != null && key.toLowerCase(Locale.ROOT).contains("vol")) {
                    lastSysKey = key;
                    try { lastSysVal = android.provider.Settings.System.getString(getContext().getContentResolver(), key); } catch (Throwable t) { lastSysVal = "?"; }
                }
            }
            right.invalidate();
        }
    };
    private boolean volWatching;

    private void watchVolume(boolean on) {
        if (on == volWatching) return;
        volWatching = on;
        try {
            if (on) {
                getContext().registerReceiver(volRx, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
                getContext().getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, sysObs);
            } else {
                getContext().unregisterReceiver(volRx);
                getContext().getContentResolver().unregisterContentObserver(sysObs);
            }
        } catch (Throwable ignored) { }
    }

    /** Tap the speaker icon: what Drift can see of the unit's volume (for diagnosing units that do their own thing). */
    void volumeInfo() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int v = -1, max = -1; boolean fixed = false;
        try { v = am.getStreamVolume(AudioManager.STREAM_MUSIC); max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); fixed = am.isVolumeFixed(); } catch (Throwable ignored) { }
        String sys = lastSysKey == null ? "Unit volume setting: none seen yet. Turn the knob, then tap the speaker again"
                : "Unit volume setting: " + lastSysKey + " = " + lastSysVal;
        host.musicSheet("Volume info", new String[] {
                "Android music volume: " + v + " of " + max,
                "Fixed by the unit: " + (fixed ? "yes" : "no"),
                sys }, new Sheet.OnPick() { @Override public void onPick(int i) { } });
    }

    void showKeyboard() {
        query.setVisibility(VISIBLE);
        query.requestFocus();
        try { ((android.app.Activity) getContext()).getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING); } catch (Throwable ignored) { }
        query.post(new Runnable() { @Override public void run() {
            InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
        } });
    }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
    }

    /** Storage permission just arrived, or a USB stick was plugged in / removed. */
    public void refreshLibrary() { right.refreshRoot(); }

    public void show() {
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).withLayer().start();
        MusicService.addListener(this);
        MusicLibrary.get(getContext()).setListener(this);
        right.refreshRoot();
        left.tick();
        removeCallbacks(driveCheck); post(driveCheck);
        watchVolume(true);
    }

    public void close() {
        if (!open) return;
        open = false;
        if (right.finding) right.closeFind();
        removeCallbacks(driveCheck);
        watchVolume(false);
        MusicService.removeListener(this);
        animate().alpha(0f).setDuration(150).withLayer().withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }

    /** Back key: leave a folder first, then close. */
    public boolean back() {
        if (right.finding) { right.closeFind(); return true; }
        if (right.openList != null) { right.openList = null; right.scrollTo0(); right.markDirty(); return true; }
        return false;
    }

    @Override public void onMusicState() { left.invalidate(); right.markDirty(); left.tick(); }
    @Override public void onChanged() { right.markDirty(); }

    private void place(boolean animate) {
        float lx = swapped ? 703 : 26, rx = swapped ? 26 : 604;
        final float s = scale();
        if (animate) {
            float dl = (left.getLeft() - (offX() + lx * s)), dr = (right.getLeft() - (offX() + rx * s));
            left.setTranslationX(dl); right.setTranslationX(dr);
            left.animate().translationX(0).setDuration(350).start();
            right.animate().translationX(0).setDuration(350).start();
        }
        move(left, lx, 22, 554, 676);
        move(right, rx, 22, 653, 676);
        move(query, rx + 77, 22 + 101, 417, 52);
        left.rebuild(); right.invalidate();
    }

    void swap() {
        swapped = !swapped;
        getContext().getSharedPreferences("drift", Context.MODE_PRIVATE).edit().putBoolean("musicSwap", swapped).apply();
        place(true);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), bgFill);
        float w = getWidth(), h = getHeight();
        glowA.setShader(new RadialGradient(w * 0.2f, h * 0.9f, w * 0.5f, (acc & 0x00FFFFFF) | 0x1A000000, acc & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glowA);
        glowB.setShader(new RadialGradient(w * 0.85f, h * 0.1f, w * 0.4f, (acc & 0x00FFFFFF) | 0x0F000000, acc & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glowB);
    }

    // =================================================================== shared drawing helpers

    static int a(int color, float alpha) { return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24); }

    /** Card outline: rounded rectangle, optionally with the curved notch for the close button (top right). */
    static Path cardPath(float w, float h, boolean notch, float k) {
        float r = 30 * k, e = 0.5f;
        Path p = new Path();
        p.moveTo(r, e);
        if (notch) {
            p.lineTo(w - 105 * k, e);
            p.cubicTo(w - 71 * k, e, w - 75 * k, 76 * k, w - 29 * k, 76.5f * k);
            p.quadTo(w - e, 77 * k, w - e, 106 * k);
        } else {
            p.lineTo(w - r, e);
            p.quadTo(w - e, e, w - e, r);
        }
        p.lineTo(w - e, h - r);
        p.quadTo(w - e, h - e, w - r, h - e);
        p.lineTo(r, h - e);
        p.quadTo(e, h - e, e, h - r);
        p.lineTo(e, r);
        p.quadTo(e, e, r, e);
        p.close();
        return p;
    }

    static void drawCard(Canvas c, Path p, float w, float h, Paint fill, Paint sheen, Paint stroke) {
        c.drawPath(p, fill);
        c.drawPath(p, sheen);
        c.drawPath(p, stroke);
    }

    static Paint text(Context c, android.graphics.Typeface tf, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setColor(color);
        return p;
    }

    private static final java.util.HashMap<String, Path> ICONS = new java.util.HashMap<>();

    /** Parsed icon at a size, cached (parsing every frame would waste the head unit's CPU). Translate when drawing. */
    static Path ico(String d, float size) {
        String key = d.length() + ":" + d.hashCode() + ":" + Math.round(size * 10);
        Path p = ICONS.get(key);
        if (p == null) { p = SvgPath.icon(d, 24, 0, 0, size); ICONS.put(key, p); }
        return p;
    }

    static void drawIco(Canvas c, String d, float x, float y, float size, Paint paint) {
        c.save(); c.translate(x, y); c.drawPath(ico(d, size), paint); c.restore();
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Placeholder cover: a soft coloured gradient derived from the file name. */
    static void placeholder(Canvas c, RectF r, String key, float radius, Paint p) {
        int h = key == null ? 210 : Math.abs(key.hashCode()) % 360;
        float[] hsv1 = { h, 0.55f, 0.24f }, hsv2 = { (h + 40) % 360, 0.6f, 0.5f };
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, android.graphics.Color.HSVToColor(hsv2), android.graphics.Color.HSVToColor(hsv1), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(new RadialGradient(r.centerX(), r.top + r.height() * 0.38f, r.width() * 0.4f, 0x66FFF2D8, 0x00FFF2D8, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    static void drawBitmapRound(Canvas c, Bitmap b, RectF r, float radius, Paint p) {
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        m.setScale(sc, sc);
        m.postTranslate(r.left + (r.width() - b.getWidth() * sc) / 2f, r.top + (r.height() - b.getHeight() * sc) / 2f);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    // =================================================================== left: Now Playing

    private class LeftCard extends View {
        private float k = 1f;
        private Path shape;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG), circ = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG), waveFill = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hdr, title, artist, time;
        private final RectF r = new RectF();
        private Path pNote, pHeart, pSwap, pShuf, pPrev, pNext, pRep, pPlay, w1, w2, w3;
        private int down = -1;
        private boolean seeking;
        private float seekFrac;
        private final Vinyl vinyl = new Vinyl();
        private boolean vinylBusy;
        private float waveT;
        private long waveLast;
        private static final int B_SWAP = 1, B_FAV = 2, B_SHUF = 3, B_PREV = 4, B_PLAY = 5, B_NEXT = 6, B_REP = 7, B_SEEK = 8;

        final Runnable frame = new Runnable() {
            @Override public void run() {
                invalidate();
                MusicService s = MusicService.get();
                if (open && ((s != null && s.isPlaying()) || vinylBusy)) postDelayed(this, 33); // waves, progress, record at ~30 fps
            }
        };

        LeftCard(Context c) {
            super(c);
            fill.setColor(0xFF0B1018);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0x1CFFFFFF);
            hdr = (TextPaint) text(c, Fonts.regular(c), 0xFFC9D3E3);
            title = (TextPaint) text(c, Fonts.bold(c), 0xFFFFFFFF);
            title.setLetterSpacing(-0.025f);
            artist = (TextPaint) text(c, Fonts.regular(c), 0xFFA3B3C9);
            time = (TextPaint) text(c, Fonts.regular(c), 0xFFC9D3E3);
            ring.setStyle(Paint.Style.STROKE);
            wave.setStyle(Paint.Style.STROKE);
            wave.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0x21FFFFFF);
            knob.setColor(0xFFFFFFFF);
            setClickable(true);
            setHapticFeedbackEnabled(true);
        }

        void tick() { removeCallbacks(frame); post(frame); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0) { k = w / 554f; rebuild(); } }

        void rebuild() {
            if (getWidth() == 0) return;
            float w = getWidth(), h = getHeight();
            shape = cardPath(w, h, swapped, k);
            sheen.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[] { 0x13FFFFFF, 0x06FFFFFF, 0x05FFFFFF }, new float[] { 0, 0.45f, 1 }, Shader.TileMode.CLAMP));
            stroke.setStrokeWidth(Math.max(1f, k));
            hdr.setTextSize(22 * k); title.setTextSize(37 * k); artist.setTextSize(25 * k); time.setTextSize(19 * k);
            pNote = SvgPath.icon(IC_NOTE, 24, 38 * k, 24 * k, 30 * k);
            float fx = (swapped ? 378 : 462) * k, sx = (swapped ? 312 : 396) * k;
            pHeart = SvgPath.icon(IC_HEART, 24, fx + 17 * k, 28 * k + 17 * k, 28 * k);
            pSwap = SvgPath.icon(IC_SWAP, 24, sx + 13 * k, 34 * k + 13 * k, 24 * k);
            pShuf = SvgPath.icon(IC_SHUF, 24, 59 * k, 543 * k, 34 * k);
            pRep = SvgPath.icon(IC_REP, 24, 485 * k, 543 * k, 34 * k);
            pPrev = SvgPath.icon(IC_PREV, 24, 164 * k, 545 * k, 30 * k);
            pNext = SvgPath.icon(IC_NEXT, 24, 395 * k, 545 * k, 30 * k);
            pPlay = SvgPath.icon(IC_PLAY, 24, 272 * k, 538 * k, 44 * k);
            // periodic waves (two periods wide so they can slide), from the preview
            // kept below the controls so the lines don't cut through the buttons
            w1 = wavePath(628, 26); w2 = wavePath(656, 20); w3 = wavePath(640, 26);
            w3.lineTo(1108 * k, h); w3.lineTo(0, h); w3.close();
            waveFill.setShader(new LinearGradient(0, 614 * k, 0, h, a(acc, 0.16f), a(acc, 0f), Shader.TileMode.CLAMP));
            buildSprites(h);
        }

        private Path wavePath(float y, float amp) {
            Path p = new Path();
            p.moveTo(0, y * k);
            for (int i = 0; i < 2; i++) {
                float o = i * 554;
                p.cubicTo((o + 92) * k, (y - amp) * k, (o + 185) * k, (y - amp) * k, (o + 277) * k, y * k);
                p.cubicTo((o + 369) * k, (y + amp) * k, (o + 462) * k, (y + amp) * k, (o + 554) * k, y * k);
            }
            return p;
        }

        private float fx() { return (swapped ? 378 : 462) * k; }
        private float sx() { return (swapped ? 312 : 396) * k; }

        @Override protected void onDraw(Canvas c) {
            if (shape == null) rebuild();
            float w = getWidth(), h = getHeight();
            drawCard(c, shape, w, h, fill, sheen, stroke);
            MusicService svc = MusicService.get();
            MusicLibrary.Song song = svc == null ? null : svc.current();
            boolean playing = svc != null && svc.isPlaying();

            // waves (clipped to the card), sliding while music plays
            c.save();
            c.clipPath(shape);
            // the wave clock only runs while playing, so pause freezes them in place and play carries on from there
            long now = SystemClock.uptimeMillis();
            if (playing && waveLast != 0) waveT += Math.min(100, now - waveLast) / 1000f;
            waveLast = playing ? now : 0;
            float t = waveT;
            drawWave(c, sFill, -(t / 24f % 1f) * 554);
            drawWave(c, s1, -(1f - (t / 16f % 1f)) * 554); // opposite direction
            drawWave(c, s2, -(t / 11f % 1f) * 554);
            c.restore();

            // header
            icon.setColor(0xFFE8EEF7);
            c.drawPath(pNote, icon);
            c.drawText("Now Playing", 86 * k, 47 * k, hdr);
            // swap + heart
            float sx = sx();
            roundBtn(c, sx + 25 * k, 59 * k, 25 * k, down == B_SWAP, false);
            icon.setColor(0xFFDCE3EE); c.drawPath(pSwap, icon);
            float fx = fx();
            roundBtn(c, fx + 31 * k, 59 * k, 31 * k, down == B_FAV, true);
            boolean fav = song != null && MusicStore.get(getContext()).isFavourite(song.path);
            if (fav) { icon.setStyle(Paint.Style.FILL); icon.setColor(acc); c.drawPath(pHeart, icon); }
            else { icon.setStyle(Paint.Style.STROKE); icon.setStrokeWidth(1.9f * k * 28 / 24f); icon.setColor(0xFFFFFFFF); c.drawPath(pHeart, icon); }
            icon.setStyle(Paint.Style.FILL);

            // cover
            r.set(40 * k, 96 * k, 263 * k, 322 * k);
            ring.setStrokeWidth(10 * k); ring.setColor(a(acc, 0.12f)); c.drawRoundRect(r, 22 * k, 22 * k, ring);
            Bitmap art = svc == null ? null : svc.art();
            if (art != null) { drawBitmapRound(c, art, r, 22 * k, img); vinylBusy = false; }
            else vinylBusy = vinyl.draw(c, r, 22 * k, acc, playing); // no cover: the spinning record
            ring.setStrokeWidth(1.5f * k); ring.setColor(a(acc, 0.6f)); c.drawRoundRect(r, 22 * k, 22 * k, ring);

            // title (up to two lines) and artist
            String tt = song == null ? "Nothing playing" : song.title;
            String ar = song == null ? "Pick a song on the right" : (song.artist == null ? "" : song.artist);
            float tw = 240 * k, tx = 296 * k;
            String[] lines = wrap(tt, title, tw);
            c.drawText(lines[0], tx, (185 + 34) * k, title);
            if (lines[1] != null) c.drawText(lines[1], tx, (185 + 34 + 41.4f) * k, title);
            float at = (lines[1] != null ? 185 + 83 : 185 + 56) * k;
            CharSequence as = TextUtils.ellipsize(ar, artist, tw, TextUtils.TruncateAt.END);
            c.drawText(as, 0, as.length(), tx, at + 23 * k, artist);

            // progress
            long dur = svc == null ? 0 : svc.duration(), pos = svc == null ? 0 : svc.position();
            float frac = seeking ? seekFrac : dur > 0 ? Math.min(1f, pos / (float) dur) : 0;
            float bl = 40 * k, br = 516 * k, by = 398 * k;
            r.set(bl, by, br, by + 6 * k);
            c.drawRoundRect(r, 3 * k, 3 * k, track);
            float fxp = bl + (br - bl) * frac;
            r.set(bl, by, Math.max(bl + 6 * k, fxp), by + 6 * k);
            bar.setShader(new LinearGradient(bl, 0, Math.max(bl + 1, fxp), 0, a(acc, 0.8f), acc, Shader.TileMode.CLAMP));
            ring.setStrokeWidth(6 * k); ring.setColor(a(acc, 0.18f)); c.drawRoundRect(r, 3 * k, 3 * k, ring);
            c.drawRoundRect(r, 3 * k, 3 * k, bar);
            circ.setColor(a(acc, 0.35f)); c.drawCircle(fxp, by + 3 * k, 14 * k, circ);
            c.drawCircle(fxp, by + 3 * k, 10 * k, knob);
            long shown = seeking ? (long) (seekFrac * dur) : pos;
            c.drawText(fmt(shown), 40 * k, 436 * k, time);
            String rem = "-" + fmt(dur - shown);
            c.drawText(rem, 516 * k - time.measureText(rem), 436 * k, time);

            // controls
            boolean shuf = svc != null && svc.shuffle();
            int rep = svc == null ? MusicService.REPEAT_ALL : svc.repeat();
            if (down == B_SHUF) { circ.setColor(0x1AFFFFFF); c.drawCircle(76 * k, 560 * k, 38 * k, circ); }
            icon.setColor(shuf ? acc : 0xFFD5DDE8); c.drawPath(pShuf, icon);
            if (down == B_REP) { circ.setColor(0x1AFFFFFF); c.drawCircle(502 * k, 560 * k, 38 * k, circ); }
            icon.setColor(rep != MusicService.REPEAT_OFF ? acc : 0xFFD5DDE8); c.drawPath(pRep, icon);
            if (rep == MusicService.REPEAT_ONE) { time.setColor(acc); time.setTextSize(11 * k); c.drawText("1", 515 * k, 575 * k, time); time.setTextSize(19 * k); time.setColor(0xFFC9D3E3); }
            glassCircle(c, 179 * k, 560 * k, 38 * k, down == B_PREV); icon.setColor(0xFFFFFFFF); c.drawPath(pPrev, icon);
            glassCircle(c, 410 * k, 560 * k, 38 * k, down == B_NEXT); c.drawPath(pNext, icon);
            // play ring
            float pcx = 294 * k, pcy = 560 * k;
            circ.setShader(new RadialGradient(pcx, pcy, 50 * k, 0xF20A1220, 0xE6060A12, Shader.TileMode.CLAMP));
            c.drawCircle(pcx, pcy, 50 * k, circ); circ.setShader(null);
            if (down == B_PLAY) { circ.setColor(0x1AFFFFFF); c.drawCircle(pcx, pcy, 50 * k, circ); }
            if (sRing != null) c.drawBitmap(sRing, pcx - sRing.getWidth() / 2f, pcy - sRing.getHeight() / 2f, img);
            ring.setColor(acc); ring.setStrokeWidth(3 * k); c.drawCircle(pcx, pcy, 48.5f * k, ring);
            icon.setColor(0xFFFFFFFF);
            if (playing) {
                r.set(pcx - 13 * k, pcy - 16.5f * k, pcx - 3 * k, pcy + 16.5f * k); c.drawRoundRect(r, 2.6f * k, 2.6f * k, icon);
                r.set(pcx + 3 * k, pcy - 16.5f * k, pcx + 13 * k, pcy + 16.5f * k); c.drawRoundRect(r, 2.6f * k, 2.6f * k, icon);
            } else c.drawPath(pPlay, icon);
        }

        // Real blurred glow. Hardware drawing on Android 6-7 can't blur, so each wave and the play
        // ring are drawn once into a bitmap with a true blur, then only slid/placed every frame.
        private static final float SPRITE_TOP = 560;
        private Bitmap sFill, s1, s2, sRing;

        private void buildSprites(float h) {
            int bw = Math.round(1108 * k) + 2, bh = Math.max(1, Math.round(h - SPRITE_TOP * k));
            sFill = sprite(bw, bh, w3, 0, 0, 0, true);
            s1 = sprite(bw, bh, w1, 2.2f, 0.55f, 0, false);
            s2 = sprite(bw, bh, w2, 3.5f, 0.75f, 0xBFFFFFFF, false);
            int rs = Math.round(150 * k);
            try {
                Bitmap b = Bitmap.createBitmap(rs, rs, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(a(acc, 0.8f)); p.setStrokeWidth(8 * k);
                p.setMaskFilter(new BlurMaskFilter(11 * k, BlurMaskFilter.Blur.NORMAL));
                c.drawCircle(rs / 2f, rs / 2f, 49 * k, p);
                sRing = b;
            } catch (Throwable t) { sRing = null; }
        }

        private Bitmap sprite(int bw, int bh, Path p, float width, float alpha, int core, boolean fill) {
            try {
                Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.translate(0, -SPRITE_TOP * k);
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (fill) {
                    q.setShader(waveFill.getShader());
                    q.setMaskFilter(new BlurMaskFilter(14 * k, BlurMaskFilter.Blur.NORMAL)); // soft top edge
                    c.drawPath(p, q);
                    return b;
                }
                q.setStyle(Paint.Style.STROKE); q.setStrokeCap(Paint.Cap.ROUND);
                q.setColor(a(acc, alpha * 0.7f)); q.setStrokeWidth((width + 5) * k);
                q.setMaskFilter(new BlurMaskFilter(9 * k, BlurMaskFilter.Blur.NORMAL));
                c.drawPath(p, q);
                q.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, 1.2f * k), BlurMaskFilter.Blur.NORMAL));
                q.setColor(a(acc, alpha)); q.setStrokeWidth(width * 0.7f * k);
                c.drawPath(p, q);
                if (core != 0) {
                    q.setMaskFilter(null);
                    q.setColor(core); q.setStrokeWidth(1.1f * k);
                    c.drawPath(p, q);
                }
                return b;
            } catch (Throwable t) { return null; }
        }

        private void drawWave(Canvas c, Bitmap b, float off) {
            if (b != null) c.drawBitmap(b, off * k, SPRITE_TOP * k, img);
        }

        private void roundBtn(Canvas c, float cx, float cy, float rad, boolean pressed, boolean dark) {
            circ.setShader(dark ? new RadialGradient(cx, cy - rad * 0.4f, rad * 1.2f, 0x12FFFFFF, 0xD9080C14, Shader.TileMode.CLAMP) : null);
            if (!dark) circ.setColor(0x0AFFFFFF);
            c.drawCircle(cx, cy, rad, circ);
            circ.setShader(null);
            if (pressed) { circ.setColor(0x1FFFFFFF); c.drawCircle(cx, cy, rad, circ); }
            ring.setColor(dark ? 0x29FFFFFF : 0x1FFFFFFF); ring.setStrokeWidth(Math.max(1f, k)); c.drawCircle(cx, cy, rad - 0.5f, ring);
        }

        private void glassCircle(Canvas c, float cx, float cy, float rad, boolean pressed) {
            circ.setShader(null); circ.setColor(0xF20C111A);
            c.drawCircle(cx, cy, rad, circ);
            circ.setShader(new RadialGradient(cx, cy - rad * 0.5f, rad * 1.3f, 0x14FFFFFF, 0x000C111A, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, circ);
            circ.setShader(null);
            if (pressed) { circ.setColor(0x1AFFFFFF); c.drawCircle(cx, cy, rad, circ); }
            ring.setColor(0x1AFFFFFF); ring.setStrokeWidth(Math.max(1f, k)); c.drawCircle(cx, cy, rad - 0.5f, ring);
        }

        private int hit(float x, float y) {
            float X = x / k, Y = y / k;
            if (d(X, Y, (swapped ? 312 : 396) + 25, 59) < 30) return B_SWAP;
            if (d(X, Y, (swapped ? 378 : 462) + 31, 59) < 34) return B_FAV;
            if (Y > 380 && Y < 420 && X > 30 && X < 526) return B_SEEK;
            if (Math.abs(Y - 560) < 46) {
                if (Math.abs(X - 76) < 44) return B_SHUF;
                if (Math.abs(X - 179) < 48) return B_PREV;
                if (Math.abs(X - 294) < 56) return B_PLAY;
                if (Math.abs(X - 410) < 48) return B_NEXT;
                if (Math.abs(X - 502) < 44) return B_REP;
            }
            return -1;
        }

        private float d(float x, float y, float cx, float cy) { float dx = x - cx, dy = y - cy; return (float) Math.sqrt(dx * dx + dy * dy); }

        private float frac(float x) { return Math.max(0f, Math.min(1f, (x / k - 40) / 476f)); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            MusicService svc = MusicService.get();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = hit(e.getX(), e.getY());
                    if (down == B_SEEK && svc != null && svc.duration() > 0) {
                        seeking = true; seekFrac = frac(e.getX());
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (seeking) { seekFrac = frac(e.getX()); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int h = hit(e.getX(), e.getY());
                    if (seeking) {
                        seeking = false;
                        if (svc != null) svc.seekTo((long) (frac(e.getX()) * svc.duration()));
                    } else if (h == down && h > 0) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        act(h, svc);
                    }
                    down = -1; invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: seeking = false; down = -1; invalidate(); return true;
            }
            return true;
        }

        private void act(int b, MusicService svc) {
            if (b == B_SWAP) { swap(); return; }
            if (svc == null) return;
            switch (b) {
                case B_FAV: {
                    MusicLibrary.Song s = svc.current();
                    if (s == null) return;
                    MusicStore st = MusicStore.get(getContext());
                    boolean was = st.isFavourite(s.path);
                    st.toggleFavourite(s.path);
                    host.musicToast(was ? "Removed from Favourites" : "Added to Favourites");
                    right.reloadOpen();
                    right.markDirty();
                    break;
                }
                case B_SHUF: svc.setShuffle(!svc.shuffle()); host.musicToast(svc.shuffle() ? "Shuffle on" : "Shuffle off"); break;
                case B_REP: svc.cycleRepeat(); host.musicToast(new String[] { "Repeat off", "Repeat all", "Repeat this song" }[svc.repeat()]); break;
                case B_PREV: svc.previous(); break;
                case B_NEXT: svc.next(); break;
                case B_PLAY: svc.toggle(); break;
            }
            tick();
        }

        @Override protected void onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow(); }
    }

    /** Break a title into at most two lines at a space, ellipsizing the second. */
    static String[] wrap(String t, TextPaint p, float max) {
        if (p.measureText(t) <= max) return new String[] { t, null };
        int cut = -1;
        for (int i = t.length() - 1; i > 0; i--) if (t.charAt(i) == ' ' && p.measureText(t, 0, i) <= max) { cut = i; break; }
        if (cut < 0) return new String[] { TextUtils.ellipsize(t, p, max, TextUtils.TruncateAt.END).toString(), null };
        return new String[] { t.substring(0, cut), TextUtils.ellipsize(t.substring(cut + 1), p, max, TextUtils.TruncateAt.END).toString() };
    }

    // =================================================================== right: library

    /** A row in the library list. */
    private static final class Row {
        static final int FOLDER = 0, SONG = 1, FAV = 2, RECENT = 3, LIST = 4, NEW = 5, MESSAGE = 6, HEADER = 7, CHIPS = 8, ADDSONGS = 9;
        final int kind;
        final String title, sub;
        final MusicLibrary.Folder folder;
        final MusicStore.Playlist playlist;
        final MusicLibrary.Song song;
        List<MusicLibrary.Song> ctx; // search: the folder/playlist a song plays in
        String ctxName;
        Row(int kind, String title, String sub, MusicLibrary.Folder f, MusicStore.Playlist p, MusicLibrary.Song s) {
            this.kind = kind; this.title = title; this.sub = sub; folder = f; playlist = p; song = s;
        }
    }

    /** What's open on the right: a folder or a playlist. */
    private static final class OpenList {
        final String name;
        final int kind; // Row.FOLDER / FAV / RECENT / LIST
        final MusicLibrary.Folder folder;
        final MusicStore.Playlist playlist;
        List<MusicLibrary.Song> songs; // loaded off the main thread
        OpenList(String name, int kind, MusicLibrary.Folder f, MusicStore.Playlist p) { this.name = name; this.kind = kind; folder = f; playlist = p; }
    }

    /** One song in the search index, with the list it belongs to. */
    private static final class Hit {
        final MusicLibrary.Song song; final List<MusicLibrary.Song> list; final String listName;
        Hit(MusicLibrary.Song s, List<MusicLibrary.Song> l, String n) { song = s; list = l; listName = n; }
    }

    private static final HashMap<String, String> FOLD = new HashMap<>();

    /** Lower case without accents, one char per char, so match positions map back onto the original text. */
    static String fold(String t) {
        if (t == null) return "";
        String f = FOLD.get(t);
        if (f != null) return f;
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= 0x80) { String d = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD); if (d.length() > 0) ch = d.charAt(0); }
            b.append(Character.toLowerCase(ch));
        }
        f = b.toString();
        if (FOLD.size() > 20000) FOLD.clear();
        FOLD.put(t, f);
        return f;
    }

    private class RightCard extends View {
        static final int USB = 0, INTERNAL = 1, PLAYLISTS = 2;
        int src = USB;
        OpenList openList;
        private float k = 1f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG), innerLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tab, head, pill, rt, rs, dur, foot, az, bubble;
        private final RectF r = new RectF();
        private final List<Row> rows = new ArrayList<>();
        private final List<MusicLibrary.Folder> usbFolders = new ArrayList<>(), intFolders = new ArrayList<>();
        private final List<MusicLibrary.Song> usbLoose = new ArrayList<>(), intLoose = new ArrayList<>();
        private boolean usbScanned, intScanned, scanning;
        private final ExecutorService scan = Executors.newSingleThreadExecutor();
        private final LruCache<String, Bitmap> thumbs = new LruCache<>(80);
        private final java.util.HashSet<String> thumbLoading = new java.util.HashSet<>(), thumbMiss = new java.util.HashSet<>();
        private final ExecutorService thumbIo = Executors.newSingleThreadExecutor();
        private final OverScroller scroller;
        private VelocityTracker vt;
        private float scrollY, downY, downX, lastY;
        private boolean dragging, azDrag;
        private int downRow = -1, downZone = 0;
        private String azLetter;
        private final int slop;
        private final Runnable longPress = new Runnable() { @Override public void run() { onLong(); } };
        private boolean longFired, dirty = true;
        final Runnable eqTick = new Runnable() { @Override public void run() { invalidate(); } };
        private final AudioManager audio;

        // ---- search
        boolean finding;
        private String q = "";
        private int indexSrc = -1, indexGen;
        private List<Hit> index;
        private List<MusicLibrary.Folder> indexFolders = new ArrayList<>();
        private List<MusicStore.Playlist> indexLists = new ArrayList<>();
        private final List<String> recentQ = new ArrayList<>();
        private final List<RectF> chipRects = new ArrayList<>();
        private final List<String> chipText = new ArrayList<>();
        private float[] offs = new float[0];

        MusicStore.Playlist addTo; // "Add songs" mode: searching everything to add to this playlist

        void openAdd(MusicStore.Playlist pl) {
            if (driving()) { host.musicToast("Adding songs is available when parked"); return; }
            if (!host.storageAllowed()) { host.allowStorage(); return; }
            addTo = pl;
            finding = true; q = "";
            query.setText("");
            query.setHint("Search songs to add");
            buildIndex();
            scrollTo0(); markDirty();
            showKeyboard();
        }

        void openFind() {
            if (driving()) { host.musicToast("Search is available when parked"); return; }
            if (!host.storageAllowed()) { host.allowStorage(); return; }
            finding = true; q = "";
            loadRecent();
            query.setText("");
            query.setHint("Search " + srcName());
            buildIndex();
            scrollTo0(); markDirty();
            showKeyboard();
        }

        void closeFind() {
            boolean wasAdd = addTo != null;
            finding = false; q = ""; addTo = null;
            if (wasAdd) reloadOpen();
            hideKeyboard();
            query.setVisibility(GONE);
            scrollTo0(); markDirty();
        }

        void onQuery(String t) { if (!finding) return; q = t == null ? "" : t; scrollTo0(); markDirty(); }

        void remember() {
            if (addTo != null) return;
            String t = q.trim();
            if (t.isEmpty()) return;
            for (int i = recentQ.size() - 1; i >= 0; i--) if (recentQ.get(i).equalsIgnoreCase(t)) recentQ.remove(i);
            recentQ.add(0, t);
            while (recentQ.size() > 6) recentQ.remove(recentQ.size() - 1);
            StringBuilder b = new StringBuilder();
            for (String r0 : recentQ) { if (b.length() > 0) b.append('\n'); b.append(r0); }
            getContext().getSharedPreferences("drift_music", Context.MODE_PRIVATE).edit().putString("recentSearches", b.toString()).apply();
        }

        private String nextName() {
            MusicStore st = MusicStore.get(getContext());
            int n = st.lists.size() + 1;
            while (true) {
                boolean used = false;
                for (MusicStore.Playlist pl : st.lists) if (pl.name.equalsIgnoreCase("My playlist #" + n)) used = true;
                if (!used) return "My playlist #" + n;
                n++;
            }
        }

        /** Ask for a name. Typing needs you parked; on the move the suggested name is kept. */
        private void askName(String title, String ok, final String initial, final Sheet.OnText done) {
            if (driving()) { done.onText(initial); host.musicToast("Named \u201c" + initial + "\u201d. Rename it when parked"); return; }
            host.musicInput(title, initial, ok, "You can rename it any time from the playlist\u2019s \u22ef menu.", done);
        }

        private MusicLibrary.Folder folderOf(MusicLibrary.Song sg) {
            File parent = new File(sg.path).getParentFile();
            if (parent == null) return null;
            for (MusicLibrary.Folder f : usbFolders) if (f.dir.equals(parent)) return f;
            for (MusicLibrary.Folder f : intFolders) if (f.dir.equals(parent)) return f;
            return null;
        }

        /** ⋯ on a song (or hold it). */
        private void songMenu(final MusicLibrary.Song sg) {
            final MusicStore st = MusicStore.get(getContext());
            final List<String> items = new ArrayList<>();
            final List<Runnable> acts = new ArrayList<>();
            items.add("Add to playlist");
            acts.add(new Runnable() { @Override public void run() { picker(sg); } });
            final boolean fav = st.isFavourite(sg.path);
            items.add(fav ? "Remove from Favourites" : "Add to Favourites");
            acts.add(new Runnable() { @Override public void run() { st.toggleFavourite(sg.path); host.musicToast(fav ? "Removed from Favourites" : "Added to Favourites"); } });
            if (!finding && openList != null && openList.kind == Row.LIST && openList.playlist.items.contains(sg.path)) {
                final MusicStore.Playlist pl = openList.playlist;
                items.add("!Remove from this playlist");
                acts.add(new Runnable() { @Override public void run() { st.remove(pl, sg.path); host.musicToast("Removed from " + pl.name); } });
            }
            final MusicLibrary.Folder home = folderOf(sg);
            if (home != null && !(openList != null && openList.kind == Row.FOLDER && openList.folder.dir.equals(home.dir))) {
                items.add("Go to folder \u00b7 " + home.name);
                acts.add(new Runnable() { @Override public void run() {
                    if (finding) closeFind();
                    src = usbFolders.contains(home) ? USB : INTERNAL;
                    openList(new OpenList(home.name, Row.FOLDER, home, null));
                } });
            }
            host.musicSheet(sg.title, items.toArray(new String[0]), new Sheet.OnPick() {
                @Override public void onPick(int i) { if (i >= 0 && i < acts.size()) { acts.get(i).run(); reloadOpen(); markDirty(); left.invalidate(); } }
            });
        }

        /** Spotify-style picker: tick the playlists this song should be in. */
        private void picker(final MusicLibrary.Song sg) {
            final MusicStore st = MusicStore.get(getContext());
            final int n = st.lists.size() + 1;
            String[] labels = new String[n], subs = new String[n];
            boolean[] sel = new boolean[n];
            labels[0] = "Favourites"; subs[0] = count(st.favourites.size()); sel[0] = st.isFavourite(sg.path);
            for (int i = 0; i < st.lists.size(); i++) {
                MusicStore.Playlist pl = st.lists.get(i);
                labels[i + 1] = pl.name; subs[i + 1] = count(pl.items.size()); sel[i + 1] = pl.items.contains(sg.path);
            }
            final List<MusicStore.Playlist> snapshot = new ArrayList<>(st.lists);
            host.musicChecks("Add to playlist", labels, subs, sel, "New playlist", new Runnable() {
                @Override public void run() {
                    askName("Give your playlist a name", "Create", nextName(), new Sheet.OnText() {
                        @Override public void onText(String name) {
                            MusicStore.Playlist pl = st.create(name);
                            st.add(pl, sg.path);
                            host.musicToast("Added to " + name);
                            markDirty();
                        }
                    });
                }
            }, new Sheet.OnChecks() {
                @Override public void onDone(boolean[] on) {
                    List<String> added = new ArrayList<>();
                    int removed = 0;
                    if (on[0] != st.isFavourite(sg.path)) { st.toggleFavourite(sg.path); if (on[0]) added.add("Favourites"); else removed++; }
                    for (int i = 0; i < snapshot.size(); i++) {
                        MusicStore.Playlist pl = snapshot.get(i);
                        boolean has = pl.items.contains(sg.path);
                        if (on[i + 1] && !has) { st.add(pl, sg.path); added.add(pl.name); }
                        if (!on[i + 1] && has) { st.remove(pl, sg.path); removed++; }
                    }
                    host.musicToast(added.size() == 1 ? "Added to " + added.get(0) : added.size() > 1 ? "Saved in " + added.size() + " playlists"
                            : removed > 0 ? "Playlists updated" : "No changes");
                    reloadOpen(); markDirty(); left.invalidate();
                }
            });
        }

        /** ⋯ in a playlist's header. */
        private void playlistMenu(final MusicStore.Playlist pl) {
            final MusicStore st = MusicStore.get(getContext());
            host.musicSheet(pl.name, new String[] { "Add songs", "Rename", "!Delete playlist" }, new Sheet.OnPick() {
                @Override public void onPick(int i) {
                    if (i == 0) openAdd(pl);
                    else if (i == 1) askName("Rename playlist", "Save", pl.name, new Sheet.OnText() {
                        @Override public void onText(String name) {
                            st.rename(pl, name);
                            if (openList != null && openList.playlist == pl) openList = new OpenList(name, Row.LIST, null, pl);
                            reloadOpen(); markDirty();
                            host.musicToast("Renamed to " + name);
                        }
                    });
                    else if (i == 2) host.musicSheet("Delete \u201c" + pl.name + "\u201d?", new String[] { "!Delete playlist (songs stay on your storage)", "Keep it" }, new Sheet.OnPick() {
                        @Override public void onPick(int j) {
                            if (j != 0) return;
                            st.delete(pl);
                            openList = null; scrollTo0(); markDirty();
                            host.musicToast("Playlist deleted");
                        }
                    });
                }
            });
        }

        private void loadRecent() {
            recentQ.clear();
            String v = getContext().getSharedPreferences("drift_music", Context.MODE_PRIVATE).getString("recentSearches", "");
            for (String r0 : v.split("\n")) if (!r0.trim().isEmpty()) recentQ.add(r0);
        }

        private String srcName() { return src == USB ? "USB stick" : src == INTERNAL ? "Internal" : "Playlists"; }

        /** Every song on the current tab with the folder or playlist it plays in. Built off the main thread. */
        private static final int ALL = 99;

        private void buildIndex() {
            final int which = addTo != null ? ALL : src, gen = ++indexGen;
            index = null;
            final List<MusicLibrary.Folder> fs = new ArrayList<>();
            final List<MusicLibrary.Song> loose = new ArrayList<>();
            if (which == USB || which == ALL) { fs.addAll(usbFolders); loose.addAll(usbLoose); }
            if (which == INTERNAL || which == ALL) { fs.addAll(intFolders); loose.addAll(intLoose); }
            final MusicStore st = MusicStore.get(getContext());
            final List<MusicStore.Playlist> lists = new ArrayList<>(st.lists);
            final List<String> favs = new ArrayList<>(st.favourites);
            scan.execute(new Runnable() {
                @Override public void run() {
                    MusicLibrary lib = MusicLibrary.get(getContext());
                    final List<Hit> out = new ArrayList<>();
                    final List<MusicLibrary.Song> untagged = new ArrayList<>();
                    if (which == PLAYLISTS) {
                        List<MusicLibrary.Song> fl = new ArrayList<>();
                        for (String p0 : favs) fl.add(lib.song(p0));
                        for (MusicLibrary.Song s0 : fl) out.add(new Hit(s0, fl, "Favourites"));
                        for (MusicStore.Playlist pl : lists) {
                            List<MusicLibrary.Song> l = new ArrayList<>();
                            for (String p0 : pl.items) l.add(lib.song(p0));
                            for (MusicLibrary.Song s0 : l) out.add(new Hit(s0, l, pl.name));
                        }
                    } else {
                        for (MusicLibrary.Folder f : fs) {
                            List<MusicLibrary.Song> l = lib.songsIn(f.dir);
                            for (MusicLibrary.Song s0 : l) out.add(new Hit(s0, l, f.name));
                        }
                        for (MusicLibrary.Song s0 : loose) out.add(new Hit(s0, loose, which == USB ? "USB stick" : which == INTERNAL ? "Internal" : "Loose songs"));
                    }
                    for (Hit h0 : out) if (!h0.song.tagged) untagged.add(h0.song);
                    post(new Runnable() {
                        @Override public void run() {
                            if (gen != indexGen) return;
                            index = out; indexSrc = which; indexFolders = fs; indexLists = lists;
                            if (!untagged.isEmpty()) MusicLibrary.get(getContext()).readTags(untagged); // artists fill in as tags arrive
                            markDirty();
                        }
                    });
                }
            });
        }

        private void buildFindRows() {
            int want = addTo != null ? ALL : src;
            if (index == null || indexSrc != want) {
                if (indexSrc != want && index != null) buildIndex();
                rows.add(new Row(Row.MESSAGE, "Getting your songs ready\u2026", null, null, null, null));
                return;
            }
            String fq = fold(q.trim());
            if (addTo != null && fq.isEmpty()) { // suggestions: recently played first, then the rest
                MusicStore st = MusicStore.get(getContext());
                java.util.LinkedHashMap<String, Hit> pick = new java.util.LinkedHashMap<>();
                for (String p0 : st.recent) for (Hit h0 : index) if (h0.song.path.equals(p0)) { pick.put(p0, h0); break; }
                for (Hit h0 : index) { if (pick.size() >= 30) break; if (!pick.containsKey(h0.song.path)) pick.put(h0.song.path, h0); }
                rows.add(new Row(Row.HEADER, st.recent.isEmpty() ? "SUGGESTED" : "RECENTLY PLAYED AND MORE", null, null, null, null));
                for (Hit h0 : pick.values()) {
                    Row r0 = new Row(Row.SONG, h0.song.title, (h0.song.artist == null || h0.song.artist.isEmpty() ? "" : h0.song.artist + " \u00b7 ") + h0.listName, null, null, h0.song);
                    rows.add(r0);
                }
                if (pick.isEmpty()) rows.add(new Row(Row.MESSAGE, "No songs found", "Plug in a USB stick or add music to the unit", null, null, null));
                return;
            }
            if (fq.isEmpty()) {
                if (!recentQ.isEmpty()) { rows.add(new Row(Row.HEADER, "RECENT SEARCHES", null, null, null, null)); rows.add(new Row(Row.CHIPS, "", null, null, null, null)); }
                rows.add(new Row(Row.MESSAGE, "Type a song, artist or folder name", "Everything on " + srcName() + " is searched", null, null, null));
                return;
            }
            List<Row> folders = new ArrayList<>(), songs = new ArrayList<>();
            if (addTo != null) { /* adding: songs only */ }
            else if (src == PLAYLISTS) {
                for (MusicStore.Playlist pl : indexLists) if (fold(pl.name).contains(fq)) folders.add(new Row(Row.LIST, pl.name, count(pl.items.size()), null, pl, null));
            } else {
                for (MusicLibrary.Folder f : indexFolders) if (fold(f.name).contains(fq)) folders.add(new Row(Row.FOLDER, f.name, count(f.count), f, null, null));
            }
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int total = 0;
            for (Hit h0 : index) {
                MusicLibrary.Song s0 = h0.song;
                if (!(fold(s0.title).contains(fq) || fold(s0.artist).contains(fq) || fold(h0.listName).contains(fq))) continue;
                if (!seen.add(s0.path)) continue;
                total++;
                if (songs.size() >= 300) continue; // enough to scroll through; keeps typing quick on big sticks
                String sub = (s0.artist == null || s0.artist.isEmpty() ? "" : s0.artist + " \u00b7 ") + h0.listName;
                Row r0 = new Row(Row.SONG, s0.title, sub, null, null, s0);
                r0.ctx = h0.list; r0.ctxName = h0.listName;
                songs.add(r0);
            }
            if (folders.isEmpty() && total == 0) {
                rows.add(new Row(Row.MESSAGE, "No matches for \u201c" + q.trim() + "\u201d", "on " + srcName(), null, null, null));
                return;
            }
            if (!folders.isEmpty()) { rows.add(new Row(Row.HEADER, src == PLAYLISTS ? "PLAYLISTS" : "FOLDERS", null, null, null, null)); rows.addAll(folders); }
            if (total > 0) {
                rows.add(new Row(Row.HEADER, "SONGS \u00b7 " + (total > songs.size() ? songs.size() + " of " + total : String.valueOf(total)), null, null, null, null));
                rows.addAll(songs);
            }
        }

        private float rowH(Row r0) { return r0.kind == Row.HEADER ? 30 : r0.kind == Row.CHIPS ? 52 : ROW_H; }

        private void layoutRows() {
            offs = new float[rows.size() + 1];
            float y = 0;
            for (int i = 0; i < rows.size(); i++) { offs[i] = y; y += rowH(rows.get(i)) + ROW_GAP; }
            offs[rows.size()] = y;
        }

        /** Rebuild the rows on the next draw (only when something actually changed, not every frame). */
        void markDirty() { dirty = true; invalidate(); }

        static final float LIST_T = 164, LIST_H = 440, ROW_H = 82, ROW_GAP = 6;

        RightCard(Context c) {
            super(c);
            fill.setColor(0xFF0B1018);
            stroke.setStyle(Paint.Style.STROKE); stroke.setColor(0x1CFFFFFF);
            inner.setColor(0x05FFFFFF);
            innerLine.setStyle(Paint.Style.STROKE); innerLine.setColor(0x0DFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            tab = (TextPaint) text(c, Fonts.regular(c), 0xFFDCE3EE);
            tab.setTextAlign(Paint.Align.LEFT);
            head = (TextPaint) text(c, Fonts.bold(c), 0xFFFFFFFF); head.setLetterSpacing(-0.02f);
            pill = (TextPaint) text(c, Fonts.semibold(c), 0xFFFFFFFF);
            rt = (TextPaint) text(c, Fonts.regular(c), 0xFFFFFFFF);
            rs = (TextPaint) text(c, Fonts.regular(c), 0xFF9AA9BE);
            dur = (TextPaint) text(c, Fonts.regular(c), 0xFF9AA9BE);
            foot = (TextPaint) text(c, Fonts.regular(c), 0xFFA3B3C9);
            az = (TextPaint) text(c, Fonts.semibold(c), 0xFF8A96A8); az.setTextAlign(Paint.Align.CENTER);
            bubble = (TextPaint) text(c, Fonts.bold(c), 0xFF04070D); bubble.setTextAlign(Paint.Align.CENTER);
            scroller = new OverScroller(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            setClickable(true);
            setHapticFeedbackEnabled(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w == 0) return;
            k = w / 653f;
            sheen.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[] { 0x13FFFFFF, 0x06FFFFFF, 0x05FFFFFF }, new float[] { 0, 0.45f, 1 }, Shader.TileMode.CLAMP));
            stroke.setStrokeWidth(Math.max(1f, k)); innerLine.setStrokeWidth(Math.max(1f, k));
            tab.setTextSize(21 * k); head.setTextSize(34 * k); pill.setTextSize(15 * k);
            rt.setTextSize(22.5f * k); rs.setTextSize(17 * k); dur.setTextSize(17 * k); foot.setTextSize(19 * k);
            az.setTextSize(10.5f * k); bubble.setTextSize(32 * k);
            if (query != null) query.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * k);
        }

        void scrollTo0() { scrollY = 0; scroller.forceFinished(true); }

        void refreshRoot() {
            dirty = true;
            thumbMiss.clear();
            if (!host.storageAllowed()) { invalidate(); return; }
            rescan(USB); rescan(INTERNAL);
        }

        private void rescan(final int which) {
            final MusicLibrary lib = MusicLibrary.get(getContext());
            scanning = true;
            scan.execute(new Runnable() {
                @Override public void run() {
                    final List<File> roots = new ArrayList<>();
                    if (which == USB) roots.addAll(lib.usbRoots()); else roots.add(lib.internalRoot());
                    final List<MusicLibrary.Folder> fs = lib.folders(roots);
                    if (which == INTERNAL) { // the unit's ringtone folders aren't music
                        for (int i = fs.size() - 1; i >= 0; i--) {
                            String n = fs.get(i).name.toLowerCase(Locale.ROOT);
                            if (n.equals("ringtones") || n.equals("notifications") || n.equals("alarms") || n.equals("ui")) fs.remove(i);
                        }
                    }
                    final List<MusicLibrary.Song> loose = lib.looseSongs(roots);
                    post(new Runnable() {
                        @Override public void run() {
                            if (which == USB) { usbFolders.clear(); usbFolders.addAll(fs); usbLoose.clear(); usbLoose.addAll(loose); usbScanned = true; }
                            else { intFolders.clear(); intFolders.addAll(fs); intLoose.clear(); intLoose.addAll(loose); intScanned = true; }
                            scanning = false;
                            markDirty();
                        }
                    });
                }
            });
        }

        // ---------------------------------------------------------------- building the list

        /** Open a folder or playlist: list its songs on the scan thread, then read their tags once. */
        private void openList(final OpenList o) {
            openList = o;
            scrollTo0();
            scan.execute(new Runnable() {
                @Override public void run() {
                    final List<MusicLibrary.Song> list = songsOf(o);
                    post(new Runnable() {
                        @Override public void run() {
                            o.songs = list;
                            List<MusicLibrary.Song> untagged = new ArrayList<>();
                            for (MusicLibrary.Song s : list) if (!s.tagged) untagged.add(s);
                            if (!untagged.isEmpty()) MusicLibrary.get(getContext()).readTags(untagged);
                            markDirty();
                        }
                    });
                }
            });
            markDirty();
        }

        /** Re-list the open playlist after it changed (favourites, add/remove). */
        void reloadOpen() { if (openList != null && openList.kind != Row.FOLDER) openList(openList); }

        private List<MusicLibrary.Song> songsOf(OpenList o) {
            MusicLibrary lib = MusicLibrary.get(getContext());
            MusicStore st = MusicStore.get(getContext());
            List<String> paths = null;
            if (o.kind == Row.FOLDER) return lib.songsIn(o.folder.dir);
            if (o.kind == Row.FAV) paths = st.favourites;
            else if (o.kind == Row.RECENT) paths = st.recent;
            else if (o.kind == Row.LIST) paths = o.playlist.items;
            List<MusicLibrary.Song> out = new ArrayList<>();
            if (paths != null) for (String p : paths) out.add(lib.song(p));
            return out;
        }

        /** Every song of the open list, or of the whole source at the top level ("play everything"). Off the main thread. */
        private List<MusicLibrary.Song> here() {
            if (openList != null) return openList.songs != null ? openList.songs : songsOf(openList);
            MusicLibrary lib = MusicLibrary.get(getContext());
            List<MusicLibrary.Song> out = new ArrayList<>();
            if (src == PLAYLISTS) return out;
            for (MusicLibrary.Folder f : src == USB ? usbFolders : intFolders) out.addAll(lib.songsIn(f.dir));
            out.addAll(src == USB ? usbLoose : intLoose);
            return out;
        }

        private void buildRows() {
            rows.clear();
            buildRowsInner();
            layoutRows();
        }

        private void buildRowsInner() {
            MusicStore st = MusicStore.get(getContext());
            if (finding) { buildFindRows(); return; }
            if (!host.storageAllowed()) { rows.add(new Row(Row.MESSAGE, "Allow access to your music files", "Tap here, then tap Allow", null, null, null)); return; }
            if (openList != null) {
                List<MusicLibrary.Song> songs = openList.songs;
                if (songs == null) { rows.add(new Row(Row.MESSAGE, "Loading\u2026", null, null, null, null)); return; }
                if (openList.kind == Row.LIST) rows.add(new Row(Row.ADDSONGS, "Add songs", songs.isEmpty() ? "Let\u2019s find something for your playlist" : "Search your USB stick and the unit's storage", null, null, null));
                for (MusicLibrary.Song s : songs) rows.add(new Row(Row.SONG, s.title, s.artist, null, null, s));
                if (songs.isEmpty()) rows.add(new Row(Row.MESSAGE, openList.kind == Row.FAV ? "Tap the heart on a song to add it here"
                        : openList.kind == Row.LIST ? "Tap Add songs, or use \u22ef on any song" : "Nothing here yet", null, null, null, null));
                return;
            }
            if (src == PLAYLISTS) {
                rows.add(new Row(Row.FAV, "Favourites", count(st.favourites.size()), null, null, null));
                rows.add(new Row(Row.RECENT, "Recently played", count(st.recent.size()), null, null, null));
                for (MusicStore.Playlist pl : st.lists) rows.add(new Row(Row.LIST, pl.name, count(pl.items.size()), null, pl, null));
                rows.add(new Row(Row.NEW, "New playlist", "Name it, then add songs", null, null, null));
                return;
            }
            boolean scanned = src == USB ? usbScanned : intScanned;
            List<MusicLibrary.Folder> fs = src == USB ? usbFolders : intFolders;
            List<MusicLibrary.Song> loose = src == USB ? usbLoose : intLoose;
            for (MusicLibrary.Folder f : fs) rows.add(new Row(Row.FOLDER, f.name, count(f.count), f, null, null));
            for (MusicLibrary.Song s : loose) rows.add(new Row(Row.SONG, s.title, s.artist, null, null, s));
            if (rows.isEmpty()) rows.add(new Row(Row.MESSAGE, !scanned ? "Reading " + (src == USB ? "the USB stick" : "internal storage") + "…"
                    : src == USB ? "No music found. Plug in a USB stick with songs" : "No music found in internal storage",
                    src == INTERNAL ? "Looked in " + MusicLibrary.get(getContext()).internalRoot().getAbsolutePath() : null, null, null, null));
        }

        private boolean hasSongRows() { for (Row r0 : rows) if (r0.kind == Row.SONG) return true; return false; }

        private String count(int n) { return n + (n == 1 ? " song" : " songs"); }

        private boolean showAZ() { return !finding && openList != null && rows.size() >= 12; }

        private float listRight() { return (showAZ() ? 653 - 36 : 653 - 19) * k; }

        private float contentH() { return (offs.length == 0 ? 0 : offs[offs.length - 1]) * k; }

        private float maxScroll() { return Math.max(0, contentH() - LIST_H * k); }

        // ---------------------------------------------------------------- drawing

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) { scrollY = scroller.getCurrY(); postInvalidateOnAnimation(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (dirty) { buildRows(); dirty = false; }
            scrollY = Math.max(0, Math.min(maxScroll(), scrollY));
            Path shape = cardPath(w, h, !swapped, k);
            drawCard(c, shape, w, h, fill, sheen, stroke);
            Path in = cardPath(w, 612 * k, !swapped, k);
            c.drawPath(in, inner); c.drawPath(in, innerLine);
            drawTabs(c);
            drawHead(c);
            drawList(c);
            if (showAZ()) drawAZ(c);
            drawFoot(c);
        }

        private float[] tabX = { 23, 212, 385, 540 };

        private final RectF addDone = new RectF();

        private void drawTabs(Canvas c) {
            if (addTo != null) { // "Add to <playlist>" + Done replace the tabs
                CharSequence t = TextUtils.ellipsize("Add to " + addTo.name, head, 400 * k, TextUtils.TruncateAt.END);
                float hs = head.getTextSize(); head.setTextSize(28 * k);
                t = TextUtils.ellipsize("Add to " + addTo.name, head, 400 * k, TextUtils.TruncateAt.END);
                c.drawText(t, 0, t.length(), 34 * k, 57 * k, head);
                head.setTextSize(hs);
                float wd = 18 * k + pill.measureText("Done") * 17f / 15f + 18 * k;
                addDone.set(536 * k - wd, 26 * k, 536 * k, 66 * k);
                activePill(c, addDone, 20 * k);
                float ps = pill.getTextSize(); pill.setTextSize(17 * k);
                c.drawText("Done", addDone.left + 18 * k, 52 * k, pill);
                pill.setTextSize(ps);
                return;
            }
            r.set(19 * k, 14 * k, 542 * k, 77 * k);
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x0FFFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 32 * k, 32 * k, p); p.setShader(null);
            line.setColor(0x1FFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(r, 32 * k, 32 * k, line);
            String[] names = { "USB stick", "Internal", "Playlists" };
            String[] ics = { IC_USB, IC_BELL, IC_NOTE };
            for (int i = 0; i < 3; i++) {
                float x0 = tabX[i] * k, x1 = tabX[i + 1] * k;
                if (i == src) {
                    r.set(x0, 18 * k, x1, 73 * k);
                    activePill(c, r, 28 * k);
                }
                if (i > 0 && i != src && i - 1 != src) { line.setColor(0x38FFFFFF); line.setStrokeWidth(1.5f * k); c.drawLine(x0, 30 * k, x0, 61 * k, line); }
                tab.setColor(i == src ? 0xFFFFFFFF : 0xFFDCE3EE);
                float tw = tab.measureText(names[i]), total = 25 * k + 13 * k + tw, sx = (x0 + x1) / 2f - total / 2f;
                icon.setColor(i == src ? 0xFFFFFFFF : 0xFFDCE3EE);
                Path ip = ico(ics[i], 25 * k);
                if (i == USB) ip.setFillType(Path.FillType.EVEN_ODD); // the stick is an outline
                c.save(); c.translate(sx, 33 * k); c.drawPath(ip, icon); c.restore();
                c.drawText(names[i], sx + 38 * k, 53 * k, tab);
            }
        }

        /** The selected tab / Play all look: accent gradient, light top edge, darker bottom, thin outline. No outer glow. */
        private void activePill(Canvas c, RectF rr, float rad) {
            p.setShader(null); p.setColor(acc); c.drawRoundRect(rr, rad, rad, p);
            p.setShader(new LinearGradient(0, rr.top, 0, rr.bottom, new int[] { 0x29FFFFFF, 0x00FFFFFF, 0x47000000 }, new float[] { 0, 0.45f, 1 }, Shader.TileMode.CLAMP));
            c.drawRoundRect(rr, rad, rad, p); p.setShader(null);
            line.setColor(0x1FFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(rr, rad, rad, line);
            line.setColor(0x66FFFFFF); c.drawLine(rr.left + rad, rr.top + 1.2f * k, rr.right - rad, rr.top + 1.2f * k, line);
        }

        private RectF pillPlay = new RectF(), pillShuf = new RectF();
        private boolean pillsShown;

        private final RectF findBtn = new RectF(), plMore = new RectF();

        private void drawFindHead(Canvas c) {
            float cy = 127 * k, fr = addTo != null ? 616 : 540;
            r.set(29 * k, 102 * k, fr * k, 152 * k);
            line.setColor(a(acc, 0.12f)); line.setStrokeWidth(8 * k); c.drawRoundRect(r, 25 * k, 25 * k, line);
            p.setColor(0x0DFFFFFF); c.drawRoundRect(r, 25 * k, 25 * k, p);
            line.setColor(a(acc, 0.75f)); line.setStrokeWidth(1.5f * k); c.drawRoundRect(r, 25 * k, 25 * k, line);
            icon.setColor(0xFF9AA9BE); drawIco(c, IC_SEARCH, 47 * k, cy - 10 * k, 20 * k, icon);
            if (!q.isEmpty()) {
                float xc = (fr - 26) * k;
                p.setColor(0x1AFFFFFF); c.drawCircle(xc, cy, 17 * k, p);
                line.setColor(0xFFDCE3EE); line.setStrokeWidth(2 * k); line.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(xc - 5 * k, cy - 5 * k, xc + 5 * k, cy + 5 * k, line); c.drawLine(xc + 5 * k, cy - 5 * k, xc - 5 * k, cy + 5 * k, line);
                line.setStrokeCap(Paint.Cap.BUTT);
            }
            if (addTo != null) return; // "Done" sits up top in this mode
            pill.setColor(0xFFDCE3EE); float ps = pill.getTextSize(); pill.setTextSize(17 * k);
            c.drawText("Done", 556 * k, cy + 6 * k, pill);
            pill.setTextSize(ps); pill.setColor(0xFFFFFFFF);
        }

        private void drawHead(Canvas c) {
            if (finding) { drawFindHead(c); return; }
            float cy = 127 * k, x = 29 * k;
            String name = openList != null ? openList.name : src == USB ? "USB stick" : src == INTERNAL ? "Internal" : "Playlists";
            if (openList != null) {
                line.setColor(0xFFFFFFFF); line.setStrokeWidth(2.6f * k); line.setStrokeCap(Paint.Cap.ROUND);
                float bx = 50 * k;
                c.drawLine(bx + 4 * k, cy - 8 * k, bx - 4 * k, cy, line); c.drawLine(bx - 4 * k, cy, bx + 4 * k, cy + 8 * k, line);
                line.setStrokeCap(Paint.Cap.BUTT);
                x = 81 * k;
            }
            boolean userList = openList != null && openList.kind == Row.LIST;
            pillsShown = openList != null ? hasSongRows() : src != PLAYLISTS && host.storageAllowed()
                    && !((src == USB ? usbFolders : intFolders).isEmpty() && (src == USB ? usbLoose : intLoose).isEmpty());
            float right = 616 * k;
            if (pillsShown) {
                float wPlay = 16 * k + 16 * k + 8 * k + pill.measureText("Play all") + 16 * k;
                float wShuf = userList ? 40 * k : 16 * k + 16 * k + 8 * k + pill.measureText("Shuffle") + 16 * k;
                pillPlay.set(right - wPlay, cy - 20 * k, right, cy + 20 * k);
                pillShuf.set(pillPlay.left - 12 * k - wShuf, cy - 20 * k, pillPlay.left - 12 * k, cy + 20 * k);
                activePill(c, pillPlay, 20 * k);
                p.setColor(0x0FFFFFFF); c.drawRoundRect(pillShuf, 20 * k, 20 * k, p);
                line.setColor(0x1FFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(pillShuf, 20 * k, 20 * k, line);
                icon.setColor(0xFFFFFFFF);
                drawIco(c, IC_PLAY, pillPlay.left + 16 * k, cy - 8 * k, 16 * k, icon);
                c.drawText("Play all", pillPlay.left + 40 * k, cy + 5.5f * k, pill);
                if (userList) drawIco(c, IC_SHUF, pillShuf.centerX() - 8.5f * k, cy - 8.5f * k, 17 * k, icon); // icon only, room for the name
                else {
                    drawIco(c, IC_SHUF, pillShuf.left + 16 * k, cy - 8 * k, 16 * k, icon);
                    c.drawText("Shuffle", pillShuf.left + 40 * k, cy + 5.5f * k, pill);
                }
                right = pillShuf.left - 12 * k;
            }
            if (userList) { // playlist ⋯: add songs, rename, delete
                findBtn.setEmpty();
                plMore.set(right - 40 * k, cy - 20 * k, right, cy + 20 * k);
                p.setColor(0x0FFFFFFF); c.drawCircle(plMore.centerX(), cy, 20 * k, p);
                line.setColor(0x1FFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawCircle(plMore.centerX(), cy, 20 * k - 0.5f, line);
                p.setColor(0xFFC9D3E3);
                for (int d0 = -1; d0 <= 1; d0++) c.drawCircle(plMore.centerX() + d0 * 8 * k, cy, 2.5f * k, p);
                right = plMore.left - 12 * k;
            } else plMore.setEmpty();
            if (!userList && host.storageAllowed()) { // search: dimmed while driving
                findBtn.set(right - 40 * k, cy - 20 * k, right, cy + 20 * k);
                boolean drv = driving();
                p.setColor(drv ? 0x06FFFFFF : 0x0FFFFFFF); c.drawCircle(findBtn.centerX(), cy, 20 * k, p);
                line.setColor(drv ? 0x0DFFFFFF : 0x1FFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawCircle(findBtn.centerX(), cy, 20 * k - 0.5f, line);
                icon.setColor(drv ? 0x59FFFFFF : 0xFFFFFFFF); drawIco(c, IC_SEARCH, findBtn.centerX() - 9.5f * k, cy - 9.5f * k, 19 * k, icon);
                right = findBtn.left - 12 * k;
            } else findBtn.setEmpty();
            CharSequence t = TextUtils.ellipsize(name, head, Math.max(60 * k, right - 16 * k - x), TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), x, cy + 12 * k, head);
            float after = x + head.measureText(t, 0, t.length()) + 20 * k;
            if (right > after) {
                line.setShader(new LinearGradient(after, 0, right, 0, 0x24FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
                line.setStrokeWidth(Math.max(1f, k)); c.drawLine(after, cy + 6 * k, right, cy + 6 * k, line); line.setShader(null);
            }
        }

        private void drawList(Canvas c) {
            float top = LIST_T * k, bottom = (LIST_T + LIST_H) * k;
            c.save();
            c.clipRect(0, top, getWidth(), bottom);
            MusicService svc = MusicService.get();
            MusicLibrary.Song cur = svc == null ? null : svc.current();
            boolean playing = svc != null && svc.isPlaying();
            String qn = svc == null ? "" : svc.queueName();
            float lr = listRight();
            String fq = finding ? fold(q.trim()) : "";
            for (int i = 0; i < rows.size(); i++) {
                float y = top + offs[i] * k - scrollY;
                Row row = rows.get(i);
                if (y + rowH(row) * k < top || y > bottom) continue;
                if (row.kind == Row.HEADER) {
                    rs.setColor(0xFF7D8AA0); rs.setLetterSpacing(0.14f);
                    float ts = rs.getTextSize(); rs.setTextSize(13 * k);
                    c.drawText(row.title, 28 * k, y + 21 * k, rs);
                    rs.setTextSize(ts); rs.setLetterSpacing(0f); rs.setColor(0xFF9AA9BE);
                    continue;
                }
                if (row.kind == Row.CHIPS) { drawChips(c, y, lr); continue; }
                boolean isCur = (row.kind == Row.SONG && cur != null && cur.path.equals(row.song.path))
                        || (row.kind != Row.SONG && row.kind != Row.MESSAGE && row.kind != Row.NEW && row.title.equals(qn) && openList == null);
                float x0 = isCur ? 7 * k : 28 * k;
                r.set(x0, y, lr, y + ROW_H * k);
                if (row.kind == Row.MESSAGE) {
                    rt.setColor(0xFF9AA9BE); rt.setTextAlign(Paint.Align.CENTER);
                    c.drawText(row.title, (28 * k + lr) / 2f, y + 38 * k, rt);
                    if (row.sub != null) { rs.setTextAlign(Paint.Align.CENTER); c.drawText(row.sub, (28 * k + lr) / 2f, y + 63 * k, rs); rs.setTextAlign(Paint.Align.LEFT); }
                    rt.setTextAlign(Paint.Align.LEFT); rt.setColor(0xFFFFFFFF);
                    continue;
                }
                if (isCur) {
                    p.setShader(new LinearGradient(r.left, 0, r.right, 0, new int[] { a(acc, 0.24f), a(acc, 0.10f), a(acc, 0.06f) }, new float[] { 0, 0.6f, 1 }, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, 14 * k, 14 * k, p); p.setShader(null);
                    line.setColor(a(acc, 0.28f));
                } else {
                    p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x0AFFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, 14 * k, 14 * k, p); p.setShader(null);
                    line.setColor(0x0DFFFFFF);
                }
                if (i == downRow && !dragging) { p.setColor(0x12FFFFFF); c.drawRoundRect(r, 14 * k, 14 * k, p); }
                line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(r, 14 * k, 14 * k, line);
                if (isCur) { // accent bar on the left edge
                    c.save(); c.clipRect(r.left, r.top, r.left + 6 * k, r.bottom);
                    p.setColor(acc); c.drawRoundRect(r, 14 * k, 14 * k, p); c.restore();
                }
                // thumb
                float tx = r.left + (isCur ? 33 : 12) * k, ty = y + 7 * k;
                RectF th = new RectF(tx, ty, tx + 68 * k, ty + 68 * k);
                drawThumb(c, row, th);
                // text
                float txt = th.right + 28 * k, maxW = lr - txt - 70 * k;
                rt.setColor(0xFFFFFFFF);
                CharSequence t1 = TextUtils.ellipsize(row.title, rt, maxW, TextUtils.TruncateAt.END);
                drawMarked(c, t1.toString(), fq, txt, y + 37 * k, rt, 0xFFFFFFFF);
                if (row.sub != null) {
                    CharSequence t2 = TextUtils.ellipsize(row.sub, rs, maxW, TextUtils.TruncateAt.END);
                    drawMarked(c, t2.toString(), fq, txt, y + 62 * k, rs, 0xFF9AA9BE);
                }
                // right side: duration / playing bars / chevron
                float rx = lr - 24 * k, cy = y + ROW_H * k / 2f;
                if (row.kind == Row.SONG && addTo != null) { // + / tick
                    boolean on = addTo.items.contains(row.song.path);
                    float bx = rx - 14 * k;
                    if (on) {
                        p.setColor(acc); c.drawCircle(bx, cy, 22 * k, p);
                        line.setColor(0xFFFFFFFF); line.setStrokeWidth(2.8f * k); line.setStrokeCap(Paint.Cap.ROUND);
                        c.drawLine(bx - 8 * k, cy, bx - 2.5f * k, cy + 6 * k, line); c.drawLine(bx - 2.5f * k, cy + 6 * k, bx + 9 * k, cy - 6 * k, line);
                    } else {
                        line.setColor(0x8CFFFFFF); line.setStrokeWidth(2 * k); c.drawCircle(bx, cy, 21 * k, line);
                        line.setColor(0xFFFFFFFF); line.setStrokeWidth(2.4f * k); line.setStrokeCap(Paint.Cap.ROUND);
                        c.drawLine(bx - 8 * k, cy, bx + 8 * k, cy, line); c.drawLine(bx, cy - 8 * k, bx, cy + 8 * k, line);
                    }
                    line.setStrokeCap(Paint.Cap.BUTT);
                } else if (row.kind == Row.SONG) {
                    float mx = rx - 8 * k; // ⋯
                    p.setColor(0xFFC9D3E3);
                    for (int d0 = -1; d0 <= 1; d0++) c.drawCircle(mx + d0 * 9 * k, cy, 2.6f * k, p);
                    float dx = mx - 36 * k;
                    if (cur != null && cur.path.equals(row.song.path)) drawEq(c, dx - 18 * k, cy + 10 * k, playing);
                    else if (row.song.durationMs > 0) { String d = fmt(row.song.durationMs); c.drawText(d, dx - dur.measureText(d), cy + 6 * k, dur); }
                } else {
                    line.setColor(0xFFDCE3EE); line.setStrokeWidth(2.6f * k); line.setStrokeCap(Paint.Cap.ROUND);
                    float cx = rx - 6 * k;
                    c.drawLine(cx - 4 * k, cy - 8 * k, cx + 4 * k, cy, line); c.drawLine(cx + 4 * k, cy, cx - 4 * k, cy + 8 * k, line);
                    line.setStrokeCap(Paint.Cap.BUTT);
                }
            }
            c.restore();
            removeCallbacks(eqTick);
            if (playing && open) postDelayed(eqTick, 125); // keep the playing bars moving (8 fps)
        }

        /** Text with the search match in the accent colour. */
        private void drawMarked(Canvas c, String t, String fq, float x, float y, TextPaint tp, int col) {
            int at = fq.isEmpty() ? -1 : fold(t).indexOf(fq);
            tp.setColor(col);
            if (at < 0) { c.drawText(t, x, y, tp); return; }
            int end = Math.min(t.length(), at + fq.length());
            String a0 = t.substring(0, at), m0 = t.substring(at, end), z0 = t.substring(end);
            c.drawText(a0, x, y, tp); x += tp.measureText(a0);
            tp.setColor(acc); tp.setFakeBoldText(true); c.drawText(m0, x, y, tp); x += tp.measureText(m0); tp.setFakeBoldText(false);
            tp.setColor(col); c.drawText(z0, x, y, tp);
        }

        private void drawChips(Canvas c, float y, float lr) {
            chipRects.clear(); chipText.clear();
            float x = 28 * k, top = y + 6 * k, ts = rs.getTextSize();
            rs.setTextSize(17 * k);
            for (String t : recentQ) {
                float w = 16 * k + 15 * k + 8 * k + rs.measureText(t) + 16 * k;
                if (x + w > lr) break;
                r.set(x, top, x + w, top + 40 * k);
                p.setColor(0x0FFFFFFF); c.drawRoundRect(r, 20 * k, 20 * k, p);
                line.setColor(0x1AFFFFFF); line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(r, 20 * k, 20 * k, line);
                icon.setColor(0xFF9AA9BE); drawIco(c, IC_CLOCK, x + 16 * k, top + 12.5f * k, 15 * k, icon);
                rs.setColor(0xFFDCE3EE); c.drawText(t, x + 39 * k, top + 26 * k, rs);
                chipRects.add(new RectF(r.left, r.top + scrollY, r.right, r.bottom + scrollY)); chipText.add(t);
                x += w + 10 * k;
            }
            rs.setTextSize(ts); rs.setColor(0xFF9AA9BE);
        }

        private void drawEq(Canvas c, float x, float base, boolean moving) {
            float t = SystemClock.uptimeMillis() / 1000f;
            p.setColor(acc);
            for (int i = 0; i < 3; i++) {
                float hgt = moving ? (6 + 14 * (0.5f + 0.5f * (float) Math.sin(t * 7 + i * 2.1f))) : 6;
                r.set(x + i * 7 * k, base - hgt * k, x + i * 7 * k + 4 * k, base);
                c.drawRoundRect(r, 1 * k, 1 * k, p);
            }
        }

        private void drawThumb(Canvas c, Row row, RectF th) {
            float rad = 12 * k;
            switch (row.kind) {
                case Row.SONG: {
                    Bitmap b = thumb(row.song.path);
                    if (b != null) drawBitmapRound(c, b, th, rad, img); else placeholder(c, th, row.song.path, rad, img);
                    return;
                }
                case Row.FAV: {
                    MusicStore st = MusicStore.get(getContext());
                    Bitmap b = st.favourites.isEmpty() ? null : thumb(st.favourites.get(0));
                    if (b != null) { drawBitmapRound(c, b, th, rad, img); return; }
                    if (!st.favourites.isEmpty()) { placeholder(c, th, st.favourites.get(0), rad, img); return; }
                    tile(c, th, rad, 0x0FFFFFFF, 0x14FFFFFF); iconIn(c, th, IC_HEART, acc); return;
                }
                case Row.RECENT: tile(c, th, rad, 0x0FFFFFFF, 0x14FFFFFF); iconIn(c, th, IC_CLOCK, 0xFFC9D3E3); return;
                case Row.LIST:
                    img.setShader(new LinearGradient(th.left, th.top, th.right, th.bottom, new int[] { 0xFF3A1D8A, 0xFF7B2FF7, 0xFF2B0F5C }, new float[] { 0, 0.6f, 1 }, Shader.TileMode.CLAMP));
                    c.drawRoundRect(th, rad, rad, img); img.setShader(null);
                    iconIn(c, th, IC_MIX, 0xFFE6D9FF); return;
                case Row.NEW: case Row.ADDSONGS:
                    line.setColor(0x40FFFFFF); line.setStrokeWidth(1.5f * k); c.drawRoundRect(th, rad, rad, line);
                    iconIn(c, th, IC_ADD, 0xFFC9D3E3); return;
                default: // folder
                    tile(c, th, rad, a(acc, 0.12f), a(acc, 0.3f)); iconIn(c, th, IC_FOLD, acc);
            }
        }

        private void tile(Canvas c, RectF th, float rad, int fillC, int lineC) {
            p.setColor(fillC); c.drawRoundRect(th, rad, rad, p);
            line.setColor(lineC); line.setStrokeWidth(Math.max(1f, k)); c.drawRoundRect(th, rad, rad, line);
        }

        private void iconIn(Canvas c, RectF th, String d, int color) {
            icon.setColor(color);
            float s = 30 * k;
            drawIco(c, d, th.centerX() - s / 2, th.centerY() - s / 2, s, icon);
        }

        private Bitmap thumb(final String path) {
            Bitmap b = thumbs.get(path);
            if (b != null || thumbLoading.contains(path) || thumbMiss.contains(path)) return b;
            thumbLoading.add(path);
            thumbIo.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bm = MusicLibrary.cover(path, 136);
                    post(new Runnable() {
                        @Override public void run() {
                            thumbLoading.remove(path);
                            if (bm != null) { thumbs.put(path, bm); invalidate(); } else thumbMiss.add(path);
                        }
                    });
                }
            });
            return null;
        }

        private void drawAZ(Canvas c) {
            float top = 166 * k, h = 436 * k, x = (653 - 17) * k;
            for (int i = 0; i < 26; i++) {
                String L = String.valueOf((char) ('A' + i));
                az.setColor(L.equals(azLetter) ? acc : 0xFF8A96A8);
                c.drawText(L, x, top + (i + 0.5f) * h / 26f + 4 * k, az);
            }
            if (azLetter != null) {
                float cy = top + (azLetter.charAt(0) - 'A' + 0.5f) * h / 26f;
                p.setColor(acc); c.drawCircle((653 - 84) * k, cy, 32 * k, p);
                c.drawText(azLetter, (653 - 84) * k, cy + 11 * k, bubble);
            }
        }

        private RectF vol = new RectF();

        private void drawFoot(Canvas c) {
            float cy = 647 * k, x = 36 * k;
            p.setColor(acc);
            float[] hs = { 12, 20, 8, 16 };
            for (int i = 0; i < 4; i++) { r.set(x + i * 8 * k, cy + 12 * k - hs[i] * k, x + i * 8 * k + 5 * k, cy + 12 * k); c.drawRoundRect(r, k, k, p); }
            float volR = 616 * k, volL = volR - 163 * k, iconX = volL - 22 * k - 28 * k;
            icon.setColor(0xFFE8EEF7); drawIco(c, IC_VOL, iconX, cy - 14 * k, 28 * k, icon);
            // up next
            MusicService svc = MusicService.get();
            float tx = x + 29 * k + 14 * k, maxW = iconX - 14 * k - tx;
            String a1, a2 = "", a3 = "";
            if (svc == null || !svc.hasSong()) a1 = "Nothing playing";
            else if (svc.shuffle()) { a1 = "Up next: "; a2 = "shuffle"; }
            else {
                MusicLibrary.Song n = svc.upNext();
                if (n == null) a1 = "Last song in this list";
                else { a1 = "Up next: "; a2 = n.title; a3 = n.artist == null || n.artist.isEmpty() ? "" : " · " + n.artist; }
            }
            foot.setColor(0xFFA3B3C9);
            c.drawText(a1, tx, cy + 7 * k, foot);
            float used = foot.measureText(a1);
            if (!a2.isEmpty()) {
                foot.setColor(0xFFDCE3EE);
                CharSequence s2 = TextUtils.ellipsize(a2, foot, maxW - used, TextUtils.TruncateAt.END);
                c.drawText(s2, 0, s2.length(), tx + used, cy + 7 * k, foot);
                used += foot.measureText(s2, 0, s2.length());
                if (!a3.isEmpty() && used < maxW) {
                    foot.setColor(0xFFA3B3C9);
                    CharSequence s3 = TextUtils.ellipsize(a3, foot, maxW - used, TextUtils.TruncateAt.END);
                    c.drawText(s3, 0, s3.length(), tx + used, cy + 7 * k, foot);
                }
            }
            // volume (the unit's media volume)
            float f = 0;
            try { f = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); } catch (Throwable ignored) { }
            vol.set(volL, cy - 2.5f * k, volR, cy + 2.5f * k);
            p.setColor(0x29FFFFFF); c.drawRoundRect(vol, 3 * k, 3 * k, p);
            r.set(volL, vol.top, volL + (volR - volL) * f, vol.bottom);
            p.setColor(acc); c.drawRoundRect(r, 3 * k, 3 * k, p);
            p.setColor(a(acc, 0.35f)); c.drawCircle(r.right, cy, 14 * k, p);
            p.setColor(0xFFFFFFFF); c.drawCircle(r.right, cy, 10 * k, p);
        }

        // ---------------------------------------------------------------- touch

        private static final int Z_NONE = 0, Z_TAB = 1, Z_BACK = 2, Z_PLAY = 3, Z_SHUF = 4, Z_LIST = 5, Z_AZ = 6, Z_VOL = 7, Z_FIND = 8, Z_DONE = 9, Z_CLEAR = 10, Z_PLMORE = 11, Z_ADDDONE = 12, Z_VOLINFO = 13;

        private int zone(float x, float y) {
            float X = x / k, Y = y / k;
            if (addTo != null && Y >= 14 && Y <= 77) return x >= addDone.left - 8 * k && x <= addDone.right + 8 * k ? Z_ADDDONE : Z_NONE;
            if (Y >= 14 && Y <= 77 && X >= 19 && X <= 542) return Z_TAB;
            if (Y >= 101 && Y <= 153 && finding) {
                float fr = addTo != null ? 616 : 540;
                if (addTo == null && X >= 546) return Z_DONE;
                if (!q.isEmpty() && X >= fr - 48 && X <= fr) return Z_CLEAR;
                return Z_NONE;
            }
            if (Y >= 101 && Y <= 153) {
                if (!findBtn.isEmpty() && x >= findBtn.left - 6 * k && x <= findBtn.right + 6 * k) return Z_FIND;
                if (!plMore.isEmpty() && x >= plMore.left - 6 * k && x <= plMore.right + 6 * k) return Z_PLMORE;
                if (openList != null && X < 75) return Z_BACK;
                if (pillsShown && pillPlay.contains(x, y)) return Z_PLAY;
                if (pillsShown && pillShuf.contains(x, y)) return Z_SHUF;
            }
            if (showAZ() && X >= 617 && Y >= 160 && Y <= 606) return Z_AZ;
            if (Y >= LIST_T && Y <= LIST_T + LIST_H) return Z_LIST;
            if (Y >= 624 && Y <= 670 && X >= 440) return Z_VOL;
            if (Y >= 620 && Y <= 674 && X >= 392 && X < 440) return Z_VOLINFO;
            return Z_NONE;
        }

        private int rowAt(float y) {
            float rel = (y - LIST_T * k + scrollY) / k;
            for (int i = 0; i < rows.size(); i++) {
                if (rel >= offs[i] && rel <= offs[i] + rowH(rows.get(i))) return rows.get(i).kind == Row.HEADER ? -1 : i;
            }
            return -1;
        }

        private void azPick(float y) {
            float top = 166 * k, h = 436 * k;
            int i = Math.max(0, Math.min(25, (int) ((y - top) / h * 26)));
            String L = String.valueOf((char) ('A' + i));
            azLetter = L;
            // songs are in file order, so look for the first title starting with this letter (or the next one present)
            outer:
            for (char ch = L.charAt(0); ch <= 'Z'; ch++) {
                for (int r0 = 0; r0 < rows.size(); r0++) {
                    Row row = rows.get(r0);
                    if (row.kind == Row.SONG && !row.title.isEmpty() && Character.toUpperCase(row.title.charAt(0)) == ch) {
                        scrollY = Math.min(maxScroll(), offs[r0] * k); break outer;
                    }
                }
            }
            invalidate();
        }

        private boolean volWarned;

        private void setVol(float x) {
            float volR = 616 * k, volL = volR - 163 * k;
            float f = Math.max(0, Math.min(1, (x - volL) / (volR - volL)));
            try {
                if (audio.isVolumeFixed()) { if (!volWarned) { volWarned = true; host.musicToast("This unit sets the volume itself. Use the knob or steering buttons"); } return; }
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0);
            } catch (Throwable ignored) { }
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.forceFinished(true);
                    downX = e.getX(); downY = lastY = e.getY();
                    downZone = zone(e.getX(), e.getY());
                    dragging = false; longFired = false;
                    downRow = downZone == Z_LIST ? rowAt(e.getY()) : -1;
                    if (downZone == Z_AZ) { azDrag = true; azPick(e.getY()); if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); }
                    if (downZone == Z_VOL) setVol(e.getX());
                    if (downRow >= 0) postDelayed(longPress, 550);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (azDrag) { azPick(e.getY()); return true; }
                    if (downZone == Z_VOL) { setVol(e.getX()); return true; }
                    if (downZone == Z_LIST) {
                        if (!dragging && Math.abs(e.getY() - downY) > slop) { dragging = true; removeCallbacks(longPress); }
                        if (dragging) { scrollY = Math.max(0, Math.min(maxScroll(), scrollY + (lastY - e.getY()))); invalidate(); }
                    }
                    lastY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (azDrag) { azDrag = false; postDelayed(new Runnable() { @Override public void run() { azLetter = null; invalidate(); } }, 350); }
                    else if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        scroller.fling(0, (int) scrollY, 0, (int) -vt.getYVelocity(), 0, 0, 0, (int) maxScroll());
                        postInvalidateOnAnimation();
                    } else if (!longFired) tap(e.getX(), e.getY());
                    dragging = false; downRow = -1;
                    vt.recycle(); vt = null;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    azDrag = false; azLetter = null; dragging = false; downRow = -1;
                    if (vt != null) { vt.recycle(); vt = null; }
                    invalidate();
                    return true;
            }
            return true;
        }

        private void tap(float x, float y) {
            int z = zone(x, y);
            if (z != downZone) return;
            MusicService svc = MusicService.get();
            switch (z) {
                case Z_TAB: {
                    float X = x / k;
                    int t = X < 212 ? USB : X < 385 ? INTERNAL : PLAYLISTS;
                    if (t != src || openList != null) { src = t; openList = null; scrollTo0(); if (t != PLAYLISTS) rescan(t); }
                    if (finding) { query.setHint("Search " + srcName()); buildIndex(); }
                    break;
                }
                case Z_FIND: openFind(); return;
                case Z_PLMORE: if (openList != null && openList.playlist != null) playlistMenu(openList.playlist); return;
                case Z_ADDDONE: closeFind(); return;
                case Z_VOLINFO: volumeInfo(); return;
                case Z_DONE: remember(); closeFind(); return;
                case Z_CLEAR: query.setText(""); showKeyboard(); return;
                case Z_NONE: {
                    if (finding && y / k >= 101 && y / k <= 153) showKeyboard(); // tap on the field
                    break;
                }
                case Z_BACK: openList = null; scrollTo0(); break;
                case Z_PLAY: case Z_SHUF: {
                    if (svc == null) { MusicService.start(getContext()); host.musicToast("Starting the player, tap again"); return; }
                    final boolean sh = z == Z_SHUF, top = openList == null;
                    final String name = openList != null ? openList.name : (src == USB ? "USB stick" : "Internal");
                    scan.execute(new Runnable() {
                        @Override public void run() {
                            final List<MusicLibrary.Song> all = here();
                            post(new Runnable() {
                                @Override public void run() {
                                    MusicService s = MusicService.get();
                                    if (all.isEmpty() || s == null) return;
                                    s.playList(all, sh ? (int) (Math.random() * all.size()) : 0, name, sh);
                                    if (top) host.musicToast((sh ? "Shuffling everything on " : "Playing everything on ") + name);
                                }
                            });
                        }
                    });
                    break;
                }
                case Z_LIST: {
                    int i = rowAt(y);
                    if (i < 0 || i != downRow) return;
                    Row row = rows.get(i);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    switch (row.kind) {
                        case Row.MESSAGE: if (!host.storageAllowed()) host.allowStorage(); break;
                        case Row.CHIPS: {
                            for (int ci = 0; ci < chipRects.size(); ci++) if (chipRects.get(ci).contains(x, y + scrollY)) { query.setText(chipText.get(ci)); query.setSelection(query.length()); break; }
                            return;
                        }
                        case Row.FOLDER: if (finding) { remember(); closeFind(); } openList(new OpenList(row.title, Row.FOLDER, row.folder, null)); break;
                        case Row.FAV: openList(new OpenList("Favourites", Row.FAV, null, null)); break;
                        case Row.RECENT: openList(new OpenList("Recently played", Row.RECENT, null, null)); break;
                        case Row.LIST: if (finding) { remember(); closeFind(); } openList(new OpenList(row.playlist.name, Row.LIST, null, row.playlist)); break;
                        case Row.NEW: {
                            askName("Give your playlist a name", "Create", nextName(), new Sheet.OnText() {
                                @Override public void onText(String name) {
                                    MusicStore.Playlist pl = MusicStore.get(getContext()).create(name);
                                    openList(new OpenList(pl.name, Row.LIST, null, pl));
                                }
                            });
                            break;
                        }
                        case Row.ADDSONGS: if (openList != null && openList.playlist != null) openAdd(openList.playlist); return;
                        case Row.SONG: {
                            if (addTo != null) { // + / tick
                                MusicStore st = MusicStore.get(getContext());
                                if (addTo.items.contains(row.song.path)) { st.remove(addTo, row.song.path); host.musicToast("Removed from " + addTo.name); }
                                else { st.add(addTo, row.song.path); host.musicToast("Added to " + addTo.name); }
                                invalidate();
                                return;
                            }
                            if (x >= listRight() - 64 * k) { songMenu(row.song); return; } // ⋯
                            if (svc == null) { MusicService.start(getContext()); host.musicToast("Starting the player, tap again"); return; }
                            if (row.ctx != null) { // search result: play it inside its own folder or playlist
                                remember(); hideKeyboard();
                                svc.playList(row.ctx, Math.max(0, row.ctx.indexOf(row.song)), row.ctxName, svc.shuffle());
                                break;
                            }
                            List<MusicLibrary.Song> list = new ArrayList<>();
                            for (Row r0 : rows) if (r0.kind == Row.SONG) list.add(r0.song);
                            int start = list.indexOf(row.song);
                            String name = openList != null ? openList.name : (src == USB ? "USB stick" : "Internal");
                            svc.playList(list, Math.max(0, start), name, svc.shuffle());
                            break;
                        }
                    }
                    break;
                }
            }
            markDirty();
        }

        /** Hold a song: favourites / playlists. Hold a playlist: delete it. */
        private void onLong() {
            if (downRow < 0 || downRow >= rows.size() || dragging) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            final Row row = rows.get(downRow);
            final MusicStore st = MusicStore.get(getContext());
            if (row.kind == Row.LIST) {
                host.musicSheet(row.playlist.name, new String[] { "!Delete playlist", "Cancel" }, new Sheet.OnPick() {
                    @Override public void onPick(int i) { if (i == 0) { st.delete(row.playlist); markDirty(); } }
                });
                return;
            }
            if (row.kind != Row.SONG) return;
            if (addTo == null) { songMenu(row.song); return; }
            final String path = row.song.path;
            final List<String> items = new ArrayList<>();
            final List<Runnable> acts = new ArrayList<>();
            final boolean fav = st.isFavourite(path);
            items.add(fav ? "Remove from Favourites" : "Add to Favourites");
            acts.add(new Runnable() { @Override public void run() { st.toggleFavourite(path); host.musicToast(fav ? "Removed from Favourites" : "Added to Favourites"); } });
            if (!finding && openList != null && openList.kind == Row.LIST) {
                items.add("!Remove from " + openList.name);
                final MusicStore.Playlist pl = openList.playlist;
                acts.add(new Runnable() { @Override public void run() { st.remove(pl, path); } });
            }
            for (final MusicStore.Playlist pl : st.lists) {
                if (pl.items.contains(path)) continue;
                items.add("Add to " + pl.name);
                acts.add(new Runnable() { @Override public void run() { st.add(pl, path); host.musicToast("Added to " + pl.name); } });
            }
            items.add("Add to a new playlist");
            acts.add(new Runnable() { @Override public void run() { MusicStore.Playlist pl = st.create(st.nextName()); st.add(pl, path); host.musicToast("Added to " + pl.name); } });
            host.musicSheet(row.title, items.toArray(new String[0]), new Sheet.OnPick() {
                @Override public void onPick(int i) { if (i >= 0 && i < acts.size()) { acts.get(i).run(); reloadOpen(); markDirty(); left.invalidate(); } }
            });
        }
    }

    // =================================================================== close button

    private static class CloseButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), x = new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseButton(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE); line.setColor(0x29FFFFFF);
            x.setColor(0xFFFFFFFF); x.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setContentDescription("Close");
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), k = w / 58f, cx = w / 2f;
            bg.setShader(new RadialGradient(cx, cx * 0.6f, cx * 1.2f, 0xFF1A2230, 0xFF0A0F18, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cx, cx - k, bg);
            if (isPressed()) { bg.setShader(null); bg.setColor(0x24FFFFFF); c.drawCircle(cx, cx, cx - k, bg); }
            line.setStrokeWidth(Math.max(1f, k)); c.drawCircle(cx, cx, cx - k, line);
            x.setStrokeWidth(2.4f * k);
            float d = 8.5f * k;
            c.drawLine(cx - d, cx - d, cx + d, cx + d, x); c.drawLine(cx - d, cx + d, cx + d, cx - d, x);
        }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }
}
