package com.example.synchealth.essen

import com.example.synchealth.tandoor.MealNutrition
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.LocalDate

/**
 * Haelt die Mahlzeiten eines Tages auf dem Handy, eine JSON-Datei je Tag.
 *
 * Gespeichert wird beim Sync, was dann galt: Gerichte, Zutaten mit geplanter und
 * angepasster Menge, Naehrwerte je Einheit und der Tag des ersten Syncs. Daraus rechnet
 * [MealMerge] beim naechsten Mal, ohne dass eigene Anpassungen verloren gehen. Die
 * Tagesnotiz bekommt davon nur die Summen.
 *
 * Eine Datei, die sich nicht lesen laesst, zaehlt wie keine: Dann gilt wieder der Plan.
 */
class MealStore(private val dir: File) {

    fun load(date: LocalDate): MealMerge.Existing? {
        val file = file(date)
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    fun save(date: LocalDate, existing: MealMerge.Existing) {
        dir.mkdirs()
        // Erst daneben schreiben, dann umbenennen: Bricht es mittendrin ab, bleibt der
        // alte Stand stehen statt einer halben Datei.
        val tmp = File(dir, "$date.json.tmp")
        tmp.writeText(encode(existing))
        if (!tmp.renameTo(file(date))) {
            tmp.copyTo(file(date), overwrite = true)
            tmp.delete()
        }
    }

    private fun file(date: LocalDate) = File(dir, "$date.json")

    companion object {

        fun encode(existing: MealMerge.Existing): String = JsonObject().apply {
            addProperty("firstSync", existing.firstSync?.toString())
            add("meals", JsonArray().apply { existing.meals.forEach { add(encodeMeal(it)) } })
        }.toString()

        fun decode(text: String): MealMerge.Existing {
            val root = JsonParser.parseString(text).asJsonObject
            return MealMerge.Existing(
                firstSync = root.text("firstSync")?.let(LocalDate::parse),
                meals = root.getAsJsonArray("meals").map { decodeMeal(it.asJsonObject) },
            )
        }

        private fun encodeMeal(meal: PlannedMeal) = JsonObject().apply {
            addProperty("entryId", meal.entryId)
            addProperty("recipeId", meal.recipeId)
            addProperty("recipeName", meal.recipeName)
            addProperty("slot", meal.slot.name)
            add("ingredients", JsonArray().apply {
                meal.ingredients.forEach { ingredient ->
                    add(JsonObject().apply {
                        addProperty("name", ingredient.name)
                        addProperty("amount", ingredient.amount)
                        addProperty("planned", ingredient.planned)
                        addProperty("unit", ingredient.unit)
                        ingredient.perUnit?.let { n ->
                            add("perUnit", JsonArray().apply {
                                listOf(n.calories, n.protein, n.fat, n.fiber, n.sugar).forEach(::add)
                            })
                        }
                    })
                }
            })
        }

        private fun decodeMeal(o: JsonObject) = PlannedMeal(
            entryId = o.get("entryId").asInt,
            recipeId = o.get("recipeId").asInt,
            recipeName = o.get("recipeName").asString,
            slot = Slot.valueOf(o.get("slot").asString),
            ingredients = o.getAsJsonArray("ingredients").map { element ->
                val i = element.asJsonObject
                Ingredient(
                    name = i.get("name").asString,
                    amount = i.number("amount"),
                    planned = i.number("planned"),
                    perUnit = i.getAsJsonArray("perUnit")?.map { it.asDouble }?.let {
                        MealNutrition(it[0], it[1], it[2], it[3], it[4])
                    },
                    unit = i.text("unit") ?: GRAMS,
                )
            },
        )

        private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
        private fun JsonObject.number(key: String): Double? = value(key)?.asDouble
        private fun JsonObject.text(key: String): String? = value(key)?.asString
    }
}
