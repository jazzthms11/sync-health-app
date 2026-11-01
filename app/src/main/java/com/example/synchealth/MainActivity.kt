package com.example.synchealth

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.MealMerge
import com.example.synchealth.essen.MealStore
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.essen.amountText
import com.example.synchealth.health.HealthRepository
import com.example.synchealth.health.ScreenTimeReader
import com.example.synchealth.settings.CredentialStore
import com.example.synchealth.settings.SettingsDialog
import com.example.synchealth.tandoor.TandoorClient
import com.example.synchealth.tandoor.TandoorSource
import com.example.synchealth.ui.theme.SyncHealthTheme
import com.example.synchealth.vault.DailyNoteSync
import com.example.synchealth.vault.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale

private const val TAG = "SyncHealth"

class MainActivity : ComponentActivity() {

    private var health: HealthRepository? = null
    private lateinit var credentials: CredentialStore
    private lateinit var screenTime: ScreenTimeReader
    private lateinit var mealStore: MealStore

    private var selectedDate by mutableStateOf(LocalDate.now())
    private var healthReport by mutableStateOf(HealthReport())

    /** Was an den Werten auffiel, etwa ein Planeintrag ohne Marke oder ein stummes Tandoor. */
    private var hinweise by mutableStateOf<List<String>>(emptyList())

    /**
     * Alles, was fuer einen Tag gelesen wird. Gehalten, damit eine Aenderung in der App
     * sofort neu gerechnet wird, ohne jedes Mal Samsung und Tandoor zu fragen.
     */
    private data class Inputs(
        val date: LocalDate,
        val samsung: HealthReport,
        val samsungMeals: List<SamsungMeal>,
        val tandoor: TandoorSource.Outcome?,
        /** Was beim letzten Sync fuer den Tag galt, siehe [MealStore]. */
        val stored: MealMerge.Existing?,
    )

    private var inputs by mutableStateOf<Inputs?>(null)
    private var meals by mutableStateOf<MealMerge.Result?>(null)

    /** Aenderungen aus der App, die noch nicht gespeichert sind. Gespeichert wird beim Sync. */
    private var edits by mutableStateOf(MealMerge.Edits())

    /** Welche Mahlzeiten aufgeklappt sind. Beim Oeffnen ist alles zu. */
    private var offen by mutableStateOf<Set<Slot>>(emptySet())

    /** Was gerade in einem Mengenfeld getippt wird, auch wenn es noch keine Zahl ist ("12,"). */
    private var gramTexts by mutableStateOf<Map<Pair<Int, String>, String>>(emptyMap())

    /** Nur der Sync, der in die Notiz schreibt. Solange er laeuft, bleibt der Tag fest. */
    private var isSyncing by mutableStateOf(false)

    /** Ein Tag wird gelesen. Das Datum bleibt dabei frei, siehe [changeDate]. */
    private var isLoading by mutableStateOf(false)

    /** Das Laden des angezeigten Tags, wartend oder laufend. Ein neuer Tag bricht es ab. */
    private var loadJob: Job? = null
    private var showSettings by mutableStateOf(false)
    private var hasScreenTimeAccess by mutableStateOf(false)

    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        credentials = CredentialStore(this)
        screenTime = ScreenTimeReader(this)
        mealStore = MealStore(File(filesDir, "essen"))
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
     *
     * Mit [wait] beginnt das Lesen erst, wenn der Tag so lange angezeigt bleibt. Jeder
     * Aufruf bricht das vorige Laden ab, und ein Ergebnis fuer einen anderen Tag als den
     * angezeigten wird verworfen.
     */
    private fun loadHealthData(wait: Long = 0L) {
        val repo = health ?: return
        loadJob?.cancel()
        isLoading = false
        val date = selectedDate
        loadJob = lifecycleScope.launch {
            delay(wait)
            isLoading = true
            try {
                val read = readInputs(repo, date)
                if (date == selectedDate) {
                    inputs = read
                    recompute()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lesen fehlgeschlagen: ${e.message}")
                snackbar.showSnackbar("Samsung Health lieferte nichts: ${e.message}")
            } finally {
                if (loadJob == coroutineContext[Job]) isLoading = false
            }
        }
    }

    /**
     * Rechnet den Tag aus [current] und den offenen [edits], ohne Netz. Dieselbe Rechnung
     * dient zum Anzeigen und zum Sync: Die App zeigt, was sie schreiben wuerde.
     */
    private fun compute(current: Inputs): Pair<MealMerge.Result, HealthReport> {
        val result = MealMerge.merge(LocalDate.now(), current.stored, current.tandoor, edits)
        return result to DailyTotals.merge(current.samsung, result.nutrition)
    }

    private fun recompute() {
        val current = inputs ?: return
        val (result, report) = compute(current)
        meals = result
        healthReport = report
        hinweise = result.notes
    }

    /**
     * Liest den Tag aus allen Quellen.
     *
     * Tandoor wird auch beim blossen Anschauen abgefragt, nicht erst beim Sync: Die App
     * soll anzeigen, was sie schreiben wuerde. Stuende hier eine andere Zahl als spaeter
     * in der Notiz, waere genau der blinde Fleck zurueck, der die Drive-Fassung monatelang
     * Erfolg melden liess.
     */
    private suspend fun readInputs(repo: HealthRepository, date: LocalDate): Inputs {
        // Ohne erteilten Zugriff und an einem Tag ohne Ereignisse kommen 0.0 Stunden
        // zurueck, und das heisst hier wie bei Samsung Health "noch keine Daten".
        val hours = withContext(Dispatchers.Default) { screenTime.readHours(date) }
        val samsung = repo.read(date).copy(screenTime = hours.takeIf { it > 0.0 })
        val samsungMeals = repo.readMeals(date)
        val tandoor = withContext(Dispatchers.IO) {
            credentials.loadTandoor()?.let {
                TandoorSource(TandoorClient(it.baseUrl, it.token), it.marker, it.eaters).read(date)
            }
        }
        val stored = withContext(Dispatchers.IO) { mealStore.load(date) }
        return Inputs(date, samsung, samsungMeals, tandoor, stored)
    }

    private fun clearEdits() {
        edits = MealMerge.Edits()
        gramTexts = emptyMap()
    }

    private fun setGrams(changes: Map<Pair<Int, String>, Double>) {
        edits = edits.copy(grams = edits.grams + changes)
        gramTexts = gramTexts - changes.keys
        recompute()
    }

    /** ½×, 1×, 2×: bezogen auf die geplante Menge, 1× setzt also auf den Plan zurueck. */
    private fun scale(meal: PlannedMeal, factor: Double) {
        setGrams(
            meal.ingredients.filter { it.planned != null }
                .associate { (meal.entryId to it.name) to it.planned!! * factor },
        )
    }

    private fun reload(meal: PlannedMeal) {
        edits = edits.copy(reload = edits.reload + meal.entryId)
        recompute()
    }

    private fun startSync() {
        // Der Sync liest selbst frisch, ein wartendes oder laufendes Laden waere doppelt.
        loadJob?.cancel()
        isLoading = false
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

                // Frisch lesen statt die gehaltenen Werte zu nehmen: Seit dem Oeffnen kann in
                // Samsung Health etwas dazugekommen sein.
                val date = selectedDate
                val fresh = readInputs(repo, date)
                inputs = fresh
                val (result, report) = compute(fresh)
                val outcome = withContext(Dispatchers.IO) {
                    val client = WebDavClient(access.baseUrl, access.username, access.password)
                    DailyNoteSync(client, access.dailyNotes).sync(date, report)
                }
                Log.d(TAG, "Sync $date: $outcome, Hinweise ${result.notes}")

                // Erst wenn die Summe in der Notiz steht, gilt der Stand auch auf dem Handy.
                // Sonst stuenden dort Anpassungen, die die Notiz nie gesehen hat.
                if (outcome is DailyNoteSync.Outcome.Written || outcome is DailyNoteSync.Outcome.NothingToDo) {
                    val saved = result.meals?.let { MealMerge.Existing(result.firstSync, it) }
                    if (saved != null) withContext(Dispatchers.IO) { mealStore.save(date, saved) }
                    clearEdits()
                    inputs = fresh.copy(stored = saved ?: fresh.stored)
                }
                recompute()
                snackbar.showSnackbar(describe(outcome, result.notes))
            } catch (e: CancellationException) {
                throw e
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
                changeDate(LocalDate.of(year, month + 1, day))
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        )
        // In die Zukunft gibt es nichts zu holen.
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    /**
     * Die Werte des vorigen Tags verschwinden sofort, damit unter dem neuen Datum nie die
     * Zahlen eines anderen Tags stehen. Offene Aenderungen gelten nur fuer ihren Tag.
     */
    private fun changeDate(date: LocalDate, wait: Long = 0L) {
        if (date.isAfter(LocalDate.now()) || date == selectedDate) return
        selectedDate = date
        clearEdits()
        inputs = null
        meals = null
        healthReport = HealthReport()
        hinweise = emptyList()
        loadHealthData(wait)
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
                if (health != null && inputs == null && !isSyncing) {
                    Text(if (isLoading) "Lädt …" else "Gleich …", fontSize = 13.sp)
                }
                Button(onClick = { showDatePicker() }, enabled = !isSyncing) { Text("Datum ändern") }

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
                    Text(
                        when {
                            isSyncing -> "Läuft für $selectedDate..."
                            !edits.isEmpty -> "Speichern und Sync $selectedDate"
                            else -> "Sync $selectedDate"
                        },
                    )
                }
                if (!edits.isEmpty && !isSyncing) {
                    Text("Ungespeicherte Änderungen", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }

                Mahlzeiten()

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

    /**
     * Je Mahlzeit eine aufklappbare Zeile mit Gerichten und Zutaten. Gerichte aus Tandoor
     * lassen sich anpassen, Samsung-Eintraege nur ansehen.
     */
    @Composable
    private fun Mahlzeiten() {
        if (inputs == null) return
        val planned = meals?.meals.orEmpty()
        val samsung = inputs?.samsungMeals.orEmpty()

        Spacer(modifier = Modifier.height(24.dp))
        Text("Mahlzeiten", fontSize = 22.sp, modifier = Modifier.fillMaxWidth())
        for (slot in Slot.entries) {
            val gerichte = planned.filter { it.slot == slot }
            val eintraege = samsung.filter { it.slot == slot }
            val leer = gerichte.isEmpty() && eintraege.isEmpty()
            val summe = gerichte.sumOf { it.nutrition.calories } + eintraege.sumOf { it.calories ?: 0.0 }
            val auf = slot in offen

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !leer) { offen = if (auf) offen - slot else offen + slot }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (leer) "  " else if (auf) "▾ " else "▸ ") + slot.label,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(if (leer) "—" else kcal(summe), fontSize = 18.sp)
            }
            if (auf) {
                gerichte.forEach { Gericht(it) }
                eintraege.forEach { Eintrag(it) }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
    }

    /** Ein Eintrag aus Samsung Health, nur zum Ansehen: Geaendert wird er dort. */
    @Composable
    private fun Eintrag(entry: SamsungMeal) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(kcal(entry.calories), fontSize = 16.sp)
        }
    }

    @Composable
    private fun Gericht(meal: PlannedMeal) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
            Text(meal.recipeName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(kcal(meal.nutrition.calories), fontSize = 16.sp)
        }
        meal.ingredients.forEach { Zutat(meal, it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { scale(meal, 0.5) }, enabled = !isSyncing) { Text("½×") }
            TextButton(onClick = { scale(meal, 1.0) }, enabled = !isSyncing) { Text("1×") }
            TextButton(onClick = { scale(meal, 2.0) }, enabled = !isSyncing) { Text("2×") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload(meal) }, enabled = !isSyncing) { Text("Neu laden") }
        }
    }

    @Composable
    private fun Zutat(meal: PlannedMeal, ingredient: Ingredient) {
        val key = meal.entryId to ingredient.name
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ingredient.name, fontSize = 15.sp)
                val warning = ingredient.warning
                if (warning != null) {
                    Text("⚠ $warning", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(kcal(ingredient.nutrition?.calories), fontSize = 13.sp)
                }
            }
            if (ingredient.amount != null) {
                OutlinedTextField(
                    value = gramTexts[key] ?: amountText(ingredient.amount, ingredient.unit),
                    onValueChange = { text ->
                        gramTexts = gramTexts + (key to text)
                        text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { amount ->
                            edits = edits.copy(grams = edits.grams + (key to amount))
                            recompute()
                        }
                    },
                    enabled = !isSyncing,
                    singleLine = true,
                    suffix = { Text(ingredient.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
                TextButton(onClick = { setGrams(mapOf(key to 0.0)) }, enabled = !isSyncing) { Text("✕") }
            }
        }
    }

    private fun kcal(value: Double?): String =
        if (value == null) "— kcal" else "${zahl(value, 0)} kcal"

    private fun zahl(value: Double?, stellen: Int): String? =
        value?.let { String.format(Locale.US, "%.${stellen}f", it) }
}
