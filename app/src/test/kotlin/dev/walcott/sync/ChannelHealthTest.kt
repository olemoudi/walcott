package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

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

    @Test
    fun `a socket that has delivered recently is left alone`() {
        assertFalse(ChannelHealth.needsReconnect(lastProofMs = now - 1, nowMs = now))
        assertFalse(ChannelHealth.needsReconnect(lastProofMs = now - ChannelHealth.RECONNECT_AFTER_MS + 1, nowMs = now))
    }

    @Test
    fun `a socket silent for an hour is presumed dead and rebuilt`() {
        assertTrue(ChannelHealth.needsReconnect(lastProofMs = now - ChannelHealth.RECONNECT_AFTER_MS, nowMs = now))
    }

    @Test
    fun `nothing to judge means nothing to reconnect`() {
        // Zero is "no socket and no message ever": the caller passes the connect time once there
        // is one, so this can only be an unpaired device.
        assertFalse(ChannelHealth.needsReconnect(lastProofMs = 0, nowMs = now))
    }

    @Test
    fun `a rebuild is attempted well before anything else gives up on the channel`() {
        // The two deadlines this must stay inside: the child's own "you are offline" banner, and
        // the connectivity failure that voids an emergency release. Both deserve a retry first.
        assertTrue(ChannelHealth.RECONNECT_AFTER_MS < ChannelHealth.OFFLINE_AFTER_MS)
        val panicDeadlineMs =
            (PanicProtocol.CHECKPOINT_INTERVAL_SEC + PanicProtocol.CHECKPOINT_GRACE_SEC) * 1000
        assertTrue(ChannelHealth.RECONNECT_AFTER_MS < panicDeadlineMs)
    }
}
