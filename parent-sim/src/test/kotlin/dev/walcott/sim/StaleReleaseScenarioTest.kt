package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A release the family took back is not carried out by a phone that was away when they did.
 *
 * The case: a phone is lost, the parent asks for it to be freed, then learns it was stolen and
 * withdraws the release. The relay still holds the snapshot that asked, and a phone switched on in
 * a stranger's hands used to read it on the way to the one that withdrew it — and free itself.
 *
 * Destructive by tag because a failure is the phone giving up Device Owner, which every other
 * scenario needs; it re-provisions afterwards either way.
 */
@Tag("e2e")
@Tag("destructive")
class StaleReleaseScenarioTest : DeviceScenario() {

    @Test
    fun `a release withdrawn while the phone was away does not free it when it comes back`() {
        relay.pauseSubscribers(parent.topic)
        try {
            val commandId = parent.sendCommand(deviceId, RemoteAction.RELEASE_DEVICE)
            val withdrawn = parent.cancelCommand(commandId)

            relay.resumeSubscribers(parent.topic)
            device.reconnect()

            // Caught up to the snapshot that withdrew it — so the old one had every chance to run.
            childReports { it.appliedPolicyVersion >= withdrawn.version }
            assertDeviceNever("Device Owner given up for a withdrawn release", windowMs = 30_000) {
                !device.isDeviceOwner()
            }
            assertTrue(!parent.acknowledged(commandId), "the phone acknowledged a release the parent had taken back")
        } finally {
            relay.resumeSubscribers(parent.topic)
        }
    }

    /** Puts Device Owner back if the scenario failed by losing it. */
    @AfterEach
    fun reprovisionIfLost() {
        if (!device.isDeviceOwner()) device.reprovisionDeviceOwner()
    }
}
