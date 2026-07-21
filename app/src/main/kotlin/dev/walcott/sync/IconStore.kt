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

    /** Stores a received icon (decoded from its base64 WebP). True when it actually landed on disk. */
    fun store(pkg: String, webpBytes: ByteArray): Boolean =
        runCatching { fileFor(pkg).writeBytes(webpBytes) }.isSuccess

    companion object {
        /**
         * Render attempts, tried in order: (pixels, WebP quality). Most icons fit at full size;
         * busy photographic ones — the kind whose oversized message used to jam the icon queue
         * permanently — fall through to smaller renditions until the base64 fits the cap.
         */
        private val RENDER_LADDER = listOf(96 to 72, 72 to 60, 48 to 40)

        /**
         * Per-icon cap on the base64 payload, chosen so even a single-icon message stays well
         * under the ntfy size cap after envelope overhead (gzip + AES-GCM + base64 + JSON).
         */
        const val MAX_B64_LENGTH = 2400

        /**
         * Renders [drawable] into a compact base64 WebP bounded by [MAX_B64_LENGTH], or null
         * when even the smallest rendition doesn't fit (never delivered rather than jamming).
         */
        fun encode(drawable: Drawable): String? = RENDER_LADDER.firstNotNullOfOrNull { (px, quality) ->
            encodeAt(drawable, px, quality)?.takeIf { it.length <= MAX_B64_LENGTH }
        }

        private fun encodeAt(drawable: Drawable, px: Int, quality: Int): String? = runCatching {
            val bmp = drawable.toBitmap(px, px)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            bmp.compress(format, quality, out)
            Base64.getEncoder().encodeToString(out.toByteArray())
        }.getOrNull()

        fun decodeBase64(b64: String): ByteArray? = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()

        /** Decodes cached WebP bytes to a Bitmap for rendering. */
        fun toBitmap(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
}
