package com.example.synchealth.vault

import com.example.synchealth.HealthReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Prueft den Patcher gegen eine Kopie von "templates/Template Daily.md" aus dem Vault.
 * Aendert sich die Vorlage dort, muss die Kopie hier nachgezogen werden, und genau
 * dann soll dieser Test rot werden.
 */
class NotePatcherTest {

    private val template = """
        ---
        Stretching: false
        Essen tracken: false
        ---
        < [[2026-08-17]] · [[2026-W34]] · [[2026-08-19]] >

        # Daten
        ## Gesundheit
        - Gewicht:: 
        - Schlaf:: 
        - Schritte:: 
        - Screen Time:: 

        ## Essen
        - Kalorien:: 
        - Protein:: 
        - Ballaststoffe:: 
        - Fett:: 
        - Zucker:: 
        - Wasser:: 

        ## Bildung
        - Seiten gelesen:: 
        - Podcast gehört:: 

        # Kontext
        - Fokus:: 
        - Projekte:: 
        - Themen:: 

        # Notizen
    """.trimIndent()

    private val report = HealthReport(
        steps = 8342,
        calories = 2145.7,
        water = 1750.0,
        sleep = 7.25,
        protein = 118.4,
        fat = 71.06,
        fiber = 28.9,
        sugar = 44.55,
        screenTime = 3.5,
    )

    @Test
    fun `alle neun Schluessel werden ersetzt`() {
        val result = NotePatcher.patch(template, report)

        assertEquals(emptyList<String>(), result.missing)
        assertEquals(9, result.patched.size)
        assertTrue(result.content.contains("- Schritte:: 8342"))
        assertTrue(result.content.contains("- Kalorien:: 2145"))
        assertTrue(result.content.contains("- Schlaf:: 7.3"))
        assertTrue(result.content.contains("- Protein:: 118.4"))
        assertTrue(result.content.contains("- Ballaststoffe:: 28.9"))
        assertTrue(result.content.contains("- Fett:: 71.1"))
        assertTrue(result.content.contains("- Zucker:: 44.6"))
        assertTrue(result.content.contains("- Screen Time:: 3.5"))
    }

    @Test
    fun `Wasser wird von Milliliter in Liter umgerechnet`() {
        val result = NotePatcher.patch(template, report)
        assertTrue(result.content.contains("- Wasser:: 1.8"))
    }

    @Test
    fun `Zeilen ohne Samsung-Herkunft bleiben unangetastet`() {
        val result = NotePatcher.patch(template, report)

        assertTrue(result.content.contains("- Gewicht:: \n"))
        assertTrue(result.content.contains("- Seiten gelesen:: \n"))
        assertTrue(result.content.contains("- Fokus:: \n"))
    }

    @Test
    fun `ein Schluessel im Fliesstext wird nicht gepatcht`() {
        val mitText = template + "\nHeute kaum Schritte:: gemacht, war den ganzen Tag am Rechner.\n"
        val result = NotePatcher.patch(mitText, report)

        assertTrue(result.content.contains("- Schritte:: 8342"))
        assertTrue(
            "Der Fliesstext darf nicht angefasst werden",
            result.content.contains("Heute kaum Schritte:: gemacht, war den ganzen Tag am Rechner."),
        )
    }

    @Test
    fun `bestehende Werte werden ueberschrieben`() {
        val alt = template.replace("- Schritte:: ", "- Schritte:: 12")
        val result = NotePatcher.patch(alt, report)

        assertTrue(result.content.contains("- Schritte:: 8342"))
        assertFalse(result.content.contains("- Schritte:: 12"))
    }

    @Test
    fun `ein zweiter Durchlauf meldet nichts zu tun`() {
        val erster = NotePatcher.patch(template, report)
        val zweiter = NotePatcher.patch(erster.content, report)

        assertTrue(zweiter.unchanged)
        assertEquals(erster.content, zweiter.content)
    }

    @Test
    fun `fehlender Schluessel wird gemeldet statt still ignoriert`() {
        val ohneSchlaf = template.replace("- Schlaf:: \n", "")
        val result = NotePatcher.patch(ohneSchlaf, report)

        assertEquals(listOf("Schlaf"), result.missing)
        assertEquals(emptyList<String>(), result.skipped)
        assertEquals(8, result.patched.size)
    }

    @Test
    fun `Dezimaltrenner bleibt der Punkt auch bei deutscher Locale`() {
        val vorher = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val result = NotePatcher.patch(template, report)
            assertTrue(result.content.contains("- Protein:: 118.4"))
            assertFalse(result.content.contains("118,4"))
        } finally {
            Locale.setDefault(vorher)
        }
    }

    @Test
    fun `Windows-Zeilenenden bleiben erhalten`() {
        val crlf = template.replace("\n", "\r\n")
        val result = NotePatcher.patch(crlf, report)

        assertTrue(result.content.contains("- Schritte:: 8342\r\n"))
        assertFalse("Es darf kein nacktes LF entstehen", result.content.contains(Regex("[^\r]\n")))
    }

    @Test
    fun `ohne Wert bleibt die Zeile unangetastet`() {
        val result = NotePatcher.patch(template, HealthReport())

        assertEquals(template, result.content)
        assertTrue(result.unchanged)
        assertEquals(9, result.skipped.size)
        assertEquals(emptyList<String>(), result.missing)
    }

    @Test
    fun `ein fehlender Wert ueberschreibt keinen bestehenden`() {
        val getippt = template.replace("- Kalorien:: ", "- Kalorien:: 2100")
        val result = NotePatcher.patch(getippt, report.copy(calories = null))

        assertTrue(
            "Der von Hand eingetragene Wert muss stehen bleiben",
            result.content.contains("- Kalorien:: 2100"),
        )
        assertTrue(result.skipped.contains("Kalorien"))
    }

    /**
     * Frueher hat der Patcher jede Null verschluckt, weil eine Null aus Samsung Health
     * "noch keine Daten" hiess. Diese Regel sitzt seit Tandoor in der
     * [com.example.synchealth.health.HealthRepository] und gilt nur noch fuer diese eine
     * Quelle: Was hier ankommt, ist die Summe beider Quellen und wird geschrieben.
     */
    @Test
    fun `eine gemessene Null wird geschrieben`() {
        val result = NotePatcher.patch(template, HealthReport(sugar = 0.0))

        assertTrue(result.content.contains("- Zucker:: 0.0"))
        assertFalse(result.skipped.contains("Zucker"))
    }

    @Test
    fun `ein auf Null gerundeter Wert wird geschrieben`() {
        val result = NotePatcher.patch(template, HealthReport(protein = 0.04, calories = 0.6))

        assertTrue(result.content.contains("- Protein:: 0.0"))
        assertTrue(result.content.contains("- Kalorien:: 0"))
    }
}
