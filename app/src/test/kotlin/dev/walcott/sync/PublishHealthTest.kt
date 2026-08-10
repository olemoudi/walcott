package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishHealthTest {

    private val now = 1_700_000_000_000L

    private fun afterFailures(count: Int, code: Int = 0): PublishHealth.Status {
        var status = PublishHealth.Status()
        repeat(count) { status = PublishHealth.next(status, ok = false, code = code, atMs = now) }
        return status
    }

    @Test
    fun `one success wipes the slate`() {
        val broken = afterFailures(5, code = 429)
        assertTrue(broken.failing)
        val healed = PublishHealth.next(broken, ok = true, code = 0, atMs = now)
        assertEquals(PublishHealth.Status(), healed)
        assertFalse(healed.failing)
    }

    @Test
    fun `a run of failures is what raises it, not a single one`() {
        repeat(PublishHealth.FAILURES_BEFORE_ALERT - 1) { i ->
            assertFalse(afterFailures(i + 1).failing, "after ${i + 1} failures")
        }
        assertTrue(afterFailures(PublishHealth.FAILURES_BEFORE_ALERT).failing)
    }

    @Test
    fun `rate limiting is called out separately, and only once it is consistent`() {
        assertFalse(afterFailures(1, code = 429).rateLimited)
        assertTrue(afterFailures(PublishHealth.FAILURES_BEFORE_ALERT, code = 429).rateLimited)
        // A run of plain network failures is failing, but it is not the relay refusing us.
        assertFalse(afterFailures(PublishHealth.FAILURES_BEFORE_ALERT, code = 0).rateLimited)
        assertFalse(afterFailures(PublishHealth.FAILURES_BEFORE_ALERT, code = 503).rateLimited)
    }

    @Test
    fun `the last rejection code and time are the newest ones`() {
        var status = PublishHealth.next(PublishHealth.Status(), ok = false, code = 503, atMs = now)
        status = PublishHealth.next(status, ok = false, code = 429, atMs = now + 5_000)
        assertEquals(2, status.consecutiveFailures)
        assertEquals(429, status.lastRejectionCode)
        assertEquals(now + 5_000, status.lastFailureAtMs)
    }
}
