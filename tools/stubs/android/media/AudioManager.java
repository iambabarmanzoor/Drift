/*
 * Drift launcher. Copyright (c) 2026 Babar Manzoor. All rights reserved.
 *
 * COMPILE-TIME STUB ONLY (never packaged). Drift builds against the Android 6 (API 23) library,
 * which predates AudioManager.AudioPlaybackCallback (API 26). This stub declares the members
 * Drift uses so the code compiles; on the device the real android.media.AudioManager is used.
 * Values must match the platform exactly (constants are compiled in).
 */
package android.media;

import android.os.Handler;
import android.view.KeyEvent;
import java.util.List;

public class AudioManager {
    public static final int STREAM_MUSIC = 3;
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_LOSS = -1;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -2;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;

    public interface OnAudioFocusChangeListener { void onAudioFocusChange(int focusChange); }

    /** API 26. */
    public static abstract class AudioPlaybackCallback {
        public AudioPlaybackCallback() { }
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) { }
    }

    public boolean isMusicActive() { throw new RuntimeException("stub"); }
    public boolean isVolumeFixed() { throw new RuntimeException("stub"); }
    public int getStreamVolume(int streamType) { throw new RuntimeException("stub"); }
    public int getStreamMaxVolume(int streamType) { throw new RuntimeException("stub"); }
    public void setStreamVolume(int streamType, int index, int flags) { throw new RuntimeException("stub"); }
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) { throw new RuntimeException("stub"); }
    public int abandonAudioFocus(OnAudioFocusChangeListener l) { throw new RuntimeException("stub"); }
    public void dispatchMediaKeyEvent(KeyEvent keyEvent) { throw new RuntimeException("stub"); }
    /** API 26. */
    public void registerAudioPlaybackCallback(AudioPlaybackCallback cb, Handler handler) { throw new RuntimeException("stub"); }
    /** API 26. */
    public void unregisterAudioPlaybackCallback(AudioPlaybackCallback cb) { throw new RuntimeException("stub"); }
    /** API 26. */
    public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() { throw new RuntimeException("stub"); }
}
