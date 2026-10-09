package dev.walcott.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The order in which one update check makes its decisions. This is the part of the updater
 * where bugs are invisible in review — every branch reads fine on its own, and only their
 * sequence decides whether a phone re-downloads fifty megabytes or installs what it already has.
 */
class UpdateStepTest {

    /** Everything fine, nothing staged, nothing in the way: fetch it. */
    private fun step(
        isNewer: Boolean = true,
        parentIsBehind: Boolean = false,
        hasStagedApk: Boolean = false,
        trustedUrl: Boolean = true,
        metered: Boolean = false,
        enoughSpace: Boolean = true,
        force: Boolean = false,
        backingOff: Boolean = false,
    ) = nextUpdateStep(isNewer, parentIsBehind, hasStagedApk, trustedUrl, metered, enoughSpace, force, backingOff)

    @Test
    fun `the happy path downloads`() {
        assertEquals(UpdateStep.DOWNLOAD, step())
    }

    @Test
    fun `nothing newer means nothing to do, whatever else is true`() {
        assertEquals(UpdateStep.NOTHING_TO_DO, step(isNewer = false))
        assertEquals(UpdateStep.NOTHING_TO_DO, step(isNewer = false, hasStagedApk = true, force = true))
    }

    @Test
    fun `a staged APK is installed instead of downloading again`() {
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true))
    }

    @Test
    fun `bytes already on disk beat every network gate below them`() {
        // The point of the whole staged-APK path: somebody cancelled the install prompt, and
        // the retry must not depend on Wi-Fi, on free space for a second copy, or on a url.
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true, metered = true))
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true, enoughSpace = false))
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true, trustedUrl = false))
    }

    @Test
    fun `the canary gate outranks a staged APK`() {
        // A child that already downloaded the build still must not run ahead of the parent.
        assertEquals(UpdateStep.WAIT_FOR_PARENT, step(parentIsBehind = true, hasStagedApk = true))
    }

    @Test
    fun `an untrusted url is refused before anything is fetched`() {
        assertEquals(UpdateStep.UNTRUSTED_URL, step(trustedUrl = false))
        // Even with room and Wi-Fi, and even when asked to force it: this one is not policy.
        assertEquals(UpdateStep.UNTRUSTED_URL, step(trustedUrl = false, force = true))
    }

    @Test
    fun `a metered connection holds the download, not the check`() {
        assertEquals(UpdateStep.WAIT_FOR_WIFI, step(metered = true))
    }

    @Test
    fun `force overrides the two gates that are policy and neither that is a fact`() {
        assertEquals(UpdateStep.DOWNLOAD, step(parentIsBehind = true, force = true))
        assertEquals(UpdateStep.DOWNLOAD, step(metered = true, force = true))
        assertEquals(UpdateStep.NEED_SPACE, step(enoughSpace = false, force = true))
        assertEquals(UpdateStep.UNTRUSTED_URL, step(trustedUrl = false, force = true))
    }

    @Test
    fun `a full disk is caught before the download rather than during it`() {
        assertEquals(UpdateStep.NEED_SPACE, step(enoughSpace = false))
    }

    @Test
    fun `a build that did not install is left alone for a while instead of downloaded again`() {
        assertEquals(UpdateStep.BACK_OFF, step(backingOff = true))
        // Bytes already on disk cost nothing to try again, and the parent's "Update now" is the
        // explicit "try anyway".
        assertEquals(UpdateStep.INSTALL_STAGED, step(backingOff = true, hasStagedApk = true))
        assertEquals(UpdateStep.DOWNLOAD, step(backingOff = true, force = true))
        // And the canary still outranks it.
        assertEquals(UpdateStep.WAIT_FOR_PARENT, step(backingOff = true, parentIsBehind = true))
    }

    @Test
    fun `the wait after a failed install grows, and belongs to that build alone`() {
        val now = 1_760_000_000_000L
        val once = InstallAttempts.NONE.plusAttempt(170, now)
        assertEquals(InstallAttempts(170, 1, now), once)
        assertEquals(true, once.backingOff(170, now + 59 * 60_000))
        assertEquals(false, once.backingOff(170, now + 61 * 60_000))
        // A newer release is a different build: it gets its own tries.
        assertEquals(false, once.backingOff(171, now + 1))
        val twice = once.plusAttempt(170, now)
        assertEquals(true, twice.backingOff(170, now + 3 * 60 * 60_000))
        val often = twice.plusAttempt(170, now).plusAttempt(170, now)
        assertEquals(true, often.backingOff(170, now + 23 * 60 * 60_000))
        assertEquals(InstallAttempts(171, 1, now), often.plusAttempt(171, now))
    }

    @Test
    fun `the attempts file round-trips, and anything unreadable is no attempts at all`() {
        val value = InstallAttempts(170, 2, 1_760_000_000_000L)
        assertEquals(value, InstallAttempts.parse(InstallAttempts.format(value)))
        assertEquals(InstallAttempts.NONE, InstallAttempts.parse(null))
        assertEquals(InstallAttempts.NONE, InstallAttempts.parse("170 x 1"))
        assertEquals(InstallAttempts.NONE, InstallAttempts.parse("170 -1 1"))
    }
}
