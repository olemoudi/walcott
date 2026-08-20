package dev.walcott.sync

import kotlin.math.roundToLong

/**
 * Fits a 48h location trail into one ntfy message.
 *
 * A child samples as often as every 5 minutes — and every 60 seconds while [LiveTracking] is
 * running — so two days of fixes can be thousands of points, far more than the snapshot budget
 * allows once the app list and weekly history are also on board (an oversized publish is
 * rejected with HTTP 413 and the check-in is lost).
 *
 * Three reductions, in this order:
 *  - **Tiers by age.** Recent movement is what a parent actually scrubs through, so the last
 *    three quarters of an hour keep every fix and older stretches thin out to one point per
 *    bucket. Mock fixes are never dropped: they are the spoofing signal.
 *  - **Reserved floors.** Each tier is guaranteed a small share of the budget that a denser
 *    neighbour cannot take. Without this, a burst of dense sampling swallows the whole budget
 *    and the older trail silently disappears — which is exactly what a four-hour live session
 *    did to the parent's two-day map, and what 5-minute sampling was already doing to the
 *    oldest twelve hours of it.
 *  - **Coordinate rounding** to ~1m, which costs nothing usable and shortens every number, so
 *    the gzipped payload compresses markedly better.
 *
 * The budget is an ambition, not a promise: [SnapshotFit] re-runs this with a smaller one when
 * the rest of the snapshot leaves less room than hoped.
 */
object LocationTrail {

    /** Retention and publish window: the 48h of history the parent's timeline shows. */
    const val WINDOW_MS = 48 * 60 * 60 * 1000L

    /** Hard cap on published points, sized so the trail can't crowd out the rest of the snapshot. */
    const val MAX_POINTS = 120

    /** ~1.1m at the equator. Finer than any consumer GPS fix, so rounding here loses nothing. */
    private const val COORD_SCALE = 100_000.0

    /**
     * One age band of the trail.
     *
     * [spacingMs] is the finest resolution the band is allowed to carry (0 = keep every fix), and
     * [floor] is the number of points it keeps even when a denser band would otherwise use them
     * all. The finest band has no floor because it is the one the remainder flows to.
     */
    private data class Tier(val maxAgeMs: Long, val spacingMs: Long, val floor: Int)

    /**
     * Age bands, finest first.
     *
     * The 45-minute band exists for live tracking: it is the stretch a parent is actually
     * watching move, so it keeps every fix, while the rest of a long session degrades to
     * five-minute resolution rather than eating the two-day context.
     */
    private val TIERS = listOf(
        Tier(45 * 60 * 1000L, 0L, 0), // last 45 min: full detail
        Tier(6 * 60 * 60 * 1000L, 5 * 60 * 1000L, 8), // 45 min-6h: one per 5 min
        Tier(24 * 60 * 60 * 1000L, 30 * 60 * 1000L, 8), // 6-24h: one per 30 min
        Tier(WINDOW_MS, 60 * 60 * 1000L, 6), // 24-48h: one per hour
    )

    /**
     * Budget below which the floors are dropped and recency wins outright.
     *
     * A squeezed message has no room for luxuries: with a dozen points to spend, "where the
     * child is now and how they got there" beats a scattering of dots across two days.
     */
    private const val FLOOR_MIN_BUDGET = 40

    /**
     * Thins [points] (any order) down to a publishable trail, oldest first. Drops anything
     * older than [WINDOW_MS], keeps the newest fix and every mock fix unconditionally, and
     * never returns more than [budget] points.
     */
    fun compress(points: List<LocationPoint>, nowMs: Long, budget: Int = MAX_POINTS): List<LocationPoint> {
        if (points.isEmpty() || budget <= 0) return emptyList()
        // Newest first: the newest fix is the one the parent sees as "current", so it anchors
        // the walk and survives every reduction below. Indices rather than the points
        // themselves from here on — two fixes sharing a millisecond are equal as values, and
        // a set of values would silently merge them.
        val recent = points.filter { nowMs - it.epochMs <= WINDOW_MS }.sortedByDescending { it.epochMs }
        if (recent.isEmpty()) return emptyList()

        val perTier = List(TIERS.size) { tier ->
            spaced(recent, recent.indices.filter { tierOf(nowMs - recent[it].epochMs) == tier }, TIERS[tier].spacingMs)
        }
        val quota = allocate(perTier.map { it.size }, budget)

        // A sorted set of indices into `recent`, so ascending order IS newest first.
        val kept = sortedSetOf<Int>()
        kept += 0 // the current position, whatever else has to go
        recent.indices.filterTo(kept) { recent[it].mock } // spoofing evidence, always
        perTier.forEachIndexed { tier, indexes -> kept += decimate(indexes, quota[tier]) }
        refill(recent, kept, nowMs, budget)

        return kept.take(budget).map { recent[it].rounded() }.reversed()
    }

    /**
     * At most [keep] of an ALREADY-compressed trail (oldest first), for [SnapshotFit] to call
     * when the rest of the snapshot leaves less room than [compress] was told to assume.
     *
     * Clock-free on purpose, and that is the whole reason it is not just another [compress] pass.
     * Re-compressing needs a "now" to date every fix against, and a trail whose newest fix is
     * older than the window — a phone that has been off, a clock that has drifted, a device that
     * simply has not managed a fix in two days — would come back EMPTY. Losing the trail is a
     * degradation; losing the current position is losing the child.
     *
     * Thinning costs resolution, never span: the newest fix, the oldest, and every mock survive.
     */
    fun thin(points: List<LocationPoint>, keep: Int): List<LocationPoint> {
        if (keep <= 0) return emptyList()
        if (points.size <= keep) return points
        val newestFirst = points.indices.reversed().toList()
        val chosen = sortedSetOf<Int>() // ascending index = oldest first, as the caller wants
        chosen += points.lastIndex // the current position, whatever else has to go
        points.indices.filterTo(chosen) { points[it].mock } // spoofing evidence, always
        chosen += decimate(newestFirst, keep) // an even spread of everything else
        // Back to budget from the OLD end, so a squeeze costs the oldest history first.
        return chosen.sortedDescending().take(keep).sorted().map { points[it] }
    }

    /**
     * Spends what the spacing left on the table.
     *
     * [spaced] is a PRIORITY, and it was being applied as a ceiling. It runs before the budget is
     * ever consulted, so a band thinned to one fix per half hour stayed at one fix per half hour
     * even when the message had room for four times as many — the budget was an upper bound the
     * trail could not reach rather than an allowance it could spend. A parent sampling every ten
     * minutes was shown 80 of their child's 187 fixes with room for 120, and on a phone that
     * sleeps through the night, 49 of 197. Nothing was over budget; the points were simply
     * dropped before anything counted them.
     *
     * So: once every band has its quota, hand the remainder back, finest band first — recent
     * movement is what a parent scrubs — and spread within each band rather than taken off its
     * newest edge, for the same reason [decimate] does everywhere else. Bands already at full
     * detail contribute nothing and the loop falls through them.
     *
     * This only ever ADDS resolution inside the span that was already being published. It cannot
     * reach past the 48h window, cannot exceed [budget], and when the rest of the snapshot leaves
     * less room than hoped [SnapshotFit] thins it straight back down.
     */
    private fun refill(recent: List<LocationPoint>, kept: java.util.SortedSet<Int>, nowMs: Long, budget: Int) {
        for (tier in TIERS.indices) {
            if (kept.size >= budget) return
            val dropped = recent.indices.filter {
                it !in kept && tierOf(nowMs - recent[it].epochMs) == tier
            }
            kept += decimate(dropped, budget - kept.size)
        }
    }

    /** Which [TIERS] band a fix of the given [ageMs] belongs to. */
    private fun tierOf(ageMs: Long): Int =
        TIERS.indexOfFirst { ageMs <= it.maxAgeMs }.takeIf { it >= 0 } ?: TIERS.lastIndex

    /** [indexes] (newest first) thinned so no two survivors are closer together than [spacingMs]. */
    private fun spaced(recent: List<LocationPoint>, indexes: List<Int>, spacingMs: Long): List<Int> {
        if (spacingMs <= 0L) return indexes
        val kept = ArrayList<Int>(indexes.size)
        var lastKeptMs = Long.MAX_VALUE
        for (index in indexes) {
            val at = recent[index].epochMs
            if (kept.isEmpty() || lastKeptMs - at >= spacingMs) {
                kept += index
                lastKeptMs = at
            }
        }
        return kept
    }

    /**
     * How many points each tier may keep.
     *
     * Floors first, so no band can be squeezed out of existence by a denser one; then the
     * remainder finest-band-first, because recent movement is what a parent is looking at.
     */
    private fun allocate(want: List<Int>, budget: Int): IntArray {
        val quota = IntArray(want.size)
        var left = budget
        if (budget >= FLOOR_MIN_BUDGET) {
            for (tier in want.indices) {
                val floor = minOf(TIERS[tier].floor, want[tier], left)
                quota[tier] = floor
                left -= floor
            }
        }
        for (tier in want.indices) {
            if (left <= 0) break
            val extra = minOf(want[tier] - quota[tier], left)
            quota[tier] += extra
            left -= extra
        }
        return quota
    }

    /**
     * At most [keep] of [indexes], spread EVENLY across the band rather than taken from its
     * newest edge — collapsing a band onto one end is the very failure the floors exist to stop,
     * and doing it inside a band would just move that failure down a level.
     */
    private fun decimate(indexes: List<Int>, keep: Int): List<Int> {
        if (keep <= 0) return emptyList()
        if (indexes.size <= keep) return indexes
        if (keep == 1) return listOf(indexes.first())
        val step = (indexes.size - 1).toDouble() / (keep - 1)
        return (0 until keep).map { indexes[(it * step).roundToLong().toInt().coerceIn(indexes.indices)] }.distinct()
    }

    private fun LocationPoint.rounded(): LocationPoint = copy(
        lat = (lat * COORD_SCALE).roundToLong() / COORD_SCALE,
        lng = (lng * COORD_SCALE).roundToLong() / COORD_SCALE,
    )
}
