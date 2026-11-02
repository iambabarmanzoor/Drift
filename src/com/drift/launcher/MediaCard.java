/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Live parts of the music card, drawn over the glass card in the artwork: cover, title, artist,
 * progress bar, times and the play/pause glyph. Design size 420 x 428 (card at x=34, y=106).
 * All coordinates below are measured from the reference design.
 */
public class MediaCard extends View {
    public interface Actions { void onPrev(); void onPlayPause(); void onNext(); void onCardTap(); void onCardLongPress(); void onSeek(long ms); }

    private static final float DW = 420f;
    private static final float PREV_X = 83.5f, PLAY_X = 214f, NEXT_X = 344.5f, BTN_Y = 349f;
    private static final float BAR_L = 31f, BAR_R = 391f, BAR_T = 241f, BAR_B = 246f;
    private static final float TEXT_X = 210f, TEXT_W = 200f;
    private static final int HIT_NONE = 3, HIT_ART = 4, HIT_SEEK = 5;
    private Palette p = Palette.ALL[0];
    private Actions actions;
    private float k = 1f;
    private float bs = 0f; // button shift in design units: the artwork mirrors the buttons individually

    private String title = null, artist = null, album = "", source = "", playerName = "your music app";
    private String line1 = "", line2 = "";
    private Bitmap art;
    private boolean playing, needsAccess;
    private long pos = -1, dur = 0, posTime = 0, seekedAt;
    private float speed = 1f;

    private final RectF artR = new RectF(), tmp = new RectF();
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint artEmpty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noteP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tArtist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tTime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedArt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private int pressed = -1;
    private boolean longFired, seeking;
    private float seekFrac, downX, downY;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (playing && pos >= 0 && isShown()) postDelayed(this, 1000);
        }
    };
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed >= HIT_NONE && pressed != HIT_SEEK && !seeking && actions != null) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                actions.onCardLongPress();
            }
        }
    };

    public MediaCard(Context c) {
        super(c);
        tTitle.setTypeface(Fonts.semibold(c));
        tTitle.setColor(0xFFFFFFFF);
        tTitle.setLetterSpacing(-0.05f);
        tArtist.setTypeface(Fonts.regular(c));
        tArtist.setColor(0xFFC4C9D4);
        tArtist.setLetterSpacing(-0.058f);
        cap.setTypeface(Fonts.semibold(c));
        cap.setLetterSpacing(0.2f);
        tTime.setTypeface(Fonts.regular(c));
        tTime.setColor(0xFFC8CED8);
        tTime.setLetterSpacing(-0.042f);
        artEdge.setStyle(Paint.Style.STROKE);
        artEdge.setColor(0xAA4696DC);
        artEmpty.setColor(0xFF141B26);
        noteP.setColor(0x55FFFFFF);
        track.setColor(0xFF212934);
        fill.setColor(0xFF5DD5FF);
        fillGlow.setColor(0x40288CE6);
        knob.setColor(0xFFFFFFFF);
        knobGlow.setColor(0x5050BEFF);
        glyph.setColor(0xFFFFFFFF);
        btnDown.setColor(0x1AFFFFFF);
        pill.setStyle(Paint.Style.STROKE);
        pill.setColor(0xFF5DD5FF);
        pressedArt.setColor(0x40000000);
        setClickable(true);
    }

    public void setActions(Actions a) { actions = a; }

    public void setPalette(Palette pal) { p = pal; invalidate(); }

    /** Card on the right: the ring buttons in the mirrored artwork sit 8 units further left. */
    public void setOnRight(boolean b) { bs = b ? -8f : 0f; invalidate(); }

    public void setAccent(Accent a) {
        artEdge.setColor((a.rim() & 0x00FFFFFF) | 0xAA000000);
        fill.setColor(a.fill());
        fillGlow.setColor((a.deep() & 0x00FFFFFF) | 0x40000000);
        knobGlow.setColor((a.glow() & 0x00FFFFFF) | 0x50000000);
        pill.setColor(a.fill());
        capCol = a.fill();
        invalidate();
    }
    private int capCol = 0xFF5DD5FF;

    public void setNeedsAccess(boolean b) { setNeedsAccess(b, null); }

    /** Access missing or not working: the card says which, so a glance at the home screen tells. */
    public void setNeedsAccess(boolean b, String why) { needsAccess = b; accessWhy = why; wrapTitle(); invalidate(); }
    private String accessWhy;

    public void setPlayerName(String name) { playerName = TextUtils.isEmpty(name) ? "your music app" : name; wrapTitle(); invalidate(); }

    private boolean seekable = true;
    /** False when the player can't be told to jump (the bar still shows progress). */
    public void setSeekable(boolean b) { seekable = b; }

    public void update(String title, String artist, String album, String source, Bitmap art, boolean playing,
                       long pos, long dur, float speed, long posTime) {
        this.title = TextUtils.isEmpty(title) ? null : title;
        this.artist = artist;
        this.album = album == null ? "" : album;
        this.source = source == null ? "" : source;
        if (art != this.art) { this.art = art; applyArt(); }
        this.playing = playing;
        this.pos = pos; this.dur = dur; this.speed = speed; this.posTime = posTime;
        wrapTitle();
        removeCallbacks(progressTick);
        if (playing && pos >= 0) postDelayed(progressTick, 1000);
        invalidate();
    }

    public void setPlaying(boolean b) { playing = b; invalidate(); }

    public void setPlayback(boolean playing, long pos, float speed, long posTime) {
        this.playing = playing;
        // right after a seek, players can still report the old position for a moment: keep ours
        if (SystemClock.elapsedRealtime() - seekedAt > 700) { this.pos = pos; this.posTime = posTime; }
        this.speed = speed;
        removeCallbacks(progressTick);
        if (playing && pos >= 0) postDelayed(progressTick, 1000);
        invalidate();
    }

    private void applyArt() {
        if (getWidth() == 0) return;
        if (art != null) {
            BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float sc = Math.max(artR.width() / art.getWidth(), artR.height() / art.getHeight());
            m.setScale(sc, sc);
            m.postTranslate(artR.left + (artR.width() - art.getWidth() * sc) / 2f, artR.top + (artR.height() - art.getHeight() * sc) / 2f);
            sh.setLocalMatrix(m);
            artPaint.setShader(sh);
        } else artPaint.setShader(null);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        k = w / DW;
        artR.set(31 * k, 30 * k, 187 * k, 187 * k);
        artEdge.setStrokeWidth(1.5f * k);
        tTitle.setTextSize(27.4f * k);
        tArtist.setTextSize(20.55f * k);
        cap.setTextSize(12 * k);
        tTime.setTextSize(18.49f * k);
        pill.setStrokeWidth(Math.max(1f, k));
        applyArt();
        wrapTitle();
    }

    private String shownTitle() {
        if (title != null) return title;
        return needsAccess ? "See what's playing" : "Nothing playing";
    }

    private String shownArtist() {
        if (title != null) return artist == null ? "" : artist;
        if (needsAccess) return accessWhy != null ? accessWhy : "Allow access to show songs";
        return "Press play to start " + playerName;
    }

    /** Title on up to two lines, broken at a space, like the reference ("Midnight / City Lights"). */
    private void wrapTitle() {
        if (getWidth() == 0) return;
        String t = shownTitle();
        float max = TEXT_W * k;
        if (tTitle.measureText(t) <= max) { line1 = t; line2 = ""; return; }
        int cut = -1;
        for (int i = t.length() - 1; i > 0; i--) {
            if (t.charAt(i) == ' ' && tTitle.measureText(t, 0, i) <= max) { cut = i; break; }
        }
        if (cut < 0) { line1 = TextUtils.ellipsize(t, tTitle, max, TextUtils.TruncateAt.END).toString(); line2 = ""; return; }
        line1 = t.substring(0, cut);
        line2 = TextUtils.ellipsize(t.substring(cut + 1), tTitle, max, TextUtils.TruncateAt.END).toString();
    }

    private long currentPos() {
        long now = pos;
        if (playing) now += (long) ((SystemClock.elapsedRealtime() - posTime) * speed);
        if (seeking) now = (long) (seekFrac * dur);
        return dur > 0 ? Math.max(0, Math.min(dur, now)) : Math.max(0, now);
    }

    @Override protected void onDraw(Canvas c) {
        float tx = TEXT_X * k;

        // cover
        float ar = 14 * k;
        if (art != null) c.drawRoundRect(artR, ar, ar, artPaint);
        else {
            c.drawRoundRect(artR, ar, ar, artEmpty);
            Icons.note(c, artR.centerX(), artR.centerY(), 64 * k, noteP);
        }
        c.drawRoundRect(artR, ar, ar, artEdge);
        if (pressed == HIT_ART) c.drawRoundRect(artR, ar, ar, pressedArt);

        // title / artist (two-line titles sit at 89 and 121.5; single lines are centred beside the cover)
        boolean two = line2.length() > 0;
        float t1 = two ? 89 * k : 100 * k;
        c.drawText(line1, tx, t1, tTitle);
        if (two) c.drawText(line2, tx, 121.5f * k, tTitle);
        String a = shownArtist();
        if (a.length() > 0) {
            CharSequence s = TextUtils.ellipsize(a, tArtist, TEXT_W * k, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), tx, (two ? 160 : 136) * k, tArtist);
        }
        if (needsAccess && title == null) {
            String cta = "ALLOW ACCESS";
            float cw = cap.measureText(cta) + 36 * k, ch = 34 * k, cy = 192 * k;
            tmp.set(tx, cy - ch / 2, tx + cw, cy + ch / 2);
            c.drawRoundRect(tmp, ch / 2, ch / 2, pill);
            cap.setColor(capCol);
            c.drawText(cta, tx + 18 * k, cy + 4.5f * k, cap);
        }

        // progress
        float l = BAR_L * k, r = BAR_R * k, top = BAR_T * k, bot = BAR_B * k, rr = (BAR_B - BAR_T) / 2f * k;
        if (seeking) { top -= 1.5f * k; bot += 1.5f * k; rr += 1.5f * k; }
        tmp.set(l, top, r, bot);
        c.drawRoundRect(tmp, rr, rr, track);
        float frac = 0f;
        if (pos >= 0 && dur > 0) {
            long now = currentPos();
            frac = now / (float) dur;
            float fx = l + (r - l) * frac;
            float cy = (top + bot) / 2f;
            tmp.set(l, top - 4 * k, Math.max(fx, l + 2 * rr), bot + 4 * k);
            c.drawRoundRect(tmp, 6 * k, 6 * k, fillGlow);
            tmp.set(l, top, Math.max(fx, l + 2 * rr), bot);
            c.drawRoundRect(tmp, rr, rr, fill);
            c.drawCircle(fx, cy, 11 * k, knobGlow);
            c.drawCircle(fx, cy, (seeking ? 10 : 8) * k, knob);
            c.drawText(fmt(now), l, 275 * k, tTime);
            String rem = "-" + fmt(dur - now);
            c.drawText(rem, r - tTime.measureText(rem), 275 * k, tTime);
        } else if (pos >= 0 && title != null) {
            // length unknown: at least show how long it has been playing
            c.drawText(fmt(currentPos()), l, 275 * k, tTime);
        }

        // pressed feedback on the round buttons (the buttons themselves are in the artwork)
        float by = BTN_Y * k;
        if (pressed == 0) c.drawCircle((PREV_X + bs) * k, by, 35 * k, btnDown);
        if (pressed == 2) c.drawCircle((NEXT_X + bs) * k, by, 35 * k, btnDown);
        if (pressed == 1) c.drawCircle((PLAY_X + bs) * k, by, 40 * k, btnDown);

        // play / pause glyph inside the ringed button
        float px = (PLAY_X + bs) * k;
        if (playing) {
            tmp.set(px - 11.5f * k, by - 14.5f * k, px - 3 * k, by + 14.5f * k);
            c.drawRoundRect(tmp, 2.5f * k, 2.5f * k, glyph);
            tmp.set(px + 3 * k, by - 14.5f * k, px + 11.5f * k, by + 14.5f * k);
            c.drawRoundRect(tmp, 2.5f * k, 2.5f * k, glyph);
        } else {
            tri.reset();
            tri.moveTo(px - 9 * k, by - 15 * k);
            tri.lineTo(px + 15 * k, by);
            tri.lineTo(px - 9 * k, by + 15 * k);
            tri.close();
            c.drawPath(tri, glyph);
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (ss < 10 ? "0" : "") + ss;
        return m + ":" + (ss < 10 ? "0" : "") + ss;
    }

    private int hit(float x, float y) {
        float by = BTN_Y * k;
        float[] xs = { PREV_X, PLAY_X, NEXT_X };
        float[] rs = { 46, 54, 46 };
        for (int i = 0; i < 3; i++) {
            float dx = x - (xs[i] + bs) * k, dy = y - by;
            if (dx * dx + dy * dy < (rs[i] * k) * (rs[i] * k)) return i;
        }
        if (seekable && y > (BAR_T - 18) * k && y < (BAR_B + 18) * k && pos >= 0 && dur > 0) return HIT_SEEK;
        if (x > artR.left - 8 * k && x < artR.right + 8 * k && y > artR.top - 8 * k && y < artR.bottom + 8 * k) return HIT_ART;
        return HIT_NONE;
    }

    private float fracAt(float x) {
        return Math.max(0f, Math.min(1f, (x - BAR_L * k) / ((BAR_R - BAR_L) * k)));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                downX = e.getX(); downY = e.getY();
                longFired = false;
                if (pressed == HIT_SEEK) {
                    seeking = true;
                    seekFrac = fracAt(e.getX());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                } else if (pressed >= HIT_NONE) postDelayed(longPress, 550);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (seeking) { seekFrac = fracAt(e.getX()); invalidate(); return true; }
                float mdx = e.getX() - downX, mdy = e.getY() - downY;
                if (mdx * mdx + mdy * mdy > 18 * 18 * k * k) removeCallbacks(longPress);
                if (hit(e.getX(), e.getY()) != pressed) {
                    removeCallbacks(longPress);
                    if (pressed >= 0 && pressed <= 2) { pressed = -1; invalidate(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (seeking) {
                    seeking = false;
                    long target = (long) (fracAt(e.getX()) * dur);
                    pos = target; posTime = SystemClock.elapsedRealtime(); seekedAt = posTime;
                    if (actions != null) actions.onSeek(target);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    pressed = -1;
                    invalidate();
                    return true;
                }
                int h = hit(e.getX(), e.getY());
                if (h == pressed && actions != null && !longFired) {
                    boolean tapCard = h == HIT_ART || (needsAccess && title == null);
                    if (h == 0) actions.onPrev();
                    else if (h == 1) actions.onPlayPause();
                    else if (h == 2) actions.onNext();
                    else if (tapCard) actions.onCardTap();
                    if (h <= 2 || tapCard) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                seeking = false;
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(progressTick);
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }

    public void resumeTicking() {
        removeCallbacks(progressTick);
        if (playing && pos >= 0) post(progressTick);
    }
}
