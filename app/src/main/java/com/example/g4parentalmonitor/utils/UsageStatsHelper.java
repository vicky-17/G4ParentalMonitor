package com.example.g4parentalmonitor.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UsageStatsHelper {

    /**
     * FINAL APPROACH — Pure event-based, midnight to now.
     * NO queryUsageStats(INTERVAL_DAILY) — it bleeds yesterday's data on MIUI.
     *
     * Core rules:
     *  1. Only MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND events used.
     *  2. "One real app in foreground at a time" — when app A gets FOREGROUND,
     *     close app B's session. No need for BACKGROUND to fire (MIUI fix).
     *  3. TRANSPARENT_PACKAGES (systemui, launcher etc.) are invisible —
     *     they don't close or open real app sessions.
     *  4. SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN closes all sessions.
     *  5. Inactivity cap: if no event for >30min, cap the session there
     *     (handles MIUI missing SCREEN_OFF events).
     *  6. Hard max: 3h per single continuous session.
     *  7. Live top-up for the currently open app.
     *
     * WHY THE 10-MINUTE WHATSAPP GAP EXISTED:
     *  - It was the live session not being counted: WhatsApp was open when
     *    we refreshed, and the MOVE_TO_FOREGROUND already happened but
     *    no BACKGROUND had fired yet. The live top-up handles this.
     *  - The remaining small difference from Digital Wellbeing is expected:
     *    DW is a system app and counts foreground service time too.
     *    We cannot replicate that exactly as a third-party app.
     */

    private static final Set<String> TRANSPARENT_PACKAGES = new HashSet<>(Arrays.asList(
            // Android core
            "com.android.systemui",
            "android",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            // MIUI / Xiaomi
            "com.miui.home",
            "com.miui.systemui",
            "com.miui.securityinputmethod",
            "com.miui.miwallpaper",
            "com.xiaomi.mi_connect_service",
            // Google launcher
            "com.google.android.launcher",
            "com.google.android.apps.nexuslauncher",
            // Other OEM launchers
            "com.sec.android.app.launcher",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2"
    ));

    private static final long MIN_SESSION_MS    = 1_000;             // 1 second minimum
    private static final long INACTIVITY_CAP_MS = 30L * 60 * 1_000; // 30 minutes
    private static final long MAX_SESSION_MS    = 3L * 60 * 60 * 1_000; // 3 hours

    private static UsageStatsManager getUsageStatsManager(Context context) {
        return (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public static List<Map<String, Object>> getTodayUsageMinutes(Context context) {
        return getUsageStats(context, true);
    }

    public static List<Map<String, Object>> getRawDailyUsageStats(Context context) {
        return getUsageStats(context, false);
    }

    private static List<Map<String, Object>> getUsageStats(Context context, boolean filterSystem) {
        PackageManager pm = context.getPackageManager();
        List<Map<String, Object>> result = new ArrayList<>();

        Map<String, Long> usageMap = getEventBasedDailyUsage(context);
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        for (ApplicationInfo app : apps) {
            String pkg = app.packageName;

            if (filterSystem) {
                boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean hasLaunchIntent = pm.getLaunchIntentForPackage(pkg) != null;
                if (isSystem && !hasLaunchIntent) continue;
            }

            long totalMs = usageMap.containsKey(pkg) ? usageMap.get(pkg) : 0L;
            if (totalMs == 0) continue;

            long totalSeconds = totalMs / 1_000;
            long minutes = (totalMs + 30_000L) / 60_000L; // round, not floor

            Map<String, Object> data = new HashMap<>();
            data.put("packageName", pkg);
            data.put("appName", pm.getApplicationLabel(app).toString());
            data.put("minutes", minutes);
            data.put("seconds", totalSeconds);
            data.put("totalMs", totalMs);
            result.add(data);
        }

        Collections.sort(result, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> o1, Map<String, Object> o2) {
                return ((Long) o2.get("totalMs")).compareTo((Long) o1.get("totalMs"));
            }
        });

        return result;
    }

    public static Map<String, Long> getEventBasedDailyUsage(Context context) {
        UsageStatsManager usm = getUsageStatsManager(context);

        // Exact local midnight
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        long now = System.currentTimeMillis();

        UsageEvents events = usm.queryEvents(midnight, now);
        if (events == null) return new HashMap<>();

        Map<String, Long> totalMap = new HashMap<>();

        // The one real app currently in foreground
        String fgPkg = null;
        long fgStart  = 0;

        // Timestamp of the last meaningful event (for inactivity cap)
        long lastEventTs = midnight;

        UsageEvents.Event ev = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(ev);

            long ts   = ev.getTimeStamp();
            int  type = ev.getEventType();
            String pkg = ev.getPackageName();

            if (ts < midnight) continue; // safety guard

            // ── SCREEN OFF / LOCK ────────────────────────────────────────────
            if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                if (fgPkg != null) {
                    long dur = ts - fgStart;
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                    fgPkg = null;
                    fgStart = 0;
                }
                lastEventTs = ts;
                continue;
            }

            // ── SCREEN ON / UNLOCK ───────────────────────────────────────────
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                lastEventTs = ts;
                continue;
            }

            // ── APP FOREGROUND ───────────────────────────────────────────────
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {

                // Ignore transparent system packages
                if (TRANSPARENT_PACKAGES.contains(pkg)) continue;

                // Close previous real app session
                if (fgPkg != null && !fgPkg.equals(pkg)) {
                    long dur;
                    long gap = ts - lastEventTs;
                    if (gap > INACTIVITY_CAP_MS) {
                        // Long gap since last event — screen was probably off.
                        // Cap session at lastEventTs + INACTIVITY_CAP_MS.
                        long cappedEnd = lastEventTs + INACTIVITY_CAP_MS;
                        dur = Math.max(0, cappedEnd - fgStart);
                    } else {
                        dur = ts - fgStart;
                    }
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                }

                // Start new session
                fgPkg  = pkg;
                fgStart = ts;
                lastEventTs = ts;
            }

            // ── APP BACKGROUND ───────────────────────────────────────────────
            // Still handled for devices that fire it correctly (non-MIUI).
            else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (TRANSPARENT_PACKAGES.contains(pkg)) continue;

                if (pkg.equals(fgPkg)) {
                    long dur = ts - fgStart;
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                    fgPkg  = null;
                    fgStart = 0;
                }
                lastEventTs = ts;
            }
        }

        // ── LIVE TOP-UP ──────────────────────────────────────────────────────
        // The app currently open has no BACKGROUND event yet — add live time.
        if (fgPkg != null) {
            long timeSinceLast = now - lastEventTs;
            long sessionDur;

            if (timeSinceLast > INACTIVITY_CAP_MS) {
                // Screen likely went off without us seeing SCREEN_NON_INTERACTIVE.
                // Only count up to INACTIVITY_CAP_MS past the last real event.
                sessionDur = (lastEventTs + INACTIVITY_CAP_MS) - fgStart;
            } else {
                sessionDur = now - fgStart;
            }

            if (sessionDur >= MIN_SESSION_MS && sessionDur < MAX_SESSION_MS)
                addTime(totalMap, fgPkg, sessionDur);
        }

        return totalMap;
    }

    private static void addTime(Map<String, Long> map, String pkg, long ms) {
        long cur = map.containsKey(pkg) ? map.get(pkg) : 0L;
        map.put(pkg, cur + ms);
    }
}