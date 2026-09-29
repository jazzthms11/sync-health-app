package com.example.synchealth.vault

import com.example.synchealth.HealthReport
import java.util.Locale

/**
 * Ersetzt die Messwert-Zeilen einer Tagesnotiz.
 *
 * Arbeitet ausschliesslich auf Text: kein Netz, kein Android, keine Zeit. Das ist die
 * einzige Stelle der App, die sich ohne Geraet pruefen laesst, und gleichzeitig die,
 * die an der Vorlage im Vault haengt. Aendert sich ein Schluessel im Template Daily,
 * faellt es hier auf und nirgends sonst.
 */
object NotePatcher {

    /**
     * Ergebnis eines Durchlaufs.
     *
     * [missing] nennt Schluessel, deren Zeile in der Notiz fehlt, [skipped] solche,
     * fuer die keine Quelle etwas geliefert hat.
     */
    data class Result(
        val content: String,
        val patched: List<String>,
        val skipped: List<String>,
        val missing: List<String>,
    ) {
        /** Nichts zu schreiben: die Werte standen schon so da, oder es gab nichts zu schreiben. */
        val unchanged: Boolean get() = patched.isEmpty()
    }

    /**
     * Die neun Schluessel, die die App befuellt. Schritte, Schlaf und Wasser kommen aus
     * Samsung Health, die Bildschirmzeit aus Androids UsageStatsManager, und die fuenf
     * Naehrwerte aus Samsung Health und Tandoor zusammen, siehe
     * [com.example.synchealth.DailyTotals]. Gewicht, Seiten gelesen und Podcast gehoert
     * stehen bewusst nicht dabei, die liefert keine der Quellen.
     *
     * `null` heisst: fuer den Tag liegt nichts vor, die Zeile bleibt unberuehrt. Eine
     * `0.0` ist dagegen ein Wert und wird geschrieben.
     */
    private fun values(report: HealthReport): Map<String, String?> = linkedMapOf(
        "Schlaf" to decimal(report.sleep),
        "Schritte" to whole(report.steps),
        "Screen Time" to decimal(report.screenTime),
        "Kalorien" to whole(report.calories),
        "Protein" to decimal(report.protein),
        "Ballaststoffe" to decimal(report.fiber),
        "Fett" to decimal(report.fat),
        "Zucker" to decimal(report.sugar),
        // Samsung liefert Milliliter, in der Notiz stehen Liter.
        "Wasser" to decimal(report.water?.div(1000.0)),
    )

    /** Immer Punkt als Dezimaltrenner. Dataview liest ein Komma nicht als Zahl. */
    private fun decimal(value: Double?): String? =
        value?.let { String.format(Locale.US, "%.1f", it) }

    private fun whole(value: Long?): String? = value?.toString()

    private fun whole(value: Double?): String? = value?.toInt()?.toString()

    fun patch(content: String, report: HealthReport): Result {
        var result = content
        val patched = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for ((key, value) in values(report)) {
            if (value == null) {
                skipped += key
                continue
            }
            // Anker auf den Zeilenanfang, sonst trifft der Schluessel auch im Fliesstext
            // unter "# Notizen". Der Listenstrich wird uebernommen, wie er dasteht.
            val line = Regex(
                """^([ \t]*-[ \t]*)?${Regex.escape(key)}::.*$""",
                RegexOption.MULTILINE,
            )
            val match = line.find(result)
            if (match == null) {
                missing += key
                continue
            }
            val bullet = match.groupValues[1]
            val replacement = "$bullet$key:: $value"
            if (match.value == replacement) continue
            // replaceRange statt replace: keine Sonderbehandlung von $ im Ersatztext.
            result = result.replaceRange(match.range, replacement)
            patched += key
        }

        return Result(result, patched, skipped, missing)
    }
}
