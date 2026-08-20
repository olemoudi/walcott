package dev.walcott.sync

/**
 * The child-initiated emergency release: the way out when the parent device is gone AND the
 * parent PIN is lost, so nobody can free the device the normal way (see the PIN-gated release
 * on the child's device settings).
 *
 * It is deliberately slow and loud rather than secret: the child starts a request, and for the
 * next 24 hours the device must keep proving it can reach the family channel, sending the
 * parent a fresh notice every two hours. A parent who is still alive out there sees a dozen
 * alerts and can refuse with one tap, which also locks the child out of asking again for three
 * days. Only a request that survives the full 24 hours releases the device.
 *
 * Time is counted in SERVER seconds (the sync server's timestamp on every message), never the
 * local clock: moving the device clock forward is the obvious attack and this makes it useless.
 * The local clock is only ever used to EXPIRE a request early, which can only hurt the child.
 *
 * Pure (no Android, no clock of its own), so every rule here is unit-tested.
 */
object PanicProtocol {

    /** How often the device must prove it still has the channel, and notify the parent again. */
    const val CHECKPOINT_INTERVAL_SEC = 2 * 60 * 60L

    /**
     * How late a checkpoint may be before the request dies. A connectivity failure at the moment
     * a notice is due cancels the process, but the device sleeps: Doze defers the child's
     * check-in (~30 min nominal) and the ntfy socket reconnects on its own schedule, so a hard
     * deadline would kill honest requests. An hour tolerates that and nothing else — airplane
     * mode or a powered-off phone still ends the process.
     */
    const val CHECKPOINT_GRACE_SEC = 60 * 60L

    /** Checkpoints needed to earn the release: 12 × 2 h = 24 h of proven connectivity. */
    const val REQUIRED_CHECKPOINTS = 12

    /** How long a parent's refusal locks the child out of asking again. */
    const val DENIAL_COOLDOWN_SEC = 3 * 24 * 60 * 60L

    /** What the device must do about an active request right now. */
    enum class Step {
        /** Nothing due yet. */
        WAIT,

        /** A notice is due and the channel is proven: record it and tell the parent. */
        CHECKPOINT,

        /** The last checkpoint: the 24 hours are complete, release the device. */
        RELEASE,

        /** The channel failed when a notice was due: the request is void. */
        EXPIRED,
    }

    /** Server second at which the next notice becomes due. */
    fun dueSec(request: PanicRequest): Long = request.lastCheckpointSec + CHECKPOINT_INTERVAL_SEC

    /** Server second past which a missed notice voids the request. */
    fun deadlineSec(request: PanicRequest): Long = dueSec(request) + CHECKPOINT_GRACE_SEC

    /**
     * Whether [request] has already served its full 24 hours. Such a request is spent: the
     * device owes it a release and nothing else, and no later connectivity failure can take
     * that back — see [evaluate].
     */
    fun earned(request: PanicRequest): Boolean = request.checkpoints >= REQUIRED_CHECKPOINTS

    /**
     * The step for [request] at [serverNowSec] — the server timestamp of a message that just
     * arrived, which is itself the proof that the channel works right now.
     *
     * [earned] is checked FIRST, ahead of the deadline: the release is recorded (and published,
     * so the parent has the record) before it is carried out, and carrying it out can be
     * interrupted — a process death, a failed step. A request that comes back with its twelve
     * notices already banked has bought the release outright; expiring it there would make the
     * child serve another 24 hours for a countdown they had already finished.
     */
    fun evaluate(request: PanicRequest, serverNowSec: Long): Step = when {
        earned(request) -> Step.RELEASE
        serverNowSec > deadlineSec(request) -> Step.EXPIRED
        serverNowSec < dueSec(request) -> Step.WAIT
        request.checkpoints + 1 >= REQUIRED_CHECKPOINTS -> Step.RELEASE
        else -> Step.CHECKPOINT
    }

    /** [request] with the notice due at [serverNowSec] recorded as sent. */
    fun withCheckpoint(request: PanicRequest, serverNowSec: Long): PanicRequest =
        request.copy(lastCheckpointSec = serverNowSec, checkpoints = request.checkpoints + 1)

    /**
     * Whether a request that hasn't heard from the channel for [msSinceChannelOk] is already
     * void, judged on the LOCAL clock. Server time can't detect this case — the whole point is
     * that no message is arriving — and being generous here would only let a child sit offline
     * waiting out the clock. A local clock moved forward merely kills the request sooner.
     */
    fun expiredOffline(msSinceChannelOk: Long): Boolean =
        msSinceChannelOk > (CHECKPOINT_INTERVAL_SEC + CHECKPOINT_GRACE_SEC) * 1000

    /**
     * How recently the channel must have proven itself (a message actually received) for a new
     * request to be allowed to start. Requirement one: no connectivity, no request. The child
     * publishes at least every ~30 min, so this is "the channel works right now" without
     * needing a bespoke round-trip handshake. It also keeps the request's server-time anchor
     * fresh — starting from a stale anchor would make the first notice due in the past.
     */
    const val START_CHANNEL_FRESH_MS = 30 * 60 * 1000L

    /** Whether the channel has proven itself recently enough to start a request. */
    fun channelProven(msSinceChannelOk: Long): Boolean = msSinceChannelOk <= START_CHANNEL_FRESH_MS

    /**
     * How far a message's server timestamp may sit from the local clock and still count as
     * proof that the channel is working *now*.
     */
    const val MESSAGE_FRESH_MS = 15 * 60 * 1000L

    /**
     * Whether a message proves live connectivity. This is the difference between a device that
     * was reachable and one that merely came back: on reconnect the transport REPLAYS
     * everything published while the socket was down, and those old timestamps would otherwise
     * pay, retroactively, for exactly the two-hourly notices the device failed to send. Only a
     * message whose server timestamp matches the local clock is happening right now.
     *
     * Skewing the local clock to make a replayed message look live doesn't help: the checkpoints
     * still have to march forward two hours of SERVER time each, so a stale message can't be
     * reused, and the clock move is itself reported to the parent ([ClockGuard]).
     */
    fun provesChannel(localNowMs: Long, serverTimeSec: Long): Boolean =
        serverTimeSec > 0 && kotlin.math.abs(localNowMs - serverTimeSec * 1000) <= MESSAGE_FRESH_MS

    /**
     * Server second until which a denial blocks new requests.
     *
     * [deniedAtServerSec] must be a real server second. Anchored on zero — which is what the
     * device's cursor reads for the first message after a relay move, and what it reads for the
     * whole message being handled, since the cursor only advances afterwards — this returns three
     * days after the epoch: a lockout that expired in 1970 and stops nothing at all. Callers pass
     * the newest server second they can vouch for, and the denied request's own last checkpoint
     * is always one of them.
     */
    fun cooldownUntilSec(deniedAtServerSec: Long): Long = deniedAtServerSec + DENIAL_COOLDOWN_SEC

    /** Whether a parent's refusal has finished blocking new requests (three days). */
    fun cooldownPassed(blockedUntilSec: Long, serverNowSec: Long): Boolean = serverNowSec >= blockedUntilSec

    /**
     * Whether this device knows what time the server thinks it is.
     *
     * The cursor is reset to zero whenever the family moves relay: the old server's timestamps
     * mean nothing on the new one. Until the first message lands there, this device has no server
     * clock at all — and [channelProven] can still say yes, because the last proof of the channel
     * predates the move by less than half an hour.
     *
     * A request started in that window is anchored to the epoch, so its deadline falls in January
     * 1970 and the very next message [evaluate]s it as [Step.EXPIRED]. The child spends their one
     * request on a countdown that was already over, which is the cruellest possible way to fail.
     */
    fun anchored(serverNowSec: Long): Boolean = serverNowSec > 0

    /**
     * The whole gate on starting a request, in one testable place. This is the only door out of
     * enforcement, so it is checked in the UI (to explain why the button is grey) and again at
     * the moment the request is created — the two must not be able to disagree.
     *
     * [parentSupported] is the least obvious condition: a parent build too old to understand the
     * field ignores it silently, which would turn a loud, refusable request into a quiet escape
     * hatch. A request nobody can see is not the deal this feature offers, so it isn't allowed.
     */
    fun mayStart(
        hasActiveRequest: Boolean,
        parentSupported: Boolean,
        msSinceChannelOk: Long,
        blockedUntilSec: Long,
        serverNowSec: Long,
    ): Boolean = !hasActiveRequest && parentSupported && channelProven(msSinceChannelOk) &&
        anchored(serverNowSec) && cooldownPassed(blockedUntilSec, serverNowSec)

    /** Notices still to come before the device releases itself. */
    fun remainingCheckpoints(request: PanicRequest): Int =
        (REQUIRED_CHECKPOINTS - request.checkpoints).coerceAtLeast(0)

    /**
     * Progress as 0f..1f, for the child's countdown and the parent's alert card. Based on
     * checkpoints, not on elapsed time, so it can never run ahead of what was actually proven.
     */
    fun progress(request: PanicRequest): Float =
        (request.checkpoints.toFloat() / REQUIRED_CHECKPOINTS).coerceIn(0f, 1f)
}
