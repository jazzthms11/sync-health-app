package com.example.synchealth.tandoor

import com.example.synchealth.essen.PlannedMeal
import java.time.LocalDate

/**
 * Die zweite Quelle: was laut Wochenplan an einem Tag auf dem eigenen Teller steht.
 *
 * Haelt die beiden Abrufe des [TandoorClient] und die Rechnung in [MealPlanMath]
 * zusammen und gibt in jedem Fall ein [Outcome] heraus, statt zu werfen. Das ist
 * dieselbe Regel wie im [com.example.synchealth.vault.DailyNoteSync]: Ein Ablauf, der
 * nur Erfolg melden kann, ist blind.
 *
 * **Blockierend**, gehoert auf einen IO-Thread.
 */
class TandoorSource(
    private val client: TandoorClient,
    /** Die eigene Marke, wie sie in der `note` steht, also mit `@`. */
    private val marker: String,
    /** Wie viele sich einen Eintrag ohne Marke teilen, siehe [MealPlanMath.shared]. */
    private val householdEaters: Int,
) {

    sealed interface Outcome {
        data class Ready(val meals: List<PlannedMeal>) : Outcome

        /**
         * Kein belastbarer Plan. Was dann geschrieben wird, entscheidet
         * [com.example.synchealth.essen.MealMerge]: Steht der Tag schon in der Notiz,
         * gilt der Stand dort, sonst bleiben die fuenf geteilten Felder leer.
         */
        data class Unavailable(val reason: String) : Outcome
    }

    fun read(date: LocalDate): Outcome {
        val entries = when (val result = client.mealPlan(date, date.plusDays(REACH))) {
            is TandoorClient.Result.Found -> result.value
            else -> return Outcome.Unavailable("Wochenplan: ${result.describe()}")
        }

        val needed = neededRecipes(entries, date)
        val recipes = mutableMapOf<Int, Recipe>()
        if (needed.isNotEmpty()) {
            val fdcByType = when (val result = client.propertyTypes()) {
                is TandoorClient.Result.Found -> result.value
                else -> return Outcome.Unavailable("Naehrwerttypen: ${result.describe()}")
            }
            // Erst die geplanten Rezepte, dann Ebene fuer Ebene die verlinkten, siehe
            // [SubRecipes]. Ein Rezept, das auf sich selbst zurueckzeigt, ist dann schon da
            // und wird nicht noch einmal geholt.
            var wave = needed
            repeat(MAX_DEPTH + 1) {
                for (id in wave) {
                    when (val result = client.recipe(id, fdcByType)) {
                        is TandoorClient.Result.Found -> recipes[id] = result.value
                        else -> return Outcome.Unavailable("Rezept $id: ${result.describe()}")
                    }
                }
                wave = wave.flatMap { SubRecipes.linked(recipes.getValue(it)) }.toSet() - recipes.keys
            }
        }

        val flat = needed.associateWith { SubRecipes.flatten(recipes.getValue(it), recipes) }
        return when (val result = MealPlanMath.meals(date, marker, householdEaters, entries, flat)) {
            is MealPlanMath.Result.Ready -> Outcome.Ready(result.meals)
            is MealPlanMath.Result.Incomplete -> Outcome.Unavailable(result.reasons.joinToString("; "))
        }
    }

    /**
     * Nur die Rezepte, an denen [marker] an diesem Tag wirklich einen Anteil hat. Ein
     * Abendessen, das jemand anderes isst, kostet so keine Anfrage. Mehrfach geplante
     * Rezepte werden einmal geholt.
     */
    private fun neededRecipes(entries: List<MealPlanEntry>, date: LocalDate): Set<Int> =
        MealPlanMath.onDate(entries, date)
            .filter { MealPlanMath.share(it, marker, householdEaters) > 0.0 }
            .mapNotNull { it.recipeId }
            .toSet()

    companion object {
        /**
         * Wie weit das Fenster ueber den Tag hinausreicht.
         *
         * Nur nach vorne, denn Tandoor filtert allein auf `to_date`: Ein Eintrag, der
         * [read]s Tag abdeckt, endet nie vor ihm, und was frueher endet, kann nichts
         * beitragen. Rueckwaerts zu fragen holt also ausschliesslich Eintraege, die
         * [MealPlanMath.onDate] gleich wieder wegwirft.
         *
         * Drei Tage tragen den entfalteten Plan: Mittagessen stehen auf einem Tag,
         * Abendessen laufen ueber zwei, in Ausnahmen drei. Ein Eintrag, der weiter
         * reicht, faellt heraus, **ohne dass es der Summe anzusehen waere** -- die
         * Reichweite haengt damit an der Plandisziplin und nicht am Code.
         */
        const val REACH = 3L

        /**
         * Wie tief verlinkte Rezepte nachgeladen werden. Tiefer verschachtelte bleiben als
         * einzelnes Lebensmittel stehen, siehe [SubRecipes.flatten].
         */
        const val MAX_DEPTH = 3
    }
}

/** Ein Satz je Ausgang, fuer die Snackbar und den Knopf *Verbindung testen*. */
fun TandoorClient.Result<*>.describe(): String = when (this) {
    is TandoorClient.Result.Found -> "ok"
    TandoorClient.Result.Unauthorized -> "Token abgelehnt."
    TandoorClient.Result.Forbidden -> "Token darf das nicht lesen, Scope prüfen."
    is TandoorClient.Result.Failed -> when (code) {
        TandoorClient.NO_RESPONSE -> "keine Verbindung ($detail)"
        TandoorClient.MALFORMED -> "Antwort unlesbar ($detail)"
        else -> "Server meldet $code ($detail)"
    }
}
