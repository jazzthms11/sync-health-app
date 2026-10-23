package com.example.synchealth

import com.example.synchealth.tandoor.MealNutrition

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

    /**
     * [planned] ist die Summe der Gerichte aus dem Essen-Callout, siehe
     * [com.example.synchealth.essen.MealMerge]. `null` heisst: Es gibt fuer den Tag keinen
     * belastbaren Stand.
     */
    fun merge(samsung: HealthReport, planned: MealNutrition?): HealthReport = when (planned) {
        // Die halbe Summe zu schreiben waere schlimmer als gar nichts: Sie sieht richtig
        // aus, zaehlt in den Monatsschnitt mit und laesst sich hinterher nicht mehr von
        // einer echten Zahl unterscheiden. Die vier Felder ohne Tandoor-Anteil, also
        // Schritte, Schlaf, Wasser und Bildschirmzeit, gehen unberuehrt durch.
        null -> samsung.copy(calories = null, protein = null, fat = null, fiber = null, sugar = null)
        else -> samsung.copy(
            calories = sum(samsung.calories, planned.calories),
            protein = sum(samsung.protein, planned.protein),
            fat = sum(samsung.fat, planned.fat),
            fiber = sum(samsung.fiber, planned.fiber),
            sugar = sum(samsung.sugar, planned.sugar),
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
