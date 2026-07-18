package dev.walcott.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayLinkTest {

    @Test
    fun `parses a full play web url`() {
        assertEquals(
            "com.spotify.music",
            PlayLink.parsePackage("https://play.google.com/store/apps/details?id=com.spotify.music"),
        )
    }

    @Test
    fun `parses a play url with trailing query params`() {
        assertEquals(
            "com.spotify.music",
            PlayLink.parsePackage("https://play.google.com/store/apps/details?id=com.spotify.music&hl=es&gl=ES"),
        )
    }

    @Test
    fun `strips a url fragment after the id`() {
        assertEquals(
            "com.duolingo",
            PlayLink.parsePackage("https://play.google.com/store/apps/details?id=com.duolingo#reviews"),
        )
    }

    @Test
    fun `parses a market uri`() {
        assertEquals("com.duolingo", PlayLink.parsePackage("market://details?id=com.duolingo"))
    }

    @Test
    fun `parses the label-plus-url text the share sheet actually sends`() {
        val shared = "Duolingo: language lessons\nhttps://play.google.com/store/apps/details?id=com.duolingo"
        assertEquals("com.duolingo", PlayLink.parsePackage(shared))
    }

    @Test
    fun `parses a bare package`() {
        assertEquals("com.whatsapp", PlayLink.parsePackage("com.whatsapp"))
    }

    @Test
    fun `rejects a short link that needs network expansion`() {
        assertNull(PlayLink.parsePackage("https://play.app.goo.gl/?link=abc123"))
    }

    @Test
    fun `rejects a non-play url`() {
        assertNull(PlayLink.parsePackage("https://example.com/store/apps/details?id=com.spotify.music"))
    }

    @Test
    fun `rejects free text with no package`() {
        assertNull(PlayLink.parsePackage("check out this cool app"))
        assertNull(PlayLink.parsePackage("nopackage"))
        assertNull(PlayLink.parsePackage(""))
        assertNull(PlayLink.parsePackage(null))
    }

    @Test
    fun `rejects a single-segment id that is not a real package`() {
        assertNull(PlayLink.parsePackage("market://details?id=android"))
    }
}
