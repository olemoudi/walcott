package dev.walcott.sync

import dev.walcott.rules.AppPolicy
import dev.walcott.rules.FamilyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the parent's screens may say about a child's app list.
 *
 * A phone reports every app that has an icon, preinstalled ones included, because a parent
 * cannot ask to limit a browser they are never shown. But only some of those can be closed, and
 * a screen that forgets the difference counts a browser down to "Blocked" while the child goes
 * on browsing — the one failure this list must not have.
 */
class ManagedAppsWireTest {

    private val browser = InstalledAppInfo("com.android.chrome", "Chrome", system = true)
    private val video = InstalledAppInfo("com.google.android.youtube", "YouTube", system = true)
    private val game = InstalledAppInfo("com.game", "Game")

    private fun config(vararg managed: String) = FamilyConfig(
        version = 1,
        perAppPolicies = managed.associateWith { AppPolicy(manageSystemApp = true) },
    )

    @Test
    fun `an app the family installed is always in reach`() {
        assertEquals(listOf(game), listOf(game).managedUnder(config()))
    }

    @Test
    fun `a preinstalled app is only in reach once it was asked for`() {
        val all = listOf(browser, video, game)
        assertEquals(listOf(game), all.managedUnder(config()))
        assertEquals(listOf(browser, game), all.managedUnder(config(browser.packageName)))
    }

    @Test
    fun `a child too old to flag its apps is read exactly as it was before`() {
        // Older children send their non-system apps and no flag at all. Decoding that must give
        // back the same list, not an empty one: the parent's whole app screen hangs off it.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(
            InstalledAppInfo.serializer(),
            """{"packageName":"com.game","label":"Game"}""",
        )
        assertFalse(legacy.system)
        assertEquals(listOf(legacy), listOf(legacy).managedUnder(config()))
    }

    @Test
    fun `the flag survives the envelope a child actually sends`() {
        val familyKey = FamilyCrypto.generateFamilyKey()
        val parent = FamilyCrypto.generateSigningKeyPair()
        val snapshot = ChildSnapshot(
            deviceId = "d1",
            displayName = "phone",
            version = 1,
            epochDay = 20_000,
            apps = listOf(browser, game),
        )
        val decoded = SyncProtocol.decode(
            SyncProtocol.encodeChild(snapshot, familyKey), familyKey, parent.public,
        )
        assertEquals(
            listOf(browser, game),
            ((decoded as IncomingMessage.FromChild).snapshot).apps,
        )
    }

    @Test
    fun `only a child new enough is offered the promise`() {
        assertFalse(RemoteAction.canManageSystemApps(0))
        assertFalse(RemoteAction.canManageSystemApps(RemoteAction.MANAGE_SYSTEM_MIN_CHILD_VERSION - 1))
        assertTrue(RemoteAction.canManageSystemApps(RemoteAction.MANAGE_SYSTEM_MIN_CHILD_VERSION))
    }
}
