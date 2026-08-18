package com.example.synchealth

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.synchealth.health.HealthRepository
import com.example.synchealth.settings.CredentialStore
import com.example.synchealth.settings.VaultAccess
import com.example.synchealth.ui.theme.SyncHealthTheme
import com.example.synchealth.vault.DailyNoteSync
import com.example.synchealth.vault.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

private const val TAG = "SyncHealth"

class MainActivity : ComponentActivity() {

    private var health: HealthRepository? = null
    private lateinit var credentials: CredentialStore

    private var selectedDate by mutableStateOf(LocalDate.now())
    private var healthReport by mutableStateOf(HealthReport())
    private var isSyncing by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        credentials = CredentialStore(this)

        try {
            health = HealthRepository(this)
        } catch (e: Exception) {
            Log.e(TAG, "Samsung Health nicht verfügbar: ${e.message}")
        }

        lifecycleScope.launch {
            val repo = health ?: return@launch
            try {
                repo.requestPermissions(this@MainActivity)
                loadHealthData()
            } catch (e: Exception) {
                Log.e(TAG, "Rechte abgelehnt: ${e.message}")
            }
        }

        setContent {
            SyncHealthTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    HealthStatusScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }

    /**
     * Nur lesen, nicht schreiben. Das Oeffnen der App und das Umstellen des Datums fassen
     * die Tagesnotiz bewusst nicht an, geschrieben wird auf Knopfdruck.
     */
    private fun loadHealthData() {
        val repo = health ?: return
        lifecycleScope.launch {
            isSyncing = true
            try {
                healthReport = repo.read(selectedDate)
            } catch (e: Exception) {
                Log.e(TAG, "Lesen fehlgeschlagen: ${e.message}")
                snackbar.showSnackbar("Samsung Health lieferte nichts: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    private fun startSync() {
        lifecycleScope.launch {
            isSyncing = true
            try {
                val repo = health
                if (repo == null) {
                    snackbar.showSnackbar("Samsung Health ist auf diesem Gerät nicht verfügbar.")
                    return@launch
                }
                val access = credentials.load()
                if (access == null) {
                    showSettings = true
                    snackbar.showSnackbar("Trag zuerst den Vault-Zugang ein.")
                    return@launch
                }

                healthReport = repo.read(selectedDate)
                val outcome = withContext(Dispatchers.IO) {
                    val client = WebDavClient(access.baseUrl, access.username, access.password)
                    DailyNoteSync(client).sync(selectedDate, healthReport)
                }
                Log.d(TAG, "Sync $selectedDate: $outcome")
                snackbar.showSnackbar(describe(outcome))
            } catch (e: Exception) {
                Log.e(TAG, "Sync fehlgeschlagen", e)
                snackbar.showSnackbar("Sync fehlgeschlagen: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    /**
     * Jeder Ausgang bekommt einen eigenen Satz. Die alte Fassung meldete in jedem Fall
     * "Sync abgeschlossen", auch wenn sie in eine tote Kopie schrieb, und genau deshalb ist
     * der Ausfall im August wochenlang nicht aufgefallen.
     */
    private fun describe(outcome: DailyNoteSync.Outcome): String = when (outcome) {
        is DailyNoteSync.Outcome.Written -> buildString {
            append("${outcome.patched.size} Werte in $selectedDate geschrieben")
            if (outcome.skipped.isNotEmpty()) append(", ${outcome.skipped.size} ohne Daten")
            if (outcome.missing.isNotEmpty()) {
                append(". Zeile fehlt: ${outcome.missing.joinToString()}")
            }
        }
        is DailyNoteSync.Outcome.NothingToDo -> "$selectedDate stand schon richtig da."
        is DailyNoteSync.Outcome.NoteMissing ->
            "Tagesnotiz $selectedDate gibt es nicht."
        DailyNoteSync.Outcome.Unauthorized -> "Anmeldung abgelehnt. App-Passwort prüfen."
        DailyNoteSync.Outcome.Conflict ->
            "$selectedDate wurde zweimal parallel geändert. Nochmal versuchen."
        is DailyNoteSync.Outcome.Failed ->
            if (outcome.code == WebDavClient.NO_RESPONSE) "Keine Verbindung: ${outcome.detail}"
            else "Server meldet ${outcome.code}: ${outcome.detail}"
    }

    private fun showDatePicker() {
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                // month ist zero-based, Januar ist 0.
                selectedDate = LocalDate.of(year, month + 1, day)
                loadHealthData()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        )
        // In die Zukunft gibt es nichts zu holen.
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    @Composable
    private fun HealthStatusScreen(modifier: Modifier = Modifier) {
        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Datum: $selectedDate",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = { showDatePicker() }) { Text("Datum ändern") }

                Spacer(modifier = Modifier.height(24.dp))

                Messwert("Schritte", healthReport.steps.toString())
                Messwert("Kalorien", "${zahl(healthReport.calories, 0)} kcal")
                Messwert("Wasser", "${zahl(healthReport.water / 1000.0, 2)} l")
                Messwert("Schlaf", "${zahl(healthReport.sleep, 2)} h")
                Messwert("Protein", "${zahl(healthReport.protein, 1)} g")
                Messwert("Zucker", "${zahl(healthReport.sugar, 1)} g")
                Messwert("Fett", "${zahl(healthReport.fat, 1)} g")
                Messwert("Ballaststoffe", "${zahl(healthReport.fiber, 1)} g")

                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = { startSync() }, enabled = !isSyncing) {
                    Text(if (isSyncing) "Läuft für $selectedDate..." else "Sync $selectedDate")
                }

                TextButton(onClick = { showSettings = true }, enabled = !isSyncing) {
                    Text("Vault-Zugang")
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
        }

        if (showSettings) {
            SettingsDialog(
                initial = credentials.load(),
                onDismiss = { showSettings = false },
                onSave = { access ->
                    credentials.save(access)
                    showSettings = false
                },
            )
        }
    }

    @Composable
    private fun Messwert(name: String, wert: String) {
        Text(text = "$name: $wert", fontSize = 22.sp)
    }

    private fun zahl(value: Double, stellen: Int): String =
        String.format(Locale.US, "%.${stellen}f", value)
}

@Composable
private fun SettingsDialog(
    initial: VaultAccess?,
    onDismiss: () -> Unit,
    onSave: (VaultAccess) -> Unit,
) {
    var url by remember { mutableStateOf(initial?.baseUrl ?: "https://") }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var fehler by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vault-Zugang") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; fehler = null },
                    label = { Text("Basis-URL") },
                    placeholder = { Text("https://cloud.example.de/remote.php/dav/files/du/vault/") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it; fehler = null },
                    label = { Text("Benutzername") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; fehler = null },
                    label = { Text("App-Passwort") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                fehler?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = CredentialStore.validateBaseUrl(url)
                    ?: when {
                        user.isBlank() -> "Der Benutzername fehlt."
                        password.isBlank() -> "Das App-Passwort fehlt."
                        else -> null
                    }
                if (problem != null) fehler = problem
                else onSave(VaultAccess(url.trim(), user.trim(), password))
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
