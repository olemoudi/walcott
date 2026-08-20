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
    fun `a trail that fits under the budget is not thinned to fit a bucket`() {
        // The bug this pins: the tier spacing ran BEFORE anything counted the budget, so a band
        // thinned to one fix per half hour stayed there even with room for four times as many.
        // A real family's phone — ten minutes apart awake, three quarters of an hour asleep —
        // published 80 of 187 fixes into a 120-point budget, and 49 of 197 on a longer night.
        // Nothing was over budget; the points were dropped before anything looked.
        //
        // The sibling of `thins older fixes to roughly one per bucket`, which feeds a trail dense
        // enough to saturate the budget and so still sees strict hourly buckets. Both are true:
        // the spacing is a priority when the budget binds, and an allowance when it does not.
        val points = trail(spanMs = 37 * hour, everyMs = 12 * 60 * 1000L) // 186 fixes
        assertTrue(points.size > LocationTrail.MAX_POINTS) { "the fixture must exceed the budget" }

        val out = LocationTrail.compress(points, now)
        assertEquals(LocationTrail.MAX_POINTS, out.size) { "spare budget must be spent, got ${out.size}" }
    }

    @Test
    fun `spare budget buys detail nearest the present first`() {
        // Where the reclaimed points land matters as much as that they are reclaimed: a parent
        // scrubs the recent end, so the finest band is served before the older ones. Without this
        // the refill would be free to spend a whole budget on the far side of two days ago.
        val points = trail(spanMs = 37 * hour, everyMs = 12 * 60 * 1000L)
        val out = LocationTrail.compress(points, now)

        val lastSixHours = out.count { now - it.epochMs <= 6 * hour }
        val raw = points.count { now - it.epochMs <= 6 * hour }
        assertEquals(raw, lastSixHours) { "the recent band should be whole before older ones gain" }
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
    fun `a dense recent burst cannot evict the older trail`() {
        // The regression: four hours of live tracking at one fix a minute, on top of two days
        // of ordinary 30-minute sampling. The recent burst used to swallow the whole budget and
        // the parent's two-day map silently became a two-hour one.
        val live = trail(spanMs = 4 * hour, everyMs = 60 * 1000L)
        val ordinary = generateSequence(4 * hour) { it + 30 * 60 * 1000L }
            .takeWhile { it <= 47 * hour }.map { pointAt(it) }.toList()
        val out = LocationTrail.compress(live + ordinary, now)

        assertTrue(out.size <= LocationTrail.MAX_POINTS) { "got ${out.size}" }
        val older = out.filter { now - it.epochMs > 6 * hour }
        assertTrue(older.size >= 12) { "the day-scale trail must survive a burst, got ${older.size}" }
        val oldest = out.minByOrNull { it.epochMs }!!
        assertTrue(now - oldest.epochMs > 40 * hour) {
            "the trail must still span two days, oldest is ${(now - oldest.epochMs) / hour}h old"
        }
    }

    @Test
    fun `the last 45 minutes keep every fix while a session runs`() {
        val out = LocationTrail.compress(trail(spanMs = 4 * hour, everyMs = 60 * 1000L), now)
        val inBand = out.filter { now - it.epochMs <= 45 * 60 * 1000L }
        // 46 fixes fall in the band (0..45 min inclusive); all of them must come through.
        assertEquals(46, inBand.size) { "the watched window must not be thinned" }
    }

    @Test
    fun `thinning a band spreads the survivors instead of collapsing them onto one end`() {
        // A band squeezed to a floor must still describe the whole band. Taking its newest few
        // would be the same bug the floors exist to stop, one level down.
        val out = LocationTrail.compress(trail(spanMs = 47 * hour, everyMs = 60 * 1000L), now)
        val oldBand = out.filter { now - it.epochMs > 24 * hour }.sortedBy { it.epochMs }
        assertTrue(oldBand.size >= 2) { "the oldest band must keep its floor" }
        val span = oldBand.last().epochMs - oldBand.first().epochMs
        assertTrue(span > 12 * hour) { "survivors must span the band, they span ${span / hour}h" }
    }

    @Test
    fun `a smaller budget thins the trail rather than shortening it`() {
        // What SnapshotFit does when the rest of the snapshot leaves less room than hoped.
        val points = trail(spanMs = 47 * hour, everyMs = 5 * 60 * 1000L)
        val out = LocationTrail.compress(points, now, budget = 60)
        assertTrue(out.size <= 60) { "got ${out.size}" }
        assertEquals(now, out.last().epochMs, "the current position always survives")
        val oldest = out.minByOrNull { it.epochMs }!!
        assertTrue(now - oldest.epochMs > 40 * hour) { "a thinner trail must still span two days" }
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
