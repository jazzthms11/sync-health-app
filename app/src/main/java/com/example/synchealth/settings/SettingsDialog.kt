package com.example.synchealth.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.synchealth.tandoor.TandoorClient
import com.example.synchealth.tandoor.TandoorSource
import com.example.synchealth.tandoor.describe
import com.example.synchealth.vault.DailyNoteSync
import com.example.synchealth.vault.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Die Eingaben des Dialogs, gebuendelt statt als ein Dutzend einzelner `remember`.
 *
 * Haelt ausserdem die Pruefung: Was hier gilt, wird gespeichert, und die Teil-Composables
 * bekommen ein Objekt statt einer Parameterliste, die mit jedem Feld laenger wird.
 */
private class SettingsInput(vault: VaultAccess?, tandoor: TandoorAccess?) {
    var url by mutableStateOf(vault?.baseUrl ?: "https://")
    var user by mutableStateOf(vault?.username.orEmpty())
    var password by mutableStateOf(vault?.password.orEmpty())
    var folder by mutableStateOf(vault?.dailyNotes ?: DailyNoteSync.DEFAULT_DAILY_NOTES)

    var tandoorUrl by mutableStateOf(tandoor?.baseUrl.orEmpty())
    var token by mutableStateOf(tandoor?.token.orEmpty())
    var marker by mutableStateOf(tandoor?.marker.orEmpty())
    var eaters by mutableStateOf((tandoor?.eaters ?: DEFAULT_EATERS).toString())

    var fehler by mutableStateOf<String?>(null)
    var testStatus by mutableStateOf<String?>(null)

    /** Nach jeder Eingabe sind Fehler und Testergebnis nicht mehr die zur Eingabe passenden. */
    fun changed() {
        fehler = null
        testStatus = null
    }

    /** Leere Tandoor-Felder heissen: Der Zugang wird nicht eingetragen oder wieder geloescht. */
    val tandoorEmpty: Boolean
        get() = tandoorUrl.isBlank() && token.isBlank() && marker.isBlank()

    /** Null heisst: keine gueltige Zahl eingetippt. */
    private val eaterCount: Int?
        get() = eaters.trim().toIntOrNull()?.takeIf { it >= 1 }

    val tandoorAccess: TandoorAccess?
        get() = if (tandoorEmpty) null
        else TandoorAccess(
            tandoorUrl.trim(),
            token.trim(),
            marker.trim(),
            eaterCount ?: DEFAULT_EATERS,
        )

    val vaultAccess: VaultAccess
        get() = VaultAccess(url.trim(), user.trim(), password, folder.trim())

    /** Gibt einen Fehlertext zurueck oder null, wenn gespeichert werden darf. */
    fun validate(): String? = CredentialStore.validateBaseUrl(url)
        ?: when {
            user.isBlank() -> "Der Benutzername fehlt."
            password.isBlank() -> "Das App-Passwort fehlt."
            // Der Ordner wird bewusst nicht geprueft, leer ist ein gueltiger Wert.
            tandoorEmpty -> null
            // Alles oder nichts: Ein halber Tandoor-Zugang waere still. Ohne Marke etwa
            // rechnet sich jeder Anteil auf null, und die Naehrwerte fehlten wortlos.
            tandoorUrl.isBlank() -> "Die Tandoor-Adresse fehlt."
            token.isBlank() -> "Der Tandoor-Token fehlt."
            marker.isBlank() -> "Die eigene Marke fehlt, zum Beispiel @leo."
            // Ein leeres oder unsinniges Feld darf nicht still auf 2 zurueckfallen: Der
            // Teiler geht in jedes gemeinsame Abendessen ein, und die Zahl saehe richtig aus.
            eaterCount == null -> "Die Zahl der Esser muss mindestens 1 sein."
            else -> CredentialStore.validateBaseUrl(tandoorUrl)
        }
}

/**
 * Vault-Zugang und Tandoor-Zugang in einem Dialog. [onSave] bekommt den Tandoor-Zugang als
 * `null`, wenn seine Felder leer sind; dann bleibt es bei Samsung Health allein.
 */
@Composable
fun SettingsDialog(
    vault: VaultAccess?,
    tandoor: TandoorAccess?,
    onDismiss: () -> Unit,
    onSave: (VaultAccess, TandoorAccess?) -> Unit,
) {
    val input = remember(vault, tandoor) { SettingsInput(vault, tandoor) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Der Dialog ist hoeher als sein Platz, die Felder scrollen also. Das Ergebnis des
    // Tests steht unter dem Knopf und damit ausserhalb des Sichtbaren, wenn man ihn
    // gerade gedrueckt hat. Ein Test, dessen Antwort man nicht sieht, ist keiner.
    val content = rememberScrollState()

    // Nach der Komposition und nicht im Klick: Im Klick gaebe es die Zeile noch nicht,
    // und `maxValue` waere der Stand von davor. `Int.MAX_VALUE` wird je Bild auf das
    // gerade gueltige Ende begrenzt, das traegt auch durch den Umbruch einer langen Zeile.
    LaunchedEffect(input.testStatus) {
        if (input.testStatus != null) content.animateScrollTo(Int.MAX_VALUE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zugänge") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(content),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VaultFields(input)
                HorizontalDivider()
                TandoorFields(input)
                input.fehler?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        val problem = input.validate()
                        if (problem != null) {
                            input.fehler = problem
                            return@OutlinedButton
                        }
                        testing = true
                        input.testStatus = null
                        scope.launch {
                            input.testStatus = withContext(Dispatchers.IO) { probe(input) }
                            testing = false
                        }
                    },
                    enabled = !testing,
                ) { Text(if (testing) "Prüfe..." else "Verbindung testen") }
                input.testStatus?.let { Text(text = it, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = input.validate()
                if (problem != null) input.fehler = problem
                else onSave(input.vaultAccess, input.tandoorAccess)
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun VaultFields(input: SettingsInput) {
    Text("Vault", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(
        value = input.url,
        onValueChange = { input.url = it; input.changed() },
        label = { Text("Basis-URL") },
        placeholder = { Text("https://cloud.example.de/remote.php/dav/files/du/vault/") },
        singleLine = true,
    )
    OutlinedTextField(
        value = input.user,
        onValueChange = { input.user = it; input.changed() },
        label = { Text("Benutzername") },
        singleLine = true,
    )
    OutlinedTextField(
        value = input.password,
        onValueChange = { input.password = it; input.changed() },
        label = { Text("App-Passwort") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    OutlinedTextField(
        value = input.folder,
        onValueChange = { input.folder = it; input.changed() },
        label = { Text("Ordner der Tagesnotizen") },
        placeholder = { Text(DailyNoteSync.DEFAULT_DAILY_NOTES) },
        supportingText = { Text("Leer lassen, wenn sie direkt im Vault-Root liegen.") },
        singleLine = true,
    )
}

@Composable
private fun TandoorFields(input: SettingsInput) {
    Text("Tandoor", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(
        value = input.tandoorUrl,
        onValueChange = { input.tandoorUrl = it; input.changed() },
        label = { Text("Adresse") },
        placeholder = { Text("https://tandoor.example.de") },
        supportingText = { Text("Alle drei Felder leer lassen heißt: nur Samsung Health.") },
        singleLine = true,
    )
    OutlinedTextField(
        value = input.token,
        onValueChange = { input.token = it; input.changed() },
        label = { Text("Zugriffstoken") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    OutlinedTextField(
        value = input.marker,
        onValueChange = { input.marker = it; input.changed() },
        label = { Text("Eigene Marke") },
        placeholder = { Text("@leo") },
        supportingText = { Text("So, wie sie in der Notiz des Planeintrags steht.") },
        singleLine = true,
    )
    OutlinedTextField(
        value = input.eaters,
        onValueChange = { input.eaters = it.filter(Char::isDigit); input.changed() },
        label = { Text("Esser im Haushalt") },
        placeholder = { Text(DEFAULT_EATERS.toString()) },
        supportingText = { Text("Teilt die Abendessen, die ohne Marke im Plan stehen.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/**
 * Prueft beide Zugaenge auf einmal, ohne zu syncen.
 *
 * Fuer Tandoor ist das ein Abruf des Wochenplans und nicht bloss ein Anmeldeversuch: Er
 * prueft Adresse, Token **und** Scope in einem Zug, denn ein Token mit dem falschen Scope
 * gilt und wird trotzdem mit 403 abgewiesen.
 *
 * **Blockierend**, gehoert auf einen IO-Thread.
 */
private fun probe(input: SettingsInput): String {
    val vault = WebDavClient(input.url.trim(), input.user.trim(), input.password)
        .probe(input.folder.trim().trim('/'))
    val vaultText = "Vault: " + when (vault) {
        WebDavClient.ProbeResult.Ok -> "ok."
        WebDavClient.ProbeResult.NotFound -> "Ordner gibt es nicht."
        WebDavClient.ProbeResult.Unauthorized -> "Anmeldung abgelehnt."
        is WebDavClient.ProbeResult.Failed ->
            if (vault.code == WebDavClient.NO_RESPONSE) "keine Verbindung (${vault.detail})."
            else "Server meldet ${vault.code} (${vault.detail})."
    }

    val access = input.tandoorAccess ?: return "$vaultText\nTandoor: nicht eingetragen."
    val client = TandoorClient(access.baseUrl, access.token)
    val heute = LocalDate.now()
    // Dasselbe Fenster wie im echten Lauf: Tandoor filtert auf `to_date`, eine Abfrage
    // auf den heutigen Tag allein faende die laufenden Eintraege gar nicht.
    val plan = client.mealPlan(heute, heute.plusDays(TandoorSource.REACH))
    val tandoorText = "Tandoor: " + when {
        plan !is TandoorClient.Result.Found -> plan.describe()
        // Ohne die Naehrwerttypen laesst sich kein Rezept deuten, und der Ausfall saehe
        // sonst aus wie ein Tag ohne Plan.
        else -> when (val typen = client.propertyTypes()) {
            is TandoorClient.Result.Found ->
                "ok, ${plan.value.size} Einträge im Fenster, ${typen.value.size} Nährwerttypen."
            else -> "Nährwerttypen: ${typen.describe()}"
        }
    }
    return "$vaultText\n$tandoorText"
}
