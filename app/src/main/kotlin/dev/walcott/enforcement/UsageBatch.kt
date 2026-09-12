package dev.walcott.enforcement

import java.time.Duration

/**
 * Screen time credited in memory and written to the database in batches.
 *
 * The loop credits a foreground app every two seconds, and it used to write each credit straight
 * to Room: one transaction and one invalidation — which re-ran the day's counters query and pushed
 * a fresh map — every two seconds for as long as a limited app was open. About seven thousand
 * commits a day at four hours of use, for a counter whose every reader rounds to the minute, on
 * the cheapest phone in the family. Block counts already had this treatment; usage never did.
 *
 * Nothing about a decision changes: [overlay] puts what is still pending on top of what the
 * database holds, so the rules see every second the moment it is credited. What a process death
 * costs is at most one flush interval of counting.
 *
 * Credits are kept per DAY, so a flush after midnight files yesterday's last minutes under
 * yesterday — the day they were spent in.
 */
class UsageBatch {

    data class Credit(val pkg: String, val epochDay: Long, val seconds: Long)

    private val pending = LinkedHashMap<Pair<String, Long>, Long>()

    @Synchronized
    fun credit(pkg: String, epochDay: Long, seconds: Long) {
        if (seconds <= 0) return
        val key = pkg to epochDay
        pending[key] = (pending[key] ?: 0L) + seconds
    }

    val isEmpty: Boolean
        @Synchronized get() = pending.isEmpty()

    /** Whether anything pending belongs to a day before [epochDay]: time to write it down. */
    @Synchronized
    fun holdsDayBefore(epochDay: Long): Boolean = pending.keys.any { it.second < epochDay }

    /** [base] (what the database holds for [epochDay]) with that day's pending seconds added. */
    @Synchronized
    fun overlay(base: Map<String, Duration>, epochDay: Long): Map<String, Duration> {
        if (pending.none { it.key.second == epochDay }) return base
        val out = base.toMutableMap()
        for ((key, seconds) in pending) {
            if (key.second != epochDay) continue
            out[key.first] = (out[key.first] ?: Duration.ZERO).plusSeconds(seconds)
        }
        return out
    }

    /** Everything pending, emptied. The caller writes it; a write that fails is lost, not retried. */
    @Synchronized
    fun drain(): List<Credit> {
        val out = pending.map { (key, seconds) -> Credit(key.first, key.second, seconds) }
        pending.clear()
        return out
    }
}
