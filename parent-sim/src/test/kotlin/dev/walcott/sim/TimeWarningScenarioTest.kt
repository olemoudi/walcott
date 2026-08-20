package dev.walcott.sim

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The warnings a child gets before something closes: their time in an app running out, bedtime,
 * a screen-free window.
 *
 * These had no device coverage at all, and they are the part of this product a child meets most
 * often. Every one of them is decided in a loop that only runs on a phone, about a foreground app
 * only the OS can name, and delivered by a notification the platform is free to drop — so the
 * pure tests around `CloseWatch` and `TimeWarnings`, thorough as they are, could not say whether
 * a single warning had ever reached anybody.
 *
 * Asserted from the notification manager rather than from the app, and on the platform's own
 * `mIsInterruptive`: a warning that lands quietly in the shade is, inside a game, the same as no
 * warning at all — which is the entire reason this feature exists.
 */
class TimeWarningScenarioTest : DeviceScenario() {

    private lateinit var app: String

    /** What this scenario told the family the app may have per day. */
    private var limitMinutes = 0

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    @Test
    fun `an app close to its limit warns the child, over whatever is on screen`() {
        settleAllowed(headroomMinutes = 20)
        // Both rungs, in order, and that is not thoroughness — it is what makes this scenario
        // independent of the last one. A countdown is recognised by its DEADLINE, and for an
        // app's own time the deadline IS the minutes left, so a run that ended at four minutes
        // leaves the service believing it has already announced "four minutes left". Walking
        // down from twenty-five is a countdown it has not seen whatever ran before.
        val heardsUp = device.deviceNowMs()
        burnTo(minutesLeft = 25)
        awaitWarning("the half-hour heads-up", heardsUp) { it.title.contains("left in") }

        val since = device.deviceNowMs()
        // Down to four minutes: past the five-minute rung, so that is the rung it earns.
        burnTo(minutesLeft = 4)

        val warning = awaitWarning("the app's own time running out", since) { it.title.contains("left in") }
        assertTrue(
            warning.title.contains("4 minute") || warning.title.contains("5 minute"),
            "the warning should say what is actually LEFT, not the rung that fired it: ${warning.title}",
        )
        assertTrue(warning.title.contains(Fixture.STARTABLE.label), "the child must be told which app: ${warning.title}")
        assertShown(warning)
    }

    @Test
    fun `bedtime is announced once, however many things the child moves through`() {
        // The bug this pins. Bedtime is ONE event for the whole phone, but the countdown was
        // looked up per foreground app and remembered under that app's name — so leaving the app
        // for the home screen, or for anything else, earned the same bedtime a second warning,
        // stacked in the shade as a second notification. A child who checks two things before bed
        // was told twice, about the same twenty minutes.
        settleAllowed(headroomMinutes = 240)
        // Two rungs, like the budget scenario, and for the same reason: a countdown is recognised
        // by its DEADLINE, so two runs of this test that both put bedtime twenty minutes out
        // within five minutes of each other are ONE countdown as far as the phone is concerned,
        // and the second is correctly silent. Announcing the half hour first and then dropping to
        // the five-minute rung is something new to say whatever ran before.
        parent.pushPolicy(bedtimePolicy(minutesFromNow = 25))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        bringUp(app)
        // Long enough that the platform is willing to surface a banner again. Two rungs seconds
        // apart is not a case a child can be in — the real ones are minutes apart — and asking
        // for a second peek that soon is asking Android for something it throttles.
        Thread.sleep(RUNG_GAP_MS)

        val since = device.deviceNowMs()
        parent.pushPolicy(bedtimePolicy(minutesFromNow = 5))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        bringUp(app)

        val first = awaitWarning("bedtime, from inside the app", since) { it.title.contains("Bedtime in") }
        assertShown(first)

        // Out of the app and onto the home screen — the most ordinary thing a child does.
        device.home()
        Thread.sleep(QUIET_WINDOW_MS)
        val bedtimes = warningsSince(since).filter { it.title.contains("Bedtime in") }
        assertEquals(
            1,
            bedtimes.size,
            "one bedtime, one warning — the child was told again on leaving the app: " +
                bedtimes.map { "${it.id} ${it.title}" },
        )
    }

    @Test
    fun `nothing is said while the close is still beyond the horizon`() {
        // Half an hour is where this app starts speaking. Before that, "bedtime in an hour and a
        // half" is not news, and a phone that reports numbers nobody needed teaches its owner to
        // stop reading them — including on the night it mattered.
        settleAllowed(headroomMinutes = 240)
        parent.pushPolicy(bedtimePolicy(minutesFromNow = 90))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        val since = device.deviceNowMs()
        bringUp(app)

        Thread.sleep(QUIET_WINDOW_MS)
        assertEquals(
            emptyList<String>(),
            warningsSince(since).filter { it.title.contains("Bedtime in") }.map { it.title },
            "bedtime is an hour and a half away and the phone spoke anyway",
        )
    }

    /**
     * Installs the startable fixture, gives it a budget with [headroomMinutes] to spare, and
     * waits until the device has settled it as ALLOWED.
     *
     * The limit is computed from what this device has already counted rather than assumed to
     * start at zero: screen time lives in Room and outlives a re-pairing, so a fixture other
     * scenarios have used arrives with hours on it (same reasoning as RuleEventScenarioTest).
     */
    private fun settleAllowed(headroomMinutes: Int) {
        app = installFixtureApp(Fixture.STARTABLE)
        val counted = childReports { true }.usage.firstOrNull { it.categoryId == app }?.seconds ?: 0
        limitMinutes = (counted / 60).toInt() + headroomMinutes
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to limitMinutes)))
        childReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("$app settled as allowed") { !device.isSuspended(app) }
    }

    /** Spends everything but [minutesLeft] of what the app may use today, grants included. */
    private fun burnTo(minutesLeft: Int) {
        val snapshot = childReports { true }
        val counted = snapshot.usage.firstOrNull { it.categoryId == app }?.seconds ?: 0
        val granted = snapshot.extra.firstOrNull { it.categoryId == app }?.seconds ?: 0
        device.addUsage(app to (limitMinutes * 60L + granted - counted - minutesLeft * 60L).coerceAtLeast(0))
        bringUp(app)
    }

    /** A policy whose bedtime starts [minutesFromNow] from the DEVICE's clock. */
    private fun bedtimePolicy(minutesFromNow: Int): String {
        val start = (deviceMinuteOfDay() + minutesFromNow) % DAY_MINUTES
        return PolicyJson.build(
            version = parent.currentVersion() + 1,
            dailyMinutes = mapOf(app to limitMinutes),
            bedtime = start to (start + 480) % DAY_MINUTES,
            extra = mapOf("updateWindowEnabled" to JsonPrimitive(false)),
        )
    }

    /**
     * Minute of the day on the CHILD device.
     *
     * Read from the phone rather than from this machine: a bedtime is a local hour, and an
     * emulator in another timezone would put the window somewhere else in the day — at which
     * point the scenario is about what time it was.
     */
    private fun deviceMinuteOfDay(): Int {
        val parts = device.run("shell", "date", "+%H:%M").trim().split(":")
        fun n(at: Int) = parts[at].trimStart('0').ifEmpty { "0" }.toInt()
        return n(0) * 60 + n(1)
    }

    /** Puts the app on screen, and says what turned up instead when it does not get there. */
    private fun bringUp(pkg: String) {
        assertEquals(pkg, device.launchApp(pkg), "the fixture never reached the foreground")
    }

    /**
     * Asserts the platform actually PUT IT ON SCREEN, which is the only thing a warning is for.
     *
     * Two checks, because one of them is not enough. `mIsInterruptive` is the platform's record
     * that it alerted — and it was true for months of warnings that never appeared, because
     * `setSilent(true)` had filed them under the group "silent" with GROUP_ALERT_SUMMARY: with no
     * summary notification in that group to alert on their behalf, they were never allowed to
     * surface. So the group is checked too, and it is the check that would have caught it.
     */
    private fun assertShown(warning: ChildDevice.Posted) {
        assertTrue(
            warning.interruptive,
            "the platform did not alert for this warning: ${warning.title}",
        )
        assertTrue(
            !warning.groupKey.contains("silent"),
            "the warning was filed under the silent group, so it went to the shade without ever " +
                "appearing on screen — for a child inside a game that is the same as no warning: " +
                "${warning.title} (${warning.groupKey})",
        )
    }

    /** Warnings this phone posted at or after [since], by its own clock. */
    private fun warningsSince(since: Long): List<ChildDevice.Posted> =
        device.timeWarnings().filter { it.postedAtMs >= since }

    /** Waits for a warning matching [predicate], keeping the phone awake as the loop needs. */
    private fun awaitWarning(
        what: String,
        since: Long,
        timeoutMs: Long = 45_000,
        predicate: (ChildDevice.Posted) -> Boolean,
    ): ChildDevice.Posted {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            warningsSince(since).firstOrNull(predicate)?.let { return it }
            // The loop that decides this parks entirely while the screen is off.
            device.nudgeAwake()
            Thread.sleep(2_000)
        }
        throw AssertionError("no warning about $what within ${timeoutMs}ms; posted: ${warningsSince(since)}")
    }

    private companion object {
        /** Several ticks of the two-second enforcement loop, so a silence is a real silence. */
        const val QUIET_WINDOW_MS = 12_000L

        /** Space between two rungs, so the second is a banner rather than a throttled re-alert. */
        const val RUNG_GAP_MS = 30_000L
        const val DAY_MINUTES = 24 * 60
    }
}
