package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class BackupReminderTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val setup = 1_000_000L

    private fun remind(
        now: Long,
        enabled: Boolean = true,
        lastBackup: Long = 0,
        lastEdit: Long = 0,
        lastReminder: Long = 0,
    ) = BackupReminder.shouldRemind(now, enabled, setup, lastBackup, lastEdit, lastReminder)

    @Test
    fun `silent while disabled or before the device is a parent`() {
        assertFalse(remind(now = setup + 90 * day, enabled = false))
        assertFalse(BackupReminder.shouldRemind(setup + 90 * day, true, 0, 0, 0, 0))
    }

    @Test
    fun `never backed up - first nudge at five days, then every two`() {
        assertFalse(remind(now = setup + 4 * day))
        assertTrue(remind(now = setup + 5 * day))
        val first = setup + 5 * day
        assertFalse(remind(now = first + 1 * day, lastReminder = first))
        assertTrue(remind(now = first + 2 * day, lastReminder = first))
    }

    @Test
    fun `an unchanged backup never nags`() {
        val backup = setup + 10 * day
        // Policy last edited BEFORE the backup: silence forever, however old the file gets.
        assertFalse(remind(now = backup + 400 * day, lastBackup = backup, lastEdit = backup - day))
    }

    @Test
    fun `stale backup ladder - thirty days, forty-five, then every two`() {
        val backup = setup + 10 * day
        val edit = backup + day
        assertFalse(remind(now = backup + 29 * day, lastBackup = backup, lastEdit = edit))
        assertTrue(remind(now = backup + 30 * day, lastBackup = backup, lastEdit = edit))
        val first = backup + 30 * day
        // After the 30-day nudge: quiet until 45 days, even though 2 days have passed.
        assertFalse(remind(now = backup + 40 * day, lastBackup = backup, lastEdit = edit, lastReminder = first))
        assertTrue(remind(now = backup + 45 * day, lastBackup = backup, lastEdit = edit, lastReminder = first))
        val second = backup + 45 * day
        // From then on, every two days.
        assertFalse(remind(now = second + day, lastBackup = backup, lastEdit = edit, lastReminder = second))
        assertTrue(remind(now = second + 2 * day, lastBackup = backup, lastEdit = edit, lastReminder = second))
    }

    @Test
    fun `a fresh backup resets the ladder`() {
        val oldBackup = setup + 10 * day
        val nagged = oldBackup + 45 * day
        // The parent finally backs up: reminders stop, and only a NEW edit re-arms the 30-day clock.
        val newBackup = nagged + day
        assertFalse(remind(now = newBackup + 29 * day, lastBackup = newBackup, lastEdit = oldBackup + day, lastReminder = nagged))
        assertTrue(
            remind(
                now = newBackup + 30 * day,
                lastBackup = newBackup,
                lastEdit = newBackup + day,
                lastReminder = nagged,
            ),
        )
    }
}
