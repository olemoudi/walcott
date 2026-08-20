package dev.walcott.ui.parent

import dev.walcott.sync.LocationPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The replay arithmetic behind the parent's map: the comet tail, the fade, and the scrubber. */
class MapTrailTest {

    private fun trail(count: Int, everyMs: Long = 60_000L): List<LocationPoint> =
        List(count) { LocationPoint(lat = 40.0 + it / 1e4, lng = -3.7, epochMs = it * everyMs) }

    @Test
    fun `tail ends on the selected fix and never exceeds its length`() {
        // Derived from the constant rather than repeating it: the length is a tuning decision that
        // has already moved once, and a test that restates the number just has to be edited
        // alongside it — which tests nothing. What must hold whatever it is set to is that the
        // tail ENDS on the selected fix and is exactly that long.
        val tail = MapTrail.tailRange(selected = 29, size = 120)
        assertEquals(29, tail.last)
        assertEquals(MapTrail.TAIL_POINTS, tail.count())
        assertEquals(29 - MapTrail.TAIL_POINTS + 1, tail.first)
    }

    @Test
    fun `tail is clipped rather than wrapped at the start of the trail`() {
        assertEquals(0..0, MapTrail.tailRange(selected = 0, size = 120))
        assertEquals(0..3, MapTrail.tailRange(selected = 3, size = 120))
    }

    @Test
    fun `tail survives a selection past the end of a shrunken trail`() {
        // The trail is republished on every check-in and can come back shorter; the scrubber's
        // index is derived from it but a stale one must not index out of bounds.
        assertEquals(0..2, MapTrail.tailRange(selected = 99, size = 3))
        assertEquals(IntRange.EMPTY, MapTrail.tailRange(selected = 0, size = 0))
    }

    @Test
    fun `fade runs from gone at the far end to full at the selected fix`() {
        assertEquals(0f, MapTrail.fade(0, 10))
        assertEquals(1f, MapTrail.fade(9, 10))
    }

    @Test
    fun `fade increases with every step towards the selected fix`() {
        val steps = (0 until 10).map { MapTrail.fade(it, 10) }
        steps.zipWithNext { older, newer ->
            assertTrue(newer > older, "fade must be monotonic, got $steps")
        }
        // Distinct values matter beyond looks: osmdroid's gradient paint list falls back to a
        // flat colour when two neighbouring stops are equal, and that fallback leaks the flat
        // colour's alpha into every later segment.
        assertEquals(steps.size, steps.distinct().size)
    }

    @Test
    fun `a single-fix tail is fully present rather than invisible`() {
        assertEquals(1f, MapTrail.fade(0, 1))
        assertEquals(1f, MapTrail.hue(0, 1))
    }

    @Test
    fun `colour reaches the head faster than the line fades in`() {
        // The point of splitting the two curves: the gradient has to be spent where the trail is
        // still opaque. Anywhere before the head, the colour must be further along than the alpha
        // — otherwise the far colour only ever shows on pixels nobody can see.
        (1 until 9).forEach { position ->
            val hue = MapTrail.hue(position, 10)
            val fade = MapTrail.fade(position, 10)
            assertTrue(hue < fade, "at $position the hue ($hue) must lead the fade ($fade)")
        }
        assertEquals(MapTrail.fade(9, 10), MapTrail.hue(9, 10)) // both land on the head
    }

    @Test
    fun `colour runs the whole way from the tail to the head`() {
        assertEquals(0f, MapTrail.hue(0, 10))
        assertEquals(1f, MapTrail.hue(9, 10))
        val steps = (0 until 10).map { MapTrail.hue(it, 10) }
        steps.zipWithNext { older, newer -> assertTrue(newer > older, "hue must be monotonic: $steps") }
    }

    @Test
    fun `scrubbing lands on the newest fix at or before the chosen instant`() {
        val points = trail(5) // 0, 60k, 120k, 180k, 240k
        assertEquals(2, MapTrail.indexAt(points, 120_000L))
        assertEquals(2, MapTrail.indexAt(points, 179_999L))
        assertEquals(4, MapTrail.indexAt(points, Long.MAX_VALUE))
    }

    @Test
    fun `an instant older than the whole trail parks on its oldest fix`() {
        // Fixes age out of the 48h window while the parent is looking at them; falling off the
        // start of the trail must not read as index -1.
        assertEquals(0, MapTrail.indexAt(trail(5), -1L))
        assertEquals(0, MapTrail.indexAt(emptyList(), 1_000L))
    }

    @Test
    fun `playback speeds bracket the default and step faster as they go`() {
        assertEquals(1f, MapTrail.SPEEDS[MapTrail.DEFAULT_SPEED])
        val delays = MapTrail.SPEEDS.indices.map { MapTrail.stepMs(it) }
        delays.zipWithNext { slower, faster ->
            assertTrue(faster < slower, "a faster speed must wait less, got $delays")
        }
        assertTrue(delays.all { it > 0 }, "a zero delay is a busy loop, got $delays")
    }

    @Test
    fun `cycling the speed wraps at the fastest`() {
        assertEquals(1, MapTrail.nextSpeed(0))
        assertEquals(0, MapTrail.nextSpeed(MapTrail.SPEEDS.lastIndex))
    }

    @Test
    fun `an out-of-range speed index still yields a usable delay`() {
        assertEquals(MapTrail.stepMs(0), MapTrail.stepMs(-1))
        assertEquals(MapTrail.stepMs(MapTrail.SPEEDS.lastIndex), MapTrail.stepMs(99))
    }
}
