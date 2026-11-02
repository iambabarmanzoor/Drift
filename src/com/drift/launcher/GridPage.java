/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** One page of a pager: lays its children out in an evenly spaced cols x rows grid. */
public class GridPage extends ViewGroup {
    private final int cols, rows;
    private float gap;

    public GridPage(Context c, int cols, int rows, float gapPx) {
        super(c);
        this.cols = cols; this.rows = rows; this.gap = gapPx;
        setClipChildren(false);
    }

    public void setGap(float px) { gap = px; requestLayout(); }

    private int cellW(int W) { return (int) ((W - gap * (cols - 1)) / cols); }
    private int cellH(int H) { return (int) ((H - gap * (rows - 1)) / rows); }

    @Override protected void onMeasure(int wms, int hms) {
        int W = MeasureSpec.getSize(wms), H = MeasureSpec.getSize(hms);
        int cw = Math.max(0, cellW(W)), ch = Math.max(0, cellH(H));
        for (int i = 0; i < getChildCount(); i++)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        setMeasuredDimension(W, H);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        int cw = Math.max(0, cellW(W)), ch = Math.max(0, cellH(H));
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int col = i % cols, row = i / cols;
            if (row >= rows) { v.layout(0, 0, 0, 0); continue; }
            int x = Math.round(col * (cw + gap)), y = Math.round(row * (ch + gap));
            v.layout(x, y, x + cw, y + ch);
        }
    }
}
