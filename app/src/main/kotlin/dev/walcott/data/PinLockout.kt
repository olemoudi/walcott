package dev.walcott.data

/** Result of a guarded PIN check. */
sealed interface PinResult {
    /** Correct PIN. */
    data object Ok : PinResult
    /** Wrong PIN, not (yet) locked out. */
    data object Wrong : PinResult
    /** Too many attempts; [remainingMs] until another try is allowed. */
    data class Locked(val remainingMs: Long) : PinResult
}

/**
 * Escalating lockout after repeated wrong PINs, to make a 4-digit PIN infeasible to brute-force.
 * Pure and deterministic so it can be unit-tested without Android.
 */
object PinLockout {

    /** Wrong tries allowed before any lockout kicks in. */
    const val FREE_ATTEMPTS = 4

    /** Lockout imposed on the 5th, 6th, 7th, 8th+ consecutive failure. */
    private val STEPS_MS = longArrayOf(30_000L, 60_000L, 5 * 60_000L, 30 * 60_000L)

    /** Lockout to impose after [failedAttempts] consecutive failures (0 = none yet). */
    fun lockoutMs(failedAttempts: Int): Long {
        if (failedAttempts <= FREE_ATTEMPTS) return 0
        val idx = (failedAttempts - FREE_ATTEMPTS - 1).coerceIn(0, STEPS_MS.lastIndex)
        return STEPS_MS[idx]
    }

    /** Remaining lockout given the stored deadline and the current time. */
    fun remainingMs(lockedUntilMs: Long, nowMs: Long): Long = (lockedUntilMs - nowMs).coerceAtLeast(0)
}
