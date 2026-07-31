package dev.walcott.sync

import kotlin.math.abs

/**
 * Clock-tamper detection from the sync server's message timestamps. If the parent didn't
 * enable the date/time device restriction, a child can move the device clock and walk past
 * bedtime and daily budgets; every ntfy message already carries the server's clock, so the
 * child can notice the skew for free and report it.
 *
 * The subtlety is replay: after a reconnect the transport re-delivers messages published
 * while the socket was down, and those carry OLD server timestamps — a large positive skew
 * (local ahead of server) on an arbitrary message is indistinguishable from replay. Hence
 * [measuredSkew]'s asymmetry: a negative skew is replay-proof in ANY message (the server
 * already saw a later time than the local clock shows, so the clock was moved back), while a
 * positive skew is only ever read off an echo of one of OUR publishes, PAIRED with it by
 * nonce (see [skewFromOwnEcho]).
 *
 * The pairing is the whole guard, and it was learned the hard way: "this snapshot carries my
 * device id" is not the same as "this is the publish I just made". A device that spends 21
 * minutes off the socket (Doze, a tunnel, a dead Wi-Fi) keeps publishing over HTTP, and on
 * reconnect the server hands its own 21-minute-old message back — same device id, timestamp
 * from before the outage. Read as skew, that is "the clock is 21 minutes ahead": a false
 * tamper alert to the parent AND, because the rules fail closed on an untrusted clock, every
 * app on the child's phone locked over a network outage.
 *
 * Pure, so the decision — especially the alert hysteresis — is unit-tested.
 */
object ClockGuard {

    /** Skew beyond this is tampering (or a badly broken clock) — far past NTP drift or network lag. */
    const val TAMPER_THRESHOLD_MS = 15 * 60 * 1000L

    /** Only clear a standing alert once the skew is back under this (hysteresis, like HealthAlerts). */
    const val CLEAR_THRESHOLD_MS = 5 * 60 * 1000L

    /** Local clock minus server clock, in ms. Positive = local ahead. */
    fun skewMs(localNowMs: Long, serverTimeSec: Long): Long = localNowMs - serverTimeSec * 1000

    /**
     * The skew worth recording from a message that is NOT one of our own publishes coming
     * back, or null when it proves nothing. Only a clock moved BACKWARDS can be read off such
     * a message: replay can only ever make the skew look more positive, never more negative.
     */
    fun measuredSkew(skewMs: Long): Long? = skewMs.takeIf { it <= -TAMPER_THRESHOLD_MS }

    /**
     * The skew proven by an echo of our own publish, or null when this echo is not the publish
     * we are waiting for (an older one of ours, replayed after a reconnect) or comes from a
     * build that doesn't stamp its publishes.
     *
     * Measured against the local clock as it read AT THE MOMENT OF THAT PUBLISH, not on
     * arrival. Pairing already rules out replay, and reading the clock at publish time also
     * takes delivery latency out of the answer: a message that took two minutes to come back
     * says nothing about the clock, and this way it doesn't pretend to.
     */
    fun skewFromOwnEcho(
        awaitedNonce: Long,
        publishedAtLocalMs: Long,
        echoNonce: Long,
        serverTimeSec: Long,
    ): Long? = if (echoNonce != 0L && echoNonce == awaitedNonce) {
        skewMs(publishedAtLocalMs, serverTimeSec)
    } else {
        null
    }

    fun isTampered(skewMs: Long): Boolean = abs(skewMs) >= TAMPER_THRESHOLD_MS

    /** True once the skew is small enough to clear a standing alert. */
    fun clears(skewMs: Long): Boolean = abs(skewMs) <= CLEAR_THRESHOLD_MS

    /** One-shot: alert on entering the tampered state, not on every snapshot while it lasts. */
    fun shouldAlert(skewMs: Long, alreadyAlerted: Boolean): Boolean =
        isTampered(skewMs) && !alreadyAlerted
}
