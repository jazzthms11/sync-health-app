package com.example.synchealth.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bildschirmzeit ueber Androids UsageStatsManager, nicht ueber Samsung Health: Samsung
 * liefert diesen Wert nicht, siehe [com.example.synchealth.vault.NotePatcher].
 *
 * Der Zugriff ist eine Spezialberechtigung ("App-Nutzung"), die sich nicht ueber den
 * normalen Rechte-Dialog einholen laesst, nur ueber die Systemeinstellungen.
 */
class ScreenTimeReader(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Ohne Zugriff gibt es nichts zu melden, keine Null, die die Notiz ueberschreibt. */
    fun readHours(date: LocalDate): Double {
        if (!hasAccess()) return 0.0

        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            System.currentTimeMillis(),
        )

        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStats.queryEvents(start, end)
        val event = UsageEvents.Event()
        val transitions = mutableListOf<Pair<Int, Long>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                transitions += event.eventType to event.timeStamp
            }
        }
        return foregroundMillis(transitions, end) / 3_600_000.0
    }

    companion object {
        /**
         * Summiert alle Vordergrund-Phasen aus [transitions] bis [windowEnd]. Das System
         * zeigt zu jedem Zeitpunkt hoechstens eine App im Vordergrund, die Summe ueber
         * alle Apps ist also dieselbe Zahl wie die tatsaechliche Bildschirmzeit.
         *
         * Eigene, reine Funktion, damit die Aggregation ohne UsageStatsManager auf einem
         * Geraet pruefbar ist.
         */
        fun foregroundMillis(transitions: List<Pair<Int, Long>>, windowEnd: Long): Long {
            var total = 0L
            var since: Long? = null
            for ((type, timestamp) in transitions) {
                when (type) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> if (since == null) since = timestamp
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        since?.let { total += timestamp - it }
                        since = null
                    }
                }
            }
            // Laeuft eine App beim Fensterende noch, zaehlt das Stueck bis dahin mit.
            since?.let { total += windowEnd - it }
            return total
        }
    }
}
