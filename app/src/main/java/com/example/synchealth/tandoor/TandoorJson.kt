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
 * Ein Rezept mit seinen Zutaten.
 *
 * [servings] ist die hinterlegte Portionszahl, auf die sich [foods] bezieht: Mengen und
 * Naehrwerte gelten fuer das ganze Rezept und nicht je Portion.
 */
data class Recipe(
    val id: Int,
    val name: String,
    val servings: Double,
    val foods: List<RecipeFood>,
)

/**
 * Ein Lebensmittel im Rezept, ueber alle Zutatenzeilen zusammengefasst. Tandoor fasst in
 * `food_values` genauso zusammen, wenn ein Lebensmittel zweimal vorkommt.
 *
 * [unit] ist `g`, wenn Tandoor fuer jede Zeile eine Grammzahl kennt. Sonst gilt die
 * Einheit aus dem Rezept, etwa `Stück`, solange alle Zeilen dieselbe tragen; Tandoor
 * rechnet die Naehrwerte dann je Stueck, wenn sie so gepflegt sind. Laesst sich keine
 * gemeinsame Einheit finden, ist [amount] `null`.
 *
 * [nutrition] ist der Beitrag zum ganzen Rezept und `null`, wenn einer der fuenf Werte
 * fehlt.
 */
data class RecipeFood(
    val name: String,
    val amount: Double?,
    val nutrition: MealNutrition?,
    val unit: String = "g",
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
            foods = foods(root, foodValues(root.get("food_properties"), fdcByType)),
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

    /**
     * Die Beitraege je Lebensmittel aus `food_properties`, als `Lebensmittel-ID zu Wert je
     * fdc_id`. Ein `null` heisst: Tandoor kennt den Wert fuer dieses Lebensmittel nicht.
     *
     * Tandoor schluesselt die Zeilen nach der Typ-ID und wiederholt sie als `id` in der
     * Zeile. Gelesen wird die `id`, dann traegt die Array-Form genauso.
     */
    private fun foodValues(json: JsonElement?, fdcByType: Map<Int, Int>): Map<Int, Map<Int, Double?>> {
        if (json == null || json.isJsonNull) return emptyMap()
        val rows = when {
            json.isJsonArray -> json.asJsonArray.toList()
            json.isJsonObject -> json.asJsonObject.entrySet().map { it.value }
            else -> emptyList()
        }
        val byFood = mutableMapOf<Int, MutableMap<Int, Double?>>()
        for (row in rows.filter { it.isJsonObject }.map { it.asJsonObject }) {
            val fdcId = row.int("id")?.let { fdcByType[it] } ?: continue
            if (fdcId !in FDC_IDS) continue
            val values = row.get("food_values") ?: continue
            val entries = when {
                values.isJsonObject -> values.asJsonObject.entrySet().map { it.value }
                values.isJsonArray -> values.asJsonArray.toList()
                else -> emptyList()
            }
            for (entry in entries.filter { it.isJsonObject }.map { it.asJsonObject }) {
                val foodId = entry.int("id")
                    ?: entry.get("food")?.takeIf { it.isJsonObject }?.asJsonObject?.int("id")
                    ?: continue
                byFood.getOrPut(foodId) { mutableMapOf() }[fdcId] = entry.double("value")
            }
        }
        return byFood
    }

    /**
     * Die Lebensmittel des Rezepts in der Reihenfolge, in der sie zuerst vorkommen.
     *
     * Eine Zeile ohne Menge, etwa "Salz nach Geschmack", traegt 0 g bei. Kommt ein
     * Lebensmittel nur so vor, faellt es ganz heraus: Es aendert keine Zahl und waere in
     * der Notiz nur Rauschen.
     */
    private fun foods(root: JsonObject, values: Map<Int, Map<Int, Double?>>): List<RecipeFood> {
        class Acc(val name: String) {
            var grams = 0.0
            var allGrams = true
            val byUnit = linkedMapOf<String?, Double>()
            var counted = false
        }

        val byFood = linkedMapOf<Int, Acc>()
        val steps = root.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        for (step in steps.filter { it.isJsonObject }.map { it.asJsonObject }) {
            val ingredients = step.get("ingredients")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (ingredient in ingredients.filter { it.isJsonObject }.map { it.asJsonObject }) {
                if (ingredient.bool("is_header")) continue
                val food = ingredient.get("food")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val foodId = food.int("id") ?: continue
                val acc = byFood.getOrPut(foodId) { Acc(food.string("name").orEmpty()) }

                val amount = ingredient.double("amount") ?: 0.0
                if (amount == 0.0 || ingredient.bool("no_amount")) continue
                acc.counted = true
                val grams = grams(ingredient)
                if (grams != null) acc.grams += grams else acc.allGrams = false
                val unit = ingredient.get("unit")?.takeIf { it.isJsonObject }?.asJsonObject?.string("name")
                acc.byUnit[unit] = (acc.byUnit[unit] ?: 0.0) + amount
            }
        }
        return byFood.filter { (_, acc) -> acc.counted }.map { (foodId, acc) ->
            val nutrition = nutrition(values[foodId])
            val only = acc.byUnit.keys.singleOrNull()
            when {
                acc.allGrams -> RecipeFood(acc.name, acc.grams, nutrition)
                // Keine Grammzahl, aber eine einzige Einheit: so rechnen, wie Tandoor rechnet.
                only != null -> RecipeFood(acc.name, acc.byUnit.getValue(only), nutrition, only)
                else -> RecipeFood(acc.name, null, nutrition)
            }
        }
    }

    /**
     * Die Menge einer Zutatenzeile in Gramm, oder `null`, wenn Tandoor sie nicht kennt.
     *
     * Die Grammzahl steht in `conversions`: Tandoor rechnet dort jede Zeile in alle
     * Einheiten um, die es kennt, ueber die Gewichtstabelle und die selbst gepflegten
     * Umrechnungen wie "1 Stueck Tomate = 100 g". Die eigene Einheit steht darin mit.
     * Gesucht wird nach dem Namen, denn mehr traegt ein Eintrag dort nicht. Fehlt die
     * Liste, etwa in einer aelteren Tandoor-Version, zaehlt nur eine Zeile, die selbst
     * schon in g oder kg steht.
     */
    private fun grams(ingredient: JsonObject): Double? {
        val conversions = ingredient.get("conversions")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
        conversions.firstOrNull { it.string("unit") == "g" }?.double("amount")?.let { return it }
        conversions.firstOrNull { it.string("unit") == "kg" }?.double("amount")?.let { return it * 1000.0 }

        val unit = ingredient.get("unit")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val amount = ingredient.double("amount") ?: return null
        return when (unit.string("base_unit") ?: unit.string("name")) {
            "g" -> amount
            "kg" -> amount * 1000.0
            else -> null
        }
    }

    /** Alle fuenf Werte oder keiner: Ein fehlender Wert machte die Zutat still zu leicht. */
    private fun nutrition(values: Map<Int, Double?>?): MealNutrition? {
        if (values == null) return null
        fun v(fdcId: Int): Double? = values[fdcId]
        return MealNutrition(
            calories = v(MealNutrition.FDC_CALORIES) ?: return null,
            protein = v(MealNutrition.FDC_PROTEIN) ?: return null,
            fat = v(MealNutrition.FDC_FAT) ?: return null,
            fiber = v(MealNutrition.FDC_FIBER) ?: return null,
            sugar = v(MealNutrition.FDC_SUGAR) ?: return null,
        )
    }

    private val FDC_IDS = setOf(
        MealNutrition.FDC_CALORIES,
        MealNutrition.FDC_PROTEIN,
        MealNutrition.FDC_FAT,
        MealNutrition.FDC_FIBER,
        MealNutrition.FDC_SUGAR,
    )

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

    private fun JsonObject.bool(name: String): Boolean = value(name)?.asBoolean ?: false

    /** Tandoor liefert mal ein reines Datum, mal eines mit Uhrzeit. Die ersten zehn Zeichen reichen. */
    private fun JsonObject.date(name: String): LocalDate? = string(name)
        ?.takeIf { it.length >= 10 }
        ?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }
}
