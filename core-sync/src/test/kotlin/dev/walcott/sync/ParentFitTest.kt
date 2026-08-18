package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parent's message has to fit, and its two unbounded fields have to stop being unbounded.
 *
 * What is being protected here is the only channel the rules travel on: an oversized parent
 * snapshot is refused deterministically, on every publish and every re-emit, so a family would
 * simply stop being able to change anything on any child — with nothing on screen to say so.
 */
class ParentFitTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val signing = FamilyCrypto.generateSigningKeyPair()

    private val day = 20_000L
    private val nowMs = day * 24 * 60 * 60 * 1000L

    private fun resolution(ageMs: Long) =
        Resolution("r-$ageMs", approved = true, grantedMinutes = 15, resolvedAtEpochMs = nowMs - ageMs)

    private fun bonus(onDay: Long) = Bonus("b-$onDay", "device-1", "com.example.app", 10, onDay)

    @Test
    fun `answers older than the request they answer are retired`() {
        val fresh = resolution(60_000)
        val yesterday = resolution(24 * 60 * 60 * 1000L)
        // Past the child's own request TTL plus the day of slack: nothing can still apply it.
        val ancient = resolution(ParentFit.RESOLUTION_TTL_MS + 1)

        val live = ParentFit.liveResolutions(listOf(ancient, yesterday, fresh), nowMs)

        assertEquals(listOf(yesterday, fresh), live)
    }

    @Test
    fun `the parent remembers an answer long after it stops travelling`() {
        // Two different questions: what a child could still apply, and what the parent's own
        // screens can still explain. A notification tapped days later must not report a request
        // the family answered as one nobody ever heard of (see SyncEngine.requestState).
        val lastWeek = resolution(7L * 24 * 60 * 60 * 1000L)
        assertTrue(ParentFit.liveResolutions(listOf(lastWeek), nowMs).isEmpty())
        assertEquals(listOf(lastWeek), ParentFit.keptResolutions(listOf(lastWeek), nowMs))

        val ancient = resolution(ParentFit.RESOLUTION_KEEP_MS + 1)
        assertTrue(ParentFit.keptResolutions(listOf(ancient), nowMs).isEmpty())
    }

    @Test
    fun `what the parent remembers is bounded too`() {
        val many = (1..ParentFit.RESOLUTIONS_KEPT_MAX + 100).map { resolution(it * 1000L) }
        val kept = ParentFit.keptResolutions(many, nowMs)
        assertEquals(ParentFit.RESOLUTIONS_KEPT_MAX, kept.size)
        assertEquals(many.last(), kept.last(), "the newest answers are the ones worth keeping")
    }

    @Test
    fun `a resolution with no timestamp is kept rather than guessed at`() {
        // Legacy senders write 0. "I cannot tell how old this is" must not read as "ancient" —
        // the same rule the request expiry follows (see SyncEngine.requestExpired).
        val undated = Resolution("r-legacy", approved = true, grantedMinutes = 5, resolvedAtEpochMs = 0)
        assertEquals(listOf(undated), ParentFit.liveResolutions(listOf(undated), nowMs))
    }

    @Test
    fun `bonuses stop travelling once the day they credit is well past`() {
        val live = ParentFit.liveBonuses(
            listOf(bonus(day - 30), bonus(day - ParentFit.BONUS_MAX_AGE_DAYS), bonus(day)),
            todayEpochDay = day,
        )
        // The month-old one is gone: applying it now would hand out minutes TODAY, on a day
        // nobody granted them (the child credits a bonus when it arrives, not to its epochDay).
        assertEquals(listOf(bonus(day - ParentFit.BONUS_MAX_AGE_DAYS), bonus(day)), live)
    }

    @Test
    fun `a bonus granted for tomorrow survives a child in a timezone ahead`() {
        assertEquals(listOf(bonus(day + 1)), ParentFit.liveBonuses(listOf(bonus(day + 1)), todayEpochDay = day))
    }

    @Test
    fun `an ordinary snapshot is sent whole`() {
        val snapshot = ParentSnapshot(
            version = 12,
            policyJson = """{"familyName":"Demo","bedtime":{"SCHOOL":{"start":"21:00","end":"07:00"}}}""",
            resolutions = listOf(resolution(60_000)),
            iconRequests = listOf("com.example.one"),
        )
        val result = ParentFit.encode(snapshot, familyKey, signing.private)
        assertNull(result.degraded)
        assertFalse(result.oversize)
        // And it is a real envelope, not just a short one.
        assertNotNull(SyncProtocol.decode(result.encoded, familyKey, signing.public))
    }

    @Test
    fun `an oversized snapshot drops the cosmetic parts before anything that matters`() {
        val snapshot = ParentSnapshot(
            version = 12,
            policyJson = """{"familyName":"Demo"}""",
            // 200 icon requests: bulky, and re-asked on the very next publish.
            iconRequests = (1..200).map { "com.example.package.number$it" },
            commands = listOf(RemoteCommand("c1", "device-1", RemoteAction.UPDATE_NOW, nowMs)),
            resolutions = listOf(resolution(60_000)),
        )
        val result = ParentFit.encode(snapshot, familyKey, signing.private, maxBytes = 900)

        assertEquals("icons", result.degraded)
        assertFalse(result.oversize)
        val decoded = SyncProtocol.decode(result.encoded, familyKey, signing.public) as IncomingMessage.FromParent
        // What was kept is what a family would miss: the rules, the answer and the command.
        assertEquals(emptyList<String>(), decoded.snapshot.iconRequests)
        assertEquals(1, decoded.snapshot.commands.size)
        assertEquals(1, decoded.snapshot.resolutions.size)
    }

    @Test
    fun `commands are the last thing given up`() {
        val snapshot = ParentSnapshot(
            version = 12,
            policyJson = """{"familyName":"Demo"}""",
            resolutions = (1..40).map { resolution(it * 1000L) },
            bonuses = (1..40).map { Bonus("bonus-number-$it", "device-1", "com.example.app", 10, day) },
            commands = listOf(RemoteCommand("c1", "device-1", RemoteAction.UPDATE_NOW, nowMs)),
        )
        val result = ParentFit.encode(snapshot, familyKey, signing.private, maxBytes = 700)

        val decoded = SyncProtocol.decode(result.encoded, familyKey, signing.public) as IncomingMessage.FromParent
        assertTrue(result.encoded.length <= 700, "still too big: ${result.encoded.length}")
        assertEquals(1, decoded.snapshot.commands.size, "a queued command outlives every answer")
        assertTrue(decoded.snapshot.resolutions.size < 40)
        assertFalse(result.oversize)
    }

    @Test
    fun `rules that cannot fit at all are reported rather than silently refused`() {
        // The one case nothing can be traded for: the policy itself is the message. It is still
        // published — the relay may be a self-hosted one with a bigger cap — but the flag is what
        // lets the parent's own screen say why nothing is reaching their children.
        // Incompressible on purpose: 20 000 identical characters gzip to nothing, and a test that
        // proved gzip works would not prove this.
        val alphabet = ('a'..'z') + ('0'..'9')
        val random = kotlin.random.Random(seed = 7)
        val bulky = (1..8_000).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        val snapshot = ParentSnapshot(version = 3, policyJson = bulky)
        val result = ParentFit.encode(snapshot, familyKey, signing.private, maxBytes = 900)

        assertTrue(result.oversize)
        assertEquals("everything but the rules", result.degraded)
        assertNotNull(SyncProtocol.decode(result.encoded, familyKey, signing.public))
    }

    @Test
    fun `a rotation cert still rides on a degraded message`() {
        // A restored parent attaches the cert to every envelope; degrading must not drop the one
        // field that makes children accept it at all.
        val old = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(signing.public, old.private)
        val snapshot = ParentSnapshot(
            version = 4,
            policyJson = """{"familyName":"Demo"}""",
            iconRequests = (1..200).map { "com.example.package.number$it" },
        )
        val result = ParentFit.encode(snapshot, familyKey, signing.private, cert, maxBytes = 900)

        val decoded = SyncProtocol.decodeVerbose(
            result.encoded,
            familyKey,
            old.public,
        )
        assertNotNull(decoded)
        assertEquals(FamilyCrypto.toB64(signing.public.encoded), decoded?.rotatedParentPublicKeyB64)
    }

    @Test
    fun `a year of answers no longer grows the message`() {
        // The regression this file exists for. Two a day for a year is an ordinary family.
        val year = (1..730).map { resolution(it * 60_000L) } // all recent, so nothing is retired
        val big = ParentSnapshot(version = 9, policyJson = """{"familyName":"Demo"}""", resolutions = year)
        assertTrue(
            SyncProtocol.encodeParent(big, familyKey, signing.private).length > SnapshotFit.MAX_BYTES,
            "the unbounded shape must be provably too big, or this test proves nothing",
        )

        // Retired by age, as the parent does before every publish.
        val aged = (1..730).map { resolution(it * 60L * 60_000L) }
        val kept = ParentFit.liveResolutions(aged, nowMs)
        val fitted = ParentFit.encode(
            ParentSnapshot(version = 9, policyJson = """{"familyName":"Demo"}""", resolutions = kept),
            familyKey,
            signing.private,
        )
        assertNull(fitted.degraded)
        assertFalse(fitted.oversize)
    }
}
