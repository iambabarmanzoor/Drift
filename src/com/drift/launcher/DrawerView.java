/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen app drawer: big icons on pages you swipe left and right.
 * Also used as the picker when filling a shortcut or choosing the music app.
 */
public class DrawerView extends FrameLayout {
    public interface Host {
        void onAppChosen(AppRepo.App app, int pickSlot);
        void onAppLongPress(AppRepo.App app);
        void onDrawerClosed();
    }

    private static final int COLS = 4, ROWS = 3;
    private final AppRepo repo;
    private final Host host;
    private final Pager pager;
    private final TextView title, hint;
    private final View closeBtn;
    private List<AppRepo.App> apps = new ArrayList<>();
    private int pickSlot = -1;
    private float s = 1f;
    private boolean open, built;

    public DrawerView(Context c, AppRepo repo, Host host) {
        super(c);
        this.repo = repo;
        this.host = host;
        setClickable(true); // swallow touches so the home screen below doesn't get them
        setVisibility(GONE);
        setBackground(new ColorDrawable(0xFB0A0A0B));

        title = new TextView(c);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextColor(Palette.TEXT);
        title.setSingleLine(true);
        addView(title);

        hint = new TextView(c);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setLetterSpacing(0.3f);
        hint.setTextColor(Palette.SUB2);
        hint.setSingleLine(true);
        addView(hint);

        closeBtn = new CloseButton(c);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { close(); }
        });
        addView(closeBtn);

        pager = new Pager(c);
        addView(pager);
    }

    public boolean isOpen() { return open; }

    public void setApps(List<AppRepo.App> list) { apps = list; built = false; if (open) build(); }

    public void setPalette(Palette pal) { }

    private void build() {
        int keep = pager.page();
        pager.removeAllViews();
        int per = COLS * ROWS;
        for (int start = 0; start < apps.size(); start += per) {
            GridPage page = new GridPage(getContext(), COLS, ROWS, 12 * s);
            for (int i = start; i < Math.min(apps.size(), start + per); i++) page.addView(new AppCell(getContext(), apps.get(i)));
            pager.addView(page);
        }
        pager.setPage(keep, false);
        built = true;
    }

    public void show(int slot) {
        pickSlot = slot;
        title.setText(slot == Launcher.PICK_PLAYER ? "Your music app" : slot >= 0 ? "Pick an app" : "All apps");
        hint.setText(slot == Launcher.PICK_PLAYER ? "THE PLAY BUTTON WILL START THIS APP"
                : slot >= 0 ? "FOR SHORTCUT " + (slot + 1) : apps.size() + " APPS  ·  SWIPE FOR MORE  ·  HOLD TO PIN");
        if (!built) build();
        pager.setPage(0, false);
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(30 * s);
        animate().alpha(1f).translationY(0).setDuration(200).setListener(null).start();
    }

    public void close() {
        if (!open) return;
        open = false;
        pickSlot = -1;
        animate().alpha(0f).translationY(20 * s).setDuration(150).withEndAction(new Runnable() {
            @Override public void run() { if (!open) { setVisibility(GONE); host.onDrawerClosed(); } }
        }).start();
    }

    @Override protected void onMeasure(int wms, int hms) {
        int W = MeasureSpec.getSize(wms), H = MeasureSpec.getSize(hms);
        if (W == 0 || H == 0) { super.onMeasure(wms, hms); return; }
        float ns = Math.min(W / 1280f, H / 720f);
        if (ns != s) {
            s = ns; built = false;
            if (open) post(new Runnable() { @Override public void run() { if (!built) build(); } });
        }
        title.setTextSize(0, 38 * s);
        hint.setTextSize(0, 12 * s);
        int pad = Math.round(40 * s);
        lp(title, pad, Math.round(28 * s), W - 2 * pad, Math.round(54 * s));
        lp(hint, pad + Math.round(2 * s), Math.round(86 * s), W - 2 * pad, Math.round(22 * s));
        int cb = Math.round(60 * s);
        lp(closeBtn, W - pad - cb, Math.round(30 * s), cb, cb);
        pager.setDotsHeight(30 * s);
        lp(pager, pad, Math.round(126 * s), W - 2 * pad, H - Math.round(140 * s));
        super.onMeasure(wms, hms);
    }

    private static void lp(View v, int x, int y, int w, int h) {
        // mutate in place: we're inside onMeasure and super.onMeasure() reads these next
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
    }

    /** One app: big icon and label, drawn directly. */
    private class AppCell extends View implements AppRepo.IconCallback {
        private final AppRepo.App app;
        private Bitmap icon;
        private boolean down;
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF(), bg = new RectF();

        AppCell(Context c, AppRepo.App a) {
            super(c);
            app = a;
            label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(0xC7F2F2F3);
            press.setColor(0x14FFFFFF);
            setContentDescription(a.label);
            icon = repo.icon(a, this);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!open) return; // ignore taps during the close animation
                    int slot = pickSlot;
                    close();
                    host.onAppChosen(app, slot);
                }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (!open || pickSlot != -1) return false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    host.onAppLongPress(app);
                    return true;
                }
            });
        }

        @Override public void onIcon(AppRepo.App a, Bitmap ic, int color) {
            if (a == app) { icon = ic; invalidate(); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { down = true; invalidate(); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { down = false; invalidate(); }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), is = Math.min(112 * s, h * 0.64f);
            if (down) { bg.set(0, 0, w, h); c.drawRoundRect(bg, 18 * s, 18 * s, press); }
            float top = (h - is - 34 * s) / 2f;
            if (icon != null) {
                dst.set(w / 2 - is / 2, top, w / 2 + is / 2, top + is);
                c.drawBitmap(icon, null, dst, bmp);
            }
            label.setTextSize(18 * s);
            CharSequence t = TextUtils.ellipsize(app.label, label, w - 16 * s, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), w / 2, top + is + 30 * s, label);
        }
    }

    private class CloseButton extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), x = new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseButton(Context c) {
            super(c);
            setClickable(true);
            setContentDescription("Close");
            ring.setColor(0x14FFFFFF);
            x.setColor(Palette.TEXT);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            c.drawCircle(w / 2, w / 2, w / 2, ring);
            Icons.close(c, w / 2, w / 2, w * 0.4f, x);
        }
    }
}
