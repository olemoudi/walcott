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
    ) = nextUpdateStep(isNewer, parentIsBehind, hasStagedApk, trustedUrl, metered, enoughSpace, force)

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
}
