package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The parent's "let this phone install anything for a while". */
class AllowInstallsCommandTest {

    private val minute = 60_000L
    private val issued = 1_000_000_000L

    @Test
    fun `a command that arrives at once opens for what was asked`() {
        assertEquals(30 * minute, RemoteAction.allowInstallsRemainingMs("30", issued, issued))
        assertEquals(120 * minute, RemoteAction.allowInstallsRemainingMs("120", issued, issued))
    }

    @Test
    fun `a command that took a while opens only for what is left`() {
        // Asked for half an hour, twenty minutes ago: ten minutes, and it ends when the parent
        // meant it to.
        assertEquals(10 * minute, RemoteAction.allowInstallsRemainingMs("30", issued, issued + 20 * minute))
    }

    @Test
    fun `nothing is left of one that took longer than it asked for`() {
        assertNull(RemoteAction.allowInstallsRemainingMs("30", issued, issued + 31 * minute))
    }

    @Test
    fun `a parent clock ahead of the phone never makes the window longer`() {
        assertEquals(30 * minute, RemoteAction.allowInstallsRemainingMs("30", issued + 10 * minute, issued))
    }

    @Test
    fun `nonsense is refused`() {
        listOf("", "abc", "0", "-5", "241", "99999").forEach {
            assertNull(RemoteAction.allowInstallsRemainingMs(it, issued, issued), "arg '$it'")
        }
    }

    @Test
    fun `it has a short life`() {
        assertFalse(RemoteAction.expired(RemoteAction.ALLOW_INSTALLS, issued, issued + 29 * minute))
        assertTrue(RemoteAction.expired(RemoteAction.ALLOW_INSTALLS, issued, issued + 31 * minute))
    }

    @Test
    fun `only a build that knows it is offered it`() {
        assertFalse(RemoteAction.canAllowInstalls(161))
        assertTrue(RemoteAction.canAllowInstalls(162))
    }
}
