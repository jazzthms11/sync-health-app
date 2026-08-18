package com.example.synchealth.vault

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Der ganze Transport: zwei Verben gegen einen WebDAV-Endpunkt, Basic Auth im Header.
 *
 * Was Drive an dieser Stelle gebraucht hat, faellt weg. WebDAV kennt Pfade, es gibt also
 * keine Ordnersuche, keine zwischengespeicherte Ordner-ID und keinen Umgang mit mehreren
 * gleichnamigen Treffern.
 *
 * **Blockierend.** Alle Aufrufe gehen ins Netz und gehoeren auf einen IO-Thread.
 *
 * Auf `https` wird hier bewusst **nicht** geprueft, sonst waere die Klasse gegen einen
 * lokalen Testserver nicht pruefbar. Die Pruefung sitzt dort, wo die Adresse eingegeben
 * wird, also in den Einstellungen.
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = OkHttpClient(),
) {

    /** Ohne abschliessenden Schraegstrich, sonst entsteht beim Anhaengen ein leeres Segment. */
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    sealed interface GetResult {
        data class Found(val content: String, val etag: String?) : GetResult
        data object NotFound : GetResult
        data object Unauthorized : GetResult
        data class Failed(val code: Int, val detail: String) : GetResult
    }

    sealed interface PutResult {
        data object Written : PutResult
        /** Der ETag hat nicht mehr gestimmt, jemand war schneller. */
        data object Conflict : PutResult
        data object Unauthorized : PutResult
        data class Failed(val code: Int, val detail: String) : PutResult
    }

    /** [path] ist relativ zur Basis, unkodiert, z.B. "05 Daily Notes/2026-08-18.md". */
    fun get(path: String): GetResult {
        val request = Request.Builder().url(url(path)).header("Authorization", auth).get().build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> GetResult.Found(
                        content = response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty(),
                        etag = response.header("ETag"),
                    )
                    response.code == 404 -> GetResult.NotFound
                    response.code == 401 -> GetResult.Unauthorized
                    else -> GetResult.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            GetResult.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    /**
     * Schreibt [content] nach [path]. Ist [etag] gesetzt, geht er als `If-Match` mit und der
     * Server lehnt mit 412 ab, falls sich die Datei zwischendurch geaendert hat. Das ersetzt
     * das Nachlesen der Revision vor dem Upload, das unter Drive noetig war.
     */
    fun put(path: String, content: String, etag: String?): PutResult {
        val body = content.toRequestBody(MARKDOWN)
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", auth)
            .apply { if (etag != null) header("If-Match", etag) }
            .put(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> PutResult.Written
                    response.code == 412 -> PutResult.Conflict
                    response.code == 401 -> PutResult.Unauthorized
                    else -> PutResult.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            PutResult.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    private val auth: String get() = Credentials.basic(username, password)

    /** addPathSegments trennt an "/" und kodiert jedes Segment, aus dem Leerzeichen wird %20. */
    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).build()

    companion object {
        /** Kein HTTP-Status, sondern gar keine Antwort: Funkloch, DNS, Tunnel unten. */
        const val NO_RESPONSE = 0
        private val MARKDOWN = "text/markdown; charset=utf-8".toMediaType()
    }
}
