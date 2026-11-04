/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installed app list + icon cache. All heavy work happens on one background thread. */
public class AppRepo {
    public static class App {
        public final String label;
        public final ComponentName cn;
        public final String key;
        App(String label, ComponentName cn) { this.label = label; this.cn = cn; this.key = cn.flattenToShortString(); }
    }

    public interface Listener { void onAppsLoaded(List<App> apps); }
    public interface IconCallback { void onIcon(App app, Bitmap icon, int color); }

    private final Context ctx;
    private final PackageManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> icons;
    private final HashMap<String, Integer> colors = new HashMap<>();
    private final java.util.HashSet<String> failed = new java.util.HashSet<>();
    private volatile List<App> apps = new ArrayList<>();
    private int iconPx = 112;

    public AppRepo(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
        icons = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
    }

    public void shutdown() { worker.shutdownNow(); }

    private void exec(Runnable r) {
        try { worker.execute(r); } catch (Exception ignored) { } // after shutdown: drop quietly
    }

    public void setIconPx(int px) {
        if (px != iconPx && px > 0) { iconPx = px; icons.evictAll(); }
    }

    public List<App> apps() { return apps; }

    public App find(String key) {
        if (key == null) return null;
        for (App a : apps) if (a.key.equals(key)) return a;
        return null;
    }

    public void load(final Listener l) {
        exec(new Runnable() {
            @Override public void run() {
                try { loadNow(l); } catch (Throwable t) {
                    // never let a misbehaving app take the home screen down
                    main.post(new Runnable() { @Override public void run() { l.onAppsLoaded(apps); } });
                }
            }
        });
    }

    private void loadNow(final Listener l) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
                final ArrayList<App> out = new ArrayList<>();
                String self = ctx.getPackageName();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null || self.equals(ri.activityInfo.packageName)) continue;
                    CharSequence lab = ri.loadLabel(pm);
                    out.add(new App(lab == null ? ri.activityInfo.packageName : cleanLabel(lab.toString(), ri.activityInfo.packageName),
                            new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
                }
                // Drift Music lives inside Drift: list it in Drift's own drawer only (no second icon in other launchers)
                out.add(new App("Drift Music", new ComponentName(self, self + ".MusicActivity")));
                final Collator col = Collator.getInstance(Locale.getDefault());
                Collections.sort(out, new Comparator<App>() {
                    @Override public int compare(App a, App b) { return col.compare(a.label, b.label); }
                });
                main.post(new Runnable() {
                    @Override public void run() { apps = out; l.onAppsLoaded(out); }
                });
    }

    /** Returns cached icon immediately (or null) and delivers it async if not cached. */
    public Bitmap icon(final App app, final IconCallback cb) {
        Bitmap b = icons.get(app.key);
        if (b != null) return b;
        synchronized (failed) { if (failed.contains(app.key)) return null; }
        exec(new Runnable() {
            @Override public void run() {
                Bitmap bmp = icons.get(app.key);
                if (bmp == null) {
                    try {
                        Drawable d = pm.getActivityIcon(app.cn);
                        bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(bmp);
                        d.setBounds(0, 0, iconPx, iconPx);
                        d.draw(c);
                        bmp = roundify(bmp);
                        icons.put(app.key, bmp);
                        synchronized (colors) { colors.put(app.key, dominant(bmp)); }
                    } catch (Throwable e) {
                        synchronized (failed) { failed.add(app.key); } // don't retry a broken icon forever
                        return;
                    }
                }
                final Bitmap fb = bmp;
                final int col = color(app);
                main.post(new Runnable() { @Override public void run() { cb.onIcon(app, fb, col); } });
            }
        });
        return null;
    }

    public int color(App app) {
        synchronized (colors) { Integer c = colors.get(app.key); return c == null ? 0 : c; }
    }

    /**
     * Gives every icon the same rounded-square shape:
     *  - square icons get their corners rounded,
     *  - round icons sit on a rounded plate of their own edge colour (reads as one rounded square),
     *  - odd shapes sit centred on a neutral graphite plate.
     */
    static Bitmap roundify(Bitmap raw) {
        int n = raw.getWidth();
        if (n < 8 || raw.getHeight() != n) return raw;
        int e = Math.max(1, n / 40);
        int corners = (Color.alpha(raw.getPixel(e, e)) + Color.alpha(raw.getPixel(n - 1 - e, e))
                + Color.alpha(raw.getPixel(e, n - 1 - e)) + Color.alpha(raw.getPixel(n - 1 - e, n - 1 - e))) / 4;
        float rad = n * 0.23f;
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.RectF r = new android.graphics.RectF(0, 0, n, n);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        if (corners > 200) {
            p.setShader(new android.graphics.BitmapShader(raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            return out;
        }
        int top = raw.getPixel(n / 2, n / 14), left = raw.getPixel(n / 14, n / 2);
        boolean round = Color.alpha(top) > 220 && Color.alpha(left) > 220
                && Color.alpha(raw.getPixel(n / 2, n / 2)) > 200;
        if (round) {
            p.setColor(top | 0xFF000000);
            c.drawRoundRect(r, rad, rad, p);
            p.setColor(0xFFFFFFFF);
            c.drawBitmap(raw, 0, 0, p);
            return out;
        }
        p.setColor(0xFF2A2A2E);
        c.drawRoundRect(r, rad, rad, p);
        float m = n * 0.12f;
        c.drawBitmap(raw, null, new android.graphics.RectF(m, m, n - m, n - m), p);
        return out;
    }

    /** Most "colourful" hue in the icon, pushed bright and saturated so tiles feel playful. */
    static int dominant(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 16, 16, true);
        float[] hsv = new float[3];
        float[] w = new float[12];
        float[][] rgb = new float[12][3];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) < 128) continue;
            Color.colorToHSV(px, hsv);
            float wt = hsv[1] * hsv[2];
            if (wt < 0.12f) continue;
            int bin = ((int) (hsv[0] / 30f)) % 12;
            w[bin] += wt;
            rgb[bin][0] += Color.red(px) * wt; rgb[bin][1] += Color.green(px) * wt; rgb[bin][2] += Color.blue(px) * wt;
        }
        if (b != src) b.recycle();
        int best = -1; float bw = 0;
        for (int i = 0; i < 12; i++) if (w[i] > bw) { bw = w[i]; best = i; }
        if (best < 0 || bw < 1.5f) return 0; // greyscale icon, caller uses theme accent
        int c = Color.rgb((int) (rgb[best][0] / bw), (int) (rgb[best][1] / bw), (int) (rgb[best][2] / bw));
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.max(hsv[1], 0.6f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }

    /**
     * Some systems pad app names with invisible characters (no-break spaces, zero-width or
     * direction marks). They take up width and push a centred label off centre, so strip them.
     */
    static String cleanLabel(String s, String fallback) {
        String t = s.replaceAll("[\\s\\u00A0\\u2000-\\u200F\\u2028-\\u202F\\u205F-\\u206F\\u3000\\uFEFF]+", " ").trim();
        return t.isEmpty() ? fallback : t;
    }
}
