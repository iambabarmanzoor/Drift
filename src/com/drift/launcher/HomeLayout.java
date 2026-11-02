/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays children out on a fixed 1280x720 design canvas, scaled uniformly to fit
 * whatever space the head unit gives us (status bar, nav bar, other resolutions).
 */
public class HomeLayout extends ViewGroup {
    public static final float DW = 1280f, DH = 720f;
    private float s = 1f, ox = 0f, oy = 0f;

    public HomeLayout(Context c) { super(c); }

    public static class LP extends ViewGroup.LayoutParams {
        final float x, y, w, h;
        public LP(float x, float y, float w, float h) {
            super(0, 0); this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    public void add(View v, float x, float y, float w, float h) { addView(v, new LP(x, y, w, h)); }

    /** Move a child to a new place on the design canvas. */
    public void move(View v, float x, float y, float w, float h) { v.setLayoutParams(new LP(x, y, w, h)); }

    /** Pixels per design unit, and where the design canvas starts (it is centred). */
    public float scale() { return s; }
    public float offX() { return ox; }
    public float offY() { return oy; }

    @Override protected void onMeasure(int wms, int hms) {
        int W = MeasureSpec.getSize(wms), H = MeasureSpec.getSize(hms);
        s = Math.min(W / DW, H / DH);
        ox = (W - DW * s) / 2f; oy = (H - DH * s) / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i); LP lp = (LP) c.getLayoutParams();
            c.measure(MeasureSpec.makeMeasureSpec(Math.round(lp.w * s), MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(Math.round(lp.h * s), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(W, H);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i); LP lp = (LP) c.getLayoutParams();
            int x = Math.round(ox + lp.x * s), y = Math.round(oy + lp.y * s);
            c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
        }
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LP; }
    @Override public boolean shouldDelayChildPressedState() { return false; }
}
