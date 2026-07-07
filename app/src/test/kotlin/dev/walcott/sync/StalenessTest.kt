package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StalenessTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `never seen devices are not warned about`() {
        assertNull(Staleness.silenceMs(null, now))
        assertFalse(Staleness.isWarn(null, now))
    }

    @Test
    fun `warning starts after 30 minutes of silence`() {
        assertFalse(Staleness.isWarn(now - Staleness.WARN_AFTER_MS + 1, now))
        assertTrue(Staleness.isWarn(now - Staleness.WARN_AFTER_MS, now))
    }

    @Test
    fun `clock skew never yields negative silence`() {
        assertEquals(0L, Staleness.silenceMs(now + 60_000, now))
    }

    @Test
    fun `alerts fire once per outage`() {
        val staleSince = now - Staleness.ALERT_AFTER_MS - 1
        val lastSeen = mapOf("dev-1" to staleSince, "dev-2" to now - 60_000)

        // First pass: only the long-silent device alerts.
        val first = Staleness.devicesToAlert(lastSeen, emptyMap(), now)
        assertEquals(mapOf("dev-1" to staleSince), first)

        // Second pass with the alert recorded: nothing new.
        assertTrue(Staleness.devicesToAlert(lastSeen, first, now).isEmpty())

        // The device comes back, then goes silent again: it alerts again.
        val cameBack = mapOf("dev-1" to now)
        val muchLater = now + Staleness.ALERT_AFTER_MS + 1
        assertEquals(mapOf("dev-1" to now), Staleness.devicesToAlert(cameBack, first, muchLater))
    }
}
