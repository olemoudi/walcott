package dev.walcott.ui.parent

import dev.walcott.sync.LocationPoint
import kotlin.math.pow

/**
 * The arithmetic behind the map's replay: which fixes the trail still draws, how far each one
 * has faded, how fast playback steps, and where an instant lands in a trail that is republished
 * whole on every check-in.
 *
 * Kept out of the composable on purpose. This is the part that can be wrong in a way no
 * screenshot would show — an off-by-one in the tail, a scrubber that silently jumps a fix — so
 * it is the part that gets tests.
 */
internal object MapTrail {

    /**
     * How many fixes the trail shows, the selected one included.
     *
     * The trail is a comet, not a route: it says "this is where they have just been", not "here
     * is the whole day drawn over the streets". What keeps the two apart is the fade rather than
     * this number — by the far end the line has gone to nothing (see [fade]) — so this sets how
     * much of the recent past is visible AT ALL, and the last third of it is barely there.
     *
     * Fifteen, in the sampling intervals the app actually uses: a couple of hours on the ordinary
     * fifteen-minute cadence, and about a quarter of an hour under close tracking, which is the
     * mode where the shape of the last few minutes is the thing being watched.
     */
    const val TAIL_POINTS = 15

    /** Playback speeds on offer, slowest first. */
    val SPEEDS = listOf(0.5f, 1f, 2f, 4f)

    /** Index into [SPEEDS] that playback starts at. */
    val DEFAULT_SPEED: Int = SPEEDS.indexOf(1f)

    /**
     * One step per fix at 1x.
     *
     * A quarter of the pace it used to run at. 220ms a fix read as motion, which was the whole
     * intent, and moved a marker across a street faster than anyone could see WHERE it had gone —
     * a replay you have to watch twice is not a replay. At this pace 1x is something a parent can
     * follow, and nothing is lost by it: the speed control goes up to 4x, which is exactly the old
     * 1x, for anyone who wants to skim a whole afternoon.
     */
    private const val STEP_MS = 880L

    /**
     * Curve of the fade along the tail.
     *
     * Below 1, so the tail holds its shape for most of its length and then gives out. A straight
     * ramp leaves the middle of the trail at half strength, which over map tiles reads as a
     * washed-out line rather than as something with a head and a tail.
     */
    private const val FADE_EXPONENT = 0.75f

    /**
     * Curve of the colour along the tail. Above 1, the mirror of [FADE_EXPONENT]: the hue has to
     * be spent where the line is opaque, so it moves fastest exactly where the fade moves slowest.
     */
    private const val HUE_EXPONENT = 1.6f

    /** Delay between playback steps at `SPEEDS[speedIndex]`. */
    fun stepMs(speedIndex: Int): Long =
        (STEP_MS / SPEEDS[speedIndex.coerceIn(SPEEDS.indices)]).toLong()

    /** The next speed to cycle to, wrapping at the fastest. */
    fun nextSpeed(speedIndex: Int): Int = (speedIndex + 1) % SPEEDS.size

    /**
     * The fixes the trail draws for [selected]: at most [TAIL_POINTS], ending on the selected
     * one. Empty for an empty trail, which is the one case the caller cannot draw at all.
     */
    fun tailRange(selected: Int, size: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val last = selected.coerceIn(0, size - 1)
        return (last - TAIL_POINTS + 1).coerceAtLeast(0)..last
    }

    /**
     * How present the [position]-th fix of a [tailSize]-long tail is: 0 at the far end, where the
     * trail has faded to nothing, 1 at the selected fix. This is the alpha.
     */
    fun fade(position: Int, tailSize: Int): Float =
        along(position, tailSize).pow(FADE_EXPONENT)

    /**
     * How far the [position]-th fix has travelled from the tail's colour to the head's, 0 to 1.
     *
     * Separate from [fade], and it has to be. Sharing one curve looked tidy and drew a trail with
     * no gradient in it at all: the colour only reached the far end where the alpha had already
     * taken the line to nothing, so every pixel a parent could actually see was the head's violet.
     * Steeper than the fade, so the hue has finished most of its journey inside the stretch that
     * is still opaque — which is the only stretch that can tell anyone anything.
     */
    fun hue(position: Int, tailSize: Int): Float =
        along(position, tailSize).pow(HUE_EXPONENT)

    /** Where [position] sits along a [tailSize]-long tail: 0 at the far end, 1 at the head. */
    private fun along(position: Int, tailSize: Int): Float {
        if (tailSize <= 1) return 1f
        return position.coerceIn(0, tailSize - 1).toFloat() / (tailSize - 1)
    }

    /**
     * Where [epochMs] lands in [points] (oldest first): the newest fix at or before it, or the
     * oldest fix when the scrubber is parked before the trail begins.
     *
     * The scrubber remembers an INSTANT, not an index, and this is what turns one into the other.
     * The child republishes its whole trail on every check-in, thinned by age on the way (see
     * `LocationTrail`), so index 40 is a different moment a minute later — which is how a parent
     * replaying the afternoon used to be thrown back to "now" the instant a fix arrived.
     */
    fun indexAt(points: List<LocationPoint>, epochMs: Long): Int {
        if (points.isEmpty()) return 0
        return points.indexOfLast { it.epochMs <= epochMs }.coerceAtLeast(0)
    }
}
