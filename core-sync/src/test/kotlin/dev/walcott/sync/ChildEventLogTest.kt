package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChildEventLogTest {

    private val now = 1_800_000_000_000L

    private fun event(id: String, agoMs: Long = 0) =
        ChildEvent(id = id, atMs = now - agoMs, kind = ChildEvent.KIND_BUDGET_OUT, pkg = "com.game")

    @Test
    fun `new events are appended, oldest first`() {
        val out = ChildEventLog.plus(listOf(event("a", 1000)), listOf(event("b")), now)
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `the list never outgrows the message it rides in`() {
        // A snapshot over ntfy's cap is rejected whole, which takes the child off the air —
        // so this bound is not tidiness, it is what keeps the device reporting at all.
        val many = (1..20).map { event("e$it") }
        assertEquals(ChildEventLog.MAX, ChildEventLog.plus(emptyList(), many, now).size)
        assertEquals("e20", ChildEventLog.plus(emptyList(), many, now).last().id)
    }

    @Test
    fun `an event nobody collected in time stops travelling`() {
        // There is no acknowledgement to wait for: the parent folds each in by id and ignores
        // repeats, so an event this old has had every chance it is going to get.
        val stale = event("old", ChildEventLog.RETENTION_MS + 1)
        assertTrue(ChildEventLog.plus(listOf(stale), emptyList(), now).isEmpty())
        assertEquals(
            listOf("fresh"),
            ChildEventLog.plus(listOf(stale), listOf(event("fresh")), now).map { it.id },
        )
    }

    @Test
    fun `an event exactly at the retention edge still travels`() {
        val edge = event("edge", ChildEventLog.RETENTION_MS)
        assertEquals(listOf("edge"), ChildEventLog.plus(listOf(edge), emptyList(), now).map { it.id })
    }
}
