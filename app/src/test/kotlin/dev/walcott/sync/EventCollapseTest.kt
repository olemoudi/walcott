package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventCollapseTest {

    private fun event(
        type: String = ParentEvent.TYPE_REMOTE_DONE,
        childId: String = "c1",
        detail: String = "update_now",
        count: Int = 1,
        atMs: Long = 0,
        id: String = "$type-$childId-$detail-$count-$atMs",
    ) = ParentEvent(id = id, atMs = atMs, type = type, childId = childId, detail = detail, count = count)

    @Test
    fun `a run of identical entries folds into its first element with the run length`() {
        val run = listOf(event(atMs = 30), event(atMs = 20), event(atMs = 10))
        val collapsed = ParentEvent.collapseRepeats(run)
        assertEquals(1, collapsed.size)
        assertEquals(run.first() to 3, collapsed.single())
    }

    @Test
    fun `entries differing in type, child, detail or count stay separate`() {
        val base = event(atMs = 40)
        val feed = listOf(
            base,
            event(type = ParentEvent.TYPE_BONUS, atMs = 30),
            event(childId = "c2", atMs = 20),
            event(detail = "reapply_policy", atMs = 10),
            event(count = 0, atMs = 5),
        )
        assertEquals(feed.map { it to 1 }, ParentEvent.collapseRepeats(feed))
    }

    @Test
    fun `an interleaved different entry breaks the run`() {
        val feed = listOf(
            event(atMs = 50),
            event(atMs = 40),
            event(type = ParentEvent.TYPE_BONUS, atMs = 30),
            event(atMs = 20),
        )
        val collapsed = ParentEvent.collapseRepeats(feed)
        assertEquals(3, collapsed.size)
        assertEquals(feed[0] to 2, collapsed[0])
        assertEquals(feed[2] to 1, collapsed[1])
        assertEquals(feed[3] to 1, collapsed[2])
    }

    @Test
    fun `an empty feed collapses to nothing`() {
        assertEquals(emptyList<Pair<ParentEvent, Int>>(), ParentEvent.collapseRepeats(emptyList()))
    }
}
