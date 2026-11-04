/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

/**
 * Speed in the middle of the bottom bar, from the unit's GPS, in a game-style digital font.
 * Shows "--" until there is a fix. Design size 200 x 90 at x=540, y=600.
 */
public class SpeedView extends View implements LocationListener {
    private static final float DW = 200f;
    private static final long STALE_MS = 8000;
    private float k = 1f;
    private boolean mph, listening;
    private float kmh = -1;           // -1 = no fix yet
    private long fixAt;
    private Location last;
    private boolean moving;
    private float prevRaw;
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int glow = 0xFF46AAFF;

    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            if (kmh >= 0 && SystemClock.elapsedRealtime() - fixAt > STALE_MS) { kmh = -1; invalidate(); }
            if (listening) postDelayed(this, 2000);
        }
    };

    public SpeedView(Context c) {
        super(c);
        Typeface semi = font(c, "Orbitron-600.ttf"), med = font(c, "Orbitron-500.ttf");
        num.setTypeface(semi);
        num.setColor(0xFFFFFFFF);
        num.setTextAlign(Paint.Align.CENTER);
        num.setLetterSpacing(0.04f);
        unit.setTypeface(med);
        unit.setColor(0xFF9AA3B2);
        unit.setTextAlign(Paint.Align.CENTER);
        unit.setLetterSpacing(0.28f);
        setContentDescription("Speed");
    }

    private static Typeface font(Context c, String file) {
        try { return Typeface.createFromAsset(c.getAssets(), file); }
        catch (Throwable t) { return Typeface.create("sans-serif-condensed", Typeface.BOLD); }
    }

    public void setAccent(Accent a) { glow = a.glow(); applyGlow(); invalidate(); }

    public void setMph(boolean b) { mph = b; invalidate(); }

    /** Current speed in km/h, 0 when parked or there is no GPS fix. */
    public float kmh() { return kmh < 0 ? 0f : kmh; }

    private void applyGlow() { num.setShadowLayer(12f * k, 0, 0, (glow & 0x00FFFFFF) | 0xC0000000); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0) return;
        k = w / DW;
        num.setTextSize(40f * k);
        unit.setTextSize(10f * k);
        applyGlow();
    }

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Start listening to GPS (if allowed). Safe to call repeatedly. */
    public void start() {
        if (listening || !hasPermission(getContext())) return;
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            listening = true;
            postDelayed(staleCheck, 2000);
        } catch (Throwable t) {
            listening = false;
        }
    }

    public void stop() {
        if (!listening) return;
        listening = false;
        removeCallbacks(staleCheck);
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(this);
        } catch (Throwable ignored) { }
    }

    @Override public void onLocationChanged(Location l) {
        float v;
        if (l.hasSpeed()) v = l.getSpeed() * 3.6f;
        else if (last != null && l.getTime() > last.getTime()) v = last.distanceTo(l) / ((l.getTime() - last.getTime()) / 1000f) * 3.6f;
        else v = kmh < 0 ? 0 : kmh;
        last = l;
        // A parked car's GPS wanders a few metres, which reads as a few km/h. Only call it moving after
        // two fixes in a row at 6+ km/h with a decent fix, and stopped again below 3 km/h.
        boolean good = !l.hasAccuracy() || l.getAccuracy() <= 30f;
        if (!good) v = 0;
        if (!moving) moving = v >= 6f && prevRaw >= 6f;
        else if (v < 3f) moving = false;
        prevRaw = v;
        kmh = moving ? v : 0;
        fixAt = SystemClock.elapsedRealtime();
        invalidate();
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { kmh = -1; moving = false; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        String s = kmh < 0 ? "--" : String.valueOf(Math.round(mph ? kmh * 0.621371f : kmh));
        c.drawText(s, cx, 53f * k, num);
        c.drawText(mph ? "MPH" : "KM/H", cx, 75f * k, unit);
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
