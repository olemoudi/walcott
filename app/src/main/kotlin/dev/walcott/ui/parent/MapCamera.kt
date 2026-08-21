package dev.walcott.ui.parent

import dev.walcott.sync.LocationPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the map looks while a replay plays: the centre the camera glides to, and how far out it
 * has to be zoomed by the time it gets there.
 *
 * The pin steps from fix to fix — it is a sequence of positions, not a moving thing — so the
 * camera is what makes a replay followable. Two rules, and both are the ones a car navigator
 * follows:
 *
 *  - **Move before the pin does.** The camera runs along the trail at a constant pace, [LEAD] of
 *    a step AHEAD of the pin. By the moment the pin jumps to the next fix, the camera has already
 *    covered [LEAD] of that segment, so the fix it is about to land on has been on screen for a
 *    while; the rest of the centring is done with the first part of the time the pin spends
 *    there. Nothing ever appears from off-screen.
 *  - **Zoom out for what is coming, not for what has happened.** [zoomFor] reads the fixes still
 *    ahead and starts widening early enough that no single step has to make a big jump — the same
 *    reason a navigator pulls back before a fast stretch rather than after it. Coming back to
 *    detail is deliberately slower than pulling out ([IN_PER_STEP] against [OUT_PER_STEP]): being
 *    a little wider than necessary costs nothing, and being too close for one step means the
 *    child leaves the screen.
 *
 * Pure arithmetic, kept out of the composable, for the same reason [MapTrail] is: this is the
 * part that can be subtly wrong in a way no screenshot proves — a camera that arrives late, a
 * zoom that pumps between two levels — so it is the part that gets tests.
 */
internal object MapCamera {

    /**
     * How far into the NEXT segment the camera has travelled by the time the pin jumps onto it.
     *
     * Equivalently: the camera runs this fraction of a step ahead of the pin, in time. Below
     * half, because the camera should be leaning towards where the child is going, not standing
     * on top of it; the fix the pin is currently on would otherwise be pushed towards the back
     * edge of the screen, which reads as looking the wrong way.
     */
    const val LEAD = 0.4

    /**
     * Closest the replay will pull in, and therefore the level a walk is watched at: anything
     * whose window fits inside it asks for more and is capped here.
     *
     * A shade over a level above where it started, which is 2.3x the scale — arrived at from a
     * real phone in three goes, because "close enough to see which side of the street" is not a
     * thing arithmetic can settle. A phone screen now holds about four hundred metres.
     *
     * It costs nothing elsewhere: only the stretches that already fit ask for this much, so it is
     * the slow ones that move and a bus ride is framed exactly as it was. What it does spend is
     * pace — a sixty-metre step is 155px here against 66px at the level this started at, which is
     * still well under a fifth of the screen per step.
     *
     * There is room above this, but not much: osmdroid clamps the map to what the tile source
     * has, and MAPNIK stops at 19.
     */
    const val MAX_ZOOM = 18.23

    /**
     * Widest the replay will pull back to. About 60km across a phone screen — a fix on the far
     * side of a city still lands a couple of hundred pixels away rather than off the map, so even
     * a child who got in a car between two fixes is followed rather than teleported.
     */
    const val MIN_ZOOM = 10.0

    /**
     * Most the zoom may WIDEN in one step: a 1.5x change of scale, which over the best part of a
     * second reads as pulling back rather than as the map falling away.
     */
    const val OUT_PER_STEP = 0.6

    /**
     * Most the zoom may TIGHTEN in one step. Gentler than [OUT_PER_STEP] on purpose — being a
     * little wider than necessary costs nothing, being a little closer loses the child off the
     * edge — but not so gentle that a walk after a bus ride is watched from the sky: at a quarter
     * of a level per step it took fourteen seconds to come back from a ride, which is most of a
     * replay spent too far out to see a street.
     */
    const val IN_PER_STEP = 0.4

    /**
     * How many fixes ahead the zoom is planned over.
     *
     * Enough steps to cross the whole zoom range at [OUT_PER_STEP], so a jump anywhere inside the
     * horizon can always be reached gradually: past that, the widening simply starts as soon as
     * the fix comes into view, and it still never moves faster than one step's worth.
     */
    const val HORIZON = ((MAX_ZOOM - MIN_ZOOM) / OUT_PER_STEP).toInt() + 2

    /**
     * Fixes after the current one that must fit on screen for it to count as framed.
     *
     * The window is what the camera shows around a moment — one fix back for where they came
     * from, [WINDOW_AHEAD] on for where they are going. Framing the single next segment instead
     * made the zoom twitch between a short pause and a long stride; overlapping windows move
     * the level gradually because neighbouring fixes mostly ask for the same thing.
     */
    private const val WINDOW_AHEAD = 2

    /**
     * Share of the viewport the framed window is allowed to fill.
     *
     * The margin is not decoration: it is what guarantees the next fix is comfortably inside the
     * screen at the instant the pin jumps to it, with the camera still [LEAD] short of it and the
     * zoom still finishing its own move. Filling the screen edge to edge would put that fix
     * exactly on the border.
     */
    private const val SAFE_FRACTION = 0.55

    /** Metres per pixel at zoom 0 on the equator, for 256px tiles — the Web Mercator constant. */
    private const val EQUATOR_M_PER_PX = 156543.03392

    /** Metres in a degree of latitude, and of longitude at the equator. Flat-earth is fine here:
     * the error over the tens of kilometres a trail can span is far under the [SAFE_FRACTION]
     * margin, and a great-circle distance would buy nothing but a slower frame. */
    private const val M_PER_DEG_LAT = 110_574.0
    private const val M_PER_DEG_LNG = 111_320.0

    /** Stand-in viewport for the frames before the map has been measured. */
    private const val UNMEASURED_VIEWPORT_PX = 1000

    /** A point for the camera to sit on. Not a [LocationPoint]: it is between fixes, not one. */
    data class Center(val lat: Double, val lng: Double)

    /**
     * Where the camera sits when the pin is on [index] and [progress] of that fix's time has
     * passed (0 to 1).
     *
     * One continuous walk along the trail, offset in time: at `progress = 1 - LEAD` the camera
     * crosses the fix the pin is standing on, and by the end of the step it is [LEAD] into the
     * next segment. Which means it never stops, never doubles back, and never has to catch up
     * — the jerk of "wait, then rush" is exactly what makes a followed map unwatchable.
     */
    fun centerAt(points: List<LocationPoint>, index: Int, progress: Double): Center {
        if (points.isEmpty()) return Center(0.0, 0.0)
        val last = points.lastIndex
        // Clamped, so the ends of the trail hold still rather than run off it: there is nothing
        // before the first fix to lead in from and nothing after the last one to lean towards.
        val param = (index + progress.coerceIn(0.0, 1.0) - (1.0 - LEAD)).coerceIn(0.0, last.toDouble())
        val from = floor(param).toInt().coerceIn(0, last)
        val to = (from + 1).coerceAtMost(last)
        val step = param - from
        return Center(
            lat = points[from].lat + (points[to].lat - points[from].lat) * step,
            lng = points[from].lng + (points[to].lng - points[from].lng) * step,
        )
    }

    /**
     * The camera's centre on the way in from wherever the parent had left the map, [t] of the way
     * through the first step. Smoothstepped: play is pressed on a still map, and a linear start
     * from a standstill is the one place a constant pace looks like a shove.
     */
    fun ease(from: Center, to: Center, t: Double): Center {
        val e = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
        return Center(from.lat + (to.lat - from.lat) * e, from.lng + (to.lng - from.lng) * e)
    }

    /**
     * The zoom to be at by the end of the step the pin is spending on [index], given the camera
     * is at [currentZoom] now.
     *
     * The plan runs BACKWARDS from the fixes ahead: a stretch that needs to be seen from far away
     * imposes its zoom on every step between here and there, one [OUT_PER_STEP] looser for each
     * step of warning. Whichever future fix asks for the most wins, so the camera is already
     * pulling back while the child is still ambling — which is the whole difference between
     * following a route and being yanked after it.
     *
     * Tightening is capped separately, against where the camera IS rather than where the plan
     * wanted it: coming back to detail after a long hop is not urgent, and doing it in one step
     * looks like the map falling on the child.
     */
    fun zoomFor(points: List<LocationPoint>, index: Int, currentZoom: Double, viewportPx: Int): Double {
        if (points.size < 2) return MAX_ZOOM
        val last = points.lastIndex
        val here = index.coerceIn(0, last)
        var need = MAX_ZOOM
        for (ahead in here..min(here + HORIZON, last)) {
            need = min(need, fitZoom(points, ahead, viewportPx) + OUT_PER_STEP * (ahead - here))
        }
        return min(need, currentZoom + IN_PER_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /**
     * The zoom that frames the stretch of trail around [index] — one fix back, [WINDOW_AHEAD] on
     * — inside [SAFE_FRACTION] of a [viewportPx]-wide screen. What the camera would need if it
     * could change level instantly; [zoomFor] is what it can actually do about it.
     */
    fun fitZoom(points: List<LocationPoint>, index: Int, viewportPx: Int): Double {
        if (points.isEmpty()) return MAX_ZOOM
        val last = points.lastIndex
        val here = index.coerceIn(0, last)
        val window = (here - 1).coerceAtLeast(0)..(here + WINDOW_AHEAD).coerceAtMost(last)
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0
        for (at in window) {
            val point = points[at]
            if (point.lat < minLat) minLat = point.lat
            if (point.lat > maxLat) maxLat = point.lat
            if (point.lng < minLng) minLng = point.lng
            if (point.lng > maxLng) maxLng = point.lng
        }
        val span = distanceM(minLat, minLng, maxLat, maxLng)
        // A child sitting still asks for no framing at all, and dividing by that span would ask
        // for infinite zoom. The closest level the replay uses is the honest answer.
        if (span < 1.0) return MAX_ZOOM
        val usablePx = (if (viewportPx > 0) viewportPx else UNMEASURED_VIEWPORT_PX) * SAFE_FRACTION
        val midLat = (minLat + maxLat) / 2
        return log2(EQUATOR_M_PER_PX * cos(Math.toRadians(midLat)) * usablePx / span)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /**
     * Whether moving from [from] to [to] at [zoom] is a move the eye can follow at all: does it
     * stay inside the screen it started on.
     *
     * Used for the one move a replay makes that is not part of the walk — the opening one, out of
     * wherever the parent had left the map. Panning across town in a single step is a blur that
     * teaches nothing, and a cut is honest about being a cut.
     */
    fun withinOneScreen(from: Center, to: Center, zoom: Double, viewportPx: Int): Boolean {
        val px = (if (viewportPx > 0) viewportPx else UNMEASURED_VIEWPORT_PX) / 2.0
        return distanceM(from.lat, from.lng, to.lat, to.lng) / metersPerPixel(zoom, to.lat) < px
    }

    /** Metres one pixel covers at [zoom] and [lat] — how far a distance on the map is on screen. */
    fun metersPerPixel(zoom: Double, lat: Double): Double =
        EQUATOR_M_PER_PX * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)

    /** Straight-line metres between two positions, flat-earth (see [M_PER_DEG_LAT]). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * M_PER_DEG_LAT
        val dLng = (lng2 - lng1) * M_PER_DEG_LNG * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}
