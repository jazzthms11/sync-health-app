package com.example.synchealth

/**
 * Die Messwerte eines Tages, fertig zusammengefuehrt aus allen Quellen.
 *
 * Wasser steht in Millilitern, Schlaf und Bildschirmzeit in Stunden, alles andere in der
 * Einheit, die die Quelle liefert.
 *
 * **`null` heisst: fuer den Tag liegt nichts vor**, die Zeile in der Tagesnotiz bleibt
 * unberuehrt. Eine `0.0` ist dagegen ein Wert und wird geschrieben.
 *
 * Diese Unterscheidung kam mit Tandoor. Solange Samsung Health die einzige Quelle war,
 * durfte eine 0 als "noch keine Daten" gelesen werden, und der Patcher hat sie
 * verschluckt. Seit sich zwei Quellen die fuenf Naehrwerte teilen, ist eine 0 aus Samsung
 * nur noch ein Summand: Wer nichts eingetragen hat, aber laut Plan gegessen hat, bekommt
 * trotzdem seine Zahl. Die Regel "0 heisst noch keine Daten" steht deshalb jetzt dort, wo
 * Samsung antwortet, und nicht mehr im [com.example.synchealth.vault.NotePatcher].
 */
data class HealthReport(
    val steps: Long? = null,
    val calories: Double? = null,
    val water: Double? = null,
    val sleep: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val fiber: Double? = null,
    val sugar: Double? = null,
    /** Stunden, aus Androids UsageStatsManager statt aus Samsung Health. */
    val screenTime: Double? = null,
)
