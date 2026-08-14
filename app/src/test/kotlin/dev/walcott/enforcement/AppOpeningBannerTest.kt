package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When coming back to an app is worth a word about what is left in it.
 *
 * Everything here is really one question: what separates "I am starting on this now" from "I
 * glanced at something else". Get it wrong in one direction and the child is told nothing when
 * it would have helped; get it wrong in the other and the phone interrupts every app switch,
 * which is how a banner becomes something people learn not to read.
 */
class AppOpeningBannerTest {

    private val now = 1_000_000L
    private val quiet = AppOpeningBanner.QUIET_MS

    @Test
    fun `the first time an app is opened, the child is told`() {
        // They have no idea what they have; this is the case the feature exists for.
        assertTrue(AppOpeningBanner().opened("com.game", now))
    }

    @Test
    fun `coming straight back says nothing`() {
        val banner = AppOpeningBanner()
        banner.opened("com.game", now)
        assertFalse(banner.opened("com.game", now + 1_000))
        assertFalse(banner.opened("com.game", now + quiet))
    }

    @Test
    fun `coming back after the quiet window is a new sitting`() {
        val banner = AppOpeningBanner()
        banner.opened("com.game", now)
        assertTrue(banner.opened("com.game", now + quiet + 1))
    }

    @Test
    fun `staying in an app keeps the window measured from last use, not from opening`() {
        // A child who plays for half an hour and then checks a message has not been away; the
        // window has to run from when they put it down.
        val banner = AppOpeningBanner()
        banner.opened("com.game", now)
        for (minute in 1..30) banner.stillOpen("com.game", now + minute * 60_000)
        val putDownAt = now + 30 * 60_000
        // A minute later is the same sitting, even though the app was first opened half an hour
        // ago: what counts is when it was last used, not when it was opened.
        val cameBackAt = putDownAt + 60_000
        assertFalse(banner.opened("com.game", cameBackAt))
        // And that return is itself a use, so the next window runs from IT.
        assertTrue(banner.opened("com.game", cameBackAt + quiet + 1))
    }

    @Test
    fun `each app has its own window`() {
        val banner = AppOpeningBanner()
        banner.opened("com.game", now)
        assertTrue(banner.opened("com.chat", now + 1_000))
        assertFalse(banner.opened("com.game", now + 2_000))
    }

    @Test
    fun `switching back and forth between two apps stays quiet`() {
        // The exact noise this must not make: alternating between two apps is one sitting, and
        // announcing on every switch is how a banner stops being read.
        val banner = AppOpeningBanner()
        assertTrue(banner.opened("com.game", now))
        assertTrue(banner.opened("com.chat", now + 1_000))
        repeat(10) { i ->
            val at = now + 2_000 + i * 2_000
            assertFalse(banner.opened(if (i % 2 == 0) "com.game" else "com.chat", at))
        }
    }

    @Test
    fun `a released device forgets what it had seen`() {
        val banner = AppOpeningBanner()
        banner.opened("com.game", now)
        banner.clear()
        assertTrue(banner.opened("com.game", now + 1_000))
    }
}
