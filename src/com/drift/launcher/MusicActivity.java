/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** "Drift Music" in the app list: opens the player on Drift's home screen. */
public class MusicActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startActivity(new Intent(this, Launcher.class).setAction(Launcher.ACTION_MUSIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Throwable ignored) { }
        finish();
        overridePendingTransition(0, 0);
    }
}
