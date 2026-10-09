package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a phone has published on the relay's day, and what it does once the relay says "no more
 * today". The hosted relay counts 250 messages a day per address — every phone behind one home
 * router together — and refuses the rest with ntfy's 42908 until midnight UTC.
 */
class PublishLedgerTest {

    // 2026-10-09 15:00 UTC.
    private val now = 1_791_558_000_000L

    @Test
    fun `messages are counted per UTC day and the count starts again at midnight`() {
        val day = LedgerDay.NONE.plusSent(now).plusSent(now)
        assertEquals(2, day.sent)
        val tomorrow = LedgerDay.nextUtcMidnight(now) + 1
        assertEquals(0, day.on(tomorrow).sent)
        assertEquals(1, day.plusSent(tomorrow).sent)
    }

    @Test
    fun `the daily quota holds until the relay's midnight, not for a fixed time`() {
        val refused = LedgerDay.NONE.plusRefused(now, quota = true)
        assertTrue(refused.quotaExhausted(now + 60_000))
        assertTrue(refused.quotaExhausted(LedgerDay.nextUtcMidnight(now) - 1))
        assertFalse(refused.quotaExhausted(LedgerDay.nextUtcMidnight(now)))
    }

    @Test
    fun `any message the relay takes ends the quota, whatever this phone worked out`() {
        // This phone's idea of the relay's day can be wrong; the relay taking a message is not.
        val refused = LedgerDay.NONE.plusRefused(now, quota = true)
        assertFalse(refused.plusSent(now + 60_000).quotaExhausted(now + 60_000))
    }

    @Test
    fun `a refusal that is not the quota counts, and holds nothing back`() {
        val refused = LedgerDay.NONE.plusRefused(now, quota = false)
        assertEquals(1, refused.refused)
        assertFalse(refused.quotaExhausted(now))
    }

    @Test
    fun `only ntfy's daily message quota is read as one`() {
        val quota = """{"code":42908,"http":429,"error":"limit reached: daily message quota reached"}"""
        val burst = """{"code":42901,"http":429,"error":"limit reached: too many requests"}"""
        assertTrue(LedgerDay.isDailyQuota(429, quota))
        // A burst is worth retrying in a second; it must not silence the phone until tomorrow.
        assertFalse(LedgerDay.isDailyQuota(429, burst))
        assertFalse(LedgerDay.isDailyQuota(503, quota))
    }

    @Test
    fun `the ledger file round-trips, and anything unreadable is an empty day`() {
        val day = LedgerDay(20_735, 42, 3, now + 1000)
        assertEquals(day, LedgerDay.parse(LedgerDay.format(day)))
        assertEquals(LedgerDay.NONE, LedgerDay.parse(null))
        assertEquals(LedgerDay.NONE, LedgerDay.parse("1 2 3"))
        assertEquals(LedgerDay.NONE, LedgerDay.parse("1 x 3 4"))
        assertEquals(LedgerDay.NONE, LedgerDay.parse("1 -2 3 4"))
    }

    @Test
    fun `when the phone last published survives the day turning over`() {
        // What "has this phone published lately" asks — a parent's process started by its alarm
        // just after midnight must not publish again because the day's count went back to zero.
        val day = LedgerDay.NONE.plusSent(now)
        assertEquals(now, day.lastSentAtMs)
        assertEquals(now, day.on(LedgerDay.nextUtcMidnight(now) + 1).lastSentAtMs)
    }
}
