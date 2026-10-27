package com.example.synchealth

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.MealMerge
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.health.HealthRepository
import com.example.synchealth.health.ScreenTimeReader
import com.example.synchealth.settings.CredentialStore
import com.example.synchealth.settings.SettingsDialog
import com.example.synchealth.tandoor.TandoorClient
import com.example.synchealth.tandoor.TandoorSource
import com.example.synchealth.ui.KartenKopf
import com.example.synchealth.ui.MengenFeld
import com.example.synchealth.ui.MesswertZeile
import com.example.synchealth.ui.Pille
import com.example.synchealth.ui.Radius
import com.example.synchealth.ui.VaultKarte
import com.example.synchealth.ui.theme.LocalVault
import com.example.synchealth.ui.theme.Lucide
import com.example.synchealth.ui.theme.SyncHealthTheme
import com.example.synchealth.ui.theme.Zahl
import com.example.synchealth.vault.DailyNoteSync
import com.example.synchealth.vault.EssenCallout
import com.example.synchealth.vault.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "SyncHealth"

/** Wie lange ein Tag angezeigt bleiben muss, bevor er geladen wird, siehe changeDate. */
private const val LOAD_DELAY_MS = 700L

class MainActivity : ComponentActivity() {

    private var health: HealthRepository? = null
    private lateinit var credentials: CredentialStore
    private lateinit var screenTime: ScreenTimeReader

    private var selectedDate by mutableStateOf(LocalDate.now())
    private var healthReport by mutableStateOf(HealthReport())

    /** Was an den Werten auffiel, etwa ein Planeintrag ohne Marke oder ein stummes Tandoor. */
    private var hinweise by mutableStateOf<List<String>>(emptyList())

    /**
     * Alles, was fuer einen Tag aus dem Netz kommt. Gehalten, damit eine Aenderung in der
     * App sofort neu gerechnet wird, ohne jedes Mal Samsung, Tandoor und die Notiz zu fragen.
     */
    private data class Inputs(
        val date: LocalDate,
        val samsung: HealthReport,
        val samsungMeals: List<SamsungMeal>,
        val tandoor: TandoorSource.Outcome?,
        /** `null`, wenn es die Notiz nicht gibt oder kein Vault eingetragen ist. */
        val note: String?,
        /** Warum [note] fehlt, sonst leer. */
        val noteHints: List<String>,
    )

    private var inputs by mutableStateOf<Inputs?>(null)
    private var day by mutableStateOf<DayNote.Result?>(null)

    /** Aenderungen aus der App, die noch nicht in der Notiz stehen. */
    private var edits by mutableStateOf(MealMerge.Edits())

    /** Welche Mahlzeiten aufgeklappt sind. Wie in der Notiz ist beim Oeffnen alles zu. */
    private var offen by mutableStateOf<Set<Slot>>(emptySet())

    /** Was gerade in einem Grammfeld getippt wird, auch wenn es noch keine Zahl ist ("12,"). */
    private var gramTexts by mutableStateOf<Map<Pair<Int, String>, String>>(emptyMap())
    /** Nur der Sync, der in die Notiz schreibt. Solange er laeuft, bleibt der Tag fest. */
    private var isSyncing by mutableStateOf(false)

    /** Ein Tag wird gelesen. Die Pfeile bleiben dabei frei, siehe [changeDate]. */
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
     * Mit [wait] beginnt das Lesen erst, wenn der Tag so lange angezeigt bleibt. Wer
     * schnell durch die Tage tippt, loest so nur fuer den Tag eine Abfrage aus, auf dem er
     * stehen bleibt. Jeder Aufruf bricht das vorige Laden ab, und ein Ergebnis fuer einen
     * anderen Tag als den angezeigten wird verworfen.
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

    /** Rechnet den Tag aus den gehaltenen [inputs] und den offenen [edits] neu, ohne Netz. */
    private fun recompute() {
        val current = inputs ?: return
        val result = DayNote.build(
            content = current.note,
            today = LocalDate.now(),
            samsung = current.samsung,
            samsungMeals = current.samsungMeals,
            tandoor = current.tandoor,
            edits = edits,
        )
        day = result
        healthReport = result.report
        hinweise = current.noteHints + result.notes
    }

    /**
     * Liest den Tag aus allen Quellen.
     *
     * Tandoor wird auch beim blossen Anschauen abgefragt, nicht erst beim Sync: Die App
     * soll anzeigen, was sie schreiben wuerde. Stuende hier eine andere Zahl als spaeter
     * in der Notiz, waere genau der blinde Fleck zurueck, der die Drive-Fassung monatelang
     * Erfolg melden liess. Aus demselben Grund wird auch die Notiz gelesen: Was dort im
     * Essen-Callout steht, gilt.
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
        val access = credentials.load()
        val (note, hints) = if (access == null) {
            null to listOf("Kein Vault-Zugang eingetragen")
        } else {
            val read = withContext(Dispatchers.IO) {
                val client = WebDavClient(access.baseUrl, access.username, access.password)
                DailyNoteSync(client, access.dailyNotes).read(date)
            }
            when (read) {
                is DailyNoteSync.ReadOutcome.Found -> read.content to emptyList()
                DailyNoteSync.ReadOutcome.Missing -> null to listOf("Tagesnotiz $date gibt es nicht")
                DailyNoteSync.ReadOutcome.Unauthorized -> null to listOf("Vault: Anmeldung abgelehnt")
                is DailyNoteSync.ReadOutcome.Failed -> null to listOf("Vault: ${read.code} ${read.detail}")
            }
        }
        return Inputs(date, samsung, samsungMeals, tandoor, note, hints)
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
                val fresh = readInputs(repo, selectedDate)
                inputs = fresh
                val pending = edits
                var last: DayNote.Result? = null
                val outcome = withContext(Dispatchers.IO) {
                    val client = WebDavClient(access.baseUrl, access.username, access.password)
                    DailyNoteSync(client, access.dailyNotes).sync(selectedDate) { content ->
                        DayNote.build(
                            content = content,
                            today = LocalDate.now(),
                            samsung = fresh.samsung,
                            samsungMeals = fresh.samsungMeals,
                            tandoor = fresh.tandoor,
                            edits = pending,
                        ).also { last = it }.patch!!
                    }
                }
                val notes = last?.notes.orEmpty()
                Log.d(TAG, "Sync $selectedDate: $outcome, Hinweise $notes")
                if (outcome is DailyNoteSync.Outcome.Written || outcome is DailyNoteSync.Outcome.NothingToDo) {
                    clearEdits()
                    inputs = readInputs(repo, selectedDate)
                }
                recompute()
                snackbar.showSnackbar(describe(outcome, notes))
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
     * Einen Tag vor oder zurueck. In die Zukunft gibt es nichts zu holen.
     *
     * Die Werte des vorigen Tags verschwinden sofort, damit unter dem neuen Datum nie die
     * Zahlen eines anderen Tags stehen. Geladen wird erst nach [LOAD_DELAY_MS], oder sofort
     * mit dem Sync-Knopf.
     */
    private fun changeDate(date: LocalDate) {
        if (date.isAfter(LocalDate.now()) || date == selectedDate) return
        selectedDate = date
        clearEdits()
        inputs = null
        day = null
        healthReport = HealthReport()
        hinweise = emptyList()
        loadHealthData(wait = LOAD_DELAY_MS)
    }

    @Composable
    private fun HealthStatusScreen(modifier: Modifier = Modifier) {
        val v = LocalVault.current
        Box(modifier = modifier.fillMaxSize().background(v.bg)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Kopf()
                Karten()
                Hinweise()
                SyncKnopf()
                Mahlzeiten()

                TextButton(
                    onClick = { showSettings = true },
                    enabled = !isSyncing,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Lucide.Settings, contentDescription = null, tint = v.leise, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zugänge", style = MaterialTheme.typography.bodyMedium, color = v.leise)
                }
                Spacer(Modifier.height(24.dp))
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

    /** Wochentag und Datum wie ein Notiztitel, mit Pfeilen fuer den Tag davor und danach. */
    @Composable
    private fun Kopf() {
        val v = LocalVault.current
        val heute = LocalDate.now()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { changeDate(selectedDate.minusDays(1)) }, enabled = !isSyncing) {
                Icon(Lucide.ChevronLeft, contentDescription = "Tag davor", tint = v.leise)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radius)
                    .clickable(enabled = !isSyncing) { showDatePicker() }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val wochentag = when (selectedDate) {
                    heute -> "Heute"
                    heute.minusDays(1) -> "Gestern"
                    else -> selectedDate.format(DateTimeFormatter.ofPattern("EEEE", Locale.GERMANY))
                }
                Text(wochentag.uppercase(), style = MaterialTheme.typography.labelMedium, color = v.akzent)
                Text(
                    selectedDate.format(DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMANY)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = v.titel,
                )
                // Auch waehrend der Wartezeit: Der Tag ist gewaehlt, seine Werte kommen noch.
                if (health != null && inputs == null && !isSyncing) {
                    Text(
                        if (isLoading) "Lädt …" else "Gleich …",
                        style = MaterialTheme.typography.bodySmall,
                        color = v.leise,
                    )
                }
            }
            IconButton(
                onClick = { changeDate(selectedDate.plusDays(1)) },
                enabled = !isSyncing && selectedDate.isBefore(heute),
            ) {
                Icon(
                    Lucide.ChevronRight,
                    contentDescription = "Tag danach",
                    tint = if (selectedDate.isBefore(heute)) v.leise else v.linie,
                )
            }
        }
    }

    /** Die beiden Karten der Tagesuebersicht, mit denselben Feldern und Icons wie im Vault. */
    @Composable
    private fun Karten() {
        val r = healthReport
        VaultKarte {
            KartenKopf("Gesundheit", Lucide.HeartPulse)
            MesswertZeile(Lucide.Footprints, "Schritte", r.steps?.let { deutsch(it.toDouble(), 0) }, "", false)
            MesswertZeile(Lucide.Moon, "Schlaf", deutsch(r.sleep, 1), "Std.", true)
            if (hasScreenTimeAccess) {
                MesswertZeile(Lucide.Smartphone, "Screen Time", deutsch(r.screenTime, 1), "Std.", true)
            } else {
                HorizontalDivider(color = LocalVault.current.linie)
                TextButton(onClick = { startActivity(screenTime.requestAccessIntent()) }) {
                    Text("Bildschirmzeit-Zugriff erlauben", color = LocalVault.current.akzent)
                }
            }
        }
        VaultKarte {
            KartenKopf("Ernährung", Lucide.Utensils)
            MesswertZeile(Lucide.Flame, "Kalorien", deutsch(r.calories, 0), "kcal", false)
            MesswertZeile(Lucide.Beef, "Protein", deutsch(r.protein, 1), "g", true)
            MesswertZeile(Lucide.Droplet, "Fett", deutsch(r.fat, 1), "g", true)
            MesswertZeile(Lucide.Wheat, "Ballaststoffe", deutsch(r.fiber, 1), "g", true)
            MesswertZeile(Lucide.Candy, "Zucker", deutsch(r.sugar, 1), "g", true)
            MesswertZeile(Lucide.GlassWater, "Wasser", deutsch(r.water?.div(1000.0), 2), "L", true)
        }
    }

    @Composable
    private fun Hinweise() {
        if (hinweise.isEmpty()) return
        val v = LocalVault.current
        VaultKarte(flaeche = true) {
            hinweise.forEach {
                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Lucide.TriangleAlert,
                        contentDescription = null,
                        tint = v.warnung,
                        modifier = Modifier.size(15.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = v.text)
                }
            }
        }
    }

    @Composable
    private fun SyncKnopf() {
        val v = LocalVault.current
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { startSync() },
                enabled = !isSyncing,
                shape = Radius,
                colors = ButtonDefaults.buttonColors(containerColor = v.akzent, contentColor = v.bg),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Lucide.RefreshCw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        isSyncing -> "Läuft …"
                        !edits.isEmpty -> "Speichern und Sync"
                        else -> "Sync"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (!edits.isEmpty && !isSyncing) {
                Text(
                    "Ungespeicherte Änderungen",
                    style = MaterialTheme.typography.bodySmall,
                    color = v.akzent,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    /**
     * Je Mahlzeit eine aufklappbare Karte, wie die Callouts in der Notiz. Alle vier stehen
     * immer da, auch leer: So sieht man, was noch fehlt.
     */
    @Composable
    private fun Mahlzeiten() {
        val v = LocalVault.current
        val meals = day?.meals?.meals.orEmpty()
        val samsung = inputs?.samsungMeals.orEmpty()

        Text(
            "Mahlzeiten",
            style = MaterialTheme.typography.titleLarge,
            color = v.titel,
            modifier = Modifier.padding(top = 8.dp),
        )
        for (slot in Slot.entries) {
            val planned = meals.filter { it.slot == slot }
            val fromSamsung = samsung.filter { it.slot == slot }
            val auf = slot in offen
            val summe = planned.sumOf { it.nutrition.calories } + fromSamsung.sumOf { it.calories ?: 0.0 }
            val leer = planned.isEmpty() && fromSamsung.isEmpty()

            VaultKarte(flaeche = true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radius)
                        .clickable { offen = if (auf) offen - slot else offen + slot }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(slotIcon(slot), contentDescription = null, tint = v.akzent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(slot.label, style = MaterialTheme.typography.titleMedium, color = v.text, modifier = Modifier.weight(1f))
                    Text(
                        if (leer) "—" else "${deutsch(summe, 0)} kcal",
                        style = Zahl.copy(fontSize = 15.sp),
                        color = if (leer) v.leise else v.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Lucide.ChevronDown,
                        contentDescription = if (auf) "Zuklappen" else "Aufklappen",
                        tint = v.leise,
                        modifier = Modifier.size(18.dp).rotate(if (auf) 180f else 0f),
                    )
                }
                AnimatedVisibility(visible = auf) {
                    Column {
                        planned.forEach { Gericht(it) }
                        fromSamsung.forEach { Eintrag(it) }
                        if (leer) {
                            HorizontalDivider(color = v.linie, modifier = Modifier.padding(vertical = 6.dp))
                            Text("Nichts eingetragen", style = MaterialTheme.typography.bodyMedium, color = v.leise)
                        }
                    }
                }
            }
        }
    }

    private fun slotIcon(slot: Slot) = when (slot) {
        Slot.FRUEHSTUECK -> Lucide.Sunrise
        Slot.MITTAG -> Lucide.Sun
        Slot.ABEND -> Lucide.MoonStar
        Slot.SNACKS -> Lucide.Cookie
    }

    /** Ein Eintrag aus Samsung Health, nur zum Ansehen: Geaendert wird er dort. */
    @Composable
    private fun Eintrag(entry: SamsungMeal) {
        val v = LocalVault.current
        HorizontalDivider(color = v.linie, modifier = Modifier.padding(vertical = 6.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = v.text, modifier = Modifier.weight(1f))
            Text(kcal(entry.calories), style = Zahl.copy(fontSize = 15.sp), color = v.text)
        }
    }

    @Composable
    private fun Gericht(meal: PlannedMeal) {
        val v = LocalVault.current
        HorizontalDivider(color = v.linie, modifier = Modifier.padding(vertical = 6.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(meal.recipeName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = v.text, modifier = Modifier.weight(1f))
            Text(kcal(meal.nutrition.calories), style = Zahl.copy(fontSize = 15.sp), color = v.text)
        }
        meal.ingredients.forEach { Zutat(meal, it) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pille("½×", enabled = !isSyncing) { scale(meal, 0.5) }
            Pille("1×", enabled = !isSyncing) { scale(meal, 1.0) }
            Pille("2×", enabled = !isSyncing) { scale(meal, 2.0) }
            Spacer(Modifier.weight(1f))
            Pille("Neu laden", enabled = !isSyncing, icon = Lucide.RotateCcw) { reload(meal) }
        }
    }

    @Composable
    private fun Zutat(meal: PlannedMeal, ingredient: Ingredient) {
        val v = LocalVault.current
        val key = meal.entryId to ingredient.name
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ingredient.name, style = MaterialTheme.typography.bodyMedium, color = v.text)
                val warning = ingredient.warning
                if (warning != null) {
                    Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, color = v.warnung)
                } else {
                    Text(kcal(ingredient.nutrition?.calories), style = Zahl.copy(fontSize = 12.sp), color = v.leise)
                }
            }
            if (ingredient.amount != null) {
                MengenFeld(
                    value = gramTexts[key] ?: EssenCallout.amount(ingredient.amount, ingredient.unit),
                    einheit = ingredient.unit,
                    enabled = !isSyncing,
                    onValueChange = { text ->
                        gramTexts = gramTexts + (key to text)
                        text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { amount ->
                            edits = edits.copy(grams = edits.grams + (key to amount))
                            recompute()
                        }
                    },
                )
                IconButton(onClick = { setGrams(mapOf(key to 0.0)) }, enabled = !isSyncing, modifier = Modifier.size(36.dp)) {
                    Icon(Lucide.X, contentDescription = "Entfernen", tint = v.leise, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    private fun kcal(value: Double?): String =
        if (value == null) "— kcal" else "${deutsch(value, 0)} kcal"

    /** Wie die Tagesuebersicht: deutsches Zahlenformat, Punkt als Tausendertrenner. */
    private fun deutsch(value: Double?, stellen: Int): String? = value?.let {
        val format = java.text.NumberFormat.getNumberInstance(Locale.GERMANY)
        format.minimumFractionDigits = stellen
        format.maximumFractionDigits = stellen
        format.format(it)
    }
}
