package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * Reviewing a selection of domains a child's monitor sent, and turning it into rules.
 *
 * Accepting is not a yes/no, which is why this is a screen and not a button: the same list of
 * domains means four different rules depending on reach, and the wrong reach is either a hole or a
 * family-wide block nobody asked for. So both questions are answered before anything is written,
 * with the narrow answer preselected — the monitor knows which app resolved these, and that
 * precision is the whole point of having watched.
 */
@Composable
fun DomainReviewScreen(viewModel: WalcottViewModel, batchId: String, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val requests by viewModel.domainRequests.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val labels by viewModel.installedLabels.collectAsStateWithLifecycle()
    val entry = requests.firstOrNull { it.batchId == batchId }

    // Answered (or the batch vanished): leave rather than sit on a dead screen.
    if (entry == null) {
        androidx.compose.runtime.LaunchedEffect(batchId) { onBack() }
        return
    }

    val domains = entry.domains().orEmpty()
    val keep = remember(batchId) { mutableStateMapOf<String, Boolean>() }
    val chosen = domains.filter { keep[it] != false }
    // Registry name if the child is registered, else whatever they called themselves. A child with
    // no registry entry can hold no overrides, so "only them" isn't offered.
    val registered = settings.children.firstOrNull { it.childId == entry.childId }
    val childName = registered?.name ?: entry.childName
    var familyWide by remember(batchId) { mutableStateOf(registered == null) }
    var anyApp by remember(batchId) { mutableStateOf(false) }
    val appLabel = labels[entry.packageName] ?: entry.label.ifBlank { entry.packageName }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.domain_review_title), onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.domain_review_from, childName, appLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.md, bottom = spacing.sm),
                )
            }
            item {
                WalcottCard {
                    Column(Modifier.padding(vertical = spacing.xs)) {
                        domains.forEach { domain ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = keep[domain] != false,
                                    onCheckedChange = { keep[domain] = it },
                                )
                                Text(domain, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.domain_review_scope_who)) }
            item {
                ChoiceRow(
                    first = stringResource(R.string.domain_review_scope_family),
                    second = stringResource(R.string.domain_review_scope_child, childName),
                    firstSelected = familyWide,
                    // A legacy device has no per-child slot to write into, so the choice is moot.
                    secondEnabled = registered != null,
                    onFirst = { familyWide = true },
                    onSecond = { familyWide = false },
                )
            }

            item { SectionHeader(stringResource(R.string.domain_review_scope_what)) }
            item {
                ChoiceRow(
                    first = if (entry.packageName.isBlank()) {
                        stringResource(R.string.domain_review_scope_app_generic)
                    } else {
                        stringResource(R.string.domain_review_scope_app, appLabel)
                    },
                    second = stringResource(R.string.domain_review_scope_any),
                    firstSelected = !anyApp,
                    secondEnabled = true,
                    onFirst = { anyApp = false },
                    onSecond = { anyApp = true },
                )
            }

            item {
                OutlinedButton(
                    onClick = { viewModel.discardDomainRequest(batchId); onBack() },
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                ) { Text(stringResource(R.string.domain_review_discard)) }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }

        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (chosen.isEmpty()) {
                Text(
                    stringResource(R.string.domain_review_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.lg),
                )
            } else {
                Button(
                    onClick = {
                        viewModel.applyDomainRules(batchId, chosen, familyWide, anyApp)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                ) {
                    Text(pluralStringResource(R.plurals.domain_review_apply, chosen.size, chosen.size))
                }
            }
        }
    }
}

/** Two mutually exclusive answers, side by side — the shape both scope questions take. */
@Composable
private fun ChoiceRow(
    first: String,
    second: String,
    firstSelected: Boolean,
    secondEnabled: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        ChoiceButton(first, firstSelected, enabled = true, onClick = onFirst, modifier = Modifier.weight(1f))
        ChoiceButton(second, !firstSelected, enabled = secondEnabled, onClick = onSecond, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
    }
}
