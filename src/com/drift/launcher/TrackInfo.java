/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Some players (like the unit's own) only post a notification with the song name: no length,
 * no position, no cover. For songs stored on the unit or a USB stick, Android's media library
 * knows the length and the album cover, so we look the song up there by name.
 * Needs storage permission; without it, lookups simply return nothing.
 */
public final class TrackInfo {
    public static final class Info {
        public final long durationMs;
        public final Bitmap art;
        Info(long d, Bitmap a) { durationMs = d; art = a; }
    }

    public interface Callback { void onInfo(String key, Info info); }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<String, Info> cache = new HashMap<>();
    private static final Info NONE = new Info(0, null);

    public static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Cached result, or null if not looked up yet (then a lookup starts and the callback fires). */
    public static Info get(final Context ctx, final String title, final String artist, final Callback cb) {
        final String key = title + "|" + (artist == null ? "" : artist);
        synchronized (cache) {
            Info hit = cache.get(key);
            if (hit != null) return hit == NONE ? null : hit;
            cache.put(key, NONE); // one lookup per song
        }
        if (!allowed(ctx)) { synchronized (cache) { cache.remove(key); } return null; }
        final Context app = ctx.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override public void run() {
                final Info info = lookup(app, title, artist);
                synchronized (cache) { cache.put(key, info == null ? NONE : info); }
                if (info != null) MAIN.post(new Runnable() { @Override public void run() { cb.onInfo(key, info); } });
            }
        });
        return null;
    }

    public static void forget() { synchronized (cache) { cache.clear(); } synchronized (TrackInfo.class) { index = null; } }

    /** Diagnostics: is the song known to the media library, and how many songs does it hold. */
    public static String status(Context c, String title, String artist) {
        if (!allowed(c)) return "storage permission: NOT allowed";
        StringBuilder b = new StringBuilder("storage permission: allowed\n");
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[] { MediaStore.Audio.Media._ID }, null, null, null);
            b.append("songs in media library: ").append(cur == null ? "?" : String.valueOf(cur.getCount())).append('\n');
        } catch (Throwable t) { b.append("media library: blocked\n"); }
        finally { if (cur != null) cur.close(); }
        if (title != null) {
            Info i = lookup(c, title, artist);
            b.append("song files Drift can read: ").append(files().size()).append('\n');
            b.append("this song: ").append(i == null ? "not found" : "found, " + (i.durationMs / 1000) + " s" + (i.art != null ? ", with cover" : ", no cover"));
        }
        return b.toString();
    }

    private static Info lookup(Context c, String title, String artist) {
        Info i = lookupLibrary(c, title, artist);
        return i != null ? i : lookupFiles(title, artist);
    }

    // ------------------------------------------------------------ fallback: find the file ourselves
    // Some head units never add USB songs to Android's media library (it reports 0 songs), while their
    // own player reads the stick directly. Then we look for the file by name on the storage we can
    // read, and get its length and embedded cover from the file itself.

    private static final String[] EXT = { ".mp3", ".m4a", ".flac", ".wav", ".aac", ".ogg", ".wma", ".opus", ".ape", ".amr" };
    private static java.util.List<String[]> index; // { lowercase name without extension, path }
    private static long indexAt;

    private static synchronized java.util.List<String[]> files() {
        long now = System.currentTimeMillis();
        if (index != null && now - indexAt < 10 * 60 * 1000) return index;
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        try { roots.add(android.os.Environment.getExternalStorageDirectory().getAbsolutePath()); } catch (Throwable ignored) { }
        for (String base : new String[] { "/storage", "/mnt", "/mnt/media_rw" }) {
            java.io.File[] kids = new java.io.File(base).listFiles();
            if (kids == null) continue;
            for (java.io.File k : kids) {
                String n = k.getName();
                if (n.equals("self") || n.equals("emulated") || n.equals("secure") || n.equals("asec") || n.equals("obb")
                        || n.equals("runtime") || n.equals("user") || n.equals("appfuse")) continue;
                if (k.isDirectory() && k.canRead()) roots.add(k.getAbsolutePath());
            }
        }
        for (String r : roots) walk(new java.io.File(r), 0, out);
        index = out; indexAt = now;
        return out;
    }

    private static void walk(java.io.File dir, int depth, java.util.List<String[]> out) {
        if (depth > 8 || out.size() > 30000) return;
        java.io.File[] kids;
        try { kids = dir.listFiles(); } catch (Throwable t) { return; }
        if (kids == null) return;
        for (java.io.File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (n.equals("Android") || n.equals("LOST.DIR"))) continue;
                walk(f, depth + 1, out);
            } else {
                String low = n.toLowerCase(java.util.Locale.ROOT);
                for (String e : EXT) {
                    if (low.endsWith(e)) { out.add(new String[] { low.substring(0, low.length() - e.length()), f.getAbsolutePath() }); break; }
                }
            }
        }
    }

    private static Info lookupFiles(String title, String artist) {
        if (title == null || title.trim().length() < 2) return null;
        String t = title.toLowerCase(java.util.Locale.ROOT).trim();
        String a = artist == null ? null : artist.toLowerCase(java.util.Locale.ROOT).trim();
        String best = null; int bestScore = -1;
        for (String[] f : files()) {
            String name = f[0];
            if (!name.contains(t)) continue;
            // closest match wins: exact name, then names that also mention the artist, then the shortest
            int score = name.equals(t) ? 1000 : 500 - Math.min(400, name.length() - t.length());
            if (a != null && name.contains(a)) score += 300;
            if (score > bestScore) { bestScore = score; best = f[1]; }
        }
        if (best == null) return null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(best);
            String d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long dur = d == null ? 0 : Long.parseLong(d.trim());
            Bitmap art = null;
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 300 && o.outHeight / (s * 2) >= 300) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                art = BitmapFactory.decodeByteArray(pic, 0, pic.length, o2);
            }
            return dur > 0 || art != null ? new Info(dur, art) : null;
        } catch (Throwable e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) { }
        }
    }

    private static Info lookupLibrary(Context c, String title, String artist) {
        Cursor cur = null;
        try {
            String full = artist == null ? title : title + " - " + artist;
            String[] cols = { MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ARTIST };
            // the player may show the tag title, or the file name ("Song - Artist.mp3")
            String sel = MediaStore.Audio.Media.TITLE + "=? OR " + MediaStore.Audio.Media.TITLE + "=? OR "
                    + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?";
            String[] args = { title, full, full.replace("%", "") + ".%" };
            cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, sel, args, null);
            if (cur == null || !cur.moveToFirst()) {
                // looser: the name appears anywhere in the title or file name ("01 Aadat (Unplugged).mp3")
                if (cur != null) cur.close();
                String like = "%" + title.replace("%", "") + "%";
                cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                        MediaStore.Audio.Media.TITLE + " LIKE ? OR " + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?",
                        new String[] { like, like }, "length(" + MediaStore.Audio.Media.TITLE + ") ASC");
                if (cur == null || !cur.moveToFirst()) return null;
            }
            // prefer the row whose artist matches, if several share the title
            int best = 0;
            if (artist != null && cur.getCount() > 1) {
                do {
                    String a = cur.getString(2);
                    if (a != null && a.equalsIgnoreCase(artist)) { best = cur.getPosition(); break; }
                } while (cur.moveToNext());
            }
            cur.moveToPosition(best);
            long dur = cur.getLong(0);
            long albumId = cur.getLong(1);
            Bitmap art = null;
            try {
                Uri u = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream in = c.getContentResolver().openInputStream(u);
                if (in != null) {
                    BitmapFactory.decodeStream(in, null, o); in.close();
                    int s = 1;
                    while (o.outWidth / (s * 2) >= 300 && o.outHeight / (s * 2) >= 300) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = s;
                    in = c.getContentResolver().openInputStream(u);
                    if (in != null) { art = BitmapFactory.decodeStream(in, null, o2); in.close(); }
                }
            } catch (Throwable ignored) { } // no cover for this album
            return dur > 0 || art != null ? new Info(dur, art) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }
}
