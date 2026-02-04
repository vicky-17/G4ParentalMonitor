package com.example.g4parentalmonitor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.List;

public class SettingsBlockerService extends AccessibilityService {

    private static final String TAG = "G4BlockerService";
    private static final String CHANNEL_ID = "g4_blocker_channel";
    private static final int NOTIFICATION_ID = 2;

    // PrefsManager for dynamic blocked lists
    private PrefsManager prefs;

    // Known browser packages for web filtering
    private final List<String> BROWSER_PACKAGES = Arrays.asList(
            "com.android.chrome",
            "com.microsoft.emmx",           // Edge
            "com.sec.android.app.sbrowser", // Samsung Internet
            "org.mozilla.firefox",
            "com.opera.browser"
    );

    // Apps to BLOCK (Settings + Package Installers)
    private final List<String> BLOCKED_PACKAGES = Arrays.asList(
            "com.android.settings",                // System Settings
            "com.google.android.packageinstaller", // Google Uninstall Dialog
            "com.android.packageinstaller",        // Generic Android Uninstall Dialog
            "com.samsung.android.packageinstaller" // Samsung specific
    );

    // Variable to temporarily pause blocking (so YOU can enter settings)
    public static long lastUnlockTime = 0;
    private static final long UNLOCK_DURATION = 60000; // 1 Minute

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

            // ============================================
            // 🛡️ ENFORCER 1: APP BLOCKING
            // ============================================
            
            // Check hardcoded blocked packages (Settings, Installers)
            if (BLOCKED_PACKAGES.contains(packageName)) {
                // 1. Check if the "Snooze" is active (Did you just enter the PIN?)
                if (System.currentTimeMillis() - lastUnlockTime < UNLOCK_DURATION) {
                    return; // Allow access
                }

                // 2. If not snoozed, BLOCK IT by showing the Password Screen
                Intent intent = new Intent(this, PasswordActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return;
            }

            // Check dynamic blocked apps from server
            List<String> dynamicBlockedApps = prefs.getBlockedApps();
            if (dynamicBlockedApps.contains(packageName)) {
                Log.d(TAG, "🚫 Blocking app: " + packageName);
                performGlobalAction(GLOBAL_ACTION_HOME); // Send user to home screen
                return;
            }

            // ============================================
            // 🌐 ENFORCER 2: WEB BLOCKING (Browser Protection)
            // ============================================
            
            if (BROWSER_PACKAGES.contains(packageName)) {
                checkAndBlockUrl(event);
            }
        }
    }

    /**
     * 🌐 Extract URL from browser and block if on blacklist
     */
    private void checkAndBlockUrl(AccessibilityEvent event) {
        try {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            String url = extractUrlFromBrowser(rootNode);
            rootNode.recycle(); // Prevent memory leaks

            if (url != null && !url.isEmpty()) {
                List<String> blockedUrls = prefs.getBlockedUrls();
                
                // Check if URL contains any blocked domain/keyword
                for (String blockedUrl : blockedUrls) {
                    if (url.toLowerCase().contains(blockedUrl.toLowerCase())) {
                        Log.d(TAG, "🚫 Blocking harmful URL: " + url);
                        
                        // Enforcement: Go back twice to close tab
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        
                        // Small delay then go back again
                        new android.os.Handler().postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                        }, 300);
                        
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking URL", e);
        }
    }

    /**
     * 🔍 Recursively search for URL in address bar
     */
    private String extractUrlFromBrowser(AccessibilityNodeInfo node) {
        if (node == null) return null;

        try {
            // Check if this node is the URL bar
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            
            if (className.contains("EditText") || className.contains("TextView")) {
                CharSequence text = node.getText();
                if (text != null && text.toString().startsWith("http")) {
                    return text.toString();
                }
            }

            // Recursively check children
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    String url = extractUrlFromBrowser(child);
                    child.recycle();
                    if (url != null) return url;
                }
            }
        } catch (Exception e) {
            // Prevent crashes from NullPointerException
            Log.e(TAG, "Error extracting URL", e);
        }

        return null;
    }

    @Override
    public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        // Initialize PrefsManager
        prefs = new PrefsManager(this);
        
        // ============================================
        // 🔒 MAKE SERVICE UNKILLABLE
        // ============================================
        createNotificationChannel();
        startForegroundService();
        
        // Configure Accessibility Service
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();

        // Listen to window changes and content changes (for URL detection)
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;

        // ✅ FIXED: Changed FLAG_DEFAULT to 0
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

        info.notificationTimeout = 100;

        // ✅ CRITICAL: Do NOT set packageNames here, or we miss the installer!

        setServiceInfo(info);
        
        Log.d(TAG, "✅ G4 Blocker Service Connected and Running");
    }

    /**
     * 🔔 Create notification channel for foreground service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "G4 Protection Service",
                    NotificationManager.IMPORTANCE_HIGH // High priority to prevent killing
            );
            channel.setDescription("Keeps your child safe online");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 🛡️ Start foreground service with high-priority, non-dismissible notification
     */
    private void startForegroundService() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("G4 Protection Active")
                .setContentText("Monitoring for child safety")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true) // User cannot dismiss
                .setAutoCancel(false)
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "✅ Foreground service started - App is now protected");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
        }
    }
}