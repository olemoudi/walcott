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

    // --- parseLabel: the human title travelling with an install request ---

    @Test
    fun `label prefers the share subject`() {
        assertEquals(
            "Duolingo",
            PlayLink.parseLabel("Duolingo", "https://play.google.com/store/apps/details?id=com.duolingo"),
        )
    }

    @Test
    fun `label falls back to the first non-url line of the text`() {
        assertEquals(
            "Duolingo: language lessons",
            PlayLink.parseLabel(null, "Duolingo: language lessons\nhttps://play.google.com/store/apps/details?id=com.duolingo"),
        )
    }

    @Test
    fun `label skips a url subject and strips quotes`() {
        assertEquals(
            "Clash Royale",
            PlayLink.parseLabel(
                "https://play.google.com/store/apps/details?id=com.supercell.clashroyale",
                "“Clash Royale”\nhttps://play.google.com/store/apps/details?id=com.supercell.clashroyale",
            ),
        )
    }

    @Test
    fun `label is empty when the share carries nothing readable`() {
        assertEquals("", PlayLink.parseLabel(null, "https://play.google.com/store/apps/details?id=com.duolingo"))
        assertEquals("", PlayLink.parseLabel(null, "com.duolingo"))
        assertEquals("", PlayLink.parseLabel(null, null))
    }

    @Test
    fun `the exact sentence the play store sends is stripped, quotes and all`() {
        // Seen in the wild: the child's home read 'You can install Check out "Google Docs now'.
        val shared = "Check out \"Google Docs\"\n" +
            "https://play.google.com/store/apps/details?id=com.google.android.apps.docs.editors.docs"
        assertEquals("Google Docs", PlayLink.parseLabel(null, shared))
        assertEquals("Google Docs", PlayLink.parseLabel("Check out \"Google Docs\"", shared))
    }

    @Test
    fun `play's own sentence is stripped instead of becoming the request title`() {
        val url = "https://play.google.com/store/apps/details?id=com.duolingo"
        assertEquals("Duolingo", PlayLink.parseLabel(null, "Check out Duolingo\n$url"))
        assertEquals("Duolingo", PlayLink.parseLabel(null, "Check out this app: Duolingo\n$url"))
        assertEquals("Duolingo", PlayLink.parseLabel(null, "Echa un vistazo a Duolingo\n$url"))
        assertEquals("Duolingo", PlayLink.parseLabel(null, "Mira: Duolingo\n$url"))
    }

    @Test
    fun `boilerplate is stripped from the subject too, not only from the text`() {
        // The subject wins over the text, so leaving it uncleaned would have shown the sentence
        // on exactly the shares that set one.
        assertEquals(
            "Duolingo",
            PlayLink.parseLabel("Check out Duolingo", "Check out Duolingo\nhttps://play.google.com/store/apps/details?id=com.duolingo"),
        )
    }

    @Test
    fun `a subject that is nothing but boilerplate falls through to the text`() {
        assertEquals(
            "Duolingo",
            PlayLink.parseLabel("Check out", "Duolingo\nhttps://play.google.com/store/apps/details?id=com.duolingo"),
        )
    }

    @Test
    fun `the store's trailing shop line is dropped`() {
        assertEquals("Duolingo", PlayLink.parseLabel("Duolingo - Apps on Google Play", null))
        assertEquals("Duolingo", PlayLink.parseLabel("Duolingo – Aplicaciones en Google Play", null))
    }

    @Test
    fun `an app whose real name starts like a lead-in keeps it`() {
        // The single-word lead-ins only count with a separator behind them, so these survive.
        assertEquals("Mira Fitness", PlayLink.parseLabel("Mira Fitness", null))
        assertEquals("Try Guys", PlayLink.parseLabel("Try Guys", null))
        assertEquals("Descubre", PlayLink.parseLabel("Descubre", null))
    }

    @Test
    fun `an unrecognised phrasing is left exactly as it arrives`() {
        // The strip is cosmetic: a locale or wording we don't know must degrade to today's
        // behaviour, never to an empty or mangled title.
        assertEquals(
            "Schau dir Duolingo an",
            PlayLink.parseLabel("Schau dir Duolingo an", null),
        )
    }
}
