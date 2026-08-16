package dev.walcott.sync

import javax.crypto.SecretKey

/**
 * Guarantees a child snapshot always fits in one ntfy message.
 *
 * An oversized publish is rejected with HTTP 413 and, because the snapshot is the child's
 * only channel, the failure mode is brutal: the child silently disappears from the parent
 * until the snapshot happens to shrink. [LocationTrail] bounds the trail, but the app list
 * is driven by whatever the child installs, so the total has no hard ceiling without this.
 *
 * Degradation order trades the least critical data first:
 *  1. location trail → newest fix only (the map loses history, never the current position)
 *  2. usage history → empty (the weekly report degrades; today's usage still travels)
 *  3. block statistics → breakdown first, then the whole report (the totals come back in the
 *     next report's catch-up days, so this costs a day's long tail and never a number)
 *  4. domain slices → fewer per message (the rest ride the next publish; they are resent
 *     until acknowledged anyway, so this costs latency and nothing else)
 *  5. app list → progressively halved (classification of the tail waits for a leaner day)
 *  6. asks → dropped, and only here. An ask is a child waiting for an answer, so losing one is
 *     worse than losing any of the above; it is still better than a rejected publish, which
 *     loses the child's whole voice rather than one sentence of it.
 *
 * Nothing is returned without being measured. The last resort used to be assumed to fit — true
 * only while every remaining field was small, which stopped being true the moment a child could
 * attach a list a parent had chosen.
 */
object SnapshotFit {

    /** Headroom under ntfy's 4096-byte default message cap. */
    const val MAX_BYTES = 3800

    /** What had to be sacrificed, for the caller's log line. Null = nothing, sent in full. */
    data class Result(val encoded: String, val degraded: String?)

    fun encodeChild(snapshot: ChildSnapshot, familyKey: SecretKey, maxBytes: Int = MAX_BYTES): Result {
        val full = SyncProtocol.encodeChild(snapshot, familyKey)
        if (full.length <= maxBytes) return Result(full, null)

        val noTrail = snapshot.copy(locations = snapshot.locations.takeLast(1))
        SyncProtocol.encodeChild(noTrail, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail")
        }

        val noHistory = noTrail.copy(history = emptyList())
        SyncProtocol.encodeChild(noHistory, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail,history")
        }

        // Block statistics, breakdown first and then entirely. The totals are what the parent's
        // ledger accumulates, and a later report carries the days it missed — so what a squeezed
        // message costs here is the long tail of one day's domains, and never a number.
        val thinBlocks = noHistory.copy(blocks = noHistory.blocks?.totalsOnly())
        SyncProtocol.encodeChild(thinBlocks, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail,history,blocks:totals")
        }
        val noBlocks = thinBlocks.copy(blocks = null)
        SyncProtocol.encodeChild(noBlocks, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail,history,blocks")
        }

        // Domain slices are the only payload here that is retried by design, so thinning them
        // costs a publish cycle and never a domain.
        var trimmed = noBlocks
        while (trimmed.domainChunks.size > 1) {
            trimmed = trimmed.copy(domainChunks = trimmed.domainChunks.dropLast(1))
            SyncProtocol.encodeChild(trimmed, familyKey).let {
                if (it.length <= maxBytes) {
                    return Result(it, "trail,history,blocks,chunks:${trimmed.domainChunks.size}")
                }
            }
        }

        var apps = trimmed.apps
        while (apps.isNotEmpty()) {
            apps = apps.take(apps.size / 2)
            SyncProtocol.encodeChild(trimmed.copy(apps = apps), familyKey).let {
                if (it.length <= maxBytes) return Result(it, "trail,history,blocks,apps:${apps.size}")
            }
        }

        val bare = trimmed.copy(apps = emptyList())
        SyncProtocol.encodeChild(bare, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail,history,blocks,apps:0")
        }

        // Still too big, so something variable is left: the last domain slice, then the asks.
        // Measured rather than assumed, because the alternative to an honest degradation here is
        // a rejected publish, and a rejected publish is a child that has simply gone quiet.
        val noChunks = bare.copy(domainChunks = emptyList())
        SyncProtocol.encodeChild(noChunks, familyKey).let {
            if (it.length <= maxBytes) return Result(it, "trail,history,blocks,apps:0,chunks:0")
        }
        var asks = noChunks.asks
        while (asks.isNotEmpty()) {
            asks = asks.dropLast(1)
            SyncProtocol.encodeChild(noChunks.copy(asks = asks), familyKey).let {
                if (it.length <= maxBytes) {
                    return Result(it, "trail,history,blocks,apps:0,chunks:0,asks:${asks.size}")
                }
            }
        }
        return Result(
            SyncProtocol.encodeChild(noChunks.copy(asks = emptyList(), requests = emptyList()), familyKey),
            "everything but the fixed fields",
        )
    }
}

/**
 * Same guarantee for the icon trickle. [IconSync.pack] budgets the raw base64, but the wire
 * size is only known after gzip + AES-GCM + base64 + envelope, so this drops icons off the
 * tail until the encoded message actually fits. Returns null when even a single icon doesn't
 * fit: unlike the snapshot, an icon message is optional, so "send nothing and let the parent
 * re-ask" beats publishing a message the server will reject (HTTP 413) every single cycle.
 */
object IconFit {

    fun encode(payload: IconPayload, familyKey: SecretKey, maxBytes: Int = SnapshotFit.MAX_BYTES): String? {
        var icons = payload.icons
        while (true) {
            val encoded = SyncProtocol.encodeChildIcons(payload.copy(icons = icons), familyKey)
            if (encoded.length <= maxBytes) {
                // Carrying no icons is a real message when it is carrying the list of the ones
                // this child will never manage to render — that is what stops the parent asking
                // for ever (see IconPayload.unavailable). Carrying neither is not.
                return encoded.takeUnless { icons.isEmpty() && payload.unavailable.isEmpty() }
            }
            if (icons.isEmpty()) return null
            icons = icons.dropLast(1)
        }
    }
}

/**
 * Same guarantee for the diagnostics report: the log tail is the only unbounded part, so it
 * is halved (dropping the OLDEST lines) until the encoded message fits. The fixed fields are
 * a few hundred bytes and always fit.
 */
object DiagFit {

    fun encode(payload: DiagPayload, familyKey: SecretKey, maxBytes: Int = SnapshotFit.MAX_BYTES): String {
        var lines = payload.logLines
        while (true) {
            val encoded = SyncProtocol.encodeChildDiag(payload.copy(logLines = lines), familyKey)
            if (encoded.length <= maxBytes || lines.isEmpty()) return encoded
            lines = lines.takeLast(lines.size / 2)
        }
    }
}
