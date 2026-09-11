package dev.walcott.sim

import dev.walcott.sync.RescueCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Six digits that open a phone WITH THE RELAY IN PIECES.
 *
 * Every other scenario in this suite proves something about a message arriving. This one has to
 * prove the opposite: that the last door in this product does not depend on a channel, because
 * the situation it exists for is a phone nothing can reach — no data, a server having a bad day,
 * or a child failing closed with nothing left that opens.
 *
 * So the relay is stopped first, deliberately and before anything is asked of it, and everything
 * after that is a code read out loud and typed in by hand.
 */
class RescueScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    /** Types a code the way the child's dialog does, through the same call. */
    private fun type(code: String) = device.seed("--es", "rescue_code", code)

    @Test
    fun `a code read out loud opens a phone the family cannot reach`() {
        app = installFixtureApp(Fixture.STARTABLE)
        // Blocked outright, so "it opened" cannot be a budget quietly not having run out yet.
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        // And now there is no channel at all. Nothing below this line sends anything.
        relay.stop()

        type(parent.rescueCode(RescueCode.ACTION_OPEN_1H, deviceId))
        awaitDevice("the phone opened by a code, with the relay stopped", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(app)
        }
    }

    @Test
    fun `the same code will not open it twice`() {
        // The property that makes a code safe to say out loud in a room: overheard, photographed
        // or remembered, it is worth nothing a second time.
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        relay.stop()

        val code = parent.rescueCode(RescueCode.ACTION_OPEN_1H, deviceId)
        type(code)
        awaitDevice("the phone opened once", timeoutMs = APPLY_TIMEOUT_MS) { !device.isSuspended(app) }

        // Wind the grant back the way its own expiry would, then try the same digits again.
        device.seed("--es", "rescue_clear", "now")
        awaitDevice("the rule back in force once the grant was cleared", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }
        type(code)
        assertDeviceNever("a code that had already been used opened the phone a second time") {
            !device.isSuspended(app)
        }
    }

    @Test
    fun `a wrong code opens nothing`() {
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app shut by the rule", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        relay.stop()

        // Six digits that are not the ones. Deliberately derived from a real code so this cannot
        // pass by being the wrong LENGTH or otherwise rejected before it is even compared.
        val real = parent.rescueCode(RescueCode.ACTION_OPEN_1H, deviceId)
        val wrong = real.map { if (it == '0') '1' else '0' }.joinToString("")
        type(wrong)
        assertDeviceNever("a wrong code opened the phone") { !device.isSuspended(app) }
    }

    @Test
    fun `the parent hears about it once there is a channel again`() {
        // The rescue happens where nothing can be sent, so the record of it arrives late by
        // definition — but it does arrive, and the wall is where a parent looks a week later.
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(app to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }

        type(parent.rescueCode(RescueCode.ACTION_OPEN_1H, deviceId))
        val snapshot = childEventuallyReports { snap ->
            snap.ruleEvents.any { it.kind == dev.walcott.sync.ChildEvent.KIND_RESCUE }
        }
        assertTrue(
            snapshot.rescueUntilMs > System.currentTimeMillis(),
            "the phone should say how long the code opened it for: ${snapshot.rescueUntilMs}",
        )
    }

    private companion object {
        /**
         * The loop's own pace plus a policy landing. Longer than a reconcile because the grant
         * is read at the top of a tick and the suspension is applied at the bottom of one.
         */
        const val APPLY_TIMEOUT_MS = 90_000L
    }
}
