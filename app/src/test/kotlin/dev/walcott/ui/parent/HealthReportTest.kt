package dev.walcott.ui.parent

import dev.walcott.sync.DiagPayload
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.StoredDiag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A health report is dated, and both of these read it as of the day it was filed. */
class HealthReportTest {

    /** A report with nothing wrong, to vary one thing at a time. */
    private val clean = DiagPayload(
        deviceId = "dev-1",
        atMs = 1_700_000_000_000,
        enforcement = EnforcementStatus.DEVICE_OWNER,
        deviceOwner = true,
        usageAccess = true,
        gpsOn = true,
        networkLocationOn = true,
        locationPermission = true,
        batteryPercent = 80,
        appVersionCode = 54,
        appVersionName = "0.15.0",
    )

    @Test
    fun `a clean report counts no problems`() {
        assertEquals(0, clean.problems(seenAtVersionCode = 54))
    }

    @Test
    fun `each broken thing counts once`() {
        assertEquals(1, clean.copy(usageAccess = false).problems(54))
        assertEquals(1, clean.copy(enforcement = EnforcementStatus.NONE).problems(54))
        assertEquals(1, clean.copy(gpsOn = false).problems(54))
        assertEquals(1, clean.copy(suspendFailures = listOf("com.game")).problems(54))
        assertEquals(2, clean.copy(usageAccess = false, gpsOn = false).problems(54))
    }

    @Test
    fun `a low battery only counts while not charging`() {
        assertEquals(1, clean.copy(batteryPercent = 9).problems(54))
        assertEquals(0, clean.copy(batteryPercent = 9, charging = true).problems(54))
        // Unknown battery (-1 from a legacy child) is not a problem, it's an absence.
        assertEquals(0, clean.copy(batteryPercent = -1).problems(54))
    }

    @Test
    fun `waiting for the parent to allow an install is not a problem`() {
        assertEquals(0, clean.copy(updateError = "waiting_parent").problems(54))
        assertEquals(1, clean.copy(updateError = "download_failed").problems(54))
    }

    // --- The version row: judged as of the day the report was filed ---

    @Test
    fun `a child behind on the day it reported is behind`() {
        assertTrue(StoredDiag(clean.copy(appVersionCode = 53), seenAtVersionCode = 54).versionWasBehind())
        assertEquals(1, clean.copy(appVersionCode = 53).problems(54))
    }

    @Test
    fun `releases published after the report do not make it outdated`() {
        // Filed when 54 was the newest build, read today on a parent running 55. The child was
        // perfectly up to date when this was taken; a later release is not its fault.
        val filed = StoredDiag(clean.copy(appVersionCode = 54), seenAtVersionCode = 54)
        assertFalse(filed.versionWasBehind())
        assertEquals(0, filed.report.problems(filed.seenAtVersionCode))
    }

    @Test
    fun `a report filed before the stamp existed cannot tell, so it never says outdated`() {
        assertFalse(StoredDiag(clean.copy(appVersionCode = 1), seenAtVersionCode = 0).versionWasBehind())
        assertEquals(0, clean.copy(appVersionCode = 1).problems(seenAtVersionCode = 0))
    }

    @Test
    fun `a child that does not report its version is not counted as behind`() {
        assertFalse(StoredDiag(clean.copy(appVersionCode = 0), seenAtVersionCode = 54).versionWasBehind())
    }
}
