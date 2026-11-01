package com.example.synchealth.tandoor

import com.example.synchealth.essen.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** 400 g Nudeln mit 1600 kcal und 200 g Tomaten mit 40 kcal, fuer vier Portionen. */
    private fun recipe(
        servings: Double = 4.0,
        foods: List<RecipeFood> = listOf(
            RecipeFood("Nudeln", 400.0, MealNutrition(1600.0, 48.0, 8.0, 12.0, 4.0)),
            RecipeFood("Tomaten", 200.0, MealNutrition(40.0, 2.0, 0.4, 2.4, 6.0)),
        ),
    ) = Recipe(id = 7, name = "Bolognese", servings = servings, foods = foods)

    private fun meals(
        entries: List<MealPlanEntry>,
        recipes: Map<Int, Recipe> = mapOf(7 to recipe()),
        eaters: Int = 2,
    ) = MealPlanMath.meals(heute, "@leo", eaters, entries, recipes)

    private fun ready(entries: List<MealPlanEntry>, recipes: Map<Int, Recipe> = mapOf(7 to recipe())) =
        (meals(entries, recipes) as MealPlanMath.Result.Ready).meals

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
    fun `die Gramm werden auf den Anteil und die Portionszahl des Rezepts skaliert`() {
        // Eine von vier Portionen: 100 g Nudeln, 50 g Tomaten.
        val meal = ready(listOf(entry())).single()

        assertEquals(listOf(100.0, 50.0), meal.ingredients.map { it.amount })
        assertEquals(listOf(100.0, 50.0), meal.ingredients.map { it.planned })
        assertEquals(410.0, meal.nutrition.calories, 1e-9)
        assertEquals(12.5, meal.nutrition.protein, 1e-9)
    }

    @Test
    fun `die Naehrwerte gehen als Wert je Gramm weiter`() {
        val nudeln = ready(listOf(entry())).single().ingredients.first()

        assertEquals(4.0, nudeln.perUnit!!.calories, 1e-9)
    }

    @Test
    fun `Planeintrag und Mahlzeit werden mitgefuehrt`() {
        val meal = ready(listOf(entry(id = 263, mealType = "Abendessen"))).single()

        assertEquals(263, meal.entryId)
        assertEquals(Slot.ABEND, meal.slot)
    }

    @Test
    fun `zwei Eintraege desselben Tages bleiben zwei Gerichte`() {
        val result = ready(listOf(entry(id = 1), entry(id = 2)))

        assertEquals(listOf(1, 2), result.map { it.entryId })
    }

    /**
     * Fehlen einer Zutat Naehrwerte, haelt das den Tag nicht mehr an. Die Zutat traegt
     * stattdessen eine Warnung und zaehlt nicht mit.
     */
    @Test
    fun `eine Zutat ohne Naehrwerte zaehlt nicht mit und wird markiert`() {
        val ohne = recipe(foods = listOf(RecipeFood("Parmesan", 40.0, null)))

        val parmesan = ready(listOf(entry()), mapOf(7 to ohne)).single().ingredients.single()

        assertEquals(10.0, parmesan.amount!!, 1e-9)
        assertNull(parmesan.nutrition)
        assertEquals("ohne Nährwerte", parmesan.warning)
    }

    @Test
    fun `eine Zutat ohne Mengenangabe wird markiert`() {
        val ohne = recipe(foods = listOf(RecipeFood("Senf", null, MealNutrition(10.0, 0.0, 0.0, 0.0, 0.0))))

        val senf = ready(listOf(entry()), mapOf(7 to ohne)).single().ingredients.single()

        assertNull(senf.amount)
        assertEquals("ohne Mengenangabe", senf.warning)
    }

    /** Die Bolognese-Sosse: 1 Stueck mit 1131 kcal fuer vier Portionen, eine davon. */
    @Test
    fun `eine Zutat in Stueck rechnet je Stueck und rundet feiner`() {
        val sosse = recipe(foods = listOf(RecipeFood("Bolognese", 1.0, MealNutrition(1131.64, 0.0, 0.0, 0.0, 0.0), "Stück")))

        val ingredient = ready(listOf(entry()), mapOf(7 to sosse)).single().ingredients.single()

        assertEquals(0.25, ingredient.amount!!, 1e-9)
        assertEquals("Stück", ingredient.unit)
        assertEquals(282.91, ingredient.nutrition!!.calories, 1e-9)
    }

    @Test
    fun `Gramm werden auf eine Nachkommastelle gerundet`() {
        // 150 g fuer 4 Portionen, davon ein Drittel einer Portion: 12.5 g.
        val krumm = recipe(foods = listOf(RecipeFood("Reis", 150.0, null)))
        val drittel = entry(servings = 1.0, note = "@leo @jazz @gast")

        val reis = ready(listOf(drittel), mapOf(7 to krumm)).single().ingredients.single()

        assertEquals(12.5, reis.amount!!, 1e-9)
    }

    /**
     * So sieht ein Platz aus, an dem gegessen wird, aber nicht aus der eigenen Kueche,
     * etwa "Essen bei Eltern". Die Naehrwerte dieser Mahlzeit kommen wie bisher aus
     * Samsung Health, gemeldet wird deshalb nichts.
     */
    @Test
    fun `ein Eintrag ohne Rezept wird wortlos uebersprungen`() {
        val auswaerts = entry(recipeId = null, note = "")

        assertEquals(emptyList<Any>(), ready(listOf(auswaerts)))
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
        assertEquals(410.0, ready(listOf(abendessen)).single().nutrition.calories, 1e-9)
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

        val result = meals(listOf(ohneMarke)) as MealPlanMath.Result.Incomplete

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

        assertTrue(meals(listOf(brunch)) is MealPlanMath.Result.Incomplete)
    }

    @Test
    fun `fremdes Essen kostet weder Anteil noch Meldung`() {
        assertEquals(emptyList<Any>(), ready(listOf(entry(note = "@jazz"))))
    }

    @Test
    fun `ein nicht abrufbares Rezept haelt den Lauf an`() {
        val result = meals(listOf(entry()), recipes = emptyMap())

        assertTrue((result as MealPlanMath.Result.Incomplete).reasons.single().contains("Bolognese"))
    }

    @Test
    fun `ein Rezept ohne Portionszahl wuerde durch null teilen`() {
        val result = meals(listOf(entry()), mapOf(7 to recipe(servings = 0.0)))

        assertTrue(result is MealPlanMath.Result.Incomplete)
    }
}
