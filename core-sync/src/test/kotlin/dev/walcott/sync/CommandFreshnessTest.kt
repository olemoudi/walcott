package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which remote commands stop being worth running, and when.
 *
 * The two with a life on them are the two that act on the phone rather than on the app, and both
 * would be nasty surprises if they landed late: a lock-screen PIN nobody remembers being told, and
 * a phone freed after the family changed their mind — which cannot be undone without a factory
 * reset. Everything else must keep working after a fortnight offline, which is the other half of
 * this rule and the easier half to get wrong.
 */
class CommandFreshnessTest {

    private val issued = 1_720_000_000_000L

    @Test
    fun `a release is honoured while it is fresh`() {
        assertFalse(RemoteAction.expired(RemoteAction.RELEASE_DEVICE, issued, issued + 60_000))
        assertFalse(
            RemoteAction.expired(RemoteAction.RELEASE_DEVICE, issued, issued + RemoteAction.RELEASE_TTL_MS),
        )
    }

    @Test
    fun `a release that has waited longer than the parent's own queue is refused`() {
        // The parent drops a command from its snapshot after COMMAND_TTL_MS, so anything older
        // arriving is a replay or a very stale relay backlog — not a family still meaning it.
        assertTrue(
            RemoteAction.expired(RemoteAction.RELEASE_DEVICE, issued, issued + RemoteAction.RELEASE_TTL_MS + 1),
        )
        assertTrue(RemoteAction.RELEASE_TTL_MS <= SyncEngine.COMMAND_TTL_MS)
    }

    @Test
    fun `a lock-screen PIN keeps its much shorter life`() {
        assertFalse(RemoteAction.expired(RemoteAction.SET_LOCK_PIN, issued, issued + 60_000))
        assertTrue(RemoteAction.expired(RemoteAction.SET_LOCK_PIN, issued, issued + RemoteAction.LOCK_PIN_TTL_MS + 1))
    }

    @Test
    fun `a child too old to understand a release is never offered one`() {
        // The gate exists so the parent is told before they try, not by an "unsupported" ack
        // afterwards. 0 is what a child that does not report its build sends, and it is exactly
        // the kind of child that cannot do this.
        assertFalse(RemoteAction.canRelease(0))
        assertFalse(RemoteAction.canRelease(RemoteAction.RELEASE_MIN_CHILD_VERSION - 1))
        assertTrue(RemoteAction.canRelease(RemoteAction.RELEASE_MIN_CHILD_VERSION))
        assertTrue(RemoteAction.canRelease(RemoteAction.RELEASE_MIN_CHILD_VERSION + 40))
    }

    @Test
    fun `everything else is still worth running after a long time offline`() {
        val fortnight = 14 * 24 * 60 * 60 * 1000L
        for (action in listOf(
            RemoteAction.UPDATE_NOW,
            RemoteAction.REAPPLY_POLICY,
            RemoteAction.REQUEST_PERMISSIONS,
            RemoteAction.INSTALL_APP,
            RemoteAction.UNINSTALL_APP,
            RemoteAction.ALLOW_APP,
            RemoteAction.DIAGNOSE,
            RemoteAction.DENY_PANIC,
            RemoteAction.LOCK_NOW,
            RemoteAction.NOTIFICATION_LOG,
        )) {
            assertFalse(RemoteAction.expired(action, issued, issued + fortnight), action)
        }
    }
}
