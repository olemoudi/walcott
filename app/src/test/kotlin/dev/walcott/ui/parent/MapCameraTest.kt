package dev.walcott.ui.parent

import dev.walcott.sync.LocationPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The camera that follows a replay: where it is looking at each instant of a step, and how far
 * out it has decided to be by the time it gets there.
 *
 * What these tests defend is one promise — *nothing the replay is about ever leaves the screen,
 * and nothing on it ever lurches*. Both are properties of a whole replay rather than of a single
 * call, so most of what follows walks a trail end to end the way playback does.
 */
class MapCameraTest {

    private val viewport = 1080

    /** A trail in a straight line north, [everyM] metres between fixes. */
    private fun straight(count: Int, everyM: Double, from: Double = 40.0): List<LocationPoint> =
        List(count) { LocationPoint(lat = from + it * everyM / 110_574.0, lng = -3.7, epochMs = it * 60_000L) }

    /** Ten minutes of walking, three hops of a bus, then walking again — the case the zoom is for. */
    private fun walkThenRide(): List<LocationPoint> {
        val walk = straight(10, 60.0)
        val ride = straight(4, 1_500.0, from = walk.last().lat).drop(1)
        val after = straight(6, 60.0, from = ride.last().lat).drop(1)
        return (walk + ride + after).mapIndexed { index, point -> point.copy(epochMs = index * 60_000L) }
    }

    /** The zoom playback actually ends each step at, walked forward exactly as the loop does. */
    private fun zoomTrack(points: List<LocationPoint>, start: Double = MapCamera.MAX_ZOOM): List<Double> {
        var zoom = start
        return points.indices.map {
            zoom = MapCamera.zoomFor(points, it, zoom, viewport)
            zoom
        }
    }

    private fun pixelsApart(from: MapCamera.Center, to: LocationPoint, zoom: Double): Double =
        MapCamera.distanceM(from.lat, from.lng, to.lat, to.lng) / MapCamera.metersPerPixel(zoom, to.lat)

    @Test
    fun `the camera crosses the fix the pin is on part way through its step`() {
        val points = straight(5, 100.0)
        val crossing = MapCamera.centerAt(points, index = 2, progress = 1.0 - MapCamera.LEAD)
        assertEquals(points[2].lat, crossing.lat, 1e-9)
        assertEquals(points[2].lng, crossing.lng, 1e-9)
    }

    @Test
    fun `by the time the pin jumps the camera is already on its way to the next fix`() {
        // The whole point of the lead: the fix the pin is about to land on is not merely on
        // screen, the map has been moving towards it for a good part of the step.
        val points = straight(5, 100.0)
        val atJump = MapCamera.centerAt(points, index = 2, progress = 1.0)
        val travelled = MapCamera.distanceM(points[2].lat, points[2].lng, atJump.lat, atJump.lng)
        val segment = MapCamera.distanceM(points[2].lat, points[2].lng, points[3].lat, points[3].lng)
        assertEquals(MapCamera.LEAD * segment, travelled, 0.5)
    }

    @Test
    fun `the camera walks forward without ever doubling back`() {
        // A camera that overshoots and returns is the specific thing that makes a followed map
        // unwatchable, and it is invisible in any single frame.
        val points = walkThenRide()
        val samples = points.indices.flatMap { index ->
            (0..4).map { MapCamera.centerAt(points, index, it / 4.0) }
        }
        samples.zipWithNext { before, after ->
            assertTrue(after.lat >= before.lat - 1e-12, "camera went backwards: $before then $after")
        }
    }

    @Test
    fun `neither the pin nor the fix it is about to reach ever leaves the screen`() {
        // The promise, walked end to end: at every instant of every step, both the fix the pin is
        // standing on and the one it is about to jump to are inside the visible half-screen.
        val points = walkThenRide()
        val track = zoomTrack(points)
        points.indices.forEach { index ->
            val zoomFrom = track.getOrElse(index - 1) { track[index] }
            (0..8).forEach { tick ->
                val progress = tick / 8.0
                val zoom = zoomFrom + (track[index] - zoomFrom) * progress
                val center = MapCamera.centerAt(points, index, progress)
                assertTrue(
                    pixelsApart(center, points[index], zoom) < viewport / 2,
                    "the pin left the screen at fix $index, $progress in",
                )
                points.getOrNull(index + 1)?.let { next ->
                    assertTrue(
                        pixelsApart(center, next, zoom) < viewport / 2,
                        "the next fix was off screen at fix $index, $progress in",
                    )
                }
            }
        }
    }

    @Test
    fun `the zoom is already widening several steps before the long hop arrives`() {
        // The navigator's move: pull back BEFORE the fast stretch. The first bus hop is fix 10,
        // and the camera must not still be at walking zoom when it gets there.
        val points = walkThenRide()
        val track = zoomTrack(points)
        assertTrue(
            track[7] < MapCamera.MAX_ZOOM - 1.0,
            "three steps out from the hop the camera was still at ${track[7]}",
        )
        assertTrue(track[9] < track[7], "the widening must continue as the hop approaches: $track")
    }

    @Test
    fun `no step ever asks the eye for more than one step's worth of zoom`() {
        val points = walkThenRide()
        val track = zoomTrack(points)
        track.zipWithNext { before, after ->
            val change = after - before
            if (change < 0) {
                assertTrue(-change <= MapCamera.OUT_PER_STEP + 1e-9, "widened by ${-change} in one step: $track")
            } else {
                assertTrue(change <= MapCamera.IN_PER_STEP + 1e-9, "tightened by $change in one step: $track")
            }
        }
    }

    @Test
    fun `the camera is never tighter than the stretch it is about to show can bear`() {
        // The invariant the backward plan exists to keep: at every fix, the level is at or under
        // what framing that fix asks for, and under what any fix inside the horizon will ask for
        // once the steps left to widen are spent.
        val points = walkThenRide()
        val track = zoomTrack(points)
        points.indices.forEach { index ->
            assertTrue(
                track[index] <= MapCamera.fitZoom(points, index, viewport) + 1e-9,
                "fix $index was framed at ${track[index]}, tighter than it fits",
            )
            (index..minOf(index + MapCamera.HORIZON, points.lastIndex)).forEach { ahead ->
                val reachable = MapCamera.fitZoom(points, ahead, viewport) +
                    MapCamera.OUT_PER_STEP * (ahead - index)
                assertTrue(track[index] <= reachable + 1e-9, "fix $ahead cannot be reached from $index")
            }
        }
    }

    @Test
    fun `coming back to detail is slower than pulling away from it`() {
        // Asymmetry on purpose: being a little wide costs nothing, being a little close loses the
        // child off the edge. Also the guard against a zoom that pumps between two levels.
        assertTrue(MapCamera.IN_PER_STEP < MapCamera.OUT_PER_STEP)
        val points = walkThenRide()
        val track = zoomTrack(points)
        val widest = track.min()
        val backAt = track.indexOfLast { abs(it - widest) < 1e-9 }
        assertTrue(track.last() > widest, "the camera never came back to detail: $track")
        assertTrue(
            track.last() - widest <= MapCamera.IN_PER_STEP * (track.lastIndex - backAt) + 1e-9,
            "it tightened faster than the cap on the way back: $track",
        )
    }

    @Test
    fun `a stop-and-go ride is not zoomed in and out between its hops`() {
        // The failure the plan exists to prevent, and the one a per-step rule alone would walk
        // straight into: pull in during the pause between two long hops, and the camera spends
        // the ride pumping. Between the first hop and the last, the level may only widen.
        val stopAndGo = buildList {
            addAll(straight(3, 60.0))
            repeat(3) {
                addAll(straight(2, 1_400.0, from = last().lat).drop(1))
                addAll(straight(3, 40.0, from = last().lat).drop(1))
            }
        }.mapIndexed { index, point -> point.copy(epochMs = index * 60_000L) }
        val track = zoomTrack(stopAndGo)
        val hops = stopAndGo.indices.filter { index ->
            index < stopAndGo.lastIndex &&
                MapCamera.distanceM(
                    stopAndGo[index].lat,
                    stopAndGo[index].lng,
                    stopAndGo[index + 1].lat,
                    stopAndGo[index + 1].lng,
                ) > 1_000.0
        }
        // Not "never a hair tighter": the framed window shifts by a fix each step, so the level
        // it asks for wanders by a hundredth of a level either way. What must not happen is a
        // pull-in anyone could SEE — a few hundredths is under a percent of scale.
        (hops.first()..hops.last()).forEach { index ->
            assertTrue(
                track[index] - track[index - 1] < 0.1,
                "the camera pulled in visibly at $index in the middle of the ride: $track",
            )
        }
    }

    @Test
    fun `framing keeps the stretch it shows inside the safe part of the screen`() {
        val points = walkThenRide()
        val zoom = MapCamera.fitZoom(points, index = 11, viewportPx = viewport)
        val across = MapCamera.distanceM(points[10].lat, points[10].lng, points[13].lat, points[13].lng) /
            MapCamera.metersPerPixel(zoom, points[11].lat)
        assertTrue(across < viewport * 0.6, "the framed stretch took $across px of $viewport")
    }

    @Test
    fun `a child who has not moved is shown as close as the replay ever goes`() {
        val still = List(6) { LocationPoint(lat = 40.0, lng = -3.7, epochMs = it * 60_000L) }
        assertEquals(MapCamera.MAX_ZOOM, MapCamera.fitZoom(still, 3, viewport))
        assertEquals(MapCamera.MAX_ZOOM, MapCamera.zoomFor(still, 3, MapCamera.MAX_ZOOM, viewport))
    }

    @Test
    fun `a hop too big to frame is shown as wide as the replay ever goes rather than not at all`() {
        // Madrid to Barcelona between two fixes: nothing sensible frames it, and the answer must
        // be the widest level rather than an off-scale one that osmdroid would refuse.
        val jump = listOf(
            LocationPoint(lat = 40.4, lng = -3.7, epochMs = 0L),
            LocationPoint(lat = 41.4, lng = 2.2, epochMs = 60_000L),
        )
        assertEquals(MapCamera.MIN_ZOOM, MapCamera.fitZoom(jump, 0, viewport))
        assertEquals(MapCamera.MIN_ZOOM, MapCamera.zoomFor(jump, 0, MapCamera.MAX_ZOOM, viewport))
    }

    @Test
    fun `the ends of the trail hold still instead of running off it`() {
        // Before the first fix there is nothing to lead in from, and after the last one nothing
        // to lean towards; both must clamp rather than extrapolate into empty map.
        val points = straight(4, 100.0)
        assertEquals(points[0].lat, MapCamera.centerAt(points, 0, 0.0).lat, 1e-9)
        assertEquals(points.last().lat, MapCamera.centerAt(points, points.lastIndex, 1.0).lat, 1e-9)
    }

    @Test
    fun `a trail that shrank under the scrubber cannot point the camera off the end`() {
        // The trail is republished on every check-in and can come back shorter; a stale index
        // must land on the trail rather than out of bounds.
        val points = straight(3, 100.0)
        assertEquals(points.last().lat, MapCamera.centerAt(points, 99, 0.5).lat, 1e-9)
        assertEquals(MapCamera.MAX_ZOOM, MapCamera.zoomFor(emptyList(), 0, MapCamera.MAX_ZOOM, viewport))
        assertEquals(0.0, MapCamera.centerAt(emptyList(), 0, 0.5).lat)
    }

    @Test
    fun `an unmeasured map still frames something usable`() {
        // The first frames of a replay can land before the map has been laid out; a zero-wide
        // viewport must not resolve to the widest level the app owns.
        val points = walkThenRide()
        assertTrue(MapCamera.fitZoom(points, 11, viewportPx = 0) > MapCamera.MIN_ZOOM)
    }

    @Test
    fun `the way in is eased from nearby and cut from across town`() {
        // The one move a replay makes that is not part of the walk. From a screen away it is a
        // pan the eye can follow; from the far end of a trail it is a blur, and the loop cuts.
        val here = MapCamera.Center(40.4150, -3.6900)
        val nextStreet = MapCamera.Center(40.4155, -3.6905)
        val acrossTown = MapCamera.Center(40.4600, -3.6300)
        assertTrue(MapCamera.withinOneScreen(here, nextStreet, MapCamera.MAX_ZOOM, viewport))
        assertTrue(!MapCamera.withinOneScreen(here, acrossTown, MapCamera.MAX_ZOOM, viewport))
        // Same distance, seen from far enough out, IS one screen — the rule is about the screen,
        // not about metres.
        assertTrue(MapCamera.withinOneScreen(here, acrossTown, MapCamera.MIN_ZOOM, viewport))
    }

    @Test
    fun `the way in from wherever the map was left starts and ends where it should`() {
        val from = MapCamera.Center(40.0, -3.7)
        val to = MapCamera.Center(41.0, -3.6)
        assertEquals(from, MapCamera.ease(from, to, 0.0))
        assertEquals(to.lat, MapCamera.ease(from, to, 1.0).lat, 1e-9)
        // Smoothstepped: half way through the step, half way there — and gentler at both ends.
        assertEquals(40.5, MapCamera.ease(from, to, 0.5).lat, 1e-9)
        assertTrue(MapCamera.ease(from, to, 0.1).lat - from.lat < 0.1 * (to.lat - from.lat))
    }
}
