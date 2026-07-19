package dev.walcott.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.Base64

/**
 * Parent-side on-disk cache of child app icons, and the child-side renderer that produces the
 * tiny WebP an icon travels as. Icons rarely change, so once cached they are kept until the
 * app disappears from every child — no freshness protocol, just a durable cache.
 */
class IconStore(context: Context) {

    private val dir = File(context.filesDir, "child_app_icons").apply { mkdirs() }

    /** A filesystem-safe name for a package (dots/slashes would nest directories). */
    private fun fileFor(pkg: String) = File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".webp")

    /** Whether this package's icon is already cached (the parent requests only the ones that aren't). */
    fun has(pkg: String): Boolean = fileFor(pkg).exists()

    /** Of [packages], those already cached — the set [IconSync.toRequest] subtracts from the shown list. */
    fun cachedAmong(packages: Collection<String>): Set<String> = packages.filterTo(mutableSetOf()) { has(it) }

    fun read(pkg: String): ByteArray? = fileFor(pkg).takeIf { it.exists() }?.readBytes()

    /** Stores a received icon (decoded from its base64 WebP). Best-effort. */
    fun store(pkg: String, webpBytes: ByteArray) {
        runCatching { fileFor(pkg).writeBytes(webpBytes) }
    }

    companion object {
        /** Small enough to keep each icon ~1–2 KB after base64, big enough to look crisp in a list. */
        private const val ICON_PX = 96
        private const val QUALITY = 72

        /** Renders [drawable] into the compact base64 WebP that crosses the wire, or null on failure. */
        fun encode(drawable: Drawable): String? = runCatching {
            val bmp = drawable.toBitmap(ICON_PX, ICON_PX)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            bmp.compress(format, QUALITY, out)
            Base64.getEncoder().encodeToString(out.toByteArray())
        }.getOrNull()

        fun decodeBase64(b64: String): ByteArray? = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()

        /** Decodes cached WebP bytes to a Bitmap for rendering. */
        fun toBitmap(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
}
