package dev.walcott.sync

/**
 * The lazy, channel-respectful app-icon exchange. Icons are needed rarely (only to render the
 * parent's app list), change almost never, and arrive in a burst at first enrollment — so:
 *  - the parent asks only for icons it will show and hasn't cached ([toRequest]), a list that
 *    empties out and then costs nothing;
 *  - a child answers a few at a time, greedily packed under a byte budget ([pack]), so the
 *    initial burst trickles across many messages instead of one oversized blast;
 *  - a lost message just leaves the icon uncached, so the parent re-asks next cycle — no acks,
 *    no bookkeeping, self-healing by construction.
 *
 * Pure, so the request/packing decisions are unit-tested.
 */
object IconSync {

    /** Cap on the parent's request list, keeping the parent message small during the burst. */
    const val MAX_REQUESTS = 24

    /** Byte budget for one icon message's payload, comfortably under ntfy's ~4 KB cap. */
    const val MESSAGE_BUDGET = 3000

    /**
     * Packages the parent should request now: shown in the app list, not cached, de-duplicated
     * and bounded. Stable order (by first appearance) so retries are deterministic. When more
     * icons are missing than fit one request, [rotation] slides the bounded window across
     * publishes — otherwise a package no live child can serve (uninstalled, ghost device)
     * would pin the same [MAX_REQUESTS] forever and starve everything after it.
     */
    fun toRequest(shownPackages: List<String>, cached: Set<String>, rotation: Int = 0): List<String> {
        val missing = shownPackages.asSequence().distinct().filter { it !in cached }.toList()
        if (missing.size <= MAX_REQUESTS) return missing
        val offset = ((rotation % missing.size) + missing.size) % missing.size
        return (missing.drop(offset) + missing.take(offset)).take(MAX_REQUESTS)
    }

    /**
     * Greedily packs [candidates] (already-rendered icons the child can provide) under
     * [budget]. An icon that ALONE exceeds the budget can never be delivered — its message
     * would be rejected by the server outright — so it is skipped rather than sent: sending
     * it anyway used to jam the queue permanently (the parent kept requesting it, every
     * answer was oversized and silently dropped, and no icon behind it ever arrived). The
     * child's encoder bounds each icon well under the budget, so the skip is a last-resort
     * guard, not the normal path.
     */
    fun pack(candidates: List<AppIconData>, budget: Int = MESSAGE_BUDGET): List<AppIconData> {
        val out = mutableListOf<AppIconData>()
        var size = 0
        for (icon in candidates) {
            val cost = icon.packageName.length + icon.webpB64.length + 8
            if (cost > budget) continue
            if (size + cost > budget) break
            out += icon
            size += cost
        }
        return out
    }
}
