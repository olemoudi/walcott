package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val parent = FamilyCrypto.generateSigningKeyPair()

    @Test
    fun `parent snapshot round-trips through an envelope`() {
        val snapshot = ParentSnapshot(
            version = 4,
            policyJson = """{"version":4}""",
            resolutions = listOf(Resolution("r1", approved = true, grantedMinutes = 15, resolvedAtEpochMs = 1000)),
        )
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, parent.private)
        val decoded = SyncProtocol.decode(wire, familyKey, parent.public)
        assertInstanceOf(IncomingMessage.FromParent::class.java, decoded)
        assertEquals(snapshot, (decoded as IncomingMessage.FromParent).snapshot)
    }

    @Test
    fun `child snapshot round-trips through an envelope`() {
        val snapshot = ChildSnapshot(
            deviceId = "dev-1",
            displayName = "Ana's phone",
            version = 2,
            epochDay = 20_000,
            usage = listOf(UsageEntry("games", 600)),
            requests = listOf(ExtraTimeRequest("r7", "games", 15, "please", 123)),
        )
        val wire = SyncProtocol.encodeChild(snapshot, familyKey)
        val decoded = SyncProtocol.decode(wire, familyKey, parent.public)
        assertInstanceOf(IncomingMessage.FromChild::class.java, decoded)
        assertEquals(snapshot, (decoded as IncomingMessage.FromChild).snapshot)
    }

    @Test
    fun `a parent message signed by an impostor is rejected`() {
        val impostor = FamilyCrypto.generateSigningKeyPair()
        val snapshot = ParentSnapshot(version = 1, policyJson = "{}")
        // Impostor has the family key but not the parent's private key.
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, impostor.private)
        assertNull(SyncProtocol.decode(wire, familyKey, parent.public))
    }

    @Test
    fun `a message with the wrong family key can't be decrypted`() {
        val snapshot = ChildSnapshot("d", "phone", 1, 1)
        val wire = SyncProtocol.encodeChild(snapshot, familyKey)
        assertNull(SyncProtocol.decode(wire, FamilyCrypto.generateFamilyKey(), parent.public))
    }

    @Test
    fun `garbage input decodes to null`() {
        assertNull(SyncProtocol.decode("not-json", familyKey, parent.public))
        assertNull(SyncProtocol.decode("", familyKey, parent.public))
    }

    @Test
    fun `parent signature actually covers the ciphertext (integrity)`() {
        val snapshot = ParentSnapshot(version = 1, policyJson = """{"a":1}""")
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, parent.private)
        // Flip a character in the ciphertext field -> signature no longer matches.
        val tampered = wire.replace("\"ciphertext\":\"", "\"ciphertext\":\"A")
        assertNull(SyncProtocol.decode(tampered, familyKey, parent.public))
    }

    @Test
    fun `decode tolerates unknown fields for forward compatibility`() {
        val snapshot = ChildSnapshot("d", "phone", 1, 1)
        val wire = SyncProtocol.encodeChild(snapshot, familyKey)
        val withExtra = wire.dropLast(1) + ""","futureField":true}"""
        assertTrue(SyncProtocol.decode(withExtra, familyKey, parent.public) is IncomingMessage.FromChild)
    }
}
