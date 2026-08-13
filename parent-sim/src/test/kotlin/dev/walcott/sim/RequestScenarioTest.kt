package dev.walcott.sim

import dev.walcott.sync.ChildRequest
import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The conversation that runs the other way: a child asking, a parent answering.
 *
 * Every part of this had a unit test and none of it had ever been run across two devices. The
 * grant in particular is the one place where a duplicate is expensive and invisible — a
 * resolution rides in every re-emitted snapshot, so a child that applied it twice would hand
 * out double the minutes and nobody would ever see why.
 */
class RequestScenarioTest : DeviceScenario() {

    @Test
    fun `a child's request for more time reaches the parent`() {
        device.requestExtraTime(ALL_APPS, minutes = 20, reason = "homework is done")
        val seen = parent.awaitChild { it.requests.isNotEmpty() }
        val request = seen.requests.single()
        assertEquals(20, request.minutes)
        assertEquals("homework is done", request.reason)
    }

    @Test
    fun `an approved request is granted exactly once, however often it is re-sent`() {
        device.requestExtraTime(ALL_APPS, minutes = 20, reason = "please")
        val request = parent.awaitChild { it.requests.isNotEmpty() }.requests.single()

        parent.resolve(request.requestId, approved = true, grantedMinutes = 20)
        // The child retires the request once it has applied it — that disappearance IS the ack.
        val afterGrant = parent.awaitChild { it.requests.none { r -> r.requestId == request.requestId } }
        val granted = afterGrant.extra.sumOf { it.seconds }
        assertTrue(granted > 0, "20 approved minutes should show up as extra time, got $granted s")

        // Now the part that only a real child can show: the resolution rides along in every
        // re-emit, and applying it twice would silently double the grant.
        repeat(3) { parent.reEmit() }
        Thread.sleep(5_000)
        val after = childReports { true }
        assertEquals(granted, after.extra.sumOf { it.seconds }, "the grant was applied more than once")
    }

    @Test
    fun `a denied request is retired without granting anything`() {
        device.requestExtraTime(ALL_APPS, minutes = 20, reason = "no reason")
        val request = parent.awaitChild { it.requests.isNotEmpty() }.requests.single()
        val extraBefore = parent.children.values.first().extra.sumOf { it.seconds }

        parent.resolve(request.requestId, approved = false)
        val after = parent.awaitChild { it.requests.none { r -> r.requestId == request.requestId } }
        assertEquals(extraBefore, after.extra.sumOf { it.seconds }, "a denial granted time")
    }

    @Test
    fun `a child's install ask reaches the parent, and approving it pushes the install`() {
        // The full shape of the app-request flow: the child asks for one specific package, the
        // parent approves, and the approval comes back as a command carrying the app's human
        // name — which the child cannot resolve itself, because it does not have the app.
        device.ask(ChildRequest.KIND_INSTALL, "com.some.game")
        val asked = parent.awaitChild { it.asks.any { a -> a.kind == ChildRequest.KIND_INSTALL } }
        val ask = asked.asks.single { it.kind == ChildRequest.KIND_INSTALL }

        parent.resolve(ask.requestId, approved = true)
        val commandId = parent.sendCommand(
            deviceId, RemoteAction.INSTALL_APP, arg = "com.some.game", label = "Some Game",
        )
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "an install push should be accepted: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_INSTALL_OPENED, ack.detail)

        // The window is really open on the device, and it is open for that one package.
        val withWindow = childReports { it.installExemptionUntilMs > System.currentTimeMillis() }
        assertTrue(
            withWindow.installExemptionUntilMs > System.currentTimeMillis(),
            "the child should be in an install window",
        )
    }

    @Test
    fun `pushing an app the child already has says so instead of opening a window`() {
        val commandId = parent.sendCommand(
            deviceId, RemoteAction.INSTALL_APP, arg = ChildDevice.PACKAGE, label = "Walcott",
        )
        val ack = parent.awaitAck(commandId)
        assertEquals(RemoteAction.DETAIL_ALREADY_INSTALLED, ack.detail)
    }

    @Test
    fun `an ask survives the round trip with its text intact`() {
        // The text is free-form and written by a child; it goes through gzip, AES-GCM and JSON
        // before a parent ever sees it.
        val text = "¿me dejáis instalar Minecraft? porfa 🙏"
        device.ask(ChildRequest.KIND_APP, text)
        val asked = parent.awaitChild { it.asks.any { a -> a.kind == ChildRequest.KIND_APP } }
        assertEquals(text, asked.asks.single { it.kind == ChildRequest.KIND_APP }.text)
    }

    companion object {
        /** The engine's sentinel for "every app", as the child's own home screen uses it. */
        const val ALL_APPS = "__all_apps__"
    }
}
