/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's artwork: the reference design (glows, glass cards, road, car, dock)
 * with only the live parts removed. Everything live is drawn on top by the other views,
 * at the same design coordinates. Design size 1280 x 720.
 *
 * Two versions ship: music card on the left (home_plate.jpg) and on the right
 * (home_plate_r.jpg). The accent colour is applied to the pixels once, on one background
 * thread, so drawing stays a plain bitmap copy. Only the newest request is finished.
 */
public class PlateView extends View {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap plate;
    private boolean right, requested;
    private int accent;
    private volatile int job; // newest request wins
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    public PlateView(Context c) { super(c); }

    /** Load the artwork for this side and colour. Safe to call repeatedly. */
    public void set(final boolean right, final int accent) {
        if (requested && right == this.right && accent == this.accent) return;
        requested = true;
        this.right = right; this.accent = accent;
        final int my = ++job;
        final Context app = getContext().getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override public void run() {
                if (my != job) return; // a newer request is queued
                try {
                    final Bitmap b = decode(app, right);
                    if (b == null || my != job) return;
                    Accent a = Accent.get(accent);
                    if (!a.isOriginal()) {
                        int w = b.getWidth(), h = b.getHeight();
                        int[] px = new int[w * h];
                        b.getPixels(px, 0, w, 0, 0, w, h);
                        if (my != job) return;
                        a.shiftAll(px);
                        b.setPixels(px, 0, w, 0, 0, w, h);
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (my != job) return;
                            plate = b; // the old one is left to the garbage collector: the renderer may still hold it
                            invalidate();
                        }
                    });
                } catch (Throwable t) {
                    // out of memory or a bad asset: keep whatever is showing
                }
            }
        });
    }

    private static Bitmap decode(Context c, boolean right) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888; // keep the soft glows free of banding
        o.inMutable = true;
        InputStream in = c.getAssets().open(right ? "home_plate_r.jpg" : "home_plate.jpg");
        try { return BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
    }

    @Override protected void onDraw(Canvas c) {
        if (plate == null) { c.drawColor(Palette.BG); return; }
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(plate, null, dst, paint);
    }
}
