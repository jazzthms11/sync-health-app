package com.example.synchealth.essen

import com.example.synchealth.tandoor.MealNutrition
import com.example.synchealth.tandoor.TandoorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft die Regeln vom 24.10.2026: sieben Tage frisch, danach fest, Anpassungen bleiben
 * beim Neuladen, Korrekturen aus Tandoor kommen an.
 */
class MealMergeTest {

    private val ersterSync = LocalDate.of(2026, 10, 24)
    private val jeGramm = MealNutrition(2.0, 0.1, 0.05, 0.02, 0.01)

    private fun meal(vararg ingredients: Ingredient, entryId: Int = 259) =
        PlannedMeal(entryId, 13, "Bowl", Slot.MITTAG, ingredients.toList())

    private fun planned(name: String, grams: Double, perUnit: MealNutrition? = jeGramm) =
        Ingredient(name, grams, grams, perUnit)

    private fun tandoor(vararg meals: PlannedMeal) = TandoorSource.Outcome.Ready(meals.toList())

    /** In der Notiz: Reis von 100 auf 60 angepasst, Tomaten unveraendert. */
    private val notiz = MealMerge.Existing(
        ersterSync,
        listOf(
            meal(
                Ingredient("Reis", 60.0, 100.0, jeGramm),
                planned("Tomaten", 50.0),
            ),
        ),
    )

    @Test
    fun `der erste Sync setzt das Datum`() {
        val result = MealMerge.merge(ersterSync, null, tandoor(meal(planned("Reis", 100.0))))

        assertEquals(ersterSync, result.firstSync)
        assertEquals(200.0, result.nutrition!!.calories, 1e-9)
    }

    @Test
    fun `im frischen Fenster bleibt die eigene Anpassung`() {
        val result = MealMerge.merge(
            ersterSync.plusDays(2),
            notiz,
            tandoor(meal(planned("Reis", 100.0), planned("Tomaten", 50.0))),
        )

        val reis = result.meals!!.single().ingredients.first()
        assertEquals(60.0, reis.amount!!, 1e-9)
    }

    /** Der Fall, fuer den es das frische Fenster gibt: Im Rezept war eine Menge falsch. */
    @Test
    fun `eine Korrektur in Tandoor kommt an, wo nichts angepasst war`() {
        val result = MealMerge.merge(
            ersterSync.plusDays(2),
            notiz,
            tandoor(meal(planned("Reis", 100.0), planned("Tomaten", 80.0))),
        )

        val tomaten = result.meals!!.single().ingredients.last()
        assertEquals(80.0, tomaten.amount!!, 1e-9)
    }

    @Test
    fun `neue Naehrwerte aus Tandoor gelten auch fuer angepasste Zutaten`() {
        val besser = MealNutrition(3.0, 0.1, 0.05, 0.02, 0.01)

        val result = MealMerge.merge(
            ersterSync.plusDays(1),
            notiz,
            tandoor(meal(planned("Reis", 100.0, besser), planned("Tomaten", 50.0))),
        )

        assertEquals(180.0, result.meals!!.single().ingredients.first().nutrition!!.calories, 1e-9)
    }

    @Test
    fun `eine Zutat, die es nicht mehr gibt, faellt weg`() {
        val result = MealMerge.merge(ersterSync.plusDays(1), notiz, tandoor(meal(planned("Reis", 100.0))))

        assertEquals(listOf("Reis"), result.meals!!.single().ingredients.map { it.name })
    }

    @Test
    fun `ein Gericht, das nicht mehr im Plan steht, wird entfernt und gemeldet`() {
        val result = MealMerge.merge(ersterSync.plusDays(1), notiz, tandoor())

        assertEquals(emptyList<PlannedMeal>(), result.meals)
        assertTrue(result.notes.single().contains("nicht mehr im Plan"))
    }

    @Test
    fun `am siebten Tag wird noch nachgezogen, am achten nicht mehr`() {
        val neu = tandoor(meal(planned("Reis", 100.0), planned("Tomaten", 80.0)))

        val tag7 = MealMerge.merge(ersterSync.plusDays(7), notiz, neu)
        val tag8 = MealMerge.merge(ersterSync.plusDays(8), notiz, neu)

        assertEquals(80.0, tag7.meals!!.single().ingredients.last().amount!!, 1e-9)
        assertEquals(50.0, tag8.meals!!.single().ingredients.last().amount!!, 1e-9)
    }

    @Test
    fun `ein fester Tag braucht Tandoor nicht`() {
        val result = MealMerge.merge(
            ersterSync.plusDays(30),
            notiz,
            TandoorSource.Outcome.Unavailable("keine Verbindung"),
        )

        assertEquals(notiz.meals, result.meals)
        assertEquals(emptyList<String>(), result.notes)
    }

    @Test
    fun `Neu laden holt auch einen festen Tag und behaelt die Anpassung`() {
        val result = MealMerge.merge(
            ersterSync.plusDays(30),
            notiz,
            tandoor(meal(planned("Reis", 100.0), planned("Tomaten", 80.0))),
            MealMerge.Edits(reload = setOf(259)),
        )

        val (reis, tomaten) = result.meals!!.single().ingredients
        assertEquals(60.0, reis.amount!!, 1e-9)
        assertEquals(80.0, tomaten.amount!!, 1e-9)
    }

    @Test
    fun `faellt Tandoor aus, gilt der Stand aus der Notiz`() {
        val result = MealMerge.merge(
            ersterSync.plusDays(1),
            notiz,
            TandoorSource.Outcome.Unavailable("Token abgelehnt."),
        )

        assertEquals(notiz.meals, result.meals)
        assertTrue(result.notes.single().contains("Stand aus der Notiz"))
    }

    /** Ohne Notiz und ohne Tandoor gibt es keinen belastbaren Stand, nicht etwa null kcal. */
    @Test
    fun `faellt Tandoor beim ersten Mal aus, gibt es keine Summe`() {
        val result = MealMerge.merge(ersterSync, null, TandoorSource.Outcome.Unavailable("Token abgelehnt."))

        assertNull(result.meals)
        assertNull(result.nutrition)
    }

    @Test
    fun `ohne Tandoor-Zugang bleibt es beim Stand der Notiz`() {
        assertEquals(notiz.meals, MealMerge.merge(ersterSync, notiz, null).meals)
        assertEquals(emptyList<PlannedMeal>(), MealMerge.merge(ersterSync, null, null).meals)
    }

    @Test
    fun `eine Aenderung aus der App setzt die Gramm`() {
        val result = MealMerge.merge(
            ersterSync,
            notiz,
            tandoor(meal(planned("Reis", 100.0), planned("Tomaten", 50.0))),
            MealMerge.Edits(grams = mapOf((259 to "Tomaten") to 0.0)),
        )

        val tomaten = result.meals!!.single().ingredients.last()
        assertEquals(0.0, tomaten.amount!!, 1e-9)
        assertTrue(tomaten.adjusted)
    }

    @Test
    fun `Zutaten ohne Naehrwerte werden gemeldet`() {
        val result = MealMerge.merge(ersterSync, null, tandoor(meal(planned("Parmesan", 30.0, null))))

        assertEquals(listOf("Bowl: Parmesan ohne Nährwerte"), result.notes)
        assertEquals(0.0, result.nutrition!!.calories, 1e-9)
    }
}
