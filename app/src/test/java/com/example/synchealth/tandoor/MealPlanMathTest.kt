package com.example.synchealth.tandoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft die Zuordnung vom Wochenplan auf den eigenen Teller. Die Faelle stammen aus dem
 * echten Plan: vorgekochte Mittagessen als Tageseintraege, geteilte Abendessen ueber zwei
 * Abende, und Plaetze ohne Rezept, an denen auswaerts gegessen wird.
 */
class MealPlanMathTest {

    private val heute = LocalDate.of(2026, 9, 28)

    private fun entry(
        id: Int = 1,
        recipeId: Int? = 7,
        note: String = "@leo",
        servings: Double = 1.0,
        mealType: String = "Mittagessen",
        from: LocalDate = heute,
        to: LocalDate = from,
    ) = MealPlanEntry(id, recipeId, "Bolognese", servings, note, mealType, from, to)

    private fun recipe(
        servings: Double = 4.0,
        calories: Double = 2000.0,
        missing: Boolean = false,
        properties: List<FoodProperty>? = null,
    ) = Recipe(
        id = 7,
        name = "Bolognese",
        servings = servings,
        properties = properties ?: listOf(
            FoodProperty(MealNutrition.FDC_CALORIES, calories, missing),
            FoodProperty(MealNutrition.FDC_PROTEIN, 120.0, false),
            FoodProperty(MealNutrition.FDC_FAT, 80.0, false),
            FoodProperty(MealNutrition.FDC_FIBER, 40.0, false),
            FoodProperty(MealNutrition.FDC_SUGAR, 20.0, false),
        ),
    )

    private fun sum(
        entries: List<MealPlanEntry>,
        recipes: Map<Int, Recipe> = mapOf(7 to recipe()),
        eaters: Int = 2,
    ) = MealPlanMath.sum(heute, "@leo", eaters, entries, recipes)

    @Test
    fun `nur die erste Zeile der Notiz traegt Marken`() {
        val note = "@leo @jazz\nStatt Sahne: Hafercuisine, @tim war nur zu Besuch"

        assertEquals(listOf("@leo", "@jazz"), MealPlanMath.markers(note))
    }

    @Test
    fun `Grossschreibung und doppelte Nennung aendern den Anteil nicht`() {
        assertEquals(listOf("@leo"), MealPlanMath.markers("@Leo @leo"))
    }

    @Test
    fun `ein Abendessen ueber zwei Abende fuer zwei Esser zaehlt ein Viertel`() {
        val abendessen = entry(servings = 2.0, note = "@leo @jazz", to = heute.plusDays(1))

        assertEquals(0.5, MealPlanMath.share(abendessen, "@leo", 2), 1e-9)
    }

    @Test
    fun `ohne eigene Marke ist der Anteil null`() {
        assertEquals(0.0, MealPlanMath.share(entry(note = "@jazz"), "@leo", 2), 1e-9)
    }

    /**
     * Tandoors Datumsfilter greift allein auf `to_date`, ein laufender Eintrag faellt bei
     * der Tagesabfrage also heraus. Deshalb wird ein Fenster nach vorne geholt und hier
     * geschnitten, siehe [MealPlanMath.onDate].
     */
    @Test
    fun `ein laufender Eintrag deckt auch die Tage dazwischen ab`() {
        val woche = entry(from = heute.minusDays(2), to = heute.plusDays(2))

        assertEquals(listOf(woche), MealPlanMath.onDate(listOf(woche), heute))
        assertEquals(emptyList<MealPlanEntry>(), MealPlanMath.onDate(listOf(woche), heute.plusDays(3)))
    }

    @Test
    fun `der Naehrwert wird auf den Anteil und die Portionszahl des Rezepts skaliert`() {
        // 2000 kcal fuer vier Portionen, eine davon: 500.
        val result = sum(listOf(entry())) as MealPlanMath.Result.Ready

        assertEquals(500.0, result.nutrition.calories, 1e-9)
        assertEquals(30.0, result.nutrition.protein, 1e-9)
    }

    @Test
    fun `zwei Eintraege desselben Tages werden addiert`() {
        val mittag = entry(id = 1)
        val abend = entry(id = 2)

        val result = sum(listOf(mittag, abend)) as MealPlanMath.Result.Ready

        assertEquals(1000.0, result.nutrition.calories, 1e-9)
    }

    /**
     * So sieht ein Platz aus, an dem gegessen wird, aber nicht aus der eigenen Kueche,
     * etwa "Essen bei Eltern". Die Naehrwerte dieser Mahlzeit kommen wie bisher aus
     * Samsung Health, gemeldet wird deshalb nichts.
     */
    @Test
    fun `ein Eintrag ohne Rezept wird wortlos uebersprungen`() {
        val auswaerts = entry(recipeId = null, note = "")

        val result = sum(listOf(auswaerts)) as MealPlanMath.Result.Ready

        assertEquals(MealNutrition.NOTHING, result.nutrition)
    }

    /**
     * Abendessen werden zusammen gegessen und tragen deshalb keine Marke. Sie still zu
     * ueberspringen hiesse, die halbe Mahlzeit zu verlieren, und das faellt an der Zahl
     * nie wieder auf.
     */
    @Test
    fun `ein Abendessen ohne Marke gehoert dem ganzen Haushalt`() {
        val abendessen = entry(servings = 4.0, note = "am Dienstag kochen", mealType = "Abendessen", to = heute.plusDays(1))

        // 4 Portionen / 2 Tage / 2 Esser = 1 Portion, davon 2000 kcal / 4 = 500.
        assertEquals(1.0, MealPlanMath.share(abendessen, "@leo", 2), 1e-9)
        assertEquals(500.0, (sum(listOf(abendessen)) as MealPlanMath.Result.Ready).nutrition.calories, 1e-9)
    }

    @Test
    fun `mehr Esser teilen dasselbe Abendessen kleiner`() {
        val abendessen = entry(servings = 4.0, note = "", mealType = "Abendessen")

        assertEquals(2.0, MealPlanMath.share(abendessen, "@leo", 2), 1e-9)
        assertEquals(1.0, MealPlanMath.share(abendessen, "@leo", 4), 1e-9)
    }

    /**
     * Die Marke ist das staerkere Signal: Isst ein Abendessen ausnahmsweise nur einer,
     * steht seine Marke da, und der Haushalt zaehlt nicht mehr mit.
     */
    @Test
    fun `eine Marke am Abendessen sticht den Haushalt`() {
        val allein = entry(servings = 2.0, note = "@leo", mealType = "Abendessen")

        assertEquals(2.0, MealPlanMath.share(allein, "@leo", 2), 1e-9)
    }

    /**
     * Mittagessen stehen je Esser entfaltet im Plan. Fehlt die Marke, ist der Eintrag
     * unvollstaendig, und geraten wird nicht: weder auf null noch auf den Haushalt.
     */
    @Test
    fun `ein Mittagessen ohne Marke gibt keine Summe heraus`() {
        val ohneMarke = entry(note = "Statt Sahne: Hafercuisine")

        val result = sum(listOf(ohneMarke)) as MealPlanMath.Result.Incomplete

        assertEquals(listOf("Bolognese (Mittagessen) traegt keine Marke"), result.reasons)
    }

    /**
     * Wird die Mahlzeitenart in Tandoor umbenannt, greift die Regel fuer geteilte Gaenge
     * nicht mehr. Der Eintrag faellt dann in den lauten Fall, statt mit dem falschen
     * Teiler zu rechnen.
     */
    @Test
    fun `eine unbekannte Mahlzeitenart ohne Marke gibt keine Summe heraus`() {
        val brunch = entry(note = "", mealType = "Brunch")

        assertTrue(sum(listOf(brunch)) is MealPlanMath.Result.Incomplete)
    }

    @Test
    fun `fremdes Essen kostet weder Anteil noch Meldung`() {
        val result = sum(listOf(entry(note = "@jazz"))) as MealPlanMath.Result.Ready

        assertEquals(MealNutrition.NOTHING, result.nutrition)
    }

    /**
     * `missing_value` heisst, eine Zutat ist still aus der Summe gefallen. Die Zahl waere
     * zu niedrig, und man sieht es ihr nicht an, also wird sie nicht herausgegeben.
     */
    @Test
    fun `eine unvollstaendige Eigenschaft haelt den Lauf an`() {
        val result = sum(listOf(entry()), mapOf(7 to recipe(missing = true)))

        val incomplete = result as MealPlanMath.Result.Incomplete
        assertEquals(listOf("Bolognese: Kalorien ist unvollstaendig"), incomplete.reasons)
    }

    @Test
    fun `eine fehlende Eigenschaft haelt den Lauf genauso an`() {
        val ohneZucker = recipe(
            properties = listOf(
                FoodProperty(MealNutrition.FDC_CALORIES, 2000.0, false),
                FoodProperty(MealNutrition.FDC_PROTEIN, 120.0, false),
                FoodProperty(MealNutrition.FDC_FAT, 80.0, false),
                FoodProperty(MealNutrition.FDC_FIBER, 40.0, false),
            ),
        )

        val result = sum(listOf(entry()), mapOf(7 to ohneZucker))

        assertTrue((result as MealPlanMath.Result.Incomplete).reasons.single().contains("Zucker"))
    }

    @Test
    fun `ein nicht abrufbares Rezept haelt den Lauf an`() {
        val result = sum(listOf(entry()), recipes = emptyMap())

        assertTrue((result as MealPlanMath.Result.Incomplete).reasons.single().contains("Bolognese"))
    }

    @Test
    fun `ein Rezept ohne Portionszahl wuerde durch null teilen`() {
        val result = sum(listOf(entry()), mapOf(7 to recipe(servings = 0.0)))

        assertTrue(result is MealPlanMath.Result.Incomplete)
    }
}
