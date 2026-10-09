package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A command the parent took back stays taken back, however the relay replays what came before.
 *
 * The case this exists for: a phone is lost, the parent queues "free this phone", then decides it
 * was stolen and withdraws it. The relay still holds the first snapshot for twelve hours, and a
 * phone switched on inside them used to read it, run the release, and only then read the snapshot
 * that withdrew it — freed in a stranger's hands.
 */
class CommandReplayTest {

    private val release = RemoteCommand("r1", "phone", RemoteAction.RELEASE_DEVICE, issuedAtMs = 1_000)

    private fun snapshot(version: Long, instance: String = "A", vararg commands: RemoteCommand) =
        ParentSnapshot(version = version, policyJson = "{}", commands = commands.toList(), parentInstanceId = instance)

    @Test
    fun `a snapshot older than one already seen from the same phone runs nothing`() {
        assertFalse(SyncEngine.commandsLive(snapshotVersion = 7, seenVersion = 8))
    }

    @Test
    fun `a re-emit at the version already seen still runs, and so does a first sighting`() {
        // Re-emits reuse the version; refusing them would drop every command that rides on one.
        assertTrue(SyncEngine.commandsLive(snapshotVersion = 8, seenVersion = 8))
        assertTrue(SyncEngine.commandsLive(snapshotVersion = 9, seenVersion = 8))
        assertTrue(SyncEngine.commandsLive(snapshotVersion = 1, seenVersion = null))
    }

    @Test
    fun `each parent phone is judged against its own history`() {
        // A phone superseded by a restored backup sits a million below the other one for good,
        // and its "free this phone" must still work.
        var seen = SyncEngine.seenAfter(emptyMap(), "restored", 1_000_009, rotationAdopted = false)
        seen = SyncEngine.seenAfter(seen, "original", 12, rotationAdopted = false)
        assertTrue(SyncEngine.commandsLive(12, seen["original"]))
        assertFalse(SyncEngine.commandsLive(11, seen["original"]))
        assertTrue(SyncEngine.commandsLive(1_000_009, seen["restored"]))
    }

    @Test
    fun `the seen version only moves forward, except across a verified rotation`() {
        val seen = SyncEngine.seenAfter(mapOf("A" to 9L), "A", 7, rotationAdopted = false)
        assertEquals(9L, seen["A"])
        // A restored parent proving a new key starts a history of its own, possibly lower.
        val rebased = SyncEngine.seenAfter(mapOf("A" to 9L), "A", 3, rotationAdopted = true)
        assertEquals(3L, rebased["A"])
    }

    @Test
    fun `the ledger is bounded and forgets the phone furthest behind, never the newest`() {
        var seen = emptyMap<String, Long>()
        for ((i, phone) in listOf("p1", "p2", "p3", "p4").withIndex()) {
            seen = SyncEngine.seenAfter(seen, phone, 10L + i, rotationAdopted = false)
        }
        // The fifth arrives lowest of all; it is the one just written, so another goes instead.
        seen = SyncEngine.seenAfter(seen, "p5", 1, rotationAdopted = false)
        assertEquals(SyncEngine.MAX_SEEN_PARENTS, seen.size)
        assertEquals(1L, seen["p5"])
        assertFalse("p1" in seen)
    }

    @Test
    fun `another family's ledger is no baseline, and an unmarked one is this family's`() {
        val seen = mapOf("A" to 40L)
        assertTrue(SyncEngine.seenBaseline("old-topic", "new-topic", seen).isEmpty())
        assertEquals(seen, SyncEngine.seenBaseline("topic", "topic", seen))
        assertEquals(seen, SyncEngine.seenBaseline("", "topic", seen))
    }

    @Test
    fun `of a backlog only the newest snapshot per parent phone is kept, everything else in order`() {
        val p1 = snapshot(5, "A", release)
        val p2 = snapshot(6, "A") // the release withdrawn
        val other = snapshot(2, "B")
        val batch = listOf("child-1", p1, other, "child-2", p2)
        val kept = SyncEngine.newestParentsOf(batch) { it as? ParentSnapshot }
        assertEquals(listOf("child-1", other, "child-2", p2), kept)
    }

    @Test
    fun `of two copies at the same version the later one is kept`() {
        val first = snapshot(6, "A")
        val again = snapshot(6, "A", release)
        val kept = SyncEngine.newestParentsOf(listOf(first, again)) { it }
        assertEquals(listOf(again), kept)
    }

    @Test
    fun `a newest snapshot that arrived first in the backlog still wins`() {
        // The relay's order is the publish order, but nothing here should depend on it.
        val newer = snapshot(9, "A")
        val older = snapshot(4, "A", release)
        assertEquals(listOf(newer), SyncEngine.newestParentsOf(listOf(newer, older)) { it })
    }

    @Test
    fun `the withdrawn release is not run whichever way the backlog is handled`() {
        // Handled in full and in order, the gate alone stops it once the newer one is seen first;
        // handled as a batch, the older one is never even looked at.
        val p1 = snapshot(5, "A", release)
        val p2 = snapshot(6, "A")
        val seen = SyncEngine.seenAfter(emptyMap(), p2.parentInstanceId, p2.version, rotationAdopted = false)
        assertFalse(SyncEngine.commandsLive(p1.version, seen[p1.parentInstanceId]))
        assertEquals(listOf(p2), SyncEngine.newestParentsOf(listOf(p1, p2)) { it })
    }
}
