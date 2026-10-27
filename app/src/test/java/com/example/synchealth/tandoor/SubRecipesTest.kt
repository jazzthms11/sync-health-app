package com.example.synchealth.tandoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gebaut nach "Pilzgulasch mit Kartoffelknödeln", wie Tandoor es am 27.10.2026 geliefert
 * hat: ein Rezept fuer 3 Personen aus nur zwei Zeilen, "1 Stück Pilzgulasch" und
 * "1 Stück Kartoffelknödel", die jeweils auf ein eigenes Rezept zeigen. Die Zutaten sind
 * gekuerzt, die Form ist dieselbe.
 */
class SubRecipesTest {

    private fun kcal(value: Double) = MealNutrition(value, value / 10, value / 20, value / 30, value / 40)

    private val gulasch = Recipe(
        id = 91, name = "Pilzgulasch mit Kartoffeln", servings = 3.0, servingsText = "Portionen",
        foods = listOf(
            RecipeFood("Austernpilz", 600.0, kcal(198.0)),
            RecipeFood("Kartoffel", 250.0, kcal(175.0)),
        ),
    )

    private val knoedel = Recipe(
        id = 92, name = "Kartoffelknödel", servings = 4.0, servingsText = "Personen",
        foods = listOf(
            RecipeFood("Kartoffel", 1000.0, kcal(700.0)),
            RecipeFood("Kartoffelstärke", 180.0, kcal(631.8)),
            RecipeFood("Ei (M)", null, null),
        ),
    )

    /** Die Naehrwerte am Lebensmittel sind veraltet und duerfen nicht mehr zaehlen. */
    private val gericht = Recipe(
        id = 93, name = "Pilzgulasch mit Kartoffelknödeln", servings = 3.0, servingsText = "Personen",
        foods = listOf(
            RecipeFood("Pilzgulasch", 1.0, kcal(848.31), "Stück", recipeId = 91),
            RecipeFood("Kartoffelknödel", 1.0, kcal(1703.69), "Stück", recipeId = 92),
        ),
    )

    private val alle = mapOf(91 to gulasch, 92 to knoedel, 93 to gericht)

    @Test
    fun `ein Stueck verlinktes Rezept bringt dessen Zutaten ganz`() {
        val flat = SubRecipes.flatten(gericht, alle)

        assertEquals(listOf("Austernpilz", "Kartoffel", "Kartoffelstärke", "Ei (M)"), flat.foods.map { it.name })
        assertEquals(3.0, flat.servings, 1e-9)
        assertEquals(180.0, flat.foods.single { it.name == "Kartoffelstärke" }.amount!!, 1e-9)
    }

    /** Die Kartoffel aus Gulasch und Knoedeln steht einmal da, mit beiden Mengen. */
    @Test
    fun `gleiche Zutaten aus zwei Unterrezepten werden zusammengefasst`() {
        val kartoffel = SubRecipes.flatten(gericht, alle).foods.single { it.name == "Kartoffel" }

        assertEquals(1250.0, kartoffel.amount!!, 1e-9)
        assertEquals(875.0, kartoffel.nutrition!!.calories, 1e-9)
    }

    @Test
    fun `gerechnet wird aus dem Unterrezept, nicht aus dem Wert am Lebensmittel`() {
        val flat = SubRecipes.flatten(gericht, alle)

        val summe = flat.foods.mapNotNull { it.nutrition?.calories }.sum()
        assertEquals(198.0 + 175.0 + 700.0 + 631.8, summe, 1e-9)
    }

    @Test
    fun `Portionen rechnen gegen die Portionen des Unterrezepts`() {
        val zwei = RecipeFood("Kartoffelknödel", 2.0, null, "Personen", recipeId = 92)

        assertEquals(0.5, SubRecipes.factor(zwei, knoedel)!!, 1e-9)
    }

    @Test
    fun `Gramm rechnen gegen das Gewicht des Unterrezepts`() {
        val teil = RecipeFood("Pilzgulasch", 425.0, null, "g", recipeId = 91)

        assertEquals(0.5, SubRecipes.factor(teil, gulasch)!!, 1e-9)
    }

    /** Das Ei hat keine Grammzahl, das Gewicht der Knoedel ist also unbekannt. */
    @Test
    fun `ohne bekanntes Gewicht bleibt die Zeile stehen`() {
        val teil = gericht.copy(foods = listOf(RecipeFood("Kartoffelknödel", 300.0, kcal(400.0), "g", recipeId = 92)))

        assertNull(SubRecipes.factor(teil.foods.single(), knoedel))
        assertEquals(listOf("Kartoffelknödel"), SubRecipes.flatten(teil, alle).foods.map { it.name })
    }

    @Test
    fun `ein fehlendes Unterrezept laesst die Zeile wie bisher stehen`() {
        val flat = SubRecipes.flatten(gericht, mapOf(91 to gulasch))

        assertEquals(listOf("Austernpilz", "Kartoffel", "Kartoffelknödel"), flat.foods.map { it.name })
        assertEquals(1703.69, flat.foods.last().nutrition!!.calories, 1e-9)
    }

    @Test
    fun `ein Rezept das auf sich selbst zeigt haengt nicht`() {
        val kreis = Recipe(1, "Kreis", 1.0, listOf(RecipeFood("Kreis", 1.0, null, "Stück", recipeId = 1)))

        assertEquals(listOf("Kreis"), SubRecipes.flatten(kreis, mapOf(1 to kreis)).foods.map { it.name })
    }

    @Test
    fun `ein Schritt-Rezept zaehlt einmal ganz`() {
        val mitSchritt = Recipe(5, "Bowl mit Soße", 2.0, listOf(RecipeFood("Reis", 200.0, kcal(700.0))), stepRecipes = listOf(91))

        val flat = SubRecipes.flatten(mitSchritt, alle)

        assertEquals(listOf("Reis", "Austernpilz", "Kartoffel"), flat.foods.map { it.name })
        assertEquals(600.0, flat.foods[1].amount!!, 1e-9)
    }

    @Test
    fun `verschachtelte Unterrezepte werden durchgerechnet`() {
        val doppelt = Recipe(94, "Doppelt", 6.0, listOf(RecipeFood("Gericht", 2.0, null, "Stück", recipeId = 93)))

        val flat = SubRecipes.flatten(doppelt, alle + (94 to doppelt))

        assertEquals(2500.0, flat.foods.single { it.name == "Kartoffel" }.amount!!, 1e-9)
    }
}
