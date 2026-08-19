package com.example.synchealth.vault

import com.example.synchealth.HealthReport
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft den Ablauf gegen einen lokalen Testserver. Deckt genau die Faelle ab, die wir
 * am 18.08.2026 einmal von Hand mit curl gegen die echte Nextcloud durchgespielt haben.
 */
class DailyNoteSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var sync: DailyNoteSync

    private val date: LocalDate = LocalDate.of(2026, 8, 18)

    private val leereNotiz = """
        # Daten
        ## Gesundheit
        - Gewicht:: 
        - Schlaf:: 
        - Schritte:: 

        ## Essen
        - Kalorien:: 
        - Protein:: 
        - Ballaststoffe:: 
        - Fett:: 
        - Zucker:: 
        - Wasser:: 
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            baseUrl = server.url("/remote.php/dav/files/leo/second-brain/").toString(),
            username = "leo",
            password = "geheim",
        )
        sync = DailyNoteSync(client)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun notiz(body: String, etag: String) =
        MockResponse().setResponseCode(200).setHeader("ETag", etag).setBody(body)

    private fun status(code: Int) = MockResponse().setResponseCode(code)

    @Test
    fun `flach liegende Tagesnotiz wird gepatcht und zurueckgeschrieben`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        val outcome = sync.sync(date, report)

        assertTrue(outcome is DailyNoteSync.Outcome.Written)
        assertEquals(8, (outcome as DailyNoteSync.Outcome.Written).patched.size)

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals(
            "/remote.php/dav/files/leo/second-brain/05%20Daily%20Notes/2026-08-18.md",
            get.path,
        )

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(get.path, put.path)
        assertEquals("\"v1\"", put.getHeader("If-Match"))
        assertTrue(put.body.readUtf8().contains("- Schritte:: 8342"))
    }

    @Test
    fun `fehlt sie flach, wird im Monatsordner nachgesehen`() {
        server.enqueue(status(404))
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        val outcome = sync.sync(date, report)

        assertTrue(outcome is DailyNoteSync.Outcome.Written)
        server.takeRequest() // der flache Versuch
        val zweiter = server.takeRequest()
        assertEquals(
            "/remote.php/dav/files/leo/second-brain/05%20Daily%20Notes/2026-08/2026-08-18.md",
            zweiter.path,
        )
        assertEquals(zweiter.path, server.takeRequest().path)
    }

    @Test
    fun `fehlt sie an beiden Orten, wird nichts angelegt`() {
        server.enqueue(status(404))
        server.enqueue(status(404))

        val outcome = sync.sync(date, report)

        assertEquals(DailyNoteSync.Outcome.NoteMissing(date), outcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `abgelehnte Anmeldung wird durchgereicht statt verschluckt`() {
        server.enqueue(status(401))

        assertEquals(DailyNoteSync.Outcome.Unauthorized, sync.sync(date, report))
    }

    @Test
    fun `nach einem 412 wird neu gelesen und der zweite Versuch gelingt`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(412))
        server.enqueue(notiz(leereNotiz, "\"v2\""))
        server.enqueue(status(204))

        val outcome = sync.sync(date, report)

        assertTrue(outcome is DailyNoteSync.Outcome.Written)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        server.takeRequest()
        assertEquals("\"v2\"", server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `zweimal 412 endet als Konflikt und nicht als Erfolg`() {
        repeat(2) {
            server.enqueue(notiz(leereNotiz, "\"v1\""))
            server.enqueue(status(412))
        }

        assertEquals(DailyNoteSync.Outcome.Conflict, sync.sync(date, report))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `stehen die Werte schon da, wird nicht geschrieben`() {
        val fertig = NotePatcher.patch(leereNotiz, report).content
        server.enqueue(notiz(fertig, "\"v1\""))

        val outcome = sync.sync(date, report)

        assertTrue(outcome is DailyNoteSync.Outcome.NothingToDo)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Basic Auth geht an beiden Verben mit`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))
        sync.sync(date, report)

        val erwartet = okhttp3.Credentials.basic("leo", "geheim")
        assertEquals(erwartet, server.takeRequest().getHeader("Authorization"))
        assertEquals(erwartet, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `ohne ETag vom Server geht kein If-Match mit`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(leereNotiz))
        server.enqueue(status(204))

        sync.sync(date, report)

        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `der GET verzichtet auf Komprimierung, damit der ETag stark bleibt`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        sync.sync(date, report)

        // Mit "Accept-Encoding: gzip" schwaecht der Server den ETag zu W/"..." ab, und
        // damit scheitert jedes If-Match. Das hat am 18.08.2026 den ersten Geraetetest
        // gekostet, deshalb steht der Header hier fest.
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun `ein abweichender Ordner landet im richtigen Pfad`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        DailyNoteSync(client, "Journal/Tage").sync(date, report)

        assertEquals(
            "/remote.php/dav/files/leo/second-brain/Journal/Tage/2026-08-18.md",
            server.takeRequest().path,
        )
    }

    @Test
    fun `ohne Ordner liegen die Notizen direkt unter der Basis-URL`() {
        server.enqueue(status(404))
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        DailyNoteSync(client, "").sync(date, report)

        assertEquals(
            "/remote.php/dav/files/leo/second-brain/2026-08-18.md",
            server.takeRequest().path,
        )
        // Der Monatsordner-Rueckfall haengt am Dateinamen und nicht am Ordner. Er bleibt
        // deshalb auch dann erhalten, wenn gar kein Ordner davorsteht.
        assertEquals(
            "/remote.php/dav/files/leo/second-brain/2026-08/2026-08-18.md",
            server.takeRequest().path,
        )
    }

    @Test
    fun `Schraegstriche am Rand des Ordners erzeugen kein leeres Segment`() {
        server.enqueue(notiz(leereNotiz, "\"v1\""))
        server.enqueue(status(204))

        DailyNoteSync(client, "/05 Daily Notes/").sync(date, report)

        assertEquals(
            "/remote.php/dav/files/leo/second-brain/05%20Daily%20Notes/2026-08-18.md",
            server.takeRequest().path,
        )
    }
}
