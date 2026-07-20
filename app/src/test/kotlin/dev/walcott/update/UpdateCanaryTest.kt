package dev.walcott.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure canary gate: a child follows the fleet only up to the parent's running build. */
class UpdateCanaryTest {

    @Test
    fun `waits while the target is newer than the parent's build`() {
        assertTrue(waitsForParent(targetVersionCode = 48, parentVersionCode = 47))
    }

    @Test
    fun `proceeds once the parent runs the target (or newer)`() {
        assertFalse(waitsForParent(targetVersionCode = 48, parentVersionCode = 48))
        assertFalse(waitsForParent(targetVersionCode = 48, parentVersionCode = 49))
    }

    @Test
    fun `a legacy parent that reports no build gates nothing`() {
        assertFalse(waitsForParent(targetVersionCode = 48, parentVersionCode = 0))
    }

    @Test
    fun `an older target never waits`() {
        assertFalse(waitsForParent(targetVersionCode = 46, parentVersionCode = 47))
    }
}
