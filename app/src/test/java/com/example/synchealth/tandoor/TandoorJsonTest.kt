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
     * Gebaut nach der Bowl, wie Tandoor sie am 24.10.2026 geliefert hat: Mengen in g und
     * in Stueck mit gepflegter Umrechnung, ein Loeffelmass ohne Umrechnung, ein Gewuerz
     * ohne Menge und ein Lebensmittel, dem Naehrwerte fehlen.
     *
     * `food_properties` fuehrt die `fdc_id` nicht mit, nur Tandoors eigene Typ-ID, und
     * die steht doppelt: als Schluessel und als `id` in der Zeile. Gelesen wird die `id`.
     */
    private val rezept = """
        {
          "id": 13,
          "name": "Bowl",
          "servings": 4,
          "steps": [
            {
              "ingredients": [
                { "is_header": true, "food": null, "amount": 0 },
                { "food": { "id": 349, "name": "Vollkornreis" }, "unit": { "name": "g", "base_unit": "g" },
                  "amount": 400.0, "no_amount": false,
                  "conversions": [ { "food": "Vollkornreis", "unit": "g", "amount": 400.0 },
                                   { "food": "Vollkornreis", "unit": "kg", "amount": 0.4 } ] },
                { "food": { "id": 265, "name": "Tomate" }, "unit": { "name": "Stück", "base_unit": null },
                  "amount": 4.0, "no_amount": false,
                  "conversions": [ { "food": "Tomate", "unit": "Stück", "amount": 4.0 },
                                   { "food": "Tomate", "unit": "g", "amount": "400.000" } ] },
                { "food": { "id": 77, "name": "Senf" }, "unit": { "name": "EL", "base_unit": "tbsp" },
                  "amount": 2.0, "no_amount": false,
                  "conversions": [ { "food": "Senf", "unit": "EL", "amount": 2.0 } ] },
                { "food": { "id": 5, "name": "Salz" }, "unit": { "name": "g", "base_unit": "g" },
                  "amount": 0.0, "no_amount": false, "conversions": [] }
              ]
            },
            {
              "ingredients": [
                { "food": { "id": 265, "name": "Tomate" }, "unit": { "name": "g", "base_unit": "g" },
                  "amount": 100.0, "no_amount": false,
                  "conversions": [ { "food": "Tomate", "unit": "g", "amount": 100.0 } ] },
                { "food": { "id": 88, "name": "Parmesan" }, "unit": { "name": "g", "base_unit": "g" },
                  "amount": 30.0, "no_amount": false,
                  "conversions": [ { "food": "Parmesan", "unit": "g", "amount": 30.0 } ] }
              ]
            }
          ],
          "food_properties": {
            "1": { "id": 1, "name": "Kalorien", "total_value": 1524.0, "missing_value": true,
                   "food_values": {
                     "349": { "id": 349, "food": { "id": 349, "name": "Vollkornreis" }, "value": 1424.0 },
                     "265": { "id": 265, "food": { "id": 265, "name": "Tomate" }, "value": 100.0 },
                     "77":  { "id": 77,  "food": { "id": 77,  "name": "Senf" }, "value": null },
                     "88":  { "id": 88,  "food": { "id": 88,  "name": "Parmesan" }, "value": null }
                   } },
            "2": { "id": 2, "food_values": { "349": { "id": 349, "value": 35.6 }, "265": { "id": 265, "value": 4.5 } } },
            "3": { "id": 3, "food_values": { "349": { "id": 349, "value": 4.4 },  "265": { "id": 265, "value": 1.0 } } },
            "7": { "id": 7, "food_values": { "349": { "id": 349, "value": 14.0 }, "265": { "id": 265, "value": 6.0 } } },
            "6": { "id": 6, "food_values": { "349": { "id": 349, "value": 2.8 },  "265": { "id": 265, "value": 13.0 } } },
            "9": { "id": 9, "food_values": { "349": { "id": 349, "value": 99.0 } } }
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

    private val fdcByType = mapOf(1 to 1008, 2 to 1003, 3 to 1004, 7 to 1079, 6 to 2000)

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
    fun `Zutaten kommen je Lebensmittel mit Gramm und Beitrag`() {
        val parsed = TandoorJson.recipe(rezept, fdcByType)

        assertEquals(4.0, parsed.servings, 1e-9)
        assertEquals(listOf("Vollkornreis", "Tomate", "Senf", "Parmesan"), parsed.foods.map { it.name })
        val reis = parsed.foods.first()
        assertEquals(400.0, reis.amount!!, 1e-9)
        assertEquals(MealNutrition(1424.0, 35.6, 4.4, 14.0, 2.8), reis.nutrition)
    }

    /** Stueck zaehlt ueber die gepflegte Umrechnung, und zwei Zeilen werden addiert. */
    @Test
    fun `ein Lebensmittel in zwei Zeilen wird zusammengefasst`() {
        val tomate = TandoorJson.recipe(rezept, fdcByType).foods.single { it.name == "Tomate" }

        assertEquals(500.0, tomate.amount!!, 1e-9)
        assertEquals(100.0, tomate.nutrition!!.calories, 1e-9)
    }

    /**
     * Ohne Umrechnung in Gramm bleibt die Einheit aus dem Rezept. So stehen Fladenbrot und
     * Bolognese-Sosse in Tandoor, mit Naehrwerten je Stueck.
     */
    @Test
    fun `ohne Umrechnung in Gramm gilt die Einheit des Rezepts`() {
        val senf = TandoorJson.recipe(rezept, fdcByType).foods.single { it.name == "Senf" }

        assertEquals(2.0, senf.amount!!, 1e-9)
        assertEquals("EL", senf.unit)
    }

    @Test
    fun `gemischte Einheiten ohne Gramm lassen die Menge offen`() {
        val gemischt = """
            {
              "id": 1, "name": "Gemischt", "servings": 1,
              "steps": [ { "ingredients": [
                { "food": { "id": 1, "name": "Brot" }, "unit": { "name": "Stück" }, "amount": 1, "conversions": [] },
                { "food": { "id": 1, "name": "Brot" }, "unit": { "name": "Scheiben" }, "amount": 2, "conversions": [] }
              ] } ],
              "food_properties": {}
            }
        """.trimIndent()

        assertNull(TandoorJson.recipe(gemischt, fdcByType).foods.single().amount)
    }

    @Test
    fun `fehlt ein Wert, gibt es keine Naehrwerte fuer das Lebensmittel`() {
        val parmesan = TandoorJson.recipe(rezept, fdcByType).foods.single { it.name == "Parmesan" }

        assertEquals(30.0, parmesan.amount!!, 1e-9)
        assertNull(parmesan.nutrition)
    }

    @Test
    fun `ein Gewuerz ohne Menge faellt heraus`() {
        assertTrue(TandoorJson.recipe(rezept, fdcByType).foods.none { it.name == "Salz" })
    }

    /**
     * Ein selbst angelegter Typ ohne `fdc_id` laesst sich keinem der fuenf Naehrwerte
     * zuordnen und darf keinen davon ueberschreiben.
     */
    @Test
    fun `ein Typ ohne fdc_id wird ignoriert`() {
        val reis = TandoorJson.recipe(rezept, fdcByType).foods.first()

        assertEquals(1424.0, reis.nutrition!!.calories, 1e-9)
    }

    /** Eine aeltere Tandoor-Version ohne `conversions`: Eine Zeile in g zaehlt trotzdem. */
    @Test
    fun `ohne conversions zaehlt eine Zeile in g direkt`() {
        val alt = """
            {
              "id": 1, "name": "Alt", "servings": 1,
              "steps": [ { "ingredients": [
                { "food": { "id": 1, "name": "Reis" }, "unit": { "name": "g", "base_unit": "g" }, "amount": 80 }
              ] } ],
              "food_properties": {}
            }
        """.trimIndent()

        val reis = TandoorJson.recipe(alt, fdcByType).foods.single()

        assertEquals(80.0, reis.amount!!, 1e-9)
        assertNull(reis.nutrition)
    }

    @Test
    fun `die Typtabelle wird zu Typ-ID auf fdc_id`() {
        assertEquals(mapOf(1 to 1008, 7 to 1079), TandoorJson.propertyTypes(naehrwerttypen))
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

    /** So steht "Pilzgulasch mit Kartoffelknödeln" in Tandoor, gekuerzt auf eine Zeile. */
    @Test
    fun `ein Lebensmittel mit Rezept und ein Schritt-Rezept werden erkannt`() {
        val verlinkt = """
            {
              "id": 93, "name": "Pilzgulasch mit Kartoffelknödeln", "servings": 3, "servings_text": "Personen",
              "steps": [
                { "step_recipe": null, "ingredients": [
                  { "food": { "id": 633, "name": "Kartoffelknödel",
                              "recipe": { "id": 92, "name": "Kartoffelknödel", "url": "recipe/92" } },
                    "unit": { "name": "Stück" }, "amount": 1.0,
                    "conversions": [ { "unit": "Stück", "amount": 1.0 } ] }
                ] },
                { "step_recipe": 91, "ingredients": [] }
              ],
              "food_properties": {}
            }
        """.trimIndent()

        val parsed = TandoorJson.recipe(verlinkt, fdcByType)

        assertEquals("Personen", parsed.servingsText)
        assertEquals(listOf(91), parsed.stepRecipes)
        val knoedel = parsed.foods.single()
        assertEquals(92, knoedel.recipeId)
        assertEquals("Stück", knoedel.unit)
        assertNull(rezeptOhneVerweis().foods.first().recipeId)
    }

    private fun rezeptOhneVerweis() = TandoorJson.recipe(rezept, fdcByType)
}
