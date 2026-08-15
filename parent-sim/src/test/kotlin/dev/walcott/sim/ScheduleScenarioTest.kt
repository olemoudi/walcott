package dev.walcott.sim

import dev.walcott.sync.ChildEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * The rules about WHEN: bedtime and the family's screen-free windows.
 *
 * The other half of the product's promise, and the half no scenario covered — every schedule
 * test lived in `:core-rules`, where a window is a comparison between two `LocalTime`s. What
 * only a device can show is that the parent's window travels, that the enforcement loop notices
 * the phone is inside it without anything else changing, and that the OS actually suspends the
 * app. A schedule that reaches the child and is never acted on looks exactly like a schedule
 * that works, from the parent's side, until the evening somebody checks.
 *
 * Windows are built around the device's own clock rather than fixed hours, and made wide, so a
 * scenario cannot fail because it ran at 23:58 or because the emulator's minute drifted.
 */
class ScheduleScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    /** A window [fromMinutes] before now to [toMinutes] after, as minutes since midnight. */
    private fun windowAround(fromMinutes: Long, toMinutes: Long): Pair<Int, Int> {
        val now = LocalTime.now()
        return now.plusMinutes(fromMinutes).let { it.hour * 60 + it.minute } to
            now.plusMinutes(toMinutes).let { it.hour * 60 + it.minute }
    }

    /**
     * Installs the fixture and settles it as allowed under a policy with no schedule, so what a
     * scenario measures afterwards is the schedule and not the state it started in.
     */
    private fun settleAllowed() {
        app = installFixtureApp()
        parent.pushPolicy(PolicyJson.build(version = 2))
        // `childEventuallyReports`, not `childReports`: installing the fixture makes the child
        // publish (a package arrived), which arms the publish throttle, and the publish that
        // would carry "policy applied" is then swallowed by it — the next spontaneous one is a
        // re-emit away. One forced publish can therefore be sent before the snapshot is applied
        // and win the race, leaving the scenario waiting out a fifteen-minute interval for
        // something that already happened. Asking repeatedly is the same question, asked until
        // the answer can arrive.
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app settled as allowed") { !device.isSuspended(app) }
        // And the loop has to have SEEN a tick with nothing closing the phone. A device-wide
        // block is reported as a TRANSITION (see RuleEvents.kindsFor): one that arrives while
        // the loop still remembers the previous scenario's window reads as "still inside one"
        // and is never reported at all — the scenario then waits out a minute for a line that
        // was correctly never written. The check above cannot stand in for this, because a
        // fixture installed a second ago is unsuspended before the loop has run even once.
        Thread.sleep(LOOP_SETTLE_MS)
    }

    @Test
    fun `a bedtime covering now closes the phone, and the child says so`() {
        settleAllowed()
        val (start, end) = windowAround(-60, 60)
        parent.pushPolicy(PolicyJson.build(version = 3, bedtime = start to end))

        awaitDevice("the app suspended by bedtime", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        val reported = childEventuallyReports { snapshot ->
            snapshot.ruleEvents.any { it.kind == ChildEvent.KIND_BEDTIME }
        }
        assertTrue(
            reported.ruleEvents.any { it.kind == ChildEvent.KIND_BEDTIME && it.atMs > 0 },
            "the wall orders by when it happened, so the child must stamp the bedtime it started",
        )
    }

    @Test
    fun `a bedtime somewhere else in the day leaves the phone alone`() {
        settleAllowed()
        // Two hours from now, an hour wide: set, delivered, and correctly doing nothing yet.
        val (start, end) = windowAround(120, 180)
        parent.pushPolicy(PolicyJson.build(version = 3, bedtime = start to end))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }

        // Long enough to contain an idle tick of the enforcement loop (15s), or the scenario
        // would be asserting only that nothing happened in the gap between two of them.
        assertDeviceNever("suspended outside its bedtime", windowMs = 20_000) { device.isSuspended(app) }
    }

    @Test
    fun `a screen-free window closes the phone while it lasts`() {
        settleAllowed()
        val (start, end) = windowAround(-30, 30)
        parent.pushPolicy(PolicyJson.build(version = 3, screenFree = listOf(start to end)))

        awaitDevice("the app suspended by a screen-free window", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        childEventuallyReports { snapshot ->
            snapshot.ruleEvents.any { it.kind == ChildEvent.KIND_SCREEN_FREE }
        }
    }

    @Test
    fun `moving the window off the current time gives the phone straight back`() {
        settleAllowed()
        val (start, end) = windowAround(-30, 30)
        parent.pushPolicy(PolicyJson.build(version = 3, screenFree = listOf(start to end)))
        awaitDevice("the app suspended by a screen-free window", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        // The parent changes their mind. Nothing else about the day has changed, so this is the
        // schedule alone deciding the phone is usable again.
        val (laterStart, laterEnd) = windowAround(120, 180)
        parent.pushPolicy(PolicyJson.build(version = 4, screenFree = listOf(laterStart to laterEnd)))
        awaitDevice("the app released when the window moved", timeoutMs = APPLY_TIMEOUT_MS) { !device.isSuspended(app) }
    }

    @Test
    fun `extra time granted during bedtime does not open anything`() {
        // The precedence the rules engine has always had — bedtime outranks every budget — as
        // the family experiences it. It is why the child's screen must not offer to ask for more
        // time while a window is closed: the parent can say yes, the minutes really are granted,
        // and the app stays shut. Both of them deserve to know that before they try.
        settleAllowed()
        val (start, end) = windowAround(-60, 60)
        parent.pushPolicy(PolicyJson.build(version = 3, bedtime = start to end))
        awaitDevice("the app suspended by bedtime", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        // A DELTA, not a total: extra time lives in Room and survives re-pairing, so this
        // fixture is still carrying whatever earlier runs granted it. The question here is
        // whether THIS hour arrived, and only the difference answers it.
        val before = childReports { true }.extra.firstOrNull { it.categoryId == app }?.seconds ?: 0
        device.requestExtraTime(app, minutes = 60, reason = "please")
        val asking = childEventuallyReports { it.requests.isNotEmpty() }
        val request = asking.requests.first()
        parent.resolve(request.requestId, approved = true, grantedMinutes = 60)

        // The grant lands — this is not a message that went missing…
        val granted = childEventuallyReports { snapshot ->
            snapshot.extra.any { it.categoryId == app && it.seconds >= before + 60 * 60L }
        }
        assertEquals(
            before + 60 * 60L,
            granted.extra.first { it.categoryId == app }.seconds,
            "the hour was granted, so the app being shut is the schedule and not a lost message",
        )
        // …and the app stays shut anyway, because no number of minutes ends a window.
        assertDeviceNever("opened by extra time during bedtime", windowMs = 20_000) { !device.isSuspended(app) }
    }

    private companion object {
        /** Comfortably past the enforcement loop's idle tick (15s), so it has run at least once. */
        const val LOOP_SETTLE_MS = 20_000L

        /**
         * How long a pushed schedule gets to travel and be acted on. Generous on purpose: it is
         * seconds when the machine is quiet, and the only thing a tight bound buys is a suite
         * that fails when something else is compiling — which is a fact about the laptop, not
         * about the product.
         */
        const val APPLY_TIMEOUT_MS = 60_000L
    }
}
