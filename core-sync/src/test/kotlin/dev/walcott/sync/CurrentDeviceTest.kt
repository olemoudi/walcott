package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A child's phone, replaced.
 *
 * The supported way to enrol is a factory-reset phone, which is exactly the case where the app's
 * stored deviceId does not survive — so a replacement arrives as a second row for the same child,
 * and every screen that took the first match was reading the phone that is gone.
 */
class CurrentDeviceTest {

    private fun device(deviceId: String, childId: String, version: Long = 1) =
        ChildSnapshot(deviceId = deviceId, displayName = "phone", version = version, epochDay = 20_000, childId = childId)

    @Test
    fun `one phone per child is left alone`() {
        val rows = listOf(device("a", "ana"), device("b", "bruno"))
        assertEquals(rows, SyncEngine.currentDevices(rows, mapOf("a" to 10L, "b" to 20L)))
        assertTrue(SyncEngine.supersededDevices(rows, mapOf("a" to 10L, "b" to 20L)).isEmpty())
    }

    @Test
    fun `the replacement wins however the rows are ordered`() {
        val old = device("old", "ana")
        val new = device("new", "ana")
        val seen = mapOf("old" to 1_000L, "new" to 9_000L)
        assertEquals(listOf(new), SyncEngine.currentDevices(listOf(old, new), seen))
        // And the dead phone being FIRST in the list is the whole bug: order must not decide.
        assertEquals(listOf(new), SyncEngine.currentDevices(listOf(new, old), seen))
        assertEquals(listOf(old), SyncEngine.supersededDevices(listOf(old, new), seen))
    }

    @Test
    fun `a phone never heard from does not displace one that has been`() {
        val live = device("live", "ana")
        val ghost = device("ghost", "ana")
        val seen = mapOf("live" to 5_000L)
        assertEquals(listOf(live), SyncEngine.currentDevices(listOf(ghost, live), seen))
    }

    @Test
    fun `version breaks a tie`() {
        val a = device("a", "ana", version = 3)
        val b = device("b", "ana", version = 9)
        val seen = mapOf("a" to 7L, "b" to 7L)
        assertEquals(listOf(b), SyncEngine.currentDevices(listOf(a, b), seen))
    }

    @Test
    fun `devices registered to nobody are all kept`() {
        // Orphans belong to no child, so none of them is another one's replacement.
        val rows = listOf(device("x", ""), device("y", ""))
        assertEquals(rows, SyncEngine.currentDevices(rows, emptyMap()))
    }

    @Test
    fun `three phones for one child leave only the newest`() {
        val rows = listOf(device("1", "ana"), device("2", "ana"), device("3", "ana"))
        val seen = mapOf("1" to 1L, "2" to 2L, "3" to 3L)
        assertEquals(listOf("3"), SyncEngine.currentDevices(rows, seen).map { it.deviceId })
        assertEquals(listOf("2", "1"), SyncEngine.supersededDevices(rows, seen).map { it.deviceId })
    }
}
