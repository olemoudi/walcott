package dev.walcott.enforcement

import dev.walcott.data.PolicySettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The only arithmetic in the ringer guard, and the one place it can fail silently.
 *
 * Ring streams are short — seven steps on most phones — so "80% of the maximum" is not a smooth
 * dial, and the rounding direction decides whether a floor is a floor. Rounded down, an 80% floor
 * is satisfied by 5 of 7 (71%); on a coarse stream a low enough percentage rounds to zero, which is
 * silence — the exact state this guard exists to undo.
 */
class AudioGuardTest {

    @Test
    fun `the floor rounds up, so a floor is never merely approached`() {
        // 7 steps, the common case. 80% of 7 is 5.6: five steps is below the floor.
        assertEquals(6, AudioGuard.floorFor(max = 7, minPercent = 80))
        // 15 steps: 12 exactly.
        assertEquals(12, AudioGuard.floorFor(max = 15, minPercent = 80))
    }

    @Test
    fun `a floor never rounds down to silence`() {
        // The failure that would turn the guard into the thing it guards against.
        assertTrue(AudioGuard.floorFor(max = 7, minPercent = 1) >= 1)
        assertTrue(AudioGuard.floorFor(max = 30, minPercent = 1) >= 1)
        assertEquals(1, AudioGuard.floorFor(max = 7, minPercent = 0), "0% is coerced, not obeyed")
    }

    @Test
    fun `a floor never exceeds what the stream can do`() {
        assertEquals(7, AudioGuard.floorFor(max = 7, minPercent = 100))
        assertEquals(7, AudioGuard.floorFor(max = 7, minPercent = 500), "an absurd percentage is clamped")
    }

    @Test
    fun `a device that will not say how loud it goes gets no floor at all`() {
        // Better than inventing one: raising a stream whose range is unknown is how an app pins a
        // phone at a volume its owner then goes hunting through Settings to undo.
        assertEquals(0, AudioGuard.floorFor(max = 0, minPercent = 80))
    }

    @Test
    fun `the shipped default is loud enough to hear and quiet enough to leave alone`() {
        // 80 rather than 100 is a product decision, and the reason is in the field's comment: a
        // phone pinned at maximum sounds broken enough that its owner reaches for the volume
        // control, which is the loop this feature exists to end.
        val default = PolicySettings.DEFAULT_RING_VOLUME_PERCENT
        assertTrue(default in 50..90, "the default ring floor drifted to $default")
        assertTrue(AudioGuard.floorFor(max = 7, minPercent = default) < 7, "the default should not pin the phone at max")
    }
}
