package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {

    private fun child(id: String, version: Long) = ChildSnapshot(id, "phone", version, 20_000)

    @Test
    fun `mergeChild inserts a new device`() {
        val merged = SyncEngine.mergeChild(emptyMap(), child("a", 1))
        assertEquals(1L, merged.getValue("a").version)
    }

    @Test
    fun `mergeChild keeps the higher version and ignores stale ones`() {
        var state = SyncEngine.mergeChild(emptyMap(), child("a", 5))
        state = SyncEngine.mergeChild(state, child("a", 3)) // stale, ignored
        assertEquals(5L, state.getValue("a").version)
        state = SyncEngine.mergeChild(state, child("a", 6)) // newer, applied
        assertEquals(6L, state.getValue("a").version)
    }

    @Test
    fun `mergeChild tracks devices independently`() {
        var state = SyncEngine.mergeChild(emptyMap(), child("a", 1))
        state = SyncEngine.mergeChild(state, child("b", 1))
        assertEquals(setOf("a", "b"), state.keys)
    }

    @Test
    fun `mergeParent keeps the newest snapshot`() {
        val v1 = ParentSnapshot(1, "{}")
        val v2 = ParentSnapshot(2, "{}")
        assertEquals(2L, SyncEngine.mergeParent(null, v2).version)
        assertEquals(2L, SyncEngine.mergeParent(v2, v1).version) // stale ignored
        assertEquals(3L, SyncEngine.mergeParent(v2, ParentSnapshot(3, "{}")).version)
    }

    @Test
    fun `newResolutions returns only unapplied resolutions for pending requests`() {
        val parent = ParentSnapshot(
            version = 1,
            policyJson = "{}",
            resolutions = listOf(
                Resolution("r1", approved = true, grantedMinutes = 15, resolvedAtEpochMs = 1),
                Resolution("r2", approved = false, grantedMinutes = 0, resolvedAtEpochMs = 1),
                Resolution("r3", approved = true, grantedMinutes = 30, resolvedAtEpochMs = 1), // not pending here
            ),
        )
        val fresh = SyncEngine.newResolutions(parent, pendingRequestIds = setOf("r1", "r2"), alreadyApplied = setOf("r2"))
        assertEquals(listOf("r1"), fresh.map { it.requestId })
    }

    @Test
    fun `an empty parent map merge from null then a snapshot works`() {
        assertNull(null as ParentSnapshot?)
        assertTrue(SyncEngine.mergeParent(null, ParentSnapshot(1, "{}")).version == 1L)
    }
}
