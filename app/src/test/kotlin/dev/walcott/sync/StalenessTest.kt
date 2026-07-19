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
        assertEquals(Staleness.Tier.FRESH, Staleness.tierOf(null, now))
    }

    @Test
    fun `short silences are fresh, hours are resting, half a day is silent`() {
        assertEquals(Staleness.Tier.FRESH, Staleness.tierOf(now - Staleness.RESTING_AFTER_MS + 1, now))
        assertEquals(Staleness.Tier.RESTING, Staleness.tierOf(now - Staleness.RESTING_AFTER_MS, now))
        // Two hours idle — the exact case that used to show red — is merely resting.
        assertEquals(Staleness.Tier.RESTING, Staleness.tierOf(now - 2 * 60 * 60 * 1000L, now))
        assertEquals(Staleness.Tier.RESTING, Staleness.tierOf(now - Staleness.ALERT_AFTER_MS + 1, now))
        assertEquals(Staleness.Tier.SILENT, Staleness.tierOf(now - Staleness.ALERT_AFTER_MS, now))
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

    @Test
    fun `a child registered long ago that never reported is alerted once`() {
        val addedLongAgo = now - Staleness.ALERT_AFTER_MS - 1
        val registered = mapOf("child-a" to addedLongAgo, "child-b" to now - 60_000)

        // child-a: registered long ago, never reported -> alert. child-b: too recent -> no alert.
        val first = Staleness.childrenNeverReported(registered, reportedChildIds = emptySet(), emptyMap(), now)
        assertEquals(setOf("child-a"), first)

        // Once recorded (childId -> NEVER), it doesn't re-alert.
        val notified = mapOf("child-a" to Staleness.NEVER)
        assertTrue(Staleness.childrenNeverReported(registered, emptySet(), notified, now).isEmpty())

        // A child that has since reported is excluded.
        assertTrue(
            Staleness.childrenNeverReported(registered, reportedChildIds = setOf("child-a"), emptyMap(), now).isEmpty(),
        )
    }
}
