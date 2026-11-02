/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

/**
 * Monochrome base with a single accent colour. The accent is used sparingly:
 * progress bar, lane dashes and the accent dot. Everything else is white and grey.
 */
public final class Palette {
    // neutrals (shared by every accent)
    public static final int BG = 0xFF030509;
    public static final int BG_LIFT = 0xFF18181C;   // soft light pool behind the car
    public static final int SURFACE = 0xFF0F141C;
    public static final int LINE = 0x12FFFFFF;      // hairline borders
    public static final int TEXT = 0xFFF2F2F3;
    public static final int SUB = 0x94F2F2F3;
    public static final int SUB2 = 0x57F2F2F3;

    public final String name;
    public final int acc;
    // kept for older call sites
    public final int text = TEXT, sub = SUB, sub2 = SUB2, bgTop = BG, bgBottom = BG;
    public final int a1, a2, a3;
    /** Lighter tint of the accent, used for bright cores of glowing lines. */
    public final int hi;

    public Palette(String name, int acc) {
        this.name = name; this.acc = acc;
        a1 = acc; a2 = acc; a3 = acc;
        hi = mix(acc, 0xFFFFFFFF, 0.42f);
    }

    public static final Palette[] ALL = {
        new Palette("Electric", 0xFF3DA5FF),
        new Palette("Ice", 0xFF8EC9F0),
        new Palette("Platinum", 0xFFE6E6E8),
        new Palette("Amber", 0xFFE3A857),
        new Palette("Crimson", 0xFFE5484D),
        new Palette("Jade", 0xFF4CC38A),
    };

    public static Palette get(int i) { return ALL[((i % ALL.length) + ALL.length) % ALL.length]; }

    public static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    public static int mix(int c1, int c2, float t) {
        int a = (int) ((c1 >>> 24) * (1 - t) + (c2 >>> 24) * t);
        int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static Palette lerp(Palette x, Palette y, float t) {
        if (t >= 1f) return y;
        return new Palette(y.name, mix(x.acc, y.acc, t));
    }
}
