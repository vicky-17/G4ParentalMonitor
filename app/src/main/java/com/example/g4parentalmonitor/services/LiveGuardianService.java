package com.example.g4parentalmonitor.services;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver; // Added
import android.content.Context;
import android.content.Intent; // Added
import android.content.IntentFilter; // Added
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.g4parentalmonitor.data.PrefsManager;
import com.example.g4parentalmonitor.logic.BlockedAppsDetector;
import com.example.g4parentalmonitor.logic.ShortsDetector;
import com.example.g4parentalmonitor.utils.ScreenTimeTracker; // Ensure this matches your package
import com.example.g4parentalmonitor.vpn.WebUrlDetector;



public class LiveGuardianService extends AccessibilityService {

    private PrefsManager prefs;
    private BlockedAppsDetector appBlocker;
    private ShortsDetector shortsBlocker;
    private WebUrlDetector webUrlDetector;

    private WindowManager windowManager;
    private TextView overlayView;
    private boolean isOverlayShowing = false;

    private LinearLayout systemLockView;
    private boolean isSystemLocked = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    public static boolean isRestrictedMode = false;

    // --- STEP 1: Define the BroadcastReceiver ---
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                ScreenTimeTracker.getInstance(context).onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                ScreenTimeTracker.getInstance(context).onScreenOn();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = new PrefsManager(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        appBlocker = new BlockedAppsDetector(prefs);
        shortsBlocker = new ShortsDetector();
        webUrlDetector = new WebUrlDetector();

        startEnforcementLoop();

        // --- STEP 2: Register the Receiver ---
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);

        // Initialize tracker state
        ScreenTimeTracker.getInstance(this).onScreenOn();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // --- STEP 3: Track App Usage via Window Changes ---
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName != null) {
                ScreenTimeTracker.getInstance(this).onAppForegrounded(pkgName.toString());
            }
        }

        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();

        try {
            // App Blocker
            if (appBlocker.shouldBlockApp(packageName)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                performGlobalAction(GLOBAL_ACTION_HOME);
                showBlockedOverlay();
                return;
            } else {
                removeOverlay();
            }

            // Shorts Blocker
            if (prefs != null && prefs.isBlockShortsEnabled()) {
                if (rootNode != null && shortsBlocker.shouldBlockView(rootNode, packageName)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return;
                }
            }

            // Web Tracker
            if (rootNode != null && webUrlDetector.isBrowser(packageName)) {
                webUrlDetector.processBrowserEvent(rootNode);
            }
        } finally {
            if (rootNode != null) rootNode.recycle();
        }
    }

    // --- STEP 4: Cleanup on Destroy ---
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}

        ScreenTimeTracker.getInstance(this).onScreenOff();
        handler.removeCallbacksAndMessages(null);
    }

    private void startEnforcementLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkRestrictedState();
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    private void checkRestrictedState() {
        if (isRestrictedMode) {
            if (!isSystemLocked) showLockScreen();
        } else {
            if (isSystemLocked) hideLockScreen();
        }
    }

    private void showLockScreen() {
        if (isSystemLocked || windowManager == null) return;
        try {
            systemLockView = new LinearLayout(this);
            systemLockView.setOrientation(LinearLayout.VERTICAL);
            systemLockView.setBackgroundColor(Color.BLACK);
            systemLockView.setGravity(Gravity.CENTER);

            TextView title = new TextView(this);
            title.setText("⚠️ DEVICE LOCKED ⚠️");
            title.setTextColor(Color.RED);
            title.setTextSize(24);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, 20);

            TextView msg = new TextView(this);
            msg.setText("Internet & Location Required.\n\nEnable them to continue.");
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(16);
            msg.setGravity(Gravity.CENTER);

            systemLockView.addView(title);
            systemLockView.addView(msg);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);

            windowManager.addView(systemLockView, params);
            isSystemLocked = true;
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void hideLockScreen() {
        if (isSystemLocked && systemLockView != null) {
            try { windowManager.removeView(systemLockView); } catch (Exception e) {}
            systemLockView = null;
            isSystemLocked = false;
        }
    }

    private void showBlockedOverlay() {
        if (isOverlayShowing) return;
        handler.post(() -> {
            try {
                if (overlayView == null) {
                    overlayView = new TextView(this);
                    overlayView.setText("🚫 APP BLOCKED");
                    overlayView.setTextSize(20);
                    overlayView.setTextColor(Color.WHITE);
                    overlayView.setBackgroundColor(Color.parseColor("#E6FF0000"));
                    overlayView.setGravity(Gravity.CENTER);
                }
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, 300,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP;

                if (overlayView.getParent() == null) {
                    windowManager.addView(overlayView, params);
                    isOverlayShowing = true;
                    handler.postDelayed(this::removeOverlay, 3000);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void removeOverlay() {
        if (isOverlayShowing && overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Exception e) {}
            isOverlayShowing = false;
        }
    }

    @Override
    public void onInterrupt() { }
}