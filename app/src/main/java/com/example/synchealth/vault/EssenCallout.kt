package com.example.synchealth.vault

import com.example.synchealth.essen.GRAMS
import com.example.synchealth.essen.Ingredient
import com.example.synchealth.essen.MealMerge
import com.example.synchealth.essen.PlannedMeal
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.example.synchealth.essen.roundAmount
import com.example.synchealth.tandoor.MealNutrition
import java.time.LocalDate
import java.util.Locale

/**
 * Der eingeklappte Essen-Callout der Tagesnotiz: schreiben, wiederfinden, zuruecklesen.
 *
 * Er steht zwischen `## Notizen` und `## Daten` und sieht so aus:
 *
 * ```
 * > [!info]- Essen · 1970 kcal
 * > %% synchealth erster-sync=2026-10-24 %%
 * >
 * > > [!note]- Mittag · 660 kcal
 * > > **Bowl** · 660 kcal
 * > > - [Vollkornreis:: 100]
 * > > - [Zutat Zucker:: 3.5]
 * > > - [Fladenbrot:: 0.5] Stück
 * > > - [Parmesan:: 30] ⚠ ohne Nährwerte
 * > > - Senf ⚠ ohne Mengenangabe
 * > > %% synchealth eintrag=259 rezept=13
 * > > Vollkornreis | 100 | 3.56 0.0889 0.0111 0.0356 0.0071
 * > > Fladenbrot | 0.5 | 509 22.05 8.32 11.97 1 | Stück
 * > > Senf | - | -
 * > > %%
 * ```
 *
 * **Die Notiz ist die Wahrheit.** Was dort steht, auch von Hand geaendert, liest
 * [parse] zurueck. Dafuer traegt jedes Gericht einen Kommentar, den Obsidian in der
 * Leseansicht nicht zeigt: je Zutat die zuletzt geplante Menge und die Naehrwerte je
 * Gramm (kcal, Protein, Fett, Ballaststoffe, Zucker). Damit laesst sich jede geaenderte
 * Menge neu rechnen, auch an einem Tag, fuer den Tandoor nicht mehr gefragt wird.
 *
 * **Zutaten sind Dataview-Felder in Klammerform**, `[Name:: Gramm]`, ohne Einheit, damit
 * Dataview eine Zahl liest. Kennt Tandoor keine Grammzahl, steht die Menge in der Einheit
 * des Rezepts und die Einheit hinter der Klammer, `[Fladenbrot:: 0.5] Stück`. Heisst eine Zutat wie ein Feld, das die Notiz schon fuehrt,
 * etwa `Zucker` oder `Wasser`, bekommt sie den Vorsatz [PREFIX]. Sonst stuenden zwei
 * Felder gleichen Namens in der Notiz, Dataview machte eine Liste daraus, und der
 * Messwert fiele still aus dem Monatsschnitt.
 *
 * Samsung-Eintraege stehen nur zum Ansehen darin und werden bei jedem Lauf neu
 * geschrieben, geaendert werden sie in Samsung Health.
 */
object EssenCallout {

    const val PREFIX = "Zutat "

    /** Was [parse] aus der Notiz liest. [issues] nennt Zeilen, die es nicht versteht. */
    data class Parsed(val existing: MealMerge.Existing, val issues: List<String>)

    // --- Schreiben --------------------------------------------------------------

    /**
     * Der ganze Callout als Text, ohne abschliessenden Zeilenumbruch.
     *
     * [totalCalories] ist die Zahl, die auch unter `###### Essen` steht. [reserved] sind
     * die Feldnamen der Notiz ausserhalb des Callouts, siehe [reservedKeys].
     */
    fun render(
        firstSync: LocalDate,
        meals: List<PlannedMeal>,
        samsung: List<SamsungMeal>,
        totalCalories: Double?,
        reserved: Set<String>,
    ): String {
        val lines = mutableListOf<String>()
        // Abgeschnitten wie im Feld `Kalorien::`, damit Kopf und Feld dieselbe Zahl zeigen.
        lines += "> [!info]- Essen" + (totalCalories?.let { " · ${it.toInt()} kcal" } ?: "")
        lines += "> %% synchealth erster-sync=$firstSync %%"

        for (slot in Slot.entries) {
            val planned = meals.filter { it.slot == slot }
            val fromSamsung = samsung.filter { it.slot == slot }
            if (planned.isEmpty() && fromSamsung.isEmpty()) continue

            val slotCalories = planned.sumOf { it.nutrition.calories } +
                fromSamsung.sumOf { it.calories ?: 0.0 }
            lines += ">"
            lines += "> > [!note]- ${slot.label}" + kcalSuffix(slotCalories)
            planned.forEachIndexed { i, meal ->
                if (i > 0) lines += "> >"
                lines += renderMeal(meal, reserved)
            }
            if (fromSamsung.isNotEmpty()) {
                if (planned.isNotEmpty()) {
                    lines += "> >"
                    lines += "> > **Samsung Health**"
                }
                fromSamsung.forEach { lines += "> > - ${clean(it.title)}" + kcalSuffix(it.calories) }
            }
        }
        return lines.joinToString("\n")
    }

    private fun renderMeal(meal: PlannedMeal, reserved: Set<String>): List<String> {
        val lines = mutableListOf<String>()
        lines += "> > **${clean(meal.recipeName)}**" + kcalSuffix(meal.nutrition.calories)
        for (ingredient in meal.ingredients) {
            val name = clean(ingredient.name)
            lines += when {
                ingredient.amount == null -> "> > - $name ⚠ ${ingredient.warning}"
                else -> "> > - [${key(name, reserved)}:: ${amount(ingredient.amount, ingredient.unit)}]" +
                    (if (ingredient.unit != GRAMS) " ${ingredient.unit}" else "") +
                    (ingredient.warning?.let { " ⚠ $it" } ?: "")
            }
        }
        lines += "> > %% synchealth eintrag=${meal.entryId} rezept=${meal.recipeId}"
        for (ingredient in meal.ingredients) {
            val planned = ingredient.planned?.let { amount(it, ingredient.unit) } ?: "-"
            val perUnit = ingredient.perUnit?.let { perUnit(it) } ?: "-"
            val unit = if (ingredient.unit != GRAMS) " | ${clean(ingredient.unit)}" else ""
            lines += "> > ${clean(ingredient.name)} | $planned | $perUnit$unit"
        }
        lines += "> > %%"
        return lines
    }

    /** Der Feldname einer Zutat: der Name selbst, oder mit [PREFIX], wenn er schon belegt ist. */
    fun key(name: String, reserved: Set<String>): String =
        if (canonical(name) in reserved) PREFIX + name else name

    private fun kcalSuffix(calories: Double?): String =
        if (calories == null) "" else " · ${Math.round(calories)} kcal"

    /** Immer Punkt, so viele Stellen wie [roundAmount] laesst, keine ueberfluessige Null. */
    fun amount(value: Double, unit: String = GRAMS): String = number(roundAmount(value, unit))

    private fun perUnit(n: MealNutrition): String =
        listOf(n.calories, n.protein, n.fat, n.fiber, n.sugar).joinToString(" ") { number(it) }

    private fun number(value: Double): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /**
     * Zeichen, die das Format brechen wuerden, fliegen aus Namen heraus: Klammern und
     * Doppelpunkte beenden ein Dataview-Feld, `|` trennt die Spalten im Kommentar, `%%`
     * beendet ihn, `*` und `·` stehen im Kopf eines Gerichts.
     */
    fun clean(name: String): String =
        name.replace(Regex("""[\[\]:|%*·\r\n]"""), " ").replace(Regex("""\s+"""), " ").trim()

    // --- Wiederfinden -----------------------------------------------------------

    private val START = Regex("""^> \[!info][+-]? Essen\b.*$""")

    /** Die Zeilen des Callouts als Bereich, oder `null`, wenn die Notiz keinen hat. */
    fun locate(content: String): IntRange? {
        val lines = content.split("\n")
        val start = lines.indexOfFirst { START.matches(it) }
        if (start < 0) return null
        var end = start
        while (end + 1 < lines.size && lines[end + 1].startsWith(">")) end++
        return start..end
    }

    /**
     * Setzt [callout] in die Notiz: an die Stelle des alten, oder neu direkt vor
     * `## Daten`. Gibt `null` zurueck, wenn die Notiz kein `## Daten` hat; dann wird nichts
     * geraten, sondern gemeldet.
     */
    fun place(content: String, callout: String): String? {
        val lines = content.split("\n").toMutableList()
        val range = locate(content)
        if (range != null) {
            repeat(range.last - range.first + 1) { lines.removeAt(range.first) }
            lines.addAll(range.first, callout.split("\n"))
            return lines.joinToString("\n")
        }
        val daten = lines.indexOfFirst { DATEN.matches(it) }
        if (daten < 0) return null
        // Eine Leerzeile davor und danach, sonst klebt der Callout an der Liste darueber
        // oder Obsidian zieht die Ueberschrift in den Callout.
        val insert = mutableListOf<String>()
        if (daten > 0 && lines[daten - 1].isNotBlank()) insert += ""
        insert += callout.split("\n")
        insert += ""
        lines.addAll(daten, insert)
        return lines.joinToString("\n")
    }

    private val DATEN = Regex("""^#{1,6} Daten\s*$""")

    /**
     * Die Feldnamen, die die Notiz ausserhalb des Callouts schon fuehrt, in Dataviews
     * Schreibweise: Frontmatter-Schluessel und Inline-Felder. Ausgelesen statt fest
     * verdrahtet, damit ein neues Feld in der Vorlage nicht erst hier nachgetragen werden
     * muss. Die Felder, die die App selbst schreibt, stehen in jedem Fall darin.
     */
    fun reservedKeys(content: String): Set<String> {
        val lines = content.split("\n")
        val callout = locate(content)
        val keys = mutableSetOf<String>()
        var inFrontmatter = lines.firstOrNull()?.trim() == "---"
        for ((i, line) in lines.withIndex()) {
            if (callout != null && i in callout) continue
            if (inFrontmatter) {
                if (i > 0 && line.trim() == "---") {
                    inFrontmatter = false
                    continue
                }
                FRONTMATTER_KEY.find(line)?.let { keys += canonical(it.groupValues[1]) }
                continue
            }
            INLINE_KEY.find(line)?.let { keys += canonical(it.groupValues[1]) }
            BRACKET_KEY.findAll(line).forEach { keys += canonical(it.groupValues[1]) }
        }
        return keys + ALWAYS_RESERVED.map { canonical(it) }
    }

    private val FRONTMATTER_KEY = Regex("""^([^\s#:-][^:]*):""")
    private val INLINE_KEY = Regex("""^\s*(?:-\s*)?([^\[\]:\n]+?)::""")
    private val BRACKET_KEY = Regex("""[\[(]([^\[\]():\n]+?)::""")

    /** Die Felder, die [NotePatcher] schreibt. Sie gelten auch dann, wenn die Zeile fehlt. */
    private val ALWAYS_RESERVED = listOf(
        "Schlaf", "Schritte", "Screen Time", "Kalorien", "Protein", "Ballaststoffe",
        "Fett", "Zucker", "Wasser",
    )

    /** Dataviews Schreibweise eines Schluessels: klein, Leerzeichen als Bindestrich. */
    fun canonical(key: String): String =
        key.trim().lowercase().replace(Regex("""\s+"""), "-")

    // --- Zuruecklesen -----------------------------------------------------------

    private val FIRST_SYNC = Regex("""erster-sync=(\d{4}-\d{2}-\d{2})""")
    private val SLOT = Regex("""^> > \[!note][+-]? (.+?)(?: · .*)?$""")
    private val MEAL_HEAD = Regex("""^> > \*\*(.+?)\*\*(?: · .*)?$""")
    private val FIELD = Regex("""^> > - \[([^\[\]]+?):: *([^\[\]]*?) *](.*)$""")
    private val COMMENT_START = Regex("""^> > %% synchealth eintrag=(\d+) rezept=(\d+)\s*$""")
    private val COMMENT_END = Regex("""^> > %%\s*$""")
    private val COMMENT_ROW = Regex("""^> > (.+?) \| (\S+) \| ([^|]+?)(?: \| (.+))?$""")

    /** Liest den Callout zurueck. `null`, wenn die Notiz keinen hat. */
    fun parse(content: String): Parsed? {
        val range = locate(content) ?: return null
        val lines = content.split("\n").subList(range.first, range.last + 1)
        val issues = mutableListOf<String>()
        val meals = mutableListOf<PlannedMeal>()

        val firstSync = lines.firstNotNullOfOrNull { FIRST_SYNC.find(it) }
            ?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
        if (firstSync == null) issues += "Essen-Callout ohne lesbares erster-sync"

        var slot: Slot? = null
        var recipeName: String? = null
        // Die Felder eines Gerichts stehen vor seinem Kommentar und gehoeren zu ihm.
        val fields = linkedMapOf<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            SLOT.find(line)?.let { m ->
                slot = Slot.ofLabel(m.groupValues[1])
                recipeName = null
                fields.clear()
            }
            MEAL_HEAD.find(line)?.let { m ->
                recipeName = m.groupValues[1]
                fields.clear()
            }
            FIELD.find(line)?.let { m ->
                if (recipeName != null) fields[m.groupValues[1].trim()] = m.groupValues[2]
            }
            val comment = COMMENT_START.find(line)
            if (comment != null) {
                val rows = mutableListOf<String>()
                i++
                while (i < lines.size && !COMMENT_END.matches(lines[i])) rows += lines[i++]
                val meal = readMeal(
                    entryId = comment.groupValues[1].toInt(),
                    recipeId = comment.groupValues[2].toInt(),
                    name = recipeName,
                    slot = slot,
                    rows = rows,
                    fields = fields,
                    issues = issues,
                )
                if (meal != null) meals += meal
                recipeName = null
                fields.clear()
            }
            i++
        }
        return Parsed(MealMerge.Existing(firstSync, meals), issues)
    }

    private fun readMeal(
        entryId: Int,
        recipeId: Int,
        name: String?,
        slot: Slot?,
        rows: List<String>,
        fields: Map<String, String>,
        issues: MutableList<String>,
    ): PlannedMeal? {
        if (name == null || slot == null) {
            issues += "Gericht zu Planeintrag $entryId ohne Kopfzeile"
            return null
        }
        val unclaimed = fields.keys.toMutableSet()
        val ingredients = mutableListOf<Ingredient>()
        for (row in rows) {
            val m = COMMENT_ROW.find(row)
            if (m == null) {
                issues += "$name: Kommentarzeile unlesbar: ${row.removePrefix("> > ")}"
                continue
            }
            val ingredient = m.groupValues[1]
            val planned = m.groupValues[2].takeIf { it != "-" }?.let { parseNumber(it) }
            val perUnit = m.groupValues[3].takeIf { it.trim() != "-" }?.let { parsePerUnit(it) }
            val unit = m.groupValues[4].trim().ifEmpty { GRAMS }
            if (m.groupValues[2] != "-" && planned == null || m.groupValues[3].trim() != "-" && perUnit == null) {
                issues += "$name: Kommentarzeile unlesbar: ${row.removePrefix("> > ")}"
                continue
            }

            // Ob mit oder ohne Vorsatz: Welcher Name gerade belegt ist, kann sich
            // zwischen zwei Laeufen aendern, gelesen wird deshalb beides.
            val key = listOf(ingredient, PREFIX + ingredient).firstOrNull { it in fields }
            val amount = when {
                planned == null -> null
                // Die Zeile ist weg: von Hand geloescht, also nicht gegessen.
                key == null -> 0.0
                else -> {
                    unclaimed -= key
                    val value = parseNumber(fields.getValue(key))
                    if (value == null) {
                        issues += "$name: \"${fields.getValue(key)}\" bei $ingredient ist keine Zahl"
                        return null
                    }
                    value
                }
            }
            ingredients += Ingredient(ingredient, amount, planned, perUnit, unit)
        }
        unclaimed.forEach { issues += "$name: unbekannte Zutat \"$it\"" }
        return PlannedMeal(entryId, recipeId, name, slot, ingredients)
    }

    /** Ein Komma beim Tippen am Handy ist ein Versehen, kein anderer Wert. */
    private fun parseNumber(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }

    private fun parsePerUnit(text: String): MealNutrition? {
        val values = text.trim().split(Regex("""\s+""")).map { it.toDoubleOrNull() ?: return null }
        if (values.size != 5) return null
        return MealNutrition(values[0], values[1], values[2], values[3], values[4])
    }
}
