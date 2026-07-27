package dev.walcott.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * version.json names the APK to download. The OS refuses to install anything not signed with
 * our key, so this is a second line — but "a JSON file can point a child's phone at any host"
 * is not a property worth having.
 */
class ApkSourceTest {

    @Test
    fun `the release asset is accepted`() {
        assertTrue(Updater.trustedApkUrl(dev.walcott.Distribution.CHILD_APK_URL))
        assertTrue(
            Updater.trustedApkUrl(
                "https://github.com/olemoudi/walcott/releases/download/v0.9.0/walcott-alpha.apk",
            ),
        )
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(Updater.trustedApkUrl("https://evil.example/walcott-alpha.apk"))
        assertFalse(Updater.trustedApkUrl("http://github.com/olemoudi/walcott/x.apk")) // not https
        assertFalse(Updater.trustedApkUrl("https://github.com/someone/walcott/x.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com.evil.example/olemoudi/walcott/x.apk"))
        assertFalse(Updater.trustedApkUrl(""))
    }
}
