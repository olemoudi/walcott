package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * The pause a parent starts from their own phone, and tonight's bedtime moved out of the way.
 *
 * Both are ordinary rules once they reach the child — the engine's own tests cover what they
 * mean — so what only a device can show is the rest of the promise: that a per-MEMBER field
 * survives the wire and the resolution at the other end, that the OS really suspends on it, and
 * above all that it **ends by itself**. Nothing sends a second message to lift a pause: the
 * child has to notice the moment passing on its own clock and give the phone back. A pause that
 * arrives and never lifts is the worst failure this feature has, because the way out of it is a
 * parent who has already put their phone down.
 */
class PauseScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    /** The fixture installed and settled as allowed, so what follows is about the pause alone. */
    private fun settleAllowed() {
        app = installFixtureApp()
        parent.pushPolicy(PolicyJson.build(version = 2))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app settled as allowed") { !device.isSuspended(app) }
    }

    /** The policy that pauses THIS member until [untilMs], and nobody else. */
    private fun pausedPolicy(version: Long, untilMs: Long): String = PolicyJson.build(
        version = version,
        children = listOf(
            PolicyJson.childEntry(
                childId = CHILD_ID,
                name = CHILD_NAME,
                overrides = PolicyJson.todayException(pauseUntilMs = untilMs),
            ),
        ),
    )

    @Test
    fun `a pause closes the phone, and gives it back when it runs out`() {
        settleAllowed()
        // Short on purpose: the assertion that matters is the second one, and it is only an
        // assertion at all if the scenario outlives the pause.
        val until = System.currentTimeMillis() + PAUSE_MS
        parent.pushPolicy(pausedPolicy(version = 3, untilMs = until))

        awaitDevice("the app suspended by the pause", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }
        awaitDevice(
            "the app given back when the pause ran out, with nothing else sent",
            timeoutMs = PAUSE_MS + APPLY_TIMEOUT_MS,
        ) { !device.isSuspended(app) }
    }

    @Test
    fun `a parent can hand the phone back before the pause was due to end`() {
        settleAllowed()
        parent.pushPolicy(pausedPolicy(version = 3, untilMs = System.currentTimeMillis() + 10 * 60_000L))
        awaitDevice("the app suspended by the pause", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }

        // Ending it is the same field, cleared: the member's entry goes back to holding nothing.
        parent.pushPolicy(
            PolicyJson.build(
                version = 4,
                children = listOf(PolicyJson.childEntry(childId = CHILD_ID, name = CHILD_NAME)),
            ),
        )
        awaitDevice("the app given back as soon as the pause was lifted", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(app)
        }
    }

    @Test
    fun `tonight's bedtime can be lifted for one night`() {
        settleAllowed()
        // A bedtime that covers now, so the phone is shut by a rule rather than by a pause.
        val now = LocalTime.now()
        val start = now.minusMinutes(60).let { it.hour * 60 + it.minute }
        val end = now.plusMinutes(60).let { it.hour * 60 + it.minute }
        parent.pushPolicy(PolicyJson.build(version = 3, bedtime = start to end))
        awaitDevice("the app suspended by bedtime", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }

        // The night this member is in. Local to the harness and to the device alike — they are
        // the same machine's clock, which is what "tonight" means to both of them.
        val night = java.time.LocalDate.now()
            .let { if (start > end && now.hour * 60 + now.minute < end) it.minusDays(1) else it }
        parent.pushPolicy(
            PolicyJson.build(
                version = 4,
                bedtime = start to end,
                children = listOf(
                    PolicyJson.childEntry(
                        childId = CHILD_ID,
                        name = CHILD_NAME,
                        overrides = PolicyJson.todayException(
                            bedtimeNightEpochDay = night.toEpochDay(),
                            bedtimeOff = true,
                        ),
                    ),
                ),
            ),
        )
        awaitDevice("the app given back for the night", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(app)
        }
    }

    private companion object {
        /** How long the pause under test lasts. Long enough to be seen applied, short enough to wait out. */
        const val PAUSE_MS = 90_000L

        /** A publish, a check-in and a loop tick, with room for an emulator having a slow minute. */
        const val APPLY_TIMEOUT_MS = 60_000L
    }
}
