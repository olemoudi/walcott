package dev.walcott.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The debug log's file is capped in bytes. It used to be trimmed to a line COUNT, which a few
 * stack traces put permanently over the byte cap — and then every append rewrote the file.
 */
class LogTrimTest {

    @Test
    fun `big lines are trimmed by size, newest kept`() {
        val lines = (1..50).map { "line $it " + "x".repeat(4_000) }
        val kept = LogFormat.trimToBytes(lines, maxBytes = 96_000, maxLines = 500)
        val bytes = kept.sumOf { it.toByteArray().size + 1 }
        assertTrue(bytes <= 96_000, "kept $bytes bytes")
        assertEquals(lines.last(), kept.last())
        // Oldest first, and contiguous: a gap in the middle would misrepresent what happened.
        assertEquals(lines.takeLast(kept.size), kept)
    }

    @Test
    fun `small lines are still capped by count`() {
        val lines = (1..1_000).map { "l$it" }
        assertEquals(lines.takeLast(500), LogFormat.trimToBytes(lines, maxBytes = 1_000_000, maxLines = 500))
    }

    @Test
    fun `one line bigger than the budget leaves nothing rather than overflowing`() {
        assertEquals(emptyList<String>(), LogFormat.trimToBytes(listOf("x".repeat(10)), maxBytes = 5, maxLines = 500))
    }

    @Test
    fun `sizes are counted in bytes, not characters`() {
        // "ñ" is two bytes in UTF-8; three of them plus the newline is seven.
        assertEquals(listOf("ñññ"), LogFormat.trimToBytes(listOf("ñññ", "ñññ"), maxBytes = 7, maxLines = 500))
    }
}
