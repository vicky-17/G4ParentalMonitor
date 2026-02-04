package com.example.g4parentalmonitor;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsageStatsHelper {

    public static List<Map<String, Object>> getRecentAppUsage(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = context.getPackageManager();

        // 1. Calculate Start Time (Midnight Today)
        Calendar calendar = Calendar.getInstance();
        long endTime = calendar.getTimeInMillis(); // NOW
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis(); // Midnight

        // 2. Query Events instead of aggregated Stats
        // queryEvents gives us the raw stream of app opens/closes
        UsageEvents usageEvents = usm.queryEvents(startTime, endTime);

        Map<String, Long> appUsageMap = new HashMap<>();
        Map<String, Long> startTimes = new HashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();

        // 3. Process events linearly
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event);
            String pkg = event.getPackageName();

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // App opened: record the timestamp
                startTimes.put(pkg, event.getTimeStamp());
            } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                // App closed: calculate duration if we have a start time
                if (startTimes.containsKey(pkg)) {
                    long startTimeSession = startTimes.get(pkg);
                    long duration = event.getTimeStamp() - startTimeSession;
                    appUsageMap.put(pkg, appUsageMap.getOrDefault(pkg, 0L) + duration);
                    startTimes.remove(pkg); // Clear start time to wait for next open
                }
            }
        }

        // 4. Handle apps currently in foreground (Open right now)
        // If an app is still in startTimes, it means it hasn't closed yet.
        for (Map.Entry<String, Long> entry : startTimes.entrySet()) {
            String pkg = entry.getKey();
            long sessionStart = entry.getValue();
            long duration = endTime - sessionStart; // Time from open until NOW
            appUsageMap.put(pkg, appUsageMap.getOrDefault(pkg, 0L) + duration);
        }

        // 5. Build the result list
        List<Map<String, Object>> appUsageList = new ArrayList<>();
        for (Map.Entry<String, Long> entry : appUsageMap.entrySet()) {
            String packageName = entry.getKey();
            long totalTimeMs = entry.getValue();

            // Filter out tiny usage (e.g., less than 1 second) if desired, or keep all.
            if (totalTimeMs > 0) {
                String appName = packageName;
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    appName = pm.getApplicationLabel(appInfo).toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                    // If we can't find the app name, we might want to skip it (system process)
                    // or just use the package name.
                }

                Map<String, Object> appData = new HashMap<>();
                appData.put("appName", appName);
                appData.put("packageName", packageName);

                // Use Math.ceil to ensure 30 seconds counts as 1 minute rather than 0
                appData.put("minutes", (int) Math.ceil(totalTimeMs / 1000.0 / 60.0));

                appData.put("category", "General");
                appUsageList.add(appData);
            }
        }
        return appUsageList;
    }
}