package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Repeated asks about the same app collapse into one card on the parent.
 *
 * The child is meant not to send duplicates, but only its per-app path refuses to — the picker
 * sheet never checked — and an older child build goes on sending them whatever this build does.
 * Three cards for one question is not only untidy: each carries its own grant button, so the
 * parent can hand out the same extra time three times without noticing.
 */
class RequestCollapseTest {

    private val now = 1_000_000_000_000L

    private fun request(id: String, target: String, at: Long, minutes: Int = 15) = ExtraTimeRequest(
        requestId = id,
        categoryId = target,
        minutes = minutes,
        createdAtEpochMs = at,
    )

    @Test
    fun `three asks about one app leave the newest`() {
        val requests = listOf(
            request("a", "com.duolingo", now - 600_000, minutes = 10),
            request("b", "com.duolingo", now - 300_000, minutes = 20),
            request("c", "com.duolingo", now, minutes = 30),
        )
        val collapsed = SyncEngine.newestPerTarget(requests)
        assertEquals(listOf("c"), collapsed.map { it.requestId })
        // The newest minutes are what the parent is answering, not the first ask's.
        assertEquals(30, collapsed.single().minutes)
    }

    @Test
    fun `asks about different apps all survive`() {
        val requests = listOf(
            request("a", "com.duolingo", now - 600_000),
            request("b", "com.spotify.music", now - 300_000),
            request("c", "__all_apps__", now),
        )
        assertEquals(
            listOf("a", "b", "c"),
            SyncEngine.newestPerTarget(requests).map { it.requestId },
        )
    }

    @Test
    fun `the newest wins wherever it sits in the list`() {
        // Arrival order is not send order: a replayed backlog can deliver them any way round.
        val requests = listOf(
            request("newest", "com.duolingo", now),
            request("oldest", "com.duolingo", now - 600_000),
        )
        assertEquals(listOf("newest"), SyncEngine.newestPerTarget(requests).map { it.requestId })
    }

    @Test
    fun `order follows each target's first appearance, so answering one does not reshuffle`() {
        val requests = listOf(
            request("a1", "com.duolingo", now - 600_000),
            request("b1", "com.spotify.music", now - 500_000),
            request("a2", "com.duolingo", now),
        )
        // com.duolingo keeps first place even though its surviving request is the newest one.
        assertEquals(
            listOf("a2", "b1"),
            SyncEngine.newestPerTarget(requests).map { it.requestId },
        )
    }

    @Test
    fun `a legacy child sending no timestamps still collapses to one, deterministically`() {
        val requests = listOf(
            request("aaa", "com.duolingo", 0),
            request("zzz", "com.duolingo", 0),
        )
        assertEquals(listOf("zzz"), SyncEngine.newestPerTarget(requests).map { it.requestId })
        // Same set, other arrival order: the same survivor, so the parent's list can't flicker.
        assertEquals(listOf("zzz"), SyncEngine.newestPerTarget(requests.reversed()).map { it.requestId })
    }

    @Test
    fun `nothing to collapse is left exactly alone`() {
        assertEquals(emptyList<ExtraTimeRequest>(), SyncEngine.newestPerTarget(emptyList()))
        val one = listOf(request("a", "com.duolingo", now))
        assertEquals(one, SyncEngine.newestPerTarget(one))
    }
}
