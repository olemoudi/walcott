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
     * How long a phone has to have been gone for its RETURN to be worth a notification.
     *
     * Today a return can only follow an alert, and an alert needs [ALERT_AFTER_MS] of silence, so
     * this never bites. It is here because that is an argument about the current numbers, not a
     * property of the code: the alert threshold is one constant away from being lowered, and a
     * `lastSeen` refreshed between the alert and the return would produce the same short gap. The
     * failure it prevents is the one that makes people stop reading notifications — a phone that
     * dips under a bridge and pings twice about it.
     */
    const val BACK_ONLINE_MIN_SILENCE_MS = 2 * 60 * 60 * 1000L

    /**
     * Whether coming back is worth telling the parent about, given how long [silenceMs] lasted.
     *
     * Null means the device had never reported at all before now, which is not a reconnection and
     * has no gap to measure: it is the answer to an enrollment that looked abandoned, and it is
     * said whatever the clock says.
     */
    fun worthAnnouncingReturn(silenceMs: Long?): Boolean =
        silenceMs == null || silenceMs >= BACK_ONLINE_MIN_SILENCE_MS

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
     * The dedup keys to drop when a device checks in again, empty when there is nothing to say.
     *
     * The other half of [devicesToAlert], and the half that was missing: a parent who was told at
     * 3 a.m. that a phone had gone quiet was never told it came back, so the only way to find out
     * was to open the app and go looking — which is exactly what an alert is supposed to save you.
     *
     * Deliberately keyed on having ALERTED rather than on how long the silence was. Phones sleep
     * for hours and that is not news in either direction; "it is back" is only worth a
     * notification when "it is gone" earned one. Both keys are checked because the two alerts are
     * stored in the same map under different keys: an outage by [deviceId], a child that had never
     * reported at all by [childId] (see [childrenNeverReported]).
     */
    fun recoveryKeys(deviceId: String, childId: String, alreadyNotified: Map<String, Long>): Set<String> =
        buildSet {
            if (deviceId in alreadyNotified) add(deviceId)
            if (childId.isNotBlank() && alreadyNotified[childId] == NEVER) add(childId)
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
