package dev.walcott.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a phone this app keeps dying on is opened, and — just as much — when it must not be.
 *
 * Opening a child's phone by mistake is a phone with no rules; never opening it is a phone frozen
 * in its rules until a factory reset. Both are decided here.
 */
class CrashBreakerTest {

    private val build = 168
    private val now = 1_760_000_000_000L

    private fun after(crashes: Int, early: Boolean = true): BreakerState {
        var state = BreakerState(0, build, 0)
        repeat(crashes) { state = CrashBreakerRules.afterCrash(state, early) }
        return state
    }

    @Test
    fun `three starts in a row that die before enforcing open a Device Owner phone`() {
        val stored = after(CrashBreakerRules.EARLY_CRASHES_TO_TRIP)
        val started = CrashBreakerRules.atStart(stored, build, deviceOwner = true, nowMs = now)
        assertEquals(now, started.safeModeSinceMs)
    }

    @Test
    fun `two are not enough`() {
        val stored = after(CrashBreakerRules.EARLY_CRASHES_TO_TRIP - 1)
        assertEquals(0L, CrashBreakerRules.atStart(stored, build, deviceOwner = true, nowMs = now).safeModeSinceMs)
    }

    @Test
    fun `a crash with one of the app's screens in front never counts`() {
        // Something a child can find and repeat on purpose must never add up to an open phone.
        assertFalse(CrashBreakerRules.countsAsEarly(enforcedOnce = false, screenShown = true))
        assertEquals(after(5, early = false), BreakerState(0, build, 0))
    }

    @Test
    fun `a crash after the rules were applied once is not a start-up that fails`() {
        assertFalse(CrashBreakerRules.countsAsEarly(enforcedOnce = true, screenShown = false))
        assertTrue(CrashBreakerRules.countsAsEarly(enforcedOnce = false, screenShown = false))
    }

    @Test
    fun `enforcing once proves the build starts and wipes the count`() {
        assertEquals(0, CrashBreakerRules.afterEnforced(after(2)).earlyCrashes)
    }

    @Test
    fun `a new build starts clean, open phone or not`() {
        val open = after(3).copy(safeModeSinceMs = now - 60_000)
        val next = CrashBreakerRules.atStart(open, build + 1, deviceOwner = true, nowMs = now)
        assertEquals(BreakerState(0, build + 1, 0), next)
    }

    @Test
    fun `an open phone stays open across restarts of the same build`() {
        val open = after(3).copy(safeModeSinceMs = now - 60_000)
        assertEquals(open, CrashBreakerRules.atStart(open, build, deviceOwner = true, nowMs = now))
    }

    @Test
    fun `a phone that is not a Device Owner is never put in safe mode`() {
        // Nothing of this app's can be left suspended on it, so there is nothing to open.
        val next = CrashBreakerRules.atStart(after(4), build, deviceOwner = false, nowMs = now)
        assertEquals(0L, next.safeModeSinceMs)
    }

    @Test
    fun `leaving puts the rules back and forgets the crashes`() {
        val open = after(3).copy(safeModeSinceMs = now)
        assertEquals(BreakerState(0, build, 0), CrashBreakerRules.left(open))
    }

    @Test
    fun `the file round-trips, and anything unreadable reads as clean`() {
        val state = BreakerState(2, build, now)
        assertEquals(state, BreakerStateFormat.parse(BreakerStateFormat.format(state)))
        // A corrupt file must not be able to open a phone on its own.
        assertEquals(BreakerState.CLEAN, BreakerStateFormat.parse(null))
        assertEquals(BreakerState.CLEAN, BreakerStateFormat.parse(""))
        assertEquals(BreakerState.CLEAN, BreakerStateFormat.parse("3 168"))
        assertEquals(BreakerState.CLEAN, BreakerStateFormat.parse("x 168 0"))
        assertEquals(BreakerState.CLEAN, BreakerStateFormat.parse("-1 168 0"))
    }
}
