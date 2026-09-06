package com.example.synchealth.vault

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
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

    sealed interface ProbeResult {
        data object Ok : ProbeResult
        data object NotFound : ProbeResult
        data object Unauthorized : ProbeResult
        data class Failed(val code: Int, val detail: String) : ProbeResult
    }

    /** [path] ist relativ zur Basis, unkodiert, z.B. "05 Daily Notes/2026-08-18.md". */
    fun get(path: String): GetResult {
        val target = url(path)
        val request = Request.Builder()
            .url(target)
            .header("Authorization", auth)
            // Ohne diese Zeile setzt OkHttp von sich aus "Accept-Encoding: gzip". Der Server
            // liefert die Antwort dann komprimiert und schwaecht dabei den ETag zu W/"...".
            // Ein schwacher ETag ist fuer If-Match nicht zulaessig, der Vergleich dort ist ein
            // starker, und der PUT scheitert danach zuverlaessig mit 412. Belegt am 18.08.2026:
            // derselbe GET liefert "9090f5ad..." ohne und W/"9090f5ad..." mit Komprimierung.
            // Die Notizen sind Kilobytes, der Verzicht kostet also nichts.
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val etag = response.header("ETag")
                Log.d(TAG, "GET $target -> ${response.code} (ETag $etag)")
                if (etag != null && etag.startsWith("W/")) {
                    Log.w(TAG, "Schwacher ETag, If-Match wird daran scheitern: $etag")
                }
                when {
                    response.isSuccessful -> GetResult.Found(
                        content = response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty(),
                        etag = etag,
                    )
                    response.code == 404 -> GetResult.NotFound
                    response.code == 401 -> GetResult.Unauthorized
                    else -> GetResult.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "GET $target fehlgeschlagen: ${e.message}")
            GetResult.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    /**
     * Schreibt [content] nach [path]. Ist [etag] gesetzt, geht er als `If-Match` mit und der
     * Server lehnt mit 412 ab, falls sich die Datei zwischendurch geaendert hat. Das ersetzt
     * das Nachlesen der Revision vor dem Upload, das unter Drive noetig war.
     */
    fun put(path: String, content: String, etag: String?): PutResult {
        val target = url(path)
        val body = content.toRequestBody(MARKDOWN)
        val request = Request.Builder()
            .url(target)
            .header("Authorization", auth)
            .apply { if (etag != null) header("If-Match", etag) }
            .put(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                Log.d(TAG, "PUT $target (If-Match $etag) -> ${response.code}")
                when {
                    response.isSuccessful -> PutResult.Written
                    response.code == 412 -> PutResult.Conflict
                    response.code == 401 -> PutResult.Unauthorized
                    else -> PutResult.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "PUT $target fehlgeschlagen: ${e.message}")
            PutResult.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    /**
     * `PROPFIND` mit `Depth: 0` gegen [path]: prueft, ob der Ordner existiert und die
     * Zugangsdaten gelten, ohne dessen Inhalt aufzulisten. Fuer den Knopf *Verbindung
     * testen* im Einstellungsdialog, nicht Teil des Sync-Ablaufs.
     */
    fun probe(path: String): ProbeResult {
        val target = url(path)
        val request = Request.Builder()
            .url(target)
            .header("Authorization", auth)
            .header("Depth", "0")
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                Log.d(TAG, "PROPFIND $target -> ${response.code}")
                when {
                    response.code == 207 -> ProbeResult.Ok
                    response.code == 404 -> ProbeResult.NotFound
                    response.code == 401 -> ProbeResult.Unauthorized
                    else -> ProbeResult.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "PROPFIND $target fehlgeschlagen: ${e.message}")
            ProbeResult.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    private val auth: String get() = Credentials.basic(username, password)

    /** addPathSegments trennt an "/" und kodiert jedes Segment, aus dem Leerzeichen wird %20. */
    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).build()

    companion object {
        /** Kein HTTP-Status, sondern gar keine Antwort: Funkloch, DNS, Tunnel unten. */
        const val NO_RESPONSE = 0
        private const val TAG = "SyncHealth"
        private val MARKDOWN = "text/markdown; charset=utf-8".toMediaType()
    }
}
