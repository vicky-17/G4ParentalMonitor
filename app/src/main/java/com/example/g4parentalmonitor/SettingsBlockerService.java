package com.example.g4parentalmonitor;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsBlockerService extends AccessibilityService {

    private PrefsManager prefs;
    private WindowManager windowManager;
    private TextView overlayView;
    private boolean isOverlayShowing = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = new PrefsManager(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        // 1. Check if App is Blocked (Local Storage Check)
        if (prefs != null && prefs.isAppBlocked(packageName)) {
            Log.d("Blocker", "⛔ BLOCKED APP DETECTED: " + packageName);

            // 2. Action: Press Back
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME); // Double safety

            // 3. Action: Show Overlay
            showBlockedOverlay();
        } else {
            // Hide overlay if user switches to a safe app
            removeOverlay();
        }

        // ... [Your existing Browser Tracking Code] ...
    }

    private void showBlockedOverlay() {
        if (isOverlayShowing) return;

        handler.post(() -> {
            try {
                if (overlayView == null) {
                    overlayView = new TextView(this);
                    overlayView.setText("🚫 THIS APP IS BLOCKED BY PARENT");
                    overlayView.setTextSize(18);
                    overlayView.setTextColor(Color.WHITE);
                    overlayView.setBackgroundColor(Color.parseColor("#CCFF0000")); // Red with transparency
                    overlayView.setGravity(Gravity.CENTER);
                    overlayView.setPadding(20, 20, 20, 20);
                }

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        200, // Height
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // Allowed in AccessibilityService
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP;

                windowManager.addView(overlayView, params);
                isOverlayShowing = true;

                // Auto-hide after 3 seconds
                handler.postDelayed(this::removeOverlay, 3000);

            } catch (Exception e) {
                // Fallback if overlay fails (e.g., permission issues)
                Toast.makeText(this, "🚫 APP BLOCKED", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void removeOverlay() {
        if (isOverlayShowing && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {}
            isOverlayShowing = false;
        }
    }

    @Override
    public void onInterrupt() {}
}