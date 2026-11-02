/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Android only lets an app see other apps' media sessions if it owns an enabled
 * notification listener. Drift uses it for one thing: finding music players.
 * It looks only at "now playing" notifications (and ongoing ones from your chosen
 * music app) and never stores or reads anything else.
 */
public class MediaWatcher extends NotificationListenerService {

    /** One media notification: which app, its session (if it shares one) and what it says. */
    public static final class Entry {
        public final String pkg;
        public final MediaSession.Token token;
        public final String title, text;
        public final Bitmap art;
        public final long when;
        public String extras = ""; // diagnostics only: which extra fields the notification carries
        /** The notification's own buttons (previous / play-pause / next): the surest way to control such players. */
        public android.app.PendingIntent[] actions;
        public String[] actionTitles;
        public int[] compact;
        Entry(String pkg, MediaSession.Token token, String title, String text, Bitmap art, long when) {
            this.pkg = pkg; this.token = token; this.title = title; this.text = text; this.art = art; this.when = when;
        }
    }

    public static volatile boolean connected;
    private static volatile MediaWatcher instance;
    /** Set by the launcher so the unit's own player counts even if its notification isn't "media style". */
    public static volatile String preferredPkg;
    private static final List<Entry> entries = new ArrayList<>();
    private static Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String lastKey = "";

    public static void setListener(Runnable r) { listener = r; }

    public static List<Entry> entries() {
        synchronized (entries) { return new ArrayList<>(entries); }
    }

    /** Ask Android to reconnect the listener (API 24+; called by reflection so we still build against 23). */
    public static void rebind(Context c) {
        try {
            Method m = NotificationListenerService.class.getMethod("requestRebind", ComponentName.class);
            m.invoke(null, new ComponentName(c, MediaWatcher.class));
        } catch (Throwable ignored) { }
    }

    @Override public void onListenerConnected() { connected = true; instance = this; scan(); }

    /**
     * For diagnostics only: package and title of every ongoing notification, so we can spot a
     * unit's own music player that doesn't announce itself as one. Not stored anywhere.
     */
    public static List<String> ongoing() {
        List<String> out = new ArrayList<>();
        MediaWatcher w = instance;
        if (w == null) return out;
        try {
            StatusBarNotification[] all = w.getActiveNotifications();
            if (all == null) return out;
            for (StatusBarNotification sbn : all) {
                Notification n = sbn.getNotification();
                if (n == null || (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) == 0) continue;
                if (looksLikeMedia(sbn)) continue; // already listed above
                CharSequence t = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
                out.add(sbn.getPackageName() + (t == null ? "" : "  " + t));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // API 24+ callback (no @Override: we compile against API 23)
    public void onListenerDisconnected() { clear(); }

    @Override public void onDestroy() { clear(); super.onDestroy(); }

    private static void clear() {
        connected = false;
        instance = null;
        synchronized (entries) { entries.clear(); }
        lastKey = "";
        notifyListener();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { if (relevant(sbn)) scan(); }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { if (relevant(sbn)) scan(); }

    private static boolean looksLikeMedia(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return false;
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        if (x != null && x.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true;
        String tpl = x == null ? null : x.getString(Notification.EXTRA_TEMPLATE);
        if (tpl != null && tpl.contains("MediaStyle")) return true;
        return Notification.CATEGORY_TRANSPORT.equals(n.category);
    }

    private static boolean relevant(StatusBarNotification sbn) {
        if (looksLikeMedia(sbn)) return true;
        String pref = preferredPkg;
        return sbn != null && sbn.getNotification() != null && pref != null && pref.equals(sbn.getPackageName())
                && (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private void scan() {
        StatusBarNotification[] all;
        try { all = getActiveNotifications(); } catch (Throwable t) { return; }
        List<Entry> found = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        if (all != null) {
            for (StatusBarNotification sbn : all) {
                if (!relevant(sbn)) continue;
                Notification n = sbn.getNotification();
                Bundle x = n.extras;
                MediaSession.Token tok = null;
                String title = null, text = null;
                Bitmap art = null;
                if (x != null) {
                    Parcelable p = x.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                    if (p instanceof MediaSession.Token) tok = (MediaSession.Token) p;
                    CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence s = x.getCharSequence(Notification.EXTRA_TEXT);
                    title = t == null ? null : t.toString();
                    text = s == null ? null : s.toString();
                    Object li = x.get(Notification.EXTRA_LARGE_ICON);
                    if (li instanceof Bitmap) art = (Bitmap) li;
                    else if (li instanceof Icon) art = iconToBitmap((Icon) li, this);
                }
                if (art == null) {
                    try { Icon ic = n.getLargeIcon(); if (ic != null) art = iconToBitmap(ic, this); } catch (Throwable ignored) { }
                }
                Entry en = new Entry(sbn.getPackageName(), tok, title, text, art, sbn.getPostTime());
                if (n.actions != null && n.actions.length > 0) {
                    en.actions = new android.app.PendingIntent[n.actions.length];
                    en.actionTitles = new String[n.actions.length];
                    for (int ai = 0; ai < n.actions.length; ai++) {
                        en.actions[ai] = n.actions[ai].actionIntent;
                        en.actionTitles[ai] = n.actions[ai].title == null ? "" : n.actions[ai].title.toString();
                    }
                }
                if (x != null) en.compact = x.getIntArray(Notification.EXTRA_COMPACT_ACTIONS);
                if (x != null) {
                    StringBuilder ks = new StringBuilder();
                    for (String kk : x.keySet()) {
                        if (ks.length() > 300) break;
                        String nm = kk.startsWith("android.") ? kk.substring(8) : kk;
                        Object v = x.get(kk);
                        ks.append(nm);
                        if (v instanceof Number || v instanceof Boolean) ks.append('=').append(v);
                        ks.append(", ");
                    }
                    en.extras = ks.toString();
                }
                found.add(en);
                int actions = n.actions == null ? 0 : n.actions.length;
                key.append(sbn.getPackageName()).append('|').append(title).append('|').append(text).append('|')
                   .append(tok != null).append('|').append(n.when).append('|').append(actions).append('|')
                   .append(sbn.getPostTime()).append('|') // a re-post (e.g. play/pause icon swap) counts as a change
                   .append(art == null ? "-" : art.getWidth() + "x" + art.getHeight()).append(';');
            }
        }
        synchronized (entries) { entries.clear(); entries.addAll(found); }
        String k = key.toString();
        if (!k.equals(lastKey)) {
            lastKey = k;
            notifyListener();
        }
    }

    private static void notifyListener() {
        main.post(new Runnable() {
            @Override public void run() { Runnable r = listener; if (r != null) r.run(); }
        });
    }

    private static Bitmap iconToBitmap(Icon icon, Context c) {
        try {
            Drawable d = icon.loadDrawable(c);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
            int w = Math.min(300, Math.max(1, d.getIntrinsicWidth())), h = Math.min(300, Math.max(1, d.getIntrinsicHeight()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            return b;
        } catch (Throwable t) { return null; }
    }
}
