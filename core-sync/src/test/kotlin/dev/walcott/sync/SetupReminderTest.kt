package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupReminderTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `stays quiet while the parent is still standing over the phone`() {
        // Seen incomplete a minute ago: they are almost certainly mid-enrollment, with the same
        // list on the screen in front of them.
        assertFalse(SetupReminder.shouldRemind(now - 60_000, lastReminderMs = 0, nowMs = now))
    }

    @Test
    fun `reminds once the grace period is over`() {
        assertTrue(SetupReminder.shouldRemind(now - SetupReminder.GRACE_MS, lastReminderMs = 0, nowMs = now))
    }

    @Test
    fun `does not repeat before the repeat window`() {
        val pendingSince = now - 5 * SetupReminder.REPEAT_MS
        assertFalse(SetupReminder.shouldRemind(pendingSince, lastReminderMs = now - 60_000, nowMs = now))
    }

    @Test
    fun `repeats while the problem lasts`() {
        val pendingSince = now - 5 * SetupReminder.REPEAT_MS
        assertTrue(
            SetupReminder.shouldRemind(pendingSince, lastReminderMs = now - SetupReminder.REPEAT_MS, nowMs = now),
        )
    }

    @Test
    fun `a device that was never seen incomplete is never mentioned`() {
        assertFalse(SetupReminder.shouldRemind(pendingSinceMs = 0, lastReminderMs = 0, nowMs = now))
    }

    @Test
    fun `a clock that jumped backwards cannot silence the reminder for ever`() {
        // A stamp parked in the future would otherwise never be REPEAT_MS old.
        val pendingSince = now - 2 * SetupReminder.REPEAT_MS
        assertTrue(SetupReminder.shouldRemind(pendingSince, lastReminderMs = now + 86_400_000, nowMs = now))
    }
}
