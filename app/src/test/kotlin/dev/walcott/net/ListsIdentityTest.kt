package dev.walcott.net

import dev.walcott.rules.Blocklists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the filter recompiles its lists. Every refresh pass emits a new store state, and most of
 * them only confirm with a 304 what is already on disk; recompiling for those read every enabled
 * list — megabytes — to build the matcher already in hand.
 */
class ListsIdentityTest {

    private val adult = Blocklists.ADULT
    private val etags = Blocklists.sources(adult).associateWith { "\"v1-${it.hashCode()}\"" }

    private fun state(vararg lists: Pair<String, BlocklistStore.ListState>) = BlocklistStore.State(lists.toMap())

    @Test
    fun `the list under test has sources, or none of this means anything`() {
        assertTrue(Blocklists.sources(adult).isNotEmpty())
    }

    @Test
    fun `a pass that only confirms the lists changes nothing to compile`() {
        val before = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 1_000, etags = etags))
        // What a round of 304s writes: a new timestamp, and the same ETags.
        val after = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 90_000_000, etags = etags))
        assertEquals(ListsIdentity.of(setOf(adult), emptySet(), before), ListsIdentity.of(setOf(adult), emptySet(), after))
    }

    @Test
    fun `a list downloaded again with new content is compiled again`() {
        val before = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 1_000, etags = etags))
        val newer = etags.mapValues { "\"v2\"" }
        val after = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 2_000, etags = newer))
        assertNotEquals(ListsIdentity.of(setOf(adult), emptySet(), before), ListsIdentity.of(setOf(adult), emptySet(), after))
    }

    @Test
    fun `a list whose sources send no ETag may have changed on every download`() {
        // No ETag, no conditional request, no 304: every pass is a full download, and the same
        // count of domains is no proof the domains are the same.
        val before = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 1_000))
        val after = state(adult to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 2_000))
        assertNotEquals(ListsIdentity.of(setOf(adult), emptySet(), before), ListsIdentity.of(setOf(adult), emptySet(), after))
    }

    @Test
    fun `switching a list on is a change, and what a list that is off does is not`() {
        val lists = state(
            adult to BlocklistStore.ListState(domains = 10, fetchedAtMs = 1, etags = etags),
            Blocklists.PIRACY to BlocklistStore.ListState(domains = 5, fetchedAtMs = 1),
        )
        val piracyRefreshed = state(
            adult to BlocklistStore.ListState(domains = 10, fetchedAtMs = 1, etags = etags),
            Blocklists.PIRACY to BlocklistStore.ListState(domains = 6, fetchedAtMs = 2),
        )
        assertEquals(ListsIdentity.of(setOf(adult), emptySet(), lists), ListsIdentity.of(setOf(adult), emptySet(), piracyRefreshed))
        assertNotEquals(
            ListsIdentity.of(setOf(adult), emptySet(), lists),
            ListsIdentity.of(setOf(adult, Blocklists.PIRACY), emptySet(), lists),
        )
        // A list that finishes its first download is a change too.
        assertNotEquals(
            ListsIdentity.of(setOf(adult), emptySet(), state()),
            ListsIdentity.of(setOf(adult), emptySet(), lists),
        )
    }

    @Test
    fun `the bundled domains ride in the same matcher and count`() {
        val s = state()
        assertNotEquals(ListsIdentity.of(setOf(adult), setOf("a.example"), s), ListsIdentity.of(setOf(adult), setOf("b.example"), s))
    }
}
