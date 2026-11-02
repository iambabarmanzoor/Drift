/*
 * Drift launcher
 * Copyright (c) 2026 Babar Manzoor. All rights reserved.
 */
package com.drift.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens Android's own recent apps screen for the button next to Home. Android only lets
 * other apps do this through an accessibility service; this one listens to nothing and
 * reads nothing, it only presses "Recents" when you tap the button.
 */
public class RecentsService extends AccessibilityService {
    private static volatile RecentsService instance;

    @Override protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = 0;                       // no events: we don't watch the screen
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 1000;
        try { setServiceInfo(info); } catch (Throwable ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    @Override public boolean onUnbind(android.content.Intent i) { if (instance == this) instance = null; return super.onUnbind(i); }

    /** True when the user has switched the service on in Accessibility settings. */
    public static boolean isEnabled(Context c) {
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) return false;
            ComponentName me = new ComponentName(c, RecentsService.class);
            for (String s : list.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(s);
                if (me.equals(cn)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Open the recent apps screen. Returns false if the service isn't running. */
    public static boolean showRecents() {
        RecentsService s = instance;
        if (s == null) return false;
        try { return s.performGlobalAction(GLOBAL_ACTION_RECENTS); } catch (Throwable t) { return false; }
    }
}
