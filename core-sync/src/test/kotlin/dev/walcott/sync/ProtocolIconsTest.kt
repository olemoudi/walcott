package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolIconsTest {

    private val key = FamilyCrypto.generateFamilyKey()
    private val parentKeys = FamilyCrypto.generateSigningKeyPair()

    @Test
    fun `an icon payload round-trips on the wire as FromChildIcons`() {
        val payload = IconPayload(
            deviceId = "child-1",
            icons = listOf(AppIconData("com.a", "AAAA"), AppIconData("com.b", "BBBB")),
        )
        val decoded = SyncProtocol.decode(
            SyncProtocol.encodeChildIcons(payload, key), key, parentKeys.public,
        )
        val out = (decoded as IncomingMessage.FromChildIcons).payload
        assertEquals(payload, out)
    }

    @Test
    fun `parent icon requests survive the wire`() {
        val snapshot = ParentSnapshot(version = 1, policyJson = "{}", iconRequests = listOf("com.a", "com.b"))
        val decoded = SyncProtocol.decode(
            SyncProtocol.encodeParent(snapshot, key, parentKeys.private), key, parentKeys.public,
        )
        val out = (decoded as IncomingMessage.FromParent).snapshot
        assertEquals(listOf("com.a", "com.b"), out.iconRequests)
    }

    @Test
    fun `a legacy parent snapshot without icon requests decodes to an empty list`() {
        val legacy = ParentSnapshot(version = 1, policyJson = "{}")
        val decoded = SyncProtocol.decode(
            SyncProtocol.encodeParent(legacy, key, parentKeys.private), key, parentKeys.public,
        )
        assertEquals(emptyList<String>(), (decoded as IncomingMessage.FromParent).snapshot.iconRequests)
    }
}
