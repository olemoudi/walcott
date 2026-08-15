package dev.walcott.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.walcott.data.AppInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Icons are immutable per package: in-memory cache avoids re-decoding while scrolling.
private val iconCache = LruCache<String, ImageBitmap>(256)

/**
 * App icon loaded off the main thread, with a placeholder while it decodes. Prefers the
 * locally installed icon; on the parent (where the child's apps aren't installed) it falls
 * back to [remoteLoader], the cache fed by the child over sync. [refreshKey] re-attempts the
 * load when new remote icons arrive.
 */
@Composable
fun AppIcon(
    packageName: String,
    inventory: AppInventory,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    remoteLoader: ((String) -> ByteArray?)? = null,
    refreshKey: Any? = null,
    /** The app's name, for the initial shown while (or instead of) the icon (see [Monogram]). */
    label: String? = null,
) {
    var bitmap by remember(packageName) { mutableStateOf(iconCache.get(packageName)) }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }

    LaunchedEffect(packageName, refreshKey) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                (
                    inventory.icon(packageName)?.toBitmap(sizePx, sizePx)
                        ?: remoteLoader?.invoke(packageName)?.let { dev.walcott.sync.IconStore.toBitmap(it) }
                    )?.asImageBitmap()
            }
            if (decoded != null) {
                iconCache.put(packageName, decoded)
                bitmap = decoded
            }
        }
    }

    val shape = RoundedCornerShape(size / 4)
    val current = bitmap
    if (current != null) {
        Image(bitmap = current, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Monogram(label ?: packageName, size, shape, modifier)
    }
}

/**
 * What an app looks like when its icon isn't here: its initial on a tile tinted from its own
 * name, rather than the empty grey square that used to stand in.
 *
 * The blank was indistinguishable from a rendering bug, and on the parent's phone it is a
 * perfectly ordinary state — the icon travels from the child over sync and may be minutes
 * away, a day away on a phone that is off, or never coming at all for a drawable no device can
 * render (see IconPayload.unavailable). A letter is at least the app, and it is stable: the
 * same app is the same colour every time, which is most of what an icon is for in a list.
 */
@Composable
private fun Monogram(name: String, size: Dp, shape: RoundedCornerShape, modifier: Modifier) {
    // Derived from the name, so it is stable across devices and restarts without storing
    // anything. Fixed saturation and lightness keep every tile in the same family.
    val hue = (name.hashCode().toUInt() % 360u).toFloat()
    // The family's own Light/Dark choice, not the system's (see LocalDarkTheme).
    val dark = dev.walcott.ui.theme.LocalDarkTheme.current
    val tile = Color.hsl(hue, 0.45f, if (dark) 0.28f else 0.90f)
    val ink = Color.hsl(hue, 0.55f, if (dark) 0.82f else 0.30f)
    Box(
        modifier.size(size).clip(shape).background(tile),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = ink,
            fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() },
            fontWeight = FontWeight.SemiBold,
        )
    }
}
