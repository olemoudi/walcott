package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationTrailTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun pointAt(ageMs: Long, lat: Double = 40.4, lng: Double = -3.7, mock: Boolean = false) =
        LocationPoint(lat = lat, lng = lng, epochMs = now - ageMs, accuracyM = 5f, mock = mock)

    /** A fix every [everyMs] going back [spanMs], newest first. */
    private fun trail(spanMs: Long, everyMs: Long): List<LocationPoint> =
        generateSequence(0L) { it + everyMs }.takeWhile { it <= spanMs }.map { pointAt(it) }.toList()

    @Test
    fun `empty input yields empty trail`() {
        assertEquals(emptyList<LocationPoint>(), LocationTrail.compress(emptyList(), now))
    }

    @Test
    fun `drops fixes older than the 48h window`() {
        val points = listOf(pointAt(1 * hour), pointAt(47 * hour), pointAt(49 * hour), pointAt(200 * hour))
        val out = LocationTrail.compress(points, now)
        assertTrue(out.all { now - it.epochMs <= LocationTrail.WINDOW_MS }, "stale fixes must be dropped")
        assertEquals(2, out.size)
    }

    @Test
    fun `returns points oldest first so the timeline scrubs forward`() {
        val out = LocationTrail.compress(trail(spanMs = 40 * hour, everyMs = hour), now)
        assertEquals(out.sortedBy { it.epochMs }, out)
    }

    @Test
    fun `keeps the newest fix, which is the child's current position`() {
        val points = trail(spanMs = 47 * hour, everyMs = 5 * 60 * 1000L)
        val newest = points.maxByOrNull { it.epochMs }!!
        val out = LocationTrail.compress(points, now)
        assertEquals(newest.epochMs, out.last().epochMs)
    }

    @Test
    fun `keeps every fix within the last 6h`() {
        // 5-minute sampling for 5h sits entirely in the full-detail tier.
        val points = trail(spanMs = 5 * hour, everyMs = 5 * 60 * 1000L)
        val out = LocationTrail.compress(points, now)
        assertEquals(points.size, out.size)
    }

    @Test
    fun `thins older fixes to roughly one per bucket`() {
        val points = trail(spanMs = 47 * hour, everyMs = 5 * 60 * 1000L)
        val out = LocationTrail.compress(points, now)

        val olderThanDay = out.filter { now - it.epochMs > 24 * hour }
        // 24-48h is bucketed hourly, so ~24 points, never the 288 raw fixes in that span.
        assertTrue(olderThanDay.size <= 26) { "expected hourly buckets, got ${olderThanDay.size}" }
        val gaps = olderThanDay.sortedBy { it.epochMs }.zipWithNext { a, b -> b.epochMs - a.epochMs }
        assertTrue(gaps.all { it >= 55 * 60 * 1000L }) { "hourly tier must not keep denser fixes: $gaps" }
    }

    @Test
    fun `never exceeds the point budget`() {
        val points = trail(spanMs = 47 * hour, everyMs = 60 * 1000L) // a fix every minute
        val out = LocationTrail.compress(points, now)
        assertTrue(out.size <= LocationTrail.MAX_POINTS) { "got ${out.size}" }
    }

    @Test
    fun `keeps mock fixes even where the tier would thin them out`() {
        // A spoofed fix deep in the hourly tier, surrounded by dense honest ones.
        val points = trail(spanMs = 47 * hour, everyMs = 5 * 60 * 1000L) +
            pointAt(30 * hour + 7 * 60 * 1000L, mock = true)
        val out = LocationTrail.compress(points, now)
        assertTrue(out.any { it.mock }, "spoofing evidence must survive decimation")
    }

    @Test
    fun `budget trimming keeps the newest fixes, not the oldest`() {
        val points = trail(spanMs = 47 * hour, everyMs = 30 * 1000L)
        val out = LocationTrail.compress(points, now, budget = 10)
        assertEquals(10, out.size)
        assertEquals(now, out.last().epochMs)
        // All ten should come from the recent end, not from two days ago.
        assertTrue(out.all { now - it.epochMs < hour })
    }

    @Test
    fun `rounds coordinates to about a metre`() {
        val out = LocationTrail.compress(listOf(pointAt(0, lat = 40.412345678, lng = -3.712345678)), now)
        assertEquals(40.41235, out.single().lat)
        assertEquals(-3.71235, out.single().lng)
    }

    @Test
    fun `rounding keeps fixes accurate to within a couple of metres`() {
        val original = pointAt(0, lat = 40.412345678, lng = -3.712345678)
        val out = LocationTrail.compress(listOf(original), now).single()
        // ~1e-5 degrees is ~1.1m of latitude; the error must stay well inside GPS noise.
        assertTrue(kotlin.math.abs(out.lat - original.lat) < 1e-5)
        assertTrue(kotlin.math.abs(out.lng - original.lng) < 1e-5)
    }

    @Test
    fun `preserves accuracy and mock metadata on kept fixes`() {
        val out = LocationTrail.compress(listOf(pointAt(0, mock = true)), now).single()
        assertEquals(5f, out.accuracyM)
        assertTrue(out.mock)
    }

    @Test
    fun `accepts unsorted input`() {
        val points = trail(spanMs = 10 * hour, everyMs = 20 * 60 * 1000L).shuffled()
        val out = LocationTrail.compress(points, now)
        assertEquals(out.sortedBy { it.epochMs }, out)
        assertEquals(now, out.last().epochMs)
    }

    @Test
    fun `a compressed 48h trail fits comfortably in one ntfy message`() {
        // The regression this whole class exists for: an oversized publish is rejected
        // with HTTP 413 and the child's check-in is silently lost.
        val key = FamilyCrypto.generateFamilyKey()
        val snapshot = ChildSnapshot(
            deviceId = "device-1",
            displayName = "Test child",
            version = 7,
            epochDay = 20_000,
            usage = List(6) { UsageEntry("cat$it", 3600) },
            history = List(7) { day -> DayUsage(20_000L - day, List(6) { UsageEntry("cat$it", 3600) }) },
            apps = List(60) { InstalledAppInfo("com.example.app$it", "Example application $it") },
            locations = LocationTrail.compress(trail(spanMs = 47 * hour, everyMs = 5 * 60 * 1000L), now),
        )
        val encoded = SyncProtocol.encodeChild(snapshot, key)
        assertTrue(encoded.length < 4096) { "child snapshot is ${encoded.length} bytes, over the ntfy cap" }
    }
}
