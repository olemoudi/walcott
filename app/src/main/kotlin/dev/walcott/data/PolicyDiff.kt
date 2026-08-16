package dev.walcott.data

/**
 * Which parts of the rules have been edited but not yet published (see [PolicyPush]).
 *
 * While a burst of edits is being held, the family's screens are showing settings that are real
 * on this phone and not yet real on any child's. Saying so is the honest counterpart to holding
 * them: without it the delay is indistinguishable from the app having ignored the change.
 *
 * The keys are deliberately at the granularity someone EDITS, not the granularity the data
 * happens to be stored at — a screen, an app, a child. A parent who changed one app's limit
 * wants that app marked, not "limits" marked; a parent who moved bedtime does not want every app
 * marked because bedtime is family-wide.
 *
 * Pure, so what counts as a change is checkable without a device.
 */
object PolicyDiff {

    const val DEFAULT_BUDGET = "defaultBudget"
    const val BEDTIME = "bedtime"
    const val SCREEN_FREE = "screenFree"
    const val CALENDAR = "calendar"
    const val WEB_FILTER = "webFilter"
    const val RESTRICTIONS = "restrictions"
    const val EARN = "earn"
    const val LOCATION = "location"
    const val UPDATES = "updates"
    const val NEW_APP_ALERTS = "newAppAlerts"
    const val PIN = "pin"
    const val FAMILY_NAME = "familyName"

    /** Key for one app's own rules. */
    fun appKey(packageName: String) = "app:$packageName"

    /** Key for one child's entry: their name and their overrides. */
    fun childKey(childId: String) = "child:$childId"

    /**
     * Everything that differs between the last published policy and the current one.
     *
     * A null [deployed] — nothing has been published yet from this install — reports NOTHING
     * rather than everything: on a fresh family every setting differs from "no policy at all",
     * and marking the entire app as pending would say nothing useful about what the parent just
     * touched.
     */
    fun changedKeys(deployed: PolicySettings?, current: PolicySettings): Set<String> {
        if (deployed == null) return emptySet()
        val changed = mutableSetOf<String>()

        if (deployed.defaultAppBudget != current.defaultAppBudget) changed += DEFAULT_BUDGET
        if (deployed.bedtime != current.bedtime) changed += BEDTIME
        if (deployed.allAppsBlockedWindows != current.allAppsBlockedWindows) changed += SCREEN_FREE
        if (deployed.blockedDomains != current.blockedDomains ||
            deployed.domainAppRules != current.domainAppRules ||
            deployed.enabledBlocklists != current.enabledBlocklists
        ) {
            changed += WEB_FILTER
        }
        if (deployed.deviceRestrictions != current.deviceRestrictions) changed += RESTRICTIONS
        if (deployed.idleEarn != current.idleEarn) changed += EARN
        if (deployed.trackingIntervalMinutes != current.trackingIntervalMinutes ||
            deployed.locationHistoryEnabled != current.locationHistoryEnabled
        ) {
            changed += LOCATION
        }
        if (deployed.updateWifiOnly != current.updateWifiOnly) changed += UPDATES
        if (deployed.newAppAlerts != current.newAppAlerts) changed += NEW_APP_ALERTS
        if (deployed.pinHash != current.pinHash || deployed.pinSalt != current.pinSalt) changed += PIN
        if (deployed.familyName != current.familyName) changed += FAMILY_NAME

        // The calendar is one screen, so its several fields are one key.
        if (deployed.holidays != current.holidays ||
            deployed.vacations != current.vacations ||
            deployed.childHolidays != current.childHolidays ||
            deployed.childVacations != current.childVacations ||
            deployed.specialDaysOwnRules != current.specialDaysOwnRules ||
            deployed.weekendStartsFridayAtMinute != current.weekendStartsFridayAtMinute ||
            deployed.weekendEndsSundayAtMinute != current.weekendEndsSundayAtMinute
        ) {
            changed += CALENDAR
        }

        // Per app: added, removed or altered. A removal counts — "this app no longer has its own
        // limit" is a change a child has not been told about yet either.
        (deployed.appPolicies.keys + current.appPolicies.keys).forEach { pkg ->
            if (deployed.appPolicies[pkg] != current.appPolicies[pkg]) changed += appKey(pkg)
        }

        // Per child: their name, and the overrides that make their rules differ from the family's.
        val before = deployed.children.associateBy { it.childId }
        val after = current.children.associateBy { it.childId }
        (before.keys + after.keys).forEach { childId ->
            if (before[childId] != after[childId]) changed += childKey(childId)
        }
        return changed
    }
}
