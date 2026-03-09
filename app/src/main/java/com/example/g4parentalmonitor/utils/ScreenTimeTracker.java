package com.example.g4parentalmonitor.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * ScreenTimeTracker — Real-time screen time accumulator
 * ======================================================
 * Written to by LiveGuardianService (Accessibility) on every window change.
 * Read by UsageStatsHelper when displaying usage data.
 *
 * Uses SharedPreferences as persistent storage so data survives service restarts.
 * Resets automatically at midnight.
 *
 * Thread-safe via synchronized methods.
 */
public class ScreenTimeTracker {

    private static final String TAG = "ScreenTimeTracker";
    private static final String PREFS_NAME = "screen_time_tracker";
    private static final String KEY_DATE = "tracking_date";
    private static final String KEY_PREFIX = "pkg_ms_";
    private static final String KEY_CURRENT_PKG = "current_pkg";
    private static final String KEY_CURRENT_START = "current_start";
    private static final String KEY_SCREEN_ON = "screen_on";

    private static ScreenTimeTracker instance;

    private final SharedPreferences prefs;

    // In-memory cache for performance (flushed to prefs periodically and on session close)
    private final Map<String, Long> sessionCache = new HashMap<>();

    private ScreenTimeTracker(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        checkAndResetForNewDay();
    }

    public static synchronized ScreenTimeTracker getInstance(Context context) {
        if (instance == null) {
            instance = new ScreenTimeTracker(context.getApplicationContext());
        }
        return instance;
    }

    // ─── Called by LiveGuardianService ───────────────────────────────────────

    /**
     * Call when a new app window comes to foreground.
     * Closes the previous app's session and opens one for the new app.
     */
    public synchronized void onAppForegrounded(String newPkg) {
        checkAndResetForNewDay();

        long now = System.currentTimeMillis();
        String prevPkg = prefs.getString(KEY_CURRENT_PKG, null);
        long prevStart = prefs.getLong(KEY_CURRENT_START, 0);

        // Close previous session
        if (prevPkg != null && prevStart > 0 && isScreenOn()) {
            long dur = now - prevStart;
            if (dur > 500 && dur < 3L * 60 * 60 * 1000) { // 500ms min, 3h max
                addToCache(prevPkg, dur);
                flushCacheToPrefs();
                Log.d(TAG, "Closed session: " + prevPkg + " = " + (dur / 1000) + "s");
            }
        }

        // Open new session
        prefs.edit()
                .putString(KEY_CURRENT_PKG, newPkg)
                .putLong(KEY_CURRENT_START, now)
                .apply();

        Log.d(TAG, "Opened session: " + newPkg);
    }

    /**
     * Call when screen turns off or device is locked.
     * Closes any open session — we don't count screen-off time.
     */
    public synchronized void onScreenOff() {
        checkAndResetForNewDay();
        closeCurrentSession();
        prefs.edit().putBoolean(KEY_SCREEN_ON, false).apply();
        Log.d(TAG, "Screen off — session closed");
    }

    /**
     * Call when screen turns on or device is unlocked.
     */
    public synchronized void onScreenOn() {
        prefs.edit().putBoolean(KEY_SCREEN_ON, true).apply();
        // Don't open a session yet — wait for onAppForegrounded
        Log.d(TAG, "Screen on");
    }

    // ─── Called by UsageStatsHelper ──────────────────────────────────────────

    /**
     * Returns accumulated milliseconds per package for today (since midnight).
     * Includes the currently-open live session.
     */
    public synchronized Map<String, Long> getTodayUsageMs() {
        checkAndResetForNewDay();

        // Load all saved data from prefs into result map
        Map<String, Long> result = new HashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                String pkg = entry.getKey().substring(KEY_PREFIX.length());
                Object val = entry.getValue();
                if (val instanceof Long) {
                    result.put(pkg, (Long) val);
                }
            }
        }

        // Merge in-memory cache (may have unsaved data)
        for (Map.Entry<String, Long> entry : sessionCache.entrySet()) {
            long existing = result.containsKey(entry.getKey()) ? result.get(entry.getKey()) : 0L;
            result.put(entry.getKey(), existing + entry.getValue());
        }

        // Add live session top-up for currently open app
        String currentPkg = prefs.getString(KEY_CURRENT_PKG, null);
        long currentStart = prefs.getLong(KEY_CURRENT_START, 0);
        if (currentPkg != null && currentStart > 0 && isScreenOn()) {
            long liveMs = System.currentTimeMillis() - currentStart;
            if (liveMs > 500 && liveMs < 3L * 60 * 60 * 1000) {
                long existing = result.containsKey(currentPkg) ? result.get(currentPkg) : 0L;
                result.put(currentPkg, existing + liveMs);
            }
        }

        return result;
    }

    /**
     * Returns true if the accessibility tracker has any data for today.
     * UsageStatsHelper uses this to decide whether to use tracker or fall back to UsageStats.
     */
    public synchronized boolean hasDataForToday() {
        checkAndResetForNewDay();
        Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_PREFIX)) return true;
        }
        return !sessionCache.isEmpty();
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private void closeCurrentSession() {
        long now = System.currentTimeMillis();
        String pkg = prefs.getString(KEY_CURRENT_PKG, null);
        long start = prefs.getLong(KEY_CURRENT_START, 0);

        if (pkg != null && start > 0) {
            long dur = now - start;
            if (dur > 500 && dur < 3L * 60 * 60 * 1000) {
                addToCache(pkg, dur);
                flushCacheToPrefs();
            }
        }

        prefs.edit()
                .remove(KEY_CURRENT_PKG)
                .remove(KEY_CURRENT_START)
                .apply();
    }

    private void addToCache(String pkg, long ms) {
        long current = sessionCache.containsKey(pkg) ? sessionCache.get(pkg) : 0L;
        sessionCache.put(pkg, current + ms);
    }

    private void flushCacheToPrefs() {
        if (sessionCache.isEmpty()) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Long> entry : sessionCache.entrySet()) {
            String key = KEY_PREFIX + entry.getKey();
            long existing = prefs.getLong(key, 0L);
            editor.putLong(key, existing + entry.getValue());
        }
        editor.apply();
        sessionCache.clear();
    }

    private boolean isScreenOn() {
        return prefs.getBoolean(KEY_SCREEN_ON, true); // default true (assume on)
    }

    /**
     * Resets all data if today's date doesn't match the stored tracking date.
     * This ensures we start fresh at midnight every day.
     */
    private void checkAndResetForNewDay() {
        Calendar cal = Calendar.getInstance();
        // Store date as YYYYMMDD integer
        int today = cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);

        int storedDate = prefs.getInt(KEY_DATE, 0);

        if (storedDate != today) {
            Log.d(TAG, "New day detected — resetting tracker. Previous date: " + storedDate);
            // Clear everything except screen state
            SharedPreferences.Editor editor = prefs.edit();
            Map<String, ?> all = prefs.getAll();
            for (String key : all.keySet()) {
                if (!key.equals(KEY_SCREEN_ON)) {
                    editor.remove(key);
                }
            }
            editor.putInt(KEY_DATE, today);
            editor.apply();
            sessionCache.clear();
        }
    }
}