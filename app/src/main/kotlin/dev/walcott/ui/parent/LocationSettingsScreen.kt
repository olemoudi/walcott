package dev.walcott.ui.parent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * Family-wide location defaults: how often every child device reports, and whether the
 * 48h history is kept. Individual children inherit these unless their detail screen
 * switches on a per-child customization.
 */
@Composable
fun LocationSettingsScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_location_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                stringResource(R.string.location_family_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(spacing.lg)) {
                    Text(stringResource(R.string.tracking_periodic_title), style = MaterialTheme.typography.titleSmall)
                    TrackingIntervalChips(
                        selected = settings.trackingIntervalMinutes,
                        onSelect = { viewModel.setFamilyTrackingInterval(it) },
                    )
                    Text(
                        stringResource(R.string.tracking_battery_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.location_history_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.location_history_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(spacing.sm))
                        Switch(
                            checked = settings.locationHistoryEnabled,
                            onCheckedChange = { viewModel.setFamilyLocationHistory(it) },
                        )
                    }
                }
            }
        }
    }
}

/** Interval chip row shared by the family defaults and the per-child override editor. */
@Composable
fun TrackingIntervalChips(selected: Int, onSelect: (Int) -> Unit) {
    val spacing = Tokens.spacing
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        TRACKING_INTERVALS.forEach { m ->
            FilterChip(
                selected = m == selected,
                onClick = { onSelect(m) },
                label = {
                    Text(
                        if (m == 0) stringResource(R.string.tracking_off)
                        else stringResource(R.string.tracking_minutes_fmt, m),
                    )
                },
            )
        }
    }
}

internal val TRACKING_INTERVALS = listOf(0, 5, 15, 30, 60)
