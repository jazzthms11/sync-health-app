package com.example.synchealth

import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.MealMerge
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.tandoor.MealNutrition
import com.example.synchealth.tandoor.TandoorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Der ganze Tag: Callout schreiben, zuruecklesen, Felder darunter patchen. */
class DayNoteTest {

    private val notiz = """
        ## Notizen

        - Etwas Text.

        ## Daten
        ###### Gesundheit
        - Schlaf::
        - Schritte::

        ###### Essen
        - Kalorien::
        - Protein::
        - Ballaststoffe::
        - Fett::
        - Zucker::
        - Wasser::
    """.trimIndent()

    private val heute = LocalDate.of(2026, 10, 24)

    private val samsung = HealthReport(steps = 8000, calories = 400.0, protein = 20.0)
    private val samsungMeals = listOf(SamsungMeal("Haferflocken", Slot.FRUEHSTUECK, 400.0))

    /** 100 g Reis zu 3 kcal je Gramm und 50 g Tomaten zu 0.2. */
    private val bowl = PlannedMeal(
        259, 13, "Bowl", Slot.MITTAG,
        listOf(
            Ingredient("Reis", 100.0, 100.0, MealNutrition(3.0, 0.1, 0.01, 0.02, 0.0)),
            Ingredient("Tomaten", 50.0, 50.0, MealNutrition(0.2, 0.01, 0.0, 0.01, 0.03)),
        ),
    )

    private fun build(
        content: String?,
        tandoor: TandoorSource.Outcome? = TandoorSource.Outcome.Ready(listOf(bowl)),
        edits: MealMerge.Edits = MealMerge.Edits(),
        today: LocalDate = heute,
    ) = DayNote.build(content, today, samsung, samsungMeals, tandoor, edits)

    @Test
    fun `Callout und Summen landen in der Notiz`() {
        val result = build(notiz)
        val text = result.content!!

        assertTrue(text.contains("> [!info]- Essen · 710 kcal"))
        assertTrue(text.contains("- Kalorien:: 710"))
        assertTrue(text.contains("- Protein:: 30.5"))
        assertTrue(text.contains("- Schritte:: 8000"))
        assertTrue("Essen" in result.patch!!.patched)
    }

    @Test
    fun `ein zweiter Lauf aendert nichts`() {
        val once = build(notiz).content!!

        assertTrue(build(once).patch!!.unchanged)
    }

    /** Die Notiz ist die Wahrheit: Was von Hand geaendert wurde, rechnet der naechste Lauf. */
    @Test
    fun `eine Aenderung in Obsidian wird beim naechsten Lauf gerechnet`() {
        val edited = build(notiz).content!!.replace("[Reis:: 100]", "[Reis:: 50]")

        val text = build(edited).content!!

        assertTrue(text.contains("[Reis:: 50]"))
        assertTrue(text.contains("- Kalorien:: 560"))
    }

    @Test
    fun `eine Aenderung aus der App wird geschrieben`() {
        val edits = MealMerge.Edits(grams = mapOf((259 to "Reis") to 200.0))

        val text = build(notiz, edits = edits).content!!

        assertTrue(text.contains("[Reis:: 200]"))
        assertTrue(text.contains("- Kalorien:: 1010"))
    }

    /** Eine unlesbare Zeile wird nicht ueberschrieben, und die Summe ohne sie nicht geschrieben. */
    @Test
    fun `eine unlesbare Zeile laesst Callout und Naehrwerte stehen`() {
        val broken = build(notiz).content!!.replace("[Reis:: 100]", "[Reis:: viel]")

        val result = build(broken)

        assertTrue(result.content!!.contains("[Reis:: viel]"))
        assertTrue(result.content!!.contains("- Kalorien:: 710"))
        assertNull(result.report.calories)
        assertTrue(result.notes.any { it.contains("viel") })
    }

    /** Beim ersten Mal ohne Tandoor gaebe es nur Fruehstueck, und das waere zu niedrig. */
    @Test
    fun `ohne Tandoor beim ersten Mal wird kein Essen geschrieben`() {
        val result = build(notiz, tandoor = TandoorSource.Outcome.Unavailable("Token abgelehnt."))

        assertTrue(result.content!!.lines().none { it.startsWith("> [!info]") })
        assertTrue(result.content!!.lines().any { it.matches(Regex("""- Kalorien::\s*""")) })
        assertNull(result.report.calories)
        assertTrue(result.content!!.contains("- Schritte:: 8000"))
    }

    @Test
    fun `ohne Notiz wird trotzdem gerechnet`() {
        val result = build(null)

        assertNull(result.content)
        assertEquals(710.0, result.report.calories!!, 1e-9)
    }

    @Test
    fun `fehlt der Daten-Abschnitt, wird das gemeldet`() {
        val result = build("# Irgendwas\n- Kalorien:: ")

        assertTrue("Essen" in result.patch!!.missing)
    }
}
