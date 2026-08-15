package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FamilyHealthTest {

    private val now = 1_700_000_000_000L

    private fun child(
        deviceId: String,
        enforcement: String = EnforcementStatus.DEVICE_OWNER,
        usageAccessOn: Boolean = true,
        gaps: List<String> = emptyList(),
        skewMs: Long = 0,
        panic: PanicRequest? = null,
        webFilterExpected: Boolean = false,
        webFilterOn: Boolean = true,
        setupUnmet: List<String> = emptyList(),
    ) = ChildSnapshot(
        deviceId = deviceId,
        displayName = deviceId,
        version = 1,
        epochDay = 0,
        enforcement = enforcement,
        usageAccessOn = usageAccessOn,
        enforcementGaps = gaps,
        clockSkewMs = skewMs,
        panic = panic,
        webFilterExpected = webFilterExpected,
        webFilterOn = webFilterOn,
        setupUnmet = setupUnmet,
    )

    @Test
    fun `a healthy family shows no alerts`() {
        val kids = listOf(child("a"), child("b"))
        val seen = mapOf("a" to now - 60_000, "b" to now - 60_000)
        assertEquals(0, FamilyHealth.alerts(kids, seen, now))
    }

    @Test
    fun `every kind of trouble counts`() {
        val seen = mapOf("a" to now, "b" to now, "c" to now, "d" to now, "e" to now)
        assertEquals(1, FamilyHealth.alerts(listOf(child("a", enforcement = EnforcementStatus.NONE)), seen, now))
        assertEquals(1, FamilyHealth.alerts(listOf(child("b", usageAccessOn = false)), seen, now))
        assertEquals(1, FamilyHealth.alerts(listOf(child("c", gaps = listOf("com.x"))), seen, now))
        assertEquals(1, FamilyHealth.alerts(listOf(child("d", skewMs = -6 * 60 * 60 * 1000L)), seen, now))
        assertEquals(1, FamilyHealth.alerts(listOf(child("e", panic = PanicRequest("p", 0, 0))), seen, now))
        assertEquals(
            1,
            FamilyHealth.alerts(
                listOf(child("a", webFilterExpected = true, webFilterOn = false)), seen, now,
            ),
        )
        // An enrollment nobody finished: the device is alive and publishing, and part of the
        // rules is not running on it.
        assertEquals(1, FamilyHealth.alerts(listOf(child("a", setupUnmet = listOf("notifications"))), seen, now))
    }

    @Test
    fun `a legacy child that reports no setup list never looks unfinished`() {
        assertEquals(0, FamilyHealth.alerts(listOf(child("a")), mapOf("a" to now), now))
    }

    @Test
    fun `a web filter is only down when the rules asked for one`() {
        // No filter configured: there is no tunnel to miss, however the flag reads.
        assertEquals(false, FamilyHealth.webFilterDown(child("a", webFilterOn = false)))
        // A legacy child reports neither half, and must never look broken.
        assertEquals(false, FamilyHealth.webFilterDown(child("a")))
        assertEquals(false, FamilyHealth.webFilterDown(child("a", webFilterExpected = true)))
        assertEquals(
            true,
            FamilyHealth.webFilterDown(child("a", webFilterExpected = true, webFilterOn = false)),
        )
    }

    @Test
    fun `a child silent past the alert threshold counts, a resting one does not`() {
        val kids = listOf(child("a"))
        assertEquals(1, FamilyHealth.alerts(kids, mapOf("a" to now - Staleness.ALERT_AFTER_MS), now))
        assertEquals(0, FamilyHealth.alerts(kids, mapOf("a" to now - Staleness.RESTING_AFTER_MS), now))
    }

    @Test
    fun `several problems on one phone are still one child to go and look at`() {
        val broken = child("a", enforcement = EnforcementStatus.NONE, usageAccessOn = false, gaps = listOf("x"))
        assertEquals(1, FamilyHealth.alerts(listOf(broken), mapOf("a" to now), now))
    }

    @Test
    fun `pending counts unanswered asks and complete domain batches only`() {
        val chunk = DomainChunk("batch-1", "com.app", "App", 0, 1, listOf("a.com"))
        val partial = DomainChunk("batch-2", "com.app", "App", 0, 2, listOf("b.com"))
        val state = SyncState(
            children = listOf(
                child("a").copy(
                    requests = listOf(ExtraTimeRequest("r1", "games", 30, createdAtEpochMs = 0)),
                    asks = listOf(
                        ChildRequest("k1", ChildRequest.KIND_INSTALL, "App", 0, "com.app"),
                        ChildRequest("k2", ChildRequest.KIND_OTHER, "please", 0),
                    ),
                ),
            ),
            resolutions = listOf(Resolution("k2", true, 0, 0)),
            domainInbox = listOf(
                DomainInboxEntry("batch-1", "a", slices = listOf(chunk)),
                DomainInboxEntry("batch-2", "a", slices = listOf(partial)),
            ),
        )
        // r1 + k1 + the complete batch; k2 is answered and batch-2 is still arriving.
        assertEquals(3, FamilyHealth.pending(state))
    }

    @Test
    fun `a discarded domain batch stops being pending`() {
        val chunk = DomainChunk("batch-1", "com.app", "App", 0, 1, listOf("a.com"))
        val state = SyncState(
            domainInbox = listOf(DomainInboxEntry("batch-1", "a", slices = listOf(chunk))),
            domainsHandled = listOf("batch-1"),
        )
        assertEquals(0, FamilyHealth.pending(state))
    }
}
