package com.example.synchealth.tandoor

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft den ganzen Weg vom Abruf bis zur Summe gegen einen Testserver: welche Rezepte
 * ueberhaupt geholt werden und was passiert, wenn Tandoor stumm bleibt.
 */
class TandoorSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: TandoorSource

    private val heute = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = TandoorSource(TandoorClient(server.url("/").toString(), "geheim"), "@leo", 2)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun plan(vararg entries: String) =
        MockResponse().setBody(entries.joinToString(",", "[", "]"))

    private fun entry(
        id: Int,
        recipeId: Int?,
        note: String,
        servings: Double = 1.0,
        mealType: String = "Mittagessen",
    ) = """
        {
          "id": $id,
          "recipe": ${if (recipeId == null) "null" else """{ "id": $recipeId, "name": "R$recipeId" }"""},
          "servings": $servings,
          "note": "$note",
          "meal_type": { "id": 1, "name": "$mealType" },
          "from_date": "2026-09-28"
        }
    """.trimIndent()

    /** Ein Rezept fuer zwei Portionen aus 200 g einer einzigen Zutat. */
    private fun recipe(id: Int, calories: Double) = MockResponse().setBody(
        """
        {
          "id": $id,
          "name": "R$id",
          "servings": 2,
          "steps": [ { "ingredients": [
            { "food": { "id": 1, "name": "Zutat$id" }, "unit": { "name": "g", "base_unit": "g" },
              "amount": 200, "conversions": [ { "unit": "g", "amount": 200 } ] }
          ] } ],
          "food_properties": {
            "1": { "id": 1, "food_values": { "1": { "id": 1, "value": $calories } } },
            "2": { "id": 2, "food_values": { "1": { "id": 1, "value": 60 } } },
            "3": { "id": 3, "food_values": { "1": { "id": 1, "value": 40 } } },
            "4": { "id": 4, "food_values": { "1": { "id": 1, "value": 20 } } },
            "5": { "id": 5, "food_values": { "1": { "id": 1, "value": 10 } } }
          }
        }
        """.trimIndent()
    )

    private fun calories(outcome: TandoorSource.Outcome.Ready) =
        outcome.meals.sumOf { it.nutrition.calories }

    /** Die Tabelle, ohne die sich `food_properties` nicht deuten laesst. */
    private fun typen() = MockResponse().setBody(
        """
        {
          "count": 5,
          "results": [
            { "id": 1, "name": "Kalorien", "fdc_id": 1008 },
            { "id": 2, "name": "Proteine", "fdc_id": 1003 },
            { "id": 3, "name": "Fett", "fdc_id": 1004 },
            { "id": 4, "name": "Ballaststoffe", "fdc_id": 1079 },
            { "id": 5, "name": "Zucker", "fdc_id": 2000 }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `Mittag und Abend kommen als zwei Gerichte`() {
        server.enqueue(plan(entry(1, 7, "@leo"), entry(2, 8, "@leo @jazz", servings = 2.0)))
        server.enqueue(typen())
        server.enqueue(recipe(7, 1000.0))
        server.enqueue(recipe(8, 1600.0))

        val outcome = source.read(heute) as TandoorSource.Outcome.Ready

        // 1000/2 fuer eine Portion, dazu 1600 * (2/1/2) / 2 fuer die eigene Haelfte.
        assertEquals(listOf(1, 2), outcome.meals.map { it.entryId })
        assertEquals(1300.0, calories(outcome), 1e-9)
        assertEquals(100.0, outcome.meals.first().ingredients.single().amount!!, 1e-9)
    }

    /** Ein Abendessen, das jemand anderes isst, darf keine Anfrage kosten. */
    @Test
    fun `fremde Eintraege holen kein Rezept`() {
        server.enqueue(plan(entry(1, 7, "@leo"), entry(2, 9, "@jazz")))
        server.enqueue(typen())
        server.enqueue(recipe(7, 1000.0))

        source.read(heute)

        assertEquals(3, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/api/recipe/7/", server.takeRequest().path)
    }

    @Test
    fun `ein zweimal geplantes Rezept wird einmal geholt`() {
        server.enqueue(plan(entry(1, 7, "@leo"), entry(2, 7, "@leo")))
        server.enqueue(typen())
        server.enqueue(recipe(7, 1000.0))

        val outcome = source.read(heute) as TandoorSource.Outcome.Ready

        assertEquals(3, server.requestCount)
        assertEquals(1000.0, calories(outcome), 1e-9)
    }

    @Test
    fun `ein Platz ohne Rezept kostet keine Anfrage und keine Meldung`() {
        server.enqueue(plan(entry(1, null, "")))

        val outcome = source.read(heute) as TandoorSource.Outcome.Ready

        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Any>(), outcome.meals)
    }

    /**
     * Der Fall, der frueher still die halbe Summe geschrieben haette: Tandoor antwortet
     * nicht, Samsung Health allein waere nur Fruehstueck und Snacks.
     */
    @Test
    fun `ein abgelehnter Token gibt keine Summe heraus`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = source.read(heute) as TandoorSource.Outcome.Unavailable

        assertTrue(outcome.reason.contains("Token abgelehnt"))
    }

    @Test
    fun `ein Rezept das nicht kommt gibt keine Summe heraus`() {
        server.enqueue(plan(entry(1, 7, "@leo")))
        server.enqueue(typen())
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = source.read(heute) as TandoorSource.Outcome.Unavailable

        assertTrue(outcome.reason.contains("Rezept 7"))
    }

    /**
     * Ohne die Typtabelle traegt kein einziger Wert eine Nummer. Die Summe waere dann
     * null und saehe aus wie ein Tag ohne Plan, deshalb wird sie nicht herausgegeben.
     */
    @Test
    fun `eine ausbleibende Typtabelle gibt keine Summe heraus`() {
        server.enqueue(plan(entry(1, 7, "@leo")))
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = source.read(heute) as TandoorSource.Outcome.Unavailable

        assertTrue(outcome.reason.contains("Naehrwerttypen"))
    }

    /**
     * Ein Abendessen im Plan traegt keine Marke, es wird zusammen gegessen. Es muss
     * trotzdem sein Rezept holen, sonst fehlte die halbe Mahlzeit.
     */
    @Test
    fun `ein Abendessen ohne Marke holt sein Rezept`() {
        server.enqueue(plan(entry(1, 7, "mit Ei", servings = 4.0, mealType = "Abendessen")))
        server.enqueue(typen())
        server.enqueue(recipe(7, 1000.0))

        val outcome = source.read(heute) as TandoorSource.Outcome.Ready

        // 4 Portionen / 1 Tag / 2 Esser = 2, davon 1000 kcal / 2 Rezeptportionen = 1000.
        assertEquals(1000.0, calories(outcome), 1e-9)
    }

    @Test
    fun `ein Mittagessen ohne Marke gibt keine Summe heraus`() {
        server.enqueue(plan(entry(1, 7, "ohne alles")))

        val outcome = source.read(heute) as TandoorSource.Outcome.Unavailable

        assertTrue(outcome.reason.contains("traegt keine Marke"))
    }

    /**
     * Nur nach vorne: Tandoor filtert allein auf `to_date`, ein Eintrag, der heute
     * abdeckt, endet also nie vor heute.
     */
    @Test
    fun `das Fenster fragt ab heute und drei Tage voraus`() {
        server.enqueue(plan())

        source.read(heute)

        assertEquals(
            "/api/meal-plan/?from_date=2026-09-28&to_date=2026-10-01",
            server.takeRequest().path,
        )
    }

    /**
     * Wie das Pilzgulasch: Das geplante Rezept besteht nur aus einer Zeile, die auf ein
     * anderes Rezept zeigt. Dessen Zutaten muessen im Gericht stehen.
     */
    @Test
    fun `ein verlinktes Rezept wird nachgeladen und aufgeklappt`() {
        server.enqueue(plan(entry(1, 7, "@leo")))
        server.enqueue(typen())
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": 7, "name": "R7", "servings": 2,
                  "steps": [ { "ingredients": [
                    { "food": { "id": 50, "name": "Knödel", "recipe": { "id": 8 } },
                      "unit": { "name": "Stück" }, "amount": 1, "conversions": [] }
                  ] } ],
                  "food_properties": {}
                }
                """.trimIndent()
            )
        )
        server.enqueue(recipe(8, 1000.0))

        val outcome = source.read(heute) as TandoorSource.Outcome.Ready

        assertEquals("/api/recipe/8/", server.run { takeRequest(); takeRequest(); takeRequest(); takeRequest().path })
        val meal = outcome.meals.single()
        assertEquals("R7", meal.recipeName)
        // Ein ganzes R8 (200 g, 1000 kcal) steckt in R7 fuer zwei Portionen, eine davon ist meine.
        assertEquals(100.0, meal.ingredients.single().amount!!, 1e-9)
        assertEquals("Zutat8", meal.ingredients.single().name)
        assertEquals(500.0, calories(outcome), 1e-9)
    }
}
