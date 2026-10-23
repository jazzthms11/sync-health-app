package com.example.synchealth

import com.example.synchealth.essen.MealMerge
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.tandoor.TandoorSource
import com.example.synchealth.vault.EssenCallout
import com.example.synchealth.vault.NotePatcher
import java.time.LocalDate

/**
 * Ein Tag, fertig fuer die Notiz: Essen-Callout zuruecklesen, mit Tandoor abgleichen,
 * neu schreiben, und die Messwerte darunter patchen.
 *
 * Dieselbe Rechnung dient zum Anzeigen und zum Schreiben. Die App zeigt also genau das,
 * was sie beim Sync schreiben wuerde, und nach einem `412` laesst sie sich auf den
 * frischen Notizinhalt einfach noch einmal anwenden.
 *
 * Reine Rechnung, kein Netz und kein Android.
 */
object DayNote {

    data class Result(
        /** Der neue Notizinhalt, oder `null`, wenn es keine Notiz gab. */
        val content: String?,
        val patch: NotePatcher.Result?,
        val report: HealthReport,
        val meals: MealMerge.Result,
        val notes: List<String>,
    )

    /** [content] ist `null`, wenn es die Tagesnotiz (noch) nicht gibt oder kein Vault eingetragen ist. */
    fun build(
        content: String?,
        today: LocalDate,
        samsung: HealthReport,
        samsungMeals: List<SamsungMeal>,
        tandoor: TandoorSource.Outcome?,
        edits: MealMerge.Edits = MealMerge.Edits(),
    ): Result {
        val parsed = content?.let { EssenCallout.parse(it) }

        // Eine Zeile, die nicht zu lesen ist, wird nicht ueberschrieben: Sie ist von Hand
        // geaendert und muss von Hand korrigiert werden. Bis dahin bleiben der Callout und
        // die fuenf geteilten Felder, wie sie sind, eine Summe ohne diese Zeile waere zu
        // niedrig.
        if (content != null && parsed != null && parsed.issues.isNotEmpty()) {
            val notes = parsed.issues + "Essen nicht geschrieben, bitte in der Notiz korrigieren"
            val meals = MealMerge.Result(parsed.existing.firstSync ?: today, null, notes)
            val report = DailyTotals.merge(samsung, null)
            val patch = NotePatcher.patch(content, report)
            return Result(patch.content, patch, report, meals, notes)
        }

        val meals = MealMerge.merge(today, parsed?.existing, tandoor, edits)
        val report = DailyTotals.merge(samsung, meals.nutrition)
        if (content == null) return Result(null, null, report, meals, meals.notes)

        var next = content
        val placed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (meals.meals != null) {
            val callout = EssenCallout.render(
                firstSync = meals.firstSync,
                meals = meals.meals,
                samsung = samsungMeals,
                totalCalories = report.calories,
                reserved = EssenCallout.reservedKeys(content),
            )
            val withCallout = EssenCallout.place(content, callout)
            when {
                withCallout == null -> missing += "Essen"
                withCallout != content -> {
                    next = withCallout
                    placed += "Essen"
                }
            }
        }

        val fields = NotePatcher.patch(next, report)
        val patch = fields.copy(
            patched = placed + fields.patched,
            missing = missing + fields.missing,
        )
        return Result(patch.content, patch, report, meals, meals.notes)
    }
}
