package dev.walcott.sim

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hour in which a blocked phone is allowed to update the apps it already has.
 *
 * The install block is the bluntest thing this app does, and to Android an update IS an install —
 * so arming it quietly stopped security fixes from arriving on the child's phone. The answer is a
 * scheduled window in which the platform restriction comes off and Play may update what is
 * installed; nothing new is forgiven, the guard still quarantines anything that appears.
 *
 * There is nothing to unit-test here that matters. The arithmetic of when the window falls is
 * already pure and covered; what could not be seen without a device is the only part families
 * depend on — that `no_install_apps` genuinely leaves the OS while the window is open and
 * genuinely comes back when it closes. A window that reported itself open while the restriction
 * stayed on would look perfect from the parent's phone and update nothing, for ever.
 */
class UpdateWindowScenarioTest : DeviceScenario() {

    @Test
    fun `the install block really lifts inside the window and really comes back after it`() {
        // Armed first, and asserted at the OS rather than in the snapshot: the whole point is
        // what the platform believes, and everything after this is a claim about changing it.
        parent.pushPolicy(policy(version = 2, windowOpen = false))
        awaitDevice("installs blocked") { device.installBlocked() }
        val blocked = childEventuallyReports { it.updatesOpenUntilMs == 0L }
        assertEquals(0L, blocked.updatesOpenUntilMs, "no window is open, and the child says one is")

        // A window this moment is inside. Opened from the POLICY arriving rather than from an
        // alarm firing, which is the case a phone that is switched on mid-window depends on.
        parent.pushPolicy(policy(version = 3, windowOpen = true))
        awaitDevice("installs allowed inside the window") { !device.installBlocked() }
        val open = childEventuallyReports { it.updatesOpenUntilMs > 0L }
        assertTrue(
            open.updatesOpenUntilMs > System.currentTimeMillis(),
            "the window is open on the device but the parent is told it ended at ${open.updatesOpenUntilMs}",
        )

        // And withdrawn. A block left down because the family changed its mind and nothing
        // noticed is the failure that would never announce itself.
        parent.pushPolicy(policy(version = 4, windowOpen = false))
        awaitDevice("installs blocked again") { device.installBlocked() }
        assertEquals(
            0L,
            childEventuallyReports { it.updatesOpenUntilMs == 0L }.updatesOpenUntilMs,
            "the window closed on the device and the parent still shows it running",
        )
    }

    @Test
    fun `no window is opened for a family that does not block installs at all`() {
        // The other half of the rule, and the reason it exists: a phone with nothing blocked has
        // no block to lift, so a device announcing an update window would be telling its parent
        // about a state it cannot be in — and, on a phone that only has accessibility-level
        // enforcement, would be lifting a restriction it never held.
        // `appliedPolicyVersion` is the SNAPSHOT's counter, not the policy JSON's own — the two
        // both exist and are easy to confuse (see PolicyJson.minimal), and comparing against the
        // wrong one waits for a number that is never coming.
        val pushed = parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                extra = mapOf(
                    "updateWindowEnabled" to JsonPrimitive(true),
                    "updateWindowFollowsBedtime" to JsonPrimitive(false),
                    "updateWindowHour" to JsonPrimitive(deviceHour()),
                    "updateWindowMinutes" to JsonPrimitive(WINDOW_MINUTES),
                ),
            ),
        )
        val reported = childEventuallyReports { it.appliedPolicyVersion >= pushed.version }
        assertEquals(
            0L,
            reported.updatesOpenUntilMs,
            "a family with no install block was told a window had opened for it",
        )
        assertEquals(false, device.installBlocked(), "nothing asked for a block here")
    }

    /**
     * A policy with installs blocked, and the nightly window either covering this instant or off.
     *
     * The hour comes from the DEVICE's clock, not this machine's: the window is a local hour and
     * an emulator in another timezone would put it somewhere else in the day, at which point the
     * scenario would be testing what time it was.
     */
    private fun policy(version: Long, windowOpen: Boolean): String = PolicyJson.build(
        version = version,
        restrictions = setOf("installs"),
        extra = mapOf(
            "updateWindowEnabled" to JsonPrimitive(windowOpen),
            "updateWindowFollowsBedtime" to JsonPrimitive(false),
            "updateWindowHour" to JsonPrimitive(deviceHour()),
            "updateWindowMinutes" to JsonPrimitive(WINDOW_MINUTES),
        ),
    )

    /** The hour of the day on the child device (0-23). */
    private fun deviceHour(): Int =
        device.run("shell", "date", "+%H").trim().trimStart('0').ifEmpty { "0" }.toInt()

    private companion object {
        /**
         * Long enough that the window still contains "now" however slow the round trips are,
         * and that starting at the top of this hour cannot leave the current minute outside it.
         */
        const val WINDOW_MINUTES = 180
    }
}
