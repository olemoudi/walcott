package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChannelHealthTest {

    private val now = 10_000_000_000L

    @Test
    fun `healthy while the last message is recent`() {
        assertNull(ChannelHealth.offlineSinceMs(lastOkMs = now - 1, nowMs = now))
        assertNull(ChannelHealth.offlineSinceMs(lastOkMs = now - ChannelHealth.OFFLINE_AFTER_MS + 1, nowMs = now))
    }

    @Test
    fun `offline once the silence exceeds the threshold, reporting since when`() {
        val lastOk = now - ChannelHealth.OFFLINE_AFTER_MS
        assertEquals(lastOk, ChannelHealth.offlineSinceMs(lastOkMs = lastOk, nowMs = now))
    }

    @Test
    fun `never-connected devices show nothing (fresh installs must not alarm)`() {
        assertNull(ChannelHealth.offlineSinceMs(lastOkMs = 0, nowMs = now))
    }
}
