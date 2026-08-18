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
    fun `a device that was reported gone earns a word when it comes back`() {
        // Only after an alert: a phone that merely slept for two hours is not news in either
        // direction, and "it is back" from a phone nobody said was missing is noise.
        assertTrue(Staleness.recoveryKeys("dev-1", "child-a", emptyMap()).isEmpty())

        val alerted = mapOf("dev-1" to now - Staleness.ALERT_AFTER_MS)
        assertEquals(setOf("dev-1"), Staleness.recoveryKeys("dev-1", "child-a", alerted))
        // Another device's outage says nothing about this one.
        assertTrue(Staleness.recoveryKeys("dev-2", "child-b", alerted).isEmpty())
    }

    @Test
    fun `a child that had never reported at all earns one too, under its own key`() {
        // The never-reported alert is stored by childId with NEVER, not by deviceId (see
        // childrenNeverReported), so a recovery has to look under both.
        val alerted = mapOf("child-a" to Staleness.NEVER)
        assertEquals(setOf("child-a"), Staleness.recoveryKeys("dev-1", "child-a", alerted))
        // A legacy device has no childId to match, and must not match the blank one either.
        assertTrue(Staleness.recoveryKeys("dev-1", "", mapOf("" to Staleness.NEVER)).isEmpty())
    }

    @Test
    fun `dropping the recovery keys is what lets the NEXT outage alert again`() {
        val alerted = mapOf("dev-1" to now - Staleness.ALERT_AFTER_MS, "child-a" to Staleness.NEVER)
        val cleared = alerted - Staleness.recoveryKeys("dev-1", "child-a", alerted)
        assertTrue(cleared.isEmpty())

        // Silent again after coming back: alerts, because nothing is deduping it any more.
        val silentAgain = mapOf("dev-1" to now)
        val muchLater = now + Staleness.ALERT_AFTER_MS + 1
        assertEquals(mapOf("dev-1" to now), Staleness.devicesToAlert(silentAgain, cleared, muchLater))
    }

    @Test
    fun `a short outage earns no word when it ends`() {
        // The rule ole asked for, as a floor rather than as a consequence of the alert threshold:
        // a phone that dips under a bridge and comes back must not ping about it, whatever the
        // alert numbers happen to be.
        assertFalse(Staleness.worthAnnouncingReturn(0))
        assertFalse(Staleness.worthAnnouncingReturn(90 * 60_000L))
        assertFalse(Staleness.worthAnnouncingReturn(Staleness.BACK_ONLINE_MIN_SILENCE_MS - 1))
    }

    @Test
    fun `a real absence does`() {
        assertTrue(Staleness.worthAnnouncingReturn(Staleness.BACK_ONLINE_MIN_SILENCE_MS))
        assertTrue(Staleness.worthAnnouncingReturn(Staleness.ALERT_AFTER_MS))
        // Null is not a short gap, it is no gap: a child that had never reported at all until now.
        assertTrue(Staleness.worthAnnouncingReturn(null))
    }

    @Test
    fun `the floor sits well under what an alert costs`() {
        // If these ever crossed, a return could be announced for an outage that was never
        // reported — a "back online" for something the parent was never told had gone.
        assertTrue(Staleness.BACK_ONLINE_MIN_SILENCE_MS < Staleness.ALERT_AFTER_MS)
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
