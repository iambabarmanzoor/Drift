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
        lp.gravity = Gravity.CENTER; lp.topMargin = 0;
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
            if (subtitle.length() > 320) { // long text (e.g. music diagnostics): scroll inside the card
                android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
                sv.setScrollbarFadingEnabled(false);
                sv.addView(st);
                card.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(470 * s)));
            } else card.addView(st);
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

    public interface OnChecks { void onDone(boolean[] sel); }
    public interface OnText { void onText(String text); }
    private static int accent = 0xFF46AAFF;
    public static void setAccent(int a) { accent = a; }

    private TextView button(String label, boolean primary, final Runnable r) {
        TextView b = text(label, 18, 0xFFFFFFFF, "sans-serif-medium", false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * s);
        if (primary) bg.setColor(accent); else { bg.setColor(0x12FFFFFF); bg.setStroke(Math.max(1, Math.round(s)), 0x1FFFFFFF); }
        b.setBackground(bg);
        b.setPadding(Math.round(28 * s), 0, Math.round(28 * s), 0);
        b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        return b;
    }

    private LinearLayout buttons(TextView... bs) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.END);
        row.setPadding(Math.round(24 * s), Math.round(14 * s), Math.round(24 * s), Math.round(8 * s));
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(52 * s));
            if (i > 0) lp.leftMargin = Math.round(12 * s);
            row.addView(bs[i], lp);
        }
        return row;
    }

    /** Round tick box in the accent colour. */
    private static final class Check extends View {
        boolean on; final float s;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Check(Context c, float s) { super(c); this.s = s; }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 15 * s;
            if (on) {
                p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(accent); c.drawCircle(cx, cy, r, p);
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setColor(0xFFFFFFFF); p.setStrokeWidth(3 * s);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND); p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(cx - 6 * s, cy); t.lineTo(cx - 1.5f * s, cy + 5 * s); t.lineTo(cx + 7 * s, cy - 5 * s);
                c.drawPath(t, p);
            } else {
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setColor(0x66FFFFFF); p.setStrokeWidth(2 * s); c.drawCircle(cx, cy, r - s, p);
            }
        }
    }

    /**
     * Tick the ones you want, then Done (the "Add to playlist" picker). An optional action button
     * sits above the list (e.g. "New playlist").
     */
    public void checks(String title, String[] labels, String[] subs, boolean[] initial, String topAction, final Runnable onTop, final OnChecks done) {
        scale();
        header(title, null);
        final boolean[] sel = initial.clone();
        if (topAction != null) {
            LinearLayout wrap = new LinearLayout(getContext());
            wrap.setPadding(Math.round(28 * s), 0, Math.round(28 * s), Math.round(10 * s));
            wrap.addView(button("+  " + topAction, true, new Runnable() { @Override public void run() { dismiss(); onTop.run(); } }),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(46 * s)));
            card.addView(wrap);
        }
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(28 * s), 0, Math.round(22 * s), 0);
            LinearLayout tx = new LinearLayout(getContext());
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView a = text(labels[i], 20, Palette.TEXT, "sans-serif", false); a.setSingleLine(true);
            tx.addView(a);
            if (subs != null && subs[i] != null) { TextView b = text(subs[i], 15, Palette.SUB, "sans-serif", false); tx.addView(b); }
            row.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final Check ck = new Check(getContext(), s);
            ck.on = sel[i];
            row.addView(ck, new LinearLayout.LayoutParams(Math.round(40 * s), Math.round(40 * s)));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[] { android.R.attr.state_pressed }, new ColorDrawable(0x14FFFFFF));
            sl.addState(new int[] {}, new ColorDrawable(0));
            row.setBackground(sl);
            row.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { sel[idx] = !sel[idx]; ck.on = sel[idx]; ck.invalidate(); } });
            list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(72 * s)));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
        sv.addView(list);
        int maxH = Math.round(Math.min(labels.length * 72, 360) * s);
        card.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        card.addView(buttons(
                button("Cancel", false, new Runnable() { @Override public void run() { dismiss(); } }),
                button("Done", true, new Runnable() { @Override public void run() { dismiss(); done.onDone(sel); } })));
        show();
    }

    /** Ask for a name (new playlist, rename). The card sits high so the keyboard doesn't cover it. */
    public void input(String title, String initial, String okLabel, String note, final OnText ok) {
        scale();
        header(title, null);
        final android.widget.EditText in = new android.widget.EditText(getContext());
        in.setText(initial);
        in.setSingleLine(true);
        in.setTextColor(0xFFFFFFFF);
        in.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * s);
        in.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        in.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(40) });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0AFFFFFF); bg.setCornerRadius(16 * s); bg.setStroke(Math.max(1, Math.round(1.5f * s)), accent);
        in.setBackground(bg);
        in.setPadding(Math.round(18 * s), 0, Math.round(18 * s), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(60 * s));
        lp.leftMargin = lp.rightMargin = Math.round(28 * s);
        card.addView(in, lp);
        if (note != null) {
            TextView n = text(note, 15, Palette.SUB, "sans-serif", false);
            n.setPadding(Math.round(28 * s), Math.round(10 * s), Math.round(28 * s), 0);
            card.addView(n);
        }
        final Runnable go = new Runnable() {
            @Override public void run() {
                String t = in.getText().toString().trim();
                hideKeys(in); dismiss();
                ok.onText(t.isEmpty() ? initial : t);
            }
        };
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) { go.run(); return true; }
        });
        card.addView(buttons(
                button("Cancel", false, new Runnable() { @Override public void run() { hideKeys(in); dismiss(); } }),
                button(okLabel, true, go)));
        LayoutParams clp = (LayoutParams) card.getLayoutParams();
        clp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        clp.topMargin = Math.round(70 * s);
        card.setLayoutParams(clp);
        show();
        in.requestFocus();
        in.selectAll();
        try { ((android.app.Activity) getContext()).getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING); } catch (Throwable ignored) { }
        in.postDelayed(new Runnable() { @Override public void run() {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(in, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } }, 120);
    }

    private void hideKeys(View v) {
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    public interface OnApp { void onApp(int index); }

    /**
     * Drift's own recent apps (for units whose system recents screen doesn't open): tap an app to
     * switch to it, Close to stop it in the background, Close all for everything listed.
     */
    public void apps(String title, final String[] labels, android.graphics.drawable.Drawable[] icons, String empty,
                     final OnApp open, final OnApp close, final Runnable closeAll) {
        scale();
        header(title, labels.length == 0 ? empty : null);
        final LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(24 * s), 0, Math.round(18 * s), 0);
            android.widget.ImageView iv = new android.widget.ImageView(getContext());
            if (icons[i] != null) iv.setImageDrawable(icons[i]);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Math.round(46 * s), Math.round(46 * s));
            ip.rightMargin = Math.round(18 * s);
            row.addView(iv, ip);
            TextView t = text(labels[i], 20, Palette.TEXT, "sans-serif", false);
            t.setSingleLine(true);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = button("Close", false, new Runnable() {
                @Override public void run() { close.onApp(idx); list.removeView(row); if (list.getChildCount() == 0) dismiss(); }
            });
            x.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * s);
            row.addView(x, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(42 * s)));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[] { android.R.attr.state_pressed }, new ColorDrawable(0x14FFFFFF));
            sl.addState(new int[] {}, new ColorDrawable(0));
            row.setBackground(sl);
            row.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { dismiss(); open.onApp(idx); } });
            list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(70 * s)));
        }
        if (labels.length > 0) {
            android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
            sv.addView(list);
            card.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(Math.min(labels.length * 70, 420) * s)));
            card.addView(buttons(
                    button("Close all", false, new Runnable() { @Override public void run() { dismiss(); closeAll.run(); } }),
                    button("Done", true, new Runnable() { @Override public void run() { dismiss(); } })));
        } else {
            card.addView(buttons(button("OK", true, new Runnable() { @Override public void run() { dismiss(); } })));
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
        View f = findFocus();
        if (f != null) hideKeys(f);
        animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() { if (!showing) setVisibility(GONE); }
        }).start();
    }
}
