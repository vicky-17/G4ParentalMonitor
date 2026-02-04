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

    private static final long APP_SYNC_INTERVAL_MS = 60000; // 60000 = 1 minute, 300000 = 5 mins

    // --- HELPERS ---
    private LocationHelper locationHelper; // Imports location logic
    // UsageStatsHelper is static, so we don't need to instantiate it

    // --- NETWORK & TOOLS ---
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PrefsManager prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new PrefsManager(this);
        locationHelper = new LocationHelper(this); // Initialize the helper

        // 1. Foreground Notification
        createNotificationChannel();
        startForeground(1, buildNotification());

        // 2. Start All Sync Loops
        startConfigLoop();
        startLocationLoop();
        startAppUsageLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        return START_STICKY;
    }

    // =========================================================
    // 📍 1. LOCATION SYNC LOOP (Uses LocationHelper)
    // =========================================================
    private final Runnable locationRunnable = new Runnable() {
        @Override
        public void run() {
            // Ask Helper for Location
            locationHelper.fetchCurrentLocation(location -> {
                // When location is found, send it
                sendLocationData(location);
            });

            // Schedule next run (Battery Aware)
            long interval = (getBatteryLevel() < 15) ? (30 * 60 * 1000) : 45000;
            handler.postDelayed(this, interval);
        }
    };

    private void sendLocationData(Location loc) {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                // Distance Filter
                if (prefs.hasLastSentLocation()) {
                    double dist = calculateDistance(prefs.getLastSentLatitude(), prefs.getLastSentLongitude(), loc.getLatitude(), loc.getLongitude());
                    if (dist < DISTANCE_THRESHOLD_METERS) return; // Didn't move enough
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
            // CHANGE THIS LINE:
            handler.postDelayed(this, APP_SYNC_INTERVAL_MS);
        }
    };

    private void syncApps() {
        new Thread(() -> {
            try {
                String deviceId = prefs.getDeviceId();
                if (deviceId == null) return;

                // 1. Get ALL apps from helper
                List<Map<String, Object>> currentApps = UsageStatsHelper.getRecentAppUsage(this);
                if (currentApps.isEmpty()) return;

                // 2. Smart Check: Did anything actually change?
                boolean hasChanged = false;
                for (Map<String, Object> app : currentApps) {
                    String pkg = (String) app.get("packageName");
                    long currentMins = (long) app.get("minutes");

                    // Compare against local cache in PrefsManager
                    if (currentMins > prefs.getLastSentAppMinutes(pkg)) {
                        hasChanged = true;
                        break;
                    }
                }

                // 3. Only send if there is new data to report
                if (hasChanged) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("deviceId", deviceId);
                    payload.put("apps", currentApps);

                    RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json"));
                    Request req = new Request.Builder().url(BASE_URL + "/apps").post(body).build();

                    try (Response res = client.newCall(req).execute()) {
                        if (res.isSuccessful()) {
                            Log.d("SyncService", "All Apps Synced ✅");
                            // 4. Update cache ONLY after successful server confirmation
                            for (Map<String, Object> app : currentApps) {
                                prefs.saveAppUsageCache((String) app.get("packageName"), (long) app.get("minutes"));
                            }
                        }
                    }
                } else {
                    Log.d("SyncService", "No app usage changes detected. Skipping sync.");
                }
            } catch (Exception e) {
                Log.e("SyncService", "App Sync Failed", e);
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
            handler.postDelayed(this, 60000); // Every 1 min
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
                .setContentTitle("G4 Monitor")
                .setContentText("Syncing Data...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "g4_sync_channel", "G4 Sync Service", NotificationManager.IMPORTANCE_MIN
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(locationRunnable);
        handler.removeCallbacks(appUsageRunnable);
        handler.removeCallbacks(configRunnable);
        super.onDestroy();
    }
}