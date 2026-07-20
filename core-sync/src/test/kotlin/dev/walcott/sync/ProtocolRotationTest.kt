package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The restored-parent wire path: children adopt a rotated signing key via its cert. */
class ProtocolRotationTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val oldPair = FamilyCrypto.generateSigningKeyPair()
    private val newPair = FamilyCrypto.generateSigningKeyPair()
    private val snapshot = ParentSnapshot(version = 7, policyJson = "{}")

    @Test
    fun `a restored parent's envelope is accepted via the rotation cert and reports the new key`() {
        val cert = KeyRotation.create(newPair.public, oldPair.private)
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, newPair.private, rotation = cert)

        // The child still trusts the OLD key only.
        val decoded = SyncProtocol.decodeVerbose(wire, familyKey, oldPair.public)
        assertNotNull(decoded)
        assertEquals(snapshot, (decoded!!.message as IncomingMessage.FromParent).snapshot)
        assertEquals(FamilyCrypto.toB64(newPair.public.encoded), decoded.rotatedParentPublicKeyB64)
    }

    @Test
    fun `after adoption the new key verifies directly, without a cert`() {
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, newPair.private)
        val decoded = SyncProtocol.decodeVerbose(wire, familyKey, newPair.public)
        assertNotNull(decoded)
        assertNull(decoded!!.rotatedParentPublicKeyB64)
    }

    @Test
    fun `a new-key envelope without a cert is rejected while the child trusts the old key`() {
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, newPair.private)
        assertNull(SyncProtocol.decodeVerbose(wire, familyKey, oldPair.public))
    }

    @Test
    fun `a cert from an untrusted signer does not smuggle a key in`() {
        val impostor = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(newPair.public, impostor.private)
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, newPair.private, rotation = cert)
        assertNull(SyncProtocol.decodeVerbose(wire, familyKey, oldPair.public))
    }

    @Test
    fun `a valid cert cannot bless an envelope signed by yet another key`() {
        val other = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(newPair.public, oldPair.private)
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, other.private, rotation = cert)
        assertNull(SyncProtocol.decodeVerbose(wire, familyKey, oldPair.public))
    }

    @Test
    fun `a normally signed envelope still decodes and reports no rotation`() {
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, oldPair.private)
        val decoded = SyncProtocol.decodeVerbose(wire, familyKey, oldPair.public)
        assertNotNull(decoded)
        assertNull(decoded!!.rotatedParentPublicKeyB64)
    }
}
