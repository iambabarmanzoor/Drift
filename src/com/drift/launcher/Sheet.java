/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Drift's own menu card, replacing Android's grey dialogs.
 * A dark card in the centre with big rows you can hit while parked; tap outside to close.
 */
public class Sheet extends FrameLayout {
    public interface OnPick { void onPick(int index); }

    private final LinearLayout card;
    private float s = 1f;
    private boolean showing;

    public Sheet(Context c) {
        super(c);
        setVisibility(GONE);
        setBackground(new ColorDrawable(0xB3000000));
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // taps on the card don't fall through to dismiss
        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public boolean isShowing() { return showing; }

    private void scale() {
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        s = Math.min(Math.max(W, H) / 1280f, Math.min(W, H) / 720f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF141416);
        bg.setCornerRadius(22 * s);
        bg.setStroke(Math.max(1, Math.round(s)), 0x14FFFFFF);
        card.setBackground(bg);
        card.setPadding(0, Math.round(10 * s), 0, Math.round(10 * s));
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        lp.width = Math.round(520 * s);
        card.setLayoutParams(lp);
    }

    private TextView text(String t, float sizeDesign, int color, String face, boolean caps) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDesign * s);
        v.setTypeface(Typeface.create(face, Typeface.NORMAL));
        if (caps) v.setLetterSpacing(0.28f);
        return v;
    }

    private void header(String title, String subtitle) {
        card.removeAllViews();
        int pad = Math.round(28 * s);
        TextView t = text(title, 26, Palette.TEXT, "sans-serif-light", false);
        t.setPadding(pad, Math.round(16 * s), pad, subtitle == null ? Math.round(14 * s) : Math.round(4 * s));
        t.setSingleLine(true);
        card.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, 16, Palette.SUB, "sans-serif", false);
            st.setPadding(pad, 0, pad, Math.round(16 * s));
            st.setLineSpacing(0, 1.2f);
            card.addView(st);
        }
    }

    private View row(String label, int color, final Runnable r) {
        TextView v = text(label, 20, color, "sans-serif", false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(28 * s);
        v.setPadding(pad, 0, pad, 0);
        v.setSingleLine(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_pressed }, new ColorDrawable(0x14FFFFFF));
        sl.addState(new int[] {}, new ColorDrawable(0));
        v.setBackground(sl);
        v.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View x) { dismiss(); r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(66 * s));
        v.setLayoutParams(lp);
        return v;
    }

    private View divider() {
        View d = new View(getContext());
        d.setBackground(new ColorDrawable(0x12FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(s)));
        lp.leftMargin = lp.rightMargin = Math.round(28 * s);
        d.setLayoutParams(lp);
        return d;
    }

    /** A list of actions. Items starting with "!" are shown in red (destructive). */
    public void list(String title, String[] items, final OnPick pick) {
        scale();
        header(title, null);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            String it = items[i];
            boolean danger = it.startsWith("!");
            if (i > 0) card.addView(divider());
            card.addView(row(danger ? it.substring(1) : it, danger ? 0xFFFF6B6B : Palette.TEXT, new Runnable() {
                @Override public void run() { pick.onPick(idx); }
            }));
        }
        show();
    }

    /** A short message with one or two buttons. */
    public void message(String title, String body, String primary, final Runnable onPrimary, String secondary) {
        scale();
        header(title, body);
        card.addView(divider());
        if (primary != null) card.addView(row(primary, Palette.TEXT, onPrimary == null ? NOOP : onPrimary));
        if (secondary != null) {
            card.addView(divider());
            card.addView(row(secondary, Palette.SUB, NOOP));
        }
        show();
    }

    private static final Runnable NOOP = new Runnable() { @Override public void run() { } };

    private void show() {
        showing = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        animate().alpha(1f).setDuration(140).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    public void dismiss() {
        if (!showing) return;
        showing = false;
        animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() { if (!showing) setVisibility(GONE); }
        }).start();
    }
}
