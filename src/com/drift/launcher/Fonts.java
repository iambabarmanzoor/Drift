/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Typeface;

/** Inter (SIL Open Font License), bundled so the screen matches the reference design. */
public final class Fonts {
    private static Typeface light, regular, semibold;

    private static Typeface load(Context c, String file, String fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), file); }
        catch (Throwable t) { return Typeface.create(fallback, Typeface.NORMAL); }
    }

    public static Typeface light(Context c) { if (light == null) light = load(c, "Inter-300.ttf", "sans-serif-light"); return light; }
    public static Typeface regular(Context c) { if (regular == null) regular = load(c, "Inter-400.ttf", "sans-serif"); return regular; }
    public static Typeface semibold(Context c) { if (semibold == null) semibold = load(c, "Inter-600.ttf", "sans-serif-medium"); return semibold; }
}
