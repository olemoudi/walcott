package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnapshotFitTest {

    private val key = FamilyCrypto.generateFamilyKey()
    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun trail(points: Int): List<LocationPoint> =
        List(points) { LocationPoint(40.4 + it / 1e5, -3.7 - it / 1e5, now - it * 15 * 60_000L, 8f) }

    private fun apps(count: Int): List<InstalledAppInfo> =
        List(count) { InstalledAppInfo("com.example.application.number$it", "Example Application Number $it") }

    private fun snapshot(apps: Int = 40, trailPoints: Int = 100, historyDays: Int = 7) = ChildSnapshot(
        deviceId = "device-1",
        displayName = "Test child",
        version = 9,
        epochDay = 20_000,
        usage = List(6) { UsageEntry("cat$it", 3600) },
        history = List(historyDays) { d -> DayUsage(20_000L - d, List(6) { UsageEntry("cat$it", 3600) }) },
        apps = apps(apps),
        locations = trail(trailPoints),
    )

    private fun decode(encoded: String): ChildSnapshot {
        val pair = FamilyCrypto.generateSigningKeyPair()
        return (SyncProtocol.decode(encoded, key, pair.public) as IncomingMessage.FromChild).snapshot
    }

    @Test
    fun `a normal snapshot is sent in full`() {
        val result = SnapshotFit.encodeChild(snapshot(), key)
        assertNull(result.degraded)
        val out = decode(result.encoded)
        assertEquals(100, out.locations.size)
        assertEquals(40, out.apps.size)
        assertEquals(7, out.history.size)
    }

    @Test
    fun `an oversized snapshot sacrifices the trail first`() {
        // Enough apps to overflow with a full trail but fit once the trail is dropped.
        var appCount = 100
        var result = SnapshotFit.encodeChild(snapshot(apps = appCount), key)
        while (result.degraded == null && appCount < 400) {
            appCount += 20
            result = SnapshotFit.encodeChild(snapshot(apps = appCount), key)
        }
        assertTrue(result.degraded != null) { "could not build an oversized snapshot" }
        assertTrue(result.encoded.length <= SnapshotFit.MAX_BYTES)
        val out = decode(result.encoded)
        assertTrue(out.locations.size <= 1) { "trail must be the first sacrifice" }
    }

    @Test
    fun `even a monster snapshot ends up under the cap`() {
        val monster = snapshot(apps = 1000, trailPoints = 120, historyDays = 7)
        val result = SnapshotFit.encodeChild(monster, key)
        assertTrue(result.encoded.length <= SnapshotFit.MAX_BYTES) { "got ${result.encoded.length}" }
        // The fixed fields must survive every degradation step.
        val out = decode(result.encoded)
        assertEquals("device-1", out.deviceId)
        assertEquals(9, out.version)
        assertEquals(6, out.usage.size)
    }

    @Test
    fun `degradation never drops today's usage or identity fields`() {
        val result = SnapshotFit.encodeChild(snapshot(apps = 1000), key, maxBytes = 1200)
        assertTrue(result.encoded.length <= 1200)
        val out = decode(result.encoded)
        assertEquals("device-1", out.deviceId)
        assertEquals("Test child", out.displayName)
        assertEquals(6, out.usage.size)
    }

    @Test
    fun `the degradation report names what was cut`() {
        val result = SnapshotFit.encodeChild(snapshot(apps = 1000), key, maxBytes = 1200)
        assertTrue(result.degraded!!.startsWith("trail,history"))
    }
}
