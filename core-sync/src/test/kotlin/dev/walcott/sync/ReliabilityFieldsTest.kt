package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Wire round-trips and legacy defaults for the reliability fields. */
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
    fun `what close tracking cost this phone round-trips, and is absent by default`() {
        val plain = ChildSnapshot(deviceId = "dev-1", displayName = "Ana", version = 9, epochDay = 20_000)
        val decodedPlain = SyncProtocol.decode(SyncProtocol.encodeChild(plain, familyKey), familyKey, parent.public)
        assertNull(
            (decodedPlain as IncomingMessage.FromChild).snapshot.batteryDrain,
            "a child that has measured nothing must send nothing, not zeroes",
        )

        val measured = plain.copy(
            batteryDrain = BatteryDrain.Summary(
                normalPct = 1.2f,
                normalMinutes = 900,
                livePct = 4.5f,
                liveSessions = 3,
                lastDrop = 6,
                lastMinutes = 45,
            ),
        )
        val decoded = SyncProtocol.decode(SyncProtocol.encodeChild(measured, familyKey), familyKey, parent.public)
        assertEquals(measured.batteryDrain, (decoded as IncomingMessage.FromChild).snapshot.batteryDrain)
        assertEquals(275, measured.batteryDrain?.upliftPercent)
    }

    @Test
    fun `the child's timezone offset round-trips, negative ones included`() {
        for (offset in listOf(0, 60, 9 * 60, -6 * 60, -12 * 60, 14 * 60)) {
            val snapshot = ChildSnapshot(
                deviceId = "dev-1", displayName = "Ana", version = 9, epochDay = 20_000,
                tzOffsetMinutes = offset,
            )
            val decoded = SyncProtocol.decode(SyncProtocol.encodeChild(snapshot, familyKey), familyKey, parent.public)
            assertEquals(offset, (decoded as IncomingMessage.FromChild).snapshot.tzOffsetMinutes)
        }
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
        // No offset reported: the parent must keep dating this child by its own clock rather
        // than guess a timezone for it.
        assertNull(child.tzOffsetMinutes)

        val parentSnap = json.decodeFromString(
            ParentSnapshot.serializer(),
            """{"version":1,"policyJson":"{}"}""",
        )
        assertEquals(0, parentSnap.parentVersionCode)
    }
}
