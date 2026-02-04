package com.example.g4parentalmonitor

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
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.g4parentalmonitor.ui.theme.G4ParentalMonitorTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {

    // REMOVED: private val BASE_URL (Using Constants.APPS_URL now)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // No need to setContent here, the LifecycleObserver will handle the refresh
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.getBooleanExtra("SYNC_NOW", false)) {
            Toast.makeText(this, "Syncing App Data...", Toast.LENGTH_LONG).show()
            uploadDeviceData()
        }

        setContent { AppUI() }
    }

    // REMOVED: onResume(). We handle updates inside AppUI now.

    @Composable
    fun AppUI() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()
        val lifecycleOwner = LocalLifecycleOwner.current

        // --- AUTOMATIC REFRESH LOGIC ---
        // This variable forces the UI to redraw when it changes
        var refreshTrigger by remember { mutableLongStateOf(System.currentTimeMillis()) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // When user comes back to the app, update this value to force a redraw
                    refreshTrigger = System.currentTimeMillis()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        // -------------------------------

        G4ParentalMonitorTheme {
            // key(refreshTrigger) ensures everything inside re-evaluates when we return
            key(refreshTrigger) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(20.dp)
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text("G4 Monitor Setup", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Grant these permissions to activate monitoring.", color = Color.Gray)

                        Spacer(modifier = Modifier.height(40.dp))
                        HorizontalDivider(thickness = 1.dp, color = Color.LightGray)
                        Spacer(modifier = Modifier.height(20.dp))

                        Text("Troubleshooting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, PairingActivity::class.java)
                                intent.putExtra("FORCE_PAIR", true)
                                startActivity(intent)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Text("Connect Again / Re-pair Device")
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // --- BASIC PERMISSIONS ---

                        // Check permissions (Re-runs every time refreshTrigger changes)
                        val isUsageGranted = hasUsageStatsPermission()

                        PermissionItem(
                            name = "1. Usage Access",
                            description = "Required to see app usage time.",
                            isGranted = isUsageGranted,
                            onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            PermissionItem(
                                name = "2. All Files Access",
                                description = "Required for monitoring logs.",
                                isGranted = Environment.isExternalStorageManager(),
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        intent.data = Uri.parse("package:$packageName")
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    }
                                }
                            )
                        }

                        val runtimePermissionsGranted = areRuntimePermissionsGranted()
                        PermissionItem(
                            name = "3. Location & Camera",
                            description = "Standard Android permissions.",
                            isGranted = runtimePermissionsGranted,
                            onClick = { if (!runtimePermissionsGranted) requestRuntimePermissions() }
                        )

                        Spacer(modifier = Modifier.height(30.dp))

                        val allGood = isUsageGranted &&
                                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) &&
                                runtimePermissionsGranted

                        Button(
                            onClick = {
                                if (allGood) {
                                    startMonitoringService()
                                    Toast.makeText(context, "Service Started!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please grant all permissions first.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = if (allGood) Color(0xFF4CAF50) else Color.Gray)
                        ) {
                            Text(if (allGood) "START MONITORING SERVICE" else "SETUP INCOMPLETE")
                        }

                        // --- SECURITY SETUP SECTION ---
                        Spacer(modifier = Modifier.height(30.dp))

                        HorizontalDivider(thickness = 1.dp, color = Color.LightGray)

                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Security Features", style = MaterialTheme.typography.titleLarge)
                        Text("Enable these to prevent uninstallation.", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // 1. Anti-Uninstall
                        val isAdmin = isDeviceAdminActive(context)
                        PermissionItem(
                            name = "1. Anti-Uninstall",
                            description = "Prevents the app from being deleted.",
                            isGranted = isAdmin,
                            onClick = {
                                if (!isAdmin) {
                                    val compName = ComponentName(context, MyAppAdminReceiver::class.java)
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents uninstallation.")
                                    startActivity(intent)
                                } else {
                                    Toast.makeText(context, "Anti-Uninstall is already active!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        // 2. Settings Lock
                        val isAccessibilityEnabled = isAccessibilityServiceEnabled(context, SettingsBlockerService::class.java)
                        PermissionItem(
                            name = "2. Lock Settings",
                            description = "Blocks access to Settings without PIN.",
                            isGranted = isAccessibilityEnabled,
                            onClick = {
                                if (!isAccessibilityEnabled) {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    startActivity(intent)
                                    Toast.makeText(context, "Find 'G4 Parental Monitor' and turn ON", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Settings Lock is active!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionItem(name: String, description: String, isGranted: Boolean, onClick: () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(text = description, fontSize = 12.sp, color = Color.Gray)
                }
                Text(
                    text = if (isGranted) "✅" else "❌",
                    fontSize = 20.sp
                )
            }
        }
    }

    // --- HELPER FUNCTIONS ---

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun areRuntimePermissionsGranted(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRuntimePermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, SyncService::class.java) // <--- Changed to SyncService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(context, MyAppAdminReceiver::class.java)
        return dpm.isAdminActive(compName)
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName)
                return true
        }
        return false
    }

    // --- UPLOAD DEVICE DATA (Using Constants) ---
    private fun uploadDeviceData() {
        val prefs = PrefsManager(this)
        val deviceId = prefs.deviceId ?: return

        val pm = packageManager
        val appsList = pm.getInstalledPackages(0)
        val jsonArray = JSONArray()

        for (pkg in appsList) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (!isSystem || isUpdated) {
                val appObj = JSONObject()
                appObj.put("appName", appInfo.loadLabel(pm).toString())
                appObj.put("packageName", pkg.packageName)
                appObj.put("minutes", 0)
                appObj.put("lastTime", System.currentTimeMillis())
                jsonArray.put(appObj)
            }
        }

        val jsonBody = JSONObject()
        jsonBody.put("deviceId", deviceId)
        jsonBody.put("apps", jsonArray)

        val client = OkHttpClient()
        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(Constants.APPS_URL) // <--- Correctly using Constants
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Sync", "Failed to upload apps", e)
                runOnUiThread {
                    Toast.makeText(applicationContext, "Sync Failed: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Apps Uploaded Successfully!", Toast.LENGTH_SHORT).show()
                    }
                    startMonitoringService()
                } else {
                    Log.e("Sync", "Server Error: " + response.code)
                }
            }
        })
    }
}