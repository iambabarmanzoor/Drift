/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Launcher extends Activity implements DrawerView.Host, MediaCard.Actions, MediaHub.Player, SettingsPanel.Host {
    private static final int SLOTS = 12, PER_PAGE = 4; // 3 swipeable pages of 4
    static final int PICK_PLAYER = -2;
    private static final String NOTIF_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private SharedPreferences prefs;
    private AppRepo repo;
    private MediaHub media;
    private PlateView plate;
    private DockGlowView dockGlow;
    private SpeedView speed;
    private View roadZone;
    private AppearancePanel appearance;
    private ClockView clock;
    private MediaCard card;
    private DrawerView drawer;
    private final TileView[] tiles = new TileView[SLOTS];
    private final String[] slots = new String[SLOTS];
    private Palette palette;
    private int themeIdx, roadMode;
    private boolean glow, mph, flip;
    private RoadView road;
    private Pager shortcuts;
    private StatusView status;
    private DockView dock;
    private Sheet sheet;
    private SettingsPanel settings;
    private HomeLayout home;

    private static final int REQ_LOCATION = 7, REQ_STORAGE = 8;
    private boolean storageAskedThisRun;
    private static final long FRAME_MS = 33;
    private boolean ticking, screenOn = true;
    private int recentsFails;

    /** One ~30 fps clock for all home-screen motion, paused whenever the home screen is covered. */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            boolean covered = settings.isOpen() || drawer.isOpen() || !screenOn;
            if (!covered) {
                if (road.isActive()) road.invalidate();
                if (dockGlow.isActive()) dockGlow.invalidate();
            }
            home.postDelayed(this, FRAME_MS);
        }
    };

    private void startTicker() { if (!ticking) { ticking = true; home.post(ticker); } }
    private void stopTicker() { ticking = false; home.removeCallbacks(ticker); }
    // music card on the right: the card moves with its glass in the mirrored artwork, the shortcuts the other way
    private float cardX() { return flip ? 34 + 792 : 34; }
    private float tilesX() { return flip ? 861 - 836 : 861; }

    private final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) { screenOn = false; return; }
            if (Intent.ACTION_SCREEN_ON.equals(a)) { screenOn = true; return; }
            clock.tick(); status.refresh();
            if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(a)) Updater.autoCheck(Launcher.this, updates);
        }
    };

    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // an uninstalled app leaves its shortcut slot: clear it so the tile shows "+"
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    && i.getData() != null) {
                String gone = i.getData().getSchemeSpecificPart();
                for (int s = 0; s < SLOTS; s++) {
                    if (slots[s] != null && slots[s].startsWith(gone + "/")) { slots[s] = null; prefs.edit().remove("slot" + s).apply(); }
                }
                if (gone.equals(prefs.getString("player", null))) prefs.edit().remove("player").apply();
            }
            reloadApps();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("drift", MODE_PRIVATE);
        themeIdx = prefs.getInt("accent", 0);
        roadMode = prefs.getInt("road", prefs.getBoolean("motion", true) ? RoadView.CRUISE : RoadView.OFF);
        glow = prefs.getBoolean("glow", true);
        mph = prefs.getBoolean("mph", false);
        flip = prefs.getBoolean("flip", false);
        palette = accentPalette();
        for (int i = 0; i < SLOTS; i++) slots[i] = prefs.getString("slot" + i, null);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setBackgroundDrawable(null);

        repo = new AppRepo(this);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm); // full screen: we run immersive
        float sc = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f, Math.min(dm.widthPixels, dm.heightPixels) / 720f);
        repo.setIconPx(Math.max(64, Math.round(128 * sc))); // big enough for the drawer's large icons

        FrameLayout root = new FrameLayout(this);
        root.setBackground(null); // the artwork covers everything

        home = new HomeLayout(this);
        home.setClipChildren(false);
        // the artwork: the reference design with only the live parts removed
        plate = new PlateView(this);
        home.add(plate, 0, 0, 1280, 720);
        plate.set(flip, themeIdx);

        // motion over the artwork: lane lights and tail lights, and the light running along the bottom bar
        road = new RoadView(this);
        home.add(road, 440, 40, 400, 560);
        dockGlow = new DockGlowView(this);
        home.add(dockGlow, 0, 560, 1280, 150);

        // tap the car to open navigation
        roadZone = new View(this);
        roadZone.setContentDescription("Open navigation");
        home.add(roadZone, 500, 120, 280, 440);
        roadZone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNavigation(); }
        });

        // top strip: home and recent apps on the left, Bluetooth / Wi-Fi on the right
        status = new StatusView(this);
        home.add(status, 0, 0, 1280, 80);
        status.setListener(new StatusView.Listener() {
            @Override public void onHome() { goHome(); }
            @Override public void onRecents() { openRecents(); }
        });

        // music card
        card = new MediaCard(this);
        card.setActions(this);
        card.setOnRight(flip);
        home.add(card, cardX(), 106, 420, 428);

        // clock with lined date
        clock = new ClockView(this);
        clock.setLeftAligned(flip);
        home.add(clock, 0, 0, 1280, 720); // draws at the reference's exact coordinates

        // shortcuts: pages of 2x2 glass tiles you swipe left/right
        shortcuts = new Pager(this);
        home.add(shortcuts, tilesX(), 261, 394, 300);
        shortcuts.setDotsHeight(Math.round(18 * sc));
        GridPage page = null;
        for (int i = 0; i < SLOTS; i++) {
            if (i % PER_PAGE == 0) {
                page = new GridPage(this, 2, 2, 16.5f * sc);
                shortcuts.addView(page);
            }
            final TileView t = new TileView(this, i, false);
            tiles[i] = t;
            page.addView(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { tileClick(t); }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    tileMenu(t);
                    return true;
                }
            });
        }

        shortcuts.setFade(true);       // icons dissolve in place on the fixed glass tiles
        shortcuts.setPage(0, false);

        // dock: All Apps / speed (navigation) / Settings
        dock = new DockView(this);
        home.add(dock, 0, 594, 1280, 126);
        dock.setListener(new DockView.Listener() {
            @Override public void onDock(int item) {
                if (item == DockView.APPS) drawer.show(-1);
                else if (item == DockView.CAR) openNavigation();
                else driftMenu();
            }
        });
        speed = new SpeedView(this); // drawn over the dock's centre; taps fall through to the dock
        speed.setMph(mph);
        home.add(speed, 540, 600, 200, 90);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        appearance = new AppearancePanel(this, this);
        appearance.setOnLeft(flip);
        root.addView(appearance, new FrameLayout.LayoutParams(-1, -1));
        settings = new SettingsPanel(this, this);
        root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
        drawer = new DrawerView(this, repo, this); // above settings: it's also the app picker
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        sheet = new Sheet(this);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        media = new MediaHub(this, card, this);
        applyTheme();
        road.setMode(roadMode);
        dockGlow.setOn(glow);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);
        IntentFilter mf = new IntentFilter();
        mf.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        mf.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(pkgRx, mf);

        reloadApps();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(timeRx, f);
        immersive();
        clock.tick();
        status.refresh();
        road.start();
        dockGlow.start();
        screenOn = true;
        startTicker();
        media.start();
        if (SpeedView.hasPermission(this)) speed.start();
        else if (!prefs.getBoolean("askedLocation", false)) {
            prefs.edit().putBoolean("askedLocation", true).apply();
            try { requestPermissions(new String[] { android.Manifest.permission.ACCESS_FINE_LOCATION }, REQ_LOCATION); }
            catch (Throwable ignored) { }
        }
        settings.refresh();
        Updater.cleanup(this);
        Updater.autoCheck(this, updates);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_LOCATION && SpeedView.hasPermission(this)) speed.start();
        if (req == REQ_STORAGE && TrackInfo.allowed(this)) { TrackInfo.forget(); media.refreshNow(); }
        settings.refresh();
        settings.refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeRx); } catch (Exception ignored) { }
        stopTicker();
        road.stop();
        dockGlow.stop();
        speed.stop();
        media.stop();
    }

    private AlertDialog openDialog;

    /** Full screen: hide Android's own bar; Drift draws its own status strip. A swipe from the edge brings it back. */
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    /** The home icon in the status strip: close anything open and go back to the first shortcut page. */
    private void goHome() {
        if (sheet.isShowing()) sheet.dismiss();
        closePanels();
        if (drawer.isOpen()) drawer.close();
        shortcuts.setPage(0, true);
    }

    /** The button next to Home: Android's recent apps screen, where apps can be swiped away. */
    private void openRecents() {
        if (RecentsService.showRecents()) { recentsFails = 0; return; }
        if (RecentsService.isEnabled(this) && recentsFails++ == 0) {
            Toast.makeText(this, "Recent apps is starting, try again in a moment", Toast.LENGTH_SHORT).show();
            return;
        }
        recentsFails = 0;
        openRecentsAccess();
    }

    @Override protected void onDestroy() {
        if (openDialog != null) openDialog.dismiss();
        repo.shutdown();
        try { unregisterReceiver(pkgRx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // Home button closes whatever is open
        if (sheet.isShowing()) sheet.dismiss();
        closePanels();
        if (drawer.isOpen()) drawer.close();
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
    }

    @Override public void onBackPressed() {
        if (sheet.isShowing()) sheet.dismiss(); // never leave the home screen
        else if (drawer.isOpen()) drawer.close();
        else if (appearance.isOpen()) openSettings();
        else if (settings.isOpen()) settings.close();
    }

    // ---------- theme ----------

    /** A palette for the views that use one (drawer, tiles, sheets), built from the accent. */
    private Palette accentPalette() {
        Accent a = Accent.get(themeIdx);
        return new Palette(a.name, a.glow());
    }

    private void applyTheme() {
        Window w = getWindow();
        w.setStatusBarColor(Palette.BG);
        w.setNavigationBarColor(Palette.BG);
        Accent a = Accent.get(themeIdx);
        palette = accentPalette();
        plate.set(flip, themeIdx);
        road.setAccent(a);
        dockGlow.setAccent(a);
        speed.setAccent(a);
        card.setPalette(palette);
        card.setAccent(a);
        clock.setPalette(palette);
        for (TileView t : tiles) t.setPalette(palette);
        dock.setPalette(palette);
        drawer.setPalette(palette);
        settings.setAccent(a);
        appearance.setAccent(a);
    }

    // ---------- apps & shortcuts ----------

    private void reloadApps() {
        repo.load(new AppRepo.Listener() {
            @Override public void onAppsLoaded(List<AppRepo.App> apps) {
                prefCached = false;
                if (!prefs.getBoolean("seeded", false)) seedShortcuts(apps);
                drawer.setApps(apps);
                bindTiles();
                card.setPlayerName(playerLabel());
            }
        });
    }

    /** First run: guess the apps a driver wants most. */
    private void seedShortcuts(List<AppRepo.App> apps) {
        String[][] groups = {
            { "com.google.android.apps.maps", "waze", "maps", "navi", "gps" },
            { "music", "poweramp", "spotify", "youtube.music", "player", "audio" },
            { "bluetooth", ".bt", "btphone", "phone", "dialer" },
            { "radio", ".fm", "tuner" },
            { "waze" },
            { "youtube", "video" },
            { "zlink", "carplay", "androidauto", "autolink" },
            { "com.android.settings", "settings" },
        };
        List<String> used = new ArrayList<>();
        int slot = 0;
        for (String[] g : groups) {
            if (slot >= SLOTS) break;
            AppRepo.App hit = null;
            for (String kw : g) {
                for (AppRepo.App a : apps) {
                    if (used.contains(a.key)) continue;
                    String hay = (a.cn.getPackageName() + " " + a.label).toLowerCase(Locale.ROOT);
                    if (hay.contains(kw)) { hit = a; break; }
                }
                if (hit != null) break;
            }
            if (hit != null) { used.add(hit.key); slots[slot++] = hit.key; }
        }
        SharedPreferences.Editor e = prefs.edit().putBoolean("seeded", true);
        for (int i = 0; i < SLOTS; i++) e.putString("slot" + i, slots[i]);
        e.apply();
    }

    private void saveSlot(int i, String key) {
        slots[i] = key;
        prefs.edit().putString("slot" + i, key).apply();
        bindTile(i);
    }

    private void bindTiles() { for (int i = 0; i < SLOTS; i++) bindTile(i); }

    private void bindTile(int i) {
        final TileView t = tiles[i];
        AppRepo.App a = repo.find(slots[i]);
        if (a == null) { t.bind(null, null, 0); return; }
        Bitmap ic = repo.icon(a, new AppRepo.IconCallback() {
            @Override public void onIcon(AppRepo.App app, Bitmap icon, int color) {
                if (t.app() == app) t.setIcon(icon, color);
            }
        });
        t.bind(a, ic, ic != null ? repo.color(a) : 0);
    }

    private void tileClick(TileView t) {
        if (t.app() != null) launch(t.app(), t);
        else drawer.show(t.index);
    }

    private void tileMenu(final TileView t) {
        final int i = t.index;
        if (t.app() == null) { drawer.show(i); return; }
        String[] items = { "Change app", "Move earlier", "Move later", "!Remove", "!Reset all shortcuts" };
        dialog(t.app().label, items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) drawer.show(i);
                else if (which == 1 && i > 0) { swap(i, i - 1); shortcuts.setPage((i - 1) / PER_PAGE, true); }
                else if (which == 2 && i < SLOTS - 1) { swap(i, i + 1); shortcuts.setPage((i + 1) / PER_PAGE, true); }
                else if (which == 3) saveSlot(i, null);
                else if (which == 4) resetShortcuts();
            }
        });
    }

    private void swap(int a, int b) {
        String ka = slots[a], kb = slots[b];
        saveSlot(a, kb);
        saveSlot(b, ka);
    }

    private void launch(AppRepo.App a, View from) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            Bundle opts = from == null ? null : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(i, opts);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** Tap on the car: open the first navigation app we can find (shortcut 1 first). */
    private void openNavigation() {
        AppRepo.App first = repo.find(slots[0]);
        if (first != null && "NAVIGATION".equals(TileView.guessCategory(first))) { launch(first, roadZone); return; }
        for (AppRepo.App a : repo.apps()) {
            if ("NAVIGATION".equals(TileView.guessCategory(a))) { launch(a, roadZone); return; }
        }
        if (first != null) launch(first, roadZone);
    }

    private boolean start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (Exception e) { return false; }
    }

    // ---------- DrawerView.Host ----------

    @Override public void onAppChosen(AppRepo.App app, int pickSlot) {
        if (pickSlot == PICK_PLAYER) {
            prefs.edit().putString("player", app.cn.getPackageName()).apply();
            prefCached = false;
            card.setPlayerName(app.label);
            clock.flash(app.label.toUpperCase(Locale.ROOT));
            settings.refresh();
        } else if (pickSlot >= 0) saveSlot(pickSlot, app.key);
        else launch(app, null);
    }

    // ---------- MediaHub.Player: the user's music app ----------

    /**
     * Chosen music app, or a default: the system's own music app (what the unit ships with),
     * never a streaming app like Spotify or YouTube Music unless the user picks it.
     */
    private String prefCache;
    private boolean prefCached;

    @Override public String preferredPackage() {
        if (prefCached) return prefCache;
        prefCache = computePreferred();
        prefCached = !repo.apps().isEmpty();
        return prefCache;
    }

    private String computePreferred() {
        PackageManager pm = getPackageManager();
        String p = prefs.getString("player", null);
        if (p != null && pm.getLaunchIntentForPackage(p) != null) { MediaWatcher.preferredPkg = p; return p; }
        String found = null;
        try {
            ResolveInfo ri = pm.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), 0);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)
                    && !isStreaming(ri.activityInfo.packageName)) found = ri.activityInfo.packageName;
        } catch (Exception ignored) { }
        if (found == null) {
            String fallback = null;
            for (AppRepo.App a : repo.apps()) {
                String pkg = a.cn.getPackageName();
                String hay = (pkg + " " + a.label).toLowerCase(Locale.ROOT);
                if (isStreaming(pkg) || !(hay.contains("music") || hay.contains("media") || hay.contains("audio") || hay.contains("mp3"))) continue;
                try {
                    if ((pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0) { found = pkg; break; }
                } catch (Exception ignored) { }
                if (fallback == null) fallback = pkg;
            }
            if (found == null) found = fallback;
        }
        MediaWatcher.preferredPkg = found;
        return found;
    }

    private static boolean isStreaming(String pkg) {
        return pkg.startsWith("com.spotify") || pkg.startsWith("com.google.") || pkg.contains("youtube")
                || pkg.startsWith("com.amazon") || pkg.startsWith("com.deezer") || pkg.startsWith("com.soundcloud");
    }

    private String playerLabel() {
        String p = preferredPackage();
        if (p == null) return null;
        for (AppRepo.App a : repo.apps()) if (a.cn.getPackageName().equals(p)) return a.label;
        return null;
    }

    @Override public void returnHome() {
        try { startActivity(new Intent(this, Launcher.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (Exception ignored) { }
    }

    @Override public void needStorage() {
        // ask on first need, and again (once per start) until the user says "don't ask again"
        if (storageAskedThisRun) return;
        if (prefs.getBoolean("askedStorage", false) && !shouldShowRequestPermissionRationale(android.Manifest.permission.READ_EXTERNAL_STORAGE)) return;
        storageAskedThisRun = true;
        prefs.edit().putBoolean("askedStorage", true).apply();
        try { requestPermissions(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE); }
        catch (Throwable ignored) { }
    }

    @Override public void launchPreferred() {
        String p = preferredPackage();
        if (p != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null && start(i)) return;
        }
        chooseMusicAppNow();
    }

    private void chooseMusicAppNow() {
        if (repo.apps().isEmpty()) { clock.flash("LOADING APPS"); return; }
        drawer.show(PICK_PLAYER);
    }

    @Override public void onAppLongPress(final AppRepo.App app) {
        final String pkg = app.cn.getPackageName();
        int flags = 0;
        try { flags = getPackageManager().getApplicationInfo(pkg, 0).flags; } catch (Exception ignored) { }
        boolean system = (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        // built-in apps can't be removed (only their updates); offer what Android will actually allow
        String[] items = !system ? new String[] { "Pin to home screen", "App info", "!Uninstall" }
                : updated ? new String[] { "Pin to home screen", "App info", "!Uninstall updates" }
                : new String[] { "Pin to home screen", "App info" };
        dialog(app.label, items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) pin(app);
                else if (which == 1) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                else if (!start(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + pkg))))
                    start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
            }
        });
    }

    private void pin(final AppRepo.App app) {
        for (int i = 0; i < SLOTS; i++) {
            if (repo.find(slots[i]) == null) {
                saveSlot(i, app.key);
                shortcuts.setPage(i / PER_PAGE, true);
                drawer.close();
                Toast.makeText(this, app.label + " pinned", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String[] names = new String[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            AppRepo.App a = repo.find(slots[i]);
            names[i] = (i + 1) + ".  Replace " + (a == null ? "empty" : a.label);
        }
        dialog("Home is full", names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { saveSlot(which, app.key); drawer.close(); }
        });
    }

    @Override public void onDrawerClosed() { }

    // ---------- MediaCard.Actions ----------

    @Override public void onPrev() { media.prev(); }
    @Override public void onPlayPause() { media.playPause(); }
    @Override public void onNext() { media.next(); }
    @Override public void onSeek(long ms) { media.seekTo(ms); }

    @Override public void onCardTap() {
        if (!MediaHub.hasAccess(this) || !MediaWatcher.connected) { askAccess(); return; }
        String pkg = media.playingPackage();
        if (pkg != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && start(i)) return;
        }
        launchPreferred();
    }

    @Override public void onCardLongPress() {
        String[] items = { "Choose music app" + (playerLabel() == null ? "" : "  (now: " + playerLabel() + ")"),
                           "Music diagnostics" };
        dialog("Music", items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) chooseMusicAppNow(); else showDiagnostics();
            }
        });
    }

    private void showDiagnostics() {
        sheet.message("Music diagnostics",
                "Play a song, then open this again. A photo of this screen shows exactly what your unit reports.\n\n" + media.diagnostics(),
                "Close", null, null);
    }

    private void askAccess() {
        boolean on = MediaHub.hasAccess(this);
        sheet.message(on ? "Reconnect music access" : "Show what's playing",
                on ? "Android has Drift's access switched on but isn't connecting it. On the next screen, switch Drift off and back on. "
                        + "If it still doesn't connect, restart the unit once."
                   : "Turn on Notification access for Drift on the next screen. Drift only reads your music player, never your messages.",
                "Open settings", new Runnable() {
                    @Override public void run() {
                        if (!start(new Intent(NOTIF_SETTINGS))) {
                            start(new Intent(Settings.ACTION_SETTINGS));
                            Toast.makeText(Launcher.this, "Look for Notification access, then enable Drift", Toast.LENGTH_LONG).show();
                        }
                    }
                }, "Not now");
    }

    // ---------- Drift menu ----------

    private void driftMenu() { openSettings(); }

    // ---------- SettingsPanel.Host ----------

    @Override public int accent() { return themeIdx; }
    @Override public void setAccent(int index) {
        themeIdx = ((index % Accent.ALL.length) + Accent.ALL.length) % Accent.ALL.length;
        prefs.edit().putInt("accent", themeIdx).apply();
        applyTheme();
        settings.refresh();
        appearance.refresh();
    }
    @Override public int roadMode() { return roadMode; }
    @Override public void setRoadMode(int mode) {
        roadMode = mode;
        prefs.edit().putInt("road", mode).apply();
        road.setMode(mode);
    }
    @Override public boolean dockGlow() { return glow; }
    @Override public void setDockGlow(boolean on) {
        glow = on;
        prefs.edit().putBoolean("glow", on).apply();
        dockGlow.setOn(on);
    }
    @Override public boolean cardRight() { return flip; }
    @Override public void setCardRight(boolean right) {
        if (right == flip) return;
        flip = right;
        prefs.edit().putBoolean("flip", right).apply();
        plate.set(flip, themeIdx);
        home.move(card, cardX(), 106, 420, 428);
        home.move(shortcuts, tilesX(), 261, 394, 300);
        card.setOnRight(flip);
        clock.setLeftAligned(flip);
        appearance.setOnLeft(flip); // keep the panel off the music card so the change shows
    }
    @Override public boolean mph() { return mph; }
    // ---------- permissions, all in one place ----------

    private boolean[] perms() {
        return new boolean[] { MediaHub.hasAccess(this) && MediaWatcher.connected, SpeedView.hasPermission(this),
                TrackInfo.allowed(this), RecentsService.isEnabled(this) };
    }
    private static final String[] PERM_NAMES = { "Music access", "Location", "Media files", "Recent apps" };
    private static final String[] PERM_USES = { "song names and controls", "speed", "song length and covers", "the button next to Home" };

    @Override public int permissionsOn() { int n = 0; for (boolean b : perms()) if (b) n++; return n; }
    @Override public String permissionsMissing() {
        boolean[] p = perms(); StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length; i++) if (!p[i]) b.append(b.length() == 0 ? "" : ", ").append(PERM_NAMES[i].toLowerCase(Locale.ROOT));
        return b.toString();
    }
    @Override public void openPermissions() {
        final boolean[] p = perms();
        String[] items = new String[p.length];
        for (int i = 0; i < p.length; i++)
            items[i] = (p[i] ? "\u2713  " : "\u2717  ") + PERM_NAMES[i] + "  \u00B7  " + (p[i] ? "on" : "tap to turn on") + "  (" + PERM_USES[i] + ")";
        sheet.list("Permissions", items, new Sheet.OnPick() {
            @Override public void onPick(int i) {
                if (i == 0) askAccess();
                else if (i == 1) allowSpeed();
                else if (i == 2) allowStorage();
                else openRecentsAccess();
            }
        });
    }
    private void allowStorage() {
        String perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (TrackInfo.allowed(this) || shouldShowRequestPermissionRationale(perm) || !prefs.getBoolean("askedStorage", false)) {
            prefs.edit().putBoolean("askedStorage", true).apply();
            if (TrackInfo.allowed(this)) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            else requestPermissions(new String[] { perm }, REQ_STORAGE);
        } else {
            start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Turn on Storage under Permissions", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- updates ----------

    private final Updater.Listener updates = new Updater.Listener() {
        @Override public void onUpdateState() {
            settings.refresh();
            int code = Updater.latestCode(Launcher.this);
            if (Updater.available(Launcher.this) && prefs.getInt("toldUpdate", 0) != code) {
                prefs.edit().putInt("toldUpdate", code).apply();
                clock.flash("UPDATE READY  \u00B7  SETTINGS");
            }
        }
    };

    @Override public String versionName() { return Updater.installedName(this); }
    @Override public boolean updateReady() { return Updater.available(this) && Updater.state() != Updater.DOWNLOADING; }
    @Override public String updateHint() {
        if (!Updater.configured()) return "Designed and built by Babar Manzoor";
        switch (Updater.state()) {
            case Updater.CHECKING: return "Checking for updates\u2026";
            case Updater.DOWNLOADING: return "Downloading update\u2026 " + Updater.progress() + "%";
            case Updater.FAILED: return Updater.error() + ". Tap to try again";
            default:
                if (Updater.available(this)) return "Update ready (build " + Updater.latestCode(this) + "). Tap to install";
                long at = Updater.checkedAt(this);
                if (at == 0) return "By Babar Manzoor. Tap to check for updates";
                long min = Math.max(0, (System.currentTimeMillis() - at) / 60000);
                String ago = min < 1 ? "just now" : min < 60 ? min + " min ago" : min < 1440 ? (min / 60) + " h ago" : (min / 1440) + " d ago";
                return "Up to date, checked " + ago + ". Tap to check";
        }
    }
    @Override public void updateTap() {
        if (!Updater.configured()) return;
        if (Updater.available(this)) {
            String notes = Updater.latestNotes(this);
            sheet.message("Update Drift", (notes.isEmpty() ? "A new build of Drift is ready." : notes)
                            + "\n\nIt downloads now (about half a megabyte), then Android asks you to confirm the install. Your shortcuts and settings stay.",
                    "Download and install", new Runnable() { @Override public void run() { Updater.download(Launcher.this, updates); } }, "Later");
        } else Updater.check(this, updates);
    }

    @Override public boolean speedAllowed() { return SpeedView.hasPermission(this); }
    @Override public void allowSpeed() {
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION) || !prefs.getBoolean("askedLocation", false)) {
            prefs.edit().putBoolean("askedLocation", true).apply();
            requestPermissions(new String[] { android.Manifest.permission.ACCESS_FINE_LOCATION }, REQ_LOCATION);
        } else {
            // "don't ask again" was chosen (or the dialog never showed): the app's settings page can always change it
            start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Turn on Location under Permissions", Toast.LENGTH_LONG).show();
        }
    }
    @Override public void setMph(boolean b) {
        mph = b;
        prefs.edit().putBoolean("mph", b).apply();
        speed.setMph(b);
    }
    @Override public String musicApp() { return playerLabel(); }
    @Override public void chooseMusicApp() { chooseMusicAppNow(); }
    @Override public boolean musicAccess() { return MediaHub.hasAccess(this); }
    @Override public void openMusicAccess() { askAccess(); }
    @Override public void openDiagnostics() { showDiagnostics(); }
    @Override public void openAndroidSettings() { start(new Intent(Settings.ACTION_SETTINGS)); }
    @Override public void openBluetooth() { if (!start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) start(new Intent(Settings.ACTION_SETTINGS)); }
    @Override public boolean recentsOn() { return RecentsService.isEnabled(this); }
    @Override public void openRecentsAccess() {
        sheet.message("Recent apps button",
                "Android only lets Drift open the recent apps screen through Accessibility. On the next screen, open "
                        + "\"Drift recent apps button\" and switch it on. It doesn't read the screen; it only opens recent apps when you tap the button.",
                "Open Accessibility", new Runnable() {
                    @Override public void run() {
                        if (!start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) start(new Intent(Settings.ACTION_SETTINGS));
                    }
                }, "Not now");
    }
    @Override public void openAppearance() { settings.hideNow(); appearance.show(); }
    @Override public void openSettings() { appearance.close(); settings.show(); }
    @Override public void closePanels() { if (appearance.isOpen()) appearance.close(); if (settings.isOpen()) settings.close(); }
    @Override public void resetShortcuts() {
        sheet.list("Reset shortcuts?", new String[] { "!Reset to suggested apps", "Cancel" }, new Sheet.OnPick() {
            @Override public void onPick(int i) {
                if (i != 0) return;
                prefs.edit().putBoolean("seeded", false).apply();
                if (repo.apps().isEmpty()) return; // still loading; it will seed when ready
                for (int j = 0; j < SLOTS; j++) slots[j] = null;
                seedShortcuts(repo.apps());
                bindTiles();
                shortcuts.setPage(0, true);
                closePanels();
            }
        });
    }

    private void dialog(String title, String[] items, final DialogInterface.OnClickListener l) {
        sheet.list(title, items, new Sheet.OnPick() {
            @Override public void onPick(int i) { l.onClick(null, i); }
        });
    }
}
