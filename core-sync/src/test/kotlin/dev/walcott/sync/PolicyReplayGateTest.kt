package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyReplayGateTest {

    @Test
    fun `a strictly newer snapshot is adopted and raises the baseline`() {
        assertTrue(SyncEngine.adoptsPolicy(snapshotVersion = 8, appliedVersion = 7, rotationAdopted = false))
        assertEquals(8, SyncEngine.rebasedPolicyVersion(8, 7, rotationAdopted = false))
    }

    @Test
    fun `a replayed equal or older snapshot is rejected`() {
        // Re-emits reuse the current version — idempotent skip, not an error.
        assertFalse(SyncEngine.adoptsPolicy(snapshotVersion = 7, appliedVersion = 7, rotationAdopted = false))
        // The attack: a removed child replays a captured envelope with laxer past rules.
        assertFalse(SyncEngine.adoptsPolicy(snapshotVersion = 3, appliedVersion = 7, rotationAdopted = false))
    }

    @Test
    fun `a verified rotation adopts even a lower version and rebases the baseline down`() {
        // A parent restored from backup legitimately restarts its counter below what the
        // lost phone last published; the cert-attested rotation is the proof of freshness.
        assertTrue(SyncEngine.adoptsPolicy(snapshotVersion = 3, appliedVersion = 7, rotationAdopted = true))
        assertEquals(3, SyncEngine.rebasedPolicyVersion(3, 7, rotationAdopted = true))
    }

    @Test
    fun `after a rotation rebase the restored parent's next edits keep flowing`() {
        val rebased = SyncEngine.rebasedPolicyVersion(3, 7, rotationAdopted = true)
        // Post-adoption the new key verifies directly, so rotationAdopted is false again —
        // only the rebased baseline lets version 4 through where 7 would have blocked it.
        assertTrue(SyncEngine.adoptsPolicy(snapshotVersion = 4, appliedVersion = rebased, rotationAdopted = false))
    }

    @Test
    fun `without rotation the baseline never moves down`() {
        assertEquals(7, SyncEngine.rebasedPolicyVersion(3, 7, rotationAdopted = false))
    }

    @Test
    fun `a same-key restore leaves the counter somewhere an incremental parent can never reach`() {
        // Why a restore must not be spoken aloud on a topic until it is certain it belongs there
        // (see FamilyHub.addFamilyFromBackup). A same-key restore carries no rotation cert, so it
        // buys its way past this gate with a version a million ahead — and once a child has
        // rebased there, a parent scope still counting 42, 43, 44 is refused for ever. Fixing that
        // needs a factory reset of the family's counter; there is no way back over the wire.
        val leaped = 42L + 1_000_000L
        assertTrue(SyncEngine.adoptsPolicy(leaped, appliedVersion = 42, rotationAdopted = false))
        val childBaseline = SyncEngine.rebasedPolicyVersion(leaped, 42, rotationAdopted = false)
        assertEquals(leaped, childBaseline)
        // The scope that really manages this family, carrying on from where it was.
        assertFalse(SyncEngine.adoptsPolicy(snapshotVersion = 43, appliedVersion = childBaseline, rotationAdopted = false))
        assertFalse(SyncEngine.adoptsPolicy(snapshotVersion = 99, appliedVersion = childBaseline, rotationAdopted = false))
    }
}
