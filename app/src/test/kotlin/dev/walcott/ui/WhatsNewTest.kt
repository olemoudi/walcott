package dev.walcott.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WhatsNewTest {

    private val releases = listOf(
        WhatsNew.Release(90, "0.37.0-beta", 1),
        WhatsNew.Release(91, "0.38.0-beta", 2),
        WhatsNew.Release(92, "0.39.0-beta", 3),
    )

    @Test
    fun `only a parent phone is told what changed`() {
        // A supervised phone — a child's, or an adult's in assisted mode — did not choose the
        // update and could not have declined it. A modal explaining somebody else's decision,
        // in the way of the phone, is not something it should ever be handed.
        assertTrue(WhatsNew.isAnnouncedOn(dev.walcott.sync.DeviceMode.PARENT))
        assertFalse(WhatsNew.isAnnouncedOn(dev.walcott.sync.DeviceMode.CHILD))
        // And a phone that has not been told what it is yet says nothing either.
        assertFalse(WhatsNew.isAnnouncedOn(dev.walcott.sync.DeviceMode.UNSET))
    }

    @Test
    fun `a fresh install is told nothing`() {
        // No "before" to have changed from, and the whole changelog at first launch is noise.
        assertTrue(WhatsNew.entriesFor(0, 92, releases).isEmpty())
    }

    @Test
    fun `only releases newer than the one last seen, newest first`() {
        assertEquals(
            listOf(92, 91),
            WhatsNew.entriesFor(90, 92, releases).map { it.versionCode },
        )
        assertEquals(listOf(92), WhatsNew.entriesFor(91, 92, releases).map { it.versionCode })
    }

    @Test
    fun `nothing newer means nothing to show`() {
        assertTrue(WhatsNew.entriesFor(92, 92, releases).isEmpty())
    }

    @Test
    fun `a release ahead of this build is not announced by it`() {
        // Guards the case of listing the next version before bumping versionCode.
        assertEquals(listOf(91), WhatsNew.entriesFor(90, 91, releases).map { it.versionCode })
    }

    @Test
    fun `a downgrade announces nothing rather than going backwards`() {
        assertTrue(WhatsNew.entriesFor(92, 90, releases).isEmpty())
    }

    @Test
    fun `the shipped list is newest-first and reaches this build`() {
        val codes = WhatsNew.RELEASES.map { it.versionCode }
        assertEquals(codes.sortedDescending(), codes)
        assertEquals(codes.distinct(), codes)
        // A changelog entry for a version nobody can be running would never be shown.
        assertTrue(codes.all { it <= dev.walcott.BuildConfig.VERSION_CODE })
    }
}
