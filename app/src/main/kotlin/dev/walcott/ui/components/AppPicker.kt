package dev.walcott.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.AppInventory
import dev.walcott.ui.theme.Tokens

/** One app as a picker row: what to show, and what the caller gets back. */
data class PickableApp(val packageName: String, val label: String) {
    /** What the row prints: the app's name, or its package when nobody could resolve one. */
    val display: String get() = label.ifBlank { packageName }
}

/**
 * The one way this app asks "which app?".
 *
 * There were three of them, and they disagreed: the bonus dialog had icons and a search box, the
 * web filter's had icons and no way to find anything, and the notification log's had neither — a
 * column of bare names on a phone with sixty apps, which is a list you scroll rather than read.
 * The question is the same everywhere, so it looks the same everywhere now.
 *
 * Three details worth keeping:
 *
 *  - **The icon is the thing being recognised.** A name is how an app is written down; the icon is
 *    how it is known, and a picker without one makes the parent match text against a memory of a
 *    launcher. [AppIcon] falls back to a coloured monogram when the image has not arrived, which
 *    on a parent's phone is an ordinary state rather than a failure.
 *  - **The search appears when it earns its place** ([SEARCH_ABOVE]). Below that, scrolling is
 *    faster than typing, and a text field over five rows is furniture.
 *  - **It matches the package too, not only the label.** Rules and reports show package names, so
 *    a parent who has just read one and wants to act on it types what they saw.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<PickableApp>,
    inventory: AppInventory,
    onPick: (PickableApp) -> Unit,
    onDismiss: () -> Unit,
    /** Parent-side icon cache; null on a device where the apps are installed locally. */
    iconBytes: ((String) -> ByteArray?)? = null,
    /** Bumped when new icons arrive over sync, so rows redraw instead of staying monograms. */
    iconRefresh: Any? = null,
) {
    val spacing = Tokens.spacing
    var query by remember { mutableStateOf("") }
    val matches = remember(apps, query) { matching(apps, query) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = spacing.xxl)) {
            if (apps.size > SEARCH_ABOVE) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_app)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screen, vertical = spacing.sm),
                )
            }
            if (matches.isEmpty()) {
                Text(
                    stringResource(if (apps.isEmpty()) R.string.app_picker_none else R.string.app_picker_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.md),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(matches, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.display) },
                        leadingContent = {
                            AppIcon(
                                app.packageName,
                                inventory,
                                size = 36.dp,
                                remoteLoader = iconBytes,
                                refreshKey = iconRefresh,
                                label = app.display,
                            )
                        },
                        modifier = Modifier.clickable { onPick(app) },
                    )
                }
            }
        }
    }
}

/**
 * An app named the way it is recognised: its icon, then its name.
 *
 * For the places that GROUP by app rather than pick one — which domains an app resolved, which app
 * a notification came from. Same reasoning as the picker's rows: a column of bare names is a list
 * that gets scrolled rather than read, and on a parent's phone the icon is the only part of an app
 * they have actually seen.
 */
@Composable
fun AppHeading(
    packageName: String,
    label: String,
    inventory: AppInventory,
    modifier: Modifier = Modifier,
    iconBytes: ((String) -> ByteArray?)? = null,
    iconRefresh: Any? = null,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Tokens.spacing.sm),
    ) {
        AppIcon(
            packageName,
            inventory,
            size = size,
            remoteLoader = iconBytes,
            refreshKey = iconRefresh,
            label = label,
        )
        Text(
            label.ifBlank { packageName },
            style = style ?: MaterialTheme.typography.labelLarge,
        )
    }
}

/** Above this many apps the picker gets a search box; below it, scrolling beats typing. */
const val SEARCH_ABOVE = 7

/**
 * The items [query] names, by label or by package, case-insensitively. Blank asks for everything.
 *
 * Generic because the pickers hold different types — a child's installed apps, a parent's rows
 * built from what the children reported — and the rule for "does this match what I typed" must not
 * be one of the things they differ in. Its own function so it is testable, which matters most for
 * the half a reader would not assume: typing a package name works too, because rules and reports
 * show package names and somebody who has just read one types what they saw.
 */
fun <T> matching(items: List<T>, query: String, label: (T) -> String, packageName: (T) -> String): List<T> {
    val needle = query.trim()
    if (needle.isBlank()) return items
    return items.filter {
        label(it).contains(needle, ignoreCase = true) || packageName(it).contains(needle, ignoreCase = true)
    }
}

/** [matching] for the picker's own rows. */
fun matching(apps: List<PickableApp>, query: String): List<PickableApp> =
    matching(apps, query, label = { it.label }, packageName = { it.packageName })
