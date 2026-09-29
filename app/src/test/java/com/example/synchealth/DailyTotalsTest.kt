package com.example.synchealth

import com.example.synchealth.tandoor.MealNutrition
import com.example.synchealth.tandoor.TandoorSource
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

    private fun ready(nutrition: MealNutrition) = TandoorSource.Outcome.Ready(nutrition)

    @Test
    fun `ohne Tandoor-Zugang bleibt es bei Samsung Health allein`() {
        val merged = DailyTotals.merge(samsung, null)

        assertEquals(samsung, merged.report)
        assertEquals(emptyList<String>(), merged.notes)
    }

    @Test
    fun `beide Quellen werden je Naehrwert summiert`() {
        val merged = DailyTotals.merge(samsung, ready(mittagUndAbend))

        assertEquals(1800.0, merged.report.calories!!, 1e-9)
        assertEquals(110.0, merged.report.protein!!, 1e-9)
        assertEquals(25.0, merged.report.sugar!!, 1e-9)
    }

    @Test
    fun `Schritte und Schlaf gehen unberuehrt durch`() {
        val merged = DailyTotals.merge(samsung, ready(mittagUndAbend))

        assertEquals(8342L, merged.report.steps!!)
        assertEquals(7.25, merged.report.sleep!!, 1e-9)
    }

    /**
     * Der Fall, wegen dem "0 heisst noch keine Daten" neu gefasst wurde: In Samsung Health
     * steht nichts, weil Mittag und Abend nicht mehr von Hand getippt werden.
     */
    @Test
    fun `ohne Eintrag in Samsung Health zaehlt der Plan allein`() {
        val leer = HealthReport(steps = 8342)

        val merged = DailyTotals.merge(leer, ready(mittagUndAbend))

        assertEquals(1200.0, merged.report.calories!!, 1e-9)
    }

    @Test
    fun `hat keine Quelle etwas geliefert bleibt das Feld leer`() {
        val merged = DailyTotals.merge(HealthReport(steps = 8342), ready(MealNutrition.NOTHING))

        assertNull(merged.report.calories)
        assertNull(merged.report.protein)
        assertEquals(8342L, merged.report.steps!!)
    }

    @Test
    fun `ein Tag ohne Planeintrag laesst die Samsung-Werte stehen`() {
        val merged = DailyTotals.merge(samsung, ready(MealNutrition.NOTHING))

        assertEquals(600.0, merged.report.calories!!, 1e-9)
    }

    /**
     * Samsung Health allein waere die halbe Mahlzeit. Eine zu niedrige Zahl sieht in der
     * Tagesnotiz richtig aus, zaehlt in den Wochen-Rollups mit und laesst sich hinterher
     * nicht mehr von einer echten unterscheiden.
     */
    @Test
    fun `faellt Tandoor aus bleiben die fuenf geteilten Felder leer`() {
        val merged = DailyTotals.merge(samsung, TandoorSource.Outcome.Unavailable("Token abgelehnt."))

        assertNull(merged.report.calories)
        assertNull(merged.report.protein)
        assertNull(merged.report.fat)
        assertNull(merged.report.fiber)
        assertNull(merged.report.sugar)
    }

    @Test
    fun `der Ausfall wird gemeldet statt verschluckt`() {
        val merged = DailyTotals.merge(samsung, TandoorSource.Outcome.Unavailable("Token abgelehnt."))

        assertEquals(listOf("Token abgelehnt."), merged.notes)
    }

    @Test
    fun `beim Ausfall bleiben Schritte Schlaf und Wasser stehen`() {
        val merged = DailyTotals.merge(
            samsung.copy(water = 1750.0),
            TandoorSource.Outcome.Unavailable("keine Verbindung"),
        )

        assertEquals(8342L, merged.report.steps!!)
        assertEquals(7.25, merged.report.sleep!!, 1e-9)
        assertEquals(1750.0, merged.report.water!!, 1e-9)
    }

    /** Eine belastbare Summe hat nichts zu melden, der Grund steht nur im Ausfall. */
    @Test
    fun `eine vollstaendige Summe meldet nichts`() {
        val merged = DailyTotals.merge(samsung, ready(mittagUndAbend))

        assertEquals(emptyList<String>(), merged.notes)
        assertEquals(1800.0, merged.report.calories!!, 1e-9)
    }
}
