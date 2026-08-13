package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What survives an install window and what gets quarantined.
 *
 * These cases are the ones the old close-on-first-install path got wrong: a second app landing
 * behind the approved one, an app landing after the window shut, and an app that appeared with
 * no window at all.
 */
class InstallGuardTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a family that allows installs is never guarded`() {
        // The setting is off: installing is not a violation, and quarantining what the child
        // installs would be the worst bug this file could have.
        assertFalse(InstallGuard.guarding(installsBlocked = false, blanketWindowOpen = false))
        assertFalse(InstallGuard.guarding(installsBlocked = false, blanketWindowOpen = true))
    }

    @Test
    fun `a blanket window is the parent installing, not the child sneaking`() {
        // Entered their PIN, standing at the phone: every install in that window is theirs.
        assertFalse(InstallGuard.guarding(installsBlocked = true, blanketWindowOpen = true))
        assertTrue(InstallGuard.guarding(installsBlocked = true, blanketWindowOpen = false))
    }

    @Test
    fun `the open window's target is approved`() {
        assertEquals(
            setOf("com.game"),
            InstallGuard.approved("com.game", lastWindowPackage = "", lastWindowClosedAtMs = 0, nowMs = now),
        )
    }

    @Test
    fun `the approved app is still approved when it lands late`() {
        // Play commits the session on the tap and finishes whenever it finishes — and the first
        // install to land is what closes the window. Quarantining the approved app for being
        // slow would punish exactly the child who did as they were told.
        val closed = now - InstallGuard.LATE_LANDING_GRACE_MS + 1
        assertTrue("com.game" in InstallGuard.approved("", "com.game", closed, now))
    }

    @Test
    fun `approval does not last for ever`() {
        val closed = now - InstallGuard.LATE_LANDING_GRACE_MS - 1
        assertEquals(emptySet<String>(), InstallGuard.approved("", "com.game", closed, now))
    }

    @Test
    fun `a second app installed behind the approved one is not approved`() {
        val installed = setOf("com.launcher", "com.game", "com.sneaky")
        val baseline = setOf("com.launcher")
        val approved = InstallGuard.approved("com.game", "", 0, now)
        assertEquals(
            setOf("com.sneaky"),
            InstallGuard.fresh(installed, baseline, approved, quarantined = emptySet()),
        )
    }

    @Test
    fun `an app that appears with no window at all is fresh`() {
        // Installs are supposed to be blocked outright here, so this is the loudest case of the
        // lot: something installed anyway. It must not need a window to be noticed.
        assertEquals(
            setOf("com.sideloaded"),
            InstallGuard.fresh(
                installed = setOf("com.launcher", "com.sideloaded"),
                baseline = setOf("com.launcher"),
                approved = emptySet(),
                quarantined = emptySet(),
            ),
        )
    }

    @Test
    fun `an open case is not reported again`() {
        assertEquals(
            emptySet<String>(),
            InstallGuard.fresh(
                installed = setOf("com.launcher", "com.sneaky"),
                baseline = setOf("com.launcher"),
                approved = emptySet(),
                quarantined = setOf("com.sneaky"),
            ),
        )
    }

    @Test
    fun `a case closes when its app is gone`() {
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now))
        assertEquals(
            emptyList<UnauthorizedApp>(),
            InstallGuard.nextQuarantine(ledger, fresh = emptyList(), installed = setOf("com.launcher")),
        )
    }

    @Test
    fun `a case stays open while the app is still there`() {
        // The uninstall can be refused or interrupted; the entry is what makes the next pass
        // try again instead of forgetting, and what keeps the app suspended meanwhile.
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now, removalAttempts = 3))
        val next = InstallGuard.nextQuarantine(ledger, emptyList(), installed = setOf("com.sneaky"))
        assertEquals(1, next.size)
        assertEquals(3, next.first().removalAttempts)
    }

    @Test
    fun `a fresh case is not added twice`() {
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now))
        val next = InstallGuard.nextQuarantine(
            ledger,
            fresh = listOf(UnauthorizedApp("com.sneaky", atMs = now + 1)),
            installed = setOf("com.sneaky"),
        )
        assertEquals(1, next.size)
        assertEquals(now, next.first().atMs)
    }

    @Test
    fun `turning the install block off releases every open case`() {
        // The rule that created the case has been withdrawn. Holding the apps suspended under it
        // would be enforcing a setting the family turned off — and nothing would ever clear them,
        // because the only pass that closes a case is the one this state skips.
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now, suspended = true))
        assertEquals(
            emptyList<UnauthorizedApp>(),
            InstallGuard.retained(ledger, installed = setOf("com.sneaky"), installsBlocked = false),
        )
    }

    @Test
    fun `a case whose app is gone closes even on a pass that judges nobody`() {
        // The bug this exists for: a case left open for an app that is no longer installed is a
        // trap for the NEXT install of that same package — including the parent approving it
        // properly — which walks into a stale accusation and is suspended and removed.
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now))
        assertEquals(
            emptyList<UnauthorizedApp>(),
            InstallGuard.retained(ledger, installed = setOf("com.launcher"), installsBlocked = true),
        )
    }

    @Test
    fun `a live case survives a pass that judges nobody`() {
        // A blanket window (the parent standing at the phone) must not amnesty a case that is
        // still real: the app is still installed, and still not supposed to be.
        val ledger = listOf(UnauthorizedApp("com.sneaky", atMs = now, removalAttempts = 2))
        val kept = InstallGuard.retained(ledger, installed = setOf("com.sneaky"), installsBlocked = true)
        assertEquals(1, kept.size)
        assertEquals(2, kept.first().removalAttempts)
    }

    @Test
    fun `the ledger is capped, and says how much it dropped`() {
        val ledger = (1..InstallGuard.MAX_QUARANTINE).map { UnauthorizedApp("com.app$it", atMs = now) }
        val installed = ledger.map { it.pkg }.toSet() + "com.newest"
        val fresh = listOf(UnauthorizedApp("com.newest", atMs = now + 1))
        val next = InstallGuard.nextQuarantine(ledger, fresh, installed)
        assertEquals(InstallGuard.MAX_QUARANTINE, next.size)
        assertEquals("com.newest", next.last().pkg)
        assertEquals(1, InstallGuard.overflow(ledger, fresh, installed))
    }
}
