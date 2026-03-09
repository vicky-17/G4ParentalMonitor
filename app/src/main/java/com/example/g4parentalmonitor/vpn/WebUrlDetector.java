package com.example.g4parentalmonitor.vpn;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * WebUrlDetector — Dual-mode URL capture.
 *
 * Mode A: Accessibility service reads the browser's URL bar in real-time.
 *          Works when LiveGuardianService (accessibility) is enabled.
 *
 * Mode B: VPN DNS capture records every domain resolved by any app.
 *          Works when DnsVpnService is running.
 *
 * Strategy:
 *   - Both modes write to their own lists (visitedUrlsAccessibility / visitedUrlsVpn).
 *   - SyncService reads from whichever list(s) are available and deduplicates before upload.
 *   - If both are running, we get the union (more complete picture).
 *   - If only one is available, we gracefully fall back to that one.
 */
public class WebUrlDetector {

    private static final String TAG = "WebUrlDetector";

    // ── Shared lists (written by different sources, read by SyncService) ──────

    /** URLs captured by the accessibility service reading the browser URL bar. */
    public static final List<String> visitedUrlsAccessibility = new ArrayList<>();

    /** Domains captured by the VPN DNS filter (every DNS query). */
    public static final List<String> visitedUrlsVpn = new ArrayList<>();

    // ── Accessibility mode ────────────────────────────────────────────────────

    private String lastAccessibilityUrl = "";

    private static final String[] BROWSER_PACKAGES = {
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",       // Edge
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.brave.browser",
            "com.duckduckgo.mobile.android"
    };

    private static final String[] URL_BAR_IDS = {
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/url_bar_title",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field",
            "com.brave.browser:id/url_bar",
            "com.duckduckgo.mobile.android:id/omnibarTextInput"
    };

    public boolean isBrowser(String packageName) {
        if (packageName == null) return false;
        for (String b : BROWSER_PACKAGES) {
            if (packageName.contains(b)) return true;
        }
        return false;
    }

    /**
     * Called by LiveGuardianService on every accessibility event for a browser.
     * Extracts the current URL from the address bar and stores it if new.
     */
    public void processBrowserEvent(AccessibilityNodeInfo root) {
        if (root == null) return;
        String url = extractUrlFromNode(root);
        if (url != null && !url.isEmpty() && !url.equals(lastAccessibilityUrl)) {
            lastAccessibilityUrl = url;
            String entry = url + "|" + System.currentTimeMillis() + "|accessibility";
            synchronized (visitedUrlsAccessibility) {
                visitedUrlsAccessibility.add(entry);
            }
            Log.d(TAG, "🌐 [Accessibility] " + url);
        }
    }

    private String extractUrlFromNode(AccessibilityNodeInfo root) {
        for (String id : URL_BAR_IDS) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo node = nodes.get(0);
                    if (node.getText() != null) {
                        return node.getText().toString().trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── VPN mode (called by DnsFilterEngine) ──────────────────────────────────

    /**
     * Called by DnsVpnService/DnsFilterEngine every time a DNS query is allowed through.
     * Records the domain with timestamp for later upload.
     */
    public static void recordVpnDomain(String domain) {
        if (domain == null || domain.isEmpty()) return;
        // Skip common non-browsing domains to reduce noise
        if (isInternalDomain(domain)) return;

        String entry = domain + "|" + System.currentTimeMillis() + "|vpn";
        synchronized (visitedUrlsVpn) {
            visitedUrlsVpn.add(entry);
        }
        Log.v(TAG, "🔎 [VPN-DNS] " + domain);
    }

    private static boolean isInternalDomain(String d) {
        return d.endsWith(".local")
                || d.endsWith(".arpa")
                || d.contains("googleapis.com")
                || d.contains("gstatic.com")
                || d.contains("firebase")
                || d.contains("crashlytics")
                || d.contains("android.clients")
                || d.contains("play.googleapis")
                || d.equals("time.android.com");
    }

    // ── SyncService helper — get all URLs to upload ───────────────────────────

    /**
     * Returns and clears all captured URLs from BOTH sources.
     * Deduplicates by domain within the same 5-second window.
     */
    public static List<String> drainAllUrls() {
        List<String> all = new ArrayList<>();

        synchronized (visitedUrlsAccessibility) {
            all.addAll(visitedUrlsAccessibility);
            visitedUrlsAccessibility.clear();
        }
        synchronized (visitedUrlsVpn) {
            all.addAll(visitedUrlsVpn);
            visitedUrlsVpn.clear();
        }

        return all; // SyncService can deduplicate if needed
    }

    /**
     * Returns which capture modes are currently active.
     * Useful for showing status in the UI.
     */
    public static String getActiveModesDescription(boolean accessibilityActive, boolean vpnActive) {
        if (accessibilityActive && vpnActive) return "Accessibility + VPN (Full Coverage)";
        if (accessibilityActive)              return "Accessibility only";
        if (vpnActive)                        return "VPN DNS only";
        return "No capture active";
    }
}