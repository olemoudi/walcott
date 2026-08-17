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

    /**
     * Child: reading notifications, when the family asked this phone to keep a log of them.
     *
     * Critical because the feature is entirely dead without it, and because it is the ONE
     * permission a Device Owner cannot grant itself — a notification listener is enabled by a
     * human in Settings, full stop. So the family can switch the log on, see nothing arrive, and
     * have no way to tell "a quiet day" from "this was never allowed" unless it is asked for here.
     */
    NOTIFICATION_ACCESS(
        critical = true,
        titleRes = R.string.req_notification_access_title,
        bodyRes = R.string.req_notification_access_body,
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

    companion object {
        /**
         * The requirement a stored or reported [key] names, or null when this build has never
         * heard of it — a parent reading a newer child's list (ChildSnapshot.setupUnmet) skips
         * what it cannot name rather than showing a blank row.
         */
        fun byKey(key: String): DeviceRequirement? = entries.firstOrNull { it.key == key }
    }
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
    /** The rules ask this device to keep a notification log (see NotificationLog). */
    val notificationLogWanted: Boolean = false,
    /** The phone's owner has let Walcott read notifications. */
    val notificationAccessGranted: Boolean = true,
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
     * Everything this device is supposed to have, satisfied or not, most serious first.
     *
     * Applicability is as important as the check itself:
     * - usage access and the accessibility blocker mean nothing on a parent phone, which
     *   enforces no rules;
     * - the accessibility blocker is the fallback for devices that are NOT Device Owner — on one
     *   that is, suspension does the blocking and the service is redundant;
     * - location is only asked for by a family that turned tracking on;
     * - the web filter only exists where the rules define one;
     * - battery optimisation cannot be changed on a Device Owner, so it is not asked for there.
     *
     * Separate from [unmet] because the guided setup has to show what is ALREADY fine as well as
     * what is missing: a summary that lists only the failures cannot tell "this phone is ready"
     * from "this phone was never checked".
     */
    fun applicable(facts: DeviceFacts): List<DeviceRequirement> {
        val applicable = mutableListOf(DeviceRequirement.NOTIFICATIONS)
        if (facts.enforcingChild) {
            applicable += DeviceRequirement.USAGE_ACCESS
            if (!facts.deviceOwner) applicable += DeviceRequirement.ACCESSIBILITY
            if (facts.webFilterWanted) applicable += DeviceRequirement.WEB_FILTER
            // Only where a log was actually asked for. A family that never turned it on must
            // never be shown a card offering to let this app read their messages.
            if (facts.notificationLogWanted) applicable += DeviceRequirement.NOTIFICATION_ACCESS
            if (facts.locationWanted) {
                // A Device Owner force-grants the permission (see LocationPolicy), so asking the
                // child for it there would be a card nobody can act on and nobody needs to.
                if (!facts.deviceOwner) applicable += DeviceRequirement.LOCATION_PERMISSION
                applicable += DeviceRequirement.LOCATION_SERVICE
            }
        }
        // Battery optimisation is the system's to decide on a fully managed device: Settings
        // lists Walcott as "Battery optimization not available" and offers no switch, because
        // the OS reserves that call for the app that owns the device. Asking anyway sent the
        // child to a screen where the one thing they had been told to do could not be done —
        // the exact failure this list exists to avoid, and worse than saying nothing, because
        // an instruction that visibly cannot be followed teaches that the others are noise too.
        if (!facts.deviceOwner) applicable += DeviceRequirement.BATTERY_OPTIMIZATION
        return applicable.sortedByDescending { it.critical }
    }

    /** Whether this device currently meets [requirement], with no view on whether it applies. */
    fun satisfied(facts: DeviceFacts, requirement: DeviceRequirement): Boolean = when (requirement) {
        DeviceRequirement.NOTIFICATIONS -> facts.notificationsEnabled
        DeviceRequirement.USAGE_ACCESS -> facts.usageAccessGranted
        DeviceRequirement.ACCESSIBILITY -> facts.accessibilityEnabled
        DeviceRequirement.WEB_FILTER -> facts.webFilterRunning
        DeviceRequirement.NOTIFICATION_ACCESS -> facts.notificationAccessGranted
        DeviceRequirement.LOCATION_PERMISSION -> facts.locationPermissionGranted
        DeviceRequirement.LOCATION_SERVICE -> facts.locationServiceEnabled
        DeviceRequirement.BATTERY_OPTIMIZATION -> facts.ignoringBatteryOptimizations
    }

    /** Everything currently not satisfied that applies to this device, most serious first. */
    fun unmet(facts: DeviceFacts): List<DeviceRequirement> =
        applicable(facts).filterNot { satisfied(facts, it) }

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
