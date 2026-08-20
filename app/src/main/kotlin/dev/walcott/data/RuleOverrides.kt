package dev.walcott.data

/**
 * One rule a FAMILY editor sets, and that a member can take away from the family by customizing
 * it on their own screen.
 *
 * The list is the parent's, not the model's: each entry names a control a parent actually sees,
 * so a family screen can ask "who is ignoring this?" without having to know which
 * [ChildOverrides] field carries the answer. Only rules with both halves are here — a family
 * editor AND a per-member override. `keepRingerAudible` and `notificationLogEnabled` have no
 * family editor to warn on, so they are deliberately absent.
 */
enum class FamilyRule {
    /** When the phone sleeps. */
    BEDTIME,

    /** The family's screen-free windows, which block every app. */
    SCREEN_FREE,

    /** The default daily budget each app gets. */
    DEFAULT_BUDGET,

    /** Per-app limits and schedules. */
    APP_LIMITS,

    /** The blocked-domain list. */
    WEB_FILTER,

    /** The device locks a member cannot undo. */
    PROTECTION,

    /** How often the phone reports where it is. */
    TRACKING_INTERVAL,

    /** Whether the phone keeps a 48h trail rather than only its current position. */
    LOCATION_HISTORY,

    /** Whether the app updates itself on Wi-Fi only. */
    UPDATE_WIFI_ONLY,
    ;

    /**
     * Whether [overrides] has taken this rule away from the family.
     *
     * Presence, not difference. An override holding exactly the family's values still ignores the
     * family rule: the two are copies that stopped being connected the moment the switch went on,
     * so every later family edit passes this member by. Reporting only overrides that currently
     * DISAGREE would go quiet at the one moment a parent most needs the warning — while they are
     * editing the family rule that is about to have no effect.
     */
    fun isTakenOverBy(overrides: ChildOverrides): Boolean = when (this) {
        BEDTIME -> overrides.bedtime != null
        SCREEN_FREE -> overrides.allAppsBlockedWindows != null
        DEFAULT_BUDGET -> overrides.defaultAppBudget != null
        APP_LIMITS -> overrides.appPolicies != null
        WEB_FILTER -> overrides.blockedDomains != null
        PROTECTION -> overrides.deviceRestrictions != null
        TRACKING_INTERVAL -> overrides.trackingIntervalMinutes != null
        LOCATION_HISTORY -> overrides.locationHistoryEnabled != null
        UPDATE_WIFI_ONLY -> overrides.updateWifiOnly != null
    }
}

/**
 * Who is not listening to a family rule.
 *
 * A family rule is not a floor and not a ceiling: [PolicySettings.resolveForChild] applies each
 * override field WHOLESALE, so a member who has customized a rule replaces it outright — a
 * bedtime of 23:00 against the family's 22:00 is 23:00 for them, not 22:00, and a two-hour app
 * limit against the family's one hour is two hours. Nothing is intersected, unioned or clamped.
 *
 * That is a good design and a surprising one, which is precisely why the family editors have to
 * say it out loud: without this, a parent tightening the family bedtime has no way to see that
 * the one child they were tightening it for is not covered by it.
 */
object RuleOverrides {

    /** The members who have taken [rule] away from the family, in registry order. */
    fun membersOverriding(settings: PolicySettings, rule: FamilyRule): List<ChildEntry> =
        settings.children.filter { rule.isTakenOverBy(it.overrides) }

    /** Their names, blank ones dropped so a nameless entry can never render as an empty sentence. */
    fun namesOverriding(settings: PolicySettings, rule: FamilyRule): List<String> =
        membersOverriding(settings, rule).map { it.name.trim() }.filter { it.isNotEmpty() }
}
