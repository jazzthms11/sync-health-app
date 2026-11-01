package com.example.synchealth.health

import android.app.Activity
import android.content.Context
import androidx.core.content.ContextCompat
import com.example.synchealth.HealthReport
import com.example.synchealth.essen.SamsungMeal
import com.example.synchealth.essen.Slot
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import com.samsung.android.sdk.health.data.response.AsyncSingleFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Alles, was mit Samsung Health zu tun hat. Liest einen Tag und gibt ihn als [HealthReport]
 * heraus; was danach damit passiert, weiss diese Klasse nicht. Die fuenf Naehrwerte darin
 * sind nur der eigene Beitrag, den Rest steuert Tandoor bei, siehe
 * [com.example.synchealth.DailyTotals].
 *
 * Der Konstruktor greift auf Samsung Health zu und wirft, wenn es fehlt. Das gehoert
 * abgefangen, damit die App auch auf einem Geraet ohne Samsung Health startet.
 */
class HealthRepository(private val context: Context) {

    private val store: HealthDataStore = HealthDataService.getStore(context)

    private val permissions = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.NUTRITION, AccessType.READ),
        Permission.of(DataTypes.SLEEP, AccessType.READ),
        Permission.of(DataTypes.WATER_INTAKE, AccessType.READ),
    )

    /**
     * Fragt nur, wenn noch etwas fehlt. Samsung zeigt seinen Dialog sonst bei jedem Start,
     * auch wenn laengst alles erteilt ist.
     */
    suspend fun requestPermissions(activity: Activity): Set<Permission> {
        val granted = store.getGrantedPermissionsAsync(permissions).await()
        if (granted.containsAll(permissions)) return granted
        return store.requestPermissionsAsync(permissions, activity).await()
    }

    suspend fun read(date: LocalDate): HealthReport {
        val filter = LocalTimeFilter.of(date.atStartOfDay(), date.atTime(23, 59, 59))
        // Schlaf laeuft ueber Mitternacht und wird deshalb ueber den Tag statt die Uhrzeit gefiltert.
        val dateFilter = LocalDateFilter.of(date, date.plusDays(1))

        val stepRequest = DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilter(filter).build()
        val calRequest = DataType.NutritionType.TOTAL_CALORIES.requestBuilder
            .setLocalTimeFilter(filter).build()
        val waterRequest = DataType.WaterIntakeType.TOTAL.requestBuilder
            .setLocalTimeFilter(filter).build()
        val sleepRequest = DataType.SleepType.TOTAL_DURATION.requestBuilder
            .setLocalDateFilter(dateFilter).build()
        val nutritionRequest = DataTypes.NUTRITION.readDataRequestBuilder
            .setLocalTimeFilter(filter).build()

        val stepResult = store.aggregateDataAsync(stepRequest).await()
        val calResult = store.aggregateDataAsync(calRequest).await()
        val waterResult = store.aggregateDataAsync(waterRequest).await()
        val sleepResult = store.aggregateDataAsync(sleepRequest).await()
        val nutrition = store.readDataAsync(nutritionRequest).await()

        // Die Naehrwerte kommen als einzelne Mahlzeiten und muessen aufsummiert werden,
        // anders als Schritte, Kalorien, Wasser und Schlaf, die Samsung schon aggregiert.
        var sugar = 0.0
        var protein = 0.0
        var fat = 0.0
        var fiber = 0.0
        nutrition.dataList.forEach { record ->
            sugar += (record.getValue(DataType.NutritionType.SUGAR) as? Number)?.toDouble() ?: 0.0
            protein += (record.getValue(DataType.NutritionType.PROTEIN) as? Number)?.toDouble() ?: 0.0
            fat += (record.getValue(DataType.NutritionType.TOTAL_FAT) as? Number)?.toDouble() ?: 0.0
            fiber += (record.getValue(DataType.NutritionType.DIETARY_FIBER) as? Number)?.toDouble() ?: 0.0
        }

        val sleepIso = sleepResult.dataList.firstOrNull()?.value as? Duration ?: Duration.ZERO
        val sleepHours = sleepIso.toSeconds().toDouble() / 3600.0

        return HealthReport(
            steps = (stepResult.dataList.firstOrNull()?.value as? Long).orNull(),
            calories = (calResult.dataList.firstOrNull()?.value as? Number)?.toDouble().orNull(),
            water = (waterResult.dataList.firstOrNull()?.value as? Number)?.toDouble().orNull(),
            sleep = sleepHours.orNull(),
            protein = protein.orNull(),
            fat = fat.orNull(),
            fiber = fiber.orNull(),
            sugar = sugar.orNull(),
        )
    }

    /**
     * Die einzelnen Ernaehrungseintraege des Tages, fuer die Liste im Essen-Callout.
     * Nur zum Anzeigen: Die Summen kommen weiter aus [read].
     */
    suspend fun readMeals(date: LocalDate): List<SamsungMeal> {
        val filter = LocalTimeFilter.of(date.atStartOfDay(), date.atTime(23, 59, 59))
        val request = DataTypes.NUTRITION.readDataRequestBuilder.setLocalTimeFilter(filter).build()
        return store.readDataAsync(request).await().dataList
            .sortedBy { it.startTime }
            .map { record ->
                SamsungMeal(
                    title = record.getValue(DataType.NutritionType.TITLE)?.takeIf { it.isNotBlank() }
                        ?: "Eintrag",
                    slot = slot(record.getValue(DataType.NutritionType.MEAL_TYPE)),
                    calories = (record.getValue(DataType.NutritionType.CALORIES) as? Number)?.toDouble(),
                )
            }
    }

    private fun slot(type: DataType.NutritionType.MealType?): Slot = when (type) {
        DataType.NutritionType.MealType.BREAKFAST -> Slot.FRUEHSTUECK
        DataType.NutritionType.MealType.LUNCH -> Slot.MITTAG
        DataType.NutritionType.MealType.DINNER -> Slot.ABEND
        else -> Slot.SNACKS
    }

    /**
     * Eine Null aus Samsung Health heisst praktisch immer "noch keine Daten" und nicht
     * "gemessen und es war nichts".
     *
     * Die Regel stand frueher im [com.example.synchealth.vault.NotePatcher] und sitzt
     * jetzt hier, weil sie nur fuer diese Quelle gilt. Seit Tandoor die Naehrwerte von
     * Mittag und Abend beisteuert, ist eine fehlende Samsung-Zahl naemlich nicht mehr
     * dasselbe wie keine Zahl: Sie heisst nur, dass zu diesem Tag nichts eingetragen
     * wurde, und die Summe kann trotzdem stehen.
     */
    private fun Double?.orNull(): Double? = if (this == null || this == 0.0) null else this

    private fun Long?.orNull(): Long? = if (this == null || this == 0L) null else this

    /** Macht aus den Samsung-Callbacks eine Coroutine. */
    private suspend fun <T> AsyncSingleFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        setCallback(
            ContextCompat.getMainExecutor(context),
            { result -> cont.resume(result) },
            { error -> cont.resumeWithException(error) },
        )
    }
}
