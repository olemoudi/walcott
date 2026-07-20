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
 * positive skew only counts on this device's own fresh echo (published seconds ago, so its
 * server timestamp is genuinely "now").
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
     * The skew worth recording from one message, or null when the message proves nothing
     * (a positive skew on a message that may be replayed). [ownFreshEcho] = the message is
     * this device's own publish coming back, so its server timestamp is current.
     */
    fun measuredSkew(skewMs: Long, ownFreshEcho: Boolean): Long? = when {
        ownFreshEcho -> skewMs
        skewMs <= -TAMPER_THRESHOLD_MS -> skewMs
        else -> null
    }

    fun isTampered(skewMs: Long): Boolean = abs(skewMs) >= TAMPER_THRESHOLD_MS

    /** True once the skew is small enough to clear a standing alert. */
    fun clears(skewMs: Long): Boolean = abs(skewMs) <= CLEAR_THRESHOLD_MS

    /** One-shot: alert on entering the tampered state, not on every snapshot while it lasts. */
    fun shouldAlert(skewMs: Long, alreadyAlerted: Boolean): Boolean =
        isTampered(skewMs) && !alreadyAlerted
}
