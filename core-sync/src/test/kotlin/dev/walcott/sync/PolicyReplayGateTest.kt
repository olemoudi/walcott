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
}
