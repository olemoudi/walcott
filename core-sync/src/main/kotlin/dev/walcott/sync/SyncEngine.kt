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

    /** Bonuses for this device that haven't been applied yet. */
    fun newBonuses(
        parent: ParentSnapshot,
        deviceId: String,
        alreadyApplied: Set<String>,
    ): List<Bonus> =
        parent.bonuses.filter { it.targetDeviceId == deviceId && it.id !in alreadyApplied }

    /** A "locate now" for this device newer than the last one it answered, else null. */
    fun freshLocationRequest(
        parent: ParentSnapshot,
        deviceId: String,
        appliedAtMs: Long,
    ): LocationRequest? =
        parent.locationRequests.firstOrNull { it.deviceId == deviceId && it.requestedAtMs > appliedAtMs }

    /** Upserts a request for [deviceId] (one per device) so the pending list stays bounded. */
    fun withLocationRequest(
        current: List<LocationRequest>,
        deviceId: String,
        requestedAtMs: Long,
    ): List<LocationRequest> =
        current.filterNot { it.deviceId == deviceId } + LocationRequest(deviceId, requestedAtMs)

    /**
     * Remote commands addressed to this device that it hasn't run yet, oldest first so a
     * queued pair applies in the order the parent issued them.
     */
    fun newCommands(
        parent: ParentSnapshot,
        deviceId: String,
        alreadyApplied: Set<String>,
    ): List<RemoteCommand> =
        parent.commands
            .filter { it.deviceId == deviceId && it.id !in alreadyApplied }
            .sortedBy { it.issuedAtMs }

    /**
     * Queues [command], replacing any pending command of the same action for that device
     * (re-tapping "Update now" should retry, not stack) and dropping entries older than
     * [COMMAND_TTL_MS] so a child that never comes back can't grow the parent snapshot
     * without bound.
     */
    fun withCommand(
        current: List<RemoteCommand>,
        command: RemoteCommand,
        nowMs: Long,
    ): List<RemoteCommand> =
        current.filterNot {
            (it.deviceId == command.deviceId && it.action == command.action) ||
                nowMs - it.issuedAtMs > COMMAND_TTL_MS
        } + command

    /** How long an unacknowledged remote command stays queued in the parent snapshot. */
    const val COMMAND_TTL_MS = 7 * 24 * 60 * 60 * 1000L
}
