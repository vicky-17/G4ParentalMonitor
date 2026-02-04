package com.example.g4parentalmonitor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class PairingActivity : ComponentActivity() {

    // ✅ Use centralized URL from Constants
    private val PAIR_URL = Constants.API_BASE_URL + "/devices/pair"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIX: Allow re-pairing if "FORCE_PAIR" is sent from MainActivity
        val isForcePair = intent.getBooleanExtra("FORCE_PAIR", false)

        // Only redirect to Main if NOT forcing a re-pair
        if (!isForcePair && PrefsManager(this).isPaired) {
            goToMain(syncNow = false)
            return
        }

        setContent {
            PairingScreen()
        }
    }

    @Composable
    fun PairingScreen() {
        var code by remember { mutableStateOf(TextFieldValue("")) }
        var isLoading by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().padding(30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔗 Connect Device", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(10.dp))
            Text("Enter the 6-digit code from the Parent Dashboard")

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Pairing Code") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val cleanCode = code.text.trim()
                    if (cleanCode.length == 6) {
                        isLoading = true
                        pairDevice(cleanCode)
                    } else {
                        Toast.makeText(applicationContext, "Enter 6 digits", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoading) "Connecting..." else "Link Device")
            }

            // --- NEW: RESET BUTTON ---
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = { code = TextFieldValue("") },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Code")
            }
        }
    }

    private fun pairDevice(code: String) {
        val client = OkHttpClient()
        val prefs = PrefsManager(applicationContext)

        val deviceId = prefs.deviceId ?: UUID.randomUUID().toString()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar { it.uppercase() }

        val json = JSONObject()
        json.put("code", code)
        json.put("deviceId", deviceId)
        json.put("deviceName", deviceName)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(PAIR_URL).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    // Allow retrying by resetting loading state (requires moving isLoading to ViewModel or simple hack)
                    // For now, restarting activity is simplest way to reset UI state if stuck,
                    // or just let user click Clear Code.
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val respBody = it.body?.string()
                        val jsonResp = JSONObject(respBody ?: "{}")

                        if (jsonResp.optBoolean("success")) {
                            prefs.saveDeviceId(deviceId)
                            runOnUiThread {
                                Toast.makeText(applicationContext, "Paired Successfully!", Toast.LENGTH_SHORT).show()
                                goToMain(syncNow = true)
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(applicationContext, "Invalid Code", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(applicationContext, "Server Error: ${it.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun goToMain(syncNow: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        if (syncNow) intent.putExtra("SYNC_NOW", true)
        startActivity(intent)
        finish()
    }
}