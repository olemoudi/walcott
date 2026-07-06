package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinTest {

    @Test
    fun `verify succeeds with the correct PIN`() {
        val hashed = Pin.hash("1234")
        assertTrue(Pin.verify("1234", hashed.hash, hashed.salt))
    }

    @Test
    fun `verify fails with a wrong PIN`() {
        val hashed = Pin.hash("1234")
        assertFalse(Pin.verify("0000", hashed.hash, hashed.salt))
    }

    @Test
    fun `same PIN hashed twice yields different salts and hashes`() {
        val a = Pin.hash("1234")
        val b = Pin.hash("1234")
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
        // But both still verify.
        assertTrue(Pin.verify("1234", a.hash, a.salt))
        assertTrue(Pin.verify("1234", b.hash, b.salt))
    }

    @Test
    fun `a tampered hash does not verify`() {
        val hashed = Pin.hash("1234")
        val tampered = hashed.hash.dropLast(2) + "AA"
        assertFalse(Pin.verify("1234", tampered, hashed.salt))
    }
}
