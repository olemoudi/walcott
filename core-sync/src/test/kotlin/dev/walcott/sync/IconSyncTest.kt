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

    // --- pack ---

    private fun icon(pkg: String, bytes: Int) = AppIconData(pkg, "x".repeat(bytes))

    @Test
    fun `packs as many icons as fit under the budget`() {
        val candidates = listOf(icon("a", 1000), icon("b", 1000), icon("c", 1000))
        val packed = IconSync.pack(candidates, budget = 2500)
        assertEquals(listOf("a", "b"), packed.map { it.packageName })
    }

    @Test
    fun `always sends at least one, even if it alone exceeds the budget`() {
        val packed = IconSync.pack(listOf(icon("big", 5000)), budget = 3000)
        assertEquals(listOf("big"), packed.map { it.packageName })
    }

    @Test
    fun `an empty candidate list packs to nothing`() {
        assertTrue(IconSync.pack(emptyList()).isEmpty())
    }
}
