/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Finds whatever is playing, from four sources in order of quality:
 *  1. Media sessions Android reports (needs Notification access)
 *  2. Media sessions found inside "now playing" notifications (also needs access)
 *  3. Now-playing notifications without a session (title/artist only)
 *  4. Legacy "track changed" broadcasts (stock players, Poweramp, Spotify)
 * Plus: if nothing is detectable but audio is playing, it still says so.
 */
public class MediaHub {
    /** Supplied by the launcher: the user's chosen music app. */
    public interface Player {
        String preferredPackage();
        void launchPreferred();
        /** A song's length and cover need storage access: ask for it (once). */
        void needStorage();
        /** Bring Drift back to the front (after briefly opening the player to wake it). */
        void returnHome();
    }

    private final Context ctx;
    private final MediaCard card;
    private final Player player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private MediaSessionManager msm;
    private MediaController controller;
    /** The last session we showed. Kept across pause and screen changes so play can resume it. */
    private MediaController remembered;
    private String lastTitle, lastArtist, lastAlbum;
    private Bitmap lastArt;
    private boolean started, legacyPlaying, legacyShown;
    private String lastPkg, lastKey, lastBroadcast = "none", legacyPkg;
    private boolean cardPlaying;
    private Bitmap lastSmall;
    private final HashMap<String, String> labels = new HashMap<>();
    /** Sessions that report no song or state (the unit's player has one). We still try their controls. */
    private final HashMap<String, MediaController> stubs = new HashMap<>();

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { pushState(state); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { push(); }
        @Override public void onSessionDestroyed() {
            if (controller != null) controller.unregisterCallback(this);
            if (remembered == controller) remembered = null;
            controller = null;
            refresh();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { refresh(); }
            };

    /** Drift Music changed song or state (also fires once it has restored the last song at start). */
    private final MusicService.Listener ownChanged = new MusicService.Listener() {
        private boolean queued;
        @Override public void onMusicState() {
            if (queued || !started) return;
            queued = true; // coalesce bursts (seeking, buffering) into one refresh
            main.postDelayed(new Runnable() { @Override public void run() { queued = false; if (started) refresh(); } }, 150);
        }
    };

    private boolean awaitingToggle;
    private final Runnable notifChanged = new Runnable() {
        @Override public void run() { awaitingToggle = false; if (started) refresh(); }
    };

    private final BroadcastReceiver legacy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String act = i.getAction() == null ? "" : i.getAction();
            lastBroadcast = act + " at " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            boolean bt = act.startsWith("android.bluetooth.avrcp");
            if (controller != null) { // a real session is better, unless it's idle and the phone is playing over Bluetooth
                android.media.session.PlaybackState cs = controller.getPlaybackState();
                boolean ctlPlaying = cs != null && cs.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                if (!bt || ctlPlaying) return;
            }
            if (isInitialStickyBroadcast()) return; // an old track replayed on register, not what's playing now
            String track = null, artist = null, album = null, src = "";
            String pkgGuess = act.startsWith("com.maxmpz.audioplayer") ? "com.maxmpz.audioplayer"
                    : act.startsWith("com.spotify.music") ? "com.spotify.music" : null;
            if (pkgGuess == null && act.lastIndexOf('.') > 0) {
                String guess = act.substring(0, act.lastIndexOf('.'));
                if (ctx.getPackageManager().getLaunchIntentForPackage(guess) != null) pkgGuess = guess;
            }
            Boolean playing = null;
            if (act.startsWith("android.bluetooth.avrcp")) {           // phone over Bluetooth
                src = "Bluetooth";
                pkgGuess = null;
                try {
                    Object md = i.getParcelableExtra("android.bluetooth.avrcp-controller.profile.extra.METADATA");
                    if (md instanceof android.media.MediaMetadata) {
                        android.media.MediaMetadata m = (android.media.MediaMetadata) md;
                        track = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                        artist = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                        album = m.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
                    }
                    Object pb = i.getParcelableExtra("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK");
                    if (pb instanceof android.media.session.PlaybackState)
                        playing = ((android.media.session.PlaybackState) pb).getState() == android.media.session.PlaybackState.STATE_PLAYING;
                } catch (Throwable ignored) { }
            } else if (act.startsWith("com.maxmpz.audioplayer")) {           // Poweramp
                src = "Poweramp";
                Bundle t = i.getBundleExtra("track");
                if (t != null) { track = t.getString("title"); artist = t.getString("artist"); album = t.getString("album"); }
                if (i.hasExtra("paused")) playing = !i.getBooleanExtra("paused", true);
            } else {
                if (act.startsWith("com.spotify")) src = "Spotify";
                track = i.getStringExtra("track");
                artist = i.getStringExtra("artist");
                album = i.getStringExtra("album");
                if (i.hasExtra("playing")) playing = i.getBooleanExtra("playing", false);
                else if (i.hasExtra("isplaying")) playing = i.getBooleanExtra("isplaying", false);
                else if (i.hasExtra("playstate")) playing = i.getBooleanExtra("playstate", false);
            }
            if (playing != null) legacyPlaying = playing;
            if (track != null || artist != null) {
                legacyShown = true;
                legacyPkg = pkgGuess;
                cardPlaying = legacyPlaying;
                card.update(track, artist, album, src, null, legacyPlaying, -1, 0, 1f, 0);
            } else if (legacyShown) {
                cardPlaying = legacyPlaying;
                card.setPlaying(legacyPlaying);
            }
        }
    };

    /**
     * The music app last opened from Drift (e.g. the unit's Bluetooth music). Some units play Bluetooth
     * audio outside Android, so the app shares nothing; the card then shows that app instead of an
     * old paused song, and a tap opens it. Cleared when something else actually plays.
     */
    private String externalPkg;

    public void externalOpened(String pkg) { externalPkg = pkg; if (started) refresh(); }

    // ---- another source is playing that nobody reports (e.g. Bluetooth on units whose BT app shares nothing)
    private long otherSince;        // when audio started playing with no player reporting it (0 = not)
    private boolean showingOther;   // the card is showing "Playing in ..." for that source
    private static final long OTHER_AFTER_MS = 3000; // ignore short sounds like navigation prompts

    public boolean showingOther() { return showingOther; }

    /** Audio is playing, but not Drift Music and not any player that tells us about it. */
    private boolean otherAudio() {
        if (!audio.isMusicActive()) return false;
        MusicService o = MusicService.get();
        if (o != null && o.isPlaying()) return false;
        MediaController c = controller;
        if (c != null && !ctx.getPackageName().equals(c.getPackageName())) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return false;
        }
        return true;
    }

    /** The unit's Bluetooth music app, found by name like Drift's shortcut icons (null if none). */
    private String btMusicPkg;
    private boolean btSearched;
    private String findBtMusicApp() {
        if (btSearched) return btMusicPkg;
        btSearched = true;
        try {
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(li, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                String s = (pkg + " " + ri.loadLabel(ctx.getPackageManager())).toLowerCase(Locale.ROOT);
                boolean bt = s.contains("bluetooth") || s.contains("bt") || s.contains("a2dp") || s.contains("蓝牙");
                if (bt && (s.contains("music") || s.contains("audio") || s.contains("a2dp") || s.contains("音乐"))) { btMusicPkg = pkg; break; }
            }
        } catch (Throwable ignored) { }
        return btMusicPkg;
    }

    // ---- Android's own Bluetooth music service (the phone's song over Bluetooth, on units using Android's stack)
    private MediaBrowser btBrowser;
    private MediaController btCtl;
    private String btStatus = "not looked for yet";
    private final MediaController.Callback btCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { if (started) refresh(); }
        @Override public void onMetadataChanged(MediaMetadata m) { if (started) refresh(); }
    };

    private void connectBluetoothMusic() {
        if (btBrowser != null) return;
        ComponentName found = null;
        StringBuilder seen = new StringBuilder();
        try {
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager().queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                String pkg = ri.serviceInfo.packageName;
                if (seen.length() > 0) seen.append(", ");
                seen.append(pkg).append('/').append(ri.serviceInfo.name.replace(pkg, ""));
                if (found == null && pkg.toLowerCase(Locale.ROOT).contains("bluetooth")) found = new ComponentName(pkg, ri.serviceInfo.name);
            }
        } catch (Throwable ignored) { }
        if (found == null) { btStatus = "none on this unit" + (seen.length() > 0 ? " (music services: " + seen + ")" : ""); return; }
        final ComponentName cn = found;
        btStatus = cn.flattenToShortString() + ": connecting";
        try {
            btBrowser = new MediaBrowser(ctx, cn, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    try {
                        btCtl = new MediaController(ctx, btBrowser.getSessionToken());
                        btCtl.registerCallback(btCb, main);
                        btStatus = cn.flattenToShortString() + ": connected";
                    } catch (Throwable t) { btStatus = cn.flattenToShortString() + ": no session"; }
                    if (started) refresh();
                }
                @Override public void onConnectionFailed() { btStatus = cn.flattenToShortString() + ": refused"; btBrowser = null; }
                @Override public void onConnectionSuspended() { btStatus = cn.flattenToShortString() + ": disconnected"; if (btCtl != null) btCtl.unregisterCallback(btCb); btCtl = null; btBrowser = null; }
            }, null);
            btBrowser.connect();
        } catch (Throwable t) { btStatus = cn.flattenToShortString() + ": failed (" + t.getClass().getSimpleName() + ")"; btBrowser = null; }
    }

    static final String AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT";

    public MediaHub(Context c, MediaCard card, Player player) {
        ctx = c;
        this.card = card;
        this.player = player;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    public static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        for (String part : s.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && c.getPackageName().equals(cn.getPackageName())) return true;
        }
        return false;
    }

    public void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        String[] acts = {
            "com.android.music.metachanged", "com.android.music.playstatechanged",
            "com.android.music.metadatachanged", "com.android.music.playbackcomplete",
            "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
            "com.maxmpz.audioplayer.TRACK_CHANGED", "com.maxmpz.audioplayer.STATUS_CHANGED",
            "com.htc.music.metachanged", "com.miui.player.metachanged", "com.sec.android.app.music.metachanged",
            "com.samsung.sec.android.MusicPlayer.metachanged", "fm.last.android.metachanged",
            "com.rdio.android.metachanged", "com.andrew.apollo.metachanged", "com.amazon.mp3.metachanged",
            "com.real.IMP.metachanged", "com.lge.music.metachanged", "com.nullsoft.winamp.metachanged",
            // Bluetooth music from the phone (Android's AVRCP controller, used by many head units' BT apps)
            AVRCP_TRACK, "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_STATE_CHANGED",
        };
        for (String a : acts) f.addAction(a);
        try { ctx.registerReceiver(legacy, f); } catch (Exception ignored) { }
        connectBluetoothMusic();

        boolean access = hasAccess(ctx);
        card.setNeedsAccess(!access);
        main.removeCallbacks(checkLink);
        MediaWatcher.setListener(notifChanged);
        MusicService.addListener(ownChanged);
        if (access) {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            try {
                m.addOnActiveSessionsChangedListener(sessions, new ComponentName(ctx, MediaWatcher.class), main);
                msm = m;
            } catch (Exception e) {
                msm = null;
            }
        }
        if (access && msm == null) card.setNeedsAccess(true, "This unit blocked music access");
        if (access && !MediaWatcher.connected) MediaWatcher.rebind(ctx);
        if (access) main.postDelayed(checkLink, 4000);
        refresh();
        card.resumeTicking();
        main.postDelayed(poll, 2000);
    }

    /** Access is switched on but Android never connected Drift's listener: say so on the card. */
    private final Runnable checkLink = new Runnable() {
        @Override public void run() {
            if (!started || !hasAccess(ctx)) return;
            if (!MediaWatcher.connected) {
                MediaWatcher.rebind(ctx);
                card.setNeedsAccess(true, "Access is on but not connected. Tap to fix");
            } else if (msm != null) card.setNeedsAccess(false);
        }
    };

    public static String status(Context c) {
        if (!hasAccess(c)) return "off";
        return MediaWatcher.connected ? "connected" : "not connected";
    }

    /** Without a session we can't be told when audio starts or stops, so check every 2 s. */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= holdUntil) {
                if (otherAudio()) { if (otherSince == 0) otherSince = now; } else otherSince = 0;
                boolean want = otherSince != 0 && now - otherSince >= OTHER_AFTER_MS;
                if (want != showingOther) refresh();
                else if (controller == null && !legacyShown) {
                    boolean active = audio.isMusicActive();
                    if (active != cardPlaying) refresh();
                }
            }
            main.postDelayed(this, 1500);
        }
    };

    public void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(launchIfSilent);
        main.removeCallbacks(checkLink);
        try { ctx.unregisterReceiver(legacy); } catch (Exception ignored) { }
        MediaWatcher.setListener(null);
        MusicService.removeListener(ownChanged);
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
            msm = null;
        }
        if (controller != null) { controller.unregisterCallback(cb); remembered = controller; }
        controller = null;
    }

    /** Jump to a position in the current track (only when a real session is known). */
    public void seekTo(long ms) {
        ms = Math.max(0, ms);
        if (controller != null) { controller.getTransportControls().seekTo(ms); return; }
        MediaController st = lastPkg == null ? null : stubs.get(lastPkg);
        if (st != null) {
            try { st.getTransportControls().seekTo(ms); } catch (Exception ignored) { }
            clockPos = ms; clockAt = SystemClock.elapsedRealtime(); // our song clock follows the jump
        }
    }

    public boolean canSeek() { return controller != null; }

    /** Gather every controller we can find and show the best one. */
    private void refresh() {
        if (msm != null && MediaWatcher.connected) card.setNeedsAccess(false); // the link came up
        List<MediaController> list = new ArrayList<>();
        if (msm != null) {
            try { list.addAll(msm.getActiveSessions(new ComponentName(ctx, MediaWatcher.class))); } catch (Exception ignored) { }
        }
        // Drift's own player is always visible to the card, with or without notification access
        MusicService own = MusicService.get();
        if (own != null && own.songAvailable() && (own.isPlaying() || ctx.getPackageName().equals(player.preferredPackage()))) {
            boolean dup = false;
            for (MediaController m : list) if (m.getSessionToken().equals(own.token())) { dup = true; break; }
            if (!dup) try { list.add(new MediaController(ctx, own.token())); } catch (Exception ignored) { }
        }
        if (btCtl != null) {
            boolean dup = false;
            for (MediaController m : list) if (m.getSessionToken().equals(btCtl.getSessionToken())) { dup = true; break; }
            if (!dup) list.add(btCtl);
        }
        List<MediaWatcher.Entry> notes = MediaWatcher.entries();
        for (MediaWatcher.Entry e : notes) {
            if (e.token == null) continue;
            boolean dup = false;
            for (MediaController m : list) if (m.getSessionToken().equals(e.token)) { dup = true; break; }
            if (!dup) {
                try { list.add(new MediaController(ctx, e.token)); } catch (Exception ignored) { }
            }
        }
        // drop dead sessions (a notification can outlive its player), and empty ones: a session that
        // isn't playing and has no song (e.g. the unit's Bluetooth service sitting idle) would otherwise
        // take over the card and just show the app's name
        stubs.clear();
        for (MediaController m : list) if (m.getPackageName() != null) stubs.put(m.getPackageName(), m);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).getPackageName() == null || !useful(list.get(i))) list.remove(i);
        MediaController best = null;
        for (MediaController m : list) {
            PlaybackState s = m.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
        }
        if (best != null && externalPkg != null && !externalPkg.equals(best.getPackageName())) externalPkg = null; // something else is playing now
        MusicService ownSvc = MusicService.get();
        if (ownSvc != null && ownSvc.isPlaying()) externalPkg = null;
        if (best == null && externalPkg != null) {
            for (MediaController m : list) if (externalPkg.equals(m.getPackageName())) { best = m; break; }
        }
        showingOther = false;
        boolean other = otherSince != 0 && SystemClock.elapsedRealtime() - otherSince >= OTHER_AFTER_MS;
        boolean songNote = false;
        for (MediaWatcher.Entry e : notes) if (e.title != null || e.text != null) songNote = true;
        if (best == null && other && !songNote) {
            // Something is playing that no player reports (e.g. Bluetooth on units whose BT app shares
            // nothing). Don't show Drift Music's old song: show the likely source, and let go of the
            // media keys so the buttons reach it.
            MusicService o = MusicService.get();
            if (o != null) o.yieldMediaKeys();
            String pkg = externalPkg != null ? externalPkg : findBtMusicApp();
            if (controller != null) { controller.unregisterCallback(cb); controller = null; }
            showingOther = true;
            legacyShown = true;
            legacyPkg = pkg;
            lastPkg = pkg;
            cardPlaying = true;
            if (pkg != null) card.update(appLabel(pkg), "Playing in " + appLabel(pkg) + " \u00b7 tap to open", null, appLabel(pkg), null, true, -1, 0, 1f, 0);
            else card.update("Playing on another source", "Bluetooth, radio or another app", null, "", null, true, -1, 0, 1f, 0);
            return;
        }
        if (best == null && controller != null) { // keep showing the app the user was just using
            for (MediaController m : list) if (m.getSessionToken().equals(controller.getSessionToken())) { best = m; break; }
        }
        String pref = player.preferredPackage();
        if (best == null && pref != null) {
            for (MediaController m : list) if (pref.equals(m.getPackageName())) { best = m; break; }
        }
        if (best == null) {
            // Many players drop out of the "active" list when paused. Stay on the one we were showing
            // (if its session is still alive) so pressing play resumes it.
            MediaController keep = controller != null ? controller : remembered;
            if (keep != null && keep.getPackageName() != null && useful(keep)) best = keep;
        }
        if (best == null && !list.isEmpty()) best = list.get(0);

        if (controller != null && best != null && controller.getSessionToken().equals(best.getSessionToken())) {
            push();
            return;
        }
        if (controller != null) controller.unregisterCallback(cb);
        controller = best;
        if (controller != null) {
            main.removeCallbacks(launchIfSilent);
            controller.registerCallback(cb, main);
            push();
            return;
        }
        // no session: fall back to a plain now-playing notification
        MediaWatcher.Entry note = null;
        for (MediaWatcher.Entry e : notes) if (note == null || e.when > note.when) note = e;
        boolean active = playingNow();
        if (note != null && (note.title != null || note.text != null)) {
            legacyShown = false;
            lastPkg = note.pkg;
            cardPlaying = active;
            showNote(note, active);
            return;
        }
        if (legacyShown) return; // keep what a broadcast told us
        if (!active && lastPkg != null && lastTitle != null) {
            // nothing reports anything now: keep the last track on screen, paused; play resumes that app
            cardPlaying = false;
            card.update(lastTitle, lastArtist, lastAlbum, appLabel(lastPkg), lastArt, false, -1, 0, 1f, 0);
            return;
        }
        lastPkg = null;
        cardPlaying = active;
        if (active) card.update("Music is playing", null, null, "", null, true, -1, 0, 1f, 0);
        else card.update(null, null, null, "", null, false, -1, 0, 1f, 0);
    }

    /** Playing, or knows which song it has. */
    private static boolean useful(MediaController m) {
        try {
            PlaybackState s = m.getPlaybackState();
            if (s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING)) return true;
            MediaMetadata md = m.getMetadata();
            return md != null && (md.getString(MediaMetadata.METADATA_KEY_TITLE) != null || md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null);
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------ notification-only players

    private String clockKey;
    private long clockPos, clockAt;
    private boolean clockRunning;

    private final TrackInfo.Callback infoReady = new TrackInfo.Callback() {
        @Override public void onInfo(String key, TrackInfo.Info info) { if (started && controller == null) refresh(); }
    };

    /**
     * A player that only posts "Song - Artist" in a notification (no session details). We split the
     * name, look the song up in the media library for its length and cover, and keep time ourselves:
     * from when the notification for this song first appeared, counting only while audio plays.
     */
    private void showNote(MediaWatcher.Entry note, boolean active) {
        String t = note.title, a = note.text;
        if (t != null && (a == null || a.trim().isEmpty())) {
            int k = t.indexOf(" - ");
            if (k > 0) { a = t.substring(k + 3).trim(); t = t.substring(0, k).trim(); }
        }
        if (t == null) t = appLabel(note.pkg);

        TrackInfo.Info info = TrackInfo.get(ctx, t, a, infoReady);
        if (info == null && !TrackInfo.allowed(ctx)) player.needStorage();
        long dur = info == null ? 0 : info.durationMs;

        long now = SystemClock.elapsedRealtime();
        String key = note.pkg + "|" + note.title;
        if (!key.equals(clockKey)) {
            clockKey = key;
            long since = System.currentTimeMillis() - note.when; // players re-post the notification when the song changes
            clockPos = since > 0 && (dur <= 0 || since < dur) ? since : 0;
            clockAt = now;
        } else if (clockRunning) {
            clockPos += now - clockAt;
            clockAt = now;
        } else clockAt = now;
        clockRunning = active;
        if (dur > 0 && clockPos > dur) clockPos = dur;

        Bitmap art = info != null && info.art != null ? small(info.art) : small(note.art);
        lastTitle = t; lastArtist = a; lastAlbum = null; lastArt = art;
        card.setSeekable(false); // tested on the unit: this kind of player ignores jump requests, so don't pretend
        card.update(t, a, null, appLabel(note.pkg), art, active, clockPos, dur, 1f, now);
    }

    private long holdUntil; // until then, trust the button the user just pressed over Android's slow audio state

    private void showLocalState(boolean playing) {
        long now = SystemClock.elapsedRealtime();
        if (clockRunning) clockPos += now - clockAt;
        clockAt = now;
        clockRunning = playing;
        cardPlaying = playing;
        holdUntil = now + 2800;
        card.setPlayback(playing, clockPos, 1f, now);
    }

    private boolean playingNow() {
        return SystemClock.elapsedRealtime() < holdUntil ? cardPlaying : audio.isMusicActive();
    }

    /**
     * After the car starts, a player like VLC shows its last song but its playback engine is asleep,
     * so "play" through its session does nothing. If nothing plays shortly after a play press:
     *   1. send the media PLAY key (Android routes it to the player, which wakes up and resumes);
     *   2. if still silent, open the player for a moment, press play there, and come back home.
     */
    private int resumeTry;
    private void resumeWatch(final String pkg) {
        final int my = ++resumeTry;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!started || my != resumeTry || nowPlaying()) return;
                key(KeyEvent.KEYCODE_MEDIA_PLAY);
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (my != resumeTry || nowPlaying() || pkg == null) return;
                        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                        if (open == null) return;
                        try { ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { return; }
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (my != resumeTry) return;
                                MediaController c = controller;
                                if (!nowPlaying()) {
                                    if (c != null && pkg.equals(c.getPackageName())) c.getTransportControls().play();
                                    else key(KeyEvent.KEYCODE_MEDIA_PLAY);
                                }
                                main.postDelayed(new Runnable() { @Override public void run() { if (my == resumeTry) player.returnHome(); } }, 1200);
                            }
                        }, 1800);
                    }
                }, 1500);
            }
        }, 1200);
    }

    private boolean nowPlaying() {
        MediaController c = controller;
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return (s != null && s.getState() == PlaybackState.STATE_PLAYING) || audio.isMusicActive();
    }

    /** Previous restarts the song (or goes back one): either way it plays from the start. */
    private void restartClock() { clockPos = 0; clockAt = SystemClock.elapsedRealtime(); main.postDelayed(new Runnable() { @Override public void run() { if (started && controller == null) refresh(); } }, 500); }

    /** Re-read everything now (e.g. after a permission was granted). */
    public void refreshNow() { if (started) { clockKey = null; refresh(); } }

    private void push() {
        if (controller == null) { refresh(); return; }
        MediaMetadata md = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        String title = null, artist = null, album = null;
        Bitmap art = null;
        long dur = 0;
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        lastPkg = controller.getPackageName();
        if (title == null) {
            // some players only fill the notification
            for (MediaWatcher.Entry e : MediaWatcher.entries()) {
                if (e.pkg.equals(lastPkg)) { title = e.title; artist = e.text; if (art == null) art = e.art; break; }
            }
        }
        if (title == null && lastPkg != null) { // playing, but the app shares no song details
            title = appLabel(lastPkg);
            if (artist == null) artist = "Song details not shared";
        }
        boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        long pos = st == null ? -1 : st.getPosition();
        float speed = st == null ? 1f : (st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed());
        long t = st == null ? SystemClock.elapsedRealtime() : st.getLastPositionUpdateTime();
        String key = lastPkg + "|" + title + "|" + artist + "|" + album + "|" + (art == null ? "-" : art.getWidth() + "x" + art.getHeight());
        Bitmap sm;
        if (key.equals(lastKey)) sm = lastSmall;
        else { sm = small(art); lastSmall = sm; lastKey = key; }
        legacyShown = false;
        cardPlaying = playing;
        remembered = controller;
        lastTitle = title; lastArtist = artist; lastAlbum = album; lastArt = sm;
        card.setSeekable(true);
        card.update(title, artist, album, appLabel(lastPkg), sm, playing, pos, dur, speed, t);
    }

    private void pushState(PlaybackState st) {
        if (st == null) return;
        boolean playing = st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING;
        float speed = st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
        cardPlaying = playing;
        card.setPlayback(playing, st.getPosition(), speed, st.getLastPositionUpdateTime());
        if (!playing) main.post(new Runnable() { @Override public void run() { if (started) refresh(); } }); // another app may have taken over
    }

    private Bitmap small(Bitmap b) {
        if (b == null) return null;
        int max = 300;
        if (b.getWidth() <= max && b.getHeight() <= max) return b;
        float sc = max / (float) Math.max(b.getWidth(), b.getHeight());
        try {
            return Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * sc)), Math.max(1, (int) (b.getHeight() * sc)), true);
        } catch (Throwable t) { return null; }
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String l = pm.getApplicationLabel(ai).toString();
            labels.put(pkg, l);
            return l;
        } catch (Exception e) { return ""; }
    }

    /** Package of whatever we're currently showing (null when nothing is known). */
    public String playingPackage() { return lastPkg; }

    // ------------------------------------------------------------ controls

    /** Card buttons while another, silent source plays: standard media keys, which reach it once Drift Music lets go. */
    private boolean otherKey(int code) {
        if (!showingOther) return false;
        MusicService o = MusicService.get();
        if (o != null) o.yieldMediaKeys();
        key(code);
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { cardPlaying = !cardPlaying; holdUntil = SystemClock.elapsedRealtime() + 2800; card.setPlaying(cardPlaying); }
        return true;
    }

    public void playPause() {
        main.removeCallbacks(launchIfSilent);
        resumeTry++; // any new press cancels a pending wake-up
        if (otherKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) return;
        if (controller != null) {
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { controller.getTransportControls().pause(); return; }
            controller.getTransportControls().play();
            if (!ctx.getPackageName().equals(controller.getPackageName())) resumeWatch(controller.getPackageName()); // ours wakes itself
            return;
        }
        String target = targetPkg();
        final MediaController st = target == null ? null : stubs.get(target);
        if (st != null) {
            // The player's session: ask it to pause or play directly. If the sound doesn't change,
            // fall back to the media key Android routes to that player.
            final boolean was = cardPlaying;
            try { if (was) st.getTransportControls().pause(); else st.getTransportControls().play(); } catch (Exception ignored) { }
            showLocalState(!was); // flip the button straight away; Android only reports the change a second or two later
            // The player re-posts its notification (play/pause icon) the moment it reacts. If that doesn't
            // happen quickly, the session ignored us: send the media key instead.
            awaitingToggle = true;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!started) return;
                    if (awaitingToggle) { awaitingToggle = false; key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
                }
            }, 700);
            main.postDelayed(new Runnable() { @Override public void run() { if (started) recheckSoon(); } }, 2200);
            return;
        }
        if (target != null) {
            // Many simple players (the unit's own included) only react to the toggle key, not to separate
            // PLAY / PAUSE keys; next and previous already worked, so the same route with the toggle works too.
            sendKey(target, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            recheckSoon();
            return;
        }
        if (audio.isMusicActive()) {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); // something is playing: the key goes to it
            recheckSoon();
            return;
        }
        startPreferred();
    }

    public void next() {
        main.removeCallbacks(launchIfSilent);
        resumeTry++; // any new press cancels a pending wake-up
        if (otherKey(KeyEvent.KEYCODE_MEDIA_NEXT)) return;
        if (controller != null) { controller.getTransportControls().skipToNext(); return; }
        String target = targetPkg();
        if (target != null && noteAction(target, A_NEXT)) { restartClock(); return; }
        if (target != null) sendKey(target, KeyEvent.KEYCODE_MEDIA_NEXT);
        else if (audio.isMusicActive()) key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void prev() {
        main.removeCallbacks(launchIfSilent);
        resumeTry++; // any new press cancels a pending wake-up
        if (otherKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)) return;
        if (controller != null) { controller.getTransportControls().skipToPrevious(); return; }
        String target = targetPkg();
        if (target != null && noteAction(target, A_PREV)) { restartClock(); return; }
        if (target != null) sendKey(target, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        else if (audio.isMusicActive()) key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private static final int A_PREV = 0, A_PLAY = 1, A_NEXT = 2;

    /**
     * Press one of the player's own notification buttons. Players that share nothing else (like the
     * unit's own) still put previous / play-pause / next in their notification, and those always work.
     */
    private boolean noteAction(String pkg, int which) {
        if (pkg == null) return false;
        MediaWatcher.Entry e = null;
        for (MediaWatcher.Entry x : MediaWatcher.entries()) if (pkg.equals(x.pkg) && x.actions != null) { e = x; break; }
        if (e == null) return false;
        int n = e.actions.length, idx = -1;
        String[][] words = { { "prev", "back", "rewind", "上一", "previous" }, { "pause", "play", "播放", "暂停" }, { "next", "skip", "forward", "下一" } };
        for (int i = 0; i < n && idx < 0; i++) {
            String t = e.actionTitles[i].toLowerCase(Locale.ROOT);
            for (String w : words[which]) if (t.contains(w)) { idx = i; break; }
        }
        if (idx < 0 && e.compact != null && e.compact.length == 3) idx = e.compact[which];
        if (idx < 0 && n == 3) idx = which;
        if (idx < 0 && n == 5) idx = which + 1; // e.g. like / prev / play / next / close
        if (idx < 0 || idx >= n || e.actions[idx] == null) return false;
        try { e.actions[idx].send(); return true; } catch (Exception ex) { return false; }
    }

    /** The app on screen when we have no session for it (notification or broadcast source). */
    private String targetPkg() {
        if (legacyShown && legacyPkg != null) return legacyPkg;
        if (!legacyShown && lastPkg != null) return lastPkg;
        return null;
    }

    /** Sync the play/pause icon with reality shortly after a key press. */
    private void recheckSoon() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!started || controller != null) return;
                cardPlaying = playingNow();
                legacyPlaying = cardPlaying;
                if (!legacyShown) refresh(); // re-reads the notification and stops/starts our song clock
                else card.setPlaying(cardPlaying);
            }
        }, 700);
    }

    private void sendKey(String pkg, int code) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, action, code, 0));
            try { ctx.sendBroadcast(i); } catch (Exception ignored) { }
        }
    }

    private final Runnable launchIfSilent = new Runnable() {
        @Override public void run() {
            if (started && controller == null && !audio.isMusicActive()) player.launchPreferred();
        }
    };

    /**
     * Nothing is playing: ask the user's music app (not "whatever played last") to play.
     * Sends it a play key directly; if it doesn't start within a second, opens the app instead.
     */
    private void startPreferred() {
        main.removeCallbacks(launchIfSilent);
        final String pkg = player.preferredPackage();
        if (pkg == null) { player.launchPreferred(); return; }
        sendKey(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
        recheckSoon();
        main.postDelayed(launchIfSilent, 2500); // cold-starting players can be slow on this hardware
    }

    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    // ------------------------------------------------------------ diagnostics

    private String version() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("Drift ").append(version()).append('\n');
        b.append("Notification access: ").append(hasAccess(ctx) ? "ON" : "OFF").append('\n');
        b.append("Listener connected: ").append(MediaWatcher.connected ? "yes" : "no").append('\n');
        b.append("Audio playing now: ").append(audio.isMusicActive() ? "yes" : "no").append('\n');
        b.append("Last music broadcast: ").append(lastBroadcast).append('\n');
        b.append("Bluetooth music service: ").append(btStatus).append('\n');
        if (btCtl != null) {
            PlaybackState bs = btCtl.getPlaybackState();
            MediaMetadata bm = btCtl.getMetadata();
            String bt = bm == null ? null : bm.getString(MediaMetadata.METADATA_KEY_TITLE);
            b.append("  state ").append(bs == null ? "?" : String.valueOf(bs.getState())).append("  song: ").append(bt == null ? "none" : bt).append('\n');
        }
        String btApp = findBtMusicApp();
        b.append("Bluetooth music app: ").append(btApp == null ? "not found" : btApp).append('\n');
        b.append("Another source playing: ").append(showingOther ? "yes (card shows it)" : otherSince != 0 ? "maybe (checking)" : "no").append('\n');
        b.append("\nMedia sessions:\n");
        int n = 0;
        if (msm != null) {
            try {
                for (MediaController m : msm.getActiveSessions(new ComponentName(ctx, MediaWatcher.class))) {
                    PlaybackState s = m.getPlaybackState();
                    MediaMetadata md = m.getMetadata();
                    String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    b.append("  ").append(m.getPackageName()).append("  state ").append(s == null ? "?" : String.valueOf(s.getState()))
                     .append("  song: ").append(t == null ? "none" : t).append('\n');
                    n++;
                }
            } catch (Exception e) { b.append("  (blocked: ").append(e.getClass().getSimpleName()).append(")\n"); }
        }
        if (n == 0) b.append("  none\n");
        b.append("\nNow-playing notifications:\n");
        List<MediaWatcher.Entry> es = MediaWatcher.entries();
        for (MediaWatcher.Entry e : es) {
            b.append("  ").append(e.pkg).append(e.token != null ? "  (session)" : "  (no session)")
             .append("  ").append(e.title == null ? "" : e.title).append(e.text == null ? "" : " / " + e.text).append('\n');
            if (e.extras.length() > 0) b.append("    fields: ").append(e.extras).append('\n');
            if (e.actions != null) {
                b.append("    buttons: ");
                for (int i = 0; i < e.actions.length; i++) b.append(i).append('=').append(e.actionTitles[i].isEmpty() ? "(icon)" : e.actionTitles[i]).append("  ");
                if (e.compact != null) { b.append(" compact:"); for (int c : e.compact) b.append(' ').append(c); }
                b.append('\n');
            }
        }
        if (es.isEmpty()) b.append("  none\n");
        b.append("\nSong length lookup:\n  ").append(TrackInfo.status(ctx, lastTitle, lastArtist).replace("\n", "\n  ")).append('\n');
        b.append("\nOther ongoing notifications:\n");
        List<String> og = MediaWatcher.ongoing();
        for (String o : og) b.append("  ").append(o).append('\n');
        if (og.isEmpty()) b.append("  none\n");
        b.append("\nShowing: ").append(lastPkg == null ? "nothing" : lastPkg).append('\n');
        String pref = player.preferredPackage();
        b.append("Your music app: ").append(pref == null ? "not chosen" : pref);
        return b.toString();
    }
}
