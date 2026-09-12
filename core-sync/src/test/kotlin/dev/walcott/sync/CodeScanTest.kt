package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Setting a family up means scanning three different codes with two phones, and getting one of
 * them wrong is the ordinary mistake. These pin that each is recognised for what it is.
 */
class CodeScanTest {

    private val pairing = PairingPayload(
        topic = "t", familyKeyB64 = "k", parentPublicKeyB64 = "p",
        childId = "ana", childName = "Ana", familyName = "Pérez",
    )

    @Test
    fun `a pairing code is read, names and all`() {
        val scanned = CodeScan.classify(pairing.encode())
        assertTrue(scanned is ScannedCode.Pairing)
        assertEquals("Ana", (scanned as ScannedCode.Pairing).payload.childName)
    }

    @Test
    fun `surrounding whitespace does not hide a pairing code`() {
        assertTrue(CodeScan.classify("  ${pairing.encode()}\n") is ScannedCode.Pairing)
    }

    @Test
    fun `the enrollment code is named rather than refused`() {
        val json = """{"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":"dev.walcott/.X"}"""
        assertEquals(ScannedCode.EnrollmentCode, CodeScan.classify(json))
    }

    @Test
    fun `the download link is named rather than refused`() {
        assertEquals(
            ScannedCode.DownloadLink,
            CodeScan.classify("https://github.com/olemoudi/walcott/releases/latest/download/walcott.apk"),
        )
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(ScannedCode.Unknown, CodeScan.classify("https://example.com"))
        assertEquals(ScannedCode.Unknown, CodeScan.classify(""))
        assertEquals(ScannedCode.Unknown, CodeScan.classify("walcott1:not-base64!!"))
    }
}
