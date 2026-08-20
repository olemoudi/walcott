package dev.walcott.update

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What happens to the downloaded APK: whether it may be installed at all, and whether it
 * survives an install that didn't succeed. Both used to be assumed rather than asked.
 */
class StagedApkTest {

    private val ours = "dev.walcott"

    @Test
    fun `a newer build of this app is installable`() {
        assertTrue(apkIsInstallable(ours, apkVersionCode = 137, ourPackage = ours, installedVersionCode = 136))
    }

    @Test
    fun `a file that did not parse as an APK is not`() {
        // getPackageArchiveInfo returns null for anything that isn't one — a captive portal's
        // login page served with a 200, or a body cut short when the connection dropped.
        assertFalse(apkIsInstallable(null, apkVersionCode = 0, ourPackage = ours, installedVersionCode = 136))
    }

    @Test
    fun `another app is never installed under our name`() {
        assertFalse(apkIsInstallable("com.example.other", 999, ours, 136))
    }

    @Test
    fun `the build already running, or an older one, is not installable`() {
        // The leftovers of the update that just succeeded, and the stale asset of a release
        // whose version.json promised more than it published. Retrying either forever is how
        // a device gets stuck re-downloading the same fifty megabytes.
        assertFalse(apkIsInstallable(ours, 136, ours, 136))
        assertFalse(apkIsInstallable(ours, 135, ours, 136))
    }

    @Test
    fun `a build newer than promised is still progress`() {
        // The release moved on mid-download. Refusing what arrived would strand the device on
        // the older build for no reason: it is ours, and it is newer.
        assertTrue(apkIsInstallable(ours, 138, ours, 136))
    }

    @Test
    fun `a declined or blocked install keeps the APK`() {
        // Someone tapped "Cancel", by reflex as often as on purpose. The bytes are fine, so the
        // way back must not cost another download.
        assertTrue(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_BLOCKED))
    }

    @Test
    fun `a real failure discards it`() {
        assertFalse(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE))
        assertFalse(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_INVALID))
        assertFalse(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertFalse(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertFalse(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
    }
}
