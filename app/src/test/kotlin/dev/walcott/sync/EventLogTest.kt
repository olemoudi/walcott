package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventLogTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun event(atMs: Long, id: String = "e$atMs") =
        ParentEvent(id = id, atMs = atMs, type = ParentEvent.TYPE_BONUS, childId = "c1")

    @Test
    fun `entries past the retention window are dropped`() {
        val kept = SyncState.pruneEvents(
            listOf(event(now - 8 * day), event(now - 6 * day), event(now)),
            now,
        )
        assertEquals(listOf("e${now - 6 * day}", "e$now"), kept.map { it.id })
    }

    @Test
    fun `an entry exactly at the window edge is kept`() {
        val edge = now - SyncState.EVENT_RETENTION_MS
        assertEquals(1, SyncState.pruneEvents(listOf(event(edge)), now).size)
        assertEquals(0, SyncState.pruneEvents(listOf(event(edge - 1)), now).size)
    }

    @Test
    fun `a busy family is capped by count, keeping the newest`() {
        val busy = (1..SyncState.EVENT_LOG_MAX + 20).map { event(now - it * 1000L, id = "e$it") }.reversed()
        val kept = SyncState.pruneEvents(busy, now)
        assertEquals(SyncState.EVENT_LOG_MAX, kept.size)
        assertEquals("e1", kept.last().id)
    }

    @Test
    fun `appending prunes on the way in, so the store never holds a stale feed`() {
        val state = SyncState(events = listOf(event(now - 30 * day), event(now - day)))
        val next = state.plusEvent(event(now, id = "fresh"))
        assertEquals(listOf("e${now - day}", "fresh"), next.events.map { it.id })
    }

    @Test
    fun `an event stamped slightly in the future is not pruned`() {
        // Clock jitter on the writing device must not silently swallow its own entry.
        assertTrue(SyncState.pruneEvents(listOf(event(now + 60_000)), now).isNotEmpty())
    }
}
