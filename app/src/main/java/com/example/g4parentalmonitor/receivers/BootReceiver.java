package com.example.g4parentalmonitor.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import com.example.g4parentalmonitor.data.PrefsManager;
import com.example.g4parentalmonitor.services.ServiceRestartJob;
import com.example.g4parentalmonitor.services.SyncService;
import com.example.g4parentalmonitor.vpn.DnsVpnService;
import com.example.g4parentalmonitor.vpn.VpnWatchdogJob;

/**
 * BootReceiver — starts SyncService AND DnsVpnService (if enabled) on boot/update.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        PrefsManager prefs = new PrefsManager(context);
        if (!prefs.isPaired()) {
            Log.d(TAG, "Device not paired — skipping");
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "RESTART_SERVICE":
                startSyncService(context);
                startVpnIfNeeded(context, prefs);
                break;
        }
    }

    private void startSyncService(Context context) {
        try {
            Intent i = new Intent(context, SyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            ServiceRestartJob.scheduleJob(context);
            Log.d(TAG, "✅ SyncService started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SyncService", e);
        }
    }

    private void startVpnIfNeeded(Context context, PrefsManager prefs) {
        if (!prefs.isVpnFilterEnabled()) {
            Log.d(TAG, "VPN filter disabled — not starting");
            return;
        }

        // Can only auto-start VPN if permission was already granted
        // prepare() returns null when permission is granted
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not yet granted — cannot auto-start");
            return;
        }

        try {
            Intent vpnIntent = new Intent(context, DnsVpnService.class);
            vpnIntent.setAction(DnsVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(vpnIntent);
            } else {
                context.startService(vpnIntent);
            }
            VpnWatchdogJob.schedule(context);
            Log.i(TAG, "✅ DnsVpnService started on boot");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN", e);
        }
    }
}