package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * "Change device mode" on a child's phone, in the middle of bedtime.
 *
 * Until 0.112 this left the phone exactly as it was. A blank identity is UNSET, UNSET enforces by
 * design, so leaving child mode never flipped the flag that runs the hand-back — and the rules
 * were kept too. A phone passed to a sibling or a cousin went on applying the previous family's
 * bedtime with no parent, no channel and no rescue code. The unit tests could not see it: every
 * piece did what it said, and the gap was in which piece was expected to run.
 */
class ChangeModeScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    @Test
    fun `leaving child mode at bedtime gives everything back and keeps the phone owned`() {
        app = installFixtureApp()
        parent.pushPolicy(PolicyJson.build(version = 2))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app settled as allowed") { !device.isSuspended(app) }

        // Bedtime around now, wide enough that the minute this runs in cannot matter, plus a
        // restriction the OS shows independently of any app.
        val now = LocalTime.now()
        val bedtime = now.minusMinutes(30).let { it.hour * 60 + it.minute } to
            now.plusMinutes(90).let { it.hour * 60 + it.minute }
        parent.pushPolicy(PolicyJson.build(version = 3, bedtime = bedtime, restrictions = setOf("installs")))
        awaitDevice("the app suspended by bedtime", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        awaitDevice("the install block armed") { device.installBlocked() }

        device.changeMode()

        awaitDevice("the app given back", timeoutMs = APPLY_TIMEOUT_MS) { !device.isSuspended(app) }
        awaitDevice("the install block lifted") { !device.installBlocked() }
        // And it STAYS given back: the loop that re-asserts suspensions every thirty seconds is
        // the thing that used to put them straight back, so a silence shorter than that proves
        // nothing.
        assertDeviceNever("the app suspended again after leaving child mode", windowMs = 40_000) {
            device.isSuspended(app)
        }
        // Device Owner is deliberately kept: pairing again restores everything without a reset.
        assertTrue(device.isDeviceOwner(), "leaving child mode must not give up Device Owner")

        // And pairing again has to WORK, promptly. The first version of this fix stopped the
        // enforcement service and nothing started it again — leaving child mode does not change
        // whether the phone enforces, so no transition was ever seen — and this scenario passed
        // while the four after it failed, on a phone whose loop was simply not running.
        device.pair(parent.pairingFor(CHILD_ID, CHILD_NAME))
        parent.pushPolicy(PolicyJson.build(version = 4, bedtime = bedtime))
        awaitDevice("the next family's bedtime applied after pairing again", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }
    }

    private companion object {
        /** Covers a slow tick after a policy lands, as the schedule scenarios allow. */
        const val APPLY_TIMEOUT_MS = 60_000L
    }
}
