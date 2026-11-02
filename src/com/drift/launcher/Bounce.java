/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.animation.TimeInterpolator;

/** Overshoot easing for the springy press release. */
public final class Bounce implements TimeInterpolator {
    public static final Bounce IT = new Bounce(3f);
    private final float t;
    public Bounce(float tension) { t = tension; }
    @Override public float getInterpolation(float x) {
        x -= 1f;
        return x * x * ((t + 1) * x + t) + 1f;
    }
}
