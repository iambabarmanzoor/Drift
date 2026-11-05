/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The phone's music over Bluetooth, on head units that use Android's own Bluetooth stack (the unit
 * acts as a Bluetooth speaker: "A2DP sink", with "AVRCP controller" for song info and buttons).
 *
 * Android's Bluetooth app publishes the phone's song, state and controls through a media browser
 * service built for other apps to use (A2dpMediaBrowserService on Android 7-9,
 * BluetoothMediaBrowserService on 10+). Drift connects to it only while a phone is connected over
 * Bluetooth: creating that service makes it take the media buttons, so connecting with no phone
 * around could disturb the unit's own Bluetooth music app.
 *
 * Everything here is driven by Android's Bluetooth broadcasts; nothing is polled.
 * Units whose Bluetooth runs on a separate chip (the factory app talks to it directly) send none
 * of these, and this class simply stays idle there.
 */
final class BluetoothMusic {
    interface Listener { void onBluetoothMusicChanged(); }

    // Android's Bluetooth actions (hidden in the SDK, stable since Android 5)
    private static final String A2DP_SINK_CONN = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";
    private static final String A2DP_SINK_PLAYING = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED";
    private static final String AVRCP_CT_CONN = "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED";
    static final String TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT";
    private static final String EXTRA_METADATA = "android.bluetooth.avrcp-controller.profile.extra.METADATA";
    private static final String EXTRA_PLAYBACK = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK";
    private static final int PROFILE_A2DP_SINK = 11, PROFILE_AVRCP_CONTROLLER = 12; // BluetoothProfile (hidden)
    private static final int STATE_PLAYING = 10;                                     // BluetoothA2dp.STATE_PLAYING

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started, phoneConnected, streaming;
    private MediaBrowser browser;
    private MediaController controller;
    private MediaMetadata trackMeta;     // from the track broadcasts, for when the browser service isn't there
    private PlaybackState trackState;
    private String status = "idle", lastEvent = "none";
    private ComponentName musicScreen;
    private boolean musicScreenSearched;
    private boolean refused;          // the service said no: don't retry until the connection changes
    private String servicePkg;        // package of Android's Bluetooth music service (null if none)
    private boolean serviceSearched;

    BluetoothMusic(Context c, Listener l) { ctx = c.getApplicationContext(); listener = l; }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { listener.onBluetoothMusicChanged(); }
        @Override public void onMetadataChanged(MediaMetadata m) { listener.onBluetoothMusicChanged(); }
        @Override public void onSessionDestroyed() { dropController(); listener.onBluetoothMusicChanged(); }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            lastEvent = a.substring(a.lastIndexOf('.') + 1) + " at " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            int state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (A2DP_SINK_CONN.equals(a) || AVRCP_CT_CONN.equals(a)) {
                setConnected(state == BluetoothProfile.STATE_CONNECTED || (A2DP_SINK_CONN.equals(a) ? isProfileConnected(PROFILE_AVRCP_CONTROLLER) : isProfileConnected(PROFILE_A2DP_SINK)));
            } else if (A2DP_SINK_PLAYING.equals(a)) {
                streaming = state == STATE_PLAYING;
                if (streaming) setConnected(true);
            } else if (TRACK_EVENT.equals(a)) {
                try {
                    Object m = i.getParcelableExtra(EXTRA_METADATA);
                    Object p = i.getParcelableExtra(EXTRA_PLAYBACK);
                    if (m instanceof MediaMetadata) trackMeta = (MediaMetadata) m;
                    if (p instanceof PlaybackState) trackState = (PlaybackState) p;
                } catch (Throwable ignored) { }
                setConnected(true); // song info only flows while a phone is connected
            }
            listener.onBluetoothMusicChanged();
        }
    };

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(A2DP_SINK_CONN);
        f.addAction(A2DP_SINK_PLAYING);
        f.addAction(AVRCP_CT_CONN);
        f.addAction(TRACK_EVENT);
        try { ctx.registerReceiver(rx, f); } catch (Throwable ignored) { }
        setConnected(isProfileConnected(PROFILE_A2DP_SINK) || isProfileConnected(PROFILE_AVRCP_CONTROLLER));
    }

    void stop() {
        if (!started) return;
        started = false;
        try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) { }
        disconnectBrowser();
    }

    private static boolean isProfileConnected(int profile) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled() && a.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) { return false; }
    }

    private void setConnected(boolean on) {
        if (on == phoneConnected) {
            if (on && browser == null && !refused) connectBrowser(); // e.g. the service was restarted
            return;
        }
        phoneConnected = on;
        refused = false; // a new connection: try again
        if (on) connectBrowser();
        else { streaming = false; trackMeta = null; trackState = null; disconnectBrowser(); status = "no phone connected"; }
    }

    private void connectBrowser() {
        if (browser != null) return;
        ComponentName svc = findBrowserService();
        if (svc == null) { status = "phone connected; this unit has no Bluetooth music service"; refused = true; return; }
        final ComponentName cn = svc;
        status = cn.getShortClassName() + ": connecting";
        final MediaBrowser[] me = new MediaBrowser[1];
        try {
            me[0] = new MediaBrowser(ctx, cn, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (browser != me[0]) { try { me[0].disconnect(); } catch (Throwable ignored) { } return; } // stale
                    dropController();
                    try {
                        controller = new MediaController(ctx, me[0].getSessionToken());
                        controller.registerCallback(cb, main);
                        status = cn.getShortClassName() + ": connected";
                    } catch (Throwable t) { status = cn.getShortClassName() + ": no session"; }
                    listener.onBluetoothMusicChanged();
                }
                @Override public void onConnectionFailed() {
                    if (browser != me[0]) return;
                    status = cn.getShortClassName() + ": refused"; refused = true; disconnectBrowser();
                }
                @Override public void onConnectionSuspended() {
                    if (browser != me[0]) return;
                    status = cn.getShortClassName() + ": dropped"; disconnectBrowser();
                    listener.onBluetoothMusicChanged();
                }
            }, null);
            browser = me[0];
            browser.connect();
        } catch (Throwable t) { status = cn.getShortClassName() + ": failed"; refused = true; browser = null; }
    }

    private void disconnectBrowser() {
        dropController();
        if (browser != null) { try { browser.disconnect(); } catch (Throwable ignored) { } browser = null; }
    }

    private void dropController() {
        if (controller != null) { try { controller.unregisterCallback(cb); } catch (Throwable ignored) { } controller = null; }
    }

    /** Android's Bluetooth app's media browser service (a service in the Bluetooth system package). */
    private ComponentName findBrowserService() {
        ComponentName found = null;
        try {
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                String pkg = ri.serviceInfo.packageName;
                if (pkg.equals("com.android.bluetooth") || pkg.toLowerCase(Locale.ROOT).contains("bluetooth")) {
                    found = new ComponentName(pkg, ri.serviceInfo.name);
                    if (pkg.equals("com.android.bluetooth")) break;
                }
            }
        } catch (Throwable ignored) { }
        servicePkg = found == null ? null : found.getPackageName();
        serviceSearched = true;
        return found;
    }

    /**
     * True for the phone's session from Android's Bluetooth service, however Drift got hold of it
     * (its own connection, or Android's list of active sessions).
     */
    boolean isBluetoothSession(MediaController m) {
        if (m == null) return false;
        if (controller != null && controller.getSessionToken().equals(m.getSessionToken())) return true;
        if (!serviceSearched) findBrowserService();
        String p = m.getPackageName();
        return p != null && (p.equals("com.android.bluetooth") || p.equals(servicePkg));
    }

    // ------------------------------------------------------------ what the hub reads

    boolean phoneConnected() { return phoneConnected; }

    /** The phone is sending audio right now (from Android's A2DP sink, or the session's own state). */
    boolean streaming() {
        if (streaming) return true;
        PlaybackState s = controller != null ? controller.getPlaybackState() : trackState;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** The session to show and control (null if the service isn't available). */
    MediaController controller() { return controller; }

    /** Song details from the broadcasts, for units where the service can't be reached. */
    MediaMetadata trackMetadata() { return trackMeta; }

    PlaybackState trackState() { return trackState; }

    /**
     * The unit's Bluetooth music screen, to open on a tap. Factory Bluetooth apps often hold both a
     * phone/dialer screen and a music screen in one package, so the screen is found by its own name.
     */
    ComponentName musicScreen() {
        if (musicScreenSearched) return musicScreen;
        musicScreenSearched = true;
        ComponentName byClass = null;
        try {
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(li, 0)) {
                if (ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                String label = String.valueOf(ri.loadLabel(ctx.getPackageManager())).toLowerCase(Locale.ROOT);
                String all = (cn.getPackageName() + " " + cn.getClassName() + " " + label).toLowerCase(Locale.ROOT);
                // "BT" only as a word ("BT Music", "bt.music"), not inside other words like "subtitle"
                boolean bt = all.contains("bluetooth") || all.matches(".*(^|[^a-z])bt([^a-z]|$).*") || all.contains("a2dp") || all.contains("蓝牙");
                if (bt && (label.contains("music") || label.contains("audio") || label.contains("音乐"))) { musicScreen = cn; break; }
                if (byClass == null && bt && (cn.getClassName().toLowerCase(Locale.ROOT).contains("music") || all.contains("a2dp"))) byClass = cn;
            }
        } catch (Throwable ignored) { }
        if (musicScreen == null) musicScreen = byClass;
        return musicScreen;
    }

    String diagnostics() {
        ComponentName ms = musicScreen();
        return "Phone connected: " + (phoneConnected ? "yes" : "no") + (streaming() ? ", streaming" : "")
                + "\nBluetooth music service: " + status
                + "\nLast Bluetooth event: " + lastEvent
                + "\nBluetooth music screen: " + (ms == null ? "not found" : ms.flattenToShortString());
    }
}
