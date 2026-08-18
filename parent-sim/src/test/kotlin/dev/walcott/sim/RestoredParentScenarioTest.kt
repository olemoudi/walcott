package dev.walcott.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parent's phone is lost and a new one takes over from the backup file — against a child that
 * has been enforcing rules all along and knows nothing about any of it.
 *
 * The arithmetic of this (version leaps, rotation certificates, replay gates) is unit-tested to
 * death. What could not be tested anywhere else is the only question a family actually asks: does
 * the phone in my daughter's pocket obey the new parent? It has its own stored version counter,
 * its own trusted key in DataStore, and an enforcement loop that goes on applying the last rules
 * it accepted — so a restore that the maths says should work can still leave a phone quietly
 * ignoring its new parent for ever.
 *
 * Both shapes of family are covered, because they recover through different mechanisms:
 * a family whose software signing key the backup carries verbatim, and a legacy family whose
 * Keystore key could never leave the lost phone and is replaced through a signed hand-over.
 */
class RestoredParentScenarioTest : DeviceScenario() {

    private val installBlock = setOf("installs")

    @Test
    fun `a restored parent with the same key can change the rules again`() {
        // Life before the loss: a rule that is visible in the OS, not just in the app.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }

        // The phone is lost. The replacement opens the backup file and becomes the parent: same
        // keys, same topic, and a version counter that restarts from what the FILE said — which
        // is why it leaps, since the lost phone went on publishing after the backup was written.
        parent.restoreFromBackup(rotate = false)

        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the install block lifted by the restored parent") { !device.installBlocked() }

        // And the child says so out loud, which is what the parent's screen reads.
        val reported = childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        assertTrue(reported.appliedPolicyVersion >= parent.currentVersion())
    }

    @Test
    fun `a legacy family's restored parent is adopted through the rotation it can prove`() {
        // A few edits first, so the child's applied version is comfortably above where a restored
        // parent's counter restarts. Without that gap the restored parent's snapshots would pass
        // the replay gate on their own and the rotation would be proving nothing.
        parent.pushPolicy(PolicyJson.build(version = 2))
        parent.pushPolicy(PolicyJson.build(version = 3))
        parent.pushPolicy(PolicyJson.build(version = 4, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        val appliedBefore = childEventuallyReports { it.appliedPolicyVersion >= 3 }.appliedPolicyVersion

        // The Keystore key could not be exported, so the backup carried a fresh key vouched for by
        // it. The restored parent signs with a key this device has never seen, at a version BELOW
        // the one it has already applied — everything the replay gate exists to refuse, except for
        // the certificate that makes it legitimate.
        parent.restoreFromBackup(rotate = true)
        assertTrue(parent.currentVersion() < appliedBefore, "the scenario must actually restart the counter")

        parent.pushPolicy(PolicyJson.build(version = 5, restrictions = emptySet()))
        awaitDevice("the install block lifted by a parent with a new key") { !device.installBlocked() }

        // Adopted, not merely accepted once: the next ordinary edit has to keep working, which is
        // what the rebased version baseline on the child is for.
        parent.pushPolicy(PolicyJson.build(version = 6, restrictions = installBlock))
        awaitDevice("the install block armed again by the new key") { device.installBlocked() }
    }

    @Test
    fun `the restored parent hears the child again`() {
        // Recovery is not only rules going out. A parent who cannot see their children has not
        // recovered anything, and the child's messages are read with the family key — which the
        // backup carries — rather than with the signing key that changed.
        parent.restoreFromBackup(rotate = true)
        parent.pushPolicy(PolicyJson.build(version = 2))

        val reported = childEventuallyReports { it.childId == CHILD_ID }
        assertEquals(deviceId, reported.deviceId)
    }
}
