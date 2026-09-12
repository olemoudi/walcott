package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The reconciler asks the system only about packages whose wanted state moved. Skipping one
 * wrongly is an app that stays open past its limit, so the skip rule is pinned here.
 */
class SuspensionCheckTest {

    private val targets = setOf("game", "chat", "browser")

    @Test
    fun `a full sweep asks about everything`() {
        val lastLeft = mapOf("game" to true, "chat" to false, "browser" to false)
        assertEquals(targets, Enforcer.packagesToCheck(targets, setOf("game"), lastLeft, fullSweep = true))
    }

    @Test
    fun `nothing changed, nothing asked`() {
        val lastLeft = mapOf("game" to true, "chat" to false, "browser" to false)
        assertEquals(emptySet<String>(), Enforcer.packagesToCheck(targets, setOf("game"), lastLeft, fullSweep = false))
    }

    @Test
    fun `an app whose limit just ran out is asked about`() {
        val lastLeft = mapOf("game" to false, "chat" to false, "browser" to false)
        assertEquals(setOf("game"), Enforcer.packagesToCheck(targets, setOf("game"), lastLeft, fullSweep = false))
    }

    @Test
    fun `an app given back is asked about`() {
        val lastLeft = mapOf("game" to true, "chat" to false, "browser" to false)
        assertEquals(setOf("game"), Enforcer.packagesToCheck(targets, emptySet(), lastLeft, fullSweep = false))
    }

    @Test
    fun `an app never seen, or refused last time and so forgotten, is asked about`() {
        val lastLeft = mapOf("game" to true)
        assertEquals(setOf("chat", "browser"), Enforcer.packagesToCheck(targets, setOf("game"), lastLeft, fullSweep = false))
    }
}
