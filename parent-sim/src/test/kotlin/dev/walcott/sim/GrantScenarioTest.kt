package dev.walcott.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Time the parent gives without being asked, and the two things that must never happen to it:
 * being applied twice, or being applied on the wrong phone.
 *
 * A bonus rides in every re-emitted parent snapshot and every relay replay, exactly like a
 * resolution — but unlike a resolution nothing on the child retires it, because there is no
 * request to retire. The only thing standing between "15 bonus minutes" and "15 minutes every
 * fifteen minutes, for ever" is the applied-id ledger on the device.
 *
 * Every assertion is a DELTA. Extra time lives in Room and outlives a re-pairing, as it should:
 * a grant is the child's, not the family's. A test comparing totals would be a test of what ran
 * before it.
 */
class GrantScenarioTest : DeviceScenario() {

    private val allApps = "__all_apps__"

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Extra seconds the child is currently reporting, freshly published. */
    private fun extraNow(): Long = childReports { true }.extra.sumOf { it.seconds }

    /** Grants [minutes] and waits until exactly that much more has landed. */
    private fun grantAndSettle(minutes: Int): Long {
        val before = extraNow()
        parent.grantBonus(deviceId, allApps, minutes = minutes, epochDay = today())
        val target = before + minutes * 60L
        parent.awaitChild { it.extra.sumOf { entry -> entry.seconds } >= target }
        return target
    }

    @Test
    fun `a bonus reaches the device and shows up as extra time`() {
        val expected = grantAndSettle(minutes = 15)
        assertEquals(expected, extraNow(), "15 minutes should be 900 seconds, once")
    }

    @Test
    fun `a bonus is applied exactly once, however often the snapshot repeats`() {
        val expected = grantAndSettle(minutes = 10)
        repeat(3) { parent.reEmit() }
        Thread.sleep(6_000)
        assertEquals(expected, extraNow(), "a re-emit granted the bonus again")
    }

    @Test
    fun `a replayed snapshot does not grant the bonus a second time`() {
        val before = relay.published(parent.topic).size
        val expected = grantAndSettle(minutes = 10)
        // The parent snapshot carrying the bonus is the first thing published after `before`.
        relay.replay(parent.topic, before)
        Thread.sleep(6_000)
        assertEquals(expected, extraNow(), "a relay replay granted it twice")
    }

    @Test
    fun `a bonus addressed to another device is not taken by this one`() {
        // Every child on the family topic reads every snapshot; only the addressee may act.
        val before = extraNow()
        parent.grantBonus("some-other-device", allApps, minutes = 30, epochDay = today())
        Thread.sleep(6_000)
        assertEquals(before, extraNow(), "this device took a sibling's bonus")
    }

    @Test
    fun `two separate bonuses both land`() {
        // The ledger has to distinguish "already applied" from "looks similar": two grants of
        // the same size on the same day are two grants, not one seen twice.
        grantAndSettle(minutes = 5)
        val expected = grantAndSettle(minutes = 5)
        assertEquals(expected, extraNow(), "the second identical bonus was mistaken for the first")
    }
}
