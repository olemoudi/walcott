package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class IconFitTest {

    private val key = FamilyCrypto.generateFamilyKey()

    /** Base64-looking (incompressible) payload, like a real WebP travels as. */
    private fun icon(pkg: String, b64Bytes: Int): AppIconData {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val random = Random(pkg.hashCode())
        return AppIconData(pkg, buildString(b64Bytes) { repeat(b64Bytes) { append(alphabet[random.nextInt(alphabet.length)]) } })
    }

    private fun decode(encoded: String): IconPayload {
        val pair = FamilyCrypto.generateSigningKeyPair()
        return (SyncProtocol.decode(encoded, key, pair.public) as IncomingMessage.FromChildIcons).payload
    }

    @Test
    fun `a small batch is sent in full`() {
        val payload = IconPayload("device-1", listOf(icon("a", 1200), icon("b", 1200)))
        val encoded = IconFit.encode(payload, key)
        assertTrue(encoded != null && encoded.length <= SnapshotFit.MAX_BYTES)
        assertEquals(listOf("a", "b"), decode(encoded!!).icons.map { it.packageName })
    }

    @Test
    fun `an oversized batch drops icons off the tail until it fits`() {
        val payload = IconPayload("device-1", List(4) { icon("pkg$it", 2000) })
        val encoded = IconFit.encode(payload, key)
        assertTrue(encoded != null && encoded.length <= SnapshotFit.MAX_BYTES)
        val out = decode(encoded!!).icons.map { it.packageName }
        assertTrue(out.isNotEmpty() && out.size < 4)
        assertEquals(List(out.size) { "pkg$it" }, out) { "must drop from the tail, keeping request order" }
    }

    @Test
    fun `a single icon that cannot fit yields null instead of a doomed message`() {
        val payload = IconPayload("device-1", listOf(icon("huge", 20_000)))
        assertNull(IconFit.encode(payload, key))
    }

    @Test
    fun `a message carrying only the icons that will never come is still sent`() {
        // The child saying "these two I cannot render" is the whole message, and it is the one
        // that stops the parent asking on every publish for the rest of the family's life.
        val payload = IconPayload("device-1", emptyList(), unavailable = listOf("com.a", "com.b"))
        val encoded = IconFit.encode(payload, key)
        assertNotNull(encoded)
        assertEquals(listOf("com.a", "com.b"), decode(encoded!!).unavailable)
    }

    @Test
    fun `an oversized icon with nothing else to say is still not sent`() {
        // Dropping it leaves an empty message with no news in it, which is not worth a publish.
        assertNull(IconFit.encode(IconPayload("device-1", listOf(icon("huge", 20_000))), key))
    }

    @Test
    fun `an oversized icon rides out with the news that others cannot be rendered`() {
        val payload = IconPayload("device-1", listOf(icon("huge", 20_000)), unavailable = listOf("com.a"))
        val encoded = IconFit.encode(payload, key)
        assertNotNull(encoded)
        val out = decode(encoded!!)
        assertTrue(out.icons.isEmpty())
        assertEquals(listOf("com.a"), out.unavailable)
    }

    @Test
    fun `the pack budget plus per-icon cap stays under the wire cap`() {
        // The invariant the child relies on: anything IconSync.pack lets through (icons
        // bounded by the encoder's per-icon cap, sum bounded by MESSAGE_BUDGET) survives
        // the envelope without tripping IconFit's fallback.
        val candidates = List(8) { icon("com.example.app$it", 1400) }
        val packed = IconSync.pack(candidates)
        val encoded = IconFit.encode(IconPayload("device-1", packed), key)
        assertTrue(encoded != null && encoded.length <= SnapshotFit.MAX_BYTES)
        assertEquals(packed.size, decode(encoded!!).icons.size)
    }
}
