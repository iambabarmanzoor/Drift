/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * A small SVG path-data parser ("M10 4H4c-1.1 0-2 .9-2 2..."), so Drift Music's icons are the
 * exact shapes from the design preview. Supports M L H V C S Q T A Z (absolute and relative).
 * Arcs are converted with the standard endpoint-to-centre maths (no rotation needed for icons).
 */
public final class SvgPath {
    private final String d;
    private int i;

    private SvgPath(String d) { this.d = d; }

    /** Parse into a Path in the icon's own units (usually a 24 x 24 box). */
    public static Path parse(String d) {
        Path p = new Path();
        try { new SvgPath(d).run(p); } catch (Throwable ignored) { }
        return p;
    }

    /** Parse and scale so a viewBox of size vb fits a box of size px, placed at (x, y). */
    public static Path icon(String d, float vb, float x, float y, float px) {
        Path p = parse(d);
        Matrix m = new Matrix();
        m.setScale(px / vb, px / vb);
        m.postTranslate(x, y);
        p.transform(m);
        return p;
    }

    private boolean more() {
        skip();
        if (i >= d.length()) return false;
        char c = d.charAt(i);
        return c == '-' || c == '.' || c == '+' || (c >= '0' && c <= '9');
    }

    private void skip() {
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++; else break;
        }
    }

    private float num() {
        skip();
        int s = i;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false, exp = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') { i++; continue; }
            if (c == '.' && !dot && !exp) { dot = true; i++; continue; }
            if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++; continue; }
            break;
        }
        return Float.parseFloat(d.substring(s, i));
    }

    /** Arc flags may be written without separators ("0 1 1-1.8"): read one digit. */
    private boolean flag() {
        skip();
        char c = d.charAt(i++);
        return c == '1';
    }

    private void run(Path p) {
        float x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0; // current, subpath start, last control
        char cmd = 0, prev = 0;
        while (true) {
            skip();
            if (i >= d.length()) break;
            char c = d.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') { cmd = c; i++; }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    p.moveTo(nx, ny); x = sx = nx; y = sy = ny;
                    cmd = rel ? 'l' : 'L'; // following pairs are line-to
                    break;
                }
                case 'L': { float nx = num(), ny = num(); if (rel) { nx += x; ny += y; } p.lineTo(nx, ny); x = nx; y = ny; break; }
                case 'H': { float nx = num(); if (rel) nx += x; p.lineTo(nx, y); x = nx; break; }
                case 'V': { float ny = num(); if (rel) ny += y; p.lineTo(x, ny); y = ny; break; }
                case 'C': {
                    float x1 = num(), y1 = num(), x2 = num(), y2 = num(), nx = num(), ny = num();
                    if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y; }
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny; break;
                }
                case 'S': {
                    float x2 = num(), y2 = num(), nx = num(), ny = num();
                    if (rel) { x2 += x; y2 += y; nx += x; ny += y; }
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'C' || pu == 'S') ? 2 * x - cx : x, y1 = (pu == 'C' || pu == 'S') ? 2 * y - cy : y;
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny; break;
                }
                case 'Q': {
                    float x1 = num(), y1 = num(), nx = num(), ny = num();
                    if (rel) { x1 += x; y1 += y; nx += x; ny += y; }
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny; break;
                }
                case 'T': {
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'Q' || pu == 'T') ? 2 * x - cx : x, y1 = (pu == 'Q' || pu == 'T') ? 2 * y - cy : y;
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny; break;
                }
                case 'A': {
                    float rx = num(), ry = num(); num(); // x-axis rotation: unused by our icons
                    boolean large = flag(), sweep = flag();
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    arc(p, x, y, nx, ny, rx, ry, large, sweep);
                    x = nx; y = ny; break;
                }
                case 'Z': p.close(); x = sx; y = sy; break;
                default: return;
            }
            prev = cmd;
            // implicit repeats: keep the same command while numbers follow
            if (Character.toUpperCase(cmd) == 'Z') continue;
            if (!more()) continue;
        }
    }

    /** SVG arc (no rotation) via centre parameterisation. */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
        double lambda = (dx2 * dx2) / (rx * rx) + (dy2 * dy2) / (ry * ry);
        if (lambda > 1) { double s = Math.sqrt(lambda); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * dy2 * dy2 - ry * ry * dx2 * dx2;
        double den = rx * rx * dy2 * dy2 + ry * ry * dx2 * dx2;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * dy2 / ry), cyp = coef * -(ry * dx2 / rx);
        double cx = cxp + (x1 + x2) / 2.0, cy = cyp + (y1 + y2) / 2.0;
        double a1 = Math.toDegrees(Math.atan2((y1 - cy) / ry, (x1 - cx) / rx));
        double a2 = Math.toDegrees(Math.atan2((y2 - cy) / ry, (x2 - cx) / rx));
        double sweepDeg = a2 - a1;
        if (sweep && sweepDeg < 0) sweepDeg += 360;
        if (!sweep && sweepDeg > 0) sweepDeg -= 360;
        RectF o = new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry));
        p.arcTo(o, (float) a1, (float) sweepDeg);
    }
}
