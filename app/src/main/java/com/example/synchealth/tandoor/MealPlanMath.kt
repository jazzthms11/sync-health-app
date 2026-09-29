package com.example.synchealth.tandoor

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Die ganze Rechnung vom Wochenplan zu den fuenf Naehrwerten eines Tages.
 *
 * Arbeitet nur auf den Datenklassen aus [TandoorJson]: kein Netz, kein Android, keine
 * Uhr. Dieselbe Trennung wie zwischen [com.example.synchealth.vault.NotePatcher] und
 * [com.example.synchealth.vault.WebDavClient], und aus demselben Grund: Die Zuordnung
 * ueber Marken und Portionen ist das, was schiefgehen kann, und genau die laesst sich so
 * ohne Server pruefen.
 */
object MealPlanMath {

    sealed interface Result {
        data class Ready(val nutrition: MealNutrition) : Result

        /**
         * Die Summe waere nachweislich zu niedrig. Sie wird nicht herausgegeben, sonst
         * stuende eine falsche Zahl in der Tagesnotiz, und eine falsche Zahl faellt dort
         * nie wieder auf.
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
     * der Eintrag in den lauten Fall: [sum] gibt dann keine Summe heraus, statt still mit
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
     * Summiert, was [marker] an [date] laut Plan isst.
     *
     * [recipes] muss jedes Rezept enthalten, auf das ein Eintrag zeigt; wer die Rezepte
     * holt, entscheidet [TandoorSource].
     */
    fun sum(
        date: LocalDate,
        marker: String,
        householdEaters: Int,
        entries: List<MealPlanEntry>,
        recipes: Map<Int, Recipe>,
    ): Result {
        var total = MealNutrition.NOTHING
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
            when (val portion = portion(recipe, share)) {
                is Portion.Ok -> total += portion.nutrition
                is Portion.Incomplete -> reasons += portion.reasons
            }
        }

        return if (reasons.isEmpty()) Result.Ready(total) else Result.Incomplete(reasons)
    }

    private sealed interface Portion {
        data class Ok(val nutrition: MealNutrition) : Portion
        data class Incomplete(val reasons: List<String>) : Portion
    }

    /**
     * `total_value * Anteil / rezept.servings` je Naehrwert: Der Wert gilt fuer das ganze
     * Rezept in seiner hinterlegten Portionszahl, nicht je Portion.
     */
    private fun portion(recipe: Recipe, share: Double): Portion {
        if (recipe.servings <= 0.0) {
            return Portion.Incomplete(listOf("${recipe.name} steht auf ${recipe.servings} Portionen"))
        }
        val byFdc = recipe.properties.filter { it.fdcId != null }.associateBy { it.fdcId }
        val reasons = mutableListOf<String>()

        // Eine fehlende und eine unvollstaendige Eigenschaft heissen dasselbe: Die Zahl
        // waere zu niedrig, und man sieht es ihr nicht an. Beide halten den Lauf an.
        fun value(fdcId: Int, name: String): Double {
            val property = byFdc[fdcId]
            if (property == null) {
                reasons += "${recipe.name} fuehrt $name nicht"
                return 0.0
            }
            if (property.missing) {
                reasons += "${recipe.name}: $name ist unvollstaendig"
                return 0.0
            }
            return property.totalValue * share / recipe.servings
        }

        val nutrition = MealNutrition(
            calories = value(MealNutrition.FDC_CALORIES, "Kalorien"),
            protein = value(MealNutrition.FDC_PROTEIN, "Protein"),
            fat = value(MealNutrition.FDC_FAT, "Fett"),
            fiber = value(MealNutrition.FDC_FIBER, "Ballaststoffe"),
            sugar = value(MealNutrition.FDC_SUGAR, "Zucker"),
        )
        return if (reasons.isEmpty()) Portion.Ok(nutrition) else Portion.Incomplete(reasons)
    }

    /** Marken sind Namen: `@leo`, `@jazz`. Umlaute gehoeren dazu, Satzzeichen nicht. */
    private val MARKER = Regex("""@[\p{L}\p{N}_-]+""")

    /** Die Mahlzeitenart, die ohne Marke dem ganzen Haushalt gehoert. */
    private const val SHARED = "Abendessen"
}
