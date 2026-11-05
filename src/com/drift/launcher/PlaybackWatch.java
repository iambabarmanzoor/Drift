/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Android 8+ (API 26): Android tells us whenever audio players start, pause or stop, and what kind
 * of sound each one plays. Event-driven, no polling. Only a player that is actually started and
 * playing media counts (music, podcasts, a phone over Bluetooth), so navigation prompts, alerts,
 * calls and paused players never look like "music".
 *
 * Only created through {@link #create} under an SDK check, so Android 6-7 never loads this class
 * (its parent class doesn't exist there).
 */
final class PlaybackWatch extends AudioManager.AudioPlaybackCallback implements MediaHub.MediaPlayback {
    private static final int PLAYER_STATE_STARTED = 2; // AudioPlaybackConfiguration.PLAYER_STATE_STARTED

    private final AudioManager audio;
    private final MediaHub.MediaPlayback.Listener listener;
    private final Method playerState;  // hidden getter; null if this Android won't let us read it
    private boolean active;

    static MediaHub.MediaPlayback create(AudioManager audio, Handler h, MediaHub.MediaPlayback.Listener l) {
        PlaybackWatch w = new PlaybackWatch(audio, l);
        audio.registerAudioPlaybackCallback(w, h);
        w.active = w.mediaIn(audio.getActivePlaybackConfigurations());
        return w;
    }

    private PlaybackWatch(AudioManager audio, MediaHub.MediaPlayback.Listener l) {
        this.audio = audio;
        this.listener = l;
        Method m = null;
        try { m = AudioPlaybackConfiguration.class.getMethod("getPlayerState"); } catch (Throwable ignored) { }
        playerState = m;
    }

    @Override public void stop() { try { audio.unregisterAudioPlaybackCallback(this); } catch (Throwable ignored) { } }

    /** Media audio is playing right now. */
    @Override public boolean active() { return playerState != null ? active : audio.isMusicActive(); }

    @Override public String describe() { return playerState != null ? "Android playback events" : "Android playback events + output check"; }

    @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        boolean now = mediaIn(configs);
        if (now != active || playerState == null) { active = now; listener.onMediaPlaybackChanged(); }
    }

    private boolean mediaIn(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) return false;
        for (AudioPlaybackConfiguration c : configs) {
            AudioAttributes a = c.getAudioAttributes();
            if (a == null || a.getUsage() != AudioAttributes.USAGE_MEDIA) continue;
            if (playerState == null) return true; // can't read the state: active() falls back to the output check
            try { if (((Integer) playerState.invoke(c)) == PLAYER_STATE_STARTED) return true; } catch (Throwable ignored) { }
        }
        return false;
    }
}
