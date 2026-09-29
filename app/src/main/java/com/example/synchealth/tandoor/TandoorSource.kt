package com.example.synchealth.tandoor

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
        data class Ready(val nutrition: MealNutrition) : Outcome

        /**
         * Keine belastbare Summe. Die fuenf geteilten Felder bleiben dann ungeschrieben,
         * denn Samsung Health allein waere die halbe Mahlzeit, und eine zu niedrige Zahl
         * faellt in der Tagesnotiz nie wieder auf.
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
            for (id in needed) {
                when (val result = client.recipe(id, fdcByType)) {
                    is TandoorClient.Result.Found -> recipes[id] = result.value
                    else -> return Outcome.Unavailable("Rezept $id: ${result.describe()}")
                }
            }
        }

        return when (val sum = MealPlanMath.sum(date, marker, householdEaters, entries, recipes)) {
            is MealPlanMath.Result.Ready -> Outcome.Ready(sum.nutrition)
            is MealPlanMath.Result.Incomplete -> Outcome.Unavailable(sum.reasons.joinToString("; "))
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
