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
        long startTime = calendar.getTimeInMillis();

        // CHANGE: Use queryAndAggregateUsageStats instead of queryUsageStats(INTERVAL_DAILY).
        // This fixes the issue where "high time" was reported because INTERVAL_DAILY
        // might return intervals that overlap with yesterday or return duplicate entries.
        // This method automatically merges data to fit the best interval for the range.
        Map<String, UsageStats> usageStatsMap = usm.queryAndAggregateUsageStats(startTime, endTime);

        List<Map<String, Object>> appUsageList = new ArrayList<>();

        if (usageStatsMap != null) {
            for (UsageStats stats : usageStatsMap.values()) {
                long totalTimeMs = stats.getTotalTimeInForeground();

                if (totalTimeMs > 0) {
                    try {
                        ApplicationInfo appInfo = pm.getApplicationInfo(stats.getPackageName(), 0);
                        Map<String, Object> appData = new HashMap<>();
                        String appName = pm.getApplicationLabel(appInfo).toString();

                        appData.put("appName", appName);
                        appData.put("packageName", stats.getPackageName());
                        appData.put("minutes", totalTimeMs / 1000 / 60);
                        appData.put("category", "General");

                        appUsageList.add(appData);
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // Still include even if label is missing
                        Map<String, Object> appData = new HashMap<>();
                        appData.put("appName", stats.getPackageName());
                        appData.put("packageName", stats.getPackageName());
                        appData.put("minutes", totalTimeMs / 1000 / 60);
                        appUsageList.add(appData);
                    }
                }
            }
        }
        return appUsageList;
    }
}