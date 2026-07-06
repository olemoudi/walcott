package dev.walcott.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Encodes [content] as a black-on-white QR bitmap. Dark modules so it scans on any card. */
private fun encodeQr(content: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx) }
        .asImageBitmap()
}

/** Generates the QR off the main thread; null until ready. */
@Composable
fun rememberQrBitmap(content: String, size: Dp): ImageBitmap? {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(content, sizePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(content, sizePx) {
        bitmap = withContext(Dispatchers.Default) { encodeQr(content, sizePx) }
    }
    return bitmap
}
