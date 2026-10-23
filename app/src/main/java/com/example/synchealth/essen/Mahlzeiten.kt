package com.example.synchealth.essen

import com.example.synchealth.tandoor.MealNutrition
import kotlin.math.abs
import kotlin.math.round

/**
 * Die vier Mahlzeiten, nach denen der Essen-Callout in der Tagesnotiz gegliedert ist.
 *
 * Tandoor und Samsung Health ordnen jeweils ueber ihre eigene Mahlzeitenart zu. Was keiner
 * der drei Hauptmahlzeiten entspricht, landet unter Snacks.
 */
enum class Slot(val label: String) {
    FRUEHSTUECK("Frühstück"),
    MITTAG("Mittag"),
    ABEND("Abend"),
    SNACKS("Snacks");

    companion object {
        /** Tandoors Mahlzeitenart, so wie sie im Plan heisst. */
        fun ofTandoor(mealType: String): Slot = when (mealType.trim().lowercase()) {
            "frühstück", "fruehstueck" -> FRUEHSTUECK
            "mittagessen", "mittag" -> MITTAG
            "abendessen", "abend" -> ABEND
            else -> SNACKS
        }

        fun ofLabel(label: String): Slot? = entries.firstOrNull { it.label == label.trim() }
    }
}

/**
 * Eine Zutat auf dem eigenen Teller.
 *
 * [planned] ist die Menge laut Tandoor, schon auf den eigenen Anteil umgerechnet. [amount]
 * ist das, was gilt: gleich [planned], solange nichts angepasst wurde. Ist [amount] eine
 * andere Zahl, war es eine eigene Anpassung, und die bleibt beim Neuladen aus Tandoor
 * erhalten, siehe [MealMerge].
 *
 * [unit] ist `g`, wann immer Tandoor eine Grammzahl kennt. Sonst steht die Zutat in der
 * Einheit aus dem Rezept, etwa `Stück` bei einem Fladenbrot, dessen Naehrwerte je Stueck
 * gepflegt sind. [perUnit] gilt dann je Stueck.
 *
 * [amount] und [planned] sind `null`, wenn Tandoor keine Menge kennt, mit der sich
 * rechnen liesse. [perUnit] ist `null`, wenn Naehrwerte fehlen. In beiden Faellen zaehlt
 * die Zutat nicht mit und wird mit einer Warnung angezeigt.
 */
data class Ingredient(
    val name: String,
    val amount: Double?,
    val planned: Double?,
    val perUnit: MealNutrition?,
    val unit: String = GRAMS,
) {
    val nutrition: MealNutrition?
        get() = if (amount != null && perUnit != null) perUnit * amount else null

    /** Weicht von Tandoor ab. Verglichen wird auf die Stelle, auf die die Notiz rundet. */
    val adjusted: Boolean
        get() = when {
            amount == null || planned == null -> amount != planned
            else -> abs(roundAmount(amount, unit) - roundAmount(planned, unit)) > 1e-9
        }

    /** Der Grund, warum die Zutat nicht mitzaehlt, oder `null`, wenn sie mitzaehlt. */
    val warning: String?
        get() = when {
            planned == null -> "ohne Mengenangabe"
            perUnit == null -> "ohne Nährwerte"
            else -> null
        }
}

/** Ein Gericht aus dem Wochenplan, so wie es auf dem eigenen Teller liegt. */
data class PlannedMeal(
    /** Die ID des Planeintrags in Tandoor. Ueber sie werden Notiz und Plan abgeglichen. */
    val entryId: Int,
    val recipeId: Int,
    val recipeName: String,
    val slot: Slot,
    val ingredients: List<Ingredient>,
) {
    val nutrition: MealNutrition
        get() = ingredients.mapNotNull { it.nutrition }.fold(MealNutrition.NOTHING, MealNutrition::plus)
}

/** Ein Eintrag aus Samsung Health. Wird nur angezeigt, geaendert wird er dort. */
data class SamsungMeal(
    val title: String,
    val slot: Slot,
    val calories: Double?,
)

const val GRAMS = "g"

/**
 * Rundet eine Menge so, wie sie in der Notiz steht: Gramm auf eine Nachkommastelle, alles
 * andere auf zwei. Ein Viertel Stueck Sosse waeren sonst 0.3 statt 0.25, und bei 1100 kcal
 * je Stueck machte das 50 kcal aus.
 */
fun roundAmount(value: Double, unit: String): Double {
    val factor = if (unit == GRAMS) 10.0 else 100.0
    return round(value * factor) / factor
}
