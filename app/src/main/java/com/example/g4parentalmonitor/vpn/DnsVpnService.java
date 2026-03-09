package com.example.g4parentalmonitor.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import com.example.g4parentalmonitor.data.PrefsManager;
import com.example.g4parentalmonitor.ui.activities.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * DnsVpnService — DNS-level web filter + SafeSearch enforcer.
 *
 * Keep-Alive layers:
 *   1. START_STICKY              — Android auto-restarts after OOM kill
 *   2. onRevoke()                — fights back when another VPN tries to displace us
 *   3. Screen-on receiver        — re-checks tunnel every time screen wakes
 *   4. Connectivity probe thread — detects silent tunnel death every 15 s
 *   5. Heartbeat pref            — written every 7 min; read by VpnWatchdogJob
 */
public class DnsVpnService extends VpnService {

    public static final String ACTION_START         = "ACTION_VPN_START";
    public static final String ACTION_STOP          = "ACTION_VPN_STOP";
    public static final String CHANNEL_ID           = "g4_vpn_channel";
    public static final int    NOTIFICATION_ID      = 199;

    private static final String TAG          = "DnsVpnService";
    private static final String VPN_ADDRESS  = "10.0.0.1";
    private static final String VPN_DNS      = "10.0.0.2";
    private static final String UPSTREAM_DNS = "8.8.8.8";
    private static final int    DNS_PORT     = 53;
    private static final int    DNS_TIMEOUT  = 3000;

    private static final long HEARTBEAT_MS    = 7 * 60 * 1000L;
    private static final long PROBE_INTERVAL  = 15_000L;
    private static final int  MAX_FAILURES    = 3;
    private static final int  PROBE_TIMEOUT   = 5_000;

    /** Readable by other components to check live state. */
    public static volatile boolean serviceRunning = false;

    private ParcelFileDescriptor vpnInterface;
    private volatile boolean     isRunning = false;

    private Thread vpnThread;
    private Thread heartbeatThread;
    private Thread probeThread;

    private BroadcastReceiver screenReceiver;
    private int consecutiveFailures = 0;

    private PrefsManager    prefs;
    private DnsFilterEngine filterEngine;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        prefs        = new PrefsManager(this);
        filterEngine = new DnsFilterEngine();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn(true);
            return START_NOT_STICKY;
        }
        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            startVpn();
        }
        return START_STICKY;
    }

    /**
     * Called when ANOTHER VPN tries to displace ours.
     * If Prevent-Override is ON → restart immediately.
     */
    @Override
    public void onRevoke() {
        Log.w(TAG, "VPN revoked — another VPN is taking over");
        if (prefs.isVpnFilterEnabled() && prefs.isPreventVpnOverride()) {
            Log.w(TAG, "Prevent-Override ON — restarting in 1 s");
            writeHeartbeat("REVOKED");
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                Intent restart = new Intent(getApplicationContext(), DnsVpnService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplicationContext().startForegroundService(restart);
                } else {
                    getApplicationContext().startService(restart);
                }
            }).start();
        } else {
            stopVpn(false);
        }
    }

    @Override
    public void onDestroy() {
        stopVpn(false);
        super.onDestroy();
    }

    // ── Start / Stop ───────────────────────────────────────────────────────────

    private void startVpn() {
        if (isRunning) return;
        try {
            vpnInterface = new Builder()
                    .setSession("G4 Shield")
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute(VPN_DNS, 32)
                    .addDnsServer(VPN_DNS)
                    .addDisallowedApplication(getPackageName())
                    .setBlocking(true)
                    .establish();

            if (vpnInterface == null) {
                Log.e(TAG, "VPN establish() returned null — permission missing");
                stopSelf();
                return;
            }

            isRunning      = true;
            serviceRunning = true;
            writeHeartbeat("ALIVE");

            vpnThread = new Thread(this::runDnsLoop, "g4-vpn-loop");
            vpnThread.start();
            startHeartbeat();
            startProbe();
            registerScreenReceiver();

            Log.i(TAG, "✅ VPN started");
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            stopVpn(false);
        }
    }

    private void stopVpn(boolean cleanStop) {
        if (!isRunning && !serviceRunning) return;
        isRunning      = false;
        serviceRunning = false;

        writeHeartbeat(cleanStop ? "STOPPED" : "KILLED");
        Log.i(TAG, cleanStop ? "VPN clean stop" : "VPN unexpected stop");

        stopHeartbeat();
        stopProbe();
        unregisterScreenReceiver();

        if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();
    }

    // ── Heartbeat (for VpnWatchdogJob) ────────────────────────────────────────

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            try {
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(HEARTBEAT_MS);
                    if (isRunning) writeHeartbeat("ALIVE");
                }
            } catch (InterruptedException ignored) {}
        }, "g4-vpn-heartbeat");
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        if (heartbeatThread != null) { heartbeatThread.interrupt(); heartbeatThread = null; }
    }

    private void writeHeartbeat(String state) {
        prefs.setVpnHeartbeat(state, System.currentTimeMillis());
    }

    // ── Connectivity probe ─────────────────────────────────────────────────────

    private void startProbe() {
        consecutiveFailures = 0;
        probeThread = new Thread(() -> {
            try {
                Thread.sleep(30_000); // Grace period
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    checkHealth();
                    Thread.sleep(PROBE_INTERVAL);
                }
            } catch (InterruptedException ignored) {}
        }, "g4-vpn-probe");
        probeThread.start();
    }

    private void stopProbe() {
        if (probeThread != null) { probeThread.interrupt(); probeThread = null; }
    }

    private void checkHealth() {
        if (!isRunning) return;
        boolean sockOk  = probeSocket();
        boolean netOk   = hasInternet();
        if (!sockOk && netOk) {
            consecutiveFailures++;
            Log.w(TAG, "Probe fail " + consecutiveFailures + "/" + MAX_FAILURES);
            if (consecutiveFailures >= 2) {
                restartTunnel();
                consecutiveFailures = 0;
            }
        } else {
            if (consecutiveFailures > 0) Log.d(TAG, "Probe recovered");
            consecutiveFailures = 0;
        }
    }

    private boolean probeSocket() {
        try {
            Socket s = new Socket();
            protect(s);
            s.connect(new InetSocketAddress("1.1.1.1", 443), PROBE_TIMEOUT);
            s.close();
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) { return false; }
    }

    private void restartTunnel() {
        if (!isRunning) return;
        Log.i(TAG, "Restarting tunnel…");
        try {
            if (vpnInterface != null) { try { vpnInterface.close(); } catch (Exception ignored) {} vpnInterface = null; }
            if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
            isRunning = false;
            Thread.sleep(500);
            isRunning    = true;
            vpnInterface = new Builder()
                    .setSession("G4 Shield")
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute(VPN_DNS, 32)
                    .addDnsServer(VPN_DNS)
                    .addDisallowedApplication(getPackageName())
                    .setBlocking(true)
                    .establish();
            if (vpnInterface != null) {
                vpnThread = new Thread(this::runDnsLoop, "g4-vpn-loop");
                vpnThread.start();
                Log.i(TAG, "✅ Tunnel restarted");
            } else {
                isRunning = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "restartTunnel failed", e);
            isRunning = false;
        }
    }

    // ── Screen-on receiver ─────────────────────────────────────────────────────

    private void registerScreenReceiver() {
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && isRunning) {
                    new Thread(DnsVpnService.this::checkHealth, "g4-screen-check").start();
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiver = null;
        }
    }

    // ── DNS packet loop ────────────────────────────────────────────────────────

    private void runDnsLoop() {
        if (vpnInterface == null) return;
        FileInputStream  in  = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[]           buf = new byte[32767];

        Log.i(TAG, "DNS loop started");
        try {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                int len = in.read(buf);
                if (len <= 0) continue;

                DnsPacketParser.DnsQuery query = DnsPacketParser.parse(buf, len);
                if (query == null) continue;

                DnsFilterEngine.FilterDecision decision = filterEngine.decide(query.domain, query.queryType);
                byte[] response;

                if (decision instanceof DnsFilterEngine.FilterDecision.Block) {
                    response = DnsPacketParser.buildNxDomainResponse(query);
                    Log.d(TAG, "🚫 " + query.domain);
                } else if (decision instanceof DnsFilterEngine.FilterDecision.SafeSearch) {
                    String ip = ((DnsFilterEngine.FilterDecision.SafeSearch) decision).redirectIp;
                    response = DnsPacketParser.buildARecordResponse(query, ip);
                    Log.d(TAG, "🔍 SafeSearch " + query.domain + " → " + ip);
                } else {
                    response = forwardUpstream(query, buf, len);
                }

                if (response != null) { out.write(response); out.flush(); }
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "DNS loop interrupted");
        } catch (Exception e) {
            if (isRunning) Log.e(TAG, "DNS loop error", e);
        }
    }

    private byte[] forwardUpstream(DnsPacketParser.DnsQuery query, byte[] rawBuf, int rawLen) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            protect(sock);
            sock.setSoTimeout(DNS_TIMEOUT);

            int payloadLen = rawLen - query.dnsPayloadOffset;
            byte[] payload = new byte[payloadLen];
            System.arraycopy(rawBuf, query.dnsPayloadOffset, payload, 0, payloadLen);

            InetAddress up = InetAddress.getByName(UPSTREAM_DNS);
            sock.send(new DatagramPacket(payload, payload.length, up, DNS_PORT));

            byte[] resp = new byte[4096];
            DatagramPacket pkt = new DatagramPacket(resp, resp.length);
            sock.receive(pkt);

            byte[] dns = new byte[pkt.getLength()];
            System.arraycopy(resp, 0, dns, 0, dns.length);
            return DnsPacketParser.wrapUpstreamResponse(query, dns);
        } catch (Exception e) {
            return DnsPacketParser.buildNxDomainResponse(query);
        } finally {
            if (sock != null) try { sock.close(); } catch (Exception ignored) {}
        }
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private Notification buildNotification() {
        Intent stopI = new Intent(this, DnsVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("G4 Shield Active 🛡️")
                .setContentText("DNS filter & SafeSearch running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "G4 Web Filter", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("DNS content filtering is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}