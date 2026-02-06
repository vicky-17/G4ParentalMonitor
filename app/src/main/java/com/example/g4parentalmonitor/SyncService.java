package com.example.g4parentalmonitor;

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

public class SyncService extends Service {

    // --- CONFIG ---
    private static final String BASE_URL = Constants.BASE_URL + "/api";
    private static final double DISTANCE_THRESHOLD_METERS = 5.0;

    // Configurable sync interval - always send data every 2 minutes
    private static final long APP_SYNC_INTERVAL_MS = 120000; // 120000 = 2 minutes

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

        // Removed: usage sync time initialization (we do not store usage data locally anymore)

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        startConfigLoop();
        startLocationLoop();
        startAppUsageLoop();

        startNotificationMonitor();
        ServiceRestartJob.scheduleJob(this);

        Log.d("SyncService", "🚀 Service started successfully with auto-restart protection");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    // =========================================================
    // 📍 1. LOCATION SYNC LOOP (Uses LocationHelper)
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
    // 📱 2. APP USAGE SYNC LOOP (Uses UsageStatsHelper)
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

                // 1. Get absolute "today" app usage (minutes) DIRECTLY from System
                // We do NOT use any local storage or caching here.
                List<Map<String, Object>> currentApps =
                        UsageStatsHelper.getTodayUsageMinutes(this);

                if (currentApps == null || currentApps.isEmpty()) return;

                // 2. Send data
                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("apps", currentApps);

                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                Request req = new Request.Builder().url(BASE_URL + "/apps").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful()) {
                        Log.d("SyncService", "✅ Apps Synced (" + currentApps.size() + ")");
                        // Removed: prefs.setLastUsageSyncTime(...)
                    } else {
                        Log.e("SyncService", "❌ Sync failed: " + res.code());
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ App Sync Failed", e);
            }
        }).start();
    }

    // =========================================================
    // ⚙️ 3. SETTINGS SYNC LOOP
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
                .setContentText("Parent control app is running in background")
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
}