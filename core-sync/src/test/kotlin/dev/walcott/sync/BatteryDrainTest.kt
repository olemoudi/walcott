package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryDrainTest {

    @Test
    fun `a rate is per half hour, whatever the window was`() {
        assertEquals(2f, BatteryDrain.rate(minutes = 30, drop = 2))
        assertEquals(1f, BatteryDrain.rate(minutes = 60, drop = 2))
        assertEquals(4f, BatteryDrain.rate(minutes = 15, drop = 2))
        assertEquals(-1f, BatteryDrain.rate(minutes = 0, drop = 2), "no time, no rate")
    }

    @Test
    fun `what cannot be attributed is not measured`() {
        // Charging: the level went up, or would have, and neither endpoint can say what happened.
        assertFalse(BatteryDrain.measurable(30, fromPercent = 80, toPercent = 78, charging = true))
        // A level that ROSE means a charger was on somewhere in the middle regardless.
        assertFalse(BatteryDrain.measurable(30, fromPercent = 70, toPercent = 72, charging = false))
        // Too short to survive whole-percent reporting; too long to be vouched for by its ends.
        assertFalse(BatteryDrain.measurable(3, fromPercent = 80, toPercent = 79, charging = false))
        assertFalse(BatteryDrain.measurable(240, fromPercent = 80, toPercent = 60, charging = false))
        assertTrue(BatteryDrain.measurable(30, fromPercent = 80, toPercent = 79, charging = false))
        // A window with no drop at all is still a measurement: it says this phone used nothing,
        // and dropping it would bias the baseline upwards by keeping only the expensive halves.
        assertTrue(BatteryDrain.measurable(30, fromPercent = 80, toPercent = 80, charging = false))
    }

    @Test
    fun `a session is measured end to end, however long it ran`() {
        // The window ceiling that guards ordinary use would throw away the best measurement this
        // app can take: four hours in which the phone was doing one known thing.
        assertTrue(
            BatteryDrain.measurableSession(240, fromPercent = 90, toPercent = 60, charging = false),
        )
        assertFalse(
            BatteryDrain.measurable(240, fromPercent = 90, toPercent = 60, charging = false),
            "ordinary use still cannot vouch for four hours from its endpoints",
        )
        // Too short to be worth a percent, on a charger, or charged mid-session: all unusable.
        assertFalse(BatteryDrain.measurableSession(4, fromPercent = 90, toPercent = 89, charging = false))
        assertFalse(BatteryDrain.measurableSession(60, fromPercent = 90, toPercent = 80, charging = true))
        assertFalse(BatteryDrain.measurableSession(60, fromPercent = 80, toPercent = 90, charging = false))
    }

    @Test
    fun `ordinary use accumulates by day and ages out after the retention window`() {
        var days = emptyList<BatteryDrain.Day>()
        days = BatteryDrain.plusNormal(days, epochDay = 100, minutes = 30, drop = 1)
        days = BatteryDrain.plusNormal(days, epochDay = 100, minutes = 30, drop = 2)
        assertEquals(1, days.size, "the same day should merge, not repeat")
        assertEquals(60, days.single().minutes)
        assertEquals(3, days.single().drop)

        days = BatteryDrain.plusNormal(days, epochDay = 114, minutes = 30, drop = 1)
        assertEquals(2, days.size, "day 100 is still inside fifteen days of day 114")
        days = BatteryDrain.plusNormal(days, epochDay = 115, minutes = 30, drop = 1)
        assertEquals(
            listOf(114L, 115L),
            days.map { it.epochDay },
            "day 100 has aged out",
        )
    }

    @Test
    fun `a day in the future is dropped rather than kept for ever`() {
        // The clock on a child's phone is something a child can change, and this app knows it
        // (see ChildSnapshot.clockSkewMs). A day stamped in 2099 would never age out and would
        // sit at the top of the ledger for the life of the install.
        val days = BatteryDrain.plusNormal(
            listOf(BatteryDrain.Day(epochDay = 99_999, minutes = 30, drop = 5)),
            epochDay = 100,
            minutes = 30,
            drop = 1,
        )
        assertEquals(listOf(100L), days.map { it.epochDay })
    }

    @Test
    fun `only the last ten sessions are kept, newest wins`() {
        var sessions = emptyList<BatteryDrain.Session>()
        repeat(12) { i ->
            sessions = BatteryDrain.plusSession(
                sessions,
                BatteryDrain.Session(startedAtMs = 1_000L + i, minutes = 30, drop = i),
            )
        }
        assertEquals(BatteryDrain.KEEP_SESSIONS, sessions.size)
        assertEquals(1_002L, sessions.first().startedAtMs, "the two oldest should have gone")
        assertEquals(1_011L, sessions.last().startedAtMs)
    }

    @Test
    fun `the summary compares the two rates and prices the difference`() {
        // Eight hours of ordinary use at 1% per half hour, and two sessions at 4%.
        val days = listOf(BatteryDrain.Day(epochDay = 1, minutes = 480, drop = 16))
        val sessions = listOf(
            BatteryDrain.Session(startedAtMs = 10, minutes = 30, drop = 4),
            BatteryDrain.Session(startedAtMs = 20, minutes = 60, drop = 8),
        )
        val summary = BatteryDrain.summarize(days, sessions)
        assertEquals(1f, summary.normalPct)
        assertEquals(4f, summary.livePct)
        assertEquals(2, summary.liveSessions)
        assertEquals(300, summary.upliftPercent, "four times the drain is 300% more, not 400%")
        assertEquals(8, summary.lastDrop, "the last session is the newest, not the biggest")
        assertEquals(60, summary.lastMinutes)
    }

    @Test
    fun `too little measurement says nothing rather than something wrong`() {
        // The failure this prevents: one half-hour window, one whole percent of quantisation, and
        // a confident "this phone uses 2% per half hour" built on a single rounding.
        val thin = BatteryDrain.summarize(
            listOf(BatteryDrain.Day(epochDay = 1, minutes = 30, drop = 1)),
            listOf(BatteryDrain.Session(startedAtMs = 1, minutes = 2, drop = 1)),
        )
        assertFalse(thin.hasNormal)
        assertFalse(thin.hasLive)
        assertNull(thin.upliftPercent)

        // And with only one half measured, there is still no comparison to make.
        val halfOnly = BatteryDrain.summarize(
            listOf(BatteryDrain.Day(epochDay = 1, minutes = 600, drop = 20)),
            emptyList(),
        )
        assertTrue(halfOnly.hasNormal)
        assertFalse(halfOnly.hasLive)
        assertNull(halfOnly.upliftPercent)
    }

    @Test
    fun `a baseline that rounds to nothing refuses to be a denominator`() {
        // A phone that measured 0% over eight hours (asleep in a drawer, mostly) would otherwise
        // turn any session at all into "+infinity%".
        val summary = BatteryDrain.summarize(
            listOf(BatteryDrain.Day(epochDay = 1, minutes = 600, drop = 0)),
            listOf(BatteryDrain.Session(startedAtMs = 1, minutes = 60, drop = 6)),
        )
        assertTrue(summary.hasNormal)
        assertTrue(summary.hasLive)
        assertNull(summary.upliftPercent)
    }

    @Test
    fun `a session cheaper than ordinary use is reported as no increase, never as a saving`() {
        // Measurement noise can put a session below the baseline (a phone in a pocket with the
        // screen off all session). "Close tracking saves battery" is a sentence this must never
        // produce.
        val summary = BatteryDrain.summarize(
            listOf(BatteryDrain.Day(epochDay = 1, minutes = 600, drop = 40)),
            listOf(BatteryDrain.Session(startedAtMs = 1, minutes = 60, drop = 1)),
        )
        assertEquals(0, summary.upliftPercent)
    }
}
