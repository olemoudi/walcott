package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How the child's account of its own rules becomes a line on the parent's wall. */
class ChildEventFoldTest {

    private fun childEvent(kind: String, pkg: String = "", label: String = "") =
        ChildEvent(id = "e1", atMs = 1_700_000_000_000, kind = kind, pkg = pkg, label = label)

    @Test
    fun `an app running out keeps the name the child gave it`() {
        // The parent may never have heard of the package: an app can be installed, used and
        // exhausted between two publishes of the child's app list.
        val entry = ParentEvent.fromChildEvent(
            childEvent(ChildEvent.KIND_BUDGET_OUT, pkg = "com.game", label = "Roblox"),
            childId = "c1",
            childName = "Ana",
        )
        assertEquals(ParentEvent.TYPE_APP_TIME_OUT, entry?.type)
        assertEquals("Roblox", entry?.detail)
        assertEquals("c1", entry?.childId)
        // The id travels unchanged: it is what makes folding the same snapshot twice a no-op.
        assertEquals("e1", entry?.id)
        assertEquals(1_700_000_000_000, entry?.atMs)
    }

    @Test
    fun `an app with no label falls back to its package rather than an empty line`() {
        val entry = ParentEvent.fromChildEvent(
            childEvent(ChildEvent.KIND_BUDGET_OUT, pkg = "com.game"),
            "c1",
            "Ana",
        )
        assertEquals("com.game", entry?.detail)
    }

    @Test
    fun `bedtime and screen-free map to their own lines`() {
        assertEquals(
            ParentEvent.TYPE_BEDTIME,
            ParentEvent.fromChildEvent(childEvent(ChildEvent.KIND_BEDTIME), "c1", "Ana")?.type,
        )
        assertEquals(
            ParentEvent.TYPE_SCREEN_FREE,
            ParentEvent.fromChildEvent(childEvent(ChildEvent.KIND_SCREEN_FREE), "c1", "Ana")?.type,
        )
    }

    @Test
    fun `a kind from a newer child is skipped, not shown blank`() {
        assertNull(ParentEvent.fromChildEvent(childEvent("something_new"), "c1", "Ana"))
    }
}
