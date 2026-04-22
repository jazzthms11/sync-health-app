package com.example.synchealth

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.synchealth.ui.theme.SyncHealthTheme
import com.google.api.services.drive.Drive.Builder
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import com.samsung.android.sdk.health.data.response.AsyncSingleFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


data class HealthReport(
    val steps: Long = 0,
    val calories: Double = 0.0,
    val water: Double = 0.0,
    val sleep: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
)

fun HealthReport.toObsidianBlock(): String {
    return """
        # Gesundheit
        - Gewicht:: 
        - Schlaf:: ${String.format("%.2f", this.sleep)}
        - Schritte:: ${this.steps}
        - Screen Time:: 

        # Essen
        - Kalorien:: ${this.calories.toInt()}
        - Protein:: ${String.format("%.1f", this.protein)}
        - Ballaststoffe:: ${String.format("%.1f", this.fiber)}
        - Fett:: ${String.format("%.1f", this.fat)}
        - Zucker:: ${String.format("%.1f", this.sugar)}
        - Wasser:: ${String.format("%.1f", this.water)}
    """.trimIndent()
}

var selectedDate by mutableStateOf(LocalDate.now())

class MainActivity : ComponentActivity() {

    private lateinit var healthDataStore: HealthDataStore
    private var googleDriveService: com.google.api.services.drive.Drive? = null
    private val driveHelper by lazy { googleDriveService?.let { DriveServiceHelper(it) } }

    private val signInLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            initializeDriveService(account)
            // Nach dem Login direkt den Sync starten, falls gewünscht:
            refreshData()
        } catch (e: Exception) {
            Log.e("Drive", "Login fehlgeschlagen: ${e.message}")
        }
    }

    private fun initializeDriveService(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        val credential = com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential.usingOAuth2(
            this, listOf(com.google.api.services.drive.DriveScopes.DRIVE)
        )
        credential.selectedAccount = account.account

        googleDriveService = Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("SyncHealth").build()
    }
    // UI State
    var healthReport by mutableStateOf(HealthReport())
    var isSyncing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            healthDataStore = HealthDataService.getStore(this)
            checkPermissions()
        } catch (e: Exception) {
            Log.e("SyncHealth", "Fehler beim Initialisieren: ${e.message}")
        }

        setContent {
            SyncHealthTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HealthStatusScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun requestGoogleSignIn() {
        // 1. Konfiguration: Was wollen wir von Google? (E-Mail und Zugriff auf Drive-Dateien)
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestEmail()
            // DRIVE_FILE erlaubt Zugriff auf Dateien, die mit dieser App geöffnet/erstellt wurden
            .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE))
            .build()

        // 2. Den Google-Client erstellen
        val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)

        // 3. Den Login-Prozess starten
        // Das triggert den 'signInLauncher', den wir vorhin oben in der Klasse definiert haben
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    // 2. Die "Brücke": Macht aus Samsung-Callbacks moderne Coroutines
    private suspend fun <T> AsyncSingleFuture <T>.await(): T = suspendCancellableCoroutine { continuation ->
        setCallback(
            androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity),
            { result -> continuation.resume(result) },
            { error -> continuation.resumeWithException(error) }
        )
    }

    private fun checkPermissions() {
        val permissions = setOf(
            Permission.of(DataTypes.STEPS, AccessType.READ),
            Permission.of(DataTypes.NUTRITION, AccessType.READ),
            Permission.of(DataTypes.SLEEP, AccessType.READ),
            Permission.of(DataTypes.WATER_INTAKE, AccessType.READ)
        )

        lifecycleScope.launch {
            try {
                val result = healthDataStore.requestPermissionsAsync(permissions, this@MainActivity).await()
                Log.d("SyncHealth", "Rechte erhalten: $result")
                refreshData() // Daten laden nach Erlaubnis
            } catch (e: Exception) {
                Log.e("SyncHealth", "Permission Fehler: ${e.message}")
            }
        }
    }

    private fun showDatePicker() {
        val datePickerDialog = android.app.DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                // Achtung: month ist 0-basiert (Januar = 0)
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                // Nach der Datumswahl direkt Daten für den Tag laden
                refreshData()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        )
        // Man sollte nicht in die Zukunft planen können
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()
        datePickerDialog.show()
    }

    // 3. Die saubere Lese-Funktion ohne Callbacks
    private fun refreshData() {
        lifecycleScope.launch {
            isSyncing = true
            try {
                val startOfDay = selectedDate.atStartOfDay()
                val endOfDay = selectedDate.atTime(23, 59, 59)

                val filter = LocalTimeFilter.of(startOfDay, endOfDay)
                val dateFilter = LocalDateFilter.of(selectedDate, selectedDate.plusDays(1) )

                // Requests vorbereiten
                val stepRequest = DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilter(filter).build()
                val calRequest = DataType.NutritionType.TOTAL_CALORIES.requestBuilder.setLocalTimeFilter(filter).build()
                val waterRequest = DataType.WaterIntakeType.TOTAL.requestBuilder.setLocalTimeFilter(filter).build()
                val sleepRequest = DataType.SleepType.TOTAL_DURATION.requestBuilder.setLocalDateFilter(dateFilter).build()
                val readRequest = DataTypes.NUTRITION.readDataRequestBuilder
                    .setLocalTimeFilter(filter)
                    .build()


                val stepResult = healthDataStore.aggregateDataAsync(stepRequest).await()
                val calResult = healthDataStore.aggregateDataAsync(calRequest).await()
                val waterResult = healthDataStore.aggregateDataAsync(waterRequest).await()
                val sleepResult = healthDataStore.aggregateDataAsync(sleepRequest).await()
                val response = healthDataStore.readDataAsync(readRequest).await()

                val sleepIso = sleepResult.dataList.firstOrNull()?.value as? java.time.Duration ?: Duration.ZERO
                val sleepHours = if (sleepIso != Duration.ZERO) {
                    sleepIso.toSeconds().toDouble() / 3600.0
                } else {
                    0.0
                }

                var totalSugar = 0.0
                var totalProtein = 0.0
                var totalFat = 0.0
                var totalFiber = 0.0

                response.dataList.forEach { record ->
                    totalSugar += (record.getValue(DataType.NutritionType.SUGAR) as? Number)?.toDouble() ?: 0.0
                    totalProtein += (record.getValue(DataType.NutritionType.PROTEIN) as? Number)?.toDouble() ?: 0.0
                    totalFat += (record.getValue(DataType.NutritionType.TOTAL_FAT) as? Number)?.toDouble() ?: 0.0
                    totalFiber += (record.getValue(DataType.NutritionType.DIETARY_FIBER) as? Number)?.toDouble() ?: 0.0
                }

                healthReport = HealthReport(
                    steps = stepResult.dataList.firstOrNull()?.value as? Long ?: 0L,
                    calories = (calResult.dataList.firstOrNull()?.value as? Number)?.toDouble() ?: 0.0,
                    water = (waterResult.dataList.firstOrNull()?.value as? Number)?.toDouble() ?: 0.0,
                    sleep = sleepHours,
                    protein = totalProtein,
                    fat = totalFat,
                    fiber = totalFiber,
                    sugar = totalSugar,
                    )

                if (googleDriveService == null) {
                    // Falls wir noch nicht eingeloggt sind: Login-Fenster öffnen
                    requestGoogleSignIn()
                } else {
                    // Wenn eingeloggt: Daten hochladen
                    lifecycleScope.launch {
                        try {
                            val today = selectedDate.toString() // Format: 2026-03-31
                            DriveServiceHelper(googleDriveService!!).updateDailyNote(today, healthReport)
                            // Optional: Ein Toast zur Bestätigung
                        } catch (e: Exception) {
                            Log.e("Drive", "Fehler beim Upload: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("SyncHealth", "Fehler beim Datenladen: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }


    class DriveServiceHelper(private val driveService: com.google.api.services.drive.Drive) {

        suspend fun updateDailyNote(date: String, report: HealthReport) = withContext(Dispatchers.IO) {
            try {
                // 1. Suche nach der Datei "YYYY-MM-DD.md"
                val folderId = "1UiQDHtrQptXy75h2RQtTPqDihQzW9Tet"

                val query = "name = '$date.md' and '$folderId' in parents and mimeType = 'text/markdown' and trashed = false"
                val files = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive") // Sucht im normalen Drive-Speicher
                    .execute().files

                if (files.isNullOrEmpty()) {
                    Log.e("Drive", "Datei $date.md nicht gefunden!")
                    return@withContext
                }

                val fileId = files[0].id
                val outputStream = ByteArrayOutputStream()
                driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                val content = outputStream.toString("UTF-8")

                // 2. Präzises Ersetzen
                val replacements = mapOf(
                    "Kalorien::" to " ${report.calories.toInt()}",
                    "Schritte::" to " ${report.steps}",
                    // Wir fügen Locale.US hinzu, um den Punkt zu erzwingen:
                    "Zucker::" to " ${String.format(java.util.Locale.US, "%.1f", report.sugar)}",
                    "Protein::" to " ${String.format(java.util.Locale.US, "%.1f", report.protein)}",
                    "Fett::" to " ${String.format(java.util.Locale.US, "%.1f", report.fat)}",
                    "Wasser::" to " ${String.format(java.util.Locale.US, "%.1f", report.water/1000)}",
                    "Ballaststoffe::" to " ${String.format(java.util.Locale.US, "%.1f", report.fiber)}",
                    "Schlaf::" to " ${String.format(java.util.Locale.US, "%.1f", report.sleep)}"
                )

                var updatedContent = content
                replacements.forEach { (key, value) ->
                    val regex = Regex("$key.*")
                    if (updatedContent.contains(key)) {
                        Log.d("Drive", "Ersetze $key mit $value")
                        updatedContent = updatedContent.replace(regex, "$key$value")
                    } else {
                        Log.e("Drive", "SCHLÜSSEL NICHT GEFUNDEN: $key")
                    }
                }

                // 3. Update hochladen
                val mediaContent = com.google.api.client.http.ByteArrayContent.fromString("text/markdown", updatedContent)
                driveService.files().update(fileId, null, mediaContent).execute()

                Log.d("Drive", "Update für $selectedDate erfolgreich hochgeladen!")
            } catch (e: Exception) {
                Log.e("Drive", "Fehler während des Drive-Updates: ${e.message}")
            }
        }
    }

    @Composable
    fun HealthStatusScreen(modifier: Modifier = Modifier) {
        // States für die Snackbar
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        // Die Box erlaubt es, den SnackbarHost über die Column zu legen
        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- DATUM ANZEIGE & ÄNDERN ---
                Text(
                    text = "Datum: $selectedDate",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(onClick = { showDatePicker() }) {
                    Text("Datum ändern")
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- GESUNDHEITSDATEN (Mit Punkt-Formatierung & Liter-Umrechnung) ---
                Text(text = "Schritte: ${healthReport.steps}", fontSize = 24.sp)
                Text(text = "Kalorien: ${String.format(java.util.Locale.US, "%.0f", healthReport.calories)} kcal", fontSize = 24.sp)

                // Wasser in Liter umgerechnet (ml / 1000)
                Text(text = "Wasser: ${String.format(java.util.Locale.US, "%.2f", healthReport.water / 1000.0)} l", fontSize = 24.sp)

                Text(text = "Schlaf: ${String.format(java.util.Locale.US, "%.2f", healthReport.sleep)} h", fontSize = 24.sp)
                Text(text = "Protein: ${String.format(java.util.Locale.US, "%.1f", healthReport.protein)} g", fontSize = 24.sp)
                Text(text = "Zucker: ${String.format(java.util.Locale.US, "%.1f", healthReport.sugar)} g", fontSize = 24.sp)
                Text(text = "Fett: ${String.format(java.util.Locale.US, "%.1f", healthReport.fat)} g", fontSize = 24.sp)
                Text(text = "Ballaststoffe: ${String.format(java.util.Locale.US, "%.1f", healthReport.fiber)} g", fontSize = 24.sp)

                Spacer(modifier = Modifier.height(32.dp))

                // --- SYNC BUTTON ---
                Button(
                    onClick = {
                        scope.launch {
                            refreshData()
                            // Zeigt die Meldung an, sobald refreshData (und der Drive-Upload darin) fertig ist
                            snackbarHostState.showSnackbar(
                                message = "Sync für $selectedDate abgeschlossen!",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    enabled = !isSyncing
                ) {
                    Text(if (isSyncing) "Lade $selectedDate..." else "Sync $selectedDate")
                }
            }

            // Hier wird die Snackbar tatsächlich gezeichnet (ganz unten)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

