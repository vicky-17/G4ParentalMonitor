package com.example.g4parentalmonitor;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

public class SettingsBlockerService extends AccessibilityService {

    // List of common browser package names
    private final String[] BROWSER_PACKAGES = {
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
    };

    // Store captured URLs temporarily (in a real app, use a database or shared prefs)
    public static List<String> visitedUrls = new ArrayList<>();
    private String lastUrl = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();

        // Check if the active app is a browser
        if (isBrowserPackage(packageName)) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                // Attempt to find the URL bar
                String capturedUrl = findBrowserUrl(rootNode, packageName);

                // Simple de-bouncing (prevent saving the same URL 50 times in a row)
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

        // ... Keep your existing Settings Blocker logic here ...
    }

    private boolean isBrowserPackage(String packageName) {
        for (String browser : BROWSER_PACKAGES) {
            if (packageName.contains(browser)) return true;
        }
        return false;
    }

    private String findBrowserUrl(AccessibilityNodeInfo root, String packageName) {
        // Known IDs for URL bars in different browsers
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