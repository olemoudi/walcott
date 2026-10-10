package dev.walcott.sync

/**
 * When a child device's health warrants a one-shot parent alert. Pure, so the debounce logic
 * (especially the battery hysteresis that stops a phone hovering at the threshold from
 * flapping the notification on and off) is unit-tested rather than only seen live.
 */
object HealthAlerts {

    /** Alert the parent when the child drops below this, unplugged. */
    const val LOW_BATTERY_PCT = 20

    /** Only clear the low-battery alert once it recovers past this (hysteresis) or starts charging. */
    const val BATTERY_RECOVER_PCT = 25

    /**
     * True when a low-battery alert should fire now: below the threshold, not charging, a real
     * reading (>= 0), and not already alerted for this outage.
     */
    fun shouldAlertLowBattery(percent: Int, charging: Boolean, alreadyAlerted: Boolean): Boolean =
        percent in 0 until LOW_BATTERY_PCT && !charging && !alreadyAlerted

    /** True when a standing low-battery alert should be cleared: charged up past the recover mark, or plugged in. */
    fun clearsLowBattery(percent: Int, charging: Boolean): Boolean =
        charging || percent >= BATTERY_RECOVER_PCT

    /**
     * Warn the parent when a child's free storage drops below this: above the 200 MB a
     * self-update needs before it downloads, so the warning arrives while updates still work and
     * well before the phone can no longer save anything at all.
     */
    const val LOW_STORAGE_BYTES = 300L * 1024 * 1024

    /** Only clear the low-storage alert once this much is free again (hysteresis). */
    const val STORAGE_RECOVER_BYTES = 500L * 1024 * 1024

    /** True when a low-storage alert should fire now: a real reading below the threshold, not yet alerted. */
    fun shouldAlertLowStorage(freeBytes: Long, alreadyAlerted: Boolean): Boolean =
        freeBytes in 0 until LOW_STORAGE_BYTES && !alreadyAlerted

    /** True when a standing low-storage alert should be cleared: freed up past the recover mark. */
    fun clearsLowStorage(freeBytes: Long): Boolean = freeBytes >= STORAGE_RECOVER_BYTES

    /** Whether [freeBytes] is low enough to show as a problem on the parent's screens. */
    fun storageLow(freeBytes: Long): Boolean = freeBytes in 0 until LOW_STORAGE_BYTES
}
