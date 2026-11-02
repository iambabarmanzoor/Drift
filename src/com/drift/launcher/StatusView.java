/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;

/**
 * Live parts of the top strip, drawn over the artwork: the recent apps button and the
 * Bluetooth and Wi-Fi state (the big clock shows the time). The home icon itself is in the
 * artwork; this view makes it and the recent apps button tappable. Design size 1280 x 80.
 */
public class StatusView extends View {
    public interface Listener { void onHome(); void onRecents(); }

    private static final float DW = 1280f;
    private float k = 1f;
    private static final int NONE = 0, HOME = 1, RECENTS = 2;
    private boolean bt, wifi;
    private int down = NONE;
    private Listener listener;
    private Bitmap icBt, icWifi;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint on = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    public StatusView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xFFFFFFFF);
        off.setAlpha(80);
        press.setColor(0x26FFFFFF);
        icBt = asset(c, "ic_bt.png");
        icWifi = asset(c, "ic_wifi.png");
        setContentDescription("Home and recent apps");
    }

    private static Bitmap asset(Context c, String name) {
        try { InputStream in = c.getAssets().open(name); Bitmap b = BitmapFactory.decodeStream(in); in.close(); return b; }
        catch (Throwable t) { return null; }
    }

    public void setListener(Listener l) { listener = l; }

    public void refresh() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            bt = a != null && a.isEnabled();
        } catch (Throwable t) { bt = false; }
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            wifi = ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) { wifi = false; }
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0) return;
        k = w / DW;
        stroke.setStrokeWidth(2.3f * k);
    }

    @Override protected void onDraw(Canvas c) {
        if (down == HOME) c.drawCircle(62 * k, 40 * k, 28 * k, press);
        if (down == RECENTS) c.drawCircle(112 * k, 39 * k, 28 * k, press);
        // recent apps: a rounded square drawn to match the home icon's line weight
        dst.set(101.65f * k, 28.65f * k, 122.35f * k, 49.35f * k);
        c.drawRoundRect(dst, 4.5f * k, 4.5f * k, stroke);
        // right aligned with the clock and tiles below (the time moved to the big clock)
        if (icBt != null) { dst.set(1174.5f * k, 22 * k, 1199.5f * k, 57 * k); c.drawBitmap(icBt, null, dst, bt ? on : off); }
        if (icWifi != null) { dst.set(1204.5f * k, 23 * k, 1247.5f * k, 55 * k); c.drawBitmap(icWifi, null, dst, wifi ? on : off); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        int zone = x < 87 * k ? HOME : x < 145 * k ? RECENTS : NONE;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = zone; invalidate(); return true; // swallow other taps: don't let them fall through
            case MotionEvent.ACTION_UP:
                if (down != NONE && zone == down && e.getY() >= 0 && e.getY() <= getHeight() && listener != null) {
                    if (down == HOME) listener.onHome(); else listener.onRecents();
                }
                down = NONE; invalidate(); return true;
            case MotionEvent.ACTION_CANCEL:
                down = NONE; invalidate(); return true;
        }
        return true;
    }
}
