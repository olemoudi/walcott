package dev.walcott.enforcement

import dev.walcott.rules.BlockReason
import dev.walcott.sync.ChildEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of the rules' decisions reach the parent's wall. The interesting assertions are the
 * silences: a wall nobody can read is worth less than no wall.
 */
class RuleEventsTest {

    @Test
    fun `an app running out of its own time is one line, naming it`() {
        assertEquals(
            listOf(ChildEvent.KIND_BUDGET_OUT to "com.game"),
            RuleEvents.kindsFor(previousDeviceBlock = null, deviceBlock = null, newlyBudgetBlocked = listOf("com.game")),
        )
    }

    @Test
    fun `entering bedtime is ONE line, not one per app`() {
        // Every managed app is blocked at that instant; reporting each would bury the day.
        val kinds = RuleEvents.kindsFor(
            previousDeviceBlock = null,
            deviceBlock = BlockReason.BEDTIME,
            newlyBudgetBlocked = listOf("com.game", "com.chat", "com.video"),
        )
        assertEquals(listOf(ChildEvent.KIND_BEDTIME to ""), kinds)
    }

    @Test
    fun `entering a screen-free window is one line too`() {
        assertEquals(
            listOf(ChildEvent.KIND_SCREEN_FREE to ""),
            RuleEvents.kindsFor(null, BlockReason.BLOCKED_WINDOW, emptyList()),
        )
    }

    @Test
    fun `staying inside bedtime says nothing more`() {
        // The tick after: still blocked, nothing new happened.
        assertTrue(
            RuleEvents.kindsFor(BlockReason.BEDTIME, BlockReason.BEDTIME, listOf("com.game")).isEmpty(),
        )
    }

    @Test
    fun `leaving bedtime says nothing — the wall is for what closed, not what reopened`() {
        assertTrue(RuleEvents.kindsFor(BlockReason.BEDTIME, null, emptyList()).isEmpty())
    }

    @Test
    fun `several apps running out at once are several lines, in a stable order`() {
        assertEquals(
            listOf(
                ChildEvent.KIND_BUDGET_OUT to "com.a",
                ChildEvent.KIND_BUDGET_OUT to "com.b",
            ),
            RuleEvents.kindsFor(null, null, listOf("com.b", "com.a")),
        )
    }

    @Test
    fun `nothing happening produces nothing`() {
        assertTrue(RuleEvents.kindsFor(null, null, emptyList()).isEmpty())
    }
}
