package dev.walcott.sync

/**
 * Convergence by snapshots, not history: every device owns its slice of state and re-emits
 * a versioned snapshot. Merging is last-write-wins by the sender's version, so a lost
 * message or an offline device heals on the next emission.
 */
object SyncEngine {

    /**
     * How far a child's publish counter may move with no time having passed.
     *
     * It is a counter of publishes, incremented by one, so how far it can honestly move is a
     * matter of how long the parent has not been hearing it: see [maxChildVersionJump]. The bound
     * matters because child messages are not signed — anyone holding the family key (a sibling; a
     * photograph of the pairing QR) can publish as any deviceId — and a single snapshot at
     * [Long.MAX_VALUE] would make every genuine one from that phone stale for ever: the parent's
     * view of that child frozen on the forgery, usage, location, requests and the emergency-release
     * countdown included, with no way back short of removing the device.
     *
     * A speed bump, not a lock. A forger who picks a number just under the bound freezes the row
     * just as well, for as long as the real phone takes to publish past it. What closes that is a
     * key per device, which is a protocol change of its own.
     */
    const val MAX_CHILD_VERSION_JUMP = 10_000L

    /**
     * Faster than any child sustains: the quickest cadence anywhere is a domain delivery nudging
     * every twenty seconds, for minutes. A ceiling well above reality is the point — a false
     * refusal here freezes a real child's row exactly as the attack would.
     */
    private const val MAX_CHILD_PUBLISHES_PER_SECOND = 1L

    /**
     * How far a child's counter can honestly have moved when the parent last heard from that
     * phone [sinceLastHeardMs] ago. A parent phone that was off for a month has missed a month of
     * publishes, and must take the next one.
     */
    fun maxChildVersionJump(sinceLastHeardMs: Long): Long =
        MAX_CHILD_VERSION_JUMP + (sinceLastHeardMs.coerceAtLeast(0) / 1000) * MAX_CHILD_PUBLISHES_PER_SECOND

    /**
     * Parent side: keep the newest snapshot per child device, unless the newness is impossible.
     *
     * A refused jump is dropped rather than clamped: the snapshot's CONTENTS are as untrustworthy
     * as its version, and the phone's next honest publish is one counter step away.
     */
    fun mergeChild(
        current: Map<String, ChildSnapshot>,
        incoming: ChildSnapshot,
        sinceLastHeardMs: Long = 0L,
    ): Map<String, ChildSnapshot> {
        val existing = current[incoming.deviceId]
            ?: return current + (incoming.deviceId to incoming)
        // A difference rather than a sum: both are non-negative, so this cannot overflow, while
        // existing + jump can for a row a pre-bound forgery already pinned near Long.MAX_VALUE.
        if (incoming.version - existing.version > maxChildVersionJump(sinceLastHeardMs)) return current
        return if (incoming.version >= existing.version) {
            current + (incoming.deviceId to incoming)
        } else {
            current
        }
    }

    /**
     * How far a restore jumps the version counter past the backup's, so a restored parent
     * outranks whatever the lost phone published after the file was written (see
     * `SyncManager.restoreBackup`). It is also the signature of a takeover: no other event
     * moves the counter by anything like this much.
     */
    const val RESTORE_VERSION_LEAP = 1_000_000L

    /**
     * Parent side: has ANOTHER phone taken this family over?
     *
     * A parent hears its own snapshots come back off the relay, and — after a reconnect —
     * hears its older ones replayed out of the backlog too, so "a parent snapshot arrived"
     * says nothing on its own. Two things together do: it carries a different phone's
     * [ParentSnapshot.parentInstanceId], and its version is a whole [RESTORE_VERSION_LEAP]
     * above ours, which only a restore produces. Anything smaller is us, or an older build of
     * us, and must never be read as a takeover — the answer to one is to stop trusting our own
     * edits, and that is not a conclusion to reach on a coincidence.
     *
     * [ownInstanceId] is blank on a scope that has not published under a build that has this
     * field; the version rule alone carries the decision there.
     */
    fun parentSuperseded(ownVersion: Long, ownInstanceId: String, incoming: ParentSnapshot): Boolean {
        if (ownInstanceId.isNotBlank() && incoming.parentInstanceId == ownInstanceId) return false
        return incoming.version >= ownVersion + RESTORE_VERSION_LEAP
    }

    /**
     * The version this phone must publish at to take a family back from the phone in
     * [seenVersion]: above it by another leap, so the children — which gate on version
     * monotonicity — adopt this phone's rules again and keep doing so.
     */
    fun takeoverVersion(ownVersion: Long, seenVersion: Long): Long =
        maxOf(ownVersion, seenVersion) + RESTORE_VERSION_LEAP

    /**
     * One device per child: the phone that spoke most recently.
     *
     * A child's phone that is factory-reset and enrolled again comes back with a NEW deviceId —
     * `pairAsChild` keeps the old one only when the app's data survived, and the supported way to
     * enrol is a wiped phone. The rows are keyed by deviceId, so the replacement is appended and
     * the dead phone stays first in the list; every screen that reaches for a child's phone takes
     * the first match by childId, so a parent would read the stolen phone's battery and send
     * "locate now", a pause, a ring and "free this phone" to it, with nothing on any screen
     * suggesting there was a second one.
     *
     * Devices with no childId are registered to nobody — orphans and pre-childId builds — and are
     * all kept: there is no child to be the current phone OF.
     *
     * Usage history is not affected either way: it is filed under the childId when there is one
     * (see [UsageLedger.keyOf]), so a replacement inherits it.
     */
    fun currentDevices(
        children: List<ChildSnapshot>,
        lastSeen: Map<String, Long>,
    ): List<ChildSnapshot> {
        val superseded = supersededDevices(children, lastSeen).map { it.deviceId }.toSet()
        return children.filterNot { it.deviceId in superseded }
    }

    /**
     * The devices [currentDevices] drops: a child's older phones, newest-first, so the parent can
     * be shown what to retire. Empty for every family that has never replaced a phone.
     */
    fun supersededDevices(
        children: List<ChildSnapshot>,
        lastSeen: Map<String, Long>,
    ): List<ChildSnapshot> =
        children.filter { it.childId.isNotBlank() }
            .groupBy { it.childId }
            .filterValues { it.size > 1 }
            .flatMap { (_, devices) ->
                // Heard from most recently wins. A phone that has never been heard from at all
                // sorts to the bottom, and the version breaks a tie between two that arrived in
                // the same millisecond.
                val ranked = devices.sortedWith(
                    compareByDescending<ChildSnapshot> { lastSeen[it.deviceId] ?: 0L }
                        .thenByDescending { it.version },
                )
                ranked.drop(1)
            }

    /** Child side: keep the newest parent snapshot. */
    fun mergeParent(current: ParentSnapshot?, incoming: ParentSnapshot): ParentSnapshot =
        if (current == null || incoming.version >= current.version) incoming else current

    /**
     * The first child build that understands a one-off change to today — a pause, or tonight's
     * bedtime moved (`PolicySettings.todayException`).
     *
     * An older child decodes the policy with `ignoreUnknownKeys` and simply does not pause, with
     * nothing on either phone to say so. The rules it applies stay correct, which is why this is a
     * note beside the button rather than a refusal: the parent is told BEFORE tapping that this
     * phone needs its update first, the same gate `RemoteAction.canRelease` puts in front of an
     * action an old child would silently ignore.
     */
    const val TODAY_EXCEPTION_MIN_CHILD_VERSION = 124

    /** Whether a child reporting [childAppVersionCode] applies today's exceptions at all. */
    fun appliesTodayException(childAppVersionCode: Int): Boolean =
        childAppVersionCode >= TODAY_EXCEPTION_MIN_CHILD_VERSION

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

    /**
     * Bonuses for this device that haven't been applied yet.
     *
     * With [todayEpochDay], only bonuses granted for today or yesterday (the parent's day can
     * differ from the child's across a midnight or a time zone): a bonus is minutes for the day
     * it was given, and one from last month arriving in a replayed envelope — its id long gone
     * from the applied ledger — is minutes nobody granted.
     */
    fun newBonuses(
        parent: ParentSnapshot,
        deviceId: String,
        alreadyApplied: Set<String>,
        todayEpochDay: Long? = null,
    ): List<Bonus> =
        parent.bonuses.filter {
            it.targetDeviceId == deviceId && it.id !in alreadyApplied &&
                (todayEpochDay == null || it.epochDay >= todayEpochDay - 1)
        }

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
     *
     * [marks] is the newest `issuedAtMs` already applied per action (see [markApplied]): a
     * command older than the newest one of its kind this device has run is a replay, whatever
     * its id — the applied ledger is bounded, and a captured envelope can be published long
     * after the ids in it have fallen off the end. Same-instant commands of one action (a
     * catch-up queues two at once) still pass on their ids.
     */
    fun newCommands(
        parent: ParentSnapshot,
        deviceId: String,
        alreadyApplied: Set<String>,
        marks: Map<String, Long> = emptyMap(),
    ): List<RemoteCommand> =
        parent.commands
            .filter {
                it.deviceId == deviceId && it.id !in alreadyApplied &&
                    it.issuedAtMs >= (marks[it.action] ?: Long.MIN_VALUE)
            }
            .sortedBy { it.issuedAtMs }

    /** [marks] with [command] recorded as the newest of its action this device has applied. */
    fun markApplied(marks: Map<String, Long>, command: RemoteCommand): Map<String, Long> =
        marks + (command.action to maxOf(marks[command.action] ?: Long.MIN_VALUE, command.issuedAtMs))

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

    /**
     * Whether the parents' last answer has stopped being news.
     *
     * The child's home keeps that card until they tap OK, which is right for the minute it
     * arrives and wrong by the next morning: what an approval announces is minutes of TODAY's
     * extra time, and those die at the child's midnight with the rest of the day's allowance.
     * A card still reading "Approved! +20 min of YouTube" over an allowance that no longer
     * exists is the app lying about the one thing the child came to check. Denials and bonuses
     * age out the same way and for the same reason: they are answers to today's question.
     *
     * The child's own calendar day, not a fixed number of hours: it is their allowance, and it
     * turns over on their clock. A missing timestamp (0) never expires — the same rule as
     * [requestExpired], for the same reason.
     */
    fun noticeExpired(atMs: Long, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean {
        if (atMs <= 0) return false
        fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return dayOf(atMs) != dayOf(nowMs)
    }

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
