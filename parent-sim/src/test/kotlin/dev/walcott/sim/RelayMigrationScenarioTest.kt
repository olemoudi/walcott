package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A family moving from one relay to another, with a real child following it.
 *
 * This is the scenario the feature exists for and the only one that can prove it: a phone that
 * changes where it listens and gets it wrong is a phone nobody can reach again, and on a Device
 * Owner child that costs a factory reset. Nothing about that risk is visible in a unit test — the
 * child has its own stored server, its own socket, its own cursor, and it has to give up all three
 * for an address it has never used, after acknowledging on the one it is about to leave.
 *
 * Two relays run at once here, which is exactly the shape of a migration: the instruction and the
 * acknowledgement travel on the old one, everything afterwards on the new one.
 */
class RelayMigrationScenarioTest : DeviceScenario() {

    private lateinit var newRelay: MockRelay
    private var movedParent: ParentSim? = null

    @AfterEach
    fun stopNewRelay() {
        runCatching { movedParent?.stop() }
        runCatching { device.clearReverse(newRelay.port) }
        runCatching { newRelay.stop() }
    }

    @Test
    fun `the child follows the family to another relay and keeps obeying it`() {
        // Where the family is going. Reached over adb reverse like the first one, so neither
        // depends on the emulator's own network stack.
        newRelay = MockRelay().start()
        device.reversePort(newRelay.port)
        val destination = "http://127.0.0.1:${newRelay.port}"

        // A rule the OS can be asked about, so "it followed" is not just a message arriving.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
        awaitDevice("the install block armed") { device.installBlocked() }

        // The instruction goes out on the relay the child is listening to, and the child answers
        // there — before it moves. That ordering is the whole design: an acknowledgement sent
        // after the switch would arrive somewhere the parent is no longer waiting.
        val commandId = parent.sendCommand(deviceId, RemoteAction.SET_RELAY, arg = destination)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "the move should be accepted: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_RELAY_MOVED, ack.detail)

        // The same parent, now on the new relay. If the child did not really move, nothing ever
        // arrives here and this fails — which is the point.
        val moved = parent.sameFamilyOn(newRelay.localUrl, newRelay.loopbackUrl).start()
        movedParent = moved
        device.publish()
        val reported = moved.awaitChild(timeoutMs = 60_000) { it.childId == CHILD_ID }
        assertEquals(deviceId, reported.deviceId, "the same phone, on the other relay")

        // And it is still a child, not just a device that says hello: a rule pushed from the new
        // relay has to reach the OS.
        moved.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the install block lifted from the new relay") { !device.installBlocked() }
        moved.pushPolicy(PolicyJson.build(version = 4, restrictions = setOf("installs")))
        awaitDevice("and armed again from the new relay") { device.installBlocked() }
    }

    @Test
    fun `an address that is not a relay is refused rather than followed`() {
        // The failure that would strand a phone for good: it points itself at nothing and has no
        // way back. Refused on the child, and the refusal is reported.
        newRelay = MockRelay().start()
        val commandId = parent.sendCommand(deviceId, RemoteAction.SET_RELAY, arg = "not a relay at all")
        val ack = parent.awaitAck(commandId)
        assertEquals(false, ack.ok)
        assertEquals(RemoteAction.DETAIL_RELAY_REFUSED, ack.detail)

        // Still where it was, and still listening: the parent can still reach it.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
        awaitDevice("the child is still on the original relay") { device.installBlocked() }
    }
}
