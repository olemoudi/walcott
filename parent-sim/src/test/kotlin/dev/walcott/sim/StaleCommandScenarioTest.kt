package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a phone that was away finds on the relay when it comes back, and what it does with it.
 *
 * A parent takes a command back by publishing a snapshot without it, and that does not delete the
 * snapshot that carried it: the relay keeps both. A phone that read them one at a time, oldest
 * first, ran the command on its way to the snapshot that withdrew it — and the command that most
 * needs withdrawing is "free this phone", for a phone that turned out to be stolen (see
 * StaleReleaseScenarioTest for that one, which cannot run here). DIAGNOSE stands in for it: it is
 * the one command whose having run is visible from the parent's side without changing the phone.
 */
class StaleCommandScenarioTest : DeviceScenario() {

    @Test
    fun `a command taken back while the phone was away is not run when it comes back`() {
        relay.pauseSubscribers(parent.topic)
        try {
            val commandId = parent.sendCommand(deviceId, RemoteAction.DIAGNOSE)
            val withdrawn = parent.cancelCommand(commandId)

            relay.resumeSubscribers(parent.topic)
            device.reconnect()

            // It did catch up — the snapshot that withdrew the command is the one it read...
            val caughtUp = childReports { it.appliedPolicyVersion >= withdrawn.version }
            assertEquals(withdrawn.version, caughtUp.appliedPolicyVersion)
            // ...and nothing of the one it withdrew.
            assertNull(
                runCatching { parent.awaitDiag(timeoutMs = 15_000) }.getOrNull(),
                "the phone ran a command the parent had taken back before it came back",
            )
            assertTrue(!parent.acknowledged(commandId), "the phone acknowledged a command the parent had taken back")
        } finally {
            relay.resumeSubscribers(parent.topic)
        }
    }

    @Test
    fun `a command still queued when the phone comes back is run once`() {
        // The control for the one above: the catch-up must not have stopped commands altogether.
        relay.pauseSubscribers(parent.topic)
        try {
            val commandId = parent.sendCommand(deviceId, RemoteAction.DIAGNOSE)
            relay.resumeSubscribers(parent.topic)
            device.reconnect()
            val ack = parent.awaitAck(commandId)
            assertTrue(ack.ok, "the queued command should run: ${ack.detail}")
            parent.awaitDiag()
        } finally {
            relay.resumeSubscribers(parent.topic)
        }
    }

    @Test
    fun `a phone paired after the rules went out starts on them without being sent them again`() {
        // The parent publishes, and only then does a phone join. The relay holds the rules, and a
        // first connection used to ask it for nothing — so the new phone ran with none until the
        // parent happened to publish again, which on a real parent can be a quarter of an hour.
        //
        // An honest caveat about what this can show: before the fix a re-pair inside a running
        // process kept its old cursor in memory and replayed from it, so this passed then too —
        // a freshly installed phone, which is the case that failed, has no such cursor. What it
        // pins is the fix's own path: pairing now starts from no cursor and reads the backlog.
        //
        // Kept away from the relay while the rules go out, so the phone already paired by the
        // set-up cannot hear them live; then paired afresh — a new identity, no cursor — and let
        // back in. Only what its first connection reads can arm the block.
        relay.pauseSubscribers(parent.topic)
        try {
            val pushed = parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
            device.pairFresh(parent.pairingFor(childId = CHILD_ID, childName = CHILD_NAME))
            relay.resumeSubscribers(parent.topic)
            device.reconnect()

            awaitDevice("the install block armed from the rules already on the relay", timeoutMs = 60_000) {
                device.installBlocked()
            }
            val reported = childReports { it.childId == CHILD_ID && it.appliedPolicyVersion >= pushed.version }
            assertEquals(pushed.version, reported.appliedPolicyVersion)
        } finally {
            relay.resumeSubscribers(parent.topic)
        }
    }
}
