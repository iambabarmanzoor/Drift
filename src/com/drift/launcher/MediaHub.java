/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
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
 * What the home screen's music card shows, and where its buttons go.
 *
 * Sources, all event-driven:
 *   - Media sessions (Android's standard "now playing" API; needs Notification access)
 *   - Drift Music's own session
 *   - The phone over Bluetooth, through Android's Bluetooth music service (see BluetoothMusic)
 *   - Media notifications, for players that only post a notification
 *   - Documented "track changed" broadcasts of older players (stock music, Poweramp, Spotify)
 *   - Android 8+: Android's audio playback events, to know when media audio starts/stops
 *
 * Which one is shown (first match wins):
 *   1. a session that is playing (the one that started most recently)
 *   2. the phone over Bluetooth while it streams
 *   3. a notification-only player while media audio plays
 *   4. an older player's broadcast that says it is playing
 *   5. media audio that no app reports (e.g. Bluetooth on units whose factory app shares nothing)
 *   6. otherwise, paused: what was shown last, else your music app, else any session with a song
 *
 * Buttons only ever go to the shown source, through that source's own controls: its session, its
 * notification buttons, or a media button sent to that app alone. Never a generic key press,
 * which Android would hand to whichever app played last.
 */
public class MediaHub {
    /** Supplied by the launcher. */
    public interface Player {
        String preferredPackage();
        void launchPreferred();
        /** A song's length and cover need storage access: ask for it (once). */
        void needStorage();
    }

    /** Knows whether media audio is playing (Android 8+: from Android's playback events). */
    interface MediaPlayback {
        interface Listener { void onMediaPlaybackChanged(); }
        boolean active();
        void stop();
        String describe();
    }

    /** What a tap on the card should do. */
    public static final int OPEN_DONE = 0, OPEN_DRIFT_MUSIC = 1, OPEN_UNHANDLED = 2;

    private static final int NONE = 0, SESSION = 1, BT_TRACK = 2, NOTE = 3, BROADCAST = 4, OTHER = 5;

    private final Context ctx;
    private final MediaCard card;
    private final Player player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final BluetoothMusic bt;
    private MediaSessionManager msm;
    private MediaPlayback playback;   // Android 8+
    private boolean started;

    // what is shown
    private int kind = NONE;
    private MediaController controller;          // kind SESSION
    private MediaWatcher.Entry note;             // kind NOTE
    private String shownPkg;                     // package behind the card (null if unknown)
    private String reason = "nothing yet";       // diagnostics
    private boolean cardPlaying;

    // memory across refreshes
    private MediaSession.Token lastToken;        // the session shown last (kept while paused)
    private final HashMap<MediaSession.Token, Long> playingSince = new HashMap<>();
    private final HashMap<String, String> labels = new HashMap<>();
    private Bitmap lastSmall;
    private String lastArtKey;

    // older players' broadcasts
    private String bcPkg, bcTitle, bcArtist, bcAlbum, bcSource, lastBroadcast = "none";
    private boolean bcPlaying;

    // the music app last opened from Drift (only used to name/open an unreported source)
    private ComponentName openedFromDrift;

    // resuming your music app when nothing is playing
    private MediaBrowser resumeBrowser;

    public MediaHub(Context c, MediaCard card, Player player) {
        ctx = c;
        this.card = card;
        this.player = player;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        bt = new BluetoothMusic(c, new BluetoothMusic.Listener() {
            @Override public void onBluetoothMusicChanged() { requestRefresh(); }
        });
    }

    // ------------------------------------------------------------ lifecycle

    public static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        for (String part : s.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && c.getPackageName().equals(cn.getPackageName())) return true;
        }
        return false;
    }

    public static String status(Context c) {
        if (!hasAccess(c)) return "off";
        return MediaWatcher.connected ? "connected" : "not connected";
    }

    public void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        for (String a : BROADCASTS) f.addAction(a);
        try { ctx.registerReceiver(broadcasts, f); } catch (Exception ignored) { }
        bt.start();
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                playback = PlaybackWatch.create(audio, main, new MediaPlayback.Listener() {
                    @Override public void onMediaPlaybackChanged() { requestRefresh(); }
                });
            } catch (Throwable t) { playback = null; }
        }
        boolean access = hasAccess(ctx);
        card.setNeedsAccess(!access);
        MediaWatcher.setListener(notesChanged);
        MusicService.addListener(ownChanged);
        if (access) {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            try {
                m.addOnActiveSessionsChangedListener(sessions, new ComponentName(ctx, MediaWatcher.class), main);
                msm = m;
            } catch (Exception e) { msm = null; }
            if (msm == null) card.setNeedsAccess(true, "This unit blocked music access");
            if (!MediaWatcher.connected) MediaWatcher.rebind(ctx);
            main.postDelayed(checkLink, 4000);
        }
        outputWas = audio.isMusicActive();
        main.postDelayed(outputWatch, 2000);
        refresh();
        card.resumeTicking();
    }

    public void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(checkLink);
        main.removeCallbacks(outputWatch);
        try { ctx.unregisterReceiver(broadcasts); } catch (Exception ignored) { }
        bt.stop();
        if (playback != null) { playback.stop(); playback = null; }
        MediaWatcher.setListener(null);
        MusicService.removeListener(ownChanged);
        if (msm != null) { try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { } msm = null; }
        setController(null);
        kind = NONE;
        main.removeCallbacks(refreshTask);
        closeResumeBrowser();
    }

    /** Many events arrive in bursts (a session changes state and metadata at once): one refresh per burst. */
    private boolean refreshQueued;
    private final Runnable refreshTask = new Runnable() { @Override public void run() { refreshQueued = false; refresh(); } };
    private void requestRefresh() {
        if (!started || refreshQueued) return;
        refreshQueued = true;
        main.postDelayed(refreshTask, 60);
    }

    /** Re-read everything now (e.g. after a permission was granted, or a USB stick came or went). */
    public void refreshNow() { if (started) refresh(); }

    /** Access is switched on but Android never connected Drift's listener: say so on the card. */
    private final Runnable checkLink = new Runnable() {
        @Override public void run() {
            if (!started || !hasAccess(ctx)) return;
            if (!MediaWatcher.connected) { MediaWatcher.rebind(ctx); card.setNeedsAccess(true, "Access is on but not connected. Tap to fix"); }
            else if (msm != null) card.setNeedsAccess(false);
        }
    };

    // ------------------------------------------------------------ events

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { requestRefresh(); }
            };

    private final Runnable notesChanged = new Runnable() { @Override public void run() { requestRefresh(); } };

    /** Drift Music changed song or state; bursts (seeking, buffering) are coalesced into one refresh. */
    private final MusicService.Listener ownChanged = new MusicService.Listener() {
        private boolean queued;
        @Override public void onMusicState() {
            if (queued || !started) return;
            queued = true;
            main.postDelayed(new Runnable() { @Override public void run() { queued = false; requestRefresh(); } }, 150);
        }
    };

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { requestRefresh(); }
        @Override public void onMetadataChanged(MediaMetadata m) { requestRefresh(); }
        @Override public void onSessionDestroyed() { setController(null); requestRefresh(); }
    };

    /** The documented "track changed" broadcasts of older players. */
    private static final String[] BROADCASTS = {
        "com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.playbackcomplete",
        "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
        "com.maxmpz.audioplayer.TRACK_CHANGED", "com.maxmpz.audioplayer.STATUS_CHANGED",
        "com.htc.music.metachanged", "com.miui.player.metachanged", "com.sec.android.app.music.metachanged",
        "com.andrew.apollo.metachanged", "com.amazon.mp3.metachanged", "com.lge.music.metachanged",
    };

    private final BroadcastReceiver broadcasts = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String act = i.getAction() == null ? "" : i.getAction();
            lastBroadcast = act + " at " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            if (isInitialStickyBroadcast()) return; // an old track replayed on register, not what's playing now
            String pkg = act.substring(0, Math.max(0, act.lastIndexOf('.')));
            if (act.startsWith("com.maxmpz.audioplayer")) {
                pkg = "com.maxmpz.audioplayer";
                Bundle t = i.getBundleExtra("track");
                if (t != null) { bcTitle = t.getString("title"); bcArtist = t.getString("artist"); bcAlbum = t.getString("album"); }
                if (i.hasExtra("paused")) bcPlaying = !i.getBooleanExtra("paused", true);
            } else {
                if (act.startsWith("com.spotify")) pkg = "com.spotify.music";
                if (i.hasExtra("track")) { bcTitle = i.getStringExtra("track"); bcArtist = i.getStringExtra("artist"); bcAlbum = i.getStringExtra("album"); }
                if (i.hasExtra("playing")) bcPlaying = i.getBooleanExtra("playing", false);
                else if (i.hasExtra("isplaying")) bcPlaying = i.getBooleanExtra("isplaying", false);
            }
            // "com.android.music.*" is used by many players, not just the stock one: then we don't know
            // which app sent it, so the card shows the song but sends its buttons nowhere
            bcPkg = !act.startsWith("com.android.music.") && installed(pkg) ? pkg : null;
            bcSource = bcPkg == null ? "" : appLabel(bcPkg);
            requestRefresh();
        }
    };

    // ------------------------------------------------------------ choosing what to show

    private void refresh() {
        if (!started) return;
        if (msm != null && MediaWatcher.connected) card.setNeedsAccess(false);
        long now = SystemClock.elapsedRealtime();

        List<MediaController> list = sessionsNow();
        List<MediaWatcher.Entry> notes = MediaWatcher.entries();
        MusicService own = MusicService.get();
        boolean ownPlaying = own != null && own.isPlaying();

        // remember when each session started playing, so the newest one wins
        java.util.HashSet<MediaSession.Token> seen = new java.util.HashSet<>();
        for (MediaController m : list) {
            seen.add(m.getSessionToken());
            if (isPlaying(m)) { if (!playingSince.containsKey(m.getSessionToken())) playingSince.put(m.getSessionToken(), now); }
            else playingSince.remove(m.getSessionToken());
        }
        playingSince.keySet().retainAll(seen);

        // 1. a playing session, newest first
        MediaController pick = null;
        long best = -1;
        for (MediaController m : list) {
            Long since = playingSince.get(m.getSessionToken());
            if (since != null && since > best) { best = since; pick = m; }
        }
        if (pick != null) { showSession(pick, "playing"); return; }

        // 2. the phone over Bluetooth while it streams (its session may report "playing" a moment late)
        if (bt.streaming() && !ownPlaying) {
            MediaController b = bt.controller();
            if (b != null) { showSession(b, "Bluetooth streaming"); return; }
            if (bt.trackMetadata() != null) { showBluetoothTrack(true); return; }
        }

        boolean media = mediaAudioActive(ownPlaying);

        // 3. a notification-only player that is playing: its own play/pause button says so
        //    (a "Pause" button means playing); if its buttons don't tell, media audio decides
        MediaWatcher.Entry n = noteOnly(notes);
        Boolean noteSays = n == null ? null : noteSaysPlaying(n);
        if (n != null && (Boolean.TRUE.equals(noteSays) || (noteSays == null && media))) { showNote(n, true, "notification player, playing"); return; }

        // 4. an older player that says it is playing (only while media audio really plays: these
        //    broadcasts stop when an app dies, so "playing" alone can go stale)
        if (!media) bcPlaying = false;
        if (bcPlaying && bcTitle != null) { showBroadcast(true); return; }

        // 5. a phone is connected over Bluetooth: audio no app reports is the phone's, so show the
        //    phone's song with the state its session reports (never a guessed "playing")
        if (media && bt.phoneConnected()) {
            for (MediaController m : list) if (bt.isBluetoothSession(m) && hasSong(m)) { showSession(m, "Bluetooth, audio on"); return; }
            if (bt.trackMetadata() != null) { showBluetoothTrack(bt.streaming()); return; }
        }

        // 6. media audio nobody reports
        if (media) { showOther(); return; }

        // 7. paused: keep what was shown, else your music app, else any session with a song
        if (kind == BT_TRACK && bt.phoneConnected() && bt.trackMetadata() != null) { showBluetoothTrack(false); return; }
        if (kind == NOTE && n != null) { showNote(n, false, "notification player, paused"); return; }
        if (kind == BROADCAST && bcTitle != null) { showBroadcast(false); return; }
        if (lastToken != null) for (MediaController m : list) if (m.getSessionToken().equals(lastToken) && hasSong(m)) { showSession(m, "paused, shown last"); return; }
        String pref = player.preferredPackage();
        if (pref != null) for (MediaController m : list) if (pref.equals(m.getPackageName()) && hasSong(m)) { showSession(m, "your music app"); return; }
        if (pref != null && n != null && pref.equals(n.pkg)) { showNote(n, false, "your music app's notification"); return; }
        for (MediaController m : list) if (hasSong(m)) { showSession(m, "has a song"); return; }
        if (n != null) { showNote(n, false, "notification"); return; }
        showNothing();
    }

    /** Active sessions + Drift Music + the Bluetooth service + sessions inside notifications. */
    private List<MediaController> sessionsNow() {
        List<MediaController> list = new ArrayList<>();
        if (msm != null) { try { list.addAll(msm.getActiveSessions(new ComponentName(ctx, MediaWatcher.class))); } catch (Exception ignored) { } }
        MusicService own = MusicService.get();
        if (own != null && own.songAvailable() && (own.isPlaying() || ctx.getPackageName().equals(player.preferredPackage())))
            addOnce(list, own.token());
        MediaController b = bt.controller();
        if (b != null) addOnce(list, b.getSessionToken());
        for (MediaWatcher.Entry e : MediaWatcher.entries()) if (e.token != null) addOnce(list, e.token);
        for (int i = list.size() - 1; i >= 0; i--) {
            MediaController m = list.get(i);
            // the Bluetooth service keeps its last song after the phone leaves: hide it then
            if (m.getPackageName() == null || (bt.isBluetoothSession(m) && !bt.phoneConnected())) list.remove(i);
        }
        return list;
    }

    private void addOnce(List<MediaController> list, MediaSession.Token t) {
        for (MediaController m : list) if (m.getSessionToken().equals(t)) return;
        try { list.add(new MediaController(ctx, t)); } catch (Throwable ignored) { }
    }

    /** Media audio is playing that isn't Drift Music (Android 8+: from Android's playback events). */
    private boolean mediaAudioActive(boolean ownPlaying) {
        if (ownPlaying) return false;
        // Android's playback events cover apps; audio from a separate Bluetooth chip (some units) only
        // shows up at the audio output, so both count
        return (playback != null && playback.active()) || audio.isMusicActive();
    }

    /** A player that only posts a notification (no session), e.g. some units' built-in players. */
    private static MediaWatcher.Entry noteOnly(List<MediaWatcher.Entry> notes) {
        MediaWatcher.Entry pick = null;
        for (MediaWatcher.Entry e : notes) {
            if (e.token != null || (e.title == null && e.text == null)) continue;
            if (pick == null || e.when > pick.when) pick = e;
        }
        return pick;
    }

    /**
     * TransportControls.prepare() (public API since Android 7, API 24). Drift compiles against the
     * Android 6 library, so it is called by name; on Android 6 there is nothing to call.
     */
    private static void prepare(MediaController.TransportControls t) {
        if (Build.VERSION.SDK_INT < 24) return;
        try { MediaController.TransportControls.class.getMethod("prepare").invoke(t); } catch (Throwable ignored) { }
    }

    /**
     * A notification-only player's state, read from its own play/pause button: players show "Pause"
     * while playing and "Play" while paused. Null when its buttons don't say.
     */
    private static Boolean noteSaysPlaying(MediaWatcher.Entry e) {
        if (e.actionTitles == null) return null;
        boolean play = false;
        for (String t0 : e.actionTitles) {
            String t = t0.toLowerCase(Locale.ROOT);
            if (t.contains("pause") || t.contains("暂停")) return Boolean.TRUE;
            if (t.contains("play") || t.contains("播放")) play = true;
        }
        return play ? Boolean.FALSE : null;
    }

    private static boolean isPlaying(MediaController m) {
        try {
            PlaybackState s = m.getPlaybackState();
            return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        } catch (Throwable t) { return false; }
    }

    private static boolean hasSong(MediaController m) {
        try {
            MediaMetadata md = m.getMetadata();
            return md != null && (md.getString(MediaMetadata.METADATA_KEY_TITLE) != null || md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null);
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------ showing

    private void setController(MediaController m) {
        if (controller != null && (m == null || !controller.getSessionToken().equals(m.getSessionToken()))) {
            try { controller.unregisterCallback(cb); } catch (Throwable ignored) { }
            controller = null;
        }
        if (m != null && controller == null) { controller = m; controller.registerCallback(cb, main); }
    }

    private void showSession(MediaController m, String why) {
        setController(m);
        kind = SESSION;
        note = null;
        lastToken = m.getSessionToken();
        shownPkg = m.getPackageName();
        boolean isBt = bt.isBluetoothSession(m);
        reason = (isBt ? "Bluetooth session, " : "session, ") + why;
        MediaMetadata md = m.getMetadata();
        PlaybackState st = m.getPlaybackState();
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
        if (title == null) { // some players only fill their notification
            for (MediaWatcher.Entry e : MediaWatcher.entries()) if (e.pkg.equals(shownPkg)) { title = e.title; artist = e.text; if (art == null) art = e.art; break; }
        }
        String source = isBt ? "Bluetooth" : appLabel(shownPkg);
        if (title == null) { title = isBt ? "Bluetooth audio" : source; if (artist == null) artist = "Song details not shared"; }
        boolean playing = isPlaying(m);
        long pos = st == null ? -1 : st.getPosition();
        float speed = st == null || st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
        long at = st == null ? SystemClock.elapsedRealtime() : st.getLastPositionUpdateTime();
        cardPlaying = playing;
        card.setSeekable(st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
        card.update(title, artist, album, source, small(shownPkg + title + artist, art), playing, pos, dur, speed, at);
    }

    /** The phone's song from Android's Bluetooth broadcasts (the service itself wasn't reachable). */
    private void showBluetoothTrack(boolean playing) {
        setController(null);
        kind = BT_TRACK;
        note = null;
        lastToken = null;
        ComponentName ms = bt.musicScreen();
        shownPkg = ms == null ? null : ms.getPackageName();
        reason = "Bluetooth track broadcast";
        MediaMetadata md = bt.trackMetadata();
        PlaybackState st = bt.trackState();
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE), artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        cardPlaying = playing;
        card.setSeekable(false);
        card.update(title == null ? "Bluetooth audio" : title, artist, md.getString(MediaMetadata.METADATA_KEY_ALBUM), "Bluetooth", null, playing,
                st == null ? -1 : st.getPosition(), dur, 1f, st == null ? SystemClock.elapsedRealtime() : st.getLastPositionUpdateTime());
    }

    private void showBroadcast(boolean playing) {
        setController(null);
        kind = BROADCAST;
        note = null;
        lastToken = null;
        shownPkg = bcPkg;
        reason = "broadcast from " + (bcPkg == null ? "an older player" : bcPkg);
        cardPlaying = playing;
        card.setSeekable(false);
        card.update(bcTitle, bcArtist, bcAlbum, bcSource, null, playing, -1, 0, 1f, 0);
    }

    /** Media audio that no app reports. Name the likely app only if one is known; never guess a song. */
    private void showOther() {
        setController(null);
        kind = OTHER;
        note = null;
        lastToken = null;
        ComponentName target = otherTarget();
        shownPkg = target == null ? null : target.getPackageName();
        reason = "media audio no app reports";
        cardPlaying = true;
        card.setSeekable(false);
        if (target != null) card.update("Playing in " + appLabel(target.getPackageName()), "Tap to open it", null, appLabel(target.getPackageName()), null, true, -1, 0, 1f, 0);
        else card.update("Playing from another app", "Bluetooth, radio or another source", null, "", null, true, -1, 0, 1f, 0);
    }

    /** Which app to open for unreported audio: the music app you just opened from Drift, else the unit's Bluetooth music screen while a phone streams. */
    private ComponentName otherTarget() {
        if (openedFromDrift != null && installed(openedFromDrift.getPackageName())) return openedFromDrift;
        if (bt.phoneConnected()) return bt.musicScreen();
        return null;
    }

    private void showNothing() {
        setController(null);
        kind = NONE;
        note = null;
        lastToken = null;
        shownPkg = null;
        reason = "nothing playing or paused";
        cardPlaying = false;
        card.setSeekable(false);
        card.update(null, null, null, "", null, false, -1, 0, 1f, 0);
    }

    // ---- notification-only players: we keep the song's time ourselves

    private String clockKey;
    private long clockPos, clockAt;
    private boolean clockRunning;

    private final TrackInfo.Callback infoReady = new TrackInfo.Callback() {
        @Override public void onInfo(String key, TrackInfo.Info info) { if (kind == NOTE) requestRefresh(); }
    };

    /**
     * A player that only posts "Song - Artist" in a notification. We split the name, look the song up
     * in the media library for its length and cover, and run the time from when the notification for
     * this song appeared, counting only while media audio plays.
     */
    private void showNote(MediaWatcher.Entry e, boolean playing, String why) {
        setController(null);
        kind = NOTE;
        note = e;
        lastToken = null;
        shownPkg = e.pkg;
        reason = why;
        String t = e.title, a = e.text;
        if (t != null && (a == null || a.trim().isEmpty())) {
            int k = t.indexOf(" - ");
            if (k > 0) { a = t.substring(k + 3).trim(); t = t.substring(0, k).trim(); }
        }
        if (t == null) t = appLabel(e.pkg);
        TrackInfo.Info info = TrackInfo.get(ctx, t, a, infoReady);
        if (info == null && !TrackInfo.allowed(ctx)) player.needStorage();
        long dur = info == null ? 0 : info.durationMs;
        long now = SystemClock.elapsedRealtime();
        String key = e.pkg + "|" + e.title;
        if (!key.equals(clockKey)) {
            clockKey = key;
            long since = System.currentTimeMillis() - e.when; // players re-post the notification when the song changes
            clockPos = since > 0 && (dur <= 0 || since < dur) ? since : 0;
            clockAt = now;
        } else if (clockRunning) { clockPos += now - clockAt; clockAt = now; }
        else clockAt = now;
        clockRunning = playing;
        if (dur > 0 && clockPos > dur) clockPos = dur;
        cardPlaying = playing;
        card.setSeekable(false); // such players don't accept position changes
        card.update(t, a, null, appLabel(e.pkg), small(key, info != null && info.art != null ? info.art : e.art), playing, clockPos, dur, 1f, now);
    }

    /**
     * Audio that doesn't come from an app (a unit's separate Bluetooth chip) and players on Android 6-7
     * give no event when they start or stop; the audio output is the only signal. Checked every 2 s,
     * only while the home screen is showing. A change triggers a normal refresh.
     */
    private boolean outputWas;
    private final Runnable outputWatch = new Runnable() {
        @Override public void run() {
            if (!started) return;
            boolean now = audio.isMusicActive();
            if (now != outputWas) { outputWas = now; requestRefresh(); }
            main.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------ buttons

    public void playPause() {
        switch (kind) {
            case SESSION: {
                if (controller == null) return;
                if (isPlaying(controller)) { controller.getTransportControls().pause(); return; }
                // Another app's player may be asleep (e.g. right after the car starts); resume it the way
                // Android does, through its media browser service, which wakes it first. Drift Music and
                // the phone over Bluetooth are always awake: plain play.
                String pkg = controller.getPackageName();
                if (bt.isBluetoothSession(controller)) {
                    // Android plays the phone's audio only while its Bluetooth app holds audio focus.
                    // Its music service takes focus on "prepare" (Android 9: onPrepare -> requestAudioFocus),
                    // so a media app sends prepare, then play. Play alone resumes the phone silently.
                    MediaController.TransportControls t = controller.getTransportControls();
                    prepare(t);
                    t.play();
                    return;
                }
                if (ctx.getPackageName().equals(pkg) || !resume(pkg, controller)) controller.getTransportControls().play();
                return;
            }
            case NOTE: if (!noteAction(note, A_PLAY)) mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
            case BROADCAST: mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
            case BT_TRACK: case OTHER: return; // no way to reach the source; doing nothing beats doing the wrong thing
            default: startPreferred();
        }
    }

    public void next() {
        switch (kind) {
            case SESSION: if (controller != null) controller.getTransportControls().skipToNext(); return;
            case NOTE: if (noteAction(note, A_NEXT)) restartClock(); else mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_NEXT); return;
            case BROADCAST: mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_NEXT); return;
            default:
        }
    }

    public void prev() {
        switch (kind) {
            case SESSION: if (controller != null) controller.getTransportControls().skipToPrevious(); return;
            case NOTE: if (noteAction(note, A_PREV)) restartClock(); else mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
            case BROADCAST: mediaButton(shownPkg, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
            default:
        }
    }

    /** Only offered when the session says it supports jumping (see showSession). */
    public void seekTo(long ms) {
        if (kind == SESSION && controller != null) controller.getTransportControls().seekTo(Math.max(0, ms));
    }

    private void restartClock() { clockPos = 0; clockAt = SystemClock.elapsedRealtime(); }

    private static final int A_PREV = 0, A_PLAY = 1, A_NEXT = 2;

    /** Press one of the player's own notification buttons (identified by title, else by the media layout). */
    private static boolean noteAction(MediaWatcher.Entry e, int which) {
        if (e == null || e.actions == null) return false;
        int n = e.actions.length, idx = -1;
        String[][] words = { { "prev", "back", "rewind", "上一" }, { "pause", "play", "播放", "暂停" }, { "next", "skip", "forward", "下一" } };
        for (int i = 0; i < n && idx < 0; i++) {
            String t = e.actionTitles[i].toLowerCase(Locale.ROOT);
            for (String w : words[which]) if (t.contains(w)) { idx = i; break; }
        }
        if (idx < 0 && e.compact != null && e.compact.length == 3) idx = e.compact[which];
        if (idx < 0 && n == 3) idx = which;
        if (idx < 0 || idx >= n || e.actions[idx] == null) return false;
        try { e.actions[idx].send(); return true; } catch (Exception ex) { return false; }
    }

    /**
     * Android's standard media button, sent to one app only, and only if that app declares that it
     * receives media buttons. Never broadcast to "whoever played last".
     */
    private boolean mediaButton(String pkg, int code) {
        if (pkg == null) return false;
        Intent probe = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg);
        List<ResolveInfo> rs = ctx.getPackageManager().queryBroadcastReceivers(probe, 0);
        if (rs == null || rs.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(new ComponentName(rs.get(0).activityInfo.packageName, rs.get(0).activityInfo.name))
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, action, code, 0));
            try { ctx.sendBroadcast(i); } catch (Exception ignored) { }
        }
        return true;
    }

    /**
     * Nothing to resume on the card: start your music app. Drift Music plays directly. Other apps are
     * resumed the way Android itself does it (Android 11 "playback resumption"): connect to the app's
     * media browser service, which wakes its player, then send play through its session. Apps without
     * one are simply opened.
     */
    private void startPreferred() {
        String pkg = player.preferredPackage();
        if (pkg == null || pkg.equals(ctx.getPackageName()) || !resume(pkg, null)) player.launchPreferred();
    }

    /**
     * Resume an app through its media browser service. If that fails, play through the session we
     * already have (if any), else open that same app: never some other app. False if it has no service.
     */
    private boolean resume(final String pkg, final MediaController fallback) {
        ComponentName svc = null;
        try {
            List<ResolveInfo> rs = ctx.getPackageManager().queryIntentServices(new Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
            if (rs != null && !rs.isEmpty()) svc = new ComponentName(pkg, rs.get(0).serviceInfo.name);
        } catch (Throwable ignored) { }
        if (svc == null) return false;
        closeResumeBrowser();
        final MediaBrowser[] holder = new MediaBrowser[1];
        final Runnable failed = new Runnable() {
            @Override public void run() {
                if (resumeBrowser != holder[0]) return; // a newer press took over
                closeResumeBrowser();
                if (fallback != null) { try { fallback.getTransportControls().play(); return; } catch (Throwable ignored) { } }
                Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) { try { ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { } }
            }
        };
        holder[0] = new MediaBrowser(ctx, svc, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (resumeBrowser != holder[0]) return;
                main.removeCallbacks(failed);
                try { new MediaController(ctx, holder[0].getSessionToken()).getTransportControls().play(); }
                catch (Throwable t) { failed.run(); return; }
                closeResumeBrowser(); // the app now plays as its own foreground service; no need to stay bound
            }
            @Override public void onConnectionFailed() { main.removeCallbacks(failed); failed.run(); }
        }, null);
        resumeBrowser = holder[0];
        try { resumeBrowser.connect(); } catch (Throwable t) { resumeBrowser = null; return false; }
        main.postDelayed(failed, 4000); // no answer from the app: fall back
        return true;
    }

    private void closeResumeBrowser() {
        if (resumeBrowser != null) { try { resumeBrowser.disconnect(); } catch (Throwable ignored) { } resumeBrowser = null; }
    }

    // ------------------------------------------------------------ tapping the card

    /** The music app opened from Drift: names/opens audio that no app reports. */
    public void externalOpened(ComponentName cn) { openedFromDrift = cn; requestRefresh(); }

    /**
     * Open what the card shows, the way that app wants to be opened: its session's or notification's
     * own screen, the Bluetooth music screen for the phone, else the app itself.
     */
    public int openCurrent() {
        if (ctx.getPackageName().equals(shownPkg)) return OPEN_DRIFT_MUSIC;
        boolean isBt = kind == BT_TRACK || (kind == SESSION && controller != null && bt.isBluetoothSession(controller));
        if (isBt || (kind == OTHER && otherTarget() != null)) {
            ComponentName c = isBt ? bt.musicScreen() : otherTarget();
            if (c != null && startComponent(c)) return OPEN_DONE;
        }
        if (kind == SESSION && controller != null && send(controller.getSessionActivity())) return OPEN_DONE;
        if (kind == NOTE && note != null && send(note.contentIntent)) return OPEN_DONE;
        if (shownPkg != null) {
            Intent li = ctx.getPackageManager().getLaunchIntentForPackage(shownPkg);
            if (li != null) { try { ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return OPEN_DONE; } catch (Throwable ignored) { } }
        }
        return OPEN_UNHANDLED;
    }

    private static boolean send(PendingIntent p) {
        if (p == null) return false;
        try { p.send(); return true; } catch (Throwable t) { return false; }
    }

    private boolean startComponent(ComponentName c) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Package behind the card (null if unknown). */
    public String playingPackage() { return shownPkg; }

    // ------------------------------------------------------------ helpers

    private Bitmap small(String key, Bitmap b) {
        if (key.equals(lastArtKey)) return lastSmall;
        lastArtKey = key;
        lastSmall = null;
        if (b == null) return null;
        int max = 300;
        if (b.getWidth() <= max && b.getHeight() <= max) return lastSmall = b;
        float sc = max / (float) Math.max(b.getWidth(), b.getHeight());
        try { lastSmall = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * sc)), Math.max(1, (int) (b.getHeight() * sc)), true); }
        catch (Throwable t) { lastSmall = null; }
        return lastSmall;
    }

    private boolean installed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try { ctx.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
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

    // ------------------------------------------------------------ diagnostics

    private String version() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("Drift ").append(version()).append('\n');
        b.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
         .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", ").append(Build.HARDWARE).append('\n');
        b.append("Notification access: ").append(hasAccess(ctx) ? "ON" : "OFF").append(", listener ").append(MediaWatcher.connected ? "connected" : "not connected").append('\n');
        b.append("Showing: ").append(shownPkg == null ? "-" : shownPkg).append(" (").append(reason).append(")\n");
        b.append("Media audio playing: ").append(playback != null ? (playback.active() ? "yes" : "no") + " (" + playback.describe() + ")"
                : (audio.isMusicActive() ? "yes" : "no") + " (output check; no playback events before Android 8)").append('\n');
        b.append("\nBluetooth\n").append(bt.diagnostics()).append('\n');
        b.append("\nMedia sessions:\n");
        int n = 0;
        for (MediaController m : sessionsNow()) {
            PlaybackState s = m.getPlaybackState();
            MediaMetadata md = m.getMetadata();
            String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            b.append("  ").append(m.getPackageName()).append("  state ").append(s == null ? "?" : String.valueOf(s.getState()))
             .append("  song: ").append(t == null ? "none" : t).append('\n');
            n++;
        }
        if (n == 0) b.append("  none\n");
        b.append("\nNow-playing notifications:\n");
        List<MediaWatcher.Entry> es = MediaWatcher.entries();
        for (MediaWatcher.Entry e : es) {
            b.append("  ").append(e.pkg).append(e.token != null ? "  (session)" : "  (no session)")
             .append("  ").append(e.title == null ? "" : e.title).append(e.text == null ? "" : " / " + e.text).append('\n');
            if (e.actions != null) {
                b.append("    buttons: ");
                for (int i = 0; i < e.actions.length; i++) b.append(i).append('=').append(e.actionTitles[i].isEmpty() ? "(icon)" : e.actionTitles[i]).append("  ");
                b.append('\n');
            }
        }
        if (es.isEmpty()) b.append("  none\n");
        b.append("\nLast player broadcast: ").append(lastBroadcast).append('\n');
        b.append("Song length lookup:\n  ").append(TrackInfo.status(ctx, null, null).replace("\n", "\n  ")).append('\n');
        b.append("\nOther ongoing notifications:\n");
        List<String> og = MediaWatcher.ongoing();
        for (String o : og) b.append("  ").append(o).append('\n');
        if (og.isEmpty()) b.append("  none\n");
        String pref = player.preferredPackage();
        b.append("\nYour music app: ").append(pref == null ? "not chosen" : pref);
        return b.toString();
    }
}
