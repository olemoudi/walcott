package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two parent phones on one family.
 *
 * Restoring a family's backup on a second phone is how a household tries to give a second parent
 * a phone, and until this was noticed it silently ended the first phone's ability to change a
 * rule for ever. These pin the rule that notices it (and, as much as anything, the cases that
 * must NOT be read as a takeover — a false positive tells a parent their own edits are worthless).
 */
class ParentTakeoverTest {

    private fun snapshot(version: Long, instance: String) =
        ParentSnapshot(version = version, policyJson = "{}", parentInstanceId = instance)

    @Test
    fun `a restore on another phone is a takeover`() {
        val incoming = snapshot(40 + SyncEngine.RESTORE_VERSION_LEAP, "other")
        assertTrue(SyncEngine.parentSuperseded(ownVersion = 40, ownInstanceId = "mine", incoming = incoming))
    }

    @Test
    fun `our own snapshot coming back off the relay is not a takeover`() {
        // Even at an absurd version: it is ours, and a parent that restored its own backup
        // hears exactly this a second later.
        val mine = snapshot(40 + SyncEngine.RESTORE_VERSION_LEAP * 3, "mine")
        assertFalse(SyncEngine.parentSuperseded(ownVersion = 40, ownInstanceId = "mine", incoming = mine))
    }

    @Test
    fun `our own older snapshots replayed out of the backlog are not a takeover`() {
        // The backlog replay after a reconnect carries snapshots published before this build
        // existed, so they have no instance id at all. Only the version rule saves us here.
        val legacy = snapshot(38, "")
        assertFalse(SyncEngine.parentSuperseded(ownVersion = 40, ownInstanceId = "mine", incoming = legacy))
    }

    @Test
    fun `an ordinary edit by another phone is not a takeover`() {
        // Nothing but a restore moves the counter by a leap, so anything smaller is noise,
        // a stale replay, or a bug — and none of those may silence a working parent.
        assertFalse(SyncEngine.parentSuperseded(40, "mine", snapshot(41, "other")))
        assertFalse(SyncEngine.parentSuperseded(40, "mine", snapshot(40 + SyncEngine.RESTORE_VERSION_LEAP - 1, "other")))
    }

    @Test
    fun `a scope that has never published under this build still detects a takeover`() {
        // ownInstanceId is blank until the first publish on a build that has the field.
        assertTrue(SyncEngine.parentSuperseded(40, "", snapshot(40 + SyncEngine.RESTORE_VERSION_LEAP, "other")))
    }

    @Test
    fun `taking it back outranks the phone that took it`() {
        val seen = 40 + SyncEngine.RESTORE_VERSION_LEAP
        val version = SyncEngine.takeoverVersion(ownVersion = 40, seenVersion = seen)
        assertEquals(seen + SyncEngine.RESTORE_VERSION_LEAP, version)
        // And the children adopt it: it is strictly newer than what they last applied.
        assertTrue(SyncEngine.adoptsPolicy(version, appliedVersion = seen, rotationAdopted = false))
    }

    @Test
    fun `the other phone sees the take-back as a takeover of its own`() {
        // Which is the point: two phones cannot silently disagree about who is in charge.
        val takenBack = SyncEngine.takeoverVersion(40, 40 + SyncEngine.RESTORE_VERSION_LEAP)
        val theirView = snapshot(takenBack, "mine")
        assertTrue(
            SyncEngine.parentSuperseded(
                ownVersion = 40 + SyncEngine.RESTORE_VERSION_LEAP,
                ownInstanceId = "other",
                incoming = theirView,
            ),
        )
    }
}
