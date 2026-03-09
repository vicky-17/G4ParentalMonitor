package com.example.g4parentalmonitor.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

/**
 * OverlayHeartbeatService
 * ========================
 * Draws a 1×1 transparent pixel using SYSTEM_ALERT_WINDOW permission.
 *
 * WHY THIS WORKS:
 *   - MIUI shows "G4ParentalMonitor is displaying over other apps" in the
 *     status bar — but ONLY while this service is actually alive.
 *   - A sticky foreground notification persists even after an app is killed.
 *     This overlay CANNOT persist — the moment MIUI kills us, the indicator
 *     disappears instantly. Parent can see the app was killed.
 *   - Having an active overlay window makes MIUI treat the app as "actively
 *     drawing on screen" → far less aggressive killing.
 *
 * WATCHDOG:
 *   Every 10 seconds: checks SyncService, restarts if dead.
 *   Every 20 seconds: refreshes foreground notification (prevents MIUI staling it).
 */
public class OverlayHeartbeatService extends Service {

    private static final String TAG = "OverlayHeartbeat";
    public  static final String CHANNEL_ID    = "overlay_heartbeat_channel";
    public  static final int    NOTIFICATION_ID = 9001;

    private static final long WATCHDOG_INTERVAL_MS    = 10_000L;
    private static final long NOTIF_REFRESH_INTERVAL_MS = 20_000L;

    private WindowManager     windowManager;
    private View              overlayView;
    private Handler           handler;
    private NotificationManager notificationManager;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler             = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        if (canDrawOverlays()) {
            drawOverlay();
        } else {
            Log.w(TAG, "⚠️ SYSTEM_ALERT_WINDOW not granted — overlay skipped");
        }

        handler.post(watchdogRunnable);
        handler.post(notifRefreshRunnable);
        Log.d(TAG, "✅ OverlayHeartbeatService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        // Redraw overlay if it disappeared
        if ((overlayView == null || !overlayView.isAttachedToWindow()) && canDrawOverlays()) {
            drawOverlay();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "⚠️ OverlayHeartbeatService destroyed — self-restarting");
        handler.removeCallbacks(watchdogRunnable);
        handler.removeCallbacks(notifRefreshRunnable);
        removeOverlay();
        super.onDestroy();
        // Immediate self-restart (Layer 1 for this service)
        try {
            Intent i = new Intent(getApplicationContext(), OverlayHeartbeatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(i);
            } else {
                getApplicationContext().startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "Self-restart failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Overlay drawing ───────────────────────────────────────────────────────

    private void drawOverlay() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            overlayView = new View(this);
            overlayView.setBackgroundColor(Color.TRANSPARENT);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, 1,   // 1×1 pixel — completely invisible
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSPARENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            params.alpha = 0f;  // fully invisible

            windowManager.addView(overlayView, params);
            Log.d(TAG, "✅ 1×1 transparent overlay drawn (invisible to user, visible to MIUI)");
        } catch (Exception e) {
            Log.e(TAG, "❌ drawOverlay failed: " + e.getMessage());
            overlayView = null;
        }
    }

    private void removeOverlay() {
        try {
            if (windowManager != null && overlayView != null && overlayView.isAttachedToWindow()) {
                windowManager.removeView(overlayView);
            }
        } catch (Exception ignored) {}
        overlayView = null;
    }

    // ── Watchdog — runs every 10 seconds ─────────────────────────────────────

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. Check SyncService
            if (!isServiceRunning(SyncService.class)) {
                Log.w(TAG, "💓 Watchdog: SyncService dead — restarting");
                try {
                    Intent i = new Intent(OverlayHeartbeatService.this, SyncService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                    else startService(i);
                } catch (Exception e) {
                    Log.e(TAG, "SyncService restart failed: " + e.getMessage());
                }
            }
            // 2. Redraw overlay if system removed it
            if (overlayView == null || !overlayView.isAttachedToWindow()) {
                if (canDrawOverlays()) {
                    Log.w(TAG, "💓 Watchdog: overlay gone — redrawing");
                    drawOverlay();
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    // ── Notification refresh — runs every 20 seconds ──────────────────────────

    private final Runnable notifRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            // Re-post with fresh timestamp → MIUI cannot mark it as "stale" or collapse it
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification());
                Log.d(TAG, "🔔 Notification refreshed");
            } catch (Exception e) {
                Log.e(TAG, "Notification refresh error: " + e.getMessage());
            }
            handler.postDelayed(this, NOTIF_REFRESH_INTERVAL_MS);
        }
    };

    // ── Notification builder ──────────────────────────────────────────────────

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Monitoring Active")
                .setContentText("Parental protection is running")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                // Always fresh timestamp so MIUI treats it as new each refresh
                .setWhen(System.currentTimeMillis())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Parental Monitor Heartbeat", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps parental monitoring alive on MIUI");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);
            notificationManager.createNotificationChannel(ch);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> cls) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo s :
                am.getRunningServices(Integer.MAX_VALUE)) {
            if (cls.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }

    /**
     * Call this from SyncService.onCreate() and BootReceiver.
     * Starts OverlayHeartbeatService only if SYSTEM_ALERT_WINDOW is granted.
     * Safe to call multiple times — START_STICKY handles duplicates.
     */
    public static void startIfPermitted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context)) {
            try {
                Intent i = new Intent(context, OverlayHeartbeatService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i);
                } else {
                    context.startService(i);
                }
                Log.d(TAG, "✅ startIfPermitted → started");
            } catch (Exception e) {
                Log.e(TAG, "startIfPermitted failed: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "⚠️ SYSTEM_ALERT_WINDOW not granted — not starting");
        }
    }
}