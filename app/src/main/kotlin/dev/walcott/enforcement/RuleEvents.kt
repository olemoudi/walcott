package dev.walcott.enforcement

import dev.walcott.rules.BlockReason
import dev.walcott.sync.ChildEvent

/**
 * Which of the rules' everyday decisions are worth a line on the parent's activity wall.
 *
 * Pure, because the interesting part is what is deliberately NOT reported. A device entering
 * bedtime blocks every app it manages at once; a wall that took one line per app would bury
 * the day it is meant to describe. So the two device-wide reasons collapse into a single line
 * each, and only a budget running out — which is genuinely about one app — is reported per app.
 */
object RuleEvents {

    /** (kind, package) for each line [ChildEvent] should carry; empty when nothing changed. */
    fun kindsFor(
        previousDeviceBlock: BlockReason?,
        deviceBlock: BlockReason?,
        newlyBudgetBlocked: Collection<String>,
    ): List<Pair<String, String>> = when {
        // Just entered bedtime or a screen-free window: one line, and no per-app lines — every
        // app went at the same instant and for the same reason.
        deviceBlock != null && previousDeviceBlock == null -> when (deviceBlock) {
            BlockReason.BEDTIME -> listOf(ChildEvent.KIND_BEDTIME to "")
            BlockReason.BLOCKED_WINDOW -> listOf(ChildEvent.KIND_SCREEN_FREE to "")
            else -> emptyList()
        }
        // Still inside one: anything blocked now was blocked by it, not by its own limit.
        deviceBlock != null -> emptyList()
        else -> newlyBudgetBlocked.sorted().map { ChildEvent.KIND_BUDGET_OUT to it }
    }
}
