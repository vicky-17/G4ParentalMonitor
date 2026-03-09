package com.example.g4parentalmonitor.services;

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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import com.google.firebase.messaging.FirebaseMessaging;

import com.example.g4parentalmonitor.utils.Constants;
import com.example.g4parentalmonitor.utils.LocationHelper;
import com.example.g4parentalmonitor.data.PrefsManager;
import com.example.g4parentalmonitor.utils.UsageStatsHelper;
import com.example.g4parentalmonitor.logic.WebUrlDetector;

import com.google.gson.Gson;
import okhttp3.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

public class SyncService extends Service {

    // --- CONFIG ---
    private static final String BASE_URL = Constants.BASE_URL + "/api";
    private static final double DISTANCE_THRESHOLD_METERS = 1.0;

    private static final long APP_SYNC_INTERVAL_MS = 60000;      // 1 Minute
    private static final long BLOCKED_SYNC_INTERVAL_MS = 60000;  // 1 Minute
    private static final long BROWSER_SYNC_INTERVAL_MS = 30000;  // 30 Seconds

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

    private NotificationManager notificationManager;
    private static final int NOTIFICATION_ID = 1;

    // --- Track foreground state to avoid duplicate startForeground calls ---
    private boolean isForegroundStarted = false;

    // =========================================================
    // ✅ Centralized safe startForeground helper
    // =========================================================
    private void startForegroundSafe() {
        if (isForegroundStarted) return;

        if (Build.VERSION.SDK_INT >= 34) {
            boolean hasLocationPerm =
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (hasLocationPerm) {
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    Log.d("SyncService", "✅ Started Foreground Service with Location");
                } catch (SecurityException e) {
                    Log.w("SyncService", "⚠️ Background start blocked Location FGS. Falling back to Data Sync only.");
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification(),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } catch (Exception e2) {
                        Log.e("SyncService", "❌ Even DataSync FGS failed", e2);
                        startForeground(NOTIFICATION_ID, buildNotification());
                    }
                }
            } else {
                Log.w("SyncService", "⚠️ Location permission missing. Starting in DataSync mode only.");
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } catch (Exception e) {
                    Log.e("SyncService", "❌ DataSync FGS failed", e);
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        isForegroundStarted = true;
    }

    // --- SECURITY CHECKS ---
    private boolean isNetworkAvailable() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private boolean isLocationEnabled() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
    }

    private void checkDeviceState() {
        boolean hasNet = isNetworkAvailable();
        boolean hasLoc = isLocationEnabled();

        if (!hasNet || !hasLoc) {
            if (!LiveGuardianService.isRestrictedMode) {
                LiveGuardianService.isRestrictedMode = true;
                Log.w("SyncService", "🚨 Device Locked: Net=" + hasNet + " Loc=" + hasLoc);
            }
        } else {
            if (LiveGuardianService.isRestrictedMode) {
                LiveGuardianService.isRestrictedMode = false;
                Log.d("SyncService", "✅ Device Unlocked");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new PrefsManager(this);
        locationHelper = new LocationHelper(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        startForegroundSafe();

        // Start all monitoring loops
        startConfigLoop();
        startLocationLoop();
        startAppUsageLoop();
        startBrowserSyncLoop();
        startBlockedAppsSyncLoop();

        registerFCMToken();
        startNotificationMonitor();
        ServiceRestartJob.scheduleJob(this);

        // ✅ NEW: Start OverlayHeartbeatService.
        // Draws a 1×1 transparent overlay → MIUI shows "app is displaying over other
        // apps" in the status bar ONLY while we are truly running. If MIUI kills us,
        // the indicator vanishes — making the kill immediately visible to the parent.
        OverlayHeartbeatService.startIfPermitted(this);

        Log.d("SyncService", "🚀 Service Started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (prefs == null) prefs = new PrefsManager(this);
        if (locationHelper == null) locationHelper = new LocationHelper(this);
        startForegroundSafe();
        return START_STICKY;
    }

    // =========================================================
    // 🚫 1. BLOCKED APPS SYNC
    // =========================================================
    private final Runnable blockedAppsRunnable = new Runnable() {
        @Override
        public void run() {
            syncBlockedApps();
            handler.postDelayed(this, BLOCKED_SYNC_INTERVAL_MS);
        }
    };

    private void syncBlockedApps() {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                Request req = new Request.Builder()
                        .url(BASE_URL + "/rules/blocked/" + deviceId)
                        .get().build();

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
                        prefs.saveBlockedPackages(blockedList);
                        Log.d("SyncService", "🚫 Blocked List Updated: " + blockedList.size() + " apps");
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ Block List Error: " + e.getMessage());
            }
        }).start();
    }

    // =========================================================
    // 🌐 2. BROWSER HISTORY SYNC (UPDATED - DUAL MODE)
    // =========================================================
    private final Runnable browserSyncRunnable = new Runnable() {
        @Override
        public void run() {
            syncBrowserHistory();
            handler.postDelayed(this, BROWSER_SYNC_INTERVAL_MS);
        }
    };

    // ══════════════════════════════════════════════════════════════════════════════
    // ✅ UPDATED: Drains URLs from BOTH accessibility AND VPN DNS capture
    // ══════════════════════════════════════════════════════════════════════════════
    private void syncBrowserHistory() {
        new Thread(() -> {
            if (!isNetworkAvailable()) return;

            try {
                // ✅ NEW: Drain from BOTH accessibility AND VPN DNS capture
                List<String> urlsToSend = com.example.g4parentalmonitor.logic.WebUrlDetector.drainAllUrls();

                if (urlsToSend.isEmpty()) return;

                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("history", urlsToSend);

                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                Request req = new Request.Builder().url(BASE_URL + "/browser-history").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful()) {
                        Log.d("SyncService", "✅ Browser History Synced (" + urlsToSend.size() + " entries from both sources)");
                    }
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ Browser Sync Error", e);
            }
        }).start();
    }

    // =========================================================
    // 📍 3. LOCATION SYNC
    // =========================================================
    private final Runnable locationRunnable = new Runnable() {
        @Override
        public void run() {
            locationHelper.fetchCurrentLocation(location -> sendLocationData(location));
            long interval = (getBatteryLevel() < 15) ? (30 * 60 * 1000) : 45000;
            handler.postDelayed(this, interval);
        }
    };

    private void sendLocationData(Location loc) {
        new Thread(() -> {
            if (!isNetworkAvailable()) return;
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                if (prefs.hasLastSentLocation()) {
                    double dist = calculateDistance(
                            prefs.getLastSentLatitude(), prefs.getLastSentLongitude(),
                            loc.getLatitude(), loc.getLongitude());
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
            } catch (Exception e) {
                Log.e("SyncService", "Loc Send Failed", e);
            }
        }).start();
    }

    // =========================================================
    // 📱 4. APP USAGE SYNC
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
            if (!isNetworkAvailable()) return;
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
    // ⚙️ 5. SETTINGS / STATE MONITOR
    // =========================================================
    private final Runnable configRunnable = new Runnable() {
        @Override
        public void run() {
            checkDeviceState();
            syncSettings();
            handler.postDelayed(this, 10000);
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

    // --- LOOP STARTERS ---
    private void startLocationLoop()        { handler.post(locationRunnable); }
    private void startAppUsageLoop()        { handler.post(appUsageRunnable); }
    private void startConfigLoop()          { handler.post(configRunnable); }
    private void startBrowserSyncLoop()     { handler.post(browserSyncRunnable); }
    private void startBlockedAppsSyncLoop() { handler.post(blockedAppsRunnable); }
    private void startNotificationMonitor() { handler.post(notificationMonitor); }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 50;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // --- NOTIFICATION ---
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
                    "g4_sync_channel", "Parent Control Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors app usage and location");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    // =========================================================
    // 🔔 6. FCM TOKEN
    // =========================================================
    private void registerFCMToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.w("SyncService", "Fetching FCM token failed", task.getException());
                return;
            }
            String token = task.getResult();
            prefs.saveFcmToken(token);
            Log.d("SyncService", "FCM Token Generated: " + token);
            sendTokenToServer(token);
        });
    }

    private void sendTokenToServer(String token) {
        new Thread(() -> {
            if (!isNetworkAvailable()) return;
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                Map<String, Object> payload = new HashMap<>();
                payload.put("deviceId", deviceId);
                payload.put("fcmToken", token);

                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                Request req = new Request.Builder()
                        .url(BASE_URL + "/devices/update-token").post(body).build();

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful())
                        Log.d("SyncService", "✅ FCM Token sent to server successfully.");
                }
            } catch (Exception e) {
                Log.e("SyncService", "❌ Failed to send FCM token", e);
            }
        }).start();
    }

    // =========================================================
    // 🔔 7. NOTIFICATION MONITOR (re-posts if user clears it)
    // =========================================================
    private final Runnable notificationMonitor = new Runnable() {
        @Override
        public void run() {
            try {
                if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    boolean notificationExists = false;
                    android.service.notification.StatusBarNotification[] activeNotifications =
                            notificationManager.getActiveNotifications();
                    for (android.service.notification.StatusBarNotification sbn : activeNotifications) {
                        if (sbn.getId() == NOTIFICATION_ID) { notificationExists = true; break; }
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

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        isForegroundStarted = false;

        handler.removeCallbacks(locationRunnable);
        handler.removeCallbacks(appUsageRunnable);
        handler.removeCallbacks(configRunnable);
        handler.removeCallbacks(notificationMonitor);
        handler.removeCallbacks(browserSyncRunnable);
        handler.removeCallbacks(blockedAppsRunnable);

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

        ServiceRestartJob.scheduleJob(getApplicationContext());
    }
}