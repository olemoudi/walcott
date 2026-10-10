package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A phone this app keeps dying on is opened rather than frozen in its rules.
 *
 * A Device Owner's suspensions outlive the process that set them, so a build that died at start-up
 * left a child's phone shut for good — every way out ran in the process that was dying. The crash
 * breaker opens it after a few such starts. A real crash would need a build that crashes, so the
 * drill writes the same record an ordinary kill cannot (see ChildDevice.safeModeDrill).
 */
class SafeModeScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun leaveSafeMode() {
        // Whatever happened above: back to a clean breaker and a running app for the next scenario.
        runCatching { device.crashDrill(0) }
        runCatching { device.safeModeDrill(0) }
        Thread.sleep(2_000)
        runCatching { device.startWalcott() }
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    @Test
    fun `starts that keep dying open the phone, and the parent can put the rules back`() {
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        val pidBefore = device.walcottPid()
        device.safeModeDrill(3)
        awaitDevice("the process gone", timeoutMs = 30_000) { device.walcottPid() != pidBefore }
        device.startWalcott()

        awaitDevice("the phone opened by the breaker", timeoutMs = APPLY_TIMEOUT_MS) { !device.isSuspended(app) }
        val reported = childEventuallyReports { it.safeModeSinceMs > 0 }
        assertTrue(reported.safeModeSinceMs > 0)
        // And it stays open: nothing in safe mode may put a suspension back.
        assertDeviceNever("a suspension put back in safe mode", windowMs = 20_000) { device.isSuspended(app) }

        val commandId = parent.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY)
        parent.awaitAck(commandId)
        awaitDevice("the rules back in force once the parent re-applied them", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }
        childEventuallyReports { it.safeModeSinceMs == 0L }
    }

    @Test
    fun `a build that really crashes at every start opens the phone in minutes, not when Android next lets it run`() {
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        // Real crashes this time, three starts in a row. Left to Android, two inside a minute put
        // up "keeps stopping" and mark the process bad: no sticky restart, no alarms, no
        // broadcasts. Nothing here starts an activity or touches the dialog — that is what clears
        // the mark, and a child's phone in a drawer has nobody to do either.
        device.crashDrill(3)
        awaitDevice("the phone opened by the breaker", timeoutMs = CRASH_LOOP_TIMEOUT_MS) { !device.isSuspended(app) }
        childEventuallyReports { it.safeModeSinceMs > 0 }
    }

    @Test
    fun `two starts that died are not enough to open anything`() {
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        val pidBefore = device.walcottPid()
        device.safeModeDrill(2)
        awaitDevice("the process gone", timeoutMs = 30_000) { device.walcottPid() != pidBefore }
        device.startWalcott()

        assertDeviceNever("the phone opened after only two failed starts", windowMs = 30_000) {
            !device.isSuspended(app)
        }
    }

    private companion object {
        const val APPLY_TIMEOUT_MS = 90_000L

        /**
         * Three crashed starts and the one that opens the phone, each brought by an ordinary
         * restart: well under a minute. Left to Android's crash handling (0.119), the second crash
         * put "keeps stopping" on the screen for five minutes and then marked the process bad, and
         * the phone stayed shut past this whole window — measured, not assumed.
         */
        const val CRASH_LOOP_TIMEOUT_MS = 5 * 60_000L
    }
}
