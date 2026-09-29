package com.example.synchealth.tandoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft den Parser gegen Antworten in der Form, in der Tandoor sie am 29.09.2026
 * geliefert hat, und gegen die beiden Stellen, an denen die Form nicht festgelegt ist:
 * eine Liste mit und ohne `results`, ein Datum mit und ohne Uhrzeit.
 */
class TandoorJsonTest {

    private val planEintrag = """
        [
          {
            "id": 42,
            "title": "",
            "recipe": { "id": 7, "name": "Bolognese", "servings": 4 },
            "servings": 2.0,
            "note": "@leo @jazz\nStatt Sahne: Hafercuisine",
            "from_date": "2026-09-28",
            "to_date": "2026-09-29",
            "meal_type": { "id": 2, "name": "Abendessen" }
          }
        ]
    """.trimIndent()

    /**
     * `food_properties` fuehrt die `fdc_id` nicht mit, nur Tandoors eigene Typ-ID, und
     * die steht doppelt: als Schluessel und als `id` in der Zeile. Gelesen wird die `id`.
     */
    private val rezept = """
        {
          "id": 7,
          "name": "Bolognese",
          "servings": 4,
          "food_properties": {
            "1": {
              "id": 1,
              "name": "Kalorien",
              "unit": "kcal",
              "order": 0,
              "food_values": { "12": { "id": 12, "food": { "id": 12, "name": "Hack" }, "value": 900.0 } },
              "total_value": 2716.4,
              "missing_value": false
            },
            "7": {
              "id": 7,
              "name": "Ballaststoffe",
              "unit": "g",
              "order": 0,
              "food_values": {},
              "total_value": 38.2,
              "missing_value": true
            }
          }
        }
    """.trimIndent()

    private val naehrwerttypen = """
        {
          "count": 3,
          "next": null,
          "previous": null,
          "results": [
            { "id": 1, "name": "Kalorien", "unit": "kcal", "open_data_slug": "property-calories", "fdc_id": 1008 },
            { "id": 7, "name": "Ballaststoffe", "unit": "g", "open_data_slug": null, "fdc_id": 1079 },
            { "id": 9, "name": "Eigene Kennzahl", "unit": "g", "open_data_slug": null, "fdc_id": null }
          ]
        }
    """.trimIndent()

    private val fdcByType = mapOf(1 to 1008, 7 to 1079)

    @Test
    fun `ein Planeintrag kommt vollstaendig durch`() {
        val entry = TandoorJson.mealPlan(planEintrag).single()

        assertEquals(42, entry.id)
        assertEquals(7, entry.recipeId)
        assertEquals("Bolognese", entry.recipeName)
        assertEquals(2.0, entry.servings, 1e-9)
        assertEquals(LocalDate.of(2026, 9, 28), entry.from)
        assertEquals(LocalDate.of(2026, 9, 29), entry.to)
        assertTrue(entry.note.startsWith("@leo @jazz"))
    }

    @Test
    fun `ein Eintrag ohne Rezept traegt keine Rezept-ID`() {
        val json = """[{ "id": 43, "recipe": null, "note": "", "from_date": "2026-09-28" }]"""

        val entry = TandoorJson.mealPlan(json).single()

        assertNull(entry.recipeId)
        assertEquals("", entry.recipeName)
    }

    @Test
    fun `ohne to_date gilt der Eintrag fuer einen Tag`() {
        val json = """[{ "id": 44, "recipe": null, "from_date": "2026-09-28" }]"""

        val entry = TandoorJson.mealPlan(json).single()

        assertEquals(entry.from, entry.to)
        assertEquals(1.0, entry.servings, 1e-9)
    }

    /** Django liefert ein `DateTimeField` mit Uhrzeit und Zone, ein `DateField` ohne. */
    @Test
    fun `ein Datum mit Uhrzeit wird auf den Tag gekuerzt`() {
        val json = """[{ "id": 45, "recipe": null, "from_date": "2026-09-28T18:00:00+02:00" }]"""

        assertEquals(LocalDate.of(2026, 9, 28), TandoorJson.mealPlan(json).single().from)
    }

    /** Blaettert der Endpunkt, steckt die Liste in `results` statt roh dazustehen. */
    @Test
    fun `eine geblaetterte Liste wird genauso gelesen`() {
        val json = """{ "count": 1, "next": null, "results": $planEintrag }"""

        assertEquals(42, TandoorJson.mealPlan(json).single().id)
    }

    @Test
    fun `die Naehrwerte kommen mit fdc_id und Vollstaendigkeitsflagge`() {
        val parsed = TandoorJson.recipe(rezept, fdcByType)

        assertEquals(4.0, parsed.servings, 1e-9)
        val kalorien = parsed.properties.single { it.fdcId == MealNutrition.FDC_CALORIES }
        assertEquals(2716.4, kalorien.totalValue, 1e-9)
        assertEquals(false, kalorien.missing)
        assertEquals(true, parsed.properties.single { it.fdcId == 1079 }.missing)
    }

    @Test
    fun `die Typtabelle wird zu Typ-ID auf fdc_id`() {
        assertEquals(mapOf(1 to 1008, 7 to 1079), TandoorJson.propertyTypes(naehrwerttypen))
    }

    /**
     * Ein selbst angelegter Typ ohne `fdc_id` laesst sich keinem der fuenf Naehrwerte
     * zuordnen. Er faellt aus der Tabelle, und ein Wert dieses Typs bekommt dann keine
     * Nummer: [MealPlanMath] laesst ihn liegen, statt ihn zu verwechseln.
     */
    @Test
    fun `ein Typ ohne fdc_id bleibt ohne Nummer`() {
        val eigener = """
            {
              "id": 7,
              "name": "Bolognese",
              "servings": 4,
              "food_properties": {
                "9": { "id": 9, "name": "Eigene Kennzahl", "total_value": 1.0, "missing_value": false }
              }
            }
        """.trimIndent()

        val parsed = TandoorJson.recipe(eigener, TandoorJson.propertyTypes(naehrwerttypen))

        assertNull(parsed.properties.single().fdcId)
    }

    /**
     * Die Typ-ID steht in der Zeile selbst, nicht nur im Schluessel. Deshalb traegt die
     * Array-Form ohne Zutun, und eine andere Tandoor-Version kostet hier nichts.
     */
    @Test
    fun `food_properties als Liste wird genauso gelesen`() {
        val alsListe = """
            {
              "id": 7,
              "name": "Bolognese",
              "servings": 4,
              "food_properties": [
                { "id": 1, "name": "Kalorien", "total_value": 2716.4, "missing_value": false },
                { "id": 7, "name": "Ballaststoffe", "total_value": 38.2, "missing_value": true }
              ]
            }
        """.trimIndent()

        val parsed = TandoorJson.recipe(alsListe, fdcByType)

        assertEquals(2, parsed.properties.size)
        assertEquals(2716.4, parsed.properties.first { it.fdcId == 1008 }.totalValue, 1e-9)
    }

    @Test
    fun `ein Rezept ohne Portionszahl wird gemeldet statt geraten`() {
        val ohne = """{ "id": 7, "name": "Bolognese", "food_properties": {} }"""

        assertThrows(TandoorJson.MalformedException::class.java) {
            TandoorJson.recipe(ohne, fdcByType)
        }
    }

    @Test
    fun `eine Antwort die keine Liste ist wird gemeldet`() {
        assertThrows(TandoorJson.MalformedException::class.java) {
            TandoorJson.mealPlan("""{ "detail": "Not found." }""")
        }
    }

    @Test
    fun `kaputtes JSON wird gemeldet statt zu werfen`() {
        assertThrows(TandoorJson.MalformedException::class.java) {
            TandoorJson.mealPlan("<html>Login</html>")
        }
    }
}
