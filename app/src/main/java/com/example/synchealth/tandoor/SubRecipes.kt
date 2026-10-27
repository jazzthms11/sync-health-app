package com.example.synchealth.tandoor

/**
 * Klappt verlinkte Rezepte auf: Statt "1 Stück Kartoffelknödel" stehen danach Kartoffel,
 * Stärke, Ei usw. im Gericht, auf die Menge umgerechnet, die das Gericht davon braucht.
 *
 * Tandoor kennt zwei Arten zu verlinken:
 *
 * - **Ein Lebensmittel zeigt auf ein Rezept** ([RecipeFood.recipeId]). So ist etwa
 *   "Pilzgulasch mit Kartoffelknödeln" gebaut: zwei Zeilen, "1 Stück Pilzgulasch" und
 *   "1 Stück Kartoffelknödel". Wie viel vom Unterrezept gemeint ist, sagt [factor].
 * - **Ein Schritt bindet ein Rezept ein** ([Recipe.stepRecipes]). Tandoor zeigt dann das
 *   ganze Unterrezept mit seinen eigenen Mengen an, also zaehlt es einmal ganz.
 *
 * Gerechnet wird aus den Zutaten des Unterrezepts, nicht aus den Naehrwerten, die am
 * verlinkten Lebensmittel haengen: Die sind eine Momentaufnahme und veralten, sobald das
 * Unterrezept geaendert wird (am 27.10.2026: Kartoffelknödel 1704 kcal am Lebensmittel,
 * 1334 kcal laut Rezept).
 *
 * Rein rechnend wie [MealPlanMath]: Was geholt wird, entscheidet [TandoorSource].
 */
object SubRecipes {

    /** Die Rezepte, auf die [recipe] direkt zeigt. */
    fun linked(recipe: Recipe): Set<Int> =
        (recipe.foods.mapNotNull { it.recipeId } + recipe.stepRecipes).toSet()

    /**
     * [recipe] mit allen Unterrezepten aufgeklappt, auch verschachtelten.
     *
     * Bleibt ein Verweis stehen, weil das Rezept in [recipes] fehlt, sich im Kreis zeigt
     * oder [factor] keine Menge findet, gilt die Zeile wie bisher: als ein Lebensmittel
     * mit den Naehrwerten, die Tandoor dafuer kennt.
     */
    fun flatten(recipe: Recipe, recipes: Map<Int, Recipe>): Recipe =
        flatten(recipe, recipes, setOf(recipe.id))

    private fun flatten(recipe: Recipe, recipes: Map<Int, Recipe>, path: Set<Int>): Recipe {
        fun open(id: Int): Recipe? =
            if (id in path) null else recipes[id]?.let { flatten(it, recipes, path + id) }

        val foods = mutableListOf<RecipeFood>()
        for (food in recipe.foods) {
            val sub = food.recipeId?.let(::open)
            val factor = sub?.let { factor(food, it) }
            if (sub == null || factor == null) foods += food else foods += sub.foods.map { it * factor }
        }
        for (id in recipe.stepRecipes) {
            open(id)?.let { foods += it.foods }
        }
        return recipe.copy(foods = merge(foods), stepRecipes = emptyList())
    }

    /**
     * Welcher Teil des ganzen Unterrezepts [sub] mit der Zeile [food] gemeint ist.
     *
     * - In Portionen (`Portion`, `Personen`, oder wie das Unterrezept seine Portionen
     *   nennt): Menge / Portionen des Unterrezepts.
     * - In Gramm: Menge / Gewicht des Unterrezepts, wenn jede seiner Zutaten Gramm hat.
     * - In Stück: Ein Stück ist das ganze Rezept. So rechnet Tandoor selbst, das
     *   Pilzgulasch am Lebensmittel hat genau die Naehrwerte des ganzen Unterrezepts.
     *
     * Sonst `null`, und die Zeile bleibt, wie sie ist.
     */
    fun factor(food: RecipeFood, sub: Recipe): Double? {
        val amount = food.amount?.takeIf { it > 0.0 } ?: return null
        val unit = food.unit.trim().lowercase()
        val servingsName = sub.servingsText.trim().lowercase()
        if (sub.servings > 0.0 && (unit in PORTIONS || (servingsName.isNotEmpty() && unit == servingsName))) {
            return amount / sub.servings
        }
        if (unit == "g") {
            val total = sub.foods.takeIf { foods -> foods.all { it.unit == "g" && it.amount != null } }
                ?.sumOf { it.amount!! }
                ?.takeIf { it > 0.0 }
                ?: return null
            return amount / total
        }
        if (unit in WHOLE) return amount
        return null
    }

    /**
     * Fasst gleiche Lebensmittel zusammen, wenn sie dieselbe Einheit tragen, etwa die
     * Kartoffel aus Gulasch und Knoedeln. Sonst stuende sie zweimal unter einem Gericht.
     * Fehlen einer Seite die Naehrwerte, fehlen sie dem Ganzen: Halbe Werte fielen nicht auf.
     */
    private fun merge(foods: List<RecipeFood>): List<RecipeFood> {
        val out = mutableListOf<RecipeFood>()
        val at = mutableMapOf<Pair<String, String>, Int>()
        for (food in foods) {
            val key = food.name to food.unit
            val index = at[key]
            if (food.amount == null || food.recipeId != null || index == null) {
                if (food.amount != null && food.recipeId == null) at[key] = out.size
                out += food
                continue
            }
            val before = out[index]
            out[index] = before.copy(
                amount = before.amount!! + food.amount,
                nutrition = if (before.nutrition == null || food.nutrition == null) null
                else before.nutrition + food.nutrition,
            )
        }
        return out
    }

    private operator fun RecipeFood.times(factor: Double): RecipeFood =
        copy(amount = amount?.times(factor), nutrition = nutrition?.times(factor))

    private val PORTIONS = setOf("portion", "portionen", "person", "personen", "serving", "servings")
    private val WHOLE = setOf("stück", "stk", "stk.", "rezept", "mal", "x")
}
