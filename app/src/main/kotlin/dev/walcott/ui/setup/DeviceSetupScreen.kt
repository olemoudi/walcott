package dev.walcott.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.setup.DeviceRequirement
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * The permanent record behind the home-screen nudges: every requirement, its real state, and a
 * way to act on it — including the ones that were hidden.
 *
 * This is what makes dismissing a nudge safe to offer at all. "Not now" removes an interruption
 * from a screen someone uses every day; it must not remove the information, or the app quietly
 * becomes one that stopped enforcing rules and never mentioned it again.
 */
@Composable
fun DeviceSetupScreen(handle: DeviceSetupHandle, onOpenJourney: () -> Unit, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val unmet = handle.unmet
    val hidden = unmet.filter { it.key in handle.dismissed }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.device_setup_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen).padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (unmet.isEmpty()) {
                AllGoodCard()
            } else {
                Text(
                    stringResource(R.string.device_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Anything hidden from the home screen still appears here, marked as hidden and
                // with the switch that brings it back.
                SetupNudgeCards(handle)
                hidden.forEach { requirement ->
                    HiddenRequirementCard(requirement, onRestore = { handle.restore(requirement) })
                }
            }
            // The guided run, offered again from the one screen that already holds the whole
            // list: a phone that was set up months ago and has drifted is exactly the case
            // where walking it end to end beats hunting for the card that matters.
            TextButton(onClick = onOpenJourney) {
                Text(stringResource(R.string.device_setup_run_journey))
            }
        }
    }
}

@Composable
private fun AllGoodCard() {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(
                    stringResource(R.string.device_setup_all_good),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.device_setup_all_good_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun HiddenRequirementCard(requirement: DeviceRequirement, onRestore: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(requirement),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(requirement.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.device_setup_hidden_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(requirement.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            )
            TextButton(onClick = onRestore, modifier = Modifier.padding(top = spacing.xs)) {
                Text(stringResource(R.string.device_setup_restore))
            }
        }
    }
}
