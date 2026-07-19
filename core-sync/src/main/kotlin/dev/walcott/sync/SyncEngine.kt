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
     * Queues [command], replacing any pending command with the same action AND argument for
     * that device (re-tapping "Update now", or re-pushing the same app, should retry not
     * stack — but pushing two *different* apps must coexist, hence the [RemoteCommand.arg]
     * in the key) and dropping entries older than [COMMAND_TTL_MS] so a child that never
     * comes back can't grow the parent snapshot without bound.
     */
    fun withCommand(
        current: List<RemoteCommand>,
        command: RemoteCommand,
        nowMs: Long,
    ): List<RemoteCommand> =
        current.filterNot {
            (it.deviceId == command.deviceId && it.action == command.action && it.arg == command.arg) ||
                nowMs - it.issuedAtMs > COMMAND_TTL_MS
        } + command

    /** How long an unacknowledged remote command stays queued in the parent snapshot. */
    const val COMMAND_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /** How long a "locate now" counts as pending; after this it's moot, answered or not. */
    const val LOCATION_REQUEST_TTL_MS = 30 * 60 * 1000L

    /** Pseudo-action for a pending "locate now" in [pendingOps] (not a [RemoteAction]). */
    const val ACTION_LOCATE = "locate_now"

    /**
     * One remote operation the parent has in flight, for the pending-actions list.
     * [delivered] is true once the child has received it — it can no longer be cancelled,
     * we're just waiting for something to happen on the device (an install completing).
     */
    data class PendingOp(
        /** The [RemoteCommand.id] behind this operation; "" for a location request. */
        val id: String,
        val deviceId: String,
        /** A [RemoteAction], or [ACTION_LOCATE] for a location request. */
        val action: String,
        val arg: String,
        val sentAtMs: Long,
        val delivered: Boolean,
    )

    /**
     * Everything the parent has asked of its children that hasn't finished yet, newest first:
     * queued commands (cancellable — the child hasn't seen them), install prompts the child
     * opened but whose package hasn't appeared in its app list, and unanswered location
     * requests. Children that never check in can't complete anything, so every source is
     * TTL-bounded to keep the list from fossilizing.
     */
    fun pendingOps(
        commands: List<RemoteCommand>,
        locationRequests: List<LocationRequest>,
        children: List<ChildSnapshot>,
        nowMs: Long,
    ): List<PendingOp> {
        val queued = commands
            .filter { nowMs - it.issuedAtMs <= COMMAND_TTL_MS }
            .map { PendingOp(it.id, it.deviceId, it.action, it.arg, it.issuedAtMs, delivered = false) }

        // An install acked "opened" left the queue but isn't done until the package shows up
        // in the child's reported apps. Skip it while a re-push of the same app is queued,
        // so retrying doesn't show the operation twice.
        val awaitingInstall = children.mapNotNull { child ->
            val ack = child.lastCommand ?: return@mapNotNull null
            val waiting = ack.action == RemoteAction.INSTALL_APP &&
                ack.ok && ack.detail == RemoteAction.DETAIL_INSTALL_OPENED &&
                ack.arg.isNotBlank() &&
                nowMs - ack.completedAtMs <= COMMAND_TTL_MS &&
                child.apps.none { it.packageName == ack.arg } &&
                queued.none { it.deviceId == child.deviceId && it.arg == ack.arg }
            if (waiting) {
                PendingOp(ack.id, child.deviceId, ack.action, ack.arg, ack.completedAtMs, delivered = true)
            } else {
                null
            }
        }

        val locates = locationRequests
            .filter { request ->
                nowMs - request.requestedAtMs <= LOCATION_REQUEST_TTL_MS &&
                    children.none {
                        it.deviceId == request.deviceId && it.answeredLocationRequestMs >= request.requestedAtMs
                    }
            }
            .map { PendingOp("", it.deviceId, ACTION_LOCATE, "", it.requestedAtMs, delivered = false) }

        return (queued + awaitingInstall + locates).sortedByDescending { it.sentAtMs }
    }

    /** True while a "locate now" for [deviceId] is still unanswered (drives the locating spinner). */
    fun locatePending(ops: List<PendingOp>, deviceId: String): Boolean =
        ops.any { it.action == ACTION_LOCATE && it.deviceId == deviceId }
}
