package dev.walcott.data

/** Result of a guarded PIN check. */
sealed interface PinResult {
    /** Correct PIN. */
    data object Ok : PinResult
    /** Wrong PIN, not (yet) locked out. */
    data object Wrong : PinResult
    /** Too many attempts; [remainingMs] until another try is allowed. */
    data class Locked(val remainingMs: Long) : PinResult

    /**
     * This family has no PIN at all, so nothing can be checked against it.
     *
     * Its own answer, and not [Wrong], because the two need opposite handling: a wrong PIN is
     * someone guessing and earns a lockout and an alert to the parent, while this is the family
     * never having set one — the person typing is right and the setup is wrong. Told apart, the
     * screens can say so instead of rejecting every attempt forever (see the emergency release
     * in AppSettingsScreen, which is the door this used to quietly wall up).
     */
    data object NotSet : PinResult
}

/**
 * Escalating lockout after repeated wrong PINs, to make a 4-digit PIN infeasible to brute-force.
 * The lockout steps up by [STEP_MS] for every [TRANCHE] consecutive wrong attempts, capped at
 * [MAX_MS]. Pure and deterministic so it can be unit-tested without Android.
 */
object PinLockout {

    /** Wrong attempts per escalation tranche. */
    const val TRANCHE = 3

    /** Extra lockout earned per completed tranche of wrong attempts. */
    private const val STEP_MS = 5 * 60_000L

    /** Hard ceiling on the imposed lockout. */
    private const val MAX_MS = 30 * 60_000L

    /** Lockout to impose after [failedAttempts] consecutive failures (0 = none yet). */
    fun lockoutMs(failedAttempts: Int): Long {
        if (failedAttempts <= 0) return 0
        val tranches = failedAttempts / TRANCHE
        return (tranches * STEP_MS).coerceAtMost(MAX_MS)
    }

    /** Remaining lockout given the stored deadline and the current time. */
    fun remainingMs(lockedUntilMs: Long, nowMs: Long): Long = (lockedUntilMs - nowMs).coerceAtLeast(0)
}
