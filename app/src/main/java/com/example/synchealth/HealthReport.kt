package com.example.synchealth

/**
 * Ein Tag aus Samsung Health. Wasser steht in Millilitern, Schlaf in Stunden,
 * alles andere in der Einheit, die Samsung liefert.
 */
data class HealthReport(
    val steps: Long = 0,
    val calories: Double = 0.0,
    val water: Double = 0.0,
    val sleep: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
    /** Stunden, aus Androids UsageStatsManager statt aus Samsung Health. */
    val screenTime: Double = 0.0,
)
