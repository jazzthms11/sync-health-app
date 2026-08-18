package com.example.synchealth.vault

import com.example.synchealth.HealthReport
import java.time.LocalDate

/**
 * Der Ablauf: Tagesnotiz finden, patchen, mit `If-Match` zurueckschreiben.
 *
 * Gibt in jedem Fall ein [Outcome] zurueck, statt Fehler nur ins Logcat zu schreiben.
 * Das ist Absicht: Die Drive-Fassung hat "Sync abgeschlossen" gemeldet, waehrend sie in
 * eine tote Kopie schrieb, und genau daran ist der Ausfall monatelang unbemerkt geblieben.
 *
 * **Blockierend**, gehoert auf einen IO-Thread.
 */
class DailyNoteSync(private val client: WebDavClient) {

    sealed interface Outcome {
        /** Geschrieben. [skipped] hatte keine Daten, [missing] keine Zeile in der Notiz. */
        data class Written(
            val patched: List<String>,
            val skipped: List<String>,
            val missing: List<String>,
        ) : Outcome

        /** Die Notiz stand schon richtig da. */
        data class NothingToDo(val skipped: List<String>, val missing: List<String>) : Outcome

        /** Weder flach noch im Monatsordner. Die App legt bewusst nichts an. */
        data class NoteMissing(val date: LocalDate) : Outcome

        data object Unauthorized : Outcome

        /** Zweimal hintereinander dazwischengefunkt. Im Alltag praktisch unmoeglich. */
        data object Conflict : Outcome

        data class Failed(val code: Int, val detail: String) : Outcome
    }

    fun sync(date: LocalDate, report: HealthReport): Outcome {
        // Zwei Anlaeufe: Nach einem 412 ist der Inhalt veraltet, also neu lesen und nochmal.
        repeat(2) {
            val located = locate(date)
            val note = when (located) {
                is Located.Found -> located
                Located.Missing -> return Outcome.NoteMissing(date)
                Located.Unauthorized -> return Outcome.Unauthorized
                is Located.Failed -> return Outcome.Failed(located.code, located.detail)
            }

            val patch = NotePatcher.patch(note.content, report)
            if (patch.unchanged) return Outcome.NothingToDo(patch.skipped, patch.missing)

            when (val result = client.put(note.path, patch.content, note.etag)) {
                WebDavClient.PutResult.Written ->
                    return Outcome.Written(patch.patched, patch.skipped, patch.missing)
                WebDavClient.PutResult.Conflict -> Unit // naechster Anlauf
                WebDavClient.PutResult.Unauthorized -> return Outcome.Unauthorized
                is WebDavClient.PutResult.Failed -> return Outcome.Failed(result.code, result.detail)
            }
        }
        return Outcome.Conflict
    }

    private sealed interface Located {
        data class Found(val path: String, val content: String, val etag: String?) : Located
        data object Missing : Located
        data object Unauthorized : Located
        data class Failed(val code: Int, val detail: String) : Located
    }

    /**
     * Neue Tagesnotizen liegen flach in "05 Daily Notes", fertig nachgetragene im
     * Monatsordner "YYYY-MM". Welche der beiden gilt, entscheidet Leo von Hand, die App
     * muss also beide Orte kennen und darf keinen davon anlegen.
     */
    private fun locate(date: LocalDate): Located {
        val name = "$date.md"
        val flat = "$DAILY_NOTES/$name"
        when (val first = client.get(flat)) {
            is WebDavClient.GetResult.Found -> return Located.Found(flat, first.content, first.etag)
            WebDavClient.GetResult.Unauthorized -> return Located.Unauthorized
            is WebDavClient.GetResult.Failed -> return Located.Failed(first.code, first.detail)
            WebDavClient.GetResult.NotFound -> Unit // weiter im Monatsordner
        }

        val inMonth = "$DAILY_NOTES/${name.substring(0, 7)}/$name"
        return when (val second = client.get(inMonth)) {
            is WebDavClient.GetResult.Found -> Located.Found(inMonth, second.content, second.etag)
            WebDavClient.GetResult.NotFound -> Located.Missing
            WebDavClient.GetResult.Unauthorized -> Located.Unauthorized
            is WebDavClient.GetResult.Failed -> Located.Failed(second.code, second.detail)
        }
    }

    companion object {
        const val DAILY_NOTES = "05 Daily Notes"
    }
}
