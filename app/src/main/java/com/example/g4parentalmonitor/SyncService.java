package com.example.g4parentalmonitor;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.gson.Gson;
import okhttp3.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;



public class SyncService extends Service {

    // --- CONFIG ---
    private static final String BASE_URL = Constants.BASE_URL + "/api";
    private static final double DISTANCE_THRESHOLD_METERS = 1.0;

    // Configurable sync interval - always send data every 2 minutes
    private static final long APP_SYNC_INTERVAL_MS = 120000; // 2 minutes
    private static final long BROWSER_SYNC_INTERVAL_MS = 30000; // Check for new URLs every 30 seconds

    // --- HELPERS ---
    private LocationHelper locationHelper;

    // --- NETWORK & TOOLS ---
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PrefsManager prefs;

    // Notification management
    private NotificationManager notificationManager;
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new PrefsManager(this);
        locationHelper = new LocationHelper(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        startConfigLoop();
        startLocationLoop();
        startAppUsageLoop();
        startBrowserSyncLoop(); // <--- Added this

        startNotificationMonitor();
        ServiceRestartJob.scheduleJob(this);
        startBlockedAppsSyncLoop();

        Log.d("SyncService", "🚀 Service started successfully with auto-restart protection");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    // =========================================================
    // 📍 1. LOCATION SYNC LOOP
    // =========================================================
    private final Runnable locationRunnable = new Runnable() {
        @Override
        public void run() {
            locationHelper.fetchCurrentLocation(location -> {
                sendLocationData(location);
            });
            long interval = (getBatteryLevel() < 15) ? (30 * 60 * 1000) : 45000;
            handler.postDelayed(this, interval);
        }
    };

    private void sendLocationData(Location loc) {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                if (prefs.hasLastSentLocation()) {
                    double dist = calculateDistance(prefs.getLastSentLatitude(), prefs.getLastSentLongitude(), loc.getLatitude(), loc.getLongitude());
                    if (dist < DISTANCE_THRESHOLD_METERS) return;
                }

                Map<String, Object> data = new HashMap<>();
                data.put("deviceId", deviceId);
                data.put("latitude", loc.getLatitude());
                data.put("longitude", loc.getLongitude());
                data.put("batteryLevel", getBatteryLevel());

                RequestBody body = RequestBody.create(gson.toJson(data), MediaType.get("application/json"));
                Request req = new Request.Builder().url(BASE_URL + "/location").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful()) {
                        prefs.saveLastSentLocation(loc.getLatitude(), loc.getLongitude());
                        Log.d("SyncService", "Location Sent ✅");
                    }
                }
            } catch (Exception e) { Log.e("SyncService", "Loc Send Failed", e); }
        }).start();
    }

    // =========================================================
    // 📱 2. APP USAGE SYNC LOOP
    // =========================================================
    private final Runnable appUsageRunnable = new Runnable() {
        @Override
        public void run() {
            syncApps();
            handler.postDelayed(this, APP_SYNC_INTERVAL_MS);
        }
    };

    private void syncApps() {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                List<Map<String, Object>> currentApps = UsageStatsHelper.getTodayUsageMinutes(this);
                if (currentApps == null || currentApps.isEmpty()) return;

                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("apps", currentApps);

                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                Request req = new Request.Builder().url(BASE_URL + "/apps").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful()) {
                        Log.d("SyncService", "✅ Apps Synced (" + currentApps.size() + ")");
                    } else {
                        Log.e("SyncService", "❌ App Sync failed: " + res.code());
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ App Sync Failed", e);
            }
        }).start();
    }

    // =========================================================
    // 🌐 3. BROWSER HISTORY SYNC LOOP (NEW)
    // =========================================================
    private final Runnable browserSyncRunnable = new Runnable() {
        @Override
        public void run() {
            syncBrowserHistory();
            handler.postDelayed(this, BROWSER_SYNC_INTERVAL_MS);
        }
    };

    private void syncBrowserHistory() {
        new Thread(() -> {
            try {
                List<String> urlsToSend;

                // 1. Safely retrieve and clear the list from SettingsBlockerService
                // Ensure SettingsBlockerService.visitedUrls is public static in that file
                synchronized (SettingsBlockerService.visitedUrls) {
                    if (SettingsBlockerService.visitedUrls.isEmpty()) return;

                    // Create a copy to send
                    urlsToSend = new ArrayList<>(SettingsBlockerService.visitedUrls);
                    // Clear the original list so we don't send duplicates
                    SettingsBlockerService.visitedUrls.clear();
                }

                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                // 2. Prepare JSON payload
                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("history", urlsToSend); // Array of "url|timestamp" strings

                // 3. Send to Server
                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                Request req = new Request.Builder().url(BASE_URL + "/browser-history").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful()) {
                        Log.d("SyncService", "✅ Browser History Synced (" + urlsToSend.size() + ")");
                    } else {
                        Log.e("SyncService", "❌ History Sync failed: " + res.code());
                        // Optional: Could add items back to list if failed, but risky for infinite loops
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ Browser Sync Error", e);
            }
        }).start();
    }

    // =========================================================
    // ⚙️ 4. SETTINGS SYNC LOOP
    // =========================================================
    private final Runnable configRunnable = new Runnable() {
        @Override
        public void run() {
            syncSettings();
            handler.postDelayed(this, 60000);
        }
    };

    private void syncSettings() {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;
                Request req = new Request.Builder().url(BASE_URL + "/settings/" + deviceId).get().build();
                client.newCall(req).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

    // --- UTILITIES ---
    private void startLocationLoop() { handler.post(locationRunnable); }
    private void startAppUsageLoop() { handler.post(appUsageRunnable); }
    private void startConfigLoop() { handler.post(configRunnable); }
    private void startBrowserSyncLoop() { handler.post(browserSyncRunnable); } // <--- Added this

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 50;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // --- NOTIFICATION SETUP ---
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "g4_sync_channel")
                .setContentTitle("Parent Control")
                .setContentText("Monitoring active")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "g4_sync_channel",
                    "Parent Control Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors app usage and location");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    // --- NOTIFICATION MONITOR ---
    private final Runnable notificationMonitor = new Runnable() {
        @Override
        public void run() {
            try {
                if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    boolean notificationExists = false;
                    android.service.notification.StatusBarNotification[] activeNotifications =
                            notificationManager.getActiveNotifications();

                    for (android.service.notification.StatusBarNotification sbn : activeNotifications) {
                        if (sbn.getId() == NOTIFICATION_ID) {
                            notificationExists = true;
                            break;
                        }
                    }
                    if (!notificationExists) {
                        Log.d("SyncService", "⚠️ Notification cleared. Re-showing...");
                        notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "Notification monitor error", e);
            }
            handler.postDelayed(this, 5000);
        }
    };

    private void startNotificationMonitor() {
        handler.post(notificationMonitor);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(locationRunnable);
        handler.removeCallbacks(appUsageRunnable);
        handler.removeCallbacks(configRunnable);
        handler.removeCallbacks(notificationMonitor);
        handler.removeCallbacks(browserSyncRunnable); // <--- Added this

        Log.d("SyncService", "⚠️ Service destroyed. Triggering auto-restart...");
        super.onDestroy();

        Intent restartIntent = new Intent(getApplicationContext(), SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                getApplicationContext().startForegroundService(restartIntent);
            } catch (Exception e) {
                Log.e("SyncService", "Failed to restart via startForegroundService", e);
            }
        } else {
            getApplicationContext().startService(restartIntent);
        }

        scheduleServiceRestart();
        ServiceRestartJob.scheduleJob(getApplicationContext());
    }

    private void scheduleServiceRestart() {
        try {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(getApplicationContext(), BootReceiver.class);
            intent.setAction("RESTART_SERVICE");

            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                    getApplicationContext(),
                    0,
                    intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            : android.app.PendingIntent.FLAG_UPDATE_CURRENT
            );

            if (alarmManager != null) {
                long restartTime = System.currentTimeMillis() + 5000;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP, restartTime, pendingIntent
                    );
                } else {
                    alarmManager.setExact(
                            android.app.AlarmManager.RTC_WAKEUP, restartTime, pendingIntent
                    );
                }
            }
        } catch (Exception e) {
            Log.e("SyncService", "Failed to schedule AlarmManager restart", e);
        }
    }


    // =========================================================
    // 🚫 4. BLOCKED APPS SYNC LOOP (Every 2 Minutes)
    // =========================================================
    private final Runnable blockedAppsRunnable = new Runnable() {
        @Override
        public void run() {
            syncBlockedApps();
            handler.postDelayed(this, 120000); // Run every 2 minutes
        }
    };

    private void syncBlockedApps() {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                // Call the new Server Endpoint
                Request req = new Request.Builder()
                        .url(Constants.BASE_URL + "/api/rules/blocked/" + deviceId)
                        .get()
                        .build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        String jsonStr = res.body().string();
                        JSONObject json = new JSONObject(jsonStr);
                        JSONArray array = json.optJSONArray("blockedPackages");

                        List<String> blockedList = new ArrayList<>();
                        if (array != null) {
                            for (int i = 0; i < array.length(); i++) {
                                blockedList.add(array.getString(i));
                            }
                        }

                        // Save to Local Storage
                        prefs.saveBlockedPackages(blockedList);
                        Log.d("SyncService", "🚫 Blocked Apps Updated: " + blockedList.size());
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ Blocked Apps Sync Failed", e);
            }
        }).start();
    }

    private void startBlockedAppsSyncLoop() { handler.post(blockedAppsRunnable); }


}