package com.example.synchealth.essen

import com.example.synchealth.tandoor.MealNutrition
import com.example.synchealth.tandoor.TandoorSource
import java.time.LocalDate

/**
 * Entscheidet, welche Gerichte fuer einen Tag gelten: der Stand in der Notiz, der frische
 * Plan aus Tandoor oder eine Mischung aus beiden.
 *
 * Die Regeln, abgestimmt am 24.10.2026, siehe `docs/konzept-essen.md`:
 *
 * - **Bis [FRESH_DAYS] Tage nach dem ersten Sync** eines Tages zieht jeder Lauf Tandoor
 *   neu. Korrekturen am Rezept kommen so von selbst an.
 * - **Danach ist der Tag fest.** Tandoor wird nicht mehr gefragt, ausser fuer ein
 *   Gericht, das ausdruecklich neu geladen wird ([Edits.reload]).
 * - **Eigene Anpassungen bleiben beim Neuladen erhalten**, fuer jede Zutat, die es noch
 *   gibt. Eine Zutat gilt als angepasst, wenn ihre Menge von der zuletzt geplanten
 *   abweicht. Ist sie unveraendert, kommt die neue Menge aus Tandoor.
 *
 * Reine Rechnung, kein Netz und keine Uhr: [today] kommt von aussen.
 */
object MealMerge {

    /** Wie lange ein Tag nach seinem ersten Sync noch aus Tandoor nachgezogen wird. */
    const val FRESH_DAYS = 7L

    /** Was in der Notiz steht, so wie [com.example.synchealth.vault.EssenCallout] es liest. */
    data class Existing(val firstSync: LocalDate?, val meals: List<PlannedMeal>)

    /** Aenderungen aus der App, die noch nicht in der Notiz stehen. */
    data class Edits(
        /** Menge je Gericht und Zutat: `(Planeintrag, Zutat) zu Menge`. 0 heisst entfernt. */
        val grams: Map<Pair<Int, String>, Double> = emptyMap(),
        /** Planeintraege, die auch an einem festen Tag aus Tandoor neu geladen werden. */
        val reload: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = grams.isEmpty() && reload.isEmpty()
    }

    /**
     * [meals] ist `null`, wenn es fuer den Tag keinen belastbaren Stand gibt: noch nichts
     * in der Notiz und Tandoor stumm. Dann bleiben die fuenf geteilten Felder leer.
     */
    data class Result(
        val firstSync: LocalDate,
        val meals: List<PlannedMeal>?,
        val notes: List<String>,
    ) {
        val nutrition: MealNutrition?
            get() = meals?.fold(MealNutrition.NOTHING) { sum, meal -> sum + meal.nutrition }
    }

    fun fresh(today: LocalDate, firstSync: LocalDate): Boolean =
        !today.isAfter(firstSync.plusDays(FRESH_DAYS))

    /**
     * [existing] ist `null`, solange der Tag noch keinen Essen-Callout hat. [tandoor] ist
     * `null`, solange kein Tandoor-Zugang eingetragen ist.
     */
    fun merge(
        today: LocalDate,
        existing: Existing?,
        tandoor: TandoorSource.Outcome?,
        edits: Edits = Edits(),
    ): Result {
        val firstSync = existing?.firstSync ?: today
        val old = existing?.meals.orEmpty()
        val notes = mutableListOf<String>()
        val plan = (tandoor as? TandoorSource.Outcome.Ready)?.meals
        val failure = (tandoor as? TandoorSource.Outcome.Unavailable)?.reason

        val meals: List<PlannedMeal>? = if (fresh(today, firstSync)) {
            when {
                plan != null -> {
                    val keep = plan.map { it.entryId }.toSet()
                    old.filter { it.entryId !in keep }.forEach {
                        notes += "${it.recipeName} steht nicht mehr im Plan und ist entfernt"
                    }
                    plan.map { fresh -> refresh(fresh, old.firstOrNull { it.entryId == fresh.entryId }) }
                }
                // Ohne Tandoor-Zugang gibt es keinen Plan, das ist kein Fehler.
                tandoor == null -> old
                existing != null -> {
                    notes += "Tandoor: $failure. Es gilt der Stand aus der Notiz."
                    old
                }
                else -> {
                    notes += "Tandoor: $failure"
                    null
                }
            }
        } else {
            old.map { meal ->
                if (meal.entryId !in edits.reload) return@map meal
                val fresh = plan?.firstOrNull { it.entryId == meal.entryId }
                when {
                    fresh != null -> refresh(fresh, meal)
                    plan != null -> {
                        notes += "${meal.recipeName} steht nicht mehr im Plan, nichts neu geladen"
                        meal
                    }
                    else -> {
                        notes += "Tandoor: ${failure ?: "kein Zugang"}. ${meal.recipeName} nicht neu geladen."
                        meal
                    }
                }
            }
        }

        val edited = meals?.map { applyEdits(it, edits) }
        edited?.forEach { meal ->
            meal.ingredients.forEach { ingredient ->
                ingredient.warning?.let { notes += "${meal.recipeName}: ${ingredient.name} $it" }
            }
        }
        return Result(firstSync, edited, notes)
    }

    /**
     * Der frische Stand aus Tandoor, mit den eigenen Anpassungen aus [old]. Zutaten, die
     * es im Rezept nicht mehr gibt, fallen weg, neue kommen mit ihrer geplanten Menge.
     */
    private fun refresh(fresh: PlannedMeal, old: PlannedMeal?): PlannedMeal {
        if (old == null) return fresh
        return fresh.copy(
            ingredients = fresh.ingredients.map { ingredient ->
                val before = old.ingredients.firstOrNull { it.name == ingredient.name }
                if (before != null && before.adjusted && ingredient.planned != null) {
                    ingredient.copy(amount = before.amount)
                } else {
                    ingredient
                }
            },
        )
    }

    /** Nur Zutaten mit Mengenangabe lassen sich anpassen, die anderen zaehlen ohnehin nicht. */
    private fun applyEdits(meal: PlannedMeal, edits: Edits): PlannedMeal {
        if (edits.grams.isEmpty()) return meal
        return meal.copy(
            ingredients = meal.ingredients.map { ingredient ->
                val amount = edits.grams[meal.entryId to ingredient.name]
                if (amount == null || ingredient.planned == null) ingredient
                else ingredient.copy(amount = roundAmount(amount.coerceAtLeast(0.0), ingredient.unit))
            },
        )
    }
}
