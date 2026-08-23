package dev.walcott.data

import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.InstalledAppInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppCatalogTest {

    private fun snapshot(
        deviceId: String,
        childId: String,
        displayName: String,
        vararg apps: Pair<String, String>,
    ) = ChildSnapshot(
        deviceId = deviceId,
        displayName = displayName,
        version = 1,
        epochDay = 20_000,
        childId = childId,
        apps = apps.map { InstalledAppInfo(it.first, it.second) },
    )

    private val registry = mapOf("c1" to "Martina", "c2" to "Leo")

    @Test
    fun `an app preinstalled on one phone and installed on another counts as preinstalled`() {
        // The catalog is one row per package across the whole family, so it has to pick an
        // answer. It picks the one that offers the parent the switch: on the phone that shipped
        // with it, a limit does nothing without it — and on the other phone the switch is
        // simply ignored, which costs nobody anything.
        val rows = AppCatalog.build(
            listOf(
                ChildSnapshot(
                    deviceId = "d1", displayName = "Pixel", version = 1, epochDay = 20_000, childId = "c1",
                    apps = listOf(InstalledAppInfo("com.android.chrome", "Chrome", system = true)),
                ),
                ChildSnapshot(
                    deviceId = "d2", displayName = "Moto", version = 1, epochDay = 20_000, childId = "c2",
                    apps = listOf(InstalledAppInfo("com.android.chrome", "Chrome")),
                ),
            ),
            registry,
        )
        assertEquals(listOf(true), rows.map { it.system })
    }

    @Test
    fun `one phone calling an app a way to reach somebody is enough for the whole family`() {
        // The catalog is one row per package, so it has to pick an answer, and it picks the
        // protective one: an app that is the messaging app on ONE of the phones must not be
        // caught by a family-wide default because another phone does not use it that way.
        val rows = AppCatalog.build(
            listOf(
                ChildSnapshot(
                    deviceId = "d1", displayName = "Pixel", version = 1, epochDay = 20_000, childId = "c1",
                    apps = listOf(InstalledAppInfo("com.android.messaging", "Messages", reachOut = true)),
                ),
                ChildSnapshot(
                    deviceId = "d2", displayName = "Moto", version = 1, epochDay = 20_000, childId = "c2",
                    apps = listOf(InstalledAppInfo("com.android.messaging", "Messages")),
                ),
            ),
            registry,
        )
        assertEquals(listOf(true), rows.map { it.reachOut })
    }

    @Test
    fun `an app nobody flags stays what every older child means by it`() {
        val rows = AppCatalog.build(
            listOf(snapshot("d1", "c1", "Pixel", "com.game" to "Game")),
            registry,
        )
        assertEquals(listOf(false), rows.map { it.system })
        assertEquals(listOf(false), rows.map { it.reachOut })
    }

    @Test
    fun `an app on two children lists both as owners, alphabetically`() {
        val rows = AppCatalog.build(
            listOf(
                snapshot("d1", "c1", "Pixel", "com.game" to "Game"),
                snapshot("d2", "c2", "Moto", "com.game" to "Game"),
            ),
            registry,
        )
        assertEquals(listOf("Leo", "Martina"), rows.single().owners.map { it.name })
    }

    @Test
    fun `an app on one child tags only that child`() {
        val rows = AppCatalog.build(
            listOf(
                snapshot("d1", "c1", "Pixel", "com.game" to "Game"),
                snapshot("d2", "c2", "Moto", "com.other" to "Other"),
            ),
            registry,
        )
        assertEquals(listOf("Martina"), rows.first { it.packageName == "com.game" }.owners.map { it.name })
        assertEquals(listOf("Leo"), rows.first { it.packageName == "com.other" }.owners.map { it.name })
    }

    @Test
    fun `a legacy device without a registry entry falls back to its own name`() {
        val rows = AppCatalog.build(
            listOf(snapshot("d9", "", "Old phone", "com.game" to "Game")),
            registry,
        )
        val owner = rows.single().owners.single()
        assertEquals("Old phone", owner.name)
        assertEquals("d9", owner.id) // keyed by device, since there is no childId
    }

    @Test
    fun `a re-paired device with the same childId does not duplicate the tag`() {
        val rows = AppCatalog.build(
            listOf(
                snapshot("old-device", "c1", "Pixel", "com.game" to "Game"),
                snapshot("new-device", "c1", "Pixel 2", "com.game" to "Game"),
            ),
            registry,
        )
        assertEquals(listOf("Martina"), rows.single().owners.map { it.name })
    }

    @Test
    fun `entries sort by label case-insensitively`() {
        val rows = AppCatalog.build(
            listOf(snapshot("d1", "c1", "Pixel", "b.app" to "beta", "a.app" to "Alpha", "z.app" to "Zeta")),
            registry,
        )
        assertEquals(listOf("Alpha", "beta", "Zeta"), rows.map { it.label })
    }
}
