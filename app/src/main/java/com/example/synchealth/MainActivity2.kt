package com.example.synchealth

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.synchealth.ui.theme.SyncHealthTheme
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType.StepsType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import java.time.LocalDateTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


class MainActivity2 : ComponentActivity() {

    private lateinit var healthDataStore: HealthDataStore
    var stepCount by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            healthDataStore = HealthDataService.getStore(this)
            Log.d("SyncHealth", "Store erfolgreich initialisiert!")

            checkPermissions()

        } catch (e: Exception) {
            Log.e("SyncHealth", "Fehler beim Initialisieren: ${e.message}")
        }

        setContent {
            SyncHealthTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HealthStatusScreen(modifier = Modifier.padding(innerPadding))
                }
            }        }    }

    private fun checkPermissions() {
        try {
            val permissions = setOf(
                Permission.of(DataTypes.NUTRITION, AccessType.READ),
                Permission.of(DataTypes.SLEEP, AccessType.READ),
                Permission.of(DataTypes.WATER_INTAKE, AccessType.READ),
                Permission.of(DataTypes.STEPS, AccessType.READ)
            )

            // Anfrage starten
            val future = healthDataStore.requestPermissionsAsync(permissions, this)
            future.setCallback(
                ContextCompat.getMainExecutor(this),
                { resultPermissions:Set<Permission> ->
                    Log.d("SyncHealth", "Erlaubnis erhalten für: $resultPermissions")
                    readData()
                },
                { throwable ->
                    Log.e("SyncHealth", "Fehler bei der Permission: ${throwable.message}")
                }
            )
        } catch (e: Exception) {
            Log.e("SyncHealth", "Permission Error: ${e.message}")
        }
    }

    private fun readData() {
        try {
            // 1. Zeitfenster definieren (Heute 00:00 bis jetzt)
            val startTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)
            val endTime = LocalDateTime.now()
            val filter = LocalTimeFilter.of(startTime, endTime)

            val request = StepsType.TOTAL.requestBuilder
                .setLocalTimeFilter(filter)
                .build()

            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)

            val readFuture = healthDataStore.aggregateDataAsync(request)

            readFuture.setCallback(
                executor,
                { result ->
                    var totalSteps: Long = 0
                    for (stepData in result.dataList) {
                        totalSteps += stepData.value as Long
                    }

                    runOnUiThread {
                        stepCount = totalSteps
                    }
                },
                { error ->
                    // FEHLER: Falls beim Lesen etwas schiefgeht
                    Log.e("SyncHealth", "Lese-Fehler: ${error.message}")
                }
            )

        } catch (e: Exception) {
            Log.e("SyncHealth", "Allgemeiner Fehler in readMySteps: ${e.message}")
        }
    }

    @Composable
    fun HealthStatusScreen(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Schritte heute: $stepCount",
                fontSize = 32.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }}