package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the child reports as an enforcement gap. The list feeds the parent's health report, so
 * a name that lands here and never leaves is a permanent red mark on a device that is fine.
 */
class SuspendFailuresTest {

    private val installed = setOf("com.game", "com.chat", "com.video")
    private fun next(previous: List<String>, attempted: List<String>, failed: List<String>) =
        Enforcer.nextSuspendFailures(previous, attempted, failed) { it in installed }

    @Test
    fun `a package the OS refuses is reported`() {
        assertEquals(listOf("com.game"), next(emptyList(), listOf("com.game", "com.chat"), listOf("com.game")))
    }

    @Test
    fun `an uninstalled package is not a gap`() {
        // The OS refuses to suspend what isn't there, every cycle, forever — and the parent
        // would see a bare package name in red with no app behind it. It can't be used either.
        assertEquals(emptyList<String>(), next(emptyList(), listOf("com.gone"), listOf("com.gone")))
    }

    @Test
    fun `a package that suspends fine drops off the list`() {
        assertEquals(emptyList<String>(), next(listOf("com.game"), listOf("com.game"), emptyList()))
    }

    @Test
    fun `an untouched entry survives an attempt on other packages`() {
        assertEquals(listOf("com.game"), next(listOf("com.game"), listOf("com.chat"), emptyList()))
    }

    @Test
    fun `an app uninstalled after it failed stops being reported on the next attempt`() {
        // The path the parent actually hit: the name was recorded while installed, and only a
        // later attempt can tell that it's gone.
        assertEquals(emptyList<String>(), next(listOf("com.gone"), listOf("com.gone"), listOf("com.gone")))
    }

    @Test
    fun `the list stays bounded and free of duplicates`() {
        val many = (1..12).map { "com.game" }
        assertEquals(listOf("com.game"), next(emptyList(), many, many))
        val distinct = (1..12).map { "com.app$it" }
        assertEquals(8, Enforcer.nextSuspendFailures(emptyList(), distinct, distinct) { true }.size)
    }
}
