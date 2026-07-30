package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slicing a parent's selection across a channel that guarantees nothing, and putting it back
 * together. The promise being tested is the one a parent makes to themselves when they tick forty
 * boxes: all forty arrive, once each, however the messages behave on the way.
 */
class DomainDeliveryTest {

    private fun domains(n: Int) = (1..n).map { "d$it.example.com" }

    @Test
    fun `a handful of domains travels as one slice`() {
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(3))
        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().chunks)
        assertEquals(domains(3), chunks.single().domains)
    }

    @Test
    fun `a long selection is cut into slices that each know the whole shape`() {
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(45))
        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.chunks == 5 }, "a slice that doesn't know the total can't be waited for")
        assertEquals((0..4).toList(), chunks.map { it.index })
        assertEquals(domains(45), chunks.flatMap { it.domains })
    }

    @Test
    fun `an empty or blank selection produces nothing to send`() {
        assertTrue(DomainDelivery.chunk("b1", "com.game", "Game", emptyList()).isEmpty())
        assertTrue(DomainDelivery.chunk("b1", "com.game", "Game", listOf("", "   ")).isEmpty())
    }

    @Test
    fun `duplicates are dropped before they are ever sent`() {
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", listOf("a.com", "a.com", " a.com ", "b.com"))
        assertEquals(listOf("a.com", "b.com"), chunks.flatMap { it.domains })
    }

    @Test
    fun `it round-trips whole`() {
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(45))
        assertEquals(domains(45), DomainDelivery.assemble(chunks))
    }

    @Test
    fun `slices arriving out of order reassemble in the right order`() {
        // Nothing about the channel promises order, and a parent reading a scrambled list would
        // not know it was scrambled.
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(35))
        assertEquals(domains(35), DomainDelivery.assemble(chunks.shuffled(java.util.Random(7))))
    }

    @Test
    fun `a slice delivered twice changes nothing`() {
        // Resending is the delivery mechanism, so double delivery is the normal case, not an edge.
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(25))
        assertEquals(domains(25), DomainDelivery.assemble(chunks + chunks.first() + chunks.last()))
    }

    @Test
    fun `a batch missing a slice assembles to nothing rather than to a shorter list`() {
        // The failure that would matter: showing a parent 30 of 40 domains as if that were the
        // request, and blocking exactly the ones that happened to arrive.
        val chunks = DomainDelivery.chunk("b1", "com.game", "Game", domains(40))
        assertNull(DomainDelivery.assemble(chunks.drop(1)))
        assertNull(DomainDelivery.assemble(chunks.filterNot { it.index == 2 }))
        assertNull(DomainDelivery.assemble(emptyList()))
    }

    @Test
    fun `ack ids identify a slice, not just its batch`() {
        assertEquals("b1#0", DomainDelivery.ackId("b1", 0))
        assertFalse(DomainDelivery.ackId("b1", 0) == DomainDelivery.ackId("b1", 1))
        assertFalse(DomainDelivery.ackId("b1", 0) == DomainDelivery.ackId("b2", 0))
    }

    @Test
    fun `the child gives up after a bounded number of attempts`() {
        // Without this a batch the parent never confirms is resent on every publish, forever.
        assertFalse(DomainDelivery.giveUp(0))
        assertFalse(DomainDelivery.giveUp(DomainDelivery.MAX_ATTEMPTS - 1))
        assertTrue(DomainDelivery.giveUp(DomainDelivery.MAX_ATTEMPTS))
        assertTrue(DomainDelivery.giveUp(DomainDelivery.MAX_ATTEMPTS + 5))
    }

    // --- the child's side of the delivery, as a state machine ---

    private fun start(n: Int) = DomainDelivery.start("b1", "com.game", "Game", domains(n))!!

    @Test
    fun `starting a batch with nothing to send produces no batch at all`() {
        assertNull(DomainDelivery.start("b1", "com.game", "Game", emptyList()))
        assertNull(DomainDelivery.start("b1", "com.game", "Game", listOf(" ", "")))
    }

    @Test
    fun `a publish carries a bounded number of unconfirmed slices`() {
        val batch = start(45)
        assertEquals(DomainDelivery.CHUNKS_PER_MESSAGE, DomainDelivery.forPublish(batch).size)
        assertEquals(listOf(0, 1), DomainDelivery.forPublish(batch).map { it.index })
    }

    @Test
    fun `confirmed slices stop being sent and the rest move up`() {
        var batch = start(45)
        batch = DomainDelivery.acked(batch, setOf(DomainDelivery.ackId("b1", 0), DomainDelivery.ackId("b1", 1)))
        assertEquals(listOf(2, 3), DomainDelivery.forPublish(batch).map { it.index })
        assertFalse(batch.delivered)
    }

    @Test
    fun `a delivered batch stops riding the snapshot`() {
        var batch = start(25)
        batch = DomainDelivery.acked(batch, (0..2).map { DomainDelivery.ackId("b1", it) })
        assertTrue(batch.delivered)
        assertTrue(DomainDelivery.forPublish(batch).isEmpty()) { "a finished batch must not be resent forever" }
        assertTrue(DomainDelivery.forPublish(null).isEmpty())
    }

    @Test
    fun `acks for another batch or an unknown slice change nothing`() {
        val batch = start(25)
        assertEquals(batch, DomainDelivery.acked(batch, setOf(DomainDelivery.ackId("other", 0))))
        assertEquals(batch, DomainDelivery.acked(batch, setOf(DomainDelivery.ackId("b1", 99))))
        assertEquals(batch, DomainDelivery.acked(batch, emptyList()))
    }

    @Test
    fun `a batch nobody answers is abandoned, and abandoning it stops the resends`() {
        var batch = start(25)
        repeat(DomainDelivery.MAX_ATTEMPTS - 1) { batch = DomainDelivery.published(batch) }
        assertFalse(batch.abandoned)
        assertTrue(DomainDelivery.forPublish(batch).isNotEmpty())

        batch = DomainDelivery.published(batch)
        assertTrue(batch.abandoned)
        assertTrue(DomainDelivery.forPublish(batch).isEmpty()) { "out of retries means out of retries" }
        assertFalse(batch.delivered) { "abandoned is not delivered — the child has to be told" }
    }

    @Test
    fun `a big selection is not abandoned mid-delivery on a working channel`() {
        // The bug this pins down: counting total publishes instead of unanswered ones. A batch
        // only offers two slices per message, so 40 slices need 20 publishes — more than the
        // give-up bound — and every one of them was being confirmed.
        var batch = start(400)
        assertEquals(40, batch.slices.size)
        var publishes = 0
        while (!batch.delivered && publishes < 100) {
            val sent = DomainDelivery.forPublish(batch)
            assertTrue(sent.isNotEmpty()) { "gave up after $publishes publishes with every slice confirmed" }
            batch = DomainDelivery.published(batch)
            batch = DomainDelivery.acked(batch, sent.map { DomainDelivery.ackId(batch.batchId, it.index) })
            publishes++
        }
        assertTrue(batch.delivered)
        assertEquals(20, publishes)
        assertEquals(domains(400), DomainDelivery.assemble(batch.slices))
    }

    @Test
    fun `a slice getting through resets the patience for the ones behind it`() {
        var batch = start(45)
        repeat(DomainDelivery.MAX_ATTEMPTS - 1) { batch = DomainDelivery.published(batch) }
        batch = DomainDelivery.acked(batch, setOf(DomainDelivery.ackId("b1", 0)))
        assertEquals(0, batch.roundsWithoutAck) { "the channel just proved it works" }
        repeat(DomainDelivery.MAX_ATTEMPTS - 1) { batch = DomainDelivery.published(batch) }
        assertFalse(batch.abandoned)
    }

    @Test
    fun `the batch knows how many domains it is carrying`() {
        // What the child's screen and the parent's card both count.
        assertEquals(45, start(45).domainCount)
    }
}
