package dev.walcott.sync

/**
 * Pure check-in staleness rules. A resting phone is EXPECTED to go quiet for hours: Doze
 * freezes the in-process 15-min re-emit, and the watchdog heartbeat only runs in Doze
 * maintenance windows (up to every few hours in deep idle). So silence has two very
 * different readings, and the parent UI must not cry wolf over the benign one:
 *  - [Tier.RESTING]: informational — almost always just a phone that is asleep;
 *  - [Tier.SILENT]: actionable — longer than any benign Doze gap, so the device is off,
 *    offline for a long time, or the protection was tampered with. Aligned with the
 *    notification threshold so the row and the alert never disagree.
 */
object Staleness {

    /** How a child's check-in silence should be presented on the parent home. */
    enum class Tier { FRESH, RESTING, SILENT }

    /** Silence after which the row mentions it, neutrally (phones sleep for hours). */
    const val RESTING_AFTER_MS = 60 * 60 * 1000L

    /** Silence after which the parent gets a notification (and the row turns red). */
    const val ALERT_AFTER_MS = 12 * 60 * 60 * 1000L

    /** Ms without a check-in, or null when the device has never checked in. */
    fun silenceMs(lastSeenMs: Long?, nowMs: Long): Long? =
        lastSeenMs?.let { (nowMs - it).coerceAtLeast(0) }

    fun tierOf(lastSeenMs: Long?, nowMs: Long): Tier {
        val silence = silenceMs(lastSeenMs, nowMs) ?: return Tier.FRESH
        return when {
            silence >= ALERT_AFTER_MS -> Tier.SILENT
            silence >= RESTING_AFTER_MS -> Tier.RESTING
            else -> Tier.FRESH
        }
    }

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
