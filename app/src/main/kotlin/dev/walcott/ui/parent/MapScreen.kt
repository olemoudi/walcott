package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
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

/** Child location on an OpenStreetMap map: the 12h trail plus the latest fix, with "locate now". */
@Composable
fun MapScreen(viewModel: WalcottViewModel, childId: String, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val snapshot = snapshots.firstOrNull { it.childId == childId }
    val points = snapshot?.locations.orEmpty()

    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.map_title), onBack)

        if (points.any { it.mock }) {
            Text(
                stringResource(R.string.location_mock_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
        }

        if (points.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(spacing.screen), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.map_no_locations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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
            var centered by remember { mutableStateOf(false) }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { mapView },
                    update = { map ->
                        map.overlays.clear()
                        val geo = points.map { GeoPoint(it.lat, it.lng) }
                        if (geo.size > 1) {
                            map.overlays.add(Polyline(map).apply { setPoints(geo) })
                        }
                        val latest = points.last()
                        val latestGeo = GeoPoint(latest.lat, latest.lng)
                        map.overlays.add(
                            Marker(map).apply {
                                position = latestGeo
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = Instant.ofEpochMilli(latest.epochMs)
                                    .atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat)
                            },
                        )
                        if (!centered) {
                            map.controller.setCenter(latestGeo)
                            centered = true
                        }
                        map.invalidate()
                    },
                )
            }
        }

        if (snapshot != null) {
            Button(
                onClick = { viewModel.requestLocation(snapshot.deviceId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(spacing.screen),
            ) {
                Text(stringResource(R.string.locate_now))
            }
        }
    }
}
