package com.example.g4parentalmonitor;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * JobService that ensures SyncService is always running
 * Runs periodically to check and restart the service if needed
 */
public class ServiceRestartJob extends JobService {
    
    private static final String TAG = "ServiceRestartJob";
    private static final int JOB_ID = 1001;
    
    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "🔄 Job triggered - Checking SyncService status...");
        
        // Check if device is paired
        PrefsManager prefs = new PrefsManager(this);
        if (!prefs.isPaired()) {
            Log.d(TAG, "Device not paired. Skipping.");
            jobFinished(params, false);
            return false;
        }
        
        // Start SyncService
        startSyncService();
        
        // Schedule next run
        scheduleJob(this);
        
        jobFinished(params, false);
        return false;
    }
    
    @Override
    public boolean onStopJob(JobParameters params) {
        // Reschedule if stopped
        scheduleJob(this);
        return true;
    }
    
    /**
     * Start SyncService in foreground mode
     */
    private void startSyncService() {
        try {
            Intent serviceIntent = new Intent(this, SyncService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            Log.d(TAG, "✅ SyncService restart attempt completed");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to restart SyncService", e);
        }
    }
    
    /**
     * Schedule this job to run periodically
     * This ensures the service is always checked and restarted if needed
     */
    public static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            
            if (jobScheduler != null) {
                ComponentName componentName = new ComponentName(context, ServiceRestartJob.class);
                
                JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, componentName)
                        .setPersisted(true) // Survives device reboot
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE) // No network required
                        .setPeriodic(15 * 60 * 1000); // Run every 15 minutes
                
                // Additional constraints for better reliability
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setMinimumLatency(0); // Run as soon as possible
                }
                
                int result = jobScheduler.schedule(builder.build());
                
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "✅ Job scheduled successfully");
                } else {
                    Log.e(TAG, "❌ Job scheduling failed");
                }
            }
        }
    }
    
    /**
     * Cancel the scheduled job
     */
    public static void cancelJob(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                jobScheduler.cancel(JOB_ID);
                Log.d(TAG, "Job cancelled");
            }
        }
    }
}
