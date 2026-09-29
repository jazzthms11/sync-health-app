package com.example.synchealth.tandoor

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Prueft den Transport: Pfade, Token im Header und die Ausgaenge, die Tandoor liefert. */
class TandoorClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TandoorClient

    private val heute = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TandoorClient(baseUrl = server.url("/").toString(), token = "geheim")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `der Wochenplan wird mit Fenster und Bearer-Token geholt`() {
        server.enqueue(MockResponse().setBody("[]"))

        client.mealPlan(heute, heute.plusDays(3))

        val request = server.takeRequest()
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals(
            "/api/meal-plan/?from_date=2026-09-28&to_date=2026-10-01",
            request.path,
        )
    }

    @Test
    fun `die Typtabelle wird von ihrem eigenen Pfad geholt`() {
        server.enqueue(
            MockResponse().setBody(
                """{ "count": 1, "results": [ { "id": 1, "name": "Kalorien", "fdc_id": 1008 } ] }"""
            )
        )

        val result = client.propertyTypes() as TandoorClient.Result.Found

        assertEquals("/api/property-type/", server.takeRequest().path)
        assertEquals(mapOf(1 to 1008), result.value)
    }

    /** Ohne den abschliessenden Schraegstrich antwortet Django mit einer Umleitung. */
    @Test
    fun `der Rezeptpfad endet auf einem Schraegstrich`() {
        server.enqueue(MockResponse().setBody("""{ "id": 7, "name": "X", "servings": 4 }"""))

        client.recipe(7, mapOf(1 to 1008))

        assertEquals("/api/recipe/7/", server.takeRequest().path)
    }

    @Test
    fun `eine Basis-URL mit Unterpfad bleibt erhalten`() {
        val unterpfad = TandoorClient(server.url("/tandoor").toString(), "geheim")
        server.enqueue(MockResponse().setBody("[]"))

        unterpfad.mealPlan(heute, heute)

        assertTrue(server.takeRequest().path!!.startsWith("/tandoor/api/meal-plan/"))
    }

    @Test
    fun `401 heisst der Token gilt nicht`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(TandoorClient.Result.Unauthorized, client.mealPlan(heute, heute))
    }

    /** Der Scope wird durchgesetzt: ein Token ohne Leserecht gilt und darf trotzdem nicht. */
    @Test
    fun `403 wird vom abgelehnten Token unterschieden`() {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(TandoorClient.Result.Forbidden, client.mealPlan(heute, heute))
    }

    @Test
    fun `eine Antwort die kein JSON ist wird als unlesbar gemeldet`() {
        server.enqueue(MockResponse().setBody("<html>Cloudflare Access</html>"))

        val result = client.mealPlan(heute, heute) as TandoorClient.Result.Failed

        assertEquals(TandoorClient.MALFORMED, result.code)
    }

    @Test
    fun `ein Serverfehler kommt mit seinem Status durch`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.recipe(7, mapOf(1 to 1008)) as TandoorClient.Result.Failed

        assertEquals(500, result.code)
    }

    @Test
    fun `keine Antwort ist kein HTTP-Status`() {
        server.shutdown()

        val result = client.mealPlan(heute, heute) as TandoorClient.Result.Failed

        assertEquals(TandoorClient.NO_RESPONSE, result.code)
    }
}
