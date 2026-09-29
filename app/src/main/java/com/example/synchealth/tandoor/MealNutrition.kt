package com.example.synchealth.tandoor

/**
 * Die fuenf Naehrwerte, die sich Tandoor und Samsung Health in der Tagesnotiz teilen.
 *
 * Kalorien in kcal, alles andere in Gramm. Kohlenhydrate rechnet Tandoor zwar mit, die
 * Tagesnotiz fuehrt dafuer aber kein Feld.
 */
data class MealNutrition(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
) {
    operator fun plus(other: MealNutrition): MealNutrition = MealNutrition(
        calories = calories + other.calories,
        protein = protein + other.protein,
        fat = fat + other.fat,
        fiber = fiber + other.fiber,
        sugar = sugar + other.sugar,
    )

    companion object {
        val NOTHING = MealNutrition()

        /**
         * Die FDC-Nummern, ueber die zugeordnet wird. Der Name der Eigenschaft taugt nicht
         * als Schluessel, er ist in Tandoor frei vergeben und steht auf Deutsch da.
         */
        const val FDC_CALORIES = 1008
        const val FDC_PROTEIN = 1003
        const val FDC_FAT = 1004
        const val FDC_FIBER = 1079
        const val FDC_SUGAR = 2000
    }
}
