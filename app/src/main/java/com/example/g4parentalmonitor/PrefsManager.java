package com.example.g4parentalmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PrefsManager {
    private static final String PREF_NAME = "G4Prefs";
    private SharedPreferences prefs;
    private Gson gson = new Gson();

    public PrefsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
    public long getAppSyncInterval() { return prefs.getLong("appSyncInterval", 300000); }
    public long getLastModified() { return prefs.getLong("lastModified", 0); }

    // --- RULE STORAGE (Updated for MongoDB Schema) ---
    public void saveAppRules(List<AppRule> rules) {
        String json = gson.toJson(rules);
        prefs.edit().putString("appRulesJson", json).apply();
    }

    public List<AppRule> getAppRules() {
        String json = prefs.getString("appRulesJson", "[]");
        Type type = new TypeToken<ArrayList<AppRule>>(){}.getType();
        return gson.fromJson(json, type);
    }

    // --- LAST SENT LOCATION (For Smart Triggers) ---
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

    // --- BLOCKED LISTS (For Active Blocking) ---
    
    /**
     * 🚫 Save blocked apps and URLs from server
     * @param apps List of package names to block
     * @param urls List of URLs/domains to block
     */
    public void saveBlockedLists(List<String> apps, List<String> urls) {
        String appsJson = gson.toJson(apps);
        String urlsJson = gson.toJson(urls);
        
        prefs.edit()
                .putString("blockedApps", appsJson)
                .putString("blockedUrls", urlsJson)
                .apply();
        
        // Log for debugging
        android.util.Log.d("PrefsManager", "Saved " + apps.size() + " blocked apps and " + urls.size() + " blocked URLs");
    }

    /**
     * 📱 Get list of blocked apps (package names)
     * @return List of blocked package names
     */
    public List<String> getBlockedApps() {
        String json = prefs.getString("blockedApps", "[]");
        Type type = new TypeToken<ArrayList<String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    /**
     * 🌐 Get list of blocked URLs/domains
     * @return List of blocked URLs
     */
    public List<String> getBlockedUrls() {
        String json = prefs.getString("blockedUrls", "[]");
        Type type = new TypeToken<ArrayList<String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    /**
     * 🔄 Check if blocked lists are available (for offline capability)
     * @return true if lists have been synced from server
     */
    public boolean hasBlockedLists() {
        return prefs.contains("blockedApps") && prefs.contains("blockedUrls");
    }



    // --- APP USAGE CACHE ---
    public void saveAppUsageCache(String packageName, long minutes) {
        prefs.edit().putLong("cache_mins_" + packageName, minutes).apply();
    }

    public long getLastSentAppMinutes(String packageName) {
        return prefs.getLong("cache_mins_" + packageName, -1); // -1 ensures first-time sync
    }


}




