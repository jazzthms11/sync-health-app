package com.example.synchealth

import com.example.synchealth.tandoor.TandoorSource

/**
 * Fuehrt die Quellen eines Tages zu einem [HealthReport] zusammen.
 *
 * Fuenf Felder teilen sich Samsung Health und Tandoor: Fruehstueck und Snacks werden in
 * Samsung Health erfasst, Mittag und Abend stehen als Plan in Tandoor. Sie werden hier
 * summiert, damit die Tagesnotiz wie bisher ein Feld je Naehrwert fuehrt und nicht zwei.
 *
 * Reine Rechnung, kein Netz und kein Android.
 */
object DailyTotals {

    /** [notes] traegt den Grund, wenn Tandoor keine Summe hergibt. Sonst ist sie leer. */
    data class Merged(val report: HealthReport, val notes: List<String>)

    /**
     * [meals] ist `null`, solange kein Tandoor-Zugang eingetragen ist. Dann bleibt es bei
     * Samsung Health allein, so wie vor dem 27.09.2026.
     */
    fun merge(samsung: HealthReport, meals: TandoorSource.Outcome?): Merged = when (meals) {
        null -> Merged(samsung, emptyList())

        is TandoorSource.Outcome.Ready -> Merged(
            report = samsung.copy(
                calories = sum(samsung.calories, meals.nutrition.calories),
                protein = sum(samsung.protein, meals.nutrition.protein),
                fat = sum(samsung.fat, meals.nutrition.fat),
                fiber = sum(samsung.fiber, meals.nutrition.fiber),
                sugar = sum(samsung.sugar, meals.nutrition.sugar),
            ),
            notes = emptyList(),
        )

        // Die halbe Summe zu schreiben waere schlimmer als gar nichts: Sie sieht richtig
        // aus, zaehlt in den Wochen-Rollups mit und laesst sich hinterher nicht mehr von
        // einer echten Zahl unterscheiden. Die vier Felder ohne Tandoor-Anteil, also
        // Schritte, Schlaf, Wasser und Bildschirmzeit, gehen unberuehrt durch.
        is TandoorSource.Outcome.Unavailable -> Merged(
            report = samsung.copy(
                calories = null,
                protein = null,
                fat = null,
                fiber = null,
                sugar = null,
            ),
            notes = listOf(meals.reason),
        )
    }

    /**
     * Hat keine der beiden Quellen etwas beigetragen, bleibt das Feld leer. Sobald eine
     * etwas liefert, zaehlt die andere als 0 mit: Wer nichts in Samsung Health eingetragen
     * hat, aber laut Plan gegessen hat, bekommt trotzdem seine Zahl.
     */
    private fun sum(samsung: Double?, meals: Double): Double? =
        if (samsung == null && meals == 0.0) null else (samsung ?: 0.0) + meals
}
