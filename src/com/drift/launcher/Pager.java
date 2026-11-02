/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.Scroller;

/**
 * Horizontal pages you swipe left and right, with page dots underneath.
 * Each child is one full page. Children keep their own taps and long-presses;
 * a sideways drag is taken over by the pager.
 */
public class Pager extends ViewGroup {
    public interface Listener { void onPage(int page); }

    private final Scroller scroller;
    private final int slop, minFling;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging;
    private int page;
    private float dotsH = 0;
    private final Paint dotOn = new Paint(Paint.ANTI_ALIAS_FLAG), dotOff = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private boolean fade;

    public Pager(Context c) {
        super(c);
        scroller = new Scroller(c, new Interpolator() {
            @Override public float getInterpolation(float t) { float u = 1f - t; return 1f - u * u * u * u; } // ease-out
        });
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        dotOn.setColor(Palette.TEXT);
        dotOff.setColor(0x40F2F2F3);
        setWillNotDraw(false);
    }

    public void setListener(Listener l) { listener = l; }

    /**
     * Cross-fade between pages instead of sliding. Used on the home screen, where the glass tiles
     * are part of the background and only the icons would move: the icons dissolve in place instead.
     */
    public void setFade(boolean b) { fade = b; requestLayout(); }

    /** Height reserved under the pages for the dots, in pixels (0 hides them). */
    public void setDotsHeight(float px) { if (px == dotsH) return; dotsH = px; requestLayout(); }

    public int page() { return page; }

    public int pageCount() { return getChildCount(); }

    public void setPage(int p, boolean animate) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        if (fade) { fadeTo(p, animate); return; }
        page = p;
        int target = p * getWidth();
        if (!scroller.isFinished()) scroller.abortAnimation();
        if (animate && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 320);
            postInvalidateOnAnimation();
        } else scrollTo(target, 0);
        if (listener != null) listener.onPage(p);
        invalidate();
    }

    private void fadeTo(int p, boolean animate) {
        int old = page, dir = p > old ? 1 : -1;
        page = p;
        for (int i = 0; i < getChildCount(); i++) {
            final View c = getChildAt(i);
            c.animate().cancel();
            if (i == p) {
                c.setVisibility(VISIBLE);
                if (animate && i != old) {
                    c.setAlpha(0f);
                    c.setTranslationX(dir * 14 * getResources().getDisplayMetrics().density);
                    c.animate().alpha(1f).translationX(0).setStartDelay(90).setDuration(220).start();
                } else { c.setAlpha(1f); c.setTranslationX(0); }
            } else if (i == old && animate) {
                c.animate().alpha(0f).translationX(-dir * 14 * getResources().getDisplayMetrics().density).setStartDelay(0).setDuration(150)
                        .withEndAction(new Runnable() { @Override public void run() { if (c != getChildAt(page)) c.setVisibility(INVISIBLE); } }).start();
            } else {
                c.setAlpha(0f);
                c.setVisibility(INVISIBLE);
            }
        }
        if (listener != null) listener.onPage(p);
        invalidate();
    }

    @Override protected void onMeasure(int wms, int hms) {
        int W = MeasureSpec.getSize(wms), H = MeasureSpec.getSize(hms);
        int ph = Math.max(0, H - (int) dotsH);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(W, H);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int x = fade ? 0 : i * w; // fading pages all sit in the same place
            c.layout(x, 0, x + w, c.getMeasuredHeight());
        }
        if (page >= getChildCount()) page = Math.max(0, getChildCount() - 1);
        if (fade) return;
        if (!dragging && (changed || scroller.isFinished())) { scroller.abortAnimation(); scrollTo(page * w, 0); }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX(); downY = e.getY();
                dragging = !scroller.isFinished();
                if (dragging) scroller.abortAnimation();
                track(e);
                return dragging;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (dx > slop && dx > dy * 1.2f && getChildCount() > 1) {
                    dragging = true;
                    lastX = e.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                track(e);
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (vt != null) { vt.recycle(); vt = null; }
                return false;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX(); downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && getChildCount() > 1) {
                    float ax = Math.abs(e.getX() - downX), ay = Math.abs(e.getY() - downY);
                    if (ax > slop && ax > ay * 1.2f) dragging = true;
                }
                if (dragging && !fade) {
                    float d = lastX - e.getX();
                    int max = Math.max(0, (getChildCount() - 1) * getWidth());
                    float nx = getScrollX() + d;
                    if (nx < 0 || nx > max) nx = getScrollX() + d * 0.35f; // gentle edge resistance
                    scrollTo((int) nx, 0);
                }
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && fade) {
                    float v = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); }
                    float dx = e.getX() - downX;
                    if (dx < -slop * 3 || v < -minFling) setPage(page + 1, true);
                    else if (dx > slop * 3 || v > minFling) setPage(page - 1, true);
                } else if (dragging) {
                    float v = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); }
                    int w = Math.max(1, getWidth());
                    int cur = (int) Math.floor(getScrollX() / (float) w);
                    int target = v < -minFling ? cur + 1 : v > minFling ? cur : Math.round(getScrollX() / (float) w);
                    setPage(target, true);
                }
                dragging = false;
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
        }
        return true;
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    @Override protected void dispatchDraw(Canvas c) {
        // Clip to our own bounds ourselves: the home layout doesn't clip its children, so without
        // this the next page's icons show beside the tiles (over the road when they're on the left).
        c.save();
        c.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
        super.dispatchDraw(c);
        c.restore();
        int n = getChildCount();
        if (dotsH <= 0 || n < 2) return;
        float r = Math.max(2f, dotsH * 0.14f), gap = r * 4f;
        float cx = getScrollX() + getWidth() / 2f - (n - 1) * gap / 2f, cy = getHeight() - dotsH / 2f;
        for (int i = 0; i < n; i++) c.drawCircle(cx + i * gap, cy, r, i == page ? dotOn : dotOff);
    }
}
