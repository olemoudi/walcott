package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstallWindowReminderTest {

    private val now = 100 * 3_600_000L
    private val hour = 3_600_000L

    @Test
    fun `no reminder while the window has been open under an hour`() {
        assertFalse(
            InstallWindowReminder.shouldRemind(
                untilMs = now + 7 * hour, firstSeenMs = now - 59 * 60_000L, lastReminderMs = 0, nowMs = now,
            ),
        )
    }

    @Test
    fun `first reminder lands once the first hour has passed`() {
        assertTrue(
            InstallWindowReminder.shouldRemind(
                untilMs = now + 7 * hour, firstSeenMs = now - hour, lastReminderMs = 0, nowMs = now,
            ),
        )
    }

    @Test
    fun `a short window expires before it ever reminds`() {
        // A 30-minute unlock: by the time an hour has passed the window is closed.
        val opened = now - hour
        assertFalse(
            InstallWindowReminder.shouldRemind(
                untilMs = opened + 30 * 60_000L, firstSeenMs = opened, lastReminderMs = 0, nowMs = now,
            ),
        )
    }

    @Test
    fun `reminders repeat hourly, not on every check`() {
        val firstSeen = now - 3 * hour
        assertFalse(
            InstallWindowReminder.shouldRemind(
                untilMs = now + 5 * hour, firstSeenMs = firstSeen, lastReminderMs = now - 10 * 60_000L, nowMs = now,
            ),
        )
        assertTrue(
            InstallWindowReminder.shouldRemind(
                untilMs = now + 5 * hour, firstSeenMs = firstSeen, lastReminderMs = now - hour, nowMs = now,
            ),
        )
    }

    @Test
    fun `a closed window and an untracked one never remind`() {
        assertFalse(
            InstallWindowReminder.shouldRemind(untilMs = now - 1, firstSeenMs = now - 2 * hour, lastReminderMs = 0, nowMs = now),
        )
        assertFalse(
            InstallWindowReminder.shouldRemind(untilMs = now + hour, firstSeenMs = 0, lastReminderMs = 0, nowMs = now),
        )
    }
}
