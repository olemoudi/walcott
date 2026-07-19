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
}
