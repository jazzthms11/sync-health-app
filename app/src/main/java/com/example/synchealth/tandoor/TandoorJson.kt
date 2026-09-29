package com.example.synchealth.tandoor

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/** Ein Platz im Wochenplan. Ohne Rezept wird an ihm nicht aus der eigenen Kueche gegessen. */
data class MealPlanEntry(
    val id: Int,
    val recipeId: Int?,
    val recipeName: String,
    /** Portionen fuer den ganzen Eintrag, nicht je Tag. */
    val servings: Double,
    /** Traegt die Esser als Marken in der ersten Zeile, siehe [MealPlanMath.markers]. */
    val note: String,
    /** Der Name der Mahlzeitenart, wie sie in Tandoor heisst: `Mittagessen`, `Abendessen`. */
    val mealType: String,
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * Ein Rezept mit seiner aufsummierten Naehrwertliste.
 *
 * [servings] ist die hinterlegte Portionszahl, auf die sich [properties] bezieht: die
 * Werte gelten fuer das ganze Rezept und nicht je Portion.
 */
data class Recipe(
    val id: Int,
    val name: String,
    val servings: Double,
    val properties: List<FoodProperty>,
)

/**
 * Eine Zeile aus `food_properties`. [missing] heisst, eine Zutat ist still aus der Summe
 * gefallen, der Wert ist dann zu niedrig und taugt nicht.
 *
 * [fdcId] steht nicht in der Zeile selbst. Tandoor fuehrt dort nur seine eigene
 * Typ-ID, die je Instanz vergeben wird; die stabile Nummer haengt am Typ und kommt
 * ueber [TandoorJson.propertyTypes] dazu. Ist der Typ dort ohne `fdc_id` gepflegt,
 * bleibt sie `null`, und der Wert faellt aus der Rechnung.
 */
data class FoodProperty(
    val fdcId: Int?,
    val totalValue: Double,
    val missing: Boolean,
)

/**
 * Macht aus Tandoors JSON die Modelle oben. Reiner Text hinein, Datenklassen heraus:
 * kein Netz, kein Android, damit die Zuordnung ohne Server pruefbar bleibt.
 *
 * Bewusst von Hand ueber [JsonElement] statt ueber gsons Reflexion. Die Antworten tragen
 * ein Vielfaches der fuenf Felder, die hier zaehlen, und zwei Stellen sind nicht
 * festgelegt: eine Liste kommt mal roh und mal in `results` verpackt, und ein Datum mal
 * als `2026-09-28` und mal mit angehaengter Uhrzeit. Beides faengt der Parser ab, statt
 * an der naechsten Tandoor-Version zu zerbrechen.
 */
object TandoorJson {

    class MalformedException(message: String) : Exception(message)

    fun mealPlan(body: String): List<MealPlanEntry> = wrap {
        list(parse(body)).filter { it.isJsonObject }.map { entry(it.asJsonObject) }
    }

    /**
     * [fdcByType] ordnet Tandoors Typ-ID der stabilen Nummer zu, siehe [propertyTypes].
     * Ohne sie liesse sich `food_properties` nicht deuten: Die Zeilen tragen nur Namen
     * und Typ-ID, und beide sind je Instanz frei vergeben.
     */
    fun recipe(body: String, fdcByType: Map<Int, Int>): Recipe = wrap {
        val root = parse(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw MalformedException("Rezept erwartet, Objekt bekommen")
        Recipe(
            id = root.int("id") ?: throw MalformedException("Rezept ohne id"),
            name = root.string("name").orEmpty(),
            // Ohne Portionszahl liesse sich nicht auf eine Portion herunterrechnen.
            servings = root.double("servings")
                ?: throw MalformedException("Rezept ohne servings"),
            properties = properties(root.get("food_properties"), fdcByType),
        )
    }

    /**
     * Die Naehrwerttypen der Instanz als `Typ-ID zu fdc_id`.
     *
     * Typen legt man in Tandoor selbst an, mit eigenem Namen und eigener ID. Die
     * `fdc_id` daneben ist die Nummer aus FoodData Central und damit das einzige, was
     * ueber Instanzen und Umbenennungen hinweg gleich bleibt. Ein Typ ohne `fdc_id`
     * faellt heraus, er laesst sich keinem der fuenf Naehrwerte zuordnen.
     */
    fun propertyTypes(body: String): Map<Int, Int> = wrap {
        list(parse(body)).filter { it.isJsonObject }.mapNotNull { row ->
            val obj = row.asJsonObject
            val id = obj.int("id") ?: return@mapNotNull null
            val fdcId = obj.int("fdc_id") ?: return@mapNotNull null
            id to fdcId
        }.toMap()
    }

    /**
     * Eine Antwort, die anders aussieht als erwartet, wird gemeldet statt geworfen.
     *
     * Gson wirft an jedem Feld, dessen Typ nicht passt, eine eigene Sorte
     * [RuntimeException]: einen Cast beim Objekt, ein Zahlenformat beim Primitiv. Sie
     * alle einzeln abzufangen waere mehr Code als diese eine Klammer, und uebrig bliebe
     * derselbe Ausgang: Der [TandoorClient] meldet die Antwort als unlesbar, und
     * geschrieben wird nichts.
     */
    private fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw MalformedException(e.message ?: e.javaClass.simpleName)
    }

    private fun entry(json: JsonObject): MealPlanEntry {
        val from = json.date("from_date") ?: throw MalformedException("Planeintrag ohne from_date")
        val recipe = json.get("recipe")?.takeIf { it.isJsonObject }?.asJsonObject
        return MealPlanEntry(
            id = json.int("id") ?: throw MalformedException("Planeintrag ohne id"),
            recipeId = recipe?.int("id"),
            recipeName = recipe?.string("name").orEmpty(),
            // Ein Eintrag ohne Portionsangabe ist eine Portion, so legt Tandoor ihn an.
            servings = json.double("servings") ?: 1.0,
            note = json.string("note").orEmpty(),
            mealType = json.get("meal_type")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.string("name").orEmpty(),
            from = from,
            // Ein eintaegiger Eintrag traegt kein to_date.
            to = json.date("to_date") ?: from,
        )
    }

    private fun properties(json: JsonElement?, fdcByType: Map<Int, Int>): List<FoodProperty> {
        if (json == null || json.isJsonNull) return emptyList()
        // Tandoor schluesselt die Zeilen nach der Typ-ID und wiederholt sie als `id` in
        // der Zeile. Gelesen wird die `id`, dann traegt die Array-Form genauso.
        val rows = when {
            json.isJsonArray -> json.asJsonArray.toList()
            json.isJsonObject -> json.asJsonObject.entrySet().map { it.value }
            else -> emptyList()
        }
        return rows.filter { it.isJsonObject }.map { row ->
            val obj = row.asJsonObject
            FoodProperty(
                fdcId = obj.int("id")?.let { fdcByType[it] },
                totalValue = obj.double("total_value") ?: 0.0,
                missing = obj.get("missing_value")?.takeIf { it.isJsonPrimitive }
                    ?.asBoolean ?: false,
            )
        }
    }

    private fun parse(body: String): JsonElement = JsonParser.parseString(body)

    /** Eine Liste kommt roh oder unter `results`, je nachdem ob der Endpunkt blaettert. */
    private fun list(json: JsonElement): List<JsonElement> = when {
        json.isJsonArray -> json.asJsonArray.toList()
        json.isJsonObject -> json.asJsonObject.get("results")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.toList()
            ?: throw MalformedException("Liste erwartet, weder Array noch results")
        else -> throw MalformedException("Liste erwartet, ${json.javaClass.simpleName} bekommen")
    }

    private fun JsonObject.value(name: String): JsonElement? =
        get(name)?.takeIf { it.isJsonPrimitive }

    private fun JsonObject.int(name: String): Int? = value(name)?.asInt

    private fun JsonObject.double(name: String): Double? = value(name)?.asDouble

    private fun JsonObject.string(name: String): String? = value(name)?.asString

    /** Tandoor liefert mal ein reines Datum, mal eines mit Uhrzeit. Die ersten zehn Zeichen reichen. */
    private fun JsonObject.date(name: String): LocalDate? = string(name)
        ?.takeIf { it.length >= 10 }
        ?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }
}
