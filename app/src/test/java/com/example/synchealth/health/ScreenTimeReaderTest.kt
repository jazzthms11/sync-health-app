package com.example.synchealth.health

import android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND
import android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueft nur die Aggregation, nicht den UsageStatsManager selbst: Der laeuft nicht in
 * einem lokalen JVM-Test.
 */
class ScreenTimeReaderTest {

    @Test
    fun `ohne Ereignisse gibt es nichts zu summieren`() {
        assertEquals(0L, ScreenTimeReader.foregroundMillis(emptyList(), windowEnd = 1_000))
    }

    @Test
    fun `ein Paar zaehlt die Zeit dazwischen`() {
        val transitions = listOf(MOVE_TO_FOREGROUND to 1_000L, MOVE_TO_BACKGROUND to 4_000L)

        assertEquals(3_000L, ScreenTimeReader.foregroundMillis(transitions, windowEnd = 10_000))
    }

    @Test
    fun `mehrere Paare werden aufsummiert`() {
        val transitions = listOf(
            MOVE_TO_FOREGROUND to 0L,
            MOVE_TO_BACKGROUND to 500L,
            MOVE_TO_FOREGROUND to 2_000L,
            MOVE_TO_BACKGROUND to 2_800L,
        )

        assertEquals(1_300L, ScreenTimeReader.foregroundMillis(transitions, windowEnd = 10_000))
    }

    @Test
    fun `eine offene Vordergrundphase zaehlt bis zum Fensterende`() {
        val transitions = listOf(MOVE_TO_FOREGROUND to 8_000L)

        assertEquals(2_000L, ScreenTimeReader.foregroundMillis(transitions, windowEnd = 10_000))
    }

    @Test
    fun `ein Hintergrund-Ereignis ohne vorheriges Vordergrund-Ereignis wird ignoriert`() {
        val transitions = listOf(MOVE_TO_BACKGROUND to 500L)

        assertEquals(0L, ScreenTimeReader.foregroundMillis(transitions, windowEnd = 10_000))
    }

    @Test
    fun `ein zweites Vordergrund-Ereignis ohne Hintergrund dazwischen resettet nicht`() {
        val transitions = listOf(
            MOVE_TO_FOREGROUND to 1_000L,
            MOVE_TO_FOREGROUND to 3_000L,
            MOVE_TO_BACKGROUND to 5_000L,
        )

        assertEquals(4_000L, ScreenTimeReader.foregroundMillis(transitions, windowEnd = 10_000))
    }
}
