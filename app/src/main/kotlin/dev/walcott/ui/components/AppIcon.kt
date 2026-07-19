package dev.walcott.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
