package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteCommandTest {

    private val now = 1_700_000_000_000L

    private fun command(
        id: String,
        deviceId: String = "child-1",
        action: String = RemoteAction.UPDATE_NOW,
        issuedAtMs: Long = now,
    ) = RemoteCommand(id, deviceId, action, issuedAtMs)

    private fun parent(vararg commands: RemoteCommand) =
        ParentSnapshot(version = 1, policyJson = "{}", commands = commands.toList())

    // --- Child side: which commands to run ---

    @Test
    fun `picks up commands addressed to this device`() {
        val snapshot = parent(command("a"), command("b", deviceId = "child-2"))
        val out = SyncEngine.newCommands(snapshot, "child-1", emptySet())
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `skips commands already applied so a replay cannot run them twice`() {
        val snapshot = parent(command("a"), command("b", action = RemoteAction.REAPPLY_POLICY))
        val out = SyncEngine.newCommands(snapshot, "child-1", setOf("a"))
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun `applies queued commands oldest first`() {
        val snapshot = parent(
            command("newer", issuedAtMs = now),
            command("older", action = RemoteAction.REAPPLY_POLICY, issuedAtMs = now - 60_000),
        )
        assertEquals(listOf("older", "newer"), SyncEngine.newCommands(snapshot, "child-1", emptySet()).map { it.id })
    }

    @Test
    fun `returns nothing for a device with no commands`() {
        assertTrue(SyncEngine.newCommands(parent(command("a")), "unknown-device", emptySet()).isEmpty())
    }

    // --- Parent side: queueing ---

    @Test
    fun `queueing a command adds it to the pending list`() {
        val out = SyncEngine.withCommand(emptyList(), command("a"), now)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `re-issuing the same action for a device retries instead of stacking`() {
        val first = SyncEngine.withCommand(emptyList(), command("a"), now)
        val second = SyncEngine.withCommand(first, command("b", issuedAtMs = now + 1000), now + 1000)
        assertEquals(listOf("b"), second.map { it.id })
    }

    @Test
    fun `different actions for the same device coexist`() {
        val first = SyncEngine.withCommand(emptyList(), command("a"), now)
        val second = SyncEngine.withCommand(first, command("b", action = RemoteAction.REAPPLY_POLICY), now)
        assertEquals(setOf("a", "b"), second.map { it.id }.toSet())
    }

    @Test
    fun `pushing two different apps coexists but re-pushing the same app retries`() {
        val one = RemoteCommand("1", "child-1", RemoteAction.INSTALL_APP, now, arg = "com.a")
        val two = RemoteCommand("2", "child-1", RemoteAction.INSTALL_APP, now, arg = "com.b")
        val again = RemoteCommand("3", "child-1", RemoteAction.INSTALL_APP, now, arg = "com.a")

        val afterTwo = SyncEngine.withCommand(SyncEngine.withCommand(emptyList(), one, now), two, now)
        assertEquals(setOf("1", "2"), afterTwo.map { it.id }.toSet()) // different apps stay

        val afterAgain = SyncEngine.withCommand(afterTwo, again, now)
        // Re-pushing com.a replaces command 1, keeps com.b (2).
        assertEquals(setOf("2", "3"), afterAgain.map { it.id }.toSet())
    }

    @Test
    fun `the install package arg survives the wire`() {
        val key = FamilyCrypto.generateFamilyKey()
        val pair = FamilyCrypto.generateSigningKeyPair()
        val snapshot = ParentSnapshot(
            version = 1,
            policyJson = "{}",
            commands = listOf(RemoteCommand("x", "child-1", RemoteAction.INSTALL_APP, now, arg = "com.spotify.music")),
        )
        val decoded = SyncProtocol.decode(
            SyncProtocol.encodeParent(snapshot, key, pair.private), key, pair.public,
        ) as IncomingMessage.FromParent
        assertEquals("com.spotify.music", decoded.snapshot.commands.single().arg)
    }

    @Test
    fun `the same action for different devices coexists`() {
        val first = SyncEngine.withCommand(emptyList(), command("a", deviceId = "child-1"), now)
        val second = SyncEngine.withCommand(first, command("b", deviceId = "child-2"), now)
        assertEquals(setOf("a", "b"), second.map { it.id }.toSet())
    }

    @Test
    fun `expired commands are dropped so the snapshot cannot grow without bound`() {
        val stale = command("old", deviceId = "gone", issuedAtMs = now - SyncEngine.COMMAND_TTL_MS - 1)
        val out = SyncEngine.withCommand(listOf(stale), command("fresh"), now)
        assertEquals(listOf("fresh"), out.map { it.id })
    }

    @Test
    fun `commands inside the TTL are kept`() {
        val recent = command("recent", deviceId = "child-2", issuedAtMs = now - SyncEngine.COMMAND_TTL_MS + 60_000)
        val out = SyncEngine.withCommand(listOf(recent), command("fresh"), now)
        assertEquals(setOf("recent", "fresh"), out.map { it.id }.toSet())
    }

    // --- Wire compatibility ---

    @Test
    fun `commands and acks survive a round trip on the wire`() {
        val key = FamilyCrypto.generateFamilyKey()
        val keyPair = FamilyCrypto.generateSigningKeyPair()
        val snapshot = parent(command("a"), command("b", action = RemoteAction.REAPPLY_POLICY))

        val encoded = SyncProtocol.encodeParent(snapshot, key, keyPair.private)
        val decoded = SyncProtocol.decode(encoded, key, keyPair.public)

        val out = (decoded as IncomingMessage.FromParent).snapshot
        assertEquals(snapshot.commands, out.commands)
    }

    @Test
    fun `a child snapshot round-trips its command ack and update error`() {
        val key = FamilyCrypto.generateFamilyKey()
        val ack = CommandAck("a", RemoteAction.UPDATE_NOW, ok = true, detail = "installed", completedAtMs = now)
        val snapshot = ChildSnapshot(
            deviceId = "child-1",
            displayName = "Test",
            version = 1,
            epochDay = 20_000,
            lastCommand = ack,
            updateError = "download",
        )
        val decoded = SyncProtocol.decode(SyncProtocol.encodeChild(snapshot, key), key, keyPair().public)
        val out = (decoded as IncomingMessage.FromChild).snapshot
        assertEquals(ack, out.lastCommand)
        assertEquals("download", out.updateError)
    }

    @Test
    fun `a legacy parent snapshot without commands decodes with an empty list`() {
        val key = FamilyCrypto.generateFamilyKey()
        val pair = keyPair()
        // A snapshot produced by a build that predates remote commands.
        val legacy = ParentSnapshot(version = 1, policyJson = "{}")
        val decoded = SyncProtocol.decode(SyncProtocol.encodeParent(legacy, key, pair.private), key, pair.public)
        val out = (decoded as IncomingMessage.FromParent).snapshot
        assertTrue(out.commands.isEmpty())
        assertNull(ChildSnapshot(deviceId = "d", displayName = "n", version = 1, epochDay = 1).lastCommand)
    }

    private fun keyPair() = FamilyCrypto.generateSigningKeyPair()
}
