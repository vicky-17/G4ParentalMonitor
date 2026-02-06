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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class SettingsBlockerService extends AccessibilityService {

    private PrefsManager prefs;
    private WindowManager windowManager;
    private TextView overlayView;
    private boolean isOverlayShowing = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // --- BROWSER TRACKING VARS ---
    private final String[] BROWSER_PACKAGES = {
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
    };
    public static List<String> visitedUrls = new ArrayList<>();
    private String lastUrl = "";

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

        // =========================================================
        // 🛑 1. APP BLOCKING LOGIC
        // =========================================================
        if (prefs != null && prefs.isAppBlocked(packageName)) {
            Log.d("Blocker", "⛔ BLOCKED APP DETECTED: " + packageName);

            // A. Force Close Actions
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME); // Double safety to force exit

            // B. Show Blocking Overlay
            showBlockedOverlay();

            // Return immediately so we don't try to track URLs in a blocked app
            return;
        } else {
            // Hide overlay if user switches to a safe app
            removeOverlay();
        }

        // =========================================================
        // 🌐 2. BROWSER URL TRACKING LOGIC
        // =========================================================
        if (isBrowserPackage(packageName)) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                String capturedUrl = findBrowserUrl(rootNode, packageName);

                // Simple de-bouncing
                if (capturedUrl != null && !capturedUrl.equals(lastUrl) && !capturedUrl.isEmpty()) {
                    lastUrl = capturedUrl;
                    Log.d("BrowserTracker", "Visited: " + capturedUrl);

                    // Add to list to be picked up by SyncService
                    synchronized (visitedUrls) {
                        visitedUrls.add(capturedUrl + "|" + System.currentTimeMillis());
                    }
                }
            }
        }
    }

    // --- BLOCKING OVERLAY HELPERS ---
    private void showBlockedOverlay() {
        if (isOverlayShowing) return;

        handler.post(() -> {
            try {
                if (overlayView == null) {
                    overlayView = new TextView(this);
                    overlayView.setText("🚫 APP BLOCKED BY PARENT");
                    overlayView.setTextSize(20);
                    overlayView.setTextColor(Color.WHITE);
                    overlayView.setBackgroundColor(Color.parseColor("#E6FF0000")); // Red with transparency
                    overlayView.setGravity(Gravity.CENTER);
                    overlayView.setPadding(30, 30, 30, 30);
                }

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        300, // Height of the banner
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP;

                windowManager.addView(overlayView, params);
                isOverlayShowing = true;

                // Auto-hide after 3 seconds
                handler.postDelayed(this::removeOverlay, 3000);

            } catch (Exception e) {
                // Fallback if overlay permission missing
                Toast.makeText(this, "🚫 APP BLOCKED", Toast.LENGTH_SHORT).show();
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

    // --- BROWSER TRACKING HELPERS ---
    private boolean isBrowserPackage(String packageName) {
        for (String browser : BROWSER_PACKAGES) {
            if (packageName.contains(browser)) return true;
        }
        return false;
    }

    private String findBrowserUrl(AccessibilityNodeInfo root, String packageName) {
        String[] urlBarIds = {
                "com.android.chrome:id/url_bar",
                "org.mozilla.firefox:id/url_bar_title",
                "com.microsoft.emmx:id/url_bar",
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
        };

        for (String id : urlBarIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(0);
                if (node.getText() != null) {
                    return node.getText().toString();
                }
            }
        }
        return null;
    }

    @Override
    public void onInterrupt() {}
}