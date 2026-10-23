package com.example.synchealth.tandoor

import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.essen.roundAmount
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Die ganze Rechnung vom Wochenplan zu den Gerichten und Zutaten eines Tages.
 *
 * Arbeitet nur auf den Datenklassen aus [TandoorJson]: kein Netz, kein Android, keine
 * Uhr. Dieselbe Trennung wie zwischen [com.example.synchealth.vault.NotePatcher] und
 * [com.example.synchealth.vault.WebDavClient], und aus demselben Grund: Die Zuordnung
 * ueber Marken und Portionen ist das, was schiefgehen kann, und genau die laesst sich so
 * ohne Server pruefen.
 */
object MealPlanMath {

    sealed interface Result {
        data class Ready(val meals: List<PlannedMeal>) : Result

        /**
         * Ein Eintrag laesst sich nicht zuordnen. Das Ergebnis waere nachweislich zu
         * niedrig und wird nicht herausgegeben, denn eine falsche Zahl faellt in der
         * Tagesnotiz nie wieder auf.
         *
         * Eine einzelne Zutat ohne Naehrwerte gehoert nicht hierher: Sie wird an der
         * Zutat selbst markiert, siehe [Ingredient.warning].
         */
        data class Incomplete(val reasons: List<String>) : Result
    }

    /** Die Marken einer Notiz. Nur die erste Zeile zaehlt, darunter stehen Ersetzungen und Hinweise. */
    fun markers(note: String): List<String> =
        MARKER.findAll(note.lineSequence().firstOrNull().orEmpty())
            .map { it.value.lowercase() }
            .distinct()
            .toList()

    /**
     * Ob ein Eintrag ohne Marke dem ganzen Haushalt gehoert.
     *
     * Abendessen werden zusammen gegessen und tragen deshalb keine Marke; Mittagessen
     * stehen je Esser entfaltet im Plan und tragen immer eine. Ein Mittagessen ohne
     * Marke ist also ein unvollstaendiger Planeintrag und keine geteilte Mahlzeit.
     *
     * Verglichen wird der Name aus Tandoor. Wird die Mahlzeitenart dort umbenannt, faellt
     * der Eintrag in den lauten Fall: [meals] gibt dann kein Ergebnis heraus, statt still mit
     * dem falschen Teiler zu rechnen.
     */
    fun shared(entry: MealPlanEntry): Boolean = entry.mealType.equals(SHARED, ignoreCase = true)

    /**
     * Die Eintraege, die [date] abdecken.
     *
     * Der Filter sitzt hier und nicht in der Abfrage: Tandoors `from_date`/`to_date`
     * greifen nicht als Ueberschneidung, sondern liefern allein, was im Fenster *endet*.
     * Ein Eintrag vom 14. bis 18. fehlt deshalb bei jeder Abfrage ohne den 18., auch bei
     * der auf den 14. Geholt wird ein Fenster nach vorne, geschnitten wird hier.
     */
    fun onDate(entries: List<MealPlanEntry>, date: LocalDate): List<MealPlanEntry> =
        entries.filter { !date.isBefore(it.from) && !date.isAfter(it.to) }

    /**
     * Der eigene Anteil an einem Eintrag, in Portionen fuer diesen einen Tag.
     *
     * `servings / Tage / Esser`: Die Portionen gelten fuer den ganzen Eintrag, ein
     * Abendessen ueber zwei Abende zaehlt also halb, und ein geteiltes noch einmal halb.
     *
     * Wer die Esser sind, sagt die Notiz. Traegt sie Marken, gelten sie und nur sie: Ohne
     * die eigene ist der Anteil null, dann isst jemand anderes. Traegt sie keine, ist es
     * ein geteilter Gang und [householdEaters] teilen sich den Eintrag. Die Marke ist
     * damit das staerkere Signal, ein Abendessen, das ausnahmsweise nur einer isst,
     * bekommt eine und rechnet sich danach.
     */
    fun share(entry: MealPlanEntry, marker: String, householdEaters: Int): Double {
        val markers = markers(entry.note)
        val eaters = when {
            markers.isNotEmpty() -> if (markers.contains(marker.lowercase())) markers.size else return 0.0
            shared(entry) -> householdEaters
            else -> return 0.0
        }
        val days = ChronoUnit.DAYS.between(entry.from, entry.to) + 1
        return entry.servings / days / eaters
    }

    /**
     * Die Gerichte, die [marker] an [date] laut Plan isst, mit den Zutaten in Gramm auf
     * den eigenen Anteil umgerechnet.
     *
     * [recipes] muss jedes Rezept enthalten, auf das ein Eintrag zeigt; wer die Rezepte
     * holt, entscheidet [TandoorSource].
     */
    fun meals(
        date: LocalDate,
        marker: String,
        householdEaters: Int,
        entries: List<MealPlanEntry>,
        recipes: Map<Int, Recipe>,
    ): Result {
        val meals = mutableListOf<PlannedMeal>()
        val reasons = mutableListOf<String>()

        for (entry in onDate(entries, date)) {
            // Ohne Rezept wird an diesem Platz nicht aus der eigenen Kueche gegessen,
            // etwa "Essen bei Eltern". Die Naehrwerte kommen dann aus Samsung Health.
            val recipeId = entry.recipeId ?: continue

            // Ein nicht geteilter Gang ohne Marke laesst sich niemandem zuordnen. Ihn zu
            // ueberspringen hiesse, eine zu niedrige Zahl zu schreiben, und ihn dem
            // Haushalt zuzuschlagen hiesse, sie zu raten. Beides faellt spaeter nicht auf.
            if (markers(entry.note).isEmpty() && !shared(entry)) {
                reasons += "${entry.recipeName} (${entry.mealType}) traegt keine Marke"
                continue
            }

            val share = share(entry, marker, householdEaters)
            if (share == 0.0) continue

            val recipe = recipes[recipeId]
            if (recipe == null) {
                reasons += "Rezept ${entry.recipeName} nicht abrufbar"
                continue
            }
            if (recipe.servings <= 0.0) {
                reasons += "${recipe.name} steht auf ${recipe.servings} Portionen"
                continue
            }
            meals += PlannedMeal(
                entryId = entry.id,
                recipeId = recipe.id,
                recipeName = recipe.name,
                slot = Slot.ofTandoor(entry.mealType),
                ingredients = recipe.foods.map { portion(it, share / recipe.servings) },
            )
        }

        return if (reasons.isEmpty()) Result.Ready(meals) else Result.Incomplete(reasons)
    }

    /**
     * Ein Lebensmittel auf den eigenen Anteil gebracht. [fraction] ist der Anteil am
     * ganzen Rezept, also `Anteil in Portionen / Rezeptportionen`.
     *
     * Die Naehrwerte gehen als Wert je Einheit weiter: `Beitrag zum Rezept / Menge im
     * Rezept`. So laesst sich jede angepasste Menge nachrechnen, ohne Tandoor zu fragen.
     */
    private fun portion(food: RecipeFood, fraction: Double): Ingredient {
        val amount = food.amount?.takeIf { it > 0.0 }
        val mine = amount?.let { roundAmount(it * fraction, food.unit) }
        val perUnit = if (amount == null) null else food.nutrition?.times(1.0 / amount)
        return Ingredient(food.name, mine, mine, perUnit, food.unit)
    }

    /** Marken sind Namen: `@leo`, `@jazz`. Umlaute gehoeren dazu, Satzzeichen nicht. */
    private val MARKER = Regex("""@[\p{L}\p{N}_-]+""")

    /** Die Mahlzeitenart, die ohne Marke dem ganzen Haushalt gehoert. */
    private const val SHARED = "Abendessen"
}
