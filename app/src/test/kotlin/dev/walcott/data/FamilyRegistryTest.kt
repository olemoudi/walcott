package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FamilyRegistryTest {

    @Test
    fun `an empty registry is the single family every install already has`() {
        // The whole migration story: a device updating to a multi-family build must find its
        // existing family, in its existing files, without anything having been written first.
        val state = FamiliesState().normalized
        assertEquals(listOf(FamilyIds.DEFAULT), state.ids)
        assertEquals(FamilyIds.DEFAULT, state.active)
        assertFalse(state.isMulti)
    }

    @Test
    fun `the default family keeps the pre-multi-family file names`() {
        assertEquals("walcott_policy", WalcottDataStores.fileName("walcott_policy", FamilyIds.DEFAULT))
        assertEquals("walcott_sync_ab12", WalcottDataStores.fileName("walcott_sync", "ab12"))
    }

    @Test
    fun `adding a family makes it the one being shown`() {
        val state = FamiliesState().plus("second", 100)
        assertEquals(listOf(FamilyIds.DEFAULT, "second"), state.ids)
        assertEquals("second", state.active)
        assertTrue(state.isMulti)
    }

    @Test
    fun `adding the same id twice does not duplicate it`() {
        val state = FamiliesState().plus("second", 100).withActive(FamilyIds.DEFAULT).plus("second", 200)
        assertEquals(listOf(FamilyIds.DEFAULT, "second"), state.ids)
        assertEquals("second", state.active)
    }

    @Test
    fun `an id that could escape the datastore directory is refused`() {
        assertEquals(listOf(FamilyIds.DEFAULT), FamiliesState().plus("../evil", 0).ids)
        assertEquals(listOf(FamilyIds.DEFAULT), FamiliesState().plus("", 0).ids)
        assertTrue(FamilyIds.isValid(FamilyIds.newId()))
    }

    @Test
    fun `removing the shown family falls back to the first one left`() {
        val state = FamiliesState().plus("b", 1).plus("c", 2).minus("c")
        assertEquals(listOf(FamilyIds.DEFAULT, "b"), state.ids)
        assertEquals(FamilyIds.DEFAULT, state.active)
    }

    @Test
    fun `removing a family that is not being shown leaves the selection alone`() {
        val state = FamiliesState().plus("b", 1).minus(FamilyIds.DEFAULT)
        assertEquals(listOf("b"), state.ids)
        assertEquals("b", state.active)
    }

    @Test
    fun `the last family cannot be removed`() {
        // A parent with zero families has no home to show and no identity to publish.
        val state = FamiliesState().minus(FamilyIds.DEFAULT)
        assertEquals(listOf(FamilyIds.DEFAULT), state.ids)
    }

    @Test
    fun `a stored selection pointing at a family that is gone repairs itself`() {
        val state = FamiliesState(listOf(FamilyRef("b")), activeId = "vanished").normalized
        assertEquals("b", state.active)
    }

    @Test
    fun `switching to an unknown family is ignored`() {
        val state = FamiliesState().plus("b", 1).withActive("nope")
        assertEquals("b", state.active)
    }
}
