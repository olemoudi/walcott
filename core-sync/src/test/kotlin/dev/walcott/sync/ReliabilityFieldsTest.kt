package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Wire round-trips and legacy defaults for the v0.10 reliability fields. */
class ReliabilityFieldsTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val parent = FamilyCrypto.generateSigningKeyPair()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun `child self-test gaps and clock skew round-trip`() {
        val snapshot = ChildSnapshot(
            deviceId = "dev-1",
            displayName = "Ana",
            version = 9,
            epochDay = 20_000,
            enforcementGaps = listOf("com.game.one", "com.game.two"),
            clockSkewMs = -2 * 60 * 60 * 1000L,
        )
        val decoded = SyncProtocol.decode(SyncProtocol.encodeChild(snapshot, familyKey), familyKey, parent.public)
        assertEquals(snapshot, (decoded as IncomingMessage.FromChild).snapshot)
    }

    @Test
    fun `parent version code rides the parent snapshot`() {
        val snapshot = ParentSnapshot(version = 3, policyJson = "{}", parentVersionCode = 48)
        val wire = SyncProtocol.encodeParent(snapshot, familyKey, parent.private)
        val decoded = SyncProtocol.decode(wire, familyKey, parent.public)
        assertEquals(48, (decoded as IncomingMessage.FromParent).snapshot.parentVersionCode)
    }

    @Test
    fun `legacy snapshots decode with clean defaults - no false alarms, no canary gating`() {
        val child = json.decodeFromString(
            ChildSnapshot.serializer(),
            """{"deviceId":"d","displayName":"phone","version":1,"epochDay":1}""",
        )
        assertEquals(emptyList<String>(), child.enforcementGaps)
        assertEquals(0L, child.clockSkewMs)

        val parentSnap = json.decodeFromString(
            ParentSnapshot.serializer(),
            """{"version":1,"policyJson":"{}"}""",
        )
        assertEquals(0, parentSnap.parentVersionCode)
    }
}
