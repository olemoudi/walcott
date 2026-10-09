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
            InstallGuard.approved(listOf(PendingPush("com.game")), lastWindowPackage = "", lastWindowClosedAtMs = 0, nowMs = now),
        )
    }

    @Test
    fun `the approved app is still approved when it lands late`() {
        // Play commits the session on the tap and finishes whenever it finishes — and the first
        // install to land is what closes the window. Quarantining the approved app for being
        // slow would punish exactly the child who did as they were told.
        val closed = now - InstallGuard.LATE_LANDING_GRACE_MS + 1
        assertTrue("com.game" in InstallGuard.approved(emptyList(), "com.game", closed, now))
    }

    @Test
    fun `approval does not last for ever`() {
        val closed = now - InstallGuard.LATE_LANDING_GRACE_MS - 1
        assertEquals(emptySet<String>(), InstallGuard.approved(emptyList(), "com.game", closed, now))
    }

    @Test
    fun `a second app installed behind the approved one is not approved`() {
        val installed = setOf("com.launcher", "com.game", "com.sneaky")
        val baseline = setOf("com.launcher")
        val approved = InstallGuard.approved(listOf(PendingPush("com.game")), "", 0, now)
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
    fun `a PIN window says it is one, whatever else is on file`() {
        // The bug: a pushed install nobody acted on stays pending for good, and the window a
        // parent opens with their PIN was read as that push's window — so the apps they stood
        // there installing were suspended and silently uninstalled.
        assertTrue(
            InstallGuard.blanketOpen(
                untilMs = now + 1, nowMs = now, blanket = true, maintenance = false,
                pushWindowOpen = true,
            ),
        )
    }

    @Test
    fun `a pushed install's own window forgives only its target`() {
        assertFalse(
            InstallGuard.blanketOpen(
                untilMs = now + 1, nowMs = now, blanket = false, maintenance = false,
                pushWindowOpen = true,
            ),
        )
    }

    @Test
    fun `a window opened before the flag existed is still read as blanket`() {
        // No push pending is the old inference, kept as the fallback: it is right whenever there
        // is nothing pending, and being wrong THIS way costs a window in which nothing is judged.
        assertTrue(
            InstallGuard.blanketOpen(
                untilMs = now + 1, nowMs = now, blanket = false, maintenance = false, pushWindowOpen = false,
            ),
        )
    }

    @Test
    fun `the nightly update hour is nobody's blanket`() {
        // Nobody is standing at the phone at four in the morning, so nothing in it is forgiven.
        assertFalse(
            InstallGuard.blanketOpen(
                untilMs = now + 1, nowMs = now, blanket = true, maintenance = true, pushWindowOpen = false,
            ),
        )
    }

    @Test
    fun `an expired window forgives nothing`() {
        assertFalse(
            InstallGuard.blanketOpen(
                untilMs = now, nowMs = now, blanket = true, maintenance = false, pushWindowOpen = false,
            ),
        )
    }

    @Test
    fun `the ledger is capped by holding the newest out, never by letting an open case go`() {
        // It used to keep the newest and drop the oldest case — and an app dropped from the ledger
        // is an app nothing keeps suspended any more: unapproved, and handed back to make room.
        val ledger = (1..InstallGuard.MAX_QUARANTINE).map { UnauthorizedApp("com.app$it", atMs = now) }
        val installed = ledger.map { it.pkg }.toSet() + "com.newest"
        val fresh = listOf(UnauthorizedApp("com.newest", atMs = now + 1))
        val next = InstallGuard.nextQuarantine(ledger, fresh, installed)
        assertEquals(ledger.map { it.pkg }, next.map { it.pkg })
        // Named rather than counted: the caller has to keep these OUT of the baseline of known
        // packages, or the cap turns a delayed case into an app that is never looked at again.
        assertEquals(listOf("com.newest"), InstallGuard.overflow(ledger, fresh, installed).map { it.pkg })
    }

    @Test
    fun `nothing is dropped while there is room, however the ledger got there`() {
        val installed = setOf("com.a", "com.b")
        assertEquals(
            emptyList<UnauthorizedApp>(),
            InstallGuard.overflow(
                listOf(UnauthorizedApp("com.a", atMs = now)),
                listOf(UnauthorizedApp("com.b", atMs = now)),
                installed,
            ),
        )
        // And an empty pass drops nothing rather than under-flowing the cap.
        assertEquals(emptyList<UnauthorizedApp>(), InstallGuard.overflow(emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun `a case that closes makes room for the one the cap was holding out`() {
        // The whole reason the overflow is named: the sixth app is not lost, it is waiting. When
        // one of the five disappears — removed at last — the next pass has a slot for it.
        val ledger = (1..InstallGuard.MAX_QUARANTINE).map { UnauthorizedApp("com.app$it", atMs = now) }
        val waiting = UnauthorizedApp("com.newest", atMs = now + 1)
        val stillInstalled = ledger.drop(1).map { it.pkg }.toSet() + waiting.pkg
        val next = InstallGuard.nextQuarantine(ledger, listOf(waiting), stillInstalled)
        assertTrue(next.any { it.pkg == waiting.pkg }, "the waiting app never got its slot")
        assertEquals(emptyList<UnauthorizedApp>(), InstallGuard.overflow(ledger, listOf(waiting), stillInstalled))
    }

    // --- Several approvals at once ---

    @Test
    fun `approving a second app keeps the first`() {
        // The bug: one slot, so B overwrote A — and A, installed a minute later, was suspended,
        // removed and reported to the parent as installed behind their back.
        val a = PendingPush("com.a", "cmd-a", "App A", approvedAtMs = now, windowUntilMs = now + 1)
        val b = PendingPush("com.b", "cmd-b", "App B", approvedAtMs = now, windowUntilMs = now + 1)
        val both = InstallGuard.withPush(InstallGuard.withPush(emptyList(), a), b)
        assertEquals(setOf("com.a", "com.b"), InstallGuard.approved(both, "", 0, now))
    }

    @Test
    fun `pushing the same app again retries it rather than stacking it`() {
        val first = PendingPush("com.a", "cmd-1", "App A", approvedAtMs = now - 1000, windowUntilMs = 0)
        // A tap reopens the window and knows no name; the name and the age stay the first one's.
        val again = InstallGuard.withPush(listOf(first), PendingPush("com.a", "", "", approvedAtMs = now, windowUntilMs = now + 5))
        assertEquals(1, again.size)
        assertEquals("App A", again.single().label)
        assertEquals("cmd-1", again.single().commandId)
        assertEquals(now - 1000, again.single().approvedAtMs)
        assertEquals(now + 5, again.single().windowUntilMs)
    }

    @Test
    fun `an app that lands consumes its own approval and nobody else's`() {
        val pushes = listOf(PendingPush("com.a"), PendingPush("com.b"))
        assertEquals(listOf(PendingPush("com.b")), InstallGuard.withoutPush(pushes, "com.a"))
        // An app nobody approved consumes nothing: it is the guard's to judge.
        assertEquals(pushes, InstallGuard.withoutPush(pushes, "com.sneaky"))
        assertFalse("com.sneaky" in InstallGuard.approved(pushes, "", 0, now))
    }

    @Test
    fun `closing the windows keeps the approvals`() {
        // "Re-apply protection" and "Close now" end what is open; they used to withdraw the
        // approval too, while the parent was trying to work out why it would not install.
        val pushes = listOf(PendingPush("com.a", windowUntilMs = now + 60_000))
        val closed = InstallGuard.closedWindows(pushes)
        assertEquals(0L, InstallGuard.windowDeadline(closed, now))
        assertEquals(setOf("com.a"), InstallGuard.approved(closed, "", 0, now))
    }

    @Test
    fun `the install window lasts as long as the last open push window`() {
        val pushes = listOf(
            PendingPush("com.a", windowUntilMs = now + 1_000),
            PendingPush("com.b", windowUntilMs = now + 5_000),
            PendingPush("com.c", windowUntilMs = 0),
        )
        assertEquals(now + 5_000, InstallGuard.windowDeadline(pushes, now))
        assertEquals(0L, InstallGuard.windowDeadline(pushes, now + 5_000))
    }

    @Test
    fun `an approval runs out with the command that carried it`() {
        val old = PendingPush("com.old", approvedAtMs = now - InstallGuard.PUSH_LIFETIME_MS - 1)
        val fresh = PendingPush("com.fresh", approvedAtMs = now - 1_000)
        assertEquals(listOf(fresh), InstallGuard.livePushes(listOf(old, fresh), now))
    }

    @Test
    fun `an approval whose window is open does not run out under it`() {
        val tapped = PendingPush("com.late", approvedAtMs = now - InstallGuard.PUSH_LIFETIME_MS - 1, windowUntilMs = now + 60_000)
        assertEquals(listOf(tapped), InstallGuard.livePushes(listOf(tapped), now))
        assertEquals(emptyList<PendingPush>(), InstallGuard.livePushes(listOf(tapped), now + 60_001))
    }

    @Test
    fun `too many approvals make room by forgetting the oldest`() {
        var pushes = emptyList<PendingPush>()
        for (i in 1..InstallGuard.MAX_PENDING_PUSHES + 1) pushes = InstallGuard.withPush(pushes, PendingPush("com.app$i"))
        assertEquals(InstallGuard.MAX_PENDING_PUSHES, pushes.size)
        assertFalse(pushes.any { it.pkg == "com.app1" })
    }

    @Test
    fun `the one approval an older build kept is still an approval`() {
        val migrated = InstallGuard.migratedPushes(emptyList(), "com.a", "cmd-a", "App A", now + 10, now)
        // Dated by its window, so reading it again later does not make it new again.
        assertEquals(listOf(PendingPush("com.a", "cmd-a", "App A", now + 10, now + 10)), migrated)
        // Folded in once: already there, it is not added twice.
        assertEquals(migrated, InstallGuard.migratedPushes(migrated, "com.a", "cmd-a", "App A", now + 10, now))
        assertEquals(emptyList<PendingPush>(), InstallGuard.migratedPushes(emptyList(), "", "", "", 0, now))
    }

    // --- Holding an app for the parent's answer ---

    @Test
    fun `in the watched mode a new case is held for an answer, not removed`() {
        val entries = listOf(UnauthorizedApp("com.new", atMs = now))
        val held = InstallGuard.heldUnder(entries, fresh = setOf("com.new"), guarded = true, nowMs = now)
        assertEquals(now + InstallGuard.HOLD_MS, held.single().holdUntilMs)
        assertFalse(InstallGuard.dueForRemoval(held.single(), now))
        // Unanswered for the whole hold, it goes.
        assertTrue(InstallGuard.dueForRemoval(held.single(), now + InstallGuard.HOLD_MS))
    }

    @Test
    fun `an existing hold keeps its own deadline`() {
        val entries = listOf(UnauthorizedApp("com.old", atMs = now - 1000, holdUntilMs = now + 5))
        assertEquals(entries, InstallGuard.heldUnder(entries, fresh = emptySet(), guarded = true, nowMs = now))
    }

    @Test
    fun `the strict mode holds nothing, including what the watched one was holding`() {
        val entries = listOf(
            UnauthorizedApp("com.new", atMs = now),
            UnauthorizedApp("com.held", atMs = now - 1000, holdUntilMs = now + InstallGuard.HOLD_MS),
        )
        val strict = InstallGuard.heldUnder(entries, fresh = setOf("com.new"), guarded = false, nowMs = now)
        assertTrue(strict.all { InstallGuard.dueForRemoval(it, now) })
    }

    @Test
    fun `a full ledger gives up its oldest held case to make room`() {
        // Five apps held for an answer must not leave a sixth usable for two days.
        val entries = (1..InstallGuard.MAX_QUARANTINE).map {
            UnauthorizedApp("com.app$it", atMs = now + it, holdUntilMs = now + InstallGuard.HOLD_MS)
        }
        val roomy = InstallGuard.makeRoom(entries, waiting = 1, nowMs = now)
        assertTrue(InstallGuard.dueForRemoval(roomy.first { it.pkg == "com.app1" }, now))
        assertEquals(1, roomy.count { InstallGuard.dueForRemoval(it, now) })
        assertEquals(entries, InstallGuard.makeRoom(entries, waiting = 0, nowMs = now))
    }

    // --- Archived apps (Android 15) ---

    @Test
    fun `apps already archived when the guard first sees them are not strangers`() {
        val known = setOf("com.kept")
        val seeded = InstallGuard.seedArchived(known, archived = setOf("com.archived"), openCases = emptySet())
        assertEquals(setOf("com.kept", "com.archived"), seeded)
        // So the pass that follows finds nothing new.
        assertEquals(emptySet<String>(), InstallGuard.fresh(seeded, seeded, emptySet(), emptySet()))
    }

    @Test
    fun `an open case is not absolved by being archived`() {
        val seeded = InstallGuard.seedArchived(emptySet(), archived = setOf("com.sneaky"), openCases = setOf("com.sneaky"))
        assertFalse("com.sneaky" in seeded)
    }
}
