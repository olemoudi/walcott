package dev.walcott.enforcement

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock

/**
 * Determines the foreground app by reading recent UsageStats events. Keeps the last known
 * one to cover intervals with no new events.
 */
class UsageSampler(context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = System.currentTimeMillis()
    private var lastForeground: String? = null

    /**
     * When the event query last WORKED, on the monotonic clock. Not when an event last arrived:
     * events only fire on transitions, so a child reading one app for an hour produces none at
     * all — and treating that silence as staleness would stop the very budget that hour is
     * spending. What is not survivable is the query itself failing, which is why only that is
     * timed.
     */
    private var lastGoodQueryAt = SystemClock.elapsedRealtime()

    fun currentForeground(): String? {
        val usm = usm ?: return lastForeground
        val now = System.currentTimeMillis()
        // 10s overlap so we don't miss events between queries.
        val begin = minOf(lastQuery, now - 10_000)
        // Returns nothing (not throws) without usage access, but be defensive across OEMs.
        val events = runCatching { usm.queryEvents(begin, now) }.getOrNull()
            ?: return lastKnownWhileQueriesFail()
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
        lastGoodQueryAt = SystemClock.elapsedRealtime()
        return lastForeground
    }

    /**
     * What to report while the query keeps failing: the last known app for a short grace period
     * — an OEM hiccup shouldn't interrupt a budget mid-count — and then nothing.
     *
     * "Nothing" is the honest answer, and it matters because the caller credits elapsed time to
     * whatever this returns whenever the same package comes back twice running. A sampler that
     * kept naming one app for ever would spend that app's whole daily budget while the child was
     * somewhere else entirely, or asleep.
     */
    private fun lastKnownWhileQueriesFail(): String? {
        if (SystemClock.elapsedRealtime() - lastGoodQueryAt > STALE_AFTER_MS) {
            lastForeground = null
        }
        return lastForeground
    }

    private companion object {
        /** Grace period for a failing query before the last known app stops being reported. */
        const val STALE_AFTER_MS = 60_000L
    }
}
