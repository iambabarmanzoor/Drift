/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

/**
 * Accent colours. The artwork is drawn in blue; an accent re-tints only the blue glows
 * (hue 165..265 degrees) and leaves everything else alone: the red tail lights, the white
 * text and the grey glass. Greyish pixels (the car's paint) go neutral instead of tinted.
 * The same maths runs in the HTML preview, so both look identical.
 */
public final class Accent {
    public final String name;
    final float hue, sat, val; // hue < 0 keeps the hue (Platinum); sat/val scale the colour

    private Accent(String name, float hue, float sat, float val) { this.name = name; this.hue = hue; this.sat = sat; this.val = val; }

    public static final float BASE_HUE = 213f;

    public static final Accent[] ALL = {
        new Accent("Electric", 213, 1f, 1f),
        new Accent("Ice", 192, 0.55f, 1f),
        new Accent("Violet", 268, 1f, 1f),
        new Accent("Jade", 158, 0.9f, 0.95f),
        new Accent("Amber", 34, 1f, 0.9f),
        new Accent("Crimson", 352, 1f, 0.95f),
        new Accent("Platinum", -1, 0.06f, 1f),
    };

    public static Accent get(int i) { return ALL[((i % ALL.length) + ALL.length) % ALL.length]; }

    public boolean isOriginal() { return hue == BASE_HUE && sat == 1f && val == 1f; }

    /** Re-tint one colour (0xAARRGGBB, alpha kept). */
    public int shift(int argb) {
        if (isOriginal()) return argb;
        int a = argb >>> 24;
        float r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d == 0) return argb;
        float h;
        if (mx == r) h = ((g - b) / d) % 6f;
        else if (mx == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h *= 60f;
        if (h < 0) h += 360f;
        float w = h < 165 || h > 265 ? 0 : h < 185 ? (h - 165) / 20f : h > 245 ? (265 - h) / 20f : 1f;
        float s = d / mx;
        if (w == 0 || s < 0.04f) return argb;
        float nh = hue < 0 ? h : h + (hue - BASE_HUE);
        float ns = Math.min(1f, s * sat), nv = Math.min(255f, mx * val);
        // hsv -> rgb
        nh = ((nh % 360f) + 360f) % 360f;
        float c = nv * ns, x = c * (1 - Math.abs((nh / 60f) % 2 - 1)), m = nv - c, or, og, ob;
        if (nh < 60) { or = c; og = x; ob = 0; }
        else if (nh < 120) { or = x; og = c; ob = 0; }
        else if (nh < 180) { or = 0; og = c; ob = x; }
        else if (nh < 240) { or = 0; og = x; ob = c; }
        else if (nh < 300) { or = x; og = 0; ob = c; }
        else { or = c; og = 0; ob = x; }
        or += m; og += m; ob += m;
        // dim greyish pixels (chroma under 14) go neutral grey rather than tinted
        float dc = d, ws = dc < 14 ? 0 : dc > 45 ? 1 : (dc - 14) / 31f;
        if (ws < 1) {
            float y = 0.3f * r + 0.59f * g + 0.11f * b;
            or = y + (or - y) * ws; og = y + (og - y) * ws; ob = y + (ob - y) * ws;
        }
        or = r + (or - r) * w; og = g + (og - g) * w; ob = b + (ob - b) * w;
        return (a << 24) | (clamp(or) << 16) | (clamp(og) << 8) | clamp(ob);
    }

    private static int clamp(float v) { int i = Math.round(v); return i < 0 ? 0 : i > 255 ? 255 : i; }

    /** Re-tint a whole image in place (pixels as returned by Bitmap.getPixels). */
    public void shiftAll(int[] px) {
        if (isOriginal()) return;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            // fast reject: only bluish pixels can change (blue is the largest channel or close to it)
            int r = (p >> 16) & 0xFF, b = p & 0xFF;
            if (b <= r) continue;
            px[i] = shift(p);
        }
    }

    // colours used by the live parts, re-tinted with the artwork
    public int fill() { return shift(0xFF5DD5FF); }
    public int glow() { return shift(0xFF46AAFF); }
    public int core() { return shift(0xFFCDF0FF); }
    public int rim() { return shift(0xFF4696DC); }
    public int deep() { return shift(0xFF288CE6); }
}
