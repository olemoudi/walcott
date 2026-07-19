package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingOpsTest {

    private val now = 1_700_000_000_000L

    private fun command(
        id: String,
        deviceId: String = "child-1",
        action: String = RemoteAction.UPDATE_NOW,
        arg: String = "",
        issuedAtMs: Long = now,
    ) = RemoteCommand(id, deviceId, action, issuedAtMs, arg)

    private fun child(
        deviceId: String = "child-1",
        apps: List<InstalledAppInfo> = emptyList(),
        lastCommand: CommandAck? = null,
        answeredLocationRequestMs: Long = 0,
    ) = ChildSnapshot(
        deviceId = deviceId,
        displayName = deviceId,
        version = 1,
        epochDay = 20_000,
        apps = apps,
        lastCommand = lastCommand,
        answeredLocationRequestMs = answeredLocationRequestMs,
    )

    private fun openedAck(pkg: String, completedAtMs: Long = now) = CommandAck(
        id = "cmd",
        action = RemoteAction.INSTALL_APP,
        ok = true,
        detail = RemoteAction.DETAIL_INSTALL_OPENED,
        completedAtMs = completedAtMs,
        arg = pkg,
    )

    // --- Queued commands ---

    @Test
    fun `queued commands are pending and cancellable`() {
        val out = SyncEngine.pendingOps(listOf(command("a")), emptyList(), emptyList(), now)
        assertEquals(1, out.size)
        assertEquals(RemoteAction.UPDATE_NOW, out.single().action)
        assertTrue(!out.single().delivered)
    }

    @Test
    fun `expired queued commands are hidden`() {
        val stale = command("old", issuedAtMs = now - SyncEngine.COMMAND_TTL_MS - 1)
        assertTrue(SyncEngine.pendingOps(listOf(stale), emptyList(), emptyList(), now).isEmpty())
    }

    @Test
    fun `newest operations come first`() {
        val out = SyncEngine.pendingOps(
            listOf(command("older", issuedAtMs = now - 60_000), command("newer", issuedAtMs = now)),
            emptyList(),
            emptyList(),
            now,
        )
        assertEquals(listOf(now, now - 60_000), out.map { it.sentAtMs })
    }

    // --- Installs awaiting completion ---

    @Test
    fun `an install acked opened stays pending until the app appears on the child`() {
        val out = SyncEngine.pendingOps(
            emptyList(), emptyList(), listOf(child(lastCommand = openedAck("com.a"))), now,
        )
        assertEquals(1, out.size)
        assertEquals("com.a", out.single().arg)
        assertTrue(out.single().delivered)
    }

    @Test
    fun `an install disappears once the package shows up in the child app list`() {
        val installed = child(
            apps = listOf(InstalledAppInfo("com.a", "A")),
            lastCommand = openedAck("com.a"),
        )
        assertTrue(SyncEngine.pendingOps(emptyList(), emptyList(), listOf(installed), now).isEmpty())
    }

    @Test
    fun `an install acked installed is not pending`() {
        val done = child(lastCommand = openedAck("com.a").copy(detail = RemoteAction.DETAIL_INSTALLED))
        assertTrue(SyncEngine.pendingOps(emptyList(), emptyList(), listOf(done), now).isEmpty())
    }

    @Test
    fun `re-pushing the same app shows one entry not two`() {
        val out = SyncEngine.pendingOps(
            listOf(command("retry", action = RemoteAction.INSTALL_APP, arg = "com.a")),
            emptyList(),
            listOf(child(lastCommand = openedAck("com.a"))),
            now,
        )
        assertEquals(1, out.size)
        assertTrue(!out.single().delivered) // the queued retry wins
    }

    @Test
    fun `a stale opened ack ages out of the list`() {
        val old = child(lastCommand = openedAck("com.a", completedAtMs = now - SyncEngine.COMMAND_TTL_MS - 1))
        assertTrue(SyncEngine.pendingOps(emptyList(), emptyList(), listOf(old), now).isEmpty())
    }

    // --- Location requests ---

    @Test
    fun `an unanswered location request is pending`() {
        val out = SyncEngine.pendingOps(
            emptyList(), listOf(LocationRequest("child-1", now - 60_000)), listOf(child()), now,
        )
        assertEquals(listOf(SyncEngine.ACTION_LOCATE), out.map { it.action })
    }

    @Test
    fun `an answered location request is not pending`() {
        val answered = child(answeredLocationRequestMs = now - 60_000)
        val out = SyncEngine.pendingOps(
            emptyList(), listOf(LocationRequest("child-1", now - 60_000)), listOf(answered), now,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a location request older than its TTL is moot`() {
        val stale = LocationRequest("child-1", now - SyncEngine.LOCATION_REQUEST_TTL_MS - 1)
        assertTrue(SyncEngine.pendingOps(emptyList(), listOf(stale), listOf(child()), now).isEmpty())
    }
}
