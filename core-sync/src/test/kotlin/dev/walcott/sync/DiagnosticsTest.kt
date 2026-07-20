package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val parent = FamilyCrypto.generateSigningKeyPair()

    private fun payload(logLines: List<String> = emptyList()) = DiagPayload(
        deviceId = "dev-1",
        atMs = 1_720_000_000_000,
        enforcement = EnforcementStatus.DEVICE_OWNER,
        deviceOwner = true,
        usageAccess = true,
        gpsOn = true,
        networkLocationOn = false,
        locationPermission = true,
        batteryPercent = 42,
        charging = false,
        updateError = "download_failed",
        suspendFailures = listOf("com.game.one"),
        appVersionCode = 47,
        appVersionName = "0.9.1",
        logLines = logLines,
    )

    @Test
    fun `diag payload round-trips through its own envelope kind`() {
        val original = payload(logLines = listOf("10:00:00 I/Tag: hello", "10:00:01 W/Tag: warn"))
        val wire = SyncProtocol.encodeChildDiag(original, familyKey)
        val decoded = SyncProtocol.decode(wire, familyKey, parent.public)
        assertInstanceOf(IncomingMessage.FromChildDiag::class.java, decoded)
        assertEquals(original, (decoded as IncomingMessage.FromChildDiag).payload)
    }

    @Test
    fun `a small report is sent whole`() {
        val original = payload(logLines = List(10) { "line $it" })
        val wire = DiagFit.encode(original, familyKey)
        assertTrue(wire.length <= SnapshotFit.MAX_BYTES)
        val decoded = SyncProtocol.decode(wire, familyKey, parent.public) as IncomingMessage.FromChildDiag
        assertEquals(original.logLines, decoded.payload.logLines)
    }

    @Test
    fun `an oversized log is trimmed to fit, keeping the newest lines`() {
        // Random-ish content so gzip can't squash it below the cap.
        val lines = List(500) { i -> "12:00:${"%02d".format(i % 60)} E/WalcottSync: failure $i ${(i * 2654435761).toString(16)}" }
        val original = payload(logLines = lines)
        val wire = DiagFit.encode(original, familyKey)
        assertTrue(wire.length <= SnapshotFit.MAX_BYTES, "encoded ${wire.length} bytes")

        val decoded = SyncProtocol.decode(wire, familyKey, parent.public) as IncomingMessage.FromChildDiag
        val kept = decoded.payload.logLines
        assertTrue(kept.isNotEmpty(), "trimming must keep some log")
        assertTrue(kept.size < lines.size)
        // The tail (newest lines) survives; everything else about the report is intact.
        assertEquals(lines.takeLast(kept.size), kept)
        assertEquals(original.copy(logLines = kept), decoded.payload)
    }

    @Test
    fun `fixed fields alone always fit`() {
        val wire = DiagFit.encode(payload(), familyKey, maxBytes = 1500)
        assertTrue(wire.length <= 1500)
    }
}
