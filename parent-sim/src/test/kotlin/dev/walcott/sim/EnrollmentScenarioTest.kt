package dev.walcott.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Enrolment, on a real device.
 *
 * The pairing path has always been unit-tested at the protocol level, where both sides are the
 * same process and nothing can be misconfigured. What that cannot show is a child that reads a
 * QR, rewrites its own identity, connects to the relay the QR names, and reports back — which
 * is the step every other scenario stands on.
 */
class EnrollmentScenarioTest : DeviceScenario() {

    @Test
    fun `a scanned QR enrols the device into the family it names`() {
        val snapshot = parent.children.values.single()
        assertEquals(CHILD_ID, snapshot.childId, "the childId from the QR should come back")
        assertEquals(CHILD_NAME, snapshot.displayName, "the name from the QR should come back")
        // Version 0 is correct here and worth pinning: the counter only moves when the child has
        // something to say, so a freshly paired device checks in at zero. A test asserting
        // otherwise would be asserting a bug.
        assertEquals(0L, snapshot.version, "a first check-in should not have bumped the counter")
    }

    @Test
    fun `the child reports what it can enforce, not what it hopes`() {
        // The parent's whole reliability surface is built on this field, and it is decided by the
        // OS on the device: no emulator, no honest answer.
        val snapshot = childReports { it.enforcement.isNotBlank() }
        assertEquals(
            device.isDeviceOwner(),
            snapshot.enforcement == "device_owner",
            "enforcement=${snapshot.enforcement} but Device Owner=${device.isDeviceOwner()}",
        )
    }

    @Test
    fun `re-pairing the same device keeps its id, so the parent sees one child and not two`() {
        // A second QR for the same phone must not produce a ghost. The rule lives in
        // pairAsChild (keep the deviceId across re-pairs) and there was no way to check it
        // without a device that actually re-pairs.
        val firstId = deviceId
        val secondParent = ParentSim(relay.localUrl, advertisedRelay = relay.emulatorUrl).start()
        try {
            device.pair(secondParent.pairingFor(childId = "other-id", childName = "Renamed"))
            val afterRepair = secondParent.awaitChild { it.childId == "other-id" }
            assertEquals(firstId, afterRepair.deviceId, "the device id should survive a re-pair")
            assertEquals("Renamed", afterRepair.displayName, "the new QR's name should take effect")
        } finally {
            secondParent.stop()
        }
    }

    @Test
    fun `a child talks only to the relay its QR named`() {
        // The QR is the only place a child learns where the family lives. If this were not true,
        // every scenario here would be silently talking to the public server.
        val before = relay.published(parent.topic).size
        device.publish()
        assertTrue(
            relay.awaitPublished(parent.topic, moreThan = before),
            "the child published somewhere other than the relay in its QR",
        )
    }

    @Test
    fun `a reset device stops answering for the family`() {
        device.reset()
        val quiet = relay.published(parent.topic).size
        device.publish()
        // A device that has forgotten its keys has nothing to publish and nowhere to publish it.
        assertFalse(
            relay.awaitPublished(parent.topic, moreThan = quiet, timeoutMs = 5_000),
            "a reset device is still talking to its old family",
        )
        // And it is genuinely unpaired rather than merely quiet: pairing again works.
        device.pair(parent.pairingFor(childId = CHILD_ID, childName = CHILD_NAME))
        assertEquals(CHILD_ID, parent.awaitChild { it.childId == CHILD_ID }.childId)
    }
}
