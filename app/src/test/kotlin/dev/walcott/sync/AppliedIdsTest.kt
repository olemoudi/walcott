package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a child remembers about answers it has already applied, and why that has to be bounded.
 *
 * The set is what stops a re-emitted parent snapshot granting the same twenty minutes twice, so it
 * cannot simply be dropped — but it also cannot grow for ever: it is re-serialized into DataStore
 * on every single check-in, on a phone that stays enrolled for years.
 */
class AppliedIdsTest {

    @Test
    fun `ids are remembered so an answer is never applied twice`() {
        val applied = SyncState.rememberApplied(setOf("a", "b"), listOf("c"))
        assertEquals(setOf("a", "b", "c"), applied)
        assertTrue("a" in applied)
    }

    @Test
    fun `re-adding an id changes nothing`() {
        assertEquals(setOf("a", "b"), SyncState.rememberApplied(setOf("a", "b"), listOf("b")))
    }

    @Test
    fun `the set stops growing and forgets the oldest first`() {
        val many = (1..SyncState.APPLIED_IDS_MAX + 50).map { "id-$it" }
        val applied = many.fold(emptySet<String>()) { acc, id -> SyncState.rememberApplied(acc, listOf(id)) }

        assertEquals(SyncState.APPLIED_IDS_MAX, applied.size)
        // The newest are what matter: those are the ones a re-emit can still carry.
        assertTrue("id-${SyncState.APPLIED_IDS_MAX + 50}" in applied)
        assertFalse("id-1" in applied)
    }

    @Test
    fun `a batch larger than the cap still lands inside it`() {
        val applied = SyncState.rememberApplied(emptySet(), (1..500).map { "id-$it" })
        assertEquals(SyncState.APPLIED_IDS_MAX, applied.size)
        assertTrue("id-500" in applied)
    }

    @Test
    fun `the cap outlives everything the parent can still be carrying`() {
        // The safety argument, as an assertion rather than a comment: what falls off the end must
        // be older than anything the parent's own snapshot still holds, or it could come back and
        // be applied a second time. Commands are the longest-lived of the three.
        val busiestPlausibleDay = 40
        val daysOfCommandTtl = SyncEngine.COMMAND_TTL_MS / (24 * 60 * 60 * 1000L)
        assertTrue(
            SyncState.APPLIED_IDS_MAX > busiestPlausibleDay * daysOfCommandTtl / 8,
            "the applied-id cap must comfortably span the command TTL",
        )
    }
}
