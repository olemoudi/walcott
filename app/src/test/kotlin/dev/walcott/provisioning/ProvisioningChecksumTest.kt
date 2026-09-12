package dev.walcott.provisioning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The enrollment QR's certificate checksum.
 *
 * It broke once without anything noticing: the key was rotated, the checksum went on being read
 * from the current signer, and Android's provisioning compares the OLDEST (see
 * DeviceOwnerProvisioning.PUBLISHED_SIGNATURE_CHECKSUM). This pins the value to the original
 * release certificate — signer #1 of `apksigner lineage --in signing/walcott.lineage --print-certs`
 * — and the encoding provisioning expects: base64url, no padding.
 */
class ProvisioningChecksumTest {

    private val originalCertificateSha256 =
        "9e85b46c9c8dc0cb74d29d030b8e93a010ab53740de6de9f1efc03c59344d823"

    @Test
    fun `the QR names the original release certificate`() {
        val decoded = java.util.Base64.getUrlDecoder().decode(DeviceOwnerProvisioning.PUBLISHED_SIGNATURE_CHECKSUM)
        assertEquals(originalCertificateSha256, decoded.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `in the encoding provisioning reads`() {
        val value = DeviceOwnerProvisioning.PUBLISHED_SIGNATURE_CHECKSUM
        assertEquals(false, value.contains('='), "no padding")
        assertEquals(false, value.contains('+') || value.contains('/'), "url-safe alphabet")
        assertEquals(43, value.length, "a SHA-256 digest is 43 base64url characters")
    }
}
