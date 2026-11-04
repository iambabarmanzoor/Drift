/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Favourites, Recently played and your playlists, saved as a small JSON file inside Drift.
 * Songs are kept by file path, so a playlist can mix the USB stick and internal storage;
 * songs on a stick that isn't plugged in simply show as unavailable.
 */
public final class MusicStore {
    public static final class Playlist {
        public String name;
        public final List<String> items = new ArrayList<>();
        Playlist(String name) { this.name = name; }
    }

    private static MusicStore instance;
    public static synchronized MusicStore get(Context c) {
        if (instance == null) instance = new MusicStore(c.getApplicationContext());
        return instance;
    }

    private static final int RECENT_MAX = 30;
    private final File file;
    public final List<String> favourites = new ArrayList<>();
    public final List<String> recent = new ArrayList<>();
    public final List<Playlist> lists = new ArrayList<>();

    private MusicStore(Context c) {
        file = new File(c.getFilesDir(), "music_lists.json");
        load();
    }

    public boolean isFavourite(String path) { return path != null && favourites.contains(path); }

    public void toggleFavourite(String path) {
        if (path == null) return;
        if (!favourites.remove(path)) favourites.add(0, path);
        save();
    }

    public void played(String path) {
        if (path == null) return;
        recent.remove(path);
        recent.add(0, path);
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
        save();
    }

    public Playlist create(String name) {
        Playlist p = new Playlist(name);
        lists.add(p);
        save();
        return p;
    }

    public String nextName() {
        int n = lists.size() + 1;
        while (true) {
            String cand = "Playlist " + n;
            boolean used = false;
            for (Playlist p : lists) if (p.name.equalsIgnoreCase(cand)) used = true;
            if (!used) return cand;
            n++;
        }
    }

    public void add(Playlist p, String path) { if (!p.items.contains(path)) { p.items.add(path); save(); } }

    public void remove(Playlist p, String path) { p.items.remove(path); save(); }

    public void delete(Playlist p) { lists.remove(p); save(); }

    public void rename(Playlist p, String name) { p.name = name; save(); }

    // ------------------------------------------------------------ file

    private void load() {
        try {
            if (!file.exists()) return;
            FileInputStream in = new FileInputStream(file);
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            JSONObject j = new JSONObject(new String(b, 0, n, "UTF-8"));
            fill(favourites, j.optJSONArray("favourites"));
            fill(recent, j.optJSONArray("recent"));
            JSONArray ls = j.optJSONArray("lists");
            if (ls != null) for (int i = 0; i < ls.length(); i++) {
                JSONObject o = ls.getJSONObject(i);
                Playlist p = new Playlist(o.optString("name", "Playlist"));
                fill(p.items, o.optJSONArray("items"));
                lists.add(p);
            }
        } catch (Throwable ignored) { }
    }

    private static void fill(List<String> out, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) { String s = a.optString(i, null); if (s != null) out.add(s); }
    }

    public void save() {
        try {
            JSONObject j = new JSONObject();
            j.put("favourites", new JSONArray(favourites));
            j.put("recent", new JSONArray(recent));
            JSONArray ls = new JSONArray();
            for (Playlist p : lists) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("items", new JSONArray(p.items));
                ls.put(o);
            }
            j.put("lists", ls);
            File tmp = new File(file.getParentFile(), "music_lists.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(j.toString().getBytes("UTF-8"));
            out.close();
            tmp.renameTo(file);
        } catch (Throwable ignored) { }
    }
}
