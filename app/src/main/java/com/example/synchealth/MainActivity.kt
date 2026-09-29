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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.synchealth.health.HealthRepository
import com.example.synchealth.health.ScreenTimeReader
import com.example.synchealth.settings.CredentialStore
import com.example.synchealth.settings.SettingsDialog
import com.example.synchealth.tandoor.TandoorClient
import com.example.synchealth.tandoor.TandoorSource
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
    private lateinit var screenTime: ScreenTimeReader

    private var selectedDate by mutableStateOf(LocalDate.now())
    private var healthReport by mutableStateOf(HealthReport())

    /** Was an den Werten auffiel, etwa ein Planeintrag ohne Marke oder ein stummes Tandoor. */
    private var hinweise by mutableStateOf<List<String>>(emptyList())
    private var isSyncing by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var hasScreenTimeAccess by mutableStateOf(false)

    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        credentials = CredentialStore(this)
        screenTime = ScreenTimeReader(this)
        hasScreenTimeAccess = screenTime.hasAccess()

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

    override fun onResume() {
        super.onResume()
        // Der Zugriff wird nur ueber die Systemeinstellungen erteilt, die App bekommt also
        // keinen Callback dafuer. Ein Wechsel auf erlaubt heisst: gerade von dort zurueck.
        val hatteZugriff = hasScreenTimeAccess
        hasScreenTimeAccess = screenTime.hasAccess()
        if (hasScreenTimeAccess && !hatteZugriff) loadHealthData()
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
                show(readDay(repo))
            } catch (e: Exception) {
                Log.e(TAG, "Lesen fehlgeschlagen: ${e.message}")
                snackbar.showSnackbar("Samsung Health lieferte nichts: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    private fun show(day: DailyTotals.Merged) {
        healthReport = day.report
        hinweise = day.notes
    }

    /**
     * Liest den Tag aus allen Quellen und fuehrt sie zusammen.
     *
     * Tandoor wird auch beim blossen Anschauen abgefragt, nicht erst beim Sync: Die App
     * soll anzeigen, was sie schreiben wuerde. Stuende hier eine andere Zahl als spaeter
     * in der Notiz, waere genau der blinde Fleck zurueck, der die Drive-Fassung monatelang
     * Erfolg melden liess.
     */
    private suspend fun readDay(repo: HealthRepository): DailyTotals.Merged {
        // Ohne erteilten Zugriff und an einem Tag ohne Ereignisse kommen 0.0 Stunden
        // zurueck, und das heisst hier wie bei Samsung Health "noch keine Daten".
        val hours = withContext(Dispatchers.Default) { screenTime.readHours(selectedDate) }
        val samsung = repo.read(selectedDate).copy(screenTime = hours.takeIf { it > 0.0 })
        val meals = withContext(Dispatchers.IO) {
            credentials.loadTandoor()?.let {
                TandoorSource(TandoorClient(it.baseUrl, it.token), it.marker, it.eaters)
                    .read(selectedDate)
            }
        }
        return DailyTotals.merge(samsung, meals)
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

                val day = readDay(repo)
                show(day)
                val outcome = withContext(Dispatchers.IO) {
                    val client = WebDavClient(access.baseUrl, access.username, access.password)
                    DailyNoteSync(client, access.dailyNotes).sync(selectedDate, day.report)
                }
                Log.d(TAG, "Sync $selectedDate: $outcome, Hinweise ${day.notes}")
                snackbar.showSnackbar(describe(outcome, day.notes))
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
     *
     * [notes] haengt hinten an, statt eine eigene Meldung zu bekommen: Ein ausgefallenes
     * Tandoor ist kein gescheiterter Sync, die uebrigen Werte stehen danach richtig da.
     */
    private fun describe(outcome: DailyNoteSync.Outcome, notes: List<String>): String {
        val satz = when (outcome) {
            is DailyNoteSync.Outcome.Written -> buildString {
                append("${outcome.patched.size} Werte in $selectedDate geschrieben")
                if (outcome.skipped.isNotEmpty()) append(", ${outcome.skipped.size} ohne Daten")
                if (outcome.missing.isNotEmpty()) {
                    append(". Zeile fehlt: ${outcome.missing.joinToString()}")
                }
            }
            is DailyNoteSync.Outcome.NothingToDo -> "$selectedDate stand schon richtig da."
            is DailyNoteSync.Outcome.NoteMissing -> "Tagesnotiz $selectedDate gibt es nicht."
            DailyNoteSync.Outcome.Unauthorized -> "Anmeldung abgelehnt. App-Passwort prüfen."
            DailyNoteSync.Outcome.Conflict ->
                "$selectedDate wurde zweimal parallel geändert. Nochmal versuchen."
            is DailyNoteSync.Outcome.Failed ->
                if (outcome.code == WebDavClient.NO_RESPONSE) "Keine Verbindung: ${outcome.detail}"
                else "Server meldet ${outcome.code}: ${outcome.detail}"
        }
        return if (notes.isEmpty()) satz else "$satz — ${notes.joinToString("; ")}"
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

                Messwert("Schritte", healthReport.steps?.toString())
                Messwert("Kalorien", zahl(healthReport.calories, 0), "kcal")
                Messwert("Wasser", zahl(healthReport.water?.div(1000.0), 2), "l")
                Messwert("Schlaf", zahl(healthReport.sleep, 2), "h")
                Messwert("Protein", zahl(healthReport.protein, 1), "g")
                Messwert("Zucker", zahl(healthReport.sugar, 1), "g")
                Messwert("Fett", zahl(healthReport.fat, 1), "g")
                Messwert("Ballaststoffe", zahl(healthReport.fiber, 1), "g")
                if (hasScreenTimeAccess) {
                    Messwert("Bildschirmzeit", zahl(healthReport.screenTime, 1), "h")
                } else {
                    TextButton(onClick = { startActivity(screenTime.requestAccessIntent()) }) {
                        Text("Bildschirmzeit-Zugriff erlauben")
                    }
                }

                if (hinweise.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    hinweise.forEach {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = { startSync() }, enabled = !isSyncing) {
                    Text(if (isSyncing) "Läuft für $selectedDate..." else "Sync $selectedDate")
                }

                TextButton(onClick = { showSettings = true }, enabled = !isSyncing) {
                    Text("Zugänge")
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
                vault = credentials.load(),
                tandoor = credentials.loadTandoor(),
                onDismiss = { showSettings = false },
                onSave = { access, plan ->
                    credentials.save(access)
                    credentials.saveTandoor(plan)
                    showSettings = false
                    loadHealthData()
                },
            )
        }
    }

    @Composable
    private fun Messwert(name: String, wert: String?, einheit: String = "") {
        // Ein Strich statt einer Null: Fuer den Tag liegt nichts vor, und eine 0 waere
        // dafuer die falsche Anzeige, seit eine echte 0 geschrieben werden kann.
        val text = if (wert == null) "—" else listOf(wert, einheit).filter { it.isNotEmpty() }
            .joinToString(" ")
        Text(text = "$name: $text", fontSize = 22.sp)
    }

    private fun zahl(value: Double?, stellen: Int): String? =
        value?.let { String.format(Locale.US, "%.${stellen}f", it) }
}
