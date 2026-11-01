package com.example.synchealth.essen

import com.example.synchealth.tandoor.MealNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class MealStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = LocalDate.of(2026, 10, 24)

    private val stand = MealMerge.Existing(
        firstSync = tag,
        meals = listOf(
            PlannedMeal(
                259, 13, "Bowl", Slot.MITTAG,
                listOf(
                    // Angepasst: 60 statt 100 g.
                    Ingredient("Reis", 60.0, 100.0, MealNutrition(3.0, 0.1, 0.01, 0.02, 0.0)),
                    Ingredient("Fladenbrot", 0.25, 0.25, MealNutrition(510.0, 15.0, 5.0, 3.0, 2.0), unit = "Stück"),
                    Ingredient("Parmesan", 30.0, 30.0, null),
                    Ingredient("Ei (M)", null, null, null, unit = ""),
                ),
            ),
        ),
    )

    @Test
    fun `was gespeichert wird, kommt genauso zurueck`() {
        val store = MealStore(tmp.root)
        store.save(tag, stand)

        assertEquals(stand, store.load(tag))
    }

    @Test
    fun `ein zweites Speichern ersetzt den Stand`() {
        val store = MealStore(tmp.root)
        store.save(tag, stand)
        val weniger = stand.copy(meals = emptyList())
        store.save(tag, weniger)

        assertEquals(weniger, store.load(tag))
    }

    @Test
    fun `ein Tag ohne Datei hat keinen Stand`() {
        assertNull(MealStore(tmp.root).load(tag))
    }

    @Test
    fun `eine kaputte Datei zaehlt wie keine`() {
        File(tmp.root, "$tag.json").writeText("{kaputt")

        assertNull(MealStore(tmp.root).load(tag))
    }
}
