package dev.walcott.setup

import dev.walcott.R

/**
 * One thing this device needs switched on for Walcott to do its job, and what it costs when it
 * isn't.
 *
 * [critical] is not about whether it can be dismissed — everything can (see [DeviceSetup]) — but
 * about how loudly it is put: a critical requirement means part of the app is not working at all
 * right now, not that it works less well.
 */
enum class DeviceRequirement(
    val critical: Boolean,
    val titleRes: Int,
    val bodyRes: Int,
) {
    /**
     * First, always. Everything else in this list repairs itself by TELLING someone, and on a
     * phone that can't post a notification there is nobody to tell — including about the missing
     * permission itself. It is the only requirement whose absence hides the others.
     */
    NOTIFICATIONS(
        critical = true,
        titleRes = R.string.req_notifications_title,
        bodyRes = R.string.req_notifications_body,
    ),

    /** Child: without it budgets never count down, and the rules fail closed. */
    USAGE_ACCESS(
        critical = true,
        titleRes = R.string.req_usage_access_title,
        bodyRes = R.string.req_usage_access_body,
    ),

    /** Child without Device Owner: the only thing that blocks anything at all. */
    ACCESSIBILITY(
        critical = true,
        titleRes = R.string.req_accessibility_title,
        bodyRes = R.string.req_accessibility_body,
    ),

    /** Child: the rules ask for a DNS filter and the tunnel isn't up. */
    WEB_FILTER(
        critical = true,
        titleRes = R.string.req_web_filter_title,
        bodyRes = R.string.req_web_filter_body,
    ),

    /** Child: location permission, when the family asked to be able to find the phone. */
    LOCATION_PERMISSION(
        critical = false,
        titleRes = R.string.req_location_permission_title,
        bodyRes = R.string.req_location_permission_body,
    ),

    /** Child: system location switched off entirely. */
    LOCATION_SERVICE(
        critical = false,
        titleRes = R.string.req_location_service_title,
        bodyRes = R.string.req_location_service_body,
    ),

    /** Both: what defers the check-in and the catch-up poll by hours while the app is closed. */
    BATTERY_OPTIMIZATION(
        critical = false,
        titleRes = R.string.req_battery_title,
        bodyRes = R.string.req_battery_body,
    ),
    ;

    /** Stable key for persistence, so reordering or renaming the enum can't lose a dismissal. */
    val key: String get() = name.lowercase()
}

/**
 * What this device currently is and has, as read from the system in one pass.
 *
 * A plain snapshot of facts rather than a set of judgements, so [DeviceSetup.unmet] — which is
 * where all the "does this even apply here" reasoning lives — can be tested without Android.
 */
data class DeviceFacts(
    val enforcingChild: Boolean,
    val deviceOwner: Boolean,
    val notificationsEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val locationPermissionGranted: Boolean,
    val locationServiceEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    /** The family asked this device to be locatable (tracking interval > 0). */
    val locationWanted: Boolean,
    /** The rules ask this device for a DNS filter. */
    val webFilterWanted: Boolean,
    /** The DNS filter tunnel is actually established. */
    val webFilterRunning: Boolean,
)

/**
 * Which requirements this device is currently failing, and which of those are worth putting in
 * front of someone.
 *
 * Pure on purpose. The whole value of this list is that it is honest in both directions: a
 * requirement shown on a device it does not apply to trains people to dismiss the ones that do,
 * and one silently skipped is a phone that quietly stops enforcing anything. Neither is
 * checkable by looking at a screen.
 */
object DeviceSetup {

    /**
     * Everything currently not satisfied that APPLIES to this device, most serious first.
     *
     * Applicability is as important as the check itself:
     * - usage access and the accessibility blocker mean nothing on a parent phone, which
     *   enforces no rules;
     * - the accessibility blocker is the fallback for devices that are NOT Device Owner — on one
     *   that is, suspension does the blocking and the service is redundant;
     * - location is only asked for by a family that turned tracking on;
     * - the web filter only exists where the rules define one.
     */
    fun unmet(facts: DeviceFacts): List<DeviceRequirement> {
        val unmet = mutableListOf<DeviceRequirement>()
        if (!facts.notificationsEnabled) unmet += DeviceRequirement.NOTIFICATIONS
        if (facts.enforcingChild) {
            if (!facts.usageAccessGranted) unmet += DeviceRequirement.USAGE_ACCESS
            if (!facts.deviceOwner && !facts.accessibilityEnabled) unmet += DeviceRequirement.ACCESSIBILITY
            if (facts.webFilterWanted && !facts.webFilterRunning) unmet += DeviceRequirement.WEB_FILTER
            if (facts.locationWanted) {
                // A Device Owner force-grants the permission (see LocationPolicy), so asking the
                // child for it there would be a card nobody can act on and nobody needs to.
                if (!facts.deviceOwner && !facts.locationPermissionGranted) {
                    unmet += DeviceRequirement.LOCATION_PERMISSION
                }
                if (!facts.locationServiceEnabled) unmet += DeviceRequirement.LOCATION_SERVICE
            }
        }
        if (!facts.ignoringBatteryOptimizations) unmet += DeviceRequirement.BATTERY_OPTIMIZATION
        return unmet.sortedByDescending { it.critical }
    }

    /**
     * What the home screen should nag about: the unmet requirements minus the ones dismissed.
     *
     * Dismissals live per requirement and are dropped as soon as it is satisfied
     * ([survivingDismissals]), so hiding "location is off" today does not hide it for ever — it
     * hides *this* outage. The next one is a new problem and gets asked again.
     */
    fun toNag(unmet: List<DeviceRequirement>, dismissed: Set<String>): List<DeviceRequirement> =
        unmet.filterNot { it.key in dismissed }

    /**
     * The dismissals still worth keeping: only those whose requirement is STILL unmet.
     *
     * Without this, a requirement that was dismissed, fixed, and later broken again would stay
     * silently hidden — which is precisely the case where the person has forgotten it exists.
     */
    fun survivingDismissals(unmet: List<DeviceRequirement>, dismissed: Set<String>): Set<String> {
        val unmetKeys = unmet.map { it.key }.toSet()
        return dismissed.filterTo(mutableSetOf()) { it in unmetKeys }
    }
}
