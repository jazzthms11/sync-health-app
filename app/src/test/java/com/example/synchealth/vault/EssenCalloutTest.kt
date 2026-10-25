package com.example.synchealth.vault

import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.tandoor.MealNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft den Essen-Callout gegen die Tagesnotiz, wie sie am 24.10.2026 im Vault aussah:
 * Frontmatter mit Habits, dann Bildung, Kontext, Notizen und `## Daten` mit H6-Abschnitten.
 */
class EssenCalloutTest {

    private val notiz = """
        ---
        cssclasses:
          - full-width
        Klavier: true
        Sport: false
        ---

        ‹ [[2026-10-23]] · [[Oct '26]] · [[2026-10-25]] ›

        ## Bildung

        - Gelesen::

        ## Kontext

        - Fokus::
        - Projekte::
        - Themen::

        ## Notizen

        - Etwas Text.

        ## Daten
        ###### Gesundheit
        - Schlaf::
        - Stimmung::
        - Hautverlauf::

        ###### Essen
        - Kalorien::
        - Protein::
        - Ballaststoffe::
        - Fett::
        - Zucker::
        - Wasser::
    """.trimIndent()

    private val tag = LocalDate.of(2026, 10, 24)

    private val reisJeGramm = MealNutrition(3.56, 0.0889, 0.0111, 0.035, 0.007)

    private val bowl = PlannedMeal(
        entryId = 259,
        recipeId = 13,
        recipeName = "Bowl",
        slot = Slot.MITTAG,
        ingredients = listOf(
            Ingredient("Vollkornreis", 100.0, 100.0, reisJeGramm),
            Ingredient("Zucker", 3.5, 3.5, MealNutrition(4.0, 0.0, 0.0, 0.0, 1.0)),
            Ingredient("Parmesan", 30.0, 30.0, null),
            Ingredient("Senf", null, null, null),
            Ingredient("Fladenbrot", 0.5, 0.5, MealNutrition(509.0, 22.05, 8.32, 11.97, 1.0), "Stück"),
        ),
    )

    private val samsung = listOf(
        SamsungMeal("Haferflocken", Slot.FRUEHSTUECK, 380.0),
        SamsungMeal("Apfel", Slot.MITTAG, 80.0),
    )

    private fun render(meals: List<PlannedMeal> = listOf(bowl), content: String = notiz) =
        EssenCallout.render(tag, meals, samsung, EssenCallout.reservedKeys(content))

    private fun placed(meals: List<PlannedMeal> = listOf(bowl)) =
        EssenCallout.place(notiz, render(meals))!!

    @Test
    fun `Essen steht als eigene Ueberschrift zwischen Notizen und Daten`() {
        val lines = placed().split("\n")

        val essen = lines.indexOf("## Essen")
        val daten = lines.indexOf("## Daten")
        assertTrue(essen > lines.indexOf("## Notizen"))
        assertTrue(essen < daten)
        assertEquals("", lines[essen - 1])
        assertEquals("", lines[daten - 1])
    }

    @Test
    fun `Mahlzeiten tragen ihre kcal`() {
        val text = render()

        assertTrue(text.startsWith("## Essen\n\n> [!mahlzeit]- Frühstück · 380 kcal\n> %% synchealth erster-sync=2026-10-24 %%\n"))
        assertTrue(text.contains("> [!mahlzeit]- Frühstück · 380 kcal"))
        // 100 g Reis zu 3.56, 3.5 g Zucker zu 4 und ein halbes Fladenbrot zu 509, dazu der
        // Apfel aus Samsung Health.
        assertTrue(text.contains("> [!mahlzeit]- Mittag · 705 kcal"))
        assertTrue(text.contains("> **Bowl** · 625 kcal"))
    }

    @Test
    fun `Zutaten sind Felder in Klammerform ohne Einheit`() {
        assertTrue(render().contains("> - [Vollkornreis:: 100]\n"))
    }

    /**
     * Ohne Vorsatz stuende `Zucker` zweimal in der Notiz, Dataview machte eine Liste
     * daraus, und der Messwert fiele still aus dem Monatsschnitt.
     */
    @Test
    fun `eine Zutat mit dem Namen eines Messwerts bekommt den Vorsatz`() {
        assertTrue(render().contains("> - [Zutat Zucker:: 3.5]"))
    }

    @Test
    fun `auch Frontmatter-Schluessel und Gesundheitsfelder sind belegt`() {
        val reserved = EssenCallout.reservedKeys(notiz)

        assertTrue("klavier" in reserved)
        assertTrue("hautverlauf" in reserved)
        assertTrue("gelesen" in reserved)
        assertTrue("vollkornreis" !in reserved)
    }

    @Test
    fun `Zutaten ohne Naehrwerte oder Gramm werden markiert`() {
        val text = render()

        assertTrue(text.contains("> - [Parmesan:: 30] ⚠ ohne Nährwerte"))
        assertTrue(text.contains("> - Senf ⚠ ohne Mengenangabe"))
    }

    @Test
    fun `eine Zutat ohne Gramm steht mit ihrer Einheit da`() {
        val text = render()

        assertTrue(text.contains("> - [Fladenbrot:: 0.5] Stück\n"))
        assertTrue(text.contains("> Fladenbrot | 0.5 | 509 22.05 8.32 11.97 1 | Stück"))
    }

    @Test
    fun `Samsung-Eintraege stehen unter ihrer Mahlzeit`() {
        val text = render()

        assertTrue(text.contains("> - Haferflocken · 380 kcal"))
        assertTrue(text.contains("> **Samsung Health**\n> - Apfel · 80 kcal"))
    }

    @Test
    fun `was geschrieben wurde, wird genauso zurueckgelesen`() {
        val parsed = EssenCallout.parse(placed())!!

        assertEquals(emptyList<String>(), parsed.issues)
        assertEquals(tag, parsed.existing.firstSync)
        assertEquals(listOf(bowl), parsed.existing.meals)
    }

    @Test
    fun `eine von Hand geaenderte Menge wird uebernommen`() {
        val edited = placed().replace("[Vollkornreis:: 100]", "[Vollkornreis:: 60]")

        val reis = EssenCallout.parse(edited)!!.existing.meals.single().ingredients.first()

        assertEquals(60.0, reis.amount!!, 1e-9)
        assertEquals(100.0, reis.planned!!, 1e-9)
        assertTrue(reis.adjusted)
    }

    @Test
    fun `ein Komma beim Tippen wird als Punkt gelesen`() {
        val edited = placed().replace("[Vollkornreis:: 100]", "[Vollkornreis:: 62,5]")

        val reis = EssenCallout.parse(edited)!!.existing.meals.single().ingredients.first()

        assertEquals(62.5, reis.amount!!, 1e-9)
    }

    @Test
    fun `eine geloeschte Zeile heisst nicht gegessen`() {
        val edited = placed().replace("> - [Vollkornreis:: 100]\n", "")

        val reis = EssenCallout.parse(edited)!!.existing.meals.single().ingredients.first()

        assertEquals(0.0, reis.amount!!, 1e-9)
    }

    @Test
    fun `eine unlesbare Menge wird gemeldet statt geraten`() {
        val edited = placed().replace("[Vollkornreis:: 100]", "[Vollkornreis:: etwas weniger]")

        val parsed = EssenCallout.parse(edited)!!

        assertTrue(parsed.issues.single().contains("etwas weniger"))
    }

    @Test
    fun `eine unbekannte Zutat wird gemeldet`() {
        val edited = placed().replace("> - [Vollkornreis:: 100]", "> - [Vollkornreis:: 100]\n> - [Kaese:: 20]")

        assertTrue(EssenCallout.parse(edited)!!.issues.single().contains("Kaese"))
    }

    @Test
    fun `ein zweiter Lauf ersetzt den Callout statt einen zweiten anzulegen`() {
        val once = placed()
        val twice = EssenCallout.place(once, render(content = once))!!

        assertEquals(once, twice)
        assertEquals(1, twice.split("\n").count { it == "## Essen" })
    }

    /**
     * Beim zweiten Lauf steht `Zucker` als Zutat schon im Callout. Der zaehlt fuer die
     * Belegung nicht mit, sonst wuerde der Vorsatz von Lauf zu Lauf wechseln.
     */
    @Test
    fun `die eigenen Zutatenfelder belegen keinen Namen`() {
        val reserved = EssenCallout.reservedKeys(placed())

        assertTrue("vollkornreis" !in reserved)
        assertTrue("zutat-zucker" !in reserved)
    }

    /** So stand es bis zum 25.10.2026 in der Notiz: ein Callout unter `## Notizen`. */
    private fun legacy(): String = placed().let { now ->
        val lines = now.split("\n")
        val range = EssenCallout.locate(now)!!
        val marker = "%% synchealth erster-sync=2026-10-24 %%"
        val old = listOf("> [!info]- Essen · 1000 kcal", "> $marker") +
            lines.subList(range.first + 1, range.last + 1)
                .filter { !it.contains(marker) }
                .map { if (it.isEmpty()) ">" else "> ${it.replace("[!mahlzeit]", "[!note]")}" }
        // Der alte Callout stand ohne Ueberschrift im Notizen-Abschnitt.
        (lines.subList(0, range.first - 1) + "" + old + lines.subList(range.last + 1, lines.size))
            .joinToString("\n")
    }

    @Test
    fun `der alte Callout wird gelesen und durch die Ueberschrift ersetzt`() {
        val old = legacy()
        assertTrue(old.contains("> > - [Vollkornreis:: 100]"))
        assertTrue("## Essen" !in old.split("\n"))

        val parsed = EssenCallout.parse(old)!!
        assertEquals(emptyList<String>(), parsed.issues)
        assertEquals(tag, parsed.existing.firstSync)
        assertEquals(listOf(bowl), parsed.existing.meals)

        assertEquals(placed(), EssenCallout.place(old, render(content = old)))
    }

    /** So stand es am 25.10.2026 kurz in der Notiz: der Kommentar frei unter der Ueberschrift. */
    @Test
    fun `ein freier erster-sync-Kommentar wandert in den ersten Callout`() {
        val marker = "%% synchealth erster-sync=2026-10-24 %%"
        val loose = placed().replace("\n> $marker", "").replace("\n## Essen\n", "\n## Essen\n$marker\n")
        assertTrue(loose.contains("## Essen\n$marker\n\n> [!mahlzeit]"))

        assertEquals(tag, EssenCallout.parse(loose)!!.existing.firstSync)
        assertEquals(placed(), EssenCallout.place(loose, render(content = loose)))
    }

    @Test
    fun `ohne Mahlzeiten steht der Kommentar unter der Ueberschrift`() {
        val text = EssenCallout.render(tag, emptyList(), emptyList(), emptySet())

        assertEquals("## Essen\n%% synchealth erster-sync=2026-10-24 %%", text)
        assertEquals(tag, EssenCallout.parse(EssenCallout.place(notiz, text)!!)!!.existing.firstSync)
    }

    @Test
    fun `eigener Text unter der Ueberschrift bleibt stehen`() {
        val withText = placed().replace("\n\n## Daten", "\n\nNachtisch ausgelassen.\n\n## Daten")

        val again = EssenCallout.place(withText, render(content = withText))!!

        assertEquals(withText, again)
    }

    @Test
    fun `ohne Daten-Abschnitt wird nichts geraten`() {
        assertNull(EssenCallout.place("# Nur Text\n", render()))
    }

    @Test
    fun `eine Notiz ohne Callout hat nichts zurueckzulesen`() {
        assertNull(EssenCallout.parse(notiz))
    }

    @Test
    fun `Namen, die das Format brechen, werden entschaerft`() {
        assertEquals("Tomaten passiert", EssenCallout.clean("Tomaten: [passiert]"))
        assertNotNull(EssenCallout.parse(placed(listOf(bowl.copy(recipeName = "Bowl | groß")))))
    }

    @Test
    fun `Gramm stehen mit Punkt und ohne ueberfluessige Null`() {
        assertEquals("100", EssenCallout.amount(100.0))
        assertEquals("12.5", EssenCallout.amount(12.46))
        assertEquals("0.25", EssenCallout.amount(0.25, "Stück"))
    }
}
