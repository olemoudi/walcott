package dev.walcott.data

import java.util.concurrent.ConcurrentHashMap

/** The three things counted, and the bounds that keep the counting from growing without end. */
object BlockKinds {

    /** A DNS lookup the filter refused, counted by the domain asked for. */
    const val DOMAIN = "domain"

    /** The same refusals, counted by the app that made the lookup. */
    const val NET_APP = "net_app"

    /** An app a rule closed (its limit ran out, bedtime or a screen-free window began). */
    const val RULE_APP = "rule_app"

    /**
     * Where the tail goes once a day (or a report) has more distinct keys than it will keep.
     * The wire's bucket, so a tail folded here and a tail folded there are the same row.
     */
    const val OTHER = dev.walcott.sync.BlockReports.OTHER

    /** Lookups that could not be attributed to an app. Named, rather than dropped. */
    const val UNKNOWN_APP = "__unknown__"

    /**
     * The two device-wide rules, counted under [RULE_APP] as keys of their own. Bedtime closes
     * every app at once; charging that to forty packages would drown the app that actually ran
     * out of its own time, which is the row a parent is looking for.
     */
    const val DEVICE_BEDTIME = "__bedtime__"
    const val DEVICE_SCREEN_FREE = "__screen_free__"

    /**
     * Distinct keys kept per (day, kind) before the tail is folded into [OTHER]. A phone with the
     * tracker list on can ask for hundreds of distinct domains a day; the twenty biggest are the
     * answer to every question the stats screen asks, and the total stays exact either way
     * because the tail is folded rather than dropped.
     */
    const val MAX_KEYS_PER_DAY = 60

    /** What compaction keeps when a day goes over [MAX_KEYS_PER_DAY]. */
    const val KEEP_ON_COMPACT = 30
}

/**
 * The in-memory side of block counting: called from the DNS packet loop and the enforcement
 * loop, flushed to the database on a timer and before every publish.
 *
 * A database write per blocked lookup is not an option — with a tracker list on, a single app
 * opening can produce dozens in a second, and this sits on the path of every DNS query the
 * device makes. So the hot path is a map increment, and durability is bought once a minute.
 * Losing the last few counts to a process death is the right trade: these are statistics, and
 * the alternative is a phone that writes to disk every time an ad server is refused.
 *
 * Bounded here too: beyond [BlockKinds.MAX_KEYS_PER_DAY] distinct keys between flushes the tail
 * folds into [BlockKinds.OTHER], so an unusual burst cannot grow this map without limit either.
 */
object BlockCounters {

    private val pending = ConcurrentHashMap<Pair<String, String>, Long>()

    /** A DNS lookup was refused: counted once against the domain and once against the app. */
    fun recordNetworkBlock(domain: String, packageName: String?) {
        val host = dev.walcott.rules.DomainMatcher.normalize(domain)
        if (host.isEmpty()) return
        bump(BlockKinds.DOMAIN, host)
        bump(BlockKinds.NET_APP, packageName?.takeIf { it.isNotBlank() } ?: BlockKinds.UNKNOWN_APP)
    }

    /** A rule closed an app on this device. */
    fun recordRuleBlock(packageName: String) {
        if (packageName.isBlank()) return
        bump(BlockKinds.RULE_APP, packageName)
    }

    private fun bump(kind: String, key: String) {
        val slot = kind to key
        // Only the fold decision needs the size, and only when the key is new: an existing key
        // costs one atomic merge whatever the map holds.
        if (!pending.containsKey(slot) && pending.count { it.key.first == kind } >= BlockKinds.MAX_KEYS_PER_DAY) {
            pending.merge(kind to BlockKinds.OTHER, 1L, Long::plus)
            return
        }
        pending.merge(slot, 1L, Long::plus)
    }

    /**
     * Takes everything counted so far, leaving the accumulator empty. The caller writes it; if
     * that write fails the counts are gone, which is why it is the caller's job to make the
     * write the very next thing it does.
     */
    fun drain(): Map<Pair<String, String>, Long> {
        if (pending.isEmpty()) return emptyMap()
        val snapshot = HashMap<Pair<String, String>, Long>(pending.size)
        // Removing per key rather than clearing: a concurrent increment between the read and the
        // clear would otherwise be dropped, and this runs alongside a live packet loop.
        for (key in pending.keys.toList()) {
            pending.remove(key)?.let { snapshot[key] = it }
        }
        return snapshot
    }

    /** Whether anything is waiting to be written (the flush timer asks before touching the DB). */
    fun isEmpty(): Boolean = pending.isEmpty()
}
