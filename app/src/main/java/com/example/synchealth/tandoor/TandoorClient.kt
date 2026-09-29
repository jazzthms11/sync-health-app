package com.example.synchealth.tandoor

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate

/**
 * Die Abrufe gegen Tandoors API, Bearer-Token im Header.
 *
 * Der Token traegt in Tandoor den Scope `read` und wird dort durchgesetzt: Ein `POST`
 * oder `PATCH` wird mit 403 abgewiesen, noch vor jeder Validierung. Die App kann in
 * Tandoor also nichts kaputtmachen, und deshalb steht hier auch kein schreibendes Verb.
 *
 * **Blockierend.** Alle Aufrufe gehen ins Netz und gehoeren auf einen IO-Thread.
 *
 * Auf `https` wird hier bewusst **nicht** geprueft, sonst waere die Klasse gegen einen
 * lokalen Testserver nicht pruefbar. Die Pruefung sitzt in den Einstellungen, wie beim
 * [com.example.synchealth.vault.WebDavClient].
 */
class TandoorClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
) {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    sealed interface Result<out T> {
        // `out` auch hier, sonst muss der Compiler beim `is Found` aus einem
        // Result<Recipe> erst auf die Projektion schliessen, um an `value` zu kommen.
        data class Found<out T>(val value: T) : Result<T>

        /** Token abgelaufen, geloescht oder falsch abgetippt. */
        data object Unauthorized : Result<Nothing>

        /** Der Token gilt, sein Scope deckt den Abruf aber nicht. */
        data object Forbidden : Result<Nothing>

        data class Failed(val code: Int, val detail: String) : Result<Nothing>
    }

    /**
     * Die Planeintraege im Fenster [from] bis [to], beide einschliesslich.
     *
     * **Tandoor filtert allein auf `to_date`.** Zurueck kommt, was in [from] bis [to]
     * *endet*; wann ein Eintrag beginnt, spielt keine Rolle. Am 29.09.2026 gegen die
     * echte Instanz geprueft: Ein Eintrag vom 28.09. bis 02.10. fehlt bei jeder Abfrage,
     * die den 02.10. nicht enthaelt, auch bei einer auf den 28.09. selbst.
     *
     * Was davon einen bestimmten Tag abdeckt, entscheidet [MealPlanMath.onDate].
     */
    fun mealPlan(from: LocalDate, to: LocalDate): Result<List<MealPlanEntry>> {
        val url = api("meal-plan").newBuilder()
            .addQueryParameter("from_date", from.toString())
            .addQueryParameter("to_date", to.toString())
            .build()
        return get(url) { TandoorJson.mealPlan(it) }
    }

    fun recipe(id: Int, fdcByType: Map<Int, Int>): Result<Recipe> =
        get(api("recipe", id.toString())) { TandoorJson.recipe(it, fdcByType) }

    /**
     * Die Naehrwerttypen der Instanz als `Typ-ID zu fdc_id`.
     *
     * Ein eigener Abruf, weil `food_properties` am Rezept die `fdc_id` nicht mitfuehrt,
     * sondern nur Tandoors eigene Typ-ID. Die Tabelle gilt fuer die ganze Instanz und
     * wird deshalb einmal je Lauf geholt, nicht je Rezept.
     */
    fun propertyTypes(): Result<Map<Int, Int>> = get(api("property-type")) {
        TandoorJson.propertyTypes(it)
    }

    private fun <T> get(url: HttpUrl, parse: (String) -> T): Result<T> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                Log.d(TAG, "GET $url -> ${response.code}")
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        try {
                            Result.Found(parse(body))
                        } catch (e: TandoorJson.MalformedException) {
                            Log.e(TAG, "Antwort von $url unlesbar: ${e.message}")
                            Result.Failed(MALFORMED, e.message ?: "Antwort unlesbar")
                        }
                    }
                    response.code == 401 -> Result.Unauthorized
                    response.code == 403 -> Result.Forbidden
                    else -> Result.Failed(response.code, response.message)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "GET $url fehlgeschlagen: ${e.message}")
            Result.Failed(NO_RESPONSE, e.message ?: "keine Verbindung")
        }
    }

    /**
     * Tandoors API besteht auf dem abschliessenden Schraegstrich, ohne ihn antwortet
     * Django mit einer Umleitung. Das leere letzte Segment erzeugt ihn.
     */
    private fun api(vararg parts: String): HttpUrl =
        base.newBuilder().addPathSegments("api/${parts.joinToString("/")}/").build()

    companion object {
        /** Kein HTTP-Status, sondern gar keine Antwort: Funkloch, DNS, Tunnel unten. */
        const val NO_RESPONSE = 0

        /** 200, aber der Inhalt passt nicht zu dem, was hier gelesen wird. */
        const val MALFORMED = -1
        private const val TAG = "SyncHealth"
    }
}
