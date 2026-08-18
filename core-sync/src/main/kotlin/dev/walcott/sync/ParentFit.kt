package dev.walcott.sync

import java.security.PrivateKey
import javax.crypto.SecretKey

/**
 * Keeps the PARENT snapshot inside one relay message — the guarantee [SnapshotFit] gives the
 * child's half, and the half that was missing.
 *
 * The failure is worse here than anywhere else in the protocol. A child that cannot fit its
 * snapshot goes quiet; a parent that cannot fit its own stops being able to change ANY rule on
 * EVERY child, permanently and silently: the message is rejected with HTTP 413 every single time,
 * the re-emit publishes exactly the same oversized bytes, and nothing in the family ever hears
 * from the parent again. The children keep enforcing whatever they last received, for ever.
 *
 * Two of the snapshot's fields had no ceiling at all. Every resolution the parent has ever sent
 * and every bonus it has ever granted rode in every message for the lifetime of the install —
 * roughly 100 bytes each, growing by a handful a day on a family that actually uses the app,
 * against a 4 kB relay cap that the rules themselves already spend most of. So this file does
 * two things:
 *
 *  - [liveResolutions] / [liveBonuses] retire what can no longer do anything. Both are applied
 *    idempotently by id on the child, and both stop meaning anything once the request they answer
 *    has expired ([SyncEngine.REQUEST_TTL_MS]) or the day they credit is over. Keeping them was
 *    never harmless: a bonus is credited to the day it ARRIVES, so one from last month landing on
 *    a child that has been offline is minutes nobody granted today.
 *  - [encode] measures the real encoded bytes and degrades in a fixed order rather than
 *    publishing something the relay will refuse.
 *
 * What cannot be degraded is the policy itself: rules are the message. When even a bare snapshot
 * does not fit, [Result.oversize] says so, so the parent's own app can tell them their rules have
 * outgrown the channel — the one outcome that must never be silent.
 */
object ParentFit {

    /**
     * How long an answered request keeps travelling.
     *
     * A resolution exists to reach the child that asked. That child retires its own request after
     * [SyncEngine.REQUEST_TTL_MS] (48 h) and stops having anything to match the answer against, so
     * an older resolution can no longer be applied by anybody. The extra day is slack for a child
     * whose clock or connection lagged, not a second life.
     */
    const val RESOLUTION_TTL_MS = SyncEngine.REQUEST_TTL_MS + 24 * 60 * 60 * 1000L

    /**
     * How long the PARENT keeps an answer on its own phone, which is a different question.
     *
     * Nothing can apply an answer this old, so it stops travelling after [RESOLUTION_TTL_MS] — but
     * the parent's own screens still read the list to tell a tapped notification "you answered
     * this" apart from "nobody ever did" (see [SyncEngine.requestState]). Dropping it from both at
     * once would have a parent who answered their child on Monday told on Thursday that the app
     * had never heard of the request. Storage is not the wire: a month of answers is a few
     * kilobytes in DataStore and zero bytes in every message.
     */
    const val RESOLUTION_KEEP_MS = 30L * 24 * 60 * 60 * 1000L

    /** Ceiling on the kept answers, so a very busy family cannot grow the store without bound. */
    const val RESOLUTIONS_KEPT_MAX = 300

    /**
     * How many days a bonus keeps travelling.
     *
     * Extra time is credited to the day it is applied, and a bonus is granted for today. Two days
     * covers a child that was off overnight; beyond that, applying it would hand out minutes on a
     * day nobody meant.
     */
    const val BONUS_MAX_AGE_DAYS = 2L

    /** Answers still worth putting on the wire at [nowMs] — a child could still apply these. */
    fun liveResolutions(resolutions: List<Resolution>, nowMs: Long): List<Resolution> =
        resolutions.filter { it.resolvedAtEpochMs <= 0 || nowMs - it.resolvedAtEpochMs <= RESOLUTION_TTL_MS }

    /** Answers still worth REMEMBERING at [nowMs], for the parent's own screens (see [RESOLUTION_KEEP_MS]). */
    fun keptResolutions(resolutions: List<Resolution>, nowMs: Long): List<Resolution> =
        resolutions
            .filter { it.resolvedAtEpochMs <= 0 || nowMs - it.resolvedAtEpochMs <= RESOLUTION_KEEP_MS }
            .takeLast(RESOLUTIONS_KEPT_MAX)

    /** Bonuses still worth carrying on [todayEpochDay]. */
    fun liveBonuses(bonuses: List<Bonus>, todayEpochDay: Long): List<Bonus> =
        bonuses.filter { todayEpochDay - it.epochDay <= BONUS_MAX_AGE_DAYS }

    /**
     * What had to be sacrificed to make the message fit, for the caller's log line, and whether
     * even the bare snapshot was too big ([oversize] — nothing left to trade).
     */
    data class Result(val encoded: String, val degraded: String? = null, val oversize: Boolean = false)

    /**
     * Encodes [snapshot], trading away what the family can most afford to lose, in this order:
     *
     *  1. **icon requests** — cosmetic and re-asked on the very next publish.
     *  2. **domain acknowledgements** — the child simply re-sends those slices; reassembly is
     *     idempotent, so this costs one round trip and no data.
     *  3. **"locate now" asks** — the parent is standing there and can tap it again.
     *  4. **resolutions and bonuses**, oldest first — already retired by [liveResolutions] and
     *     [liveBonuses], so what is dropped here is an answer a child may still be waiting for:
     *     bad, and still better than every child losing every rule change.
     *  5. **remote commands**, oldest first — deliberately last, because each one is a parent
     *     explicitly asking for something and the parent's pending list shows what is queued.
     */
    fun encode(
        snapshot: ParentSnapshot,
        familyKey: SecretKey,
        signingKey: PrivateKey,
        rotation: RotationCert? = null,
        maxBytes: Int = SnapshotFit.MAX_BYTES,
    ): Result {
        fun encode(candidate: ParentSnapshot) =
            SyncProtocol.encodeParent(candidate, familyKey, signingKey, rotation)

        encode(snapshot).let { if (it.length <= maxBytes) return Result(it) }

        var trimmed = snapshot.copy(iconRequests = emptyList())
        encode(trimmed).let { if (it.length <= maxBytes) return Result(it, "icons") }

        trimmed = trimmed.copy(domainAcks = emptyList())
        encode(trimmed).let { if (it.length <= maxBytes) return Result(it, "icons,acks") }

        trimmed = trimmed.copy(locationRequests = emptyList())
        encode(trimmed).let { if (it.length <= maxBytes) return Result(it, "icons,acks,locates") }

        while (trimmed.resolutions.isNotEmpty() || trimmed.bonuses.isNotEmpty()) {
            trimmed = trimmed.copy(
                resolutions = trimmed.resolutions.drop(1),
                bonuses = trimmed.bonuses.drop(1),
            )
            encode(trimmed).let {
                if (it.length <= maxBytes) {
                    return Result(
                        it,
                        "icons,acks,locates,answers:${trimmed.resolutions.size + trimmed.bonuses.size}",
                    )
                }
            }
        }

        while (trimmed.commands.isNotEmpty()) {
            trimmed = trimmed.copy(commands = trimmed.commands.drop(1))
            encode(trimmed).let {
                if (it.length <= maxBytes) {
                    return Result(it, "icons,acks,locates,answers,commands:${trimmed.commands.size}")
                }
            }
        }

        // Nothing left but the rules and the version. Published anyway: the relay may be a
        // self-hosted one with a larger cap, and a refused publish that was attempted is at
        // least visible (see PublishHealth) where a message never sent is not.
        return Result(encode(trimmed), "everything but the rules", oversize = true)
    }
}
