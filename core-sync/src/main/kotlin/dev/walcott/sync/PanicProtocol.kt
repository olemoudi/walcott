package dev.walcott.sync

/**
 * The child-initiated emergency release: the way out when the parent device is gone AND the
 * parent PIN is lost, so nobody can free the device the normal way (see the PIN-gated release
 * on the child's device settings).
 *
 * It is deliberately slow and loud rather than secret. The child starts a request, and for the
 * next twelve hours the device must get a notice out to the family channel every hour, twelve
 * times. A parent who is still out there sees a dozen alerts and can refuse with one tap, which
 * also locks the child out of asking again for three days. Only a request that lands all twelve
 * notices releases the device.
 *
 * **A notice counts when it was SENT, not when it was answered.** The device publishes and waits
 * for the relay to say it took the message; that receipt — and nothing else — advances the
 * counter. A notice that will not go out is retried three times ([RETRY_DELAYS_MS]) and, if it
 * still will not go out, the whole request dies wherever it had got to. That is the deal this
 * feature offers, stated exactly: twelve hours of a phone that can be reached, or nothing.
 *
 * Time is counted in SERVER seconds — the relay's own timestamp on the receipt for each notice —
 * never the local clock: moving the device clock forward is the obvious attack, and this makes
 * it useless. The local clock only decides WHEN to try, and trying early merely gets the notice
 * refused as too soon.
 *
 * Pure (no Android, no clock of its own), so every rule here is unit-tested.
 */
object PanicProtocol {

    /** How long between notices, and therefore how long the whole window is. */
    const val CHECKPOINT_INTERVAL_SEC = 60 * 60L

    /** Notices needed to earn the release: 12 x 1 h = 12 h of proven, delivered notice. */
    const val REQUIRED_CHECKPOINTS = 12

    /**
     * Waits before trying the SAME notice again, after the relay would not take it.
     *
     * Three retries and no more. A phone that cannot reach the family for four and a half
     * minutes at the moment a notice is due has stopped meeting the one condition this whole
     * feature rests on, and carrying on regardless would turn "twelve hours of a reachable
     * phone" into "twelve hours of a phone", which is a different and much weaker promise.
     */
    val RETRY_DELAYS_MS: List<Long> = listOf(30_000L, 60_000L, 3 * 60_000L)

    /**
     * The same ladder on the same scale as [intervalSec] — a real hour gives the real thirty
     * seconds, a minute and three minutes. Floored at a second so a compressed run still makes
     * three distinct attempts rather than three in the same instant.
     */
    fun retryDelaysMs(intervalSec: Long): List<Long> =
        RETRY_DELAYS_MS.map { (it * intervalSec / CHECKPOINT_INTERVAL_SEC).coerceAtLeast(1_000L) }

    /**
     * The pause after the twelfth notice: the parent's last chance to refuse.
     *
     * The twelfth notice is the loudest one — it is the alert that says the device is about to
     * let itself go — and until now it was also the last thing that happened before it did. A
     * parent reading it had already lost. Three minutes is not a new obstacle for the child (they
     * have waited twelve hours) and it is the difference between an alert and a warning.
     */
    const val FINAL_GRACE_MS = 3 * 60 * 1000L

    /** How long a parent's refusal locks the child out of asking again. */
    const val DENIAL_COOLDOWN_SEC = 3 * 24 * 60 * 60L

    /**
     * How much of its hour a notice may be early and still count.
     *
     * Proportional rather than flat, because [CHECKPOINT_INTERVAL_SEC] is scaled down to seconds
     * when this is exercised end to end: a flat minute of slack would wave through every notice
     * of a compressed run and quietly stop testing the spacing at all. One part in sixty is a
     * minute per hour — comfortably more than the skew between a phone's clock and the relay's,
     * and far less than an hour.
     */
    fun toleranceSec(intervalSec: Long): Long = (intervalSec / 60).coerceAtLeast(1)

    /**
     * The final pause, on the same scale as [intervalSec].
     *
     * Compressed with the rest of the clock so an end-to-end run takes minutes rather than
     * hours — and floored, because the pause has exactly one job and a pause too short to do it
     * is not a smaller version of the feature, it is the absence of it. A refusal has to travel
     * from a parent's tap, over the relay, to a phone that then has to notice; scaled naively, a
     * ten-second hour left half a second for all of that and the compressed run stopped testing
     * the one thing the pause exists for. The floor never applies to a real hour.
     */
    fun finalGraceMs(intervalSec: Long): Long =
        (FINAL_GRACE_MS * intervalSec / CHECKPOINT_INTERVAL_SEC).coerceAtLeast(MIN_FINAL_GRACE_MS)

    /** Floor under [finalGraceMs]; only ever reached by a compressed clock. */
    const val MIN_FINAL_GRACE_MS = 5_000L

    /**
     * Whether [request] has landed all twelve of its notices. Such a request is spent: the device
     * owes it a release once the final pause is over, and nothing can take that back except the
     * parent refusing inside those three minutes.
     */
    fun earned(request: PanicRequest): Boolean = request.checkpoints >= REQUIRED_CHECKPOINTS

    /**
     * Whether a notice the relay stamped [receiptSec] is far enough past the previous one to
     * count.
     *
     * This is the whole anti-tamper story, and it is one line: the counter advances on the
     * RELAY's clock. A child who moves the phone forward makes the alarm fire early, the notice
     * goes out early, and this says no — so the twelve hours cannot be compressed into a
     * minute of fiddling with the date.
     */
    fun banks(request: PanicRequest, receiptSec: Long, intervalSec: Long = CHECKPOINT_INTERVAL_SEC): Boolean =
        receiptSec >= request.lastCheckpointSec + intervalSec - toleranceSec(intervalSec)

    /**
     * How long to wait before trying the next notice, judged from the relay's clock.
     *
     * Used after a notice the relay refused as too soon: the local clock is evidently wrong, so
     * the wait is worked out from how much of the hour the SERVER says is still missing.
     */
    fun sendAgainInMs(
        request: PanicRequest,
        serverNowSec: Long,
        intervalSec: Long = CHECKPOINT_INTERVAL_SEC,
    ): Long {
        val dueSec = request.lastCheckpointSec + intervalSec
        return ((dueSec - serverNowSec) * 1000).coerceIn(1_000L, intervalSec * 1000)
    }

    /** [request] with the notice the relay stamped [receiptSec] recorded as landed. */
    fun withCheckpoint(request: PanicRequest, receiptSec: Long, sentAtMs: Long): PanicRequest =
        request.copy(
            lastCheckpointSec = receiptSec,
            lastNoticeAtMs = sentAtMs,
            checkpoints = request.checkpoints + 1,
        )

    /** Whether the twelfth notice's final pause is over and the device owes itself a release. */
    fun releaseDue(
        request: PanicRequest,
        nowMs: Long,
        intervalSec: Long = CHECKPOINT_INTERVAL_SEC,
    ): Boolean = earned(request) && nowMs >= request.lastNoticeAtMs + finalGraceMs(intervalSec)

    /**
     * When the device should next wake up for this request, as a local wall-clock instant.
     *
     * The LOCAL clock decides when to try, because it is the only clock an alarm understands and
     * the only one that keeps running with no network. Whether the attempt counts is a separate
     * question, answered by [banks] against the relay's clock — so an early wake-up costs one
     * refused notice and buys the child nothing.
     */
    fun nextWakeUpAtMs(
        request: PanicRequest,
        intervalSec: Long = CHECKPOINT_INTERVAL_SEC,
    ): Long = if (earned(request)) {
        request.lastNoticeAtMs + finalGraceMs(intervalSec)
    } else {
        request.lastNoticeAtMs + intervalSec * 1000
    }

    /**
     * How recently the channel must have proven itself (a message actually received) for a new
     * request to be allowed to start. Requirement one: no connectivity, no request. The child
     * publishes at least every ~30 min, so this is "the channel works right now" without
     * needing a bespoke round-trip handshake.
     *
     * The request's own anchor no longer comes from here — it comes from the relay's receipt for
     * the opening publish — so this is a pre-check that greys the button rather than a
     * correctness condition.
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
     * pay, retroactively, for connectivity the device did not have.
     */
    fun provesChannel(localNowMs: Long, serverTimeSec: Long): Boolean =
        serverTimeSec > 0 && kotlin.math.abs(localNowMs - serverTimeSec * 1000) <= MESSAGE_FRESH_MS

    /**
     * Server second until which a denial blocks new requests.
     *
     * [deniedAtServerSec] must be a real server second. Anchored on zero — which is what the
     * device's cursor reads for the first message after a relay move — this returns three days
     * after the epoch: a lockout that expired in 1970 and stops nothing at all. Callers pass the
     * newest server second they can vouch for, and the denied request's own last checkpoint is
     * always one of them.
     */
    fun cooldownUntilSec(deniedAtServerSec: Long): Long = deniedAtServerSec + DENIAL_COOLDOWN_SEC

    /** Whether a parent's refusal has finished blocking new requests (three days). */
    fun cooldownPassed(blockedUntilSec: Long, serverNowSec: Long): Boolean = serverNowSec >= blockedUntilSec

    /**
     * The whole gate on starting a request, in one testable place. This is the only door out of
     * enforcement, so it is checked in the UI (to explain why the button is grey) and again at
     * the moment the request is created — the two must not be able to disagree.
     *
     * What is NOT here any more is an anchor check. A request used to be anchored on the newest
     * server second this device happened to have seen, so a family that had just moved relay
     * anchored one to the epoch and watched it die on the next message. The anchor is now the
     * relay's receipt for the opening publish, which cannot be stale by construction — and a
     * publish that does not come back means no request was started at all.
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
        cooldownPassed(blockedUntilSec, serverNowSec)

    /** Notices still to come before the device releases itself. */
    fun remainingCheckpoints(request: PanicRequest): Int =
        (REQUIRED_CHECKPOINTS - request.checkpoints).coerceAtLeast(0)

    /**
     * Progress as 0f..1f, for the child's countdown and the parent's alert card. Based on
     * notices landed, not on elapsed time, so it can never run ahead of what was actually proven.
     */
    fun progress(request: PanicRequest): Float =
        (request.checkpoints.toFloat() / REQUIRED_CHECKPOINTS).coerceIn(0f, 1f)
}
