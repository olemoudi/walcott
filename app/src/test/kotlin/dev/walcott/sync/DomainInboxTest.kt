package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parent's side of a chunked domain delivery. Two rules here are invisible from the UI and
 * both bite in the same way — a request that keeps coming back, or a child that never stops
 * resending — so they are pinned down rather than clicked through.
 */
class DomainInboxTest {

    private val now = 1_700_000_000_000L

    private fun slices(n: Int, batchId: String = "b1") =
        DomainDelivery.chunk(batchId, "com.game", "Game", (1..n).map { "d$it.example.com" })

    private fun merge(
        inbox: List<DomainInboxEntry>,
        incoming: List<DomainChunk>,
        handled: List<String> = emptyList(),
    ) = DomainInbox.merge(inbox, incoming, "dev-1", "child-1", "Ana", handled, now)

    @Test
    fun `a batch is incomplete until its last slice arrives`() {
        val all = slices(25)
        var inbox = merge(emptyList(), all.take(2))
        assertEquals(1, inbox.size)
        assertFalse(inbox.single().complete)
        assertNull(inbox.single().domains())
        assertEquals(1, inbox.single().missing)

        inbox = merge(inbox, all.drop(2))
        assertTrue(inbox.single().complete)
        assertEquals(25, inbox.single().domains()!!.size)
        assertEquals(0, inbox.single().missing)
    }

    @Test
    fun `the sender is recorded from the first slice that arrives`() {
        val entry = merge(emptyList(), slices(5)).single()
        assertEquals("dev-1", entry.deviceId)
        assertEquals("child-1", entry.childId)
        assertEquals("Ana", entry.childName)
        assertEquals("com.game", entry.packageName)
        assertEquals("Game", entry.label)
        assertEquals(now, entry.firstSeenMs)
    }

    @Test
    fun `a slice delivered twice is not counted twice`() {
        // Resending is how delivery works, so this is the common case.
        val all = slices(25)
        var inbox = merge(emptyList(), all)
        inbox = merge(inbox, all.take(2) + all)
        assertEquals(3, inbox.single().slices.size)
        assertEquals((1..25).map { "d$it.example.com" }, inbox.single().domains())
    }

    @Test
    fun `slices arriving out of order still assemble in order`() {
        val inbox = merge(emptyList(), slices(30).reversed())
        assertEquals((1..30).map { "d$it.example.com" }, inbox.single().domains())
    }

    @Test
    fun `two children can be mid-delivery at once`() {
        var inbox = merge(emptyList(), slices(15, "b1"))
        inbox = DomainInbox.merge(inbox, slices(15, "b2"), "dev-2", "child-2", "Leo", emptyList(), now + 1)
        assertEquals(2, inbox.size)
        assertEquals(setOf("b1", "b2"), inbox.map { it.batchId }.toSet())
        assertEquals("Leo", inbox.first { it.batchId == "b2" }.childName)
    }

    @Test
    fun `slices for a batch already answered are dropped, not re-opened`() {
        // The discard bug this guards: the parent dismisses a request, an in-flight nudge arrives,
        // and the card the parent just got rid of is back on their home.
        val inbox = merge(emptyList(), slices(15), handled = listOf("b1"))
        assertTrue(inbox.isEmpty())
    }

    @Test
    fun `an answered batch is still acknowledged, so the child stops resending`() {
        // The other half of the same story: dropping the slices must not mean ignoring them, or
        // the child keeps nudging until it gives up and reports a failure that never happened.
        val acks = DomainInbox.withAcks(emptyList(), slices(15))
        assertEquals(listOf("b1#0", "b1#1"), acks)
    }

    @Test
    fun `acknowledging the same slice twice does not grow the list`() {
        val all = slices(15)
        val once = DomainInbox.withAcks(emptyList(), all)
        assertEquals(once, DomainInbox.withAcks(once, all))
    }

    @Test
    fun `acks and handled ids stay bounded`() {
        var acks = emptyList<String>()
        repeat(200) { i -> acks = DomainInbox.withAcks(acks, slices(5, "batch$i")) }
        assertEquals(DomainInbox.MAX_ACKS, acks.size)
        assertTrue(acks.last().startsWith("batch199#")) { "the newest ack is the one that must survive" }

        var handled = emptyList<String>()
        repeat(200) { i -> handled = DomainInbox.withHandled(handled, "batch$i") }
        assertEquals(DomainInbox.MAX_HANDLED, handled.size)
        assertTrue("batch199" in handled)
    }

    @Test
    fun `the inbox is bounded, dropping the oldest batches`() {
        var inbox = emptyList<DomainInboxEntry>()
        repeat(DomainInbox.MAX_ENTRIES + 5) { i ->
            inbox = DomainInbox.merge(
                inbox, slices(5, "batch$i"), "dev-1", "child-1", "Ana", emptyList(), now + i,
            )
        }
        assertEquals(DomainInbox.MAX_ENTRIES, inbox.size)
        assertTrue(inbox.none { it.batchId == "batch0" })
        assertTrue(inbox.any { it.batchId == "batch${DomainInbox.MAX_ENTRIES + 4}" })
    }

    @Test
    fun `nothing arriving changes nothing`() {
        val inbox = merge(emptyList(), slices(5))
        assertEquals(inbox, merge(inbox, emptyList()))
        assertEquals(emptyList<String>(), DomainInbox.withAcks(emptyList(), emptyList()))
    }

    @Test
    fun `a batch that keeps being re-delivered after completion stays one entry`() {
        val all = slices(25)
        var inbox = merge(emptyList(), all)
        repeat(5) { inbox = merge(inbox, all) }
        assertEquals(1, inbox.size)
        assertEquals(25, inbox.single().domains()!!.size)
    }
}
