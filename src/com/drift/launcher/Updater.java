/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Over-the-air updates. Drift reads a small file online (update.json, kept in a GitHub repo):
 *   { "versionCode": 17, "versionName": "1.0", "apk": "https://.../Drift.apk",
 *     "sha256": "...", "notes": "What changed" }
 * If versionCode is newer than this build, Settings shows it; one tap downloads the APK, checks
 * its fingerprint and hands it to Android's installer. Android always asks you to confirm the
 * install, and only accepts it if it's signed with the same key as the Drift already installed.
 */
public final class Updater {
    /** Where update.json lives. Set to your GitHub repo (raw file URL). */
    public static final String UPDATE_URL = "https://raw.githubusercontent.com/iambabarmanzoor/Drift/main/update.json";
    private static final long AUTO_EVERY_MS = 6 * 60 * 60 * 1000L; // check at most every 6 hours on its own

    public interface Listener { void onUpdateState(); }

    public static final int IDLE = 0, CHECKING = 1, DOWNLOADING = 2, FAILED = 3;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int state = IDLE;
    private static volatile String error;
    private static volatile int progress; // 0..100 while downloading

    public static int state() { return state; }
    public static String error() { return error; }
    public static int progress() { return progress; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("drift_update", Context.MODE_PRIVATE); }

    public static int installedCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    public static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    public static boolean configured() { return !UPDATE_URL.contains("/OWNER/"); }

    /** A newer build is known (from the last check). */
    public static boolean available(Context c) { return prefs(c).getInt("code", 0) > installedCode(c); }
    public static int latestCode(Context c) { return prefs(c).getInt("code", 0); }
    public static String latestNotes(Context c) { return prefs(c).getString("notes", ""); }
    public static long checkedAt(Context c) { return prefs(c).getLong("checkedAt", 0); }

    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return false; }
    }

    /** Called when the home screen comes back: checks quietly if it's been a while and we're online. */
    public static void autoCheck(Context c, Listener l) {
        if (!configured() || state != IDLE && state != FAILED) return;
        if (System.currentTimeMillis() - checkedAt(c) < AUTO_EVERY_MS || !online(c)) return;
        check(c, l);
    }

    public static void check(Context ctx, final Listener l) {
        if (!configured()) { error = "Updates aren't set up yet"; state = FAILED; post(l); return; }
        if (state == CHECKING || state == DOWNLOADING) return;
        if (!online(ctx)) { error = "No internet"; state = FAILED; post(l); return; }
        final Context c = ctx.getApplicationContext();
        state = CHECKING; error = null; post(l);
        WORKER.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject j = new JSONObject(new String(get(UPDATE_URL + "?t=" + System.currentTimeMillis()), "UTF-8"));
                    prefs(c).edit()
                            .putInt("code", j.getInt("versionCode"))
                            .putString("name", j.optString("versionName", ""))
                            .putString("apk", j.getString("apk"))
                            .putString("sha", j.optString("sha256", "").toLowerCase(java.util.Locale.ROOT))
                            .putString("notes", j.optString("notes", ""))
                            .putLong("checkedAt", System.currentTimeMillis())
                            .apply();
                    state = IDLE;
                } catch (Throwable t) {
                    error = "Couldn't reach the update server";
                    state = FAILED;
                }
                post(l);
            }
        });
    }

    /** Download the newer build, verify it, and open Android's installer. */
    public static void download(Context ctx, final Listener l) {
        if (state == DOWNLOADING || !available(ctx)) return;
        if (!online(ctx)) { error = "No internet"; state = FAILED; post(l); return; }
        final Context c = ctx.getApplicationContext();
        final String url = prefs(c).getString("apk", null), sha = prefs(c).getString("sha", "");
        if (url == null) return;
        state = DOWNLOADING; progress = 0; error = null; post(l);
        WORKER.execute(new Runnable() {
            @Override public void run() {
                File out = ApkProvider.file(c);
                try {
                    HttpURLConnection h = open(url);
                    int total = h.getContentLength();
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    InputStream in = h.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[32768];
                    long got = 0; int n, lastP = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n); md.update(buf, 0, n); got += n;
                        if (total > 0) { int p = (int) (got * 100 / total); if (p != lastP) { lastP = p; progress = p; post(l); } }
                    }
                    fo.close(); in.close(); h.disconnect();
                    String hex = hex(md.digest());
                    if (sha.length() > 0 && !sha.equals(hex)) throw new Exception("Download was damaged, try again");
                    state = IDLE;
                    MAIN.post(new Runnable() { @Override public void run() { install(c); if (l != null) l.onUpdateState(); } });
                } catch (Throwable t) {
                    out.delete();
                    error = t.getMessage() != null && t.getMessage().startsWith("Download") ? t.getMessage() : "Download failed, try again";
                    state = FAILED;
                    post(l);
                }
            }
        });
    }

    private static void install(Context c) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uri(), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Throwable t) { error = "This unit has no installer screen"; state = FAILED; }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setInstanceFollowRedirects(true); // GitHub release files redirect to their storage
        h.setRequestProperty("User-Agent", "Drift-launcher");
        if (h.getResponseCode() / 100 != 2) throw new Exception("HTTP " + h.getResponseCode());
        return h;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection h = open(url);
        InputStream in = h.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 65536) break; }
        in.close(); h.disconnect();
        return bo.toByteArray();
    }

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x & 0xFF));
        return b.toString();
    }

    private static void post(final Listener l) {
        if (l == null) return;
        MAIN.post(new Runnable() { @Override public void run() { l.onUpdateState(); } });
    }

    /** Remove a leftover download once the new version is running. */
    public static void cleanup(Context c) { File f = ApkProvider.file(c); if (f.exists() && !available(c)) f.delete(); }

}
