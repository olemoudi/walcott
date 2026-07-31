package dev.walcott.ui.parent

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.PolicySettings
import dev.walcott.ui.format.humanize
import java.time.Duration

/**
 * The rules an app can carry of its own, each with the icon that stands for it.
 *
 * The same icon marks the app in the list and titles its section in the app's own screen, so
 * the badge a parent scans past is the thing they then edit.
 */
internal enum class AppRestriction(val icon: ImageVector, @StringRes val labelRes: Int) {
    OWN_BUDGET(Icons.Outlined.HourglassBottom, R.string.app_own_limit),
    OWN_WINDOWS(Icons.Outlined.Schedule, R.string.app_own_window),
    WEB_RULE(Icons.Outlined.Language, R.string.app_web_filter),
}

/**
 * Which of them [packageName] actually carries. Pure, so the list badges and the screen that
 * edits them can never drift apart on what "this app has its own rules" means.
 */
internal fun appRestrictions(settings: PolicySettings, packageName: String): List<AppRestriction> {
    val policy = settings.appPolicies[packageName]
    return buildList {
        if (!policy?.budgets.isNullOrEmpty()) add(AppRestriction.OWN_BUDGET)
        if (policy?.blockedWindows?.values?.any { it.isNotEmpty() } == true) add(AppRestriction.OWN_WINDOWS)
        if (settings.domainAppRules.any { it.packageName == packageName }) add(AppRestriction.WEB_RULE)
    }
}

/** The badge strip for a list row. Renders nothing when the app carries no rules of its own. */
@Composable
internal fun AppRestrictionBadges(restrictions: List<AppRestriction>, modifier: Modifier = Modifier) {
    if (restrictions.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        restrictions.forEach { restriction ->
            Icon(
                restriction.icon,
                // Named, not decorative: for a screen reader the badges ARE the information.
                contentDescription = stringResource(restriction.labelRes),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * What this app's day looks like, in one line: the limit set for it, the family default it
 * falls back to, that it was set free of the default, or that nothing limits it at all.
 *
 * Reads the RESOLVED policy (the family's, or one child's), so the list says what will really
 * happen rather than what was typed where.
 */
@Composable
internal fun appLimitLabel(settings: PolicySettings, packageName: String): String {
    val policy = settings.appPolicies[packageName]
    val dayType = dev.walcott.rules.DayType.SCHOOL
    val own = policy?.budgets?.get(dayType.name)
    return when {
        policy?.unlimited == true -> stringResource(R.string.app_limit_none_ever)
        own != null && own <= 0 -> stringResource(R.string.app_limit_blocked)
        own != null -> stringResource(R.string.app_limit_own, humanMinutes(own))
        settings.defaultAppBudget[dayType.name] != null ->
            stringResource(R.string.app_limit_default, humanMinutes(settings.defaultAppBudget.getValue(dayType.name)))
        else -> stringResource(R.string.app_limit_none)
    }
}

/** "45 min" / "1 h 30 min", reusing the app-wide duration wording. */
private fun humanMinutes(minutes: Int): String = Duration.ofMinutes(minutes.toLong()).humanize()

/** How many app rows a "used today" list shows before it stops being a glance. */
internal const val USAGE_ROWS = 6
