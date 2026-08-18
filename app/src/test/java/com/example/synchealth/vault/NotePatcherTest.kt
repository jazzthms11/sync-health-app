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
    )

    @Test
    fun `alle acht Schluessel werden ersetzt`() {
        val result = NotePatcher.patch(template, report)

        assertEquals(emptyList<String>(), result.missing)
        assertEquals(8, result.patched.size)
        assertTrue(result.content.contains("- Schritte:: 8342"))
        assertTrue(result.content.contains("- Kalorien:: 2145"))
        assertTrue(result.content.contains("- Schlaf:: 7.3"))
        assertTrue(result.content.contains("- Protein:: 118.4"))
        assertTrue(result.content.contains("- Ballaststoffe:: 28.9"))
        assertTrue(result.content.contains("- Fett:: 71.1"))
        assertTrue(result.content.contains("- Zucker:: 44.6"))
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
        assertTrue(result.content.contains("- Screen Time:: \n"))
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
        assertEquals(7, result.patched.size)
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
    fun `eine Null laesst die Zeile unangetastet`() {
        val result = NotePatcher.patch(template, HealthReport())

        assertEquals(template, result.content)
        assertTrue(result.unchanged)
        assertEquals(8, result.skipped.size)
        assertEquals(emptyList<String>(), result.missing)
    }

    @Test
    fun `eine Null ueberschreibt keinen bestehenden Wert`() {
        val getippt = template.replace("- Kalorien:: ", "- Kalorien:: 2100")
        val result = NotePatcher.patch(getippt, report.copy(calories = 0.0))

        assertTrue(
            "Der von Hand eingetragene Wert muss stehen bleiben",
            result.content.contains("- Kalorien:: 2100"),
        )
        assertTrue(result.skipped.contains("Kalorien"))
    }

    @Test
    fun `ein auf Null gerundeter Wert wird nicht geschrieben`() {
        val result = NotePatcher.patch(template, HealthReport(protein = 0.04, calories = 0.6))

        assertTrue(result.skipped.contains("Protein"))
        assertTrue(result.skipped.contains("Kalorien"))
        assertTrue(result.content.contains("- Protein:: \n"))
        assertTrue(result.content.contains("- Kalorien:: \n"))
    }
}
