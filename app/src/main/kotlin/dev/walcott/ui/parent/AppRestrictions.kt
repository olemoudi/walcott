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

/**
 * The restrictions an app can carry of its own, on top of whatever its category imposes —
 * each with the icon that stands for it.
 *
 * The same icon marks the app in the list and titles its section in the app's own screen, so
 * the badge a parent scans past is the thing they then edit. That pairing is the whole point:
 * only restrictions with a section of their own belong here, which is why a category's budget
 * (edited in Limits, identical for every app in it) is deliberately not one of them.
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
