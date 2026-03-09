package com.example.g4parentalmonitor.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class PrefsManager {
    private static final String PREF_NAME = "G4Prefs";
    private SharedPreferences prefs;
    private Gson gson = new Gson();

    public PrefsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        initializeDefaults();
    }

    private void initializeDefaults() {
        if (!prefs.contains("liveTracking")) {
            prefs.edit()
                    .putBoolean("liveTracking", false)
                    .putBoolean("uninstallProtection", true)
                    .putBoolean("blockShorts", true)
                    .apply();
        }
    }

    // --- DEVICE ID ---
    public void saveDeviceId(String id) {
        prefs.edit().putString("deviceId", id).putBoolean("isPaired", true).apply();
    }
    public String getDeviceId() { return prefs.getString("deviceId", null); }
    public boolean isPaired() { return prefs.getBoolean("isPaired", false); }

    // --- SYNC SETTINGS ---
    public void saveSettings(long locInterval, long appInterval, long lastModified) {
        prefs.edit()
                .putLong("locationInterval", locInterval)
                .putLong("appSyncInterval", appInterval)
                .putLong("lastModified", lastModified)
                .apply();
    }

    public long getLocationInterval() { return prefs.getLong("locationInterval", 60000); }
    public long getAppSyncInterval() { return prefs.getLong("appSyncInterval", 120000); }
    public long getLastModified() { return prefs.getLong("lastModified", 0); }

    // --- RULE STORAGE ---
    public void saveAppRules(List<AppRule> rules) {
        prefs.edit().putString("appRulesJson", gson.toJson(rules)).apply();
    }

    public List<AppRule> getAppRules() {
        String json = prefs.getString("appRulesJson", "[]");
        Type type = new TypeToken<ArrayList<AppRule>>(){}.getType();
        return gson.fromJson(json, type);
    }

    // --- LAST SENT LOCATION ---
    public void saveLastSentLocation(double latitude, double longitude) {
        prefs.edit()
                .putString("lastSentLat", String.valueOf(latitude))
                .putString("lastSentLng", String.valueOf(longitude))
                .apply();
    }

    public double getLastSentLatitude() {
        String lat = prefs.getString("lastSentLat", null);
        return lat != null ? Double.parseDouble(lat) : 0.0;
    }

    public double getLastSentLongitude() {
        String lng = prefs.getString("lastSentLng", null);
        return lng != null ? Double.parseDouble(lng) : 0.0;
    }

    public boolean hasLastSentLocation() {
        return prefs.contains("lastSentLat") && prefs.contains("lastSentLng");
    }

    // --- BLOCKED APPS ---
    public void saveBlockedPackages(List<String> packageNames) {
        Set<String> set = new HashSet<>(packageNames);
        prefs.edit().putStringSet("blocked_packages_set", set).apply();
    }

    public boolean isAppBlocked(String packageName) {
        Set<String> set = prefs.getStringSet("blocked_packages_set", new HashSet<>());
        return set.contains(packageName);
    }

    public void saveBlockedLists(List<String> apps, List<String> urls) {
        prefs.edit()
                .putString("blockedApps", gson.toJson(apps))
                .putString("blockedUrls", gson.toJson(urls))
                .apply();
    }

    public List<String> getBlockedApps() {
        String json = prefs.getString("blockedApps", "[]");
        Type type = new TypeToken<ArrayList<String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public List<String> getBlockedUrls() {
        String json = prefs.getString("blockedUrls", "[]");
        Type type = new TypeToken<ArrayList<String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public boolean hasBlockedLists() {
        return prefs.contains("blockedApps") && prefs.contains("blockedUrls");
    }

    // --- GLOBAL SETTINGS ---
    public void saveGlobalSettings(boolean liveTracking, boolean uninstallProtection, boolean blockShorts) {
        prefs.edit()
                .putBoolean("liveTracking", liveTracking)
                .putBoolean("uninstallProtection", uninstallProtection)
                .putBoolean("blockShorts", blockShorts)
                .apply();
    }

    public boolean isLiveTrackingEnabled() { return prefs.getBoolean("liveTracking", false); }
    public boolean isUninstallProtectionEnabled() { return prefs.getBoolean("uninstallProtection", true); }
    public boolean isBlockShortsEnabled() { return prefs.getBoolean("blockShorts", true); }

    public void saveFcmToken(String token) { prefs.edit().putString("fcmToken", token).apply(); }
    public String getFcmToken() { return prefs.getString("fcmToken", null); }

    // ======================================================================
    // --- VPN / DNS FILTER SETTINGS (NEW) ---
    // ======================================================================

    private static final String KEY_VPN_FILTER_ENABLED      = "vpnFilterEnabled";
    private static final String KEY_VPN_SAFE_SEARCH_ENABLED = "vpnSafeSearchEnabled";
    private static final String KEY_VPN_BLOCK_ADULT         = "vpnBlockAdult";
    private static final String KEY_VPN_KEEP_ALIVE          = "vpnKeepAlive";
    private static final String KEY_VPN_PREVENT_OVERRIDE    = "vpnPreventOverride";
    private static final String KEY_VPN_HEARTBEAT_STATE     = "vpnHeartbeatState";
    private static final String KEY_VPN_HEARTBEAT_TIME      = "vpnHeartbeatTime";

    /** Master switch — user wants the VPN DNS filter ON */
    public boolean isVpnFilterEnabled() {
        return prefs.getBoolean(KEY_VPN_FILTER_ENABLED, false);
    }
    public void setVpnFilterEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VPN_FILTER_ENABLED, enabled).apply();
    }

    /** Redirect Google/YouTube/Bing to SafeSearch IPs */
    public boolean isVpnSafeSearchEnabled() {
        return prefs.getBoolean(KEY_VPN_SAFE_SEARCH_ENABLED, true);
    }
    public void setVpnSafeSearchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VPN_SAFE_SEARCH_ENABLED, enabled).apply();
    }

    /** Block adult/harmful domains via NXDOMAIN */
    public boolean isVpnBlockAdultEnabled() {
        return prefs.getBoolean(KEY_VPN_BLOCK_ADULT, true);
    }
    public void setVpnBlockAdultEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VPN_BLOCK_ADULT, enabled).apply();
    }

    /** Keep VPN alive with probe + watchdog */
    public boolean isKeepVpnAlive() {
        return prefs.getBoolean(KEY_VPN_KEEP_ALIVE, true);
    }
    public void setKeepVpnAlive(boolean enabled) {
        prefs.edit().putBoolean(KEY_VPN_KEEP_ALIVE, enabled).apply();
    }

    /** Restart immediately when another VPN tries to displace ours */
    public boolean isPreventVpnOverride() {
        return prefs.getBoolean(KEY_VPN_PREVENT_OVERRIDE, true);
    }
    public void setPreventVpnOverride(boolean enabled) {
        prefs.edit().putBoolean(KEY_VPN_PREVENT_OVERRIDE, enabled).apply();
    }

    /** Written by DnsVpnService every 7 min; read by VpnWatchdogJob */
    public void setVpnHeartbeat(String state, long timestamp) {
        prefs.edit()
                .putString(KEY_VPN_HEARTBEAT_STATE, state)
                .putLong(KEY_VPN_HEARTBEAT_TIME, timestamp)
                .apply();
    }
    public String getVpnHeartbeatState() {
        return prefs.getString(KEY_VPN_HEARTBEAT_STATE, "UNKNOWN");
    }
    public long getVpnHeartbeatTime() {
        return prefs.getLong(KEY_VPN_HEARTBEAT_TIME, 0);
    }
}