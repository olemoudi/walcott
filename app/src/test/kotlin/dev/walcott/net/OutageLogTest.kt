package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Offline, every lookup fails the same way, and a warning per lookup filled the 128 KB
 * diagnostics ring in minutes — pushing out everything that had been worth reading.
 */
class OutageLogTest {

    @Test
    fun `the first failure of an outage is logged, and the rest of that minute is not`() {
        val log = OutageLog(intervalMs = 60_000)
        assertEquals(0, log.failed(1_000))
        repeat(500) { assertNull(log.failed(1_001L + it)) }
        // Still failing a minute on: one line, saying how many were held back.
        assertEquals(500, log.failed(61_000))
        assertNull(log.failed(61_001))
    }

    @Test
    fun `an answer ends the outage, and the next failure is logged at once`() {
        val log = OutageLog(intervalMs = 60_000)
        assertEquals(0, log.failed(0))
        assertNull(log.failed(10))
        log.succeeded()
        assertEquals(1, log.failed(20), "a new outage, reporting what the last one held back")
        assertNull(log.failed(30))
    }

    @Test
    fun `success with no outage running changes nothing`() {
        val log = OutageLog(intervalMs = 60_000)
        log.succeeded()
        assertEquals(0, log.failed(0))
    }

    @Test
    fun `the held-back count is only mentioned when there is one`() {
        assertEquals("", OutageLog.heldBackSuffix(0))
        assertEquals(" (3 more like it since the last report)", OutageLog.heldBackSuffix(3))
    }
}
