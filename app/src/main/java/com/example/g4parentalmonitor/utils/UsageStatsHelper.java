package com.example.g4parentalmonitor.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

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

    private static final String TAG = "UsageStatsHelper";

    /**
     * TRANSPARENT PACKAGES — system UI and launchers.
     * Both tracker and event-based ignore these when they take foreground.
     */
    private static final Set<String> TRANSPARENT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui", "android",
            "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
            "com.miui.home", "com.miui.systemui", "com.miui.securityinputmethod",
            "com.google.android.launcher", "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher", "com.oneplus.launcher",
            "com.oppo.launcher", "com.bbk.launcher2",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller"
    ));

    private static final long MIN_SESSION_MS    = 1_000;
    private static final long INACTIVITY_CAP_MS = 30L * 60 * 1_000;
    private static final long MAX_SESSION_MS    = 3L * 60 * 60 * 1_000;

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
            long minutes = (totalMs + 30_000L) / 60_000L;

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

    /**
     * PRIMARY ENTRY POINT
     *
     * Strategy:
     *   1. If ScreenTimeTracker (accessibility) has data → use it as PRIMARY source.
     *      It tracks in real-time via TYPE_WINDOW_STATE_CHANGED, much more accurate.
     *   2. Merge with event-based as FALLBACK for any apps the tracker may have missed
     *      (e.g. apps used before accessibility service was enabled today).
     *   3. For each app: take MAX(tracker, event-based).
     *      The tracker is always more accurate when running, event-based covers gaps.
     */
    public static Map<String, Long> getEventBasedDailyUsage(Context context) {
        ScreenTimeTracker tracker = ScreenTimeTracker.getInstance(context);

        // Get accessibility tracker data (real-time, most accurate)
        Map<String, Long> trackerMap = tracker.getTodayUsageMs();

        // Get event-based data (fallback / gap filler)
        Map<String, Long> eventMap = computeEventBased(context);

        boolean trackerHasData = tracker.hasDataForToday();

        if (!trackerHasData) {
            // Accessibility service not yet enabled or no data yet — use event-based only
            Log.d(TAG, "No tracker data — using event-based only");
            return eventMap;
        }

        // Merge: for each app take MAX(tracker, event-based)
        // The tracker is real-time accurate; event-based fills in anything before
        // the accessibility service was started today.
        Map<String, Long> merged = new HashMap<>(trackerMap);

        for (Map.Entry<String, Long> entry : eventMap.entrySet()) {
            String pkg = entry.getKey();
            long eventMs = entry.getValue();

            if (TRANSPARENT_PACKAGES.contains(pkg)) continue;

            long trackerMs = merged.containsKey(pkg) ? merged.get(pkg) : 0L;

            if (trackerMs == 0) {
                // Tracker has no data for this app — use event-based (app used before service start)
                merged.put(pkg, eventMs);
            } else {
                // Both have data: take max
                // Tracker is more accurate in most cases, but if event-based is significantly
                // higher it means the tracker missed some time (e.g. during a restart)
                merged.put(pkg, Math.max(trackerMs, eventMs));
            }
        }

        // Remove transparent packages from final result
        for (String pkg : TRANSPARENT_PACKAGES) {
            merged.remove(pkg);
        }

        Log.d(TAG, "Merged tracker (" + trackerMap.size() + " apps) + event-based ("
                + eventMap.size() + " apps) → " + merged.size() + " apps");

        return merged;
    }

    /**
     * FALLBACK: Pure event-based from midnight.
     * MIUI-safe: "only one real app in foreground at a time" rule.
     */
    private static Map<String, Long> computeEventBased(Context context) {
        UsageStatsManager usm = getUsageStatsManager(context);

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
        String fgPkg = null;
        long fgStart = 0;
        long lastEventTs = midnight;

        UsageEvents.Event ev = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(ev);

            long ts   = ev.getTimeStamp();
            int  type = ev.getEventType();
            String pkg = ev.getPackageName();

            if (ts < midnight) continue;

            if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                if (fgPkg != null) {
                    long dur = ts - fgStart;
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                    fgPkg = null; fgStart = 0;
                }
                lastEventTs = ts;
                continue;
            }

            if (type == UsageEvents.Event.SCREEN_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                lastEventTs = ts;
                continue;
            }

            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (TRANSPARENT_PACKAGES.contains(pkg)) continue;

                if (fgPkg != null && !fgPkg.equals(pkg)) {
                    long dur;
                    long gap = ts - lastEventTs;
                    if (gap > INACTIVITY_CAP_MS) {
                        dur = Math.max(0, (lastEventTs + INACTIVITY_CAP_MS) - fgStart);
                    } else {
                        dur = ts - fgStart;
                    }
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                }

                fgPkg = pkg;
                fgStart = ts;
                lastEventTs = ts;

            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (TRANSPARENT_PACKAGES.contains(pkg)) continue;
                if (pkg.equals(fgPkg)) {
                    long dur = ts - fgStart;
                    if (dur >= MIN_SESSION_MS && dur < MAX_SESSION_MS)
                        addTime(totalMap, fgPkg, dur);
                    fgPkg = null; fgStart = 0;
                }
                lastEventTs = ts;
            }
        }

        // Live top-up
        if (fgPkg != null) {
            long timeSinceLast = now - lastEventTs;
            long sessionDur = timeSinceLast > INACTIVITY_CAP_MS
                    ? (lastEventTs + INACTIVITY_CAP_MS) - fgStart
                    : now - fgStart;
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