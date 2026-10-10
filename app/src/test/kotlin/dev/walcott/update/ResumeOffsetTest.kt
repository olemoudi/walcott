package dev.walcott.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A download that was cut off continues from where it stopped (see [resumeOffset]) — which on a
 * child's weak mobile signal is the difference between an update arriving and the same first
 * megabytes being fetched forever.
 */
class ResumeOffsetTest {

    @Test
    fun `a partial answer that continues the file is appended`() {
        assertEquals(4_194_304L, resumeOffset(206, "bytes 4194304-9437183/9437184", have = 4_194_304L))
    }

    @Test
    fun `a whole answer starts over, whatever was asked`() {
        assertEquals(0L, resumeOffset(200, null, have = 4_194_304L))
    }

    @Test
    fun `a partial answer from anywhere else is refused, not stitched`() {
        assertNull(resumeOffset(206, "bytes 0-9437183/9437184", have = 4_194_304L))
        assertNull(resumeOffset(206, "bytes 5000000-9437183/9437184", have = 4_194_304L))
        assertNull(resumeOffset(206, null, have = 4_194_304L))
        assertNull(resumeOffset(206, "garbage", have = 4_194_304L))
    }

    @Test
    fun `an error is not a body`() {
        assertNull(resumeOffset(416, "bytes */9437184", have = 9_437_184L))
        assertNull(resumeOffset(404, null, have = 0L))
    }
}
