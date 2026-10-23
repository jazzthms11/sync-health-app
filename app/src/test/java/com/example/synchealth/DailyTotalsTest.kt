package com.example.synchealth

import com.example.synchealth.tandoor.MealNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prueft, wie sich Samsung Health und Tandoor die fuenf geteilten Felder teilen. Alles
 * haengt an einer Unterscheidung: `null` heisst "keine Quelle hat geliefert", `0.0` ist
 * ein Wert.
 */
class DailyTotalsTest {

    private val samsung = HealthReport(
        steps = 8342,
        calories = 600.0,
        protein = 30.0,
        fat = 20.0,
        fiber = 5.0,
        sugar = 15.0,
        sleep = 7.25,
    )

    private val mittagUndAbend = MealNutrition(
        calories = 1200.0,
        protein = 80.0,
        fat = 45.0,
        fiber = 20.0,
        sugar = 10.0,
    )

    @Test
    fun `beide Quellen werden je Naehrwert summiert`() {
        val merged = DailyTotals.merge(samsung, mittagUndAbend)

        assertEquals(1800.0, merged.calories!!, 1e-9)
        assertEquals(110.0, merged.protein!!, 1e-9)
        assertEquals(25.0, merged.sugar!!, 1e-9)
    }

    @Test
    fun `Schritte und Schlaf gehen unberuehrt durch`() {
        val merged = DailyTotals.merge(samsung, mittagUndAbend)

        assertEquals(8342L, merged.steps!!)
        assertEquals(7.25, merged.sleep!!, 1e-9)
    }

    /**
     * Der Fall, wegen dem "0 heisst noch keine Daten" neu gefasst wurde: In Samsung Health
     * steht nichts, weil Mittag und Abend nicht mehr von Hand getippt werden.
     */
    @Test
    fun `ohne Eintrag in Samsung Health zaehlt der Plan allein`() {
        val leer = HealthReport(steps = 8342)

        val merged = DailyTotals.merge(leer, mittagUndAbend)

        assertEquals(1200.0, merged.calories!!, 1e-9)
    }

    @Test
    fun `hat keine Quelle etwas geliefert bleibt das Feld leer`() {
        val merged = DailyTotals.merge(HealthReport(steps = 8342), MealNutrition.NOTHING)

        assertNull(merged.calories)
        assertNull(merged.protein)
        assertEquals(8342L, merged.steps!!)
    }

    @Test
    fun `ein Tag ohne Planeintrag laesst die Samsung-Werte stehen`() {
        val merged = DailyTotals.merge(samsung, MealNutrition.NOTHING)

        assertEquals(600.0, merged.calories!!, 1e-9)
    }

    /**
     * Samsung Health allein waere die halbe Mahlzeit. Eine zu niedrige Zahl sieht in der
     * Tagesnotiz richtig aus, zaehlt in den Wochen-Rollups mit und laesst sich hinterher
     * nicht mehr von einer echten unterscheiden.
     */
    @Test
    fun `ohne belastbaren Stand bleiben die fuenf geteilten Felder leer`() {
        val merged = DailyTotals.merge(samsung, null)

        assertNull(merged.calories)
        assertNull(merged.protein)
        assertNull(merged.fat)
        assertNull(merged.fiber)
        assertNull(merged.sugar)
    }

    @Test
    fun `beim Ausfall bleiben Schritte Schlaf und Wasser stehen`() {
        val merged = DailyTotals.merge(
            samsung.copy(water = 1750.0),
            null,
        )

        assertEquals(8342L, merged.steps!!)
        assertEquals(7.25, merged.sleep!!, 1e-9)
        assertEquals(1750.0, merged.water!!, 1e-9)
    }
}
