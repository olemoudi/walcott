package dev.walcott.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reading the APK we downloaded, before installing it. Only a device can answer this — the
 * platform is what parses the archive — and everything downstream trusts the answer: a file
 * that cannot be identified is discarded, and one that can is handed to the installer.
 *
 * The case that made this necessary is not exotic. A captive portal answers any request with
 * its own login page and a perfectly successful 200, so the "APK" that arrives is HTML; before
 * this, it went to PackageInstaller unopened and came back as an unreadable failure, every few
 * hours, for as long as the phone stayed on that Wi-Fi.
 *
 * Under its own file name, not [Updater.APK_FILE]: these tests run inside the live app's own
 * process, and the app's update checks delete that one the moment they find nothing to install.
 */
@RunWith(AndroidJUnit4::class)
class UpdaterDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val candidate = File(context.cacheDir, "updater-device-test.apk")
    private val updater by lazy { Updater(context) }

    @After
    fun tearDown() {
        candidate.delete()
    }

    @Test
    fun readsWhatARealApkSaysItIs() {
        // The app's own installed APK: a real one, whose identity we already know for certain.
        File(context.applicationInfo.sourceDir).copyTo(candidate, overwrite = true)
        val identity = updater.apkIdentity(candidate)
        assertEquals(context.packageName, identity?.packageName)
        assertEquals(dev.walcott.BuildConfig.VERSION_CODE, identity?.versionCode)
        assertEquals(dev.walcott.BuildConfig.VERSION_NAME, identity?.versionName)
    }

    @Test
    fun anythingThatIsNotAnApkIsRefused() {
        candidate.writeText("<html><body>Sign in to use this Wi-Fi network</body></html>")
        assertNull(updater.apkIdentity(candidate))
    }

    @Test
    fun aTruncatedDownloadIsRefused() {
        // The first megabyte of a real APK: a valid zip header, and nothing that follows it.
        val whole = File(context.applicationInfo.sourceDir).readBytes()
        candidate.writeBytes(whole.copyOfRange(0, minOf(1_000_000, whole.size)))
        assertNull(updater.apkIdentity(candidate))
    }

    @Test
    fun anEmptyOrMissingFileIsRefused() {
        candidate.delete()
        assertNull(updater.apkIdentity(candidate))
        candidate.writeBytes(ByteArray(0))
        assertNull(updater.apkIdentity(candidate))
    }
}
