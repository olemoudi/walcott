package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a child does when the channel goes away and comes back.
 *
 * Every other scenario here runs on a working connection, which is the state a family's phones are
 * in for most of a day and none of the interesting ones. A phone that loses the relay — a dead
 * router, a captive portal, a relay that restarts — has to come back by ITSELF: nobody is going to
 * open the app on a child's phone to fix it, and from the parent's side a phone that never
 * reconnects looks exactly like one whose protection has been tampered with.
 *
 * The outage is a real one: the relay is stopped, which closes every socket it was holding, and
 * then brought back at the same address. Pulling the `adb reverse` mapping instead does NOT work
 * and is worth writing down — an established connection keeps flowing through it, so the first
 * version of this test cut nothing and proved nothing.
 *
 * Nothing here nudges the device. `awaitDevice` keeps the screen awake (the enforcement loop parks
 * when it is off) but never asks the child to publish or to reconnect; if the child does not heal
 * on its own, these fail.
 */
class OutageScenarioTest : DeviceScenario() {

    private var revived: MockRelay? = null
    private var revivedParent: ParentSim? = null

    @AfterEach
    fun stopRevived() {
        runCatching { revivedParent?.stop() }
        runCatching { revived?.stop() }
    }

    /**
     * Takes the relay away for [outageMs] and brings it back on the same port, with the same
     * family behind it. Answers the parent that is now reachable.
     */
    private fun outageAndRecovery(outageMs: Long = 15_000): ParentSim {
        // The address the CHILD holds. It never changes here — only what is behind it — because
        // a child being told where to go would be testing the migration, not the reconnect.
        val childPort = relay.port
        device.clearReverse(childPort)
        parent.stop()
        relay.stop()
        Thread.sleep(outageMs)

        // A new relay, on whatever port it likes, put back behind the same door.
        val back = MockRelay().start()
        revived = back
        device.reversePort(devicePort = childPort, hostPort = back.port)
        return parent.sameFamilyOn(back.localUrl, back.loopbackUrl).start().also { revivedParent = it }
    }

    @Test
    fun `a rule reaches the child again after the relay was gone`() {
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
        awaitDevice("the install block armed") { device.installBlocked() }

        val parentAgain = outageAndRecovery()

        // The child was never told anything about this outage and is not told anything now: its
        // own backoff has to notice the door is open again. The window is generous because being
        // slow here is correct (3 s, 6 s, 12 s… up to 5 min) and being stuck is not.
        parentAgain.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the child reconnected on its own", timeoutMs = 240_000) { !device.installBlocked() }
    }

    @Test
    fun `the child can be reached and answers again after the relay was gone`() {
        awaitDevice("the child is paired and enforcing") { device.isDeviceOwner() }

        val parentAgain = outageAndRecovery()

        // Both directions, without waiting out a re-emit: a command has to arrive AND be answered.
        val commandId = parentAgain.sendCommand(deviceId, RemoteAction.DIAGNOSE)
        val ack = parentAgain.awaitAck(commandId, timeoutMs = 240_000)
        assertTrue(ack.ok, "the child should answer once the relay is back: ${ack.detail}")
    }
}
