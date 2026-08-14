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
     * Replay gate for the parent's rules: a child adopts them only from a snapshot strictly
     * newer than the last one it applied, so a captured old envelope — validly signed, e.g.
     * replayed by a removed child still holding the topic + family key — can't roll rules
     * back to a laxer past state. The one exception is a message accepted through a verified
     * key rotation: it comes from a parent restored from backup, whose version counter may
     * legitimately restart lower.
     */
    fun adoptsPolicy(snapshotVersion: Long, appliedVersion: Long, rotationAdopted: Boolean): Boolean =
        rotationAdopted || snapshotVersion > appliedVersion

    /**
     * The child's replay baseline after adopting [snapshotVersion]: normally the monotonic
     * max, but a verified rotation REBASES it (possibly downward) so the restored parent's
     * subsequent, incrementally-numbered snapshots keep passing [adoptsPolicy].
     */
    fun rebasedPolicyVersion(snapshotVersion: Long, appliedVersion: Long, rotationAdopted: Boolean): Long =
        if (rotationAdopted) snapshotVersion else maxOf(appliedVersion, snapshotVersion)

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

    /**
     * How long a child's request waits for an answer before it gives up.
     *
     * Not housekeeping. The child's screen refuses to send a second request for something that
     * already has one in flight — the right call against double-asking, and a trap without this:
     * a request nobody ever answered left that app's button dead forever, and the parent's home
     * kept a question from last week pinned above everything current.
     *
     * Two days rather than a few hours: a parent who is away for a weekend is not a parent who
     * said no, and an expired request is a small loss (ask again) next to one that vanishes
     * while someone still means to answer it.
     */
    const val REQUEST_TTL_MS = 48 * 60 * 60 * 1000L

    /**
     * Whether a request created at [createdAtEpochMs] has waited too long to still be live.
     *
     * A missing timestamp (0, as legacy children send) never expires: `now - 0` is an enormous
     * age that would retire every one of them on sight, and "I can't tell how old this is" must
     * not read as "this is ancient".
     */
    fun requestExpired(createdAtEpochMs: Long, nowMs: Long): Boolean =
        createdAtEpochMs > 0 && nowMs - createdAtEpochMs > REQUEST_TTL_MS

    /** What a notification's request turned out to be by the time somebody tapped it. */
    enum class RequestState {
        /** No family on this phone has ever heard of it. */
        UNKNOWN,

        /** Still waiting for an answer — its card is on the parent's home. */
        PENDING,

        /** Somebody answered it: this parent in the app, or another parent on their own phone. */
        ANSWERED,

        /** Nobody answered in time and it retired itself (see [REQUEST_TTL_MS]). */
        EXPIRED,
    }

    /**
     * What became of [requestId], for a notification tap that finds no card to answer.
     *
     * A parent who taps a request and lands on a home with nothing on it cannot tell whether
     * they already dealt with it, the other parent did, or it simply ran out — so they go
     * looking, or ask. The three are told apart because they are different facts.
     *
     * Answered is checked FIRST and that ordering is the whole rule: an answered request goes on
     * ageing like any other, so a resolution from three days ago would read as "expired" — which
     * would tell a parent nobody replied to a child they had in fact replied to.
     */
    fun requestState(
        requestId: String,
        resolvedIds: Set<String>,
        createdAtByRequestId: Map<String, Long>,
        nowMs: Long,
    ): RequestState = when {
        requestId in resolvedIds -> RequestState.ANSWERED
        requestId !in createdAtByRequestId -> RequestState.UNKNOWN
        requestExpired(createdAtByRequestId.getValue(requestId), nowMs) -> RequestState.EXPIRED
        else -> RequestState.PENDING
    }

    /**
     * Of one child's pending time requests, the newest for each target.
     *
     * A child who asks for ten more minutes of the same app three times has asked one question
     * three times, not three questions, and the parent should be shown one card. It matters more
     * than tidiness: each card carries its own grant button, so three of them let a parent hand
     * out three separate grants for the same ask without ever noticing they were the same one.
     *
     * The child is supposed not to send duplicates in the first place, but only one of its two
     * request paths refuses to, and a child on an older build goes on sending them regardless —
     * so the parent collapses them at the point of display, where it holds for every child it
     * will ever talk to.
     *
     * Collapsing by target rather than resolving the losers is deliberate: the older requests are
     * still real, still the child's, and still expire on their own ([requestExpired]). This
     * decides what to SHOW, and nothing here answers anything on the parent's behalf.
     *
     * Order is by each target's first appearance, so an answered card doesn't reshuffle the rest.
     */
    fun newestPerTarget(requests: List<ExtraTimeRequest>): List<ExtraTimeRequest> {
        if (requests.size < 2) return requests
        val newest = LinkedHashMap<String, ExtraTimeRequest>()
        for (request in requests) {
            val held = newest[request.categoryId]
            if (held == null || supersedes(request, held)) newest[request.categoryId] = request
        }
        return newest.values.toList()
    }

    /**
     * Whether [candidate] is the later of two requests for the same target. Ties break on the id
     * — arbitrary, but stable, so the parent's list can't flip between two cards from one read to
     * the next. Two requests sharing a millisecond means a legacy child sending 0 timestamps, in
     * which case any consistent answer is as good as another.
     */
    private fun supersedes(candidate: ExtraTimeRequest, held: ExtraTimeRequest): Boolean =
        if (candidate.createdAtEpochMs != held.createdAtEpochMs) {
            candidate.createdAtEpochMs > held.createdAtEpochMs
        } else {
            candidate.requestId > held.requestId
        }

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

    /**
     * What the child should be told about the parent's answer: which request it was, whether
     * it was approved, and what was granted. Denials matter as much as approvals — without
     * this the child's request would just silently vanish.
     */
    data class ResolutionSummary(
        val approved: Boolean,
        val grantedMinutes: Int,
        /** Category for a time request; "" for a generic ask. */
        val categoryId: String,
        /** [ChildRequest.kind] for an ask; "" for a time request. */
        val kind: String,
        /** The ask's free-form text; "" for a time request. */
        val text: String,
        val resolvedAtMs: Long,
    )

    /**
     * The newest of [fresh] resolutions matched back to the child's own pending requests and
     * asks, or null when none of them concern this device. Callers show it as the "your
     * parents answered" notice.
     */
    fun latestResolutionSummary(
        fresh: List<Resolution>,
        requests: List<ExtraTimeRequest>,
        asks: List<ChildRequest>,
    ): ResolutionSummary? {
        val requestsById = requests.associateBy { it.requestId }
        val asksById = asks.associateBy { it.requestId }
        return fresh
            .mapNotNull { resolution ->
                requestsById[resolution.requestId]?.let { request ->
                    ResolutionSummary(
                        approved = resolution.approved,
                        grantedMinutes = resolution.grantedMinutes,
                        categoryId = request.categoryId,
                        kind = "",
                        // What the child asked ABOUT, which is the whole point of the answer.
                        // Left empty here for a long time while the screen that renders it read
                        // "" as "everything", so approving fifteen minutes of one app told the
                        // child they had fifteen minutes of all of them. The label is the
                        // child's own — this runs on the device that sent the request — and the
                        // expiry notice already used it (see SyncManager.expireStaleRequests).
                        text = request.targetLabel,
                        resolvedAtMs = resolution.resolvedAtEpochMs,
                    )
                } ?: asksById[resolution.requestId]?.let { ask ->
                    ResolutionSummary(
                        approved = resolution.approved,
                        grantedMinutes = 0,
                        categoryId = "",
                        kind = ask.kind,
                        text = ask.text,
                        resolvedAtMs = resolution.resolvedAtEpochMs,
                    )
                }
            }
            .maxByOrNull { it.resolvedAtMs }
    }
}
