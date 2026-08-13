package dev.walcott.sim

import dev.walcott.sync.ChildEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wall: what the rules DID, reported by the child that did it.
 *
 * This is the longest chain in the product — a limit set on one phone, screen time counted on
 * another, an enforcement loop noticing the moment the budget runs out, an event recorded,
 * carried, and folded into the parent's activity feed. Every link had tests; the chain never
 * did, and the chain is the only thing a parent ever sees.
 *
 * A rule event is a TRANSITION, not a state, and that shapes every scenario here: the loop has
 * to observe the app allowed before it can observe it running out. Two policy changes landing
 * between the same pair of ticks look like one, and one that starts already blocked looks like
 * nothing happened — correct behaviour, and a trap for a test that does not wait.
 */
class RuleEventScenarioTest : DeviceScenario() {

    private lateinit var limitedApp: String

    @AfterEach
    fun removeFixture() {
        if (::limitedApp.isInitialized) runCatching { device.ensureRemoved(limitedApp) }
    }

    /**
     * Installs the fixture and settles it into a state the loop has SEEN as allowed, with a
     * budget it is comfortably inside. Returns the minutes of headroom left.
     *
     * Screen time accumulates in Room across scenarios, so the limit is computed from what the
     * device has actually counted rather than assumed to start at zero.
     */
    private fun settleAllowedWithHeadroom(which: Fixture = Fixture.FIRST, headroomMinutes: Int = 10): Int {
        limitedApp = installFixtureApp(which)
        val countedSeconds = childReports { true }.usage
            .firstOrNull { it.categoryId == limitedApp }?.seconds ?: 0
        val limit = (countedSeconds / 60).toInt() + headroomMinutes
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(limitedApp to limit)))
        childReports { it.appliedPolicyVersion >= parent.currentVersion() }
        // The loop must have a "before" to compare against, and only the device can say it has
        // one: the app is managed, and it is not blocked.
        awaitDevice("the app settled as allowed") { !device.isSuspended(limitedApp) }
        return headroomMinutes
    }

    @Test
    fun `running out of time on an app is reported to the parent`() {
        val headroom = settleAllowedWithHeadroom()
        device.addUsage(limitedApp to (headroom + 5) * 60L)

        val reported = childEventuallyReports { snapshot ->
            snapshot.ruleEvents.any { it.kind == ChildEvent.KIND_BUDGET_OUT && it.pkg == limitedApp }
        }
        val event = reported.ruleEvents.first { it.kind == ChildEvent.KIND_BUDGET_OUT && it.pkg == limitedApp }
        assertTrue(event.atMs > 0, "the wall orders by when it HAPPENED, so the child must stamp it")
        assertEquals(
            Fixture.FIRST.label,
            event.label,
            "the wall names the app, and only the device can resolve that name",
        )
        // And the rule was actually enforced, not merely announced.
        awaitDevice("the app blocked once its time ran out") { device.isSuspended(limitedApp) }
    }

    @Test
    fun `the same app running out does not report itself over and over`() {
        // The loop ticks every couple of seconds and the app stays over its budget all day. The
        // event is a transition; repeating it would bury the parent's wall in one app.
        // Its own fixture: screen time accumulates per package across scenarios, and a package
        // three tests deep carries a history that makes "comfortably inside its budget" a much
        // narrower thing to arrange.
        val headroom = settleAllowedWithHeadroom(Fixture.SECOND)
        device.addUsage(limitedApp to (headroom + 5) * 60L)
        childEventuallyReports { snapshot ->
            snapshot.ruleEvents.any { it.kind == ChildEvent.KIND_BUDGET_OUT && it.pkg == limitedApp }
        }

        Thread.sleep(12_000)
        val after = childReports { true }
        assertEquals(
            1,
            after.ruleEvents.count { it.kind == ChildEvent.KIND_BUDGET_OUT && it.pkg == limitedApp },
            "one app ran out once; the wall should say so once",
        )
    }

    @Test
    fun `an app with time left is not reported as out of it`() {
        val headroom = settleAllowedWithHeadroom()
        // Well inside the budget: a minute of use against several minutes of headroom.
        device.addUsage(limitedApp to 60L)

        Thread.sleep(8_000)
        val after = childReports { true }
        assertTrue(
            after.ruleEvents.none { it.kind == ChildEvent.KIND_BUDGET_OUT && it.pkg == limitedApp },
            "an app $headroom minutes inside its budget was reported as out of time",
        )
        assertTrue(!device.isSuspended(limitedApp), "an app with time left was blocked")
    }
}
