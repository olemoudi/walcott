package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Moving a family from one relay to another without stranding a phone.
 *
 * The rules that decide whether that is survivable, rather than the plumbing that performs it: an
 * instruction that must NOT expire the way the other dangerous ones do, an address that has to be
 * a relay before a phone is pointed at it, and a window long enough that a device which was off
 * during the move still finds the instruction when it comes back.
 */
class RelayMigrationTest {

    private val issued = 1_720_000_000_000L

    @Test
    fun `the move never expires on the child, unlike the other dangerous commands`() {
        // The opposite of the release and the lock PIN, and deliberately so: a phone that spent a
        // fortnight in a drawer is exactly the phone that still needs to be told where everyone
        // went. What bounds it is the parent's own queue, not the child's clock.
        val fortnight = 14 * 24 * 60 * 60 * 1000L
        assertFalse(RemoteAction.expired(RemoteAction.SET_RELAY, issued, issued + fortnight))
        assertTrue(RemoteAction.expired(RemoteAction.RELEASE_DEVICE, issued, issued + fortnight))
    }

    @Test
    fun `the window the parent holds the old relay open for covers the command's own life`() {
        // If the window were shorter, the parent would stop listening on the old relay while the
        // instruction was still queued — a phone could come back inside the TTL, find the command,
        // move, and acknowledge into a socket nobody was holding.
        assertTrue(RemoteAction.RELAY_MIGRATION_WINDOW_MS >= SyncEngine.COMMAND_TTL_MS)
    }

    @Test
    fun `only a real relay address is worth pointing a phone at`() {
        // The address arrives signed, so it is the parent's — but a typo is still a typo, and a
        // child that follows one has no way back.
        assertNull(RelayServer.normalize("not a url"))
        assertNull(RelayServer.normalize("https://ntfy.example.com/some/path"))
        assertNull(RelayServer.normalize(""))
        assertEquals("https://ntfy.example.com", RelayServer.normalize("ntfy.example.com"))
        assertEquals("http://192.168.1.10:8080", RelayServer.normalize("http://192.168.1.10:8080"))
    }

    @Test
    fun `a child too old to understand the move is known before it is asked`() {
        assertFalse(RemoteAction.canMigrateRelay(0))
        assertFalse(RemoteAction.canMigrateRelay(RemoteAction.RELAY_MIN_CHILD_VERSION - 1))
        assertTrue(RemoteAction.canMigrateRelay(RemoteAction.RELAY_MIN_CHILD_VERSION))
    }

    @Test
    fun `queuing the move for several devices keeps one command each`() {
        // Every phone has to be told, and re-tapping must retry rather than stack (see
        // SyncEngine.withCommand): same action, same argument, one command per device.
        val relay = "https://ntfy.example.com"
        var queued = emptyList<RemoteCommand>()
        for (device in listOf("dev-1", "dev-2")) {
            queued = SyncEngine.withCommand(
                queued,
                RemoteCommand("cmd-$device", device, RemoteAction.SET_RELAY, issued, relay),
                issued,
            )
        }
        assertEquals(2, queued.size)

        val retried = SyncEngine.withCommand(
            queued,
            RemoteCommand("cmd-again", "dev-1", RemoteAction.SET_RELAY, issued + 1_000, relay),
            issued + 1_000,
        )
        assertEquals(2, retried.size, "re-asking one device must replace its command, not add one")
        assertEquals(setOf("dev-1", "dev-2"), retried.map { it.deviceId }.toSet())
    }
}
