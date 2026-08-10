package dev.walcott.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The decode cache in [SettingsStore] is what stops the whole policy being parsed twice a second
 * by the enforcement loop. Its behaviour is a property of the JSON, not of Android, so the two
 * things that make it safe are checked here: that a re-parse of the same blob is identical (so
 * handing back one shared instance changes nothing), and that a DIFFERENT blob is not mistaken
 * for it (which would leave a child running yesterday's rules).
 */
class SettingsCacheTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = PolicySettings.serializer()

    private fun roundTrip(settings: PolicySettings): PolicySettings =
        json.decodeFromString(serializer, json.encodeToString(serializer, settings))

    @Test
    fun `decoding the same blob twice yields equal settings`() {
        val settings = PolicySettings(familyName = "Obiols")
        val raw = json.encodeToString(serializer, settings)
        assertEquals(
            json.decodeFromString(serializer, raw),
            json.decodeFromString(serializer, raw),
        )
    }

    @Test
    fun `settings are a value type, so one shared instance is safe to hand out`() {
        // The cache returns the SAME object to every caller; that is only sound because equality
        // is structural and nothing can mutate it underneath them.
        val settings = PolicySettings(familyName = "Obiols")
        assertEquals(settings, roundTrip(settings))
    }

    @Test
    fun `a changed policy produces a different blob, so the cache cannot match it`() {
        // The cache key is the raw string. An edit that did not change the string would be an
        // edit nobody could observe anyway; this pins that an ordinary one does change it.
        val before = json.encodeToString(serializer, PolicySettings(familyName = "Obiols"))
        val after = json.encodeToString(serializer, PolicySettings(familyName = "Moudi"))
        assertNotEquals(before, after)
        assertNotEquals(
            json.decodeFromString(serializer, before),
            json.decodeFromString(serializer, after),
        )
    }
}
