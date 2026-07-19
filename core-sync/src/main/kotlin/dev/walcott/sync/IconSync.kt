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
     * and bounded. Stable order (by first appearance) so retries are deterministic.
     */
    fun toRequest(shownPackages: List<String>, cached: Set<String>): List<String> =
        shownPackages.asSequence().distinct().filter { it !in cached }.take(MAX_REQUESTS).toList()

    /**
     * Greedily packs [candidates] (already-rendered icons the child can provide) under
     * [budget], always emitting at least one so a single oversized icon still makes progress.
     */
    fun pack(candidates: List<AppIconData>, budget: Int = MESSAGE_BUDGET): List<AppIconData> {
        val out = mutableListOf<AppIconData>()
        var size = 0
        for (icon in candidates) {
            val cost = icon.packageName.length + icon.webpB64.length + 8
            if (out.isNotEmpty() && size + cost > budget) break
            out += icon
            size += cost
        }
        return out
    }
}
