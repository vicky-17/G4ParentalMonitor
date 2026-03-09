package com.example.g4parentalmonitor.vpn;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import com.example.g4parentalmonitor.data.PrefsManager;

/**
 * VpnWatchdogJob — runs every 15 minutes via JobScheduler.
 *
 * Checks the heartbeat pref written by DnsVpnService.
 * If VPN should be running but heartbeat is stale → restart.
 */
public class VpnWatchdogJob extends JobService {

    private static final String TAG    = "VpnWatchdog";
    public  static final int    JOB_ID = 2001;

    /** Max age of a heartbeat before we consider VPN dead (25 minutes). */
    private static final long HEARTBEAT_MAX_AGE_MS = 25 * 60 * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Watchdog triggered");

        PrefsManager prefs = new PrefsManager(this);

        // Only act if VPN filter is turned on by the user
        if (!prefs.isVpnFilterEnabled()) {
            Log.d(TAG, "VPN filter disabled — skipping");
            jobFinished(params, false);
            return false;
        }

        // Check if VPN permission is still available (user may have revoked it)
        if (VpnService.prepare(this) != null) {
            Log.w(TAG, "VPN permission gone — skipping restart");
            jobFinished(params, false);
            return false;
        }

        String heartbeatState = prefs.getVpnHeartbeatState();
        long   heartbeatTime  = prefs.getVpnHeartbeatTime();
        long   age            = System.currentTimeMillis() - heartbeatTime;

        boolean shouldRestart = false;

        if ("STOPPED".equals(heartbeatState)) {
            // User stopped it cleanly — don't restart
            Log.d(TAG, "Heartbeat=STOPPED — not restarting");
        } else if (DnsVpnService.serviceRunning) {
            Log.d(TAG, "Service is alive in-process — OK");
        } else if (heartbeatTime == 0 || age > HEARTBEAT_MAX_AGE_MS) {
            Log.w(TAG, "Heartbeat stale (" + (age / 1000) + "s) — restarting VPN");
            shouldRestart = true;
        } else if ("KILLED".equals(heartbeatState) || "REVOKED".equals(heartbeatState)) {
            Log.w(TAG, "Heartbeat=" + heartbeatState + " — restarting VPN");
            shouldRestart = true;
        }

        if (shouldRestart) {
            try {
                Intent vpnIntent = new Intent(this, DnsVpnService.class);
                vpnIntent.setAction(DnsVpnService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent);
                } else {
                    startService(vpnIntent);
                }
                Log.i(TAG, "✅ VPN restart triggered from watchdog");
            } catch (Exception e) {
                Log.e(TAG, "Restart failed", e);
            }
        }

        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // Don't retry — periodic schedule will run again
    }

    /** Schedule the periodic watchdog job. Safe to call multiple times. */
    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        // Check if already scheduled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (js.getPendingJob(JOB_ID) != null) {
                Log.d(TAG, "Watchdog already scheduled");
                return;
            }
        }

        ComponentName cn = new ComponentName(context, VpnWatchdogJob.class);
        JobInfo job = new JobInfo.Builder(JOB_ID, cn)
                .setPeriodic(15 * 60 * 1000L)   // Every 15 minutes
                .setPersisted(true)              // Survive reboot
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();

        int result = js.schedule(job);
        Log.d(TAG, result == JobScheduler.RESULT_SUCCESS
                ? "✅ Watchdog scheduled" : "❌ Watchdog schedule failed");
    }

    /** Cancel watchdog (call when user disables VPN filter). */
    public static void cancel(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
        Log.d(TAG, "Watchdog cancelled");
    }
}