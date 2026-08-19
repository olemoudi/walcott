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

    /**
     * Hostnames with no shared structure. Realistic-looking domains share so many bytes that gzip
     * folds a wildly oversized list back under the cap, which would let these tests pass without
     * the degradation they are here to check.
     */
    private fun noisyDomains(count: Int, seed: Int): List<String> {
        val rnd = java.util.Random(seed.toLong())
        return List(count) { buildString { repeat(14) { append('a' + rnd.nextInt(26)) } } + ".com" }
    }

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
    fun `an oversized snapshot thins the trail first, and does not drop it`() {
        // Enough apps to overflow with a full trail but fit once the trail is thinned.
        var appCount = 100
        var result = SnapshotFit.encodeChild(snapshot(apps = appCount), key)
        while (result.degraded == null && appCount < 400) {
            appCount += 20
            result = SnapshotFit.encodeChild(snapshot(apps = appCount), key)
        }
        assertTrue(result.degraded != null) { "could not build an oversized snapshot" }
        assertTrue(result.encoded.length <= SnapshotFit.MAX_BYTES)
        val out = decode(result.encoded)
        // The regression this replaced: the trail went from 100 points straight to one, so a
        // parent with a long app list saw a single pin and no way to know history was cut.
        assertTrue(out.locations.size > 1) { "the trail must be thinned, not dropped" }
        assertTrue(out.locations.size < 100) { "something has to give at this size" }
        assertEquals(
            trail(100).last().epochMs,
            out.locations.last().epochMs,
            "the current position survives every step",
        )
    }

    @Test
    fun `thinning the trail keeps its span, not just its newest end`() {
        val result = SnapshotFit.encodeChild(snapshot(apps = 260), key)
        val out = decode(result.encoded)
        assertTrue(out.locations.size > 1) { "expected a thinned trail, got ${out.locations.size}" }
        val full = trail(100)
        assertEquals(full.first().epochMs, out.locations.first().epochMs, "the oldest fix survives")
        assertEquals(full.last().epochMs, out.locations.last().epochMs, "the newest fix survives")
    }

    @Test
    fun `a trail older than the publish window is thinned, never emptied`() {
        // A phone that has been off for days: every fix has aged out. Re-compressing against the
        // clock would return nothing at all and take the child's last known position with it.
        val ancient = List(100) {
            LocationPoint(40.4 + it / 1e5, -3.7 - it / 1e5, now - LocationTrail.WINDOW_MS - it * 60_000L, 8f)
        }
        val result = SnapshotFit.encodeChild(snapshot(apps = 400).copy(locations = ancient), key)
        val out = decode(result.encoded)
        assertTrue(out.locations.isNotEmpty()) { "the last known position must never be dropped here" }
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

    @Test
    fun `a long batch of domain slices still fits, cut only at slice boundaries`() {
        val chunks = List(20) { i ->
            DomainChunk("batch-1", "com.game", "Game", i, 20, noisyDomains(10, i))
        }
        val result = SnapshotFit.encodeChild(snapshot(apps = 60).copy(domainChunks = chunks), key)
        assertTrue(result.encoded.length <= SnapshotFit.MAX_BYTES) { "got ${result.encoded.length}" }
        val out = decode(result.encoded)
        assertTrue(out.domainChunks.size < 20) { "an oversized batch has to be thinned" }
        // Survivors are a prefix with their contents intact: the ones left behind ride the next
        // publish, and a half-emptied slice would assemble into a batch that never completes.
        assertEquals(chunks.take(out.domainChunks.size), out.domainChunks)
    }

    @Test
    fun `asks are cut too, so they can no longer overflow the message on their own`() {
        // The bug this guards: everything after the app list used to be assumed small, and the
        // final attempt was returned unmeasured. A parent-chosen list of domains inside an ask
        // broke that assumption, publishing over the cap — HTTP 413, and the child goes quiet.
        val asks = List(8) { i ->
            ChildRequest(
                requestId = "ask-$i",
                kind = ChildRequest.KIND_DOMAINS,
                text = DomainAsk.encode("Game", "com.game", noisyDomains(40, 100 + i)),
                createdAtEpochMs = now,
            )
        }
        val result = SnapshotFit.encodeChild(snapshot(apps = 0, trailPoints = 0, historyDays = 0).copy(asks = asks), key)
        assertTrue(result.encoded.length <= SnapshotFit.MAX_BYTES) { "got ${result.encoded.length}" }
        val out = decode(result.encoded)
        assertTrue(out.asks.size < 8) { "asks are the last thing cut, but they are cut" }
        assertTrue(result.degraded!!.contains("asks:")) { "the parent's log has to say a voice was dropped" }
    }

    @Test
    fun `the fixed fields survive even when nothing else can`() {
        // A cap so small that every variable field has to go. The snapshot still has to decode:
        // an unmeasured return here is the 413 all over again.
        val result = SnapshotFit.encodeChild(
            snapshot(apps = 400).copy(
                asks = List(4) { ChildRequest("ask-$it", ChildRequest.KIND_OTHER, "please", now) },
                domainChunks = List(4) { DomainChunk("b", "com.game", "Game", it, 4, noisyDomains(10, it)) },
            ),
            key,
            maxBytes = 700,
        )
        val out = decode(result.encoded)
        assertEquals("device-1", out.deviceId)
        assertEquals(9, out.version)
        assertEquals(20_000L, out.epochDay)
    }
}
