package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.LocationPoint
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Child location on an OpenStreetMap map. With location history on, the 48h trail is
 * scrubbable: the slider picks an instant, the polyline draws up to it and the marker sits
 * on the fix at that moment, so a parent can replay the day rather than read coordinates.
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

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.map_title), onBack)

        if (snapshot != null && !snapshot.networkLocationOn) {
            MapWarning(stringResource(R.string.location_network_off_warning))
        }
        if (points.any { it.mock }) {
            MapWarning(stringResource(R.string.location_mock_warning))
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
            TrailMap(points, historyOn, Modifier.weight(1f))
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
private fun TrailMap(points: List<LocationPoint>, historyOn: Boolean, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val trailColor = MaterialTheme.colorScheme.primary
    val scrubbable = points.size > 1
    val stampFormatter = remember { DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()) }

    // Selected index into the trail; always starts at the newest fix ("Now").
    var selected by remember(points.size) { mutableIntStateOf(points.lastIndex) }
    var playing by remember(points.size) { mutableStateOf(false) }
    // Kept separate from `selected` so dragging stays smooth at 60fps: the slider owns a
    // float while the map only ever redraws on whole-index changes.
    var sliderValue by remember(points.size) { mutableFloatStateOf(points.lastIndex.toFloat()) }

    // Playback: step through the trail, then stop at the end.
    LaunchedEffect(playing, points.size) {
        if (!playing) return@LaunchedEffect
        // Restart from the beginning when play is pressed at the end of the trail.
        if (selected >= points.lastIndex) {
            selected = 0
            sliderValue = 0f
        }
        while (selected < points.lastIndex) {
            delay(PLAYBACK_STEP_MS)
            selected += 1
            sliderValue = selected.toFloat()
        }
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
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    // Centre on the first fix we get, then leave the camera alone so scrubbing never
    // yanks a map the parent has panned. "Re-centre" is the explicit way back — driven by
    // effects rather than the AndroidView update block, which must stay side-effect free.
    var recenterRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(points.isNotEmpty()) {
        points.lastOrNull()?.let { mapView.controller.setCenter(GeoPoint(it.lat, it.lng)) }
    }
    LaunchedEffect(recenterRequest) {
        if (recenterRequest == 0) return@LaunchedEffect
        val point = points[selected.coerceIn(0, points.lastIndex)]
        mapView.controller.animateTo(GeoPoint(point.lat, point.lng))
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { map ->
                    val current = points[selected.coerceIn(0, points.lastIndex)]
                    val currentGeo = GeoPoint(current.lat, current.lng)
                    map.overlays.clear()
                    // Draw the path travelled up to the selected instant, not the whole trail:
                    // that is what makes the scrubber read as a replay.
                    val travelled = points.take(selected + 1).map { GeoPoint(it.lat, it.lng) }
                    if (travelled.size > 1) {
                        map.overlays.add(
                            Polyline(map).apply {
                                setPoints(travelled)
                                outlinePaint.color = trailColor.toArgb()
                                outlinePaint.strokeWidth = 8f
                            },
                        )
                    }
                    map.overlays.add(
                        Marker(map).apply {
                            position = currentGeo
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = formatStamp(current.epochMs, stampFormatter)
                        },
                    )
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
                formatter = stampFormatter,
                sliderValue = sliderValue,
                playing = playing,
                onScrub = { value ->
                    playing = false
                    sliderValue = value
                    selected = value.roundToInt().coerceIn(0, points.lastIndex)
                },
                onTogglePlay = { playing = !playing },
            )
        }
    }
}

/** Slider + play control over the trail, with the selected fix's time and accuracy. */
@Composable
private fun Timeline(
    points: List<LocationPoint>,
    selected: Int,
    formatter: DateTimeFormatter,
    sliderValue: Float,
    playing: Boolean,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val spacing = Tokens.spacing
    val current = points[selected.coerceIn(0, points.lastIndex)]
    val atLatest = selected == points.lastIndex

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (atLatest) stringResource(R.string.map_timeline_latest) else formatStamp(current.epochMs, formatter),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.map_point_count, points.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (current.accuracyM > 0f) {
                            Spacer(Modifier.width(spacing.sm))
                            Text(
                                stringResource(R.string.map_accuracy_fmt, current.accuracyM.roundToInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                FilledTonalIconButton(onClick = onTogglePlay) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.map_pause else R.string.map_play),
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = onScrub,
                valueRange = 0f..points.lastIndex.toFloat(),
                // One step per fix, so the knob lands exactly on real observations.
                steps = (points.size - 2).coerceAtLeast(0),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatStamp(points.first().epochMs, formatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.map_timeline_latest),
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

/** Playback speed: fast enough to read as motion, slow enough to follow. */
private const val PLAYBACK_STEP_MS = 220L
