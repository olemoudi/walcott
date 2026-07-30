package dev.walcott.sync

import kotlinx.serialization.Serializable

/**
 * A batch of domains as the parent is receiving it: the slices that have arrived so far, plus who
 * sent them. Incomplete until every slice is in, because half a selection is not a smaller
 * request — it is the wrong one, and acting on it would block exactly the domains that happened
 * to make it through.
 */
@Serializable
data class DomainInboxEntry(
    val batchId: String,
    val deviceId: String,
    val childId: String = "",
    /** Display name at arrival; the UI prefers the current registry name by [childId]. */
    val childName: String = "",
    val packageName: String = "",
    val label: String = "",
    val slices: List<DomainChunk> = emptyList(),
    val firstSeenMs: Long = 0,
) {
    /** The whole selection, or null while a slice is still missing. */
    fun domains(): List<String>? = DomainDelivery.assemble(slices)

    val complete: Boolean get() = domains() != null

    /** How many slices are still to come, for the "still arriving" line. */
    val missing: Int get() = (slices.firstOrNull()?.chunks ?: 0) - slices.size
}

/**
 * The parent's side of [DomainDelivery]: fold arriving slices into the inbox, and remember what
 * has been acknowledged and what has been dealt with.
 *
 * Pure, and separate from [SyncManager], because the two rules that matter here are easy to get
 * wrong and impossible to see: a slice must be acknowledged even when its batch was already
 * handled (or the child resends until it gives up), and a handled batch must never come back
 * (or "discard" only hides the card until the next nudge).
 */
object DomainInbox {

    /** Batches kept at once. A parent works through these in the moment; they don't accumulate. */
    const val MAX_ENTRIES = 20

    /**
     * Acknowledgements carried in a parent snapshot. Generous next to the slices any one batch
     * has, so a confirmation is still travelling long after the child needed it.
     */
    const val MAX_ACKS = 120

    /** Handled batch ids remembered, so a resent slice can't resurrect a discarded card. */
    const val MAX_HANDLED = 60

    /**
     * [inbox] with [incoming] folded in. Slices for a batch in [handled] are dropped: the parent
     * already answered it, and the child is only still sending because an ack hasn't landed yet.
     */
    fun merge(
        inbox: List<DomainInboxEntry>,
        incoming: List<DomainChunk>,
        deviceId: String,
        childId: String,
        childName: String,
        handled: Collection<String>,
        nowMs: Long,
    ): List<DomainInboxEntry> {
        val fresh = incoming.filterNot { it.batchId in handled }
        if (fresh.isEmpty()) return inbox
        val byBatch = inbox.associateBy { it.batchId }.toMutableMap()
        for (chunk in fresh) {
            val existing = byBatch[chunk.batchId]
            val entry = existing ?: DomainInboxEntry(
                batchId = chunk.batchId,
                deviceId = deviceId,
                childId = childId,
                childName = childName,
                packageName = chunk.packageName,
                label = chunk.label,
                firstSeenMs = nowMs,
            )
            // Replacing by index makes a redelivered slice a no-op rather than a duplicate.
            val slices = (entry.slices.filterNot { it.index == chunk.index } + chunk).sortedBy { it.index }
            byBatch[chunk.batchId] = entry.copy(slices = slices)
        }
        // Newest last, so the cap drops the batches nobody ever completed.
        return byBatch.values.sortedBy { it.firstSeenMs }.takeLast(MAX_ENTRIES)
    }

    /**
     * [acks] plus a confirmation for every slice in [incoming] — including slices of batches
     * already handled, which is the only thing that lets a child stop resending after the parent
     * has moved on.
     */
    fun withAcks(acks: List<String>, incoming: List<DomainChunk>): List<String> {
        if (incoming.isEmpty()) return acks
        val ids = incoming.map { DomainDelivery.ackId(it.batchId, it.index) }
        return (acks - ids.toSet() + ids).takeLast(MAX_ACKS)
    }

    /** [handled] with [batchId] recorded, bounded. */
    fun withHandled(handled: List<String>, batchId: String): List<String> =
        (handled - batchId + batchId).takeLast(MAX_HANDLED)
}
