package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Batching the writes must not change what the rules see, or which day a second is filed under. */
class UsageBatchTest {

    private val today = 20_000L

    @Test
    fun `pending seconds are seen by the rules before they are written`() {
        val batch = UsageBatch()
        batch.credit("game", today, 2)
        batch.credit("game", today, 2)
        val seen = batch.overlay(mapOf("game" to Duration.ofMinutes(10)), today)
        assertEquals(Duration.ofMinutes(10).plusSeconds(4), seen["game"])
    }

    @Test
    fun `an app with nothing in the database yet still counts`() {
        val batch = UsageBatch()
        batch.credit("new", today, 6)
        assertEquals(Duration.ofSeconds(6), batch.overlay(emptyMap(), today)["new"])
    }

    @Test
    fun `yesterday's pending seconds are not added to today`() {
        val batch = UsageBatch()
        batch.credit("game", today - 1, 30)
        val base = mapOf("game" to Duration.ofMinutes(1))
        assertSame(base, batch.overlay(base, today))
        assertTrue(batch.holdsDayBefore(today))
    }

    @Test
    fun `draining files each day separately and empties the batch`() {
        val batch = UsageBatch()
        batch.credit("game", today - 1, 30)
        batch.credit("game", today, 4)
        batch.credit("chat", today, 2)
        val credits = batch.drain().toSet()
        assertEquals(
            setOf(
                UsageBatch.Credit("game", today - 1, 30),
                UsageBatch.Credit("game", today, 4),
                UsageBatch.Credit("chat", today, 2),
            ),
            credits,
        )
        assertTrue(batch.isEmpty)
        assertFalse(batch.holdsDayBefore(today))
    }

    @Test
    fun `a non-positive credit is ignored`() {
        val batch = UsageBatch()
        batch.credit("game", today, 0)
        batch.credit("game", today, -3)
        assertTrue(batch.isEmpty)
    }
}
