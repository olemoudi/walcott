package dev.walcott.sync

/**
 * Convergence by snapshots, not history: every device owns its slice of state and re-emits
 * a versioned snapshot. Merging is last-write-wins by the sender's version, so a lost
 * message or an offline device heals on the next emission.
 */
object SyncEngine {

    /** Parent side: keep the newest snapshot per child device. */
    fun mergeChild(
        current: Map<String, ChildSnapshot>,
        incoming: ChildSnapshot,
    ): Map<String, ChildSnapshot> {
        val existing = current[incoming.deviceId]
        return if (existing == null || incoming.version >= existing.version) {
            current + (incoming.deviceId to incoming)
        } else {
            current
        }
    }

    /** Child side: keep the newest parent snapshot. */
    fun mergeParent(current: ParentSnapshot?, incoming: ParentSnapshot): ParentSnapshot =
        if (current == null || incoming.version >= current.version) incoming else current

    /**
     * Resolutions a child hasn't applied yet: those addressed to its pending requests. The
     * caller tracks which requestIds are already applied to keep grants idempotent.
     */
    fun newResolutions(
        parent: ParentSnapshot,
        pendingRequestIds: Set<String>,
        alreadyApplied: Set<String>,
    ): List<Resolution> =
        parent.resolutions.filter { it.requestId in pendingRequestIds && it.requestId !in alreadyApplied }
}
