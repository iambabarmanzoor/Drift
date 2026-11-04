/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drift Music's library: reads music folders straight from the USB stick and internal storage.
 * No Android media library and no "syncing": a folder is listed the moment it's opened.
 * Song names come from the file name first and are replaced by the real tags (title, artist,
 * length) as they're read in the background; tags are cached on disk so this happens once.
 */
public final class MusicLibrary {
    public static final String[] EXT = { ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma", ".amr", ".3gp", ".mka" };

    /** One song file. Title/artist/duration start as guesses and are filled from the tags. */
    public static final class Song {
        public final String path;
        public volatile String title, artist;
        public volatile long durationMs;
        public volatile boolean tagged;
        Song(String path) {
            this.path = path;
            String name = new File(path).getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            name = name.replaceFirst("^\\d{1,3}\\s*[-._)]?\\s+", "").trim(); // "01 - Song" -> "Song"
            int dash = name.indexOf(" - ");
            if (dash > 0) { artist = name.substring(0, dash).trim(); title = name.substring(dash + 3).trim(); }
            else { title = name; artist = new File(path).getParentFile() == null ? "" : new File(path).getParentFile().getName(); }
            if (title.isEmpty()) title = new File(path).getName();
        }
        public boolean exists() { return new File(path).exists(); }
    }

    /** A folder that contains music (directly or below). */
    public static final class Folder {
        public final File dir;
        public final String name;
        public int count; // songs directly inside
        Folder(File dir, String name) { this.dir = dir; this.name = name; }
    }

    public interface Listener { void onChanged(); }

    private static MusicLibrary instance;
    public static synchronized MusicLibrary get(Context c) {
        if (instance == null) instance = new MusicLibrary(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<String, Song> songs = new HashMap<>();
    private final HashMap<String, String[]> tagCache = new HashMap<>(); // path -> {title, artist, durMs, mtime}
    private boolean cacheLoaded, cacheDirty;
    private Listener listener;

    private MusicLibrary(Context c) { ctx = c; }

    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------ sources

    /** Internal storage root (the unit's own "sdcard"). */
    public File internalRoot() {
        try { return Environment.getExternalStorageDirectory(); } catch (Throwable t) { return new File("/sdcard"); }
    }

    /** Mounted removable drives we can read (USB sticks, SD cards). */
    public List<File> usbRoots() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<File> out = new ArrayList<>();
        String internal = internalRoot().getAbsolutePath();
        for (String base : new String[] { "/storage", "/mnt", "/mnt/media_rw", "/mnt/usb", "/storage/usb" }) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("self") || n.equals("emulated") || n.equals("secure") || n.equals("asec") || n.equals("obb")
                        || n.equals("runtime") || n.equals("user") || n.equals("appfuse") || n.equals("sdcard0")
                        || n.equals("sdcard") || n.equals("media_rw") || n.equals("expand") || n.equals("vendor")) continue;
                if (!k.isDirectory() || !k.canRead()) continue;
                String path;
                try { path = k.getCanonicalPath(); } catch (Exception e) { path = k.getAbsolutePath(); }
                if (path.equals(internal) || path.startsWith("/storage/emulated") || !seen.add(path)) continue;
                File[] inside = k.listFiles();
                if (inside == null || inside.length == 0) continue;
                out.add(k);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ listing

    /** Folders with music directly under dir's tree, shown as a flat list of the folders that hold songs. */
    public List<Folder> folders(List<File> roots) {
        List<Folder> out = new ArrayList<>();
        for (File r : roots) walkFolders(r, r, 0, out);
        Collections.sort(out, new Comparator<Folder>() {
            @Override public int compare(Folder a, Folder b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return out;
    }

    private void walkFolders(File root, File dir, int depth, List<Folder> out) {
        if (depth > 6 || out.size() > 500) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int songsHere = 0;
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (n.equals("Android") || n.equals("LOST.DIR") || n.equals("System Volume Information"))) continue;
                walkFolders(root, f, depth + 1, out);
            } else if (isAudio(n)) songsHere++;
        }
        if (songsHere > 0 && depth > 0) {
            Folder fo = new Folder(dir, dir.getName());
            fo.count = songsHere;
            out.add(fo);
        }
    }

    /** Songs sitting directly in the roots (not in a folder). */
    public List<Song> looseSongs(List<File> roots) {
        List<Song> out = new ArrayList<>();
        for (File r : roots) out.addAll(songsIn(r));
        return out;
    }

    public static boolean isAudio(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : EXT) if (low.endsWith(e)) return true;
        return false;
    }

    /** Songs directly in a folder, in file-name order (like the stick shows them). */
    public synchronized List<Song> songsIn(File dir) {
        List<Song> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        List<File> files = new ArrayList<>();
        for (File f : kids) if (f.isFile() && !f.getName().startsWith(".") && isAudio(f.getName())) files.add(f);
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        for (File f : files) out.add(song(f.getAbsolutePath()));
        return out;
    }

    /** Every song under the given roots (for "play everything"). */
    public List<Song> allSongs(List<File> roots) {
        List<Song> out = new ArrayList<>();
        for (Folder f : folders(roots)) out.addAll(songsIn(f.dir));
        out.addAll(looseSongs(roots));
        return out;
    }

    /** The shared Song object for a path, with cached tags applied. */
    public synchronized Song song(String path) {
        Song s = songs.get(path);
        if (s != null) return s;
        loadCache();
        s = new Song(path);
        String[] t = tagCache.get(path);
        if (t != null) {
            long mt = new File(path).lastModified();
            if (String.valueOf(mt).equals(t[3])) {
                if (!t[0].isEmpty()) s.title = t[0];
                if (!t[1].isEmpty()) s.artist = t[1];
                try { s.durationMs = Long.parseLong(t[2]); } catch (Exception ignored) { }
                s.tagged = true;
            }
        }
        songs.put(path, s);
        return s;
    }

    // ------------------------------------------------------------ tags

    /** Read tags for these songs in the background (skipping ones already known), then notify. */
    public void readTags(final List<Song> list) {
        io.execute(new Runnable() {
            @Override public void run() {
                boolean any = false;
                int n = 0;
                for (Song s : list) {
                    if (s.tagged) continue;
                    readTagsNow(s);
                    any = true;
                    if (++n % 8 == 0) notifyChanged(); // refresh visible rows as we go
                }
                if (any) { notifyChanged(); saveCache(); }
            }
        });
    }

    void readTagsNow(Song s) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(s.path);
            String t = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (a == null) a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            synchronized (this) {
                if (t != null && !t.trim().isEmpty()) s.title = AppRepo.cleanLabel(t, s.title);
                if (a != null && !a.trim().isEmpty() && !a.trim().equalsIgnoreCase("<unknown>")) s.artist = AppRepo.cleanLabel(a, s.artist);
                if (d != null) try { s.durationMs = Long.parseLong(d.trim()); } catch (Exception ignored) { }
                s.tagged = true;
                tagCache.put(s.path, new String[] { s.title, s.artist == null ? "" : s.artist, String.valueOf(s.durationMs),
                        String.valueOf(new File(s.path).lastModified()) });
                cacheDirty = true;
            }
        } catch (Throwable ignored) {
            s.tagged = true; // unreadable: keep the file-name guess
        } finally {
            try { m.release(); } catch (Throwable ignored) { }
        }
    }

    /** Embedded cover art, scaled to about max px (null if the file has none). Call off the main thread. */
    public static Bitmap cover(String path, int max) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(path);
            byte[] pic = m.getEmbeddedPicture();
            if (pic == null) return folderCover(new File(path).getParentFile(), max);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return fit(BitmapFactory.decodeByteArray(pic, 0, pic.length, o2), max);
        } catch (Throwable t) {
            return null;
        } finally {
            try { m.release(); } catch (Throwable ignored) { }
        }
    }

    /** cover.jpg / folder.jpg next to the songs, as many PC rips have. */
    private static Bitmap folderCover(File dir, int max) {
        if (dir == null) return null;
        for (String n : new String[] { "cover.jpg", "folder.jpg", "Folder.jpg", "Cover.jpg", "cover.png", "AlbumArt.jpg" }) {
            File f = new File(dir, n);
            if (!f.isFile()) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return fit(BitmapFactory.decodeFile(f.getAbsolutePath(), o2), max);
        }
        return null;
    }

    /** Scale down to at most max px on the longer side (keeps memory small). */
    private static Bitmap fit(Bitmap b, int max) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight(), m = Math.max(w, h);
        if (m <= max) return b;
        float sc = max / (float) m;
        Bitmap out = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
        if (out != b) b.recycle();
        return out;
    }

    private void notifyChanged() {
        main.post(new Runnable() { @Override public void run() { if (listener != null) listener.onChanged(); } });
    }

    // ------------------------------------------------------------ tag cache on disk (tab-separated lines)

    private File cacheFile() { return new File(ctx.getFilesDir(), "music_tags.tsv"); }

    private synchronized void loadCache() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        File f = cacheFile();
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length == 5) tagCache.put(p[0], new String[] { p[1], p[2], p[3], p[4] });
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
    }

    private synchronized void saveCache() {
        if (!cacheDirty) return;
        cacheDirty = false;
        FileWriter w = null;
        try {
            File tmp = new File(ctx.getFilesDir(), "music_tags.tmp");
            w = new FileWriter(tmp);
            for (java.util.Map.Entry<String, String[]> e : tagCache.entrySet()) {
                String[] v = e.getValue();
                w.write(clean(e.getKey()) + "\t" + clean(v[0]) + "\t" + clean(v[1]) + "\t" + v[2] + "\t" + v[3] + "\n");
            }
            w.close(); w = null;
            tmp.renameTo(cacheFile());
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }
}
