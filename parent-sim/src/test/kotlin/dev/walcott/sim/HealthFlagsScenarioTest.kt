package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The flags that say a phone has quietly stopped doing its job.
 *
 * Every other scenario asks whether a rule worked. These ask the opposite question: when the
 * ground the rules stand on is taken away, does anyone find out? A child that keeps publishing
 * cheerful snapshots while counting nothing is worse than one that has plainly gone silent — the
 * parent has no reason to look, and the limits they set simply stop happening.
 *
 * Usage access is the sharpest case and the reason this file exists. It is an AppOp: no Device
 * Owner can grant it and nothing in the app can either, a person revokes it in Settings in about
 * four taps, and the moment it is gone every budget-based rule fails closed while the app carries
 * on as if nothing had changed. The flag that carries that news to the parent had no test on a
 * device — and it is exactly the kind of field that can be computed correctly and dropped on the
 * way to the wire (see this suite's own history with screen time).
 */
class HealthFlagsScenarioTest : DeviceScenario() {

    @AfterEach
    fun giveUsageAccessBack() {
        // Never leave the device unable to count: the base class grants this as a precondition,
        // but a scenario that failed halfway would otherwise hand the NEXT one a phone whose
        // every budget fails closed, and the failure would be reported against the wrong test.
        runCatching { device.ensureUsageAccess() }
    }

    @Test
    fun `a phone that can no longer count screen time says so, and says when it can again`() {
        // Both halves in one pairing. They are one fact — "is this reported honestly" — and the
        // recovery half is where a one-way latch would hide: a flag that goes false and sticks
        // leaves a parent chasing a problem they already fixed.
        assertTrue(
            childReports { it.enforcement.isNotBlank() }.usageAccessOn,
            "a device that can count should not be reporting that it cannot",
        )

        revokeUsageAccess()
        assertEquals(false, device.usageAccessGranted(), "the AppOp should be gone before we assert on the report")
        val blind = childEventuallyReports { !it.usageAccessOn }
        assertEquals(false, blind.usageAccessOn, "budgets stopped counting and nobody was told")

        device.ensureUsageAccess()
        assertTrue(device.usageAccessGranted(), "the AppOp should be back before we assert on the report")
        assertTrue(
            childEventuallyReports { it.usageAccessOn }.usageAccessOn,
            "the alarm never cleared: the parent is left chasing a fault that is already fixed",
        )
    }

    @Test
    fun `being plugged in travels with the rest of the phone's state`() {
        // Not cosmetic: it is what stops a low battery reading as an emergency, and it is what
        // close tracking checks before deciding whether to throttle itself. Faked at the OS level
        // rather than in the app, so what is being tested is the read and not the mock.
        device.run("shell", "dumpsys", "battery", "set", "ac", "1")
        try {
            val charging = childEventuallyReports { it.charging }
            assertTrue(charging.charging, "the phone is on the charger and the snapshot denies it")
            assertTrue(charging.batteryPercent in 0..100, "battery level lost: ${charging.batteryPercent}")

            device.run("shell", "dumpsys", "battery", "unplug")
            assertEquals(
                false,
                childEventuallyReports { !it.charging }.charging,
                "unplugged and still reported as charging",
            )
        } finally {
            // The emulator keeps a faked battery until told otherwise, and every later scenario
            // would inherit it.
            runCatching { device.run("shell", "dumpsys", "battery", "reset") }
        }
    }

    /** Takes screen-time counting away the way a person does, in Settings. */
    private fun revokeUsageAccess() {
        device.run("shell", "appops", "set", "dev.walcott", "GET_USAGE_STATS", "ignore")
    }
}
