package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelpAsksTest {

    private val start = 1_000_000L

    @Test
    fun `an unanswered call for help is announced again after the interval, not before`() {
        val first = HelpAsks.Reminded(lastAtMs = start)
        assertFalse(HelpAsks.reminderDue(first, start + HelpAsks.REMINDER_INTERVAL_MS - 1))
        assertTrue(HelpAsks.reminderDue(first, start + HelpAsks.REMINDER_INTERVAL_MS))
    }

    @Test
    fun `the reminders stop, so the channel is not silenced for the next one`() {
        var reminded = HelpAsks.Reminded(lastAtMs = start)
        var now = start
        var posted = 0
        repeat(20) {
            now += HelpAsks.REMINDER_INTERVAL_MS
            if (HelpAsks.reminderDue(reminded, now)) {
                reminded = reminded.next(now)
                posted++
            }
        }
        assertEquals(HelpAsks.MAX_REMINDERS, posted)
    }

    @Test
    fun `a clock that went backwards waits instead of firing at once`() {
        assertFalse(HelpAsks.reminderDue(HelpAsks.Reminded(lastAtMs = start), start - 60 * 60 * 1000L))
    }

    @Test
    fun `the button comes back once the window has passed, and not before`() {
        // The failure this exists for is not an impatient person: it is the family who rang,
        // sorted it out on the telephone and never pressed "I've helped". Nothing else closes a
        // call for help, so the button it hid used to be gone for the two days the ask lives.
        assertFalse(HelpAsks.reaskAllowed(start, start))
        assertFalse(HelpAsks.reaskAllowed(start, start + HelpAsks.REASK_AFTER_MS - 1))
        assertTrue(HelpAsks.reaskAllowed(start, start + HelpAsks.REASK_AFTER_MS))
    }

    @Test
    fun `a clock that went backwards does not re-arm the button`() {
        assertFalse(HelpAsks.reaskAllowed(start, start - 60 * 60 * 1000L))
    }

    @Test
    fun `the window is shorter than the life of the ask and longer than the parent's catch-up`() {
        // Both ends matter. Shorter than the ask's life, or the window is unreachable and the
        // button is dead exactly as before. Longer than the parent's fastest catch-up, or asking
        // again cannot have reached anybody the first ask did not — it would only replace a call
        // for help that was still on its way.
        assertTrue(HelpAsks.REASK_AFTER_MS < SyncEngine.REQUEST_TTL_MS)
        assertTrue(HelpAsks.REASK_AFTER_MS >= ParentCadence.FAST_MS)
    }

    @Test
    fun `only a help ask the relay has not confirmed is waited on`() {
        val help = ChildRequest("h1", ChildRequest.KIND_HELP, "help", createdAtEpochMs = 1)
        val sentHelp = ChildRequest("h2", ChildRequest.KIND_HELP, "help", createdAtEpochMs = 2)
        val app = ChildRequest("a1", ChildRequest.KIND_INSTALL, "Maps", createdAtEpochMs = 3, pkg = "com.maps")
        assertEquals(setOf("h1"), HelpAsks.unconfirmed(listOf(help, sentHelp, app), receipts = setOf("h2")))
        assertTrue(HelpAsks.unconfirmed(listOf(app), receipts = emptySet()).isEmpty())
    }
}
