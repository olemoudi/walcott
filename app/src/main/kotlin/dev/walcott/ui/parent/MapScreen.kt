package dev.walcott.ui.parent

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.LocationPoint
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.MapTrailHead
import dev.walcott.ui.theme.MapTrailTail
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.advancedpolyline.ColorMapping
import org.osmdroid.views.overlay.advancedpolyline.PolychromaticPaintList
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Child location on an OpenStreetMap map. With location history on, the 48h trail is
 * scrubbable: the slider picks an instant, the trail draws the last few fixes up to it and the
 * marker sits on the fix at that moment, so a parent can replay the day rather than read
 * coordinates. Play walks it forward at a speed the parent picks.
 */
@Composable
fun MapScreen(viewModel: WalcottViewModel, childId: String, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val snapshot = snapshots.firstOrNull { it.childId == childId }
    // Sorted defensively: the trail is published oldest-first, but the scrubber's whole
    // model assumes monotonic time and a stale child could predate that guarantee.
    val points = remember(snapshot?.locations) { snapshot?.locations.orEmpty().sortedBy { it.epochMs } }
    val historyOn = remember(settings, childId) {
        settings.resolveForChild(childId).locationHistoryEnabled
    }
    // One ticking countdown for the whole screen. Read straight off the clock during composition
    // it only ever changed when something else happened to recompose, so a session that had
    // expired minutes ago went on pulling the camera back to the child every time a fix landed.
    val liveLeftMs = rememberTimeLeft(snapshot?.liveTrackingUntilMs ?: 0L)

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.map_title), onBack)

        if (snapshot != null && !snapshot.networkLocationOn) {
            MapWarning(stringResource(R.string.location_network_off_warning))
        }
        if (points.any { it.mock }) {
            MapWarning(stringResource(R.string.location_mock_warning))
        }
        // "I asked and it could not answer" must not look like "I have not asked": until the
        // child started reporting this, a locate that failed indoors cleared the parent's
        // spinner and left them reading an older position as the current one.
        if (snapshot != null && snapshot.lastLocateFailedMs > 0 &&
            points.lastOrNull()?.let { snapshot.lastLocateFailedMs > it.epochMs } == true
        ) {
            MapWarning(stringResource(R.string.location_locate_failed))
        }
        if (liveLeftMs > 0) {
            LiveTrackingBanner(liveLeftMs)
        }

        if (points.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(spacing.screen), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.map_no_locations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            TrailMap(
                points,
                historyOn,
                snapshot?.locationsTotal ?: 0,
                // Follow the child while the session the parent paid for is running.
                followNewest = liveLeftMs > 0,
                childId = childId,
                modifier = Modifier.weight(1f),
            )
        }

        if (snapshot != null) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Button(
                    onClick = { viewModel.requestLocation(snapshot.deviceId) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.locate_now))
                }
            }
        }
    }
}

@Composable
private fun MapWarning(text: String) {
    val spacing = Tokens.spacing
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.sm),
    )
}

/** The map plus, when there is a trail worth replaying, the timeline scrubber over it. */
@Composable
private fun TrailMap(
    points: List<LocationPoint>,
    historyOn: Boolean,
    totalPoints: Int,
    followNewest: Boolean,
    childId: String,
    modifier: Modifier = Modifier,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    // Read from the theme here, not inside the update block, which must stay free of composition
    // reads. The accuracy circle takes the trail's own head colour rather than the scheme's
    // primary, so the two things drawn around the current position agree in both themes.
    val headColor = MapTrailHead
    val tailColor = MapTrailTail
    val accuracyFill = headColor.copy(alpha = 0.12f).toArgb()
    val accuracyStroke = headColor.copy(alpha = 0.45f).toArgb()
    val scrubbable = points.size > 1
    val stampFormatter = remember { DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()) }

    // The scrubber's position, held as an INSTANT rather than an index: the child republishes its
    // whole trail on every check-in and thins it by age on the way, so an index means a different
    // fix a minute later. [PINNED_TO_NEWEST] is "wherever the child is now", which is where the
    // screen opens and where it goes back to when a replay reaches the end.
    var selectedMs by remember(childId) { mutableLongStateOf(PINNED_TO_NEWEST) }
    var playing by remember(childId) { mutableStateOf(false) }
    var speedIndex by remember(childId) { mutableIntStateOf(MapTrail.DEFAULT_SPEED) }
    val pinned = selectedMs == PINNED_TO_NEWEST
    val selected = remember(points, selectedMs) { MapTrail.indexAt(points, selectedMs) }

    // Playback: step through the trail one fix at a time, then stop at the end.
    //
    // Keyed on the controls alone. Keyed on the trail's length as well, a session sampling every
    // minute cancelled the replay the parent was watching every time a fix landed; the trail
    // itself comes in through [rememberUpdatedState] so playback simply walks into whatever has
    // arrived since it started.
    val trail by rememberUpdatedState(points)
    LaunchedEffect(playing, speedIndex) {
        if (!playing) return@LaunchedEffect
        val stepMs = MapTrail.stepMs(speedIndex)
        // Play pressed at the end of the trail replays it from the beginning.
        val oldest = trail.firstOrNull()
        if (oldest != null && MapTrail.indexAt(trail, selectedMs) >= trail.lastIndex) {
            selectedMs = oldest.epochMs
        }
        while (true) {
            delay(stepMs)
            val next = MapTrail.indexAt(trail, selectedMs) + 1
            if (next > trail.lastIndex) break
            selectedMs = trail[next].epochMs
        }
        selectedMs = PINNED_TO_NEWEST // done replaying: follow the child again
        playing = false
    }

    val mapView = remember {
        // osmdroid needs a user agent, and its default cache path targets external
        // storage (fails under scoped storage); keep everything in app-private cache.
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    // Colour of each fix in the tail, in trail order; the paint list below reads it as the map
    // draws, so the whole gradient is one array swap per frame rather than a rebuilt overlay.
    val tailColors = remember { ArrayList<Int>(MapTrail.TAIL_POINTS) }
    // Built once and mutated in place. Rebuilding the overlays on every step meant a Polyline, a
    // Polygon and a Marker — each with its own Paint — allocated four times a second at 1x, and
    // sixteen at 4x.
    val trailLine = remember(mapView) {
        Polyline(mapView).also { line ->
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = TRAIL_WIDTH_PX
                isAntiAlias = true
            }
            // osmdroid's own per-segment paint, with a linear gradient between each pair of
            // fixes: colour AND alpha run continuously down the tail instead of stepping at
            // every fix, which is the difference between a fade and nine stacked ribbons.
            line.outlinePaintLists.add(
                PolychromaticPaintList(paint, ColorMapping { index -> tailColors.getOrElse(index) { 0 } }, true),
            )
        }
    }
    val accuracyCircle = remember(mapView) { Polygon(mapView) }
    val marker = remember(mapView) {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
    }
    DisposableEffect(mapView) {
        mapView.overlays.add(trailLine)
        mapView.overlays.add(accuracyCircle)
        mapView.overlays.add(marker)
        mapView.invalidate()
        onDispose { mapView.overlays.clear() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        // osmdroid keeps its tile threads and its own ticker running until it is told the screen
        // has gone. Tied to the composable's lifetime alone it was only ever told when the parent
        // left the map, so a map left open behind a locked screen went on working. Playback stops
        // with it: redrawing a replay nobody is looking at, sixteen times a second, is worse.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    playing = false
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Centre on the first fix we get, then leave the camera alone so scrubbing never
    // yanks a map the parent has panned. "Re-centre" is the explicit way back — driven by
    // effects rather than the AndroidView update block, which must stay side-effect free.
    //
    // WHILE CLOSE TRACKING RUNS, follow instead. That mode means "I am watching this phone move
    // right now", and it is the one case where a camera that stays put is wrong: fixes kept
    // arriving, the marker walked off the edge of the screen, and the parent — quite reasonably —
    // read a still picture as nothing happening. Only while the scrubber is still pinned to the
    // newest fix, because a parent replaying the past has said where they want to be looking.
    var recenterRequest by remember { mutableIntStateOf(0) }
    var centred by remember { mutableStateOf(false) }
    val newest = points.lastOrNull()
    LaunchedEffect(newest, followNewest, pinned) {
        val point = newest ?: return@LaunchedEffect
        val geo = GeoPoint(point.lat, point.lng)
        when {
            !centred -> {
                centred = true
                mapView.controller.setCenter(geo)
            }
            followNewest && pinned -> mapView.controller.animateTo(geo)
        }
    }
    LaunchedEffect(recenterRequest) {
        if (recenterRequest == 0) return@LaunchedEffect
        val point = points[selected.coerceIn(0, points.lastIndex)]
        mapView.controller.animateTo(GeoPoint(point.lat, point.lng))
    }
    // A replay follows too, for the same reason close tracking does: pressing play is asking to
    // be shown the movement, and a child who walks off the edge in the first four seconds is a
    // replay of an empty street. Dragging the scrubber deliberately does NOT follow — that is a
    // parent reading a particular moment, and moving the map under them would be taking it away.
    // Centred rather than animated: the steps are small and a second-long animation per fix would
    // still be catching up with the one after it.
    LaunchedEffect(selected, playing) {
        if (!playing) return@LaunchedEffect
        val point = points.getOrNull(selected) ?: return@LaunchedEffect
        mapView.controller.setCenter(GeoPoint(point.lat, point.lng))
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { map ->
                    val current = points[selected.coerceIn(0, points.lastIndex)]
                    val currentGeo = GeoPoint(current.lat, current.lng)

                    // The comet tail: the last few fixes before the selected instant, each one
                    // further along the fade than the next. Drawing the whole travelled path at
                    // full strength said "here is the day" when the question the scrubber asks is
                    // "where were they heading at this moment".
                    val tail = MapTrail.tailRange(selected, points.size)
                    val tailSize = tail.count()
                    tailColors.clear()
                    tail.forEachIndexed { position, _ ->
                        tailColors += lerp(tailColor, headColor, MapTrail.hue(position, tailSize))
                            .copy(alpha = MapTrail.fade(position, tailSize))
                            .toArgb()
                    }
                    trailLine.setPoints(tail.map { GeoPoint(points[it].lat, points[it].lng) })
                    trailLine.isVisible = tailSize > 1

                    // The circle the fix actually justifies. A bare pin says "here" with the same
                    // confidence whether the fix is eight metres wide or two kilometres, and the
                    // parent has no way to tell which they are looking at.
                    accuracyCircle.isVisible = current.accuracyM > 0f
                    if (current.accuracyM > 0f) {
                        accuracyCircle.points = Polygon.pointsAsCircle(currentGeo, current.accuracyM.toDouble())
                        accuracyCircle.fillPaint.color = accuracyFill
                        accuracyCircle.outlinePaint.color = accuracyStroke
                        accuracyCircle.outlinePaint.strokeWidth = 2f
                    }

                    marker.position = currentGeo
                    marker.title = formatStamp(current.epochMs, stampFormatter)
                    map.invalidate()
                },
            )
            FilledTonalIconButton(
                onClick = { recenterRequest += 1 },
                modifier = Modifier.align(Alignment.TopEnd).padding(spacing.md),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.map_recenter))
            }
        }

        if (!historyOn) {
            Text(
                stringResource(R.string.map_history_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
        }

        AnimatedVisibility(visible = scrubbable) {
            Timeline(
                points = points,
                selected = selected,
                totalPoints = totalPoints,
                formatter = stampFormatter,
                playing = playing,
                speedIndex = speedIndex,
                onScrub = { value ->
                    playing = false
                    val index = value.roundToInt().coerceIn(0, points.lastIndex)
                    // Scrubbed all the way to the end is not "this instant", it is "keep up with
                    // them" — otherwise the parent lands on the newest fix and is left behind by
                    // the next one.
                    selectedMs = if (index == points.lastIndex) PINNED_TO_NEWEST else points[index].epochMs
                },
                onTogglePlay = { playing = !playing },
                onCycleSpeed = { speedIndex = MapTrail.nextSpeed(speedIndex) },
            )
        }
    }
}

/** Slider + play/speed controls over the trail, with the selected fix's time and accuracy. */
@Composable
private fun Timeline(
    points: List<LocationPoint>,
    selected: Int,
    totalPoints: Int,
    formatter: DateTimeFormatter,
    playing: Boolean,
    speedIndex: Int,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
    val spacing = Tokens.spacing
    // A one-fix trail has nothing to scrub and a degenerate slider range; the caller hides this,
    // but an exit animation keeps it composed for a frame or two after the trail shrinks.
    if (points.size < 2) return
    val current = points[selected.coerceIn(0, points.lastIndex)]
    val newest = points.last()
    val atLatest = selected == points.lastIndex
    val speedText = stringResource(R.string.map_speed_fmt, rememberSpeedNumber(MapTrail.SPEEDS[speedIndex]))
    // Resolved out here: the semantics block is not a composable scope, and the button's own
    // label is a bare multiplier that reads as nothing at all aloud.
    val speedDesc = stringResource(R.string.map_speed_desc, speedText)

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    // "Now" is a claim, and it was made about the newest fix whatever its age —
                    // so a twenty-minute-old cached position was labelled as where the child is.
                    // Past a few minutes it says how old it really is instead.
                    val ageMs = System.currentTimeMillis() - current.epochMs
                    Text(
                        when {
                            !atLatest -> formatStamp(current.epochMs, formatter)
                            ageMs <= FRESH_ENOUGH_MS -> stringResource(R.string.map_timeline_latest)
                            else -> stringResource(
                                R.string.map_fix_age,
                                java.time.Duration.ofMillis(ageMs).humanize(),
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // What is on the map, out of what the child actually recorded. The
                            // trail is the first thing a squeezed check-in thins, and saying
                            // "120" when the phone holds 600 reads as a phone that barely moved.
                            if (totalPoints > points.size) {
                                stringResource(R.string.map_point_count_of, points.size, totalPoints)
                            } else {
                                stringResource(R.string.map_point_count, points.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            // An accuracy of zero is not a perfect fix, it is a fix that would not
                            // say — and drawing nothing at all made the two indistinguishable.
                            if (current.accuracyM > 0f) {
                                stringResource(R.string.map_accuracy_fmt, current.accuracyM.roundToInt())
                            } else {
                                stringResource(R.string.map_accuracy_unknown)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Speed sits next to play because it is only ever about play. One button that
                // cycles rather than a row of chips: the timeline is already carrying a time, two
                // counts and a slider, and a speed is a thing you nudge, not a thing you browse.
                FilledTonalButton(
                    onClick = onCycleSpeed,
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
                    modifier = Modifier.semantics { contentDescription = speedDesc },
                ) {
                    Text(speedText, style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalIconButton(onClick = onTogglePlay) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.map_pause else R.string.map_play),
                    )
                }
            }
            Slider(
                value = selected.toFloat(),
                onValueChange = onScrub,
                valueRange = 0f..points.lastIndex.toFloat(),
                // Continuous, and rounded to a real fix by the caller. Asking the slider for one
                // step per fix snapped the knob just the same but drew a tick for every one of
                // them: at a full trail, a hundred and eighteen dots along the track.
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatStamp(points.first().epochMs, formatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // The far end of the track is the newest fix, which is only "now" if it is
                    // recent; over a phone that last reported at lunchtime it was a flat lie.
                    if (System.currentTimeMillis() - newest.epochMs <= FRESH_ENOUGH_MS) {
                        stringResource(R.string.map_timeline_latest)
                    } else {
                        formatStamp(newest.epochMs, formatter)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Day + time in the device locale; the trail spans two days, so the day matters. The
 * formatter is passed in (remembered by the caller) rather than held in a top-level val,
 * which would freeze the locale at class-load and survive a language change.
 */
private fun formatStamp(epochMs: Long, formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** A playback multiplier in the reader's locale: "1", "0.5", "0,5". */
@Composable
private fun rememberSpeedNumber(speed: Float): String {
    val format = remember {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }
    }
    return remember(speed, format) { format.format(speed) }
}

/** Says a close-tracking session is running, and for how much longer. */
@Composable
private fun LiveTrackingBanner(leftMs: Long) {
    val spacing = Tokens.spacing
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.map_live_active, java.time.Duration.ofMillis(leftMs).humanize()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm),
        )
    }
}

/**
 * Milliseconds left until [untilMs], on a slow tick.
 *
 * Once a minute-ish, so the countdown stays honest without costing a frame budget — and so that
 * everything keyed on "is the session still running" stops together when it ends.
 */
@Composable
private fun rememberTimeLeft(untilMs: Long): Long {
    var left by remember(untilMs) { mutableLongStateOf(untilMs - System.currentTimeMillis()) }
    LaunchedEffect(untilMs) {
        while (left > 0) {
            delay(LIVE_TICK_MS)
            left = untilMs - System.currentTimeMillis()
        }
    }
    return left.coerceAtLeast(0L)
}

/** The scrubber parked on "wherever the child is now" rather than on a particular instant. */
private const val PINNED_TO_NEWEST = Long.MAX_VALUE

/** How often the close-tracking countdown is recomputed. */
private const val LIVE_TICK_MS = 30_000L

/** Trail stroke, in pixels: wide enough to read over map tiles at the faded end. */
private const val TRAIL_WIDTH_PX = 8f

/**
 * How recent the newest fix has to be for the timeline to call it "now" rather than give its age.
 * A couple of sampling cycles: long enough not to nag on an ordinary interval, short enough that
 * a stale position can never be read as a current one.
 */
private const val FRESH_ENOUGH_MS = 5 * 60 * 1000L
