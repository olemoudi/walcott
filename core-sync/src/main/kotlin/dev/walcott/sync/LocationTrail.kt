package dev.walcott.sync

import kotlin.math.roundToLong

/**
 * Fits a 48h location trail into one ntfy message.
 *
 * A child samples as often as every 5 minutes, so two days of fixes can be ~570 points —
 * far more than the snapshot budget allows once the app list and weekly history are also
 * on board (an oversized publish is rejected with HTTP 413 and the check-in is lost).
 *
 * Two reductions, both lossless where it matters:
 *  - **Decimation by age.** Recent movement is what a parent actually scrubs through, so
 *    the last few hours keep every fix and older stretches thin out to one point per
 *    bucket. Mock fixes are never dropped: they are the spoofing signal.
 *  - **Coordinate rounding** to ~1m, which costs nothing usable and shortens every
 *    number, so the gzipped payload compresses markedly better.
 */
object LocationTrail {

    /** Retention and publish window: the 48h of history the parent's timeline shows. */
    const val WINDOW_MS = 48 * 60 * 60 * 1000L

    /** Hard cap on published points, sized so the trail can't crowd out the rest of the snapshot. */
    const val MAX_POINTS = 120

    /** ~1.1m at the equator. Finer than any consumer GPS fix, so rounding here loses nothing. */
    private const val COORD_SCALE = 100_000.0

    /**
     * Age tiers, finest first: fixes younger than [maxAgeMs] are kept at most one per
     * [spacingMs]. A spacing of 0 keeps every fix in that tier.
     */
    private val TIERS = listOf(
        6 * 60 * 60 * 1000L to 0L, // last 6h: full detail
        24 * 60 * 60 * 1000L to 30 * 60 * 1000L, // 6-24h: one per 30 min
        WINDOW_MS to 60 * 60 * 1000L, // 24-48h: one per hour
    )

    /**
     * Thins [points] (any order) down to a publishable trail, oldest first. Drops anything
     * older than [WINDOW_MS], keeps the newest fix and every mock fix unconditionally, and
     * never returns more than [budget] points.
     */
    fun compress(points: List<LocationPoint>, nowMs: Long, budget: Int = MAX_POINTS): List<LocationPoint> {
        if (points.isEmpty()) return emptyList()
        // Newest first: the newest fix is the one the parent sees as "current", so it anchors
        // the walk and survives every reduction below.
        val recent = points.filter { nowMs - it.epochMs <= WINDOW_MS }.sortedByDescending { it.epochMs }
        if (recent.isEmpty()) return emptyList()

        val kept = ArrayList<LocationPoint>(minOf(recent.size, budget))
        var lastKeptMs = Long.MAX_VALUE
        for (point in recent) {
            val spacing = spacingFor(nowMs - point.epochMs)
            // Always keep the first (newest) point and any mock fix; otherwise enforce spacing.
            if (kept.isEmpty() || point.mock || lastKeptMs - point.epochMs >= spacing) {
                kept += point
                lastKeptMs = point.epochMs
            }
        }
        // Still over budget (very dense sampling, or a burst of mock fixes): keep the newest.
        val bounded = if (kept.size > budget) kept.subList(0, budget) else kept
        return bounded.asReversed().map { it.rounded() }
    }

    /** Minimum spacing allowed for a fix of the given [ageMs]; 0 means "keep every fix". */
    private fun spacingFor(ageMs: Long): Long =
        TIERS.firstOrNull { ageMs <= it.first }?.second ?: TIERS.last().second

    private fun LocationPoint.rounded(): LocationPoint = copy(
        lat = (lat * COORD_SCALE).roundToLong() / COORD_SCALE,
        lng = (lng * COORD_SCALE).roundToLong() / COORD_SCALE,
    )
}
