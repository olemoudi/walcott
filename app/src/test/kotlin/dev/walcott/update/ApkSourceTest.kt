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
        assertFalse(Updater.trustedApkUrl("not a url at all"))
        assertFalse(Updater.trustedApkUrl("ftp://github.com/olemoudi/walcott/x.apk"))
    }

    @Test
    fun `the url is judged as it will be requested, not as it was written`() {
        // The one that a startsWith over the raw text waved straight through: the request is
        // made from the PARSED url, and parsing resolves the dot-segments away — so this reads
        // as our repository and downloads from somebody else's.
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi/walcott/../../someone/evil.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi/walcott/../evil/x.apk"))
    }

    @Test
    fun `a repository whose name merely starts the same is refused`() {
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi/walcott-evil/x.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi-evil/walcott/x.apk"))
    }

    @Test
    fun `the host is matched case-insensitively, as the network does`() {
        assertTrue(Updater.trustedApkUrl("https://GitHub.COM/olemoudi/walcott/releases/x.apk"))
    }
}
