package com.example.synchealth.vault

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Prueft [WebDavClient.probe], den Knopf *Verbindung testen* im Einstellungsdialog. */
class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            baseUrl = server.url("/remote.php/dav/files/leo/second-brain/").toString(),
            username = "leo",
            password = "geheim",
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `207 heisst der Ordner existiert und die Zugangsdaten gelten`() {
        server.enqueue(MockResponse().setResponseCode(207))

        assertEquals(WebDavClient.ProbeResult.Ok, client.probe("05 Daily Notes"))
    }

    @Test
    fun `404 heisst der Ordner fehlt`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(WebDavClient.ProbeResult.NotFound, client.probe("05 Daily Notes"))
    }

    @Test
    fun `401 wird durchgereicht statt als Fehler verpackt`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(WebDavClient.ProbeResult.Unauthorized, client.probe("05 Daily Notes"))
    }

    @Test
    fun `ein anderer Code landet als Failed mit dem Code`() {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 500 Kaputt"))

        val result = client.probe("05 Daily Notes")

        assertEquals(WebDavClient.ProbeResult.Failed(500, "Kaputt"), result)
    }

    @Test
    fun `die Anfrage geht als PROPFIND mit Depth 0 auf den eingetragenen Ordner`() {
        server.enqueue(MockResponse().setResponseCode(207))

        client.probe("05 Daily Notes")

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals(
            "/remote.php/dav/files/leo/second-brain/05%20Daily%20Notes",
            request.path,
        )
    }

    @Test
    fun `ohne Server dahinter ist es ein Failed ohne HTTP-Code`() {
        val toter = MockWebServer()
        toter.start()
        val url = toter.url("/remote.php/dav/files/leo/second-brain/").toString()
        toter.shutdown()

        val result = WebDavClient(url, "leo", "geheim").probe("05 Daily Notes")
            as WebDavClient.ProbeResult.Failed

        assertEquals(WebDavClient.NO_RESPONSE, result.code)
    }
}
