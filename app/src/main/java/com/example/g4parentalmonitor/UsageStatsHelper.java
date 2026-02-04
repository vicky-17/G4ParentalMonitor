package com.example.g4parentalmonitor;

import android.app.usage.UsageStats;
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

        Calendar calendar = Calendar.getInstance();
        long endTime = calendar.getTimeInMillis();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis();

        // Use INTERVAL_DAILY to get explicit daily buckets for accuracy
        List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        Map<String, UsageStats> aggregatedStats = new HashMap<>();
        if (statsList != null) {
            for (UsageStats stats : statsList) {
                // Fix: Changed getBeginTime() to getFirstTimeStamp()
                // This ensures we only include data that falls within today's range
                if (stats.getFirstTimeStamp() >= startTime || stats.getLastTimeUsed() >= startTime) {
                    String pkg = stats.getPackageName();
                    // Merge stats to ensure we take the most recent/relevant bucket for each app
                    if (!aggregatedStats.containsKey(pkg) ||
                            stats.getTotalTimeInForeground() > aggregatedStats.get(pkg).getTotalTimeInForeground()) {
                        aggregatedStats.put(pkg, stats);
                    }
                }
            }
        }

        List<Map<String, Object>> appUsageList = new ArrayList<>();
        for (UsageStats stats : aggregatedStats.values()) {
            long totalTimeMs = stats.getTotalTimeInForeground();

            if (totalTimeMs > 0) {
                String packageName = stats.getPackageName();
                String appName = packageName;
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    appName = pm.getApplicationLabel(appInfo).toString();
                } catch (PackageManager.NameNotFoundException ignored) {}

                Map<String, Object> appData = new HashMap<>();
                appData.put("appName", appName);
                appData.put("packageName", packageName);
                // Use Math.ceil to prevent rounding down (e.g., 50 seconds becoming 0 minutes)
                appData.put("minutes", (int) Math.ceil(totalTimeMs / 1000.0 / 60.0));
                appData.put("category", "General");
                appUsageList.add(appData);
            }
        }
        return appUsageList;
    }
}