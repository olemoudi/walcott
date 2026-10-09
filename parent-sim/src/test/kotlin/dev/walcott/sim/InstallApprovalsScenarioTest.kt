package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The installs a family asks for most: approving apps one after another, and an app the child
 * installed from Play without asking.
 *
 * Each case here is one the product used to get wrong in a way that cost an app: a second approval
 * overwrote the first, which was then removed as unapproved; an unrelated app landing used up the
 * approval; "re-apply protection" withdrew it; and in the default "watched" mode an app installed
 * without asking was removed before the parent's "let it stay" could arrive.
 */
class InstallApprovalsScenarioTest : DeviceScenario() {

    private val first = Fixture.FIRST
    private val second = Fixture.SECOND
    private val installBlock = setOf("installs")

    private fun apk(fixture: Fixture): String {
        val url = requireNotNull(javaClass.classLoader.getResource(fixture.apk)) { "missing fixture ${fixture.apk}" }
        return File(url.toURI()).absolutePath
    }

    @BeforeEach
    fun startFromAnEmptyDevice() {
        device.ensureRemoved(first.pkg)
        device.ensureRemoved(second.pkg)
        device.allowInstallsFor(0)
        device.reconcileInstalls()
    }

    @AfterEach
    fun cleanUp() {
        runCatching { device.seedPolicy(PolicyJson.minimal()) }
        runCatching { device.allowInstallsFor(0) }
        runCatching { device.clearApprovals() }
        runCatching { device.ensureRemoved(first.pkg) }
        runCatching { device.ensureRemoved(second.pkg) }
    }

    private fun armBlock() {
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
    }

    @Test
    fun `two apps approved one after the other both stay installed`() {
        armBlock()
        device.openInstallWindow(first.pkg)
        device.openInstallWindow(second.pkg)
        awaitDevice("the windows lifted the block") { !device.installBlocked() }

        assertTrue(device.install(apk(first)).contains("Success"), "the first approved app should install")
        // The first one landing must not shut the window the second one is still waiting on.
        assertTrue(device.install(apk(second)).contains("Success"), "the second approved app should install")

        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 8_000) { s -> s.unauthorized.any { it.pkg == first.pkg || it.pkg == second.pkg } }
        assertTrue(device.isInstalled(first.pkg) && device.isInstalled(second.pkg), "an approved app was removed")
        assertFalse(device.isSuspended(first.pkg) || device.isSuspended(second.pkg), "an approved app was suspended")
        // Both used up, and the block back once nothing is waiting.
        awaitDevice("the block back once both landed") { device.installBlocked() }
    }

    @Test
    fun `an app nobody approved landing does not use up the approval`() {
        armBlock()
        device.openInstallWindow(first.pkg)
        awaitDevice("the window lifted the block") { !device.installBlocked() }

        assertTrue(device.install(apk(second)).contains("Success"), "the sneak-in should install")
        parent.awaitChild { s -> s.unauthorized.any { it.pkg == second.pkg } }

        assertTrue(
            device.install(apk(first)).contains("Success"),
            "the approved app could not install after something else landed first",
        )
        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 8_000) { s -> s.unauthorized.any { it.pkg == first.pkg } }
        assertFalse(device.isSuspended(first.pkg), "the approved app was suspended")
    }

    @Test
    fun `re-applying protection shuts the window and keeps the approval`() {
        armBlock()
        device.openInstallWindow(first.pkg)
        awaitDevice("the window lifted the block") { !device.installBlocked() }

        // What a parent presses while working out why the app will not install.
        parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY))
        awaitDevice("the block back after re-applying") { device.installBlocked() }

        // The child taps the card again: the approval is still there to reopen.
        device.reopenInstall(first.pkg)
        awaitDevice("the window reopened by the approval that survived") { !device.installBlocked() }
        assertTrue(device.install(apk(first)).contains("Success"))
        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 8_000) { s -> s.unauthorized.any { it.pkg == first.pkg } }
    }

    @Test
    fun `in the watched mode an app installed without asking waits for the parent, who lets it stay`() {
        parent.pushPolicy(
            PolicyJson.build(version = 2, restrictions = installBlock, extra = mapOf("installMode" to JsonPrimitive("guarded"))),
        )
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        // Watched, not blocked: Play — and adb — can install, and the guard judges what lands.
        assertTrue(device.install(apk(first)).contains("Success"), "the watched mode should not block installing")

        val reported = parent.awaitChild { s -> s.unauthorized.any { it.pkg == first.pkg } }
        val entry = reported.unauthorized.single { it.pkg == first.pkg }
        assertTrue(entry.holdUntilMs > System.currentTimeMillis(), "the case should be held for an answer: $entry")
        assertTrue(entry.suspended, "a held app is still paused")
        // Held, not removed: long enough for any removal to have happened.
        device.reconcileInstalls()
        Thread.sleep(20_000)
        assertTrue(device.isInstalled(first.pkg), "the held app was removed before the parent could answer")
        assertTrue(device.isSuspended(first.pkg), "the held app is usable while waiting")

        val ack = parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.ALLOW_APP, arg = first.pkg))
        assertTrue(ack.ok, "letting it stay should succeed: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_ALLOWED, ack.detail)
        awaitDevice("the allowed app given back") { !device.isSuspended(first.pkg) }
    }

    @Test
    fun `an app Android archived and brought back is not taken for a new one`() {
        // Play archives apps on its own when a phone runs short of space, and an archived app
        // drops out of the system's list of installed ones. It used to drop out of the guard's
        // baseline with it — and, brought back with a tap, be judged unapproved, suspended and
        // removed with the very data archiving keeps.
        assumeTrue(device.sdk() >= 35, "archiving is Android 15's")
        assertTrue(device.installAsPlay(apk(first)).contains("Success"))
        device.reconcileInstalls()
        parent.pushPolicy(
            PolicyJson.build(version = 2, restrictions = installBlock, extra = mapOf("installMode" to JsonPrimitive("guarded"))),
        )
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        device.reconcileInstalls()

        assertTrue(device.archive(first.pkg).contains("Success"), "this image cannot archive, so this proves nothing")
        device.reconcileInstalls()
        assertTrue(device.installAsPlay(apk(first)).contains("Success"))
        device.reconcileInstalls()

        parent.assertNoChild(windowMs = 8_000) { s -> s.unauthorized.any { it.pkg == first.pkg } }
        assertFalse(device.isSuspended(first.pkg), "an app the phone already had was suspended for coming back")
    }

    @Test
    fun `in the watched mode the parent's remove still removes`() {
        parent.pushPolicy(
            PolicyJson.build(version = 2, restrictions = installBlock, extra = mapOf("installMode" to JsonPrimitive("guarded"))),
        )
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        assertTrue(device.install(apk(first)).contains("Success"))
        parent.awaitChild { s -> s.unauthorized.any { it.pkg == first.pkg } }

        parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.UNINSTALL_APP, arg = first.pkg))
        awaitDevice("the app removed on the parent's word") { !device.isInstalled(first.pkg) }
        childReports { s -> s.unauthorized.none { it.pkg == first.pkg } }
    }
}
