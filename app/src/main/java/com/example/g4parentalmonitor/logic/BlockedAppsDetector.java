package com.example.g4parentalmonitor.logic;

import com.example.g4parentalmonitor.data.PrefsManager;

public class BlockedAppsDetector {
    private PrefsManager prefs;

    public BlockedAppsDetector(PrefsManager prefs) {
        this.prefs = prefs;
    }

    public boolean shouldBlockApp(String packageName) {
        // Checks if the current app is in the blocked list
        return prefs != null && prefs.isAppBlocked(packageName);
    }
}