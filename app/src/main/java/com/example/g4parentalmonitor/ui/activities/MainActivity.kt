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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.g4parentalmonitor.utils.Constants
import com.example.g4parentalmonitor.receivers.MyAppAdminReceiver
import com.example.g4parentalmonitor.data.PrefsManager
import com.example.g4parentalmonitor.services.LiveGuardianService
import com.example.g4parentalmonitor.services.SyncService
import com.example.g4parentalmonitor.utils.UsageStatsHelper
import com.example.g4parentalmonitor.ui.theme.G4ParentalMonitorTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── Colour palette ──────────────────────────────────────────────────────────
private val BgDeep        = Color(0xFF0C0E18)
private val BgCard        = Color(0xFF161825)
private val BgCardAlt     = Color(0xFF1C1F30)
private val Stroke        = Color(0xFF252840)
private val Blue          = Color(0xFF4E8EF5)
private val BlueDim       = Color(0xFF1D2D52)
private val Green         = Color(0xFF2DCE89)
private val GreenDim      = Color(0xFF0E3028)
private val Orange        = Color(0xFFFF9F43)
private val OrangeDim     = Color(0xFF3A2410)
private val Red           = Color(0xFFFF5C5C)
private val RedDim        = Color(0xFF3A1212)
private val TextHi        = Color(0xFFECEFF8)
private val TextMid       = Color(0xFF7C82A0)
private val TextLo        = Color(0xFF464A65)

class MainActivity : ComponentActivity() {

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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** "2h 14m", "45m", "30s" */
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m"; else -> "${sec}s" }
    }

    private fun timeColor(ms: Long): Color {
        val m = ms / 60_000
        return when { m < 30 -> Green; m < 120 -> Orange; else -> Red }
    }

    private fun timeDimColor(ms: Long): Color {
        val m = ms / 60_000
        return when { m < 30 -> GreenDim; m < 120 -> OrangeDim; else -> RedDim }
    }

    // ─── Root composable ─────────────────────────────────────────────────────

    @Composable
    fun AppUI() {
        val ctx = LocalContext.current
        val scroll = rememberScrollState()
        val lcOwner = LocalLifecycleOwner.current

        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

        DisposableEffect(lcOwner) {
            val obs = LifecycleEventObserver { _, ev ->
                if (ev == Lifecycle.Event.ON_RESUME) tick = System.currentTimeMillis()
            }
            lcOwner.lifecycle.addObserver(obs)
            onDispose { lcOwner.lifecycle.removeObserver(obs) }
        }

        data class AppEntry(val name: String, val pkg: String, val ms: Long)

        var list      by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var updatedAt by remember { mutableStateOf<Long?>(null) }
        var loading   by remember { mutableStateOf(true) }
        var showAll   by remember { mutableStateOf(false) }

        LaunchedEffect(tick) {
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
                        if (ms == 0L) null
                        else AppEntry(pm.getApplicationLabel(app).toString(), app.packageName, ms)
                    }
                    .sortedByDescending { it.ms }
                withContext(Dispatchers.Main) {
                    list = entries; updatedAt = System.currentTimeMillis(); loading = false
                }
            }
        }

        // Permission states (re-read on every recompose triggered by ON_RESUME)
        val usageOk   = hasUsageStatsPermission()
        val filesOk   = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val runtimeOk = areRuntimePermissionsGranted()
        val overlayOk = hasOverlayPermission()
        val batteryOk = isBatteryOptimizationIgnored()
        val adminOk   = isDeviceAdminActive(ctx)
        val accessOk  = isAccessibilityServiceEnabled(ctx, LiveGuardianService::class.java)
        val allOk     = usageOk && filesOk && runtimeOk && overlayOk && batteryOk

        val grantedCount = listOf(usageOk, filesOk, runtimeOk, overlayOk, batteryOk).count { it }
        val progress by animateFloatAsState(grantedCount / 5f, tween(700), label = "prog")

        G4ParentalMonitorTheme {
            Box(Modifier.fillMaxSize().background(BgDeep)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(bottom = 40.dp)
                ) {

                    // ── HERO HEADER ──────────────────────────────────────────
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color(0xFF131729), BgDeep))
                            )
                            .padding(start = 22.dp, end = 22.dp, top = 58.dp, bottom = 26.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BlueDim),
                                    contentAlignment = Alignment.Center
                                ) { Text("🛡️", fontSize = 22.sp) }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        "G4 Parental Monitor",
                                        color = TextHi,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("Device protection & screen-time", color = TextMid, fontSize = 12.sp)
                                }
                            }

                            Spacer(Modifier.height(22.dp))

                            // Setup progress bar
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Setup Progress", color = TextMid, fontSize = 12.sp)
                                Text(
                                    "$grantedCount / 5 done",
                                    color = if (allOk) Green else Orange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (allOk) Green else Blue,
                                trackColor = Stroke,
                                strokeCap = StrokeCap.Round
                            )
                        }
                    }

                    // ── STATUS CHIPS ─────────────────────────────────────────
                    Pad {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MiniChip(
                                Modifier.weight(1f),
                                icon = if (allOk) "✅" else "⚙️",
                                label = "Permissions",
                                value = if (allOk) "All set" else "$grantedCount/5",
                                vc = if (allOk) Green else Orange
                            )
                            MiniChip(
                                Modifier.weight(1f),
                                icon = if (adminOk) "🔒" else "🔓",
                                label = "Anti-Uninstall",
                                value = if (adminOk) "Active" else "Off",
                                vc = if (adminOk) Green else Red
                            )
                            MiniChip(
                                Modifier.weight(1f),
                                icon = "👁️",
                                label = "Guardian",
                                value = if (accessOk) "Active" else "Off",
                                vc = if (accessOk) Green else Red
                            )
                        }
                    }

                    // ── REQUIRED PERMISSIONS ─────────────────────────────────
                    Pad {
                        SectionTitle("Required Permissions", "Tap any row to grant access")
                        Spacer(Modifier.height(10.dp))

                        PermRow("📊", "Usage Access", "Track app screen time", usageOk) {
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

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                if (allOk) {
                                    startMonitoringService()
                                    Toast.makeText(ctx, "✅ Monitoring started!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "Grant all permissions first.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (allOk) Green else BgCardAlt,
                                contentColor   = if (allOk) Color.White else TextMid
                            )
                        ) {
                            Text(
                                if (allOk) "▶  Start Monitoring Service" else "⚙  Complete Setup First",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // ── SECURITY ─────────────────────────────────────────────
                    Pad {
                        SectionTitle("Security Features", "Prevent tampering & uninstall")
                        Spacer(Modifier.height(10.dp))

                        PermRow("🛡️", "Anti-Uninstall", "Requires admin to remove this app", adminOk) {
                            if (!adminOk) {
                                val cn = ComponentName(ctx, MyAppAdminReceiver::class.java)
                                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents uninstallation.")
                                })
                            }
                        }
                        PermRow("👁️", "Live Guardian (Accessibility)", "Blocks restricted apps & settings", accessOk) {
                            if (!accessOk) {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                Toast.makeText(ctx, "Find 'G4 Parental Monitor' and enable it", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    // ── TROUBLESHOOTING ───────────────────────────────────────
                    Pad {
                        SectionTitle("Troubleshooting", null)
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = 0.35f))
                        ) {
                            Text("🔗  Re-Pair / Connect Again", fontSize = 14.sp)
                        }
                    }

                    // ── SCREEN TIME ───────────────────────────────────────────
                    Pad {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Today's Screen Time", color = TextHi, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                updatedAt?.let {
                                    Text(
                                        "Updated ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(it)}",
                                        color = TextMid, fontSize = 11.sp
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(BgCardAlt)
                                    .clickable { tick = System.currentTimeMillis() },
                                contentAlignment = Alignment.Center
                            ) { Text("🔄", fontSize = 18.sp) }
                        }

                        Spacer(Modifier.height(14.dp))

                        Crossfade(loading, label = "usage") { isLoading ->
                            if (isLoading) {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Blue, modifier = Modifier.size(34.dp))
                                        Spacer(Modifier.height(10.dp))
                                        Text("Calculating usage…", color = TextMid, fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Column {
                                    if (!usageOk) {
                                        // Error banner
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(RedDim)
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("⚠️", fontSize = 18.sp)
                                            Spacer(Modifier.width(10.dp))
                                            Text("Usage Access permission required.", color = Red, fontSize = 13.sp)
                                        }
                                    } else if (list.isEmpty()) {
                                        Box(
                                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No usage data yet today.", color = TextMid, fontSize = 14.sp)
                                        }
                                    } else {
                                        // Total summary card
                                        val totalMs = list.sumOf { it.ms }
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(
                                                    Brush.horizontalGradient(listOf(BlueDim, Color(0xFF141E3A)))
                                                )
                                                .padding(18.dp)
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text("Total Today", color = TextMid, fontSize = 11.sp)
                                                    Text(
                                                        fmtMs(totalMs),
                                                        color = Blue,
                                                        fontSize = 30.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("${list.size} apps", color = TextMid, fontSize = 11.sp)
                                                    Text("Since midnight", color = TextLo, fontSize = 10.sp)
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(12.dp))

                                        val display = if (showAll) list else list.take(8)
                                        val maxMs = list.first().ms.coerceAtLeast(1L)

                                        display.forEachIndexed { i, item ->
                                            UsageRow(i + 1, item.name, item.pkg, item.ms, maxMs)
                                            if (i < display.lastIndex) Spacer(Modifier.height(6.dp))
                                        }

                                        if (list.size > 8) {
                                            Spacer(Modifier.height(10.dp))
                                            TextButton(
                                                onClick = { showAll = !showAll },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    if (showAll) "▲  Show Less"
                                                    else "▼  Show All ${list.size} Apps",
                                                    color = Blue, fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Reusable UI atoms ────────────────────────────────────────────────────

    /** Horizontal padding container */
    @Composable
    fun Pad(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp),
            content = content
        )
    }

    @Composable
    fun SectionTitle(title: String, sub: String?) {
        Text(title, color = TextHi, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = TextMid, fontSize = 12.sp)
    }

    @Composable
    fun MiniChip(modifier: Modifier, icon: String, label: String, value: String, vc: Color) {
        Column(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(BgCard)
                .padding(12.dp)
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(label, color = TextMid, fontSize = 10.sp, maxLines = 1)
            Text(value, color = vc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun PermRow(icon: String, name: String, desc: String, granted: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
                .clickable { if (!granted) onClick() }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (granted) GreenDim else BgCardAlt),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 17.sp) }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(name, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = TextMid, fontSize = 11.sp)
            }

            Spacer(Modifier.width(8.dp))

            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (granted) GreenDim else RedDim)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    if (granted) "✓  On" else "Tap →",
                    color = if (granted) Green else Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun UsageRow(rank: Int, name: String, pkg: String, ms: Long, maxMs: Long) {
        val fraction = (ms.toFloat() / maxMs).coerceIn(0.02f, 1f)
        val color = timeColor(ms)
        val dimColor = timeDimColor(ms)

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(BgCard)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Rank badge
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(BgCardAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$rank", color = TextMid, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        color = TextHi,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        pkg,
                        color = TextLo,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Time badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(dimColor)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(fmtMs(ms), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(9.dp))

            // Progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Stroke)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color.copy(alpha = 0.75f))
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
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun startMonitoringService() {
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(context, MyAppAdminReceiver::class.java))
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)
        val setting = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
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

// ─── Data classes ─────────────────────────────────────────────────────────────

data class AppUsageItem(val name: String, val packageName: String, val minutes: Long)
data class RawUsageItem(val name: String, val packageName: String, val minutes: Long, val totalMs: Long)