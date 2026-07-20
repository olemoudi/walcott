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
 *  3. app list → progressively halved (classification of the tail waits for a leaner day)
 * A snapshot degraded this far always fits: the fixed fields are a few hundred bytes.
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

        var apps = noHistory.apps
        while (apps.isNotEmpty()) {
            apps = apps.take(apps.size / 2)
            SyncProtocol.encodeChild(noHistory.copy(apps = apps), familyKey).let {
                if (it.length <= maxBytes) return Result(it, "trail,history,apps:${apps.size}")
            }
        }
        // Bare snapshot: fixed fields only. This always fits any sane cap.
        return Result(
            SyncProtocol.encodeChild(noHistory.copy(apps = emptyList()), familyKey),
            "trail,history,apps:0",
        )
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
