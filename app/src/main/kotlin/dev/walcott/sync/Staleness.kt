package dev.walcott.sync

/**
 * Pure check-in staleness rules. Children heartbeat every ~15 min (sync re-emit), so a
 * long silence means the child device is off, offline — or the protection was tampered
 * with. The parent UI warns early; a notification fires only after a long outage.
 */
object Staleness {

    /** Silence after which the parent home shows a warning on the child row. */
    const val WARN_AFTER_MS = 30 * 60 * 1000L

    /** Silence after which the parent gets a notification. */
    const val ALERT_AFTER_MS = 12 * 60 * 60 * 1000L

    /** Ms without a check-in, or null when the device has never checked in. */
    fun silenceMs(lastSeenMs: Long?, nowMs: Long): Long? =
        lastSeenMs?.let { (nowMs - it).coerceAtLeast(0) }

    fun isWarn(lastSeenMs: Long?, nowMs: Long): Boolean =
        silenceMs(lastSeenMs, nowMs)?.let { it >= WARN_AFTER_MS } ?: false

    /** Dedup value stored once a registered-but-never-reported child has been alerted. */
    const val NEVER = 0L

    /**
     * Devices needing a stale alert now: silent for [ALERT_AFTER_MS] and not already
     * alerted for this same lastSeen value (one alert per outage; a device that comes
     * back and goes silent again alerts again).
     */
    fun devicesToAlert(
        lastSeen: Map<String, Long>,
        alreadyNotified: Map<String, Long>,
        nowMs: Long,
    ): Map<String, Long> = lastSeen.filter { (deviceId, seenMs) ->
        nowMs - seenMs >= ALERT_AFTER_MS && alreadyNotified[deviceId] != seenMs
    }

    /**
     * Registered children that were enrolled over [ALERT_AFTER_MS] ago but have *never* reported
     * (a botched enrollment used to be invisible). [registeredSince] is childId -> add time;
     * [reportedChildIds] are the childIds that have sent at least one snapshot. Keyed by childId,
     * with [NEVER] as the dedup value in [alreadyNotified].
     */
    fun childrenNeverReported(
        registeredSince: Map<String, Long>,
        reportedChildIds: Set<String>,
        alreadyNotified: Map<String, Long>,
        nowMs: Long,
    ): Set<String> = registeredSince.filter { (childId, since) ->
        childId.isNotBlank() && childId !in reportedChildIds &&
            nowMs - since >= ALERT_AFTER_MS && alreadyNotified[childId] != NEVER
    }.keys
}
