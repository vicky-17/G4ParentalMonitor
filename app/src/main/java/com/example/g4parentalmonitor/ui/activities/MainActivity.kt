package com.example.g4parentalmonitor.ui.activities

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.g4parentalmonitor.data.PrefsManager
import com.example.g4parentalmonitor.receivers.MyAppAdminReceiver
import com.example.g4parentalmonitor.services.LiveGuardianService
import com.example.g4parentalmonitor.services.SyncService
import com.example.g4parentalmonitor.utils.Constants
import com.example.g4parentalmonitor.utils.UsageStatsHelper
import com.example.g4parentalmonitor.ui.theme.G4ParentalMonitorTheme
import com.example.g4parentalmonitor.vpn.DnsVpnService
import com.example.g4parentalmonitor.vpn.VpnWatchdogJob
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ─── Design tokens ────────────────────────────────────────────────────────────

// Base palette — deep navy trust colors
private val Bg0          = Color(0xFF080D1A)   // Deepest background
private val Bg1          = Color(0xFF0D1425)   // Card background
private val Bg2          = Color(0xFF131B2E)   // Elevated card
private val Bg3          = Color(0xFF192238)   // Subtle raised
private val Border       = Color(0xFF1E2D47)

// Trust blues (psychologically safe, authoritative)
private val BlueCore     = Color(0xFF3B8BEB)
private val BlueBright   = Color(0xFF5CA8FF)
private val BlueDim      = Color(0xFF0F1F3D)
private val BlueGlow     = Color(0x2038AAFF)

// Status colors
private val EmeraldOn    = Color(0xFF00C896)
private val EmeraldDim   = Color(0xFF062921)
private val EmeraldGlow  = Color(0x2000C896)
private val AmberWarn    = Color(0xFFFFAD2E)
private val AmberDim     = Color(0xFF2A1D06)
private val CrimsonOff   = Color(0xFFFF4D6A)
private val CrimsonDim   = Color(0xFF2A0812)

// Text
private val TxtPrimary   = Color(0xFFE8EDF8)
private val TxtSecondary = Color(0xFF6E7FA8)
private val TxtTertiary  = Color(0xFF3A4668)

// Shield gradient
private val ShieldGrad   = listOf(Color(0xFF1A6EF5), Color(0xFF0A3A9A))

class MainActivity : ComponentActivity() {

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // After VPN permission dialog — check if granted and start
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("SYNC_NOW", false)) {
            Toast.makeText(this, "Syncing app data…", Toast.LENGTH_LONG).show()
            uploadDeviceData()
        }
        setContent { AppUI() }
    }

    // ─── VPN helpers ──────────────────────────────────────────────────────────

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Need to ask user for VPN permission
            vpnPermLauncher.launch(intent)
        } else {
            // Already granted — start immediately
            startVpnService()
        }
    }

    fun startVpnService() {
        val prefs = PrefsManager(this)
        prefs.setVpnFilterEnabled(true)
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        VpnWatchdogJob.schedule(this)
        Toast.makeText(this, "🛡️ G4 Shield activated", Toast.LENGTH_SHORT).show()
    }

    fun stopVpnService() {
        val prefs = PrefsManager(this)
        prefs.setVpnFilterEnabled(false)
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_STOP
        startService(intent)
        VpnWatchdogJob.cancel(this)
        Toast.makeText(this, "Shield deactivated", Toast.LENGTH_SHORT).show()
    }

    fun isVpnPermissionGranted(): Boolean =
        VpnService.prepare(this) == null

    // ─── Root composable ──────────────────────────────────────────────────────

    @Composable
    fun AppUI() {
        val ctx         = LocalContext.current
        val scroll      = rememberScrollState()
        val lcOwner     = LocalLifecycleOwner.current
        val prefs       = remember { PrefsManager(ctx) }

        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

        DisposableEffect(lcOwner) {
            val obs = LifecycleEventObserver { _, ev ->
                if (ev == Lifecycle.Event.ON_RESUME) tick = System.currentTimeMillis()
            }
            lcOwner.lifecycle.addObserver(obs)
            onDispose { lcOwner.lifecycle.removeObserver(obs) }
        }

        // Permission states — re-evaluated on every resume (tick changes)
        var usageOk   by remember { mutableStateOf(hasUsageStatsPermission()) }
        var filesOk   by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) }
        var runtimeOk by remember { mutableStateOf(areRuntimePermissionsGranted()) }
        var overlayOk by remember { mutableStateOf(hasOverlayPermission()) }
        var batteryOk by remember { mutableStateOf(isBatteryOptimizationIgnored()) }
        var adminOk   by remember { mutableStateOf(isDeviceAdminActive(ctx)) }
        var accessOk  by remember { mutableStateOf(isAccessibilityServiceEnabled(ctx, LiveGuardianService::class.java)) }
        var vpnPermOk by remember { mutableStateOf(isVpnPermissionGranted()) }
        val allOk     = usageOk && filesOk && runtimeOk && overlayOk && batteryOk

        // VPN state — read from prefs AND live service flag
        var vpnEnabled        by remember { mutableStateOf(prefs.isVpnFilterEnabled()) }
        var safeSearchEnabled by remember { mutableStateOf(prefs.isVpnSafeSearchEnabled()) }
        var blockAdultEnabled by remember { mutableStateOf(prefs.isVpnBlockAdultEnabled()) }
        var keepAliveEnabled  by remember { mutableStateOf(prefs.isKeepVpnAlive()) }
        var preventOverride   by remember { mutableStateOf(prefs.isPreventVpnOverride()) }

        val vpnRunning = vpnEnabled  // UI reflects toggle immediately; serviceRunning syncs on resume

        // App usage data
        var list      by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var updatedAt by remember { mutableStateOf<Long?>(null) }
        var loading   by remember { mutableStateOf(true) }
        var showAll   by remember { mutableStateOf(false) }

        val grantedCount = listOf(usageOk, filesOk, runtimeOk, overlayOk, batteryOk).count { it }
        val progress by animateFloatAsState(grantedCount / 5f, tween(700), label = "prog")

        LaunchedEffect(tick) {
            // Refresh all permission states on every resume
            usageOk   = hasUsageStatsPermission()
            filesOk   = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            runtimeOk = areRuntimePermissionsGranted()
            overlayOk = hasOverlayPermission()
            batteryOk = isBatteryOptimizationIgnored()
            adminOk   = isDeviceAdminActive(ctx)
            accessOk  = isAccessibilityServiceEnabled(ctx, LiveGuardianService::class.java)
            vpnPermOk = isVpnPermissionGranted()

            // Sync VPN toggle with actual running state
            val vpnActuallyRunning = DnsVpnService.serviceRunning
            if (vpnActuallyRunning != vpnEnabled) vpnEnabled = vpnActuallyRunning

            loading = true
            withContext(Dispatchers.IO) {
                val usageMap = UsageStatsHelper.getEventBasedDailyUsage(ctx)
                val pm = ctx.packageManager
                val entries = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val sys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        !sys || pm.getLaunchIntentForPackage(app.packageName) != null
                    }
                    .mapNotNull { app ->
                        val ms = usageMap[app.packageName] ?: 0L
                        if (ms == 0L) null else AppEntry(pm.getApplicationLabel(app).toString(), app.packageName, ms)
                    }
                    .sortedByDescending { it.ms }
                withContext(Dispatchers.Main) {
                    list = entries; updatedAt = System.currentTimeMillis(); loading = false
                }
            }
        }

        G4ParentalMonitorTheme {
            Box(Modifier.fillMaxSize().background(Bg0)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(bottom = 48.dp)
                ) {
                    // ── HERO HEADER ───────────────────────────────────────────
                    HeroHeader(
                        vpnRunning   = vpnRunning,
                        vpnEnabled   = vpnEnabled,
                        accessOk     = accessOk,
                        adminOk      = adminOk,
                        progress     = progress,
                        grantedCount = grantedCount,
                        allOk        = allOk
                    )

                    Spacer(Modifier.height(8.dp))

                    // ── G4 SHIELD — VPN SECTION ───────────────────────────────
                    Section {
                        ShieldSection(
                            vpnEnabled        = vpnEnabled,
                            vpnPermOk         = vpnPermOk,
                            safeSearchEnabled = safeSearchEnabled,
                            blockAdultEnabled = blockAdultEnabled,
                            keepAliveEnabled  = keepAliveEnabled,
                            preventOverride   = preventOverride,
                            accessOk          = accessOk,
                            onVpnToggle       = { wantOn ->
                                if (wantOn) {
                                    requestVpnPermission()
                                    vpnEnabled = true
                                } else {
                                    stopVpnService()
                                    vpnEnabled = false
                                }
                            },
                            onSafeSearch = { v -> safeSearchEnabled = v; prefs.setVpnSafeSearchEnabled(v) },
                            onBlockAdult = { v -> blockAdultEnabled = v; prefs.setVpnBlockAdultEnabled(v) },
                            onKeepAlive  = { v -> keepAliveEnabled  = v; prefs.setKeepVpnAlive(v) },
                            onPreventOverride = { v -> preventOverride = v; prefs.setPreventVpnOverride(v) }
                        )
                    }

                    // ── REQUIRED PERMISSIONS ──────────────────────────────────
                    Section {
                        SectionHeader("Required Permissions", "Tap any row to grant access")
                        Spacer(Modifier.height(12.dp))

                        PermRow("📊", "Usage Access", "Track screen time per app", usageOk) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            PermRow("📁", "All Files Access", "Needed for monitoring logs", filesOk) {
                                try {
                                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName")))
                                } catch (e: Exception) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        }
                        PermRow("📍", "Location & Camera", "GPS tracking & monitoring", runtimeOk) {
                            if (!runtimeOk) requestRuntimePermissions()
                        }
                        PermRow("🪟", "Display Over Apps", "Show app-blocked overlay", overlayOk) {
                            if (!overlayOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                        PermRow("🔋", "Ignore Battery Opt.", "Keep service always running", batteryOk) {
                            if (!batteryOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName")))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Settings → Battery → Optimization", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        StartButton(allOk = allOk, ctx = ctx)
                    }

                    // ── SECURITY ──────────────────────────────────────────────
                    Section {
                        SectionHeader("Security Features", "Prevent tampering & uninstall")
                        Spacer(Modifier.height(12.dp))

                        PermRow("🛡️", "Anti-Uninstall", "Admin lock prevents removal", adminOk) {
                            if (!adminOk) {
                                val cn = ComponentName(ctx, MyAppAdminReceiver::class.java)
                                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents uninstallation.")
                                })
                            }
                        }
                        PermRow("👁️", "Live Guardian", "Blocks restricted apps & websites", accessOk) {
                            if (!accessOk) {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                Toast.makeText(ctx, "Find 'G4 Parental Monitor' and enable it", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    // ── TROUBLESHOOTING ───────────────────────────────────────
                    Section {
                        SectionHeader("Troubleshooting", null)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(ctx, PairingActivity::class.java).apply {
                                    putExtra("FORCE_PAIR", true)
                                })
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonOff),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonOff.copy(alpha = 0.3f))
                        ) {
                            Text("🔗  Re-Pair / Connect Again", fontSize = 14.sp)
                        }
                    }

                    // ── SCREEN TIME ───────────────────────────────────────────
                    Section {
                        ScreenTimeSection(
                            list = list, updatedAt = updatedAt, loading = loading,
                            showAll = showAll, usageOk = usageOk,
                            onRefresh = { tick = System.currentTimeMillis() },
                            onShowAll = { showAll = !showAll }
                        )
                    }
                }
            }
        }
    }

    // ─── Hero Header ──────────────────────────────────────────────────────────

    @Composable
    fun HeroHeader(
        vpnRunning: Boolean, vpnEnabled: Boolean, accessOk: Boolean,
        adminOk: Boolean, progress: Float, grantedCount: Int, allOk: Boolean
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A1628), Color(0xFF080D1A))
                    )
                )
        ) {
            // Subtle glow orb in background
            Box(
                Modifier
                    .size(260.dp)
                    .offset(x = (-40).dp, y = (-60).dp)
                    .blur(80.dp)
                    .background(BlueGlow, CircleShape)
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 56.dp, bottom = 28.dp)
            ) {
                // App identity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedShieldIcon(active = vpnRunning || accessOk)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "G4 Guardian",
                            color = TxtPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            "Parental protection system",
                            color = TxtSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Live status pill
                    LiveStatusPill(vpnRunning = vpnRunning, accessOk = accessOk)
                }

                Spacer(Modifier.height(24.dp))

                // Status chips row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(Modifier.weight(1f), icon = if (allOk) "✅" else "⚙️",
                        label = "Setup", value = if (allOk) "Done" else "$grantedCount/5",
                        tint = if (allOk) EmeraldOn else AmberWarn)
                    StatusChip(Modifier.weight(1f), icon = if (adminOk) "🔒" else "🔓",
                        label = "Uninstall", value = if (adminOk) "Locked" else "Open",
                        tint = if (adminOk) EmeraldOn else CrimsonOff)
                    StatusChip(Modifier.weight(1f), icon = "🌐",
                        label = "Filter", value = if (vpnRunning) "Active" else "Off",
                        tint = if (vpnRunning) EmeraldOn else TxtSecondary)
                }

                Spacer(Modifier.height(20.dp))

                // Setup progress
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Setup Progress", color = TxtSecondary, fontSize = 12.sp)
                    Text(
                        "$grantedCount / 5 permissions",
                        color = if (allOk) EmeraldOn else AmberWarn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Border)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(BlueCore, if (allOk) EmeraldOn else BlueBright)
                                )
                            )
                    )
                }
            }
        }
    }

    @Composable
    fun AnimatedShieldIcon(active: Boolean) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val scale by pulse.animateFloat(
            initialValue = 1f,
            targetValue  = if (active) 1.08f else 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        val glowAlpha by pulse.animateFloat(
            initialValue = 0.3f,
            targetValue  = if (active) 0.7f else 0.3f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow"
        )

        Box(contentAlignment = Alignment.Center) {
            // Glow ring
            Box(
                Modifier
                    .size(58.dp)
                    .scale(scale)
                    .blur(8.dp)
                    .background(
                        if (active) EmeraldOn.copy(alpha = glowAlpha)
                        else BlueCore.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
            // Shield container
            Box(
                Modifier
                    .size(48.dp)
                    .scale(scale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            if (active)
                                listOf(Color(0xFF00B87D), Color(0xFF006D4C))
                            else
                                ShieldGrad
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("🛡️", fontSize = 22.sp)
            }
        }
    }

    @Composable
    fun LiveStatusPill(vpnRunning: Boolean, accessOk: Boolean) {
        val active = vpnRunning || accessOk
        val pulse  = rememberInfiniteTransition(label = "dotpulse")
        val dotAlpha by pulse.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "dot"
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (active) EmeraldDim else Bg2)
                .border(1.dp, if (active) EmeraldOn.copy(.25f) else Border, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        if (active) EmeraldOn.copy(alpha = dotAlpha) else TxtTertiary,
                        CircleShape
                    )
            )
            Text(
                if (active) "Protected" else "Inactive",
                color = if (active) EmeraldOn else TxtSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // ─── Shield / VPN Section ────────────────────────────────────────────────

    @Composable
    fun ShieldSection(
        vpnEnabled: Boolean,
        vpnPermOk: Boolean,
        safeSearchEnabled: Boolean,
        blockAdultEnabled: Boolean,
        keepAliveEnabled: Boolean,
        preventOverride: Boolean,
        accessOk: Boolean,
        onVpnToggle: (Boolean) -> Unit,
        onSafeSearch: (Boolean) -> Unit,
        onBlockAdult: (Boolean) -> Unit,
        onKeepAlive: (Boolean) -> Unit,
        onPreventOverride: (Boolean) -> Unit
    ) {
        // Section header with shield icon
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(ShieldGrad)),
                contentAlignment = Alignment.Center
            ) { Text("🌐", fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("G4 Shield — DNS Filter", color = TxtPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Blocks harmful content at the network level", color = TxtSecondary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // VPN Permission + Master toggle card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        if (vpnEnabled)
                            listOf(Color(0xFF072B4A), Color(0xFF040F1E))
                        else
                            listOf(Bg2, Bg1)
                    )
                )
                .border(
                    1.dp,
                    if (vpnEnabled) BlueCore.copy(.35f) else Border,
                    RoundedCornerShape(16.dp)
                )
                .padding(18.dp)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Web Content Filter",
                            color = TxtPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (vpnEnabled) "VPN tunnel active · DNS filtering ON"
                            else if (!vpnPermOk) "Tap toggle to grant VPN permission"
                            else "Tap to enable DNS-level filtering",
                            color = if (vpnEnabled) BlueBright else TxtSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    AnimatedToggle(checked = vpnEnabled, onCheckedChange = onVpnToggle)
                }

                // VPN Permission status row
                if (!vpnPermOk && !vpnEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmberDim)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("VPN permission required", color = AmberWarn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Tap the toggle above — Android will show a confirmation dialog", color = AmberWarn.copy(.75f), fontSize = 11.sp)
                        }
                    }
                } else if (vpnPermOk && vpnEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(EmeraldDim)
                            .border(1.dp, EmeraldOn.copy(.2f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✅", fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Shield is active", color = EmeraldOn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Harmful domains are blocked · SafeSearch enforced", color = EmeraldOn.copy(.7f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Web history capture mode banner
        val captureDesc = when {
            vpnEnabled && accessOk -> "🎯 Full Coverage — Accessibility + VPN both active"
            accessOk               -> "👁️ Accessibility capturing browser URLs"
            vpnEnabled             -> "🔍 VPN DNS capturing all domain requests"
            else                   -> "⚠️ No URL capture active — enable Guardian or Shield"
        }
        val captureTint = when {
            vpnEnabled && accessOk -> EmeraldOn
            accessOk || vpnEnabled -> BlueBright
            else                   -> AmberWarn
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (vpnEnabled && accessOk) EmeraldDim else BlueDim)
                .border(1.dp, captureTint.copy(.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(captureDesc, color = captureTint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(14.dp))

        // Sub-toggles (only visible when VPN is enabled)
        AnimatedVisibility(
            visible = vpnEnabled,
            enter = fadeIn() + expandVertically(),
            exit  = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Filter Settings", color = TxtSecondary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))

                SubToggle(
                    icon = "🔍",
                    title = "Force SafeSearch",
                    desc  = "Redirects Google, YouTube & Bing to safe search results",
                    tint  = BlueCore,
                    checked = safeSearchEnabled,
                    onChecked = onSafeSearch
                )
                SubToggle(
                    icon = "🚫",
                    title = "Block Adult Content",
                    desc  = "NXDOMAIN for known harmful / adult domains",
                    tint  = CrimsonOff,
                    checked = blockAdultEnabled,
                    onChecked = onBlockAdult
                )
                SubToggle(
                    icon = "♻️",
                    title = "Auto-Restart if Killed",
                    desc  = "Watchdog + screen-on checks keep VPN always running",
                    tint  = EmeraldOn,
                    checked = keepAliveEnabled,
                    onChecked = onKeepAlive
                )
                SubToggle(
                    icon = "🔐",
                    title = "Block VPN Override",
                    desc  = "Restart immediately if another VPN tries to displace G4",
                    tint  = AmberWarn,
                    checked = preventOverride,
                    onChecked = onPreventOverride
                )
            }
        }
    }

    @Composable
    fun AnimatedToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        val bgColor by animateColorAsState(
            targetValue = if (checked) BlueCore else Bg3,
            animationSpec = tween(250),
            label = "toggleBg"
        )
        val thumbX by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "thumb"
        )

        Box(
            Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, if (checked) BlueCore.copy(.6f) else Border, RoundedCornerShape(14.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onCheckedChange(!checked) },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(start = (4 + thumbX * 24).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }

    @Composable
    fun SubToggle(
        icon: String, title: String, desc: String,
        tint: Color, checked: Boolean, onChecked: (Boolean) -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Bg2)
                .border(1.dp, if (checked) tint.copy(.2f) else Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) tint.copy(.15f) else Bg3),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 16.sp) }

            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TxtPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = TxtSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            AnimatedToggle(checked = checked, onCheckedChange = onChecked)
        }
    }

    // ─── Permissions section ─────────────────────────────────────────────────

    @Composable
    fun StartButton(allOk: Boolean, ctx: Context) {
        val scale by animateFloatAsState(if (allOk) 1f else 0.98f, tween(200), label = "btn")
        Button(
            onClick = {
                if (allOk) {
                    startMonitoringService()
                    Toast.makeText(ctx, "✅ Monitoring started!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Grant all permissions first.", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp).scale(scale),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            if (allOk) listOf(Color(0xFF1A6EF5), Color(0xFF00C896))
                            else listOf(Bg3, Bg3)
                        ),
                        RoundedCornerShape(15.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (allOk) "▶  Start Monitoring Service" else "⚙  Complete Setup First",
                    color = if (allOk) Color.White else TxtSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // ─── Screen Time Section ─────────────────────────────────────────────────

    @Composable
    fun ScreenTimeSection(
        list: List<AppEntry>,
        updatedAt: Long?,
        loading: Boolean,
        showAll: Boolean,
        usageOk: Boolean,
        onRefresh: () -> Unit,
        onShowAll: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Today's Screen Time", color = TxtPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                updatedAt?.let {
                    Text(
                        "Updated ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(it)}",
                        color = TxtSecondary, fontSize = 11.sp
                    )
                }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Bg2)
                    .border(1.dp, Border, CircleShape)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center
            ) { Text("🔄", fontSize = 18.sp) }
        }

        Spacer(Modifier.height(14.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BlueCore, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("Calculating usage…", color = TxtSecondary, fontSize = 13.sp)
                }
            }
        } else if (!usageOk) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CrimsonDim)
                    .border(1.dp, CrimsonOff.copy(.25f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚠️", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text("Usage Access permission required.", color = CrimsonOff, fontSize = 13.sp)
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("No usage data today yet.", color = TxtSecondary, fontSize = 14.sp)
            }
        } else {
            val displayList = if (showAll) list else list.take(5)
            displayList.forEach { entry ->
                val totalMinutes = entry.ms / 60_000
                val hours = totalMinutes / 60
                val mins  = totalMinutes % 60
                val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg2)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, color = TxtPrimary, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(entry.pkg, color = TxtTertiary, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(timeStr, color = BlueCore, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (list.size > 5) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Bg2)
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .clickable(onClick = onShowAll)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (showAll) "Show less ▲" else "Show all ${list.size} apps ▼",
                        color = BlueCore, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // ─── Reusable UI atoms ────────────────────────────────────────────────────

    @Composable
    fun Section(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Bg1)
                .border(1.dp, Border, RoundedCornerShape(20.dp))
                .padding(18.dp),
            content = content
        )
    }

    @Composable
    fun SectionHeader(title: String, sub: String?) {
        Text(title, color = TxtPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = TxtSecondary, fontSize = 12.sp)
    }

    @Composable
    fun StatusChip(modifier: Modifier, icon: String, label: String, value: String, tint: Color) {
        Column(
            modifier
                .clip(RoundedCornerShape(13.dp))
                .background(Bg2)
                .border(1.dp, tint.copy(.2f), RoundedCornerShape(13.dp))
                .padding(12.dp)
        ) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.height(5.dp))
            Text(label, color = TxtSecondary, fontSize = 10.sp, maxLines = 1)
            Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun PermRow(icon: String, name: String, desc: String, granted: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Bg2)
                .border(
                    1.dp,
                    if (granted) EmeraldOn.copy(.2f) else Border,
                    RoundedCornerShape(12.dp)
                )
                .clickable { if (!granted) onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (granted) EmeraldDim else Bg3),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 17.sp) }

            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TxtPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = TxtSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (granted) EmeraldDim else CrimsonDim)
                    .border(1.dp,
                        if (granted) EmeraldOn.copy(.3f) else CrimsonOff.copy(.3f),
                        RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    if (granted) "✓  On" else "Tap →",
                    color = if (granted) EmeraldOn else CrimsonOff,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // ─── Permission helpers ───────────────────────────────────────────────────

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun areRuntimePermissionsGranted(): Boolean {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun startMonitoringService() {
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(context, MyAppAdminReceiver::class.java))
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)
        val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        while (splitter.hasNext()) {
            val c = ComponentName.unflattenFromString(splitter.next())
            if (c != null && c == expected) return true
        }
        return false
    }

    private fun uploadDeviceData() {
        val prefs = PrefsManager(this)
        val deviceId = prefs.deviceId ?: return
        val pm = packageManager
        val jsonArray = JSONArray()
        for (pkg in pm.getInstalledPackages(0)) {
            val info = pkg.applicationInfo ?: continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!isSystem || isUpdated) {
                jsonArray.put(JSONObject().apply {
                    put("appName", info.loadLabel(pm).toString())
                    put("packageName", pkg.packageName)
                    put("seconds", 0)
                    put("initialSync", true)
                })
            }
        }
        val body = JSONObject().apply {
            put("deviceId", deviceId); put("apps", jsonArray)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        OkHttpClient().newCall(
            Request.Builder().url(Constants.APPS_URL).post(body).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Sync", "Upload failed", e)
                runOnUiThread { Toast.makeText(applicationContext, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    runOnUiThread { Toast.makeText(applicationContext, "Apps uploaded!", Toast.LENGTH_SHORT).show() }
                    startMonitoringService()
                } else Log.e("Sync", "Server error: ${response.code}")
            }
        })
    }
}

data class AppUsageItem(val name: String, val packageName: String, val minutes: Long)
data class RawUsageItem(val name: String, val packageName: String, val minutes: Long, val totalMs: Long)
data class AppEntry(val name: String, val pkg: String, val ms: Long)


