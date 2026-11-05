/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Drift Music's player. Plays files exactly as they are (Android's own decoder, no effects, no
 * volume changes), keeps going in the background, and publishes a media session so the home
 * card, the steering-wheel buttons and other apps all see and control it.
 * When the car starts it loads the last song and position, paused.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    public interface Listener { void onMusicState(); }

    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static volatile MusicService instance;
    private static final List<Listener> listeners = new ArrayList<>();

    public static MusicService get() { return instance; }

    public static void start(Context c) {
        try { c.startService(new Intent(c, MusicService.class)); } catch (Throwable ignored) { }
    }

    public static void addListener(Listener l) { synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); } }
    public static void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private MusicLibrary lib;
    private MusicStore store;
    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager audio;
    private SharedPreferences prefs;

    private final List<MusicLibrary.Song> queue = new ArrayList<>();
    private final List<Integer> history = new ArrayList<>();
    private String queueName = "";
    private int index = -1, repeat = REPEAT_ALL, errors;
    private boolean shuffle, prepared, wantPlay, focusLostTransient, foreground, ducked;
    private int gen; // bumps on every load, so a stale error callback can't skip a song the user just picked
    private long pendingSeek = -1;
    private Bitmap art;
    private String artFor;

    // ------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        lib = MusicLibrary.get(this);
        store = MusicStore.get(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = getSharedPreferences("drift_music", MODE_PRIVATE);
        session = new MediaSession(this, "Drift Music");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        }, main);
        Intent open = new Intent(this, Launcher.class).setAction(Launcher.ACTION_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT));
        new Thread(new Runnable() { @Override public void run() { restore(); } }, "drift-restore").start();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        saveState();
        main.removeCallbacks(saver);
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; }
        try { audio.abandonAudioFocus(this); } catch (Throwable ignored) { }
        session.setActive(false);
        session.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ public controls

    public MediaSession.Token token() { return session.getSessionToken(); }
    public boolean hasSong() { return index >= 0 && index < queue.size(); }

    /** Has a song and its file is still there (a USB stick may have been pulled out). */
    public boolean songAvailable() {
        MusicLibrary.Song s = current();
        return s != null && new java.io.File(s.path).exists();
    }

    /** Storage changed (USB stick in or out): stop cleanly if the current song's file is gone. */
    public void storageChanged() {
        if (hasSong() && !songAvailable()) {
            wantPlay = false;
            if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; prepared = false; }
            session.setActive(false);
            updateState();
            goForeground(false);
            notifyState();
        }
    }

    /**
     * Another source is playing (Bluetooth, radio, another app) and Drift Music is paused: let go of the
     * media keys so the steering-wheel and card buttons reach that source instead of waking Drift Music.
     * Pressing play in Drift Music takes them back.
     */
    public void yieldMediaKeys() { if (!isPlaying() && session.isActive()) session.setActive(false); }
    public MusicLibrary.Song current() { return hasSong() ? queue.get(index) : null; }
    public List<MusicLibrary.Song> queue() { return queue; }
    public int index() { return index; }
    public String queueName() { return queueName; }
    public boolean isPlaying() { return mp != null && prepared && mp.isPlaying(); }
    public boolean shuffle() { return shuffle; }
    public int repeat() { return repeat; }
    public Bitmap art() { return art; }

    public long position() {
        if (mp != null && prepared) try { return mp.getCurrentPosition(); } catch (Throwable ignored) { }
        return Math.max(0, pendingSeek);
    }

    public long duration() {
        if (mp != null && prepared) try { int d = mp.getDuration(); if (d > 0) return d; } catch (Throwable ignored) { }
        MusicLibrary.Song s = current();
        return s == null ? 0 : s.durationMs;
    }

    /** Song after the current one (null when shuffling or at the end without repeat). */
    public MusicLibrary.Song upNext() {
        if (!hasSong() || shuffle) return null;
        if (repeat == REPEAT_ONE) return current();
        int n = index + 1;
        if (n >= queue.size()) { if (repeat == REPEAT_ALL && !queue.isEmpty()) n = 0; else return null; }
        return queue.get(n);
    }

    /** Play a list starting at a song. */
    public void playList(List<MusicLibrary.Song> list, int start, String name, boolean shuffled) {
        if (list == null || list.isEmpty()) return;
        queue.clear();
        queue.addAll(list);
        history.clear();
        queueName = name == null ? "" : name;
        shuffle = shuffled;
        index = Math.max(0, Math.min(list.size() - 1, start));
        errors = 0;
        saveQueue();
        load(true, 0);
    }

    public void toggle() { if (isPlaying()) pause(); else play(); }

    public void play() {
        if (!hasSong()) return;
        wantPlay = true;
        session.setActive(true); // the user chose Drift: steering-wheel keys come here now
        if (mp == null || !prepared) { if (mp == null) load(true, Math.max(0, pendingSeek)); return; }
        if (!requestFocus()) return;
        ducked = false;
        try { mp.setVolume(1f, 1f); mp.start(); } catch (Throwable ignored) { }
        updateState();
        goForeground(true);
    }

    public void pause() {
        wantPlay = false;
        if (mp != null && prepared) try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
        updateState();
        goForeground(false);
        saveState();
    }

    public void next() {
        if (queue.isEmpty()) return;
        boolean play = wantPlay || isPlaying();
        if (shuffle) {
            history.add(index);
            int n = index;
            if (queue.size() > 1) while (n == index) n = rnd.nextInt(queue.size());
            index = n;
        } else {
            index++;
            if (index >= queue.size()) index = 0; // the button always moves on; repeat-off only stops at the natural end
        }
        load(play, 0);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        boolean play = wantPlay || isPlaying();
        if (position() > 3000) { seekTo(0); return; } // like every player: first press restarts the song
        if (shuffle && !history.isEmpty()) index = history.remove(history.size() - 1);
        else { index--; if (index < 0) index = queue.size() - 1; }
        load(play, 0);
    }

    public void seekTo(long ms) {
        if (mp != null && prepared) { try { mp.seekTo((int) Math.max(0, ms)); } catch (Throwable ignored) { } }
        else pendingSeek = ms;
        updateState();
    }

    public void setShuffle(boolean on) { shuffle = on; history.clear(); saveState(); notifyState(); }

    public void cycleRepeat() { repeat = (repeat + 1) % 3; saveState(); notifyState(); }

    // ------------------------------------------------------------ player

    private void load(boolean play, long seek) {
        wantPlay = play;
        gen++;
        prepared = false;
        pendingSeek = seek;
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; }
        MusicLibrary.Song s = current();
        if (s == null) { updateState(); return; }
        if (!s.tagged) {
            List<MusicLibrary.Song> one = new ArrayList<>(); one.add(s);
            lib.readTags(one); // on the library's I/O thread
            main.postDelayed(new Runnable() { @Override public void run() { publishMeta(); notifyState(); } }, 700);
        }
        loadArt(s);
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try { mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK); } catch (Throwable ignored) { }
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(s.path);
            mp.prepareAsync();
        } catch (Throwable t) {
            onError(mp, 0, 0);
            return;
        }
        if (play) { store.played(s.path); session.setActive(true); }
        publishMeta();
        updateState();
        saveState();
    }

    @Override public void onPrepared(MediaPlayer p) {
        if (p != mp) return;
        prepared = true;
        errors = 0;
        if (pendingSeek > 0) try { mp.seekTo((int) pendingSeek); } catch (Throwable ignored) { }
        pendingSeek = -1;
        if (ducked) try { mp.setVolume(0.3f, 0.3f); } catch (Throwable ignored) { }
        publishMeta();
        if (wantPlay && requestFocus()) { try { mp.start(); } catch (Throwable ignored) { } goForeground(true); }
        updateState();
    }

    @Override public void onCompletion(MediaPlayer p) {
        if (p != mp) return;
        if (repeat == REPEAT_ONE) { seekTo(0); try { mp.start(); } catch (Throwable ignored) { } return; }
        if (!shuffle && index + 1 >= queue.size() && repeat == REPEAT_OFF) {
            wantPlay = false; updateState(); goForeground(false); return; // end of the list
        }
        wantPlay = true;
        next();
    }

    @Override public boolean onError(MediaPlayer p, int what, int extra) {
        // a missing or unreadable file (e.g. the stick was unplugged or isn't mounted yet)
        prepared = false;
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; } // play() will reopen it
        if (!wantPlay) { updateState(); return true; } // paused: keep this song and position, try again on play
        if (++errors >= Math.min(5, Math.max(1, queue.size()))) { wantPlay = false; updateState(); goForeground(false); return true; }
        final int my = gen;
        main.postDelayed(new Runnable() { @Override public void run() { if (my == gen) { wantPlay = true; next(); } } }, 400);
        return true;
    }

    private boolean requestFocus() {
        try {
            return audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable t) { return true; }
    }

    @Override public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS: // another music app took over
                focusLostTransient = false; ducked = false; pause(); break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: // a call or a navigation prompt
                if (isPlaying() || (wantPlay && !prepared)) {
                    focusLostTransient = true;
                    if (mp != null && prepared) try { mp.pause(); } catch (Throwable ignored) { }
                    updateState();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                if (mp != null) try { mp.setVolume(0.3f, 0.3f); } catch (Throwable ignored) { }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false;
                if (mp != null) try { mp.setVolume(1f, 1f); } catch (Throwable ignored) { }
                if (focusLostTransient && wantPlay && mp != null && prepared) { try { mp.start(); } catch (Throwable ignored) { } updateState(); }
                focusLostTransient = false;
                break;
        }
    }

    // ------------------------------------------------------------ session + notification

    private void loadArt(final MusicLibrary.Song s) {
        if (s.path.equals(artFor)) return;
        artFor = s.path;
        art = null;
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap b = MusicLibrary.cover(s.path, 300);
                main.post(new Runnable() {
                    @Override public void run() { if (s.path.equals(artFor)) { art = b; publishMeta(); notifyState(); } }
                });
            }
        }, "drift-art").start();
    }

    private void publishMeta() {
        MusicLibrary.Song s = current();
        if (s == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist == null ? "" : s.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
        if (foreground) goForeground(true);
    }

    private void updateState() {
        boolean playing = isPlaying() || (wantPlay && !prepared);
        int st = !hasSong() ? PlaybackState.STATE_NONE : !prepared && wantPlay ? PlaybackState.STATE_BUFFERING
                : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(st, position(), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        main.removeCallbacks(saver);
        if (isPlaying()) main.postDelayed(saver, 10000);
        notifyState();
    }

    private void goForeground(boolean on) {
        try {
            if (on) {
                MusicLibrary.Song s = current();
                Intent open = new Intent(this, Launcher.class).setAction(Launcher.ACTION_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Notification n = new Notification.Builder(this)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(s == null ? "Drift Music" : s.title)
                        .setContentText(s == null || s.artist == null ? "" : s.artist)
                        .setLargeIcon(art)
                        .setContentIntent(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT))
                        .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                        .setOngoing(isPlaying())
                        .setShowWhen(false)
                        .build();
                startForeground(42, n);
                foreground = true;
            } else if (foreground) {
                stopForeground(false);
                foreground = false;
            }
        } catch (Throwable ignored) { }
    }

    private void notifyState() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) try { l.onMusicState(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ remembering where we were

    private final Runnable saver = new Runnable() {
        @Override public void run() { saveState(); if (isPlaying()) main.postDelayed(this, 10000); }
    };

    private File queueFile() { return new File(getFilesDir(), "music_queue.json"); }

    private void saveQueue() {
        try {
            JSONArray a = new JSONArray();
            for (MusicLibrary.Song s : queue) a.put(s.path);
            FileOutputStream out = new FileOutputStream(queueFile());
            out.write(a.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    private void saveState() {
        prefs.edit().putInt("index", index).putLong("pos", position()).putBoolean("shuffle", shuffle)
                .putInt("repeat", repeat).putString("name", queueName).apply();
    }

    /** Back to the last song and position, paused (the user presses play). Runs on a worker thread. */
    private void restore() {
        final List<MusicLibrary.Song> q = new ArrayList<>();
        try {
            File f = queueFile();
            if (!f.exists()) return;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            JSONArray a = new JSONArray(new String(b, 0, n, "UTF-8"));
            for (int i = 0; i < a.length(); i++) q.add(lib.song(a.getString(i)));
        } catch (Throwable ignored) { }
        main.post(new Runnable() { @Override public void run() { applyRestore(q); } });
    }

    private void applyRestore(List<MusicLibrary.Song> q) {
        if (!queue.isEmpty()) return; // the user already started something
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_ALL);
        queueName = prefs.getString("name", "");
        queue.addAll(q);
        index = Math.min(prefs.getInt("index", -1), queue.size() - 1);
        if (!hasSong()) return;
        long pos = prefs.getLong("pos", 0);
        // the stick may not be mounted yet right after start: show the song now, open the file on play
        MusicLibrary.Song s = current();
        pendingSeek = pos;
        loadArt(s);
        // own the steering-wheel keys from the start only when Drift Music is the chosen player
        if (getPackageName().equals(getSharedPreferences("drift", MODE_PRIVATE).getString("player", null))) session.setActive(true);
        publishMeta();
        updateState();
        if (s.exists()) { boolean act = session.isActive(); load(false, pos); if (!act) session.setActive(false); }
    }
}
