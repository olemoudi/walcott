package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IconSyncTest {

    // --- toRequest ---

    @Test
    fun `requests only shown-and-uncached packages`() {
        val out = IconSync.toRequest(listOf("a", "b", "c"), cached = setOf("b"))
        assertEquals(listOf("a", "c"), out)
    }

    @Test
    fun `deduplicates and preserves first-seen order`() {
        val out = IconSync.toRequest(listOf("a", "b", "a", "c", "b"), cached = emptySet())
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `caps the request list so the parent message stays small`() {
        val many = (1..100).map { "pkg$it" }
        assertEquals(IconSync.MAX_REQUESTS, IconSync.toRequest(many, emptySet()).size)
    }

    @Test
    fun `an all-cached list requests nothing`() {
        assertTrue(IconSync.toRequest(listOf("a", "b"), cached = setOf("a", "b")).isEmpty())
    }

    @Test
    fun `rotation slides the window when more icons are missing than fit one request`() {
        val many = (1..30).map { "pkg%02d".format(it) }
        val first = IconSync.toRequest(many, emptySet(), rotation = 0)
        val second = IconSync.toRequest(many, emptySet(), rotation = 1)
        assertEquals(many.take(IconSync.MAX_REQUESTS), first)
        assertEquals((many.drop(1) + many.take(1)).take(IconSync.MAX_REQUESTS), second)
        // Every missing package is eventually requested across rotations.
        val covered = (0 until many.size).flatMap { IconSync.toRequest(many, emptySet(), rotation = it) }.toSet()
        assertEquals(many.toSet(), covered)
    }

    @Test
    fun `rotation is a no-op while everything missing fits one request`() {
        val few = listOf("a", "b", "c")
        assertEquals(few, IconSync.toRequest(few, emptySet(), rotation = 7))
    }

    @Test
    fun `negative or huge rotations stay in range`() {
        val many = (1..30).map { "pkg$it" }
        assertEquals(IconSync.MAX_REQUESTS, IconSync.toRequest(many, emptySet(), rotation = -3).size)
        assertEquals(IconSync.MAX_REQUESTS, IconSync.toRequest(many, emptySet(), rotation = Int.MAX_VALUE).size)
    }

    // --- pack ---

    private fun icon(pkg: String, bytes: Int) = AppIconData(pkg, "x".repeat(bytes))

    @Test
    fun `packs as many icons as fit under the budget`() {
        val candidates = listOf(icon("a", 1000), icon("b", 1000), icon("c", 1000))
        val packed = IconSync.pack(candidates, budget = 2500)
        assertEquals(listOf("a", "b"), packed.map { it.packageName })
    }

    @Test
    fun `an icon that alone exceeds the budget is skipped, not sent`() {
        // Sending it anyway meant an always-rejected message: the parent kept requesting the
        // icon, and everything queued behind it never arrived.
        val packed = IconSync.pack(listOf(icon("big", 5000)), budget = 3000)
        assertTrue(packed.isEmpty())
    }

    @Test
    fun `an oversized head icon does not block the icons behind it`() {
        val candidates = listOf(icon("big", 5000), icon("a", 1000), icon("b", 1000))
        val packed = IconSync.pack(candidates, budget = 3000)
        assertEquals(listOf("a", "b"), packed.map { it.packageName })
    }

    @Test
    fun `an empty candidate list packs to nothing`() {
        assertTrue(IconSync.pack(emptyList()).isEmpty())
    }
}
