package dev.walcott.enforcement

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Determines the foreground app by reading recent UsageStats events. Keeps the last known
 * one to cover intervals with no new events.
 */
class UsageSampler(context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = System.currentTimeMillis()
    private var lastForeground: String? = null

    fun currentForeground(): String? {
        val now = System.currentTimeMillis()
        // 10s overlap so we don't miss events between queries.
        val begin = minOf(lastQuery, now - 10_000)
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED,
                -> lastForeground = event.packageName
            }
        }
        lastQuery = now
        return lastForeground
    }
}
