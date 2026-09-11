package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.RescueCode
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.ActionChip
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Six digits that open a child's phone with no network at all (see [RescueCode]).
 *
 * The door this is behind matters. Everything else a parent can do arrives over the relay, and
 * when there is none — no data, a phone abroad, a server having a bad day, or a child failing
 * closed with nothing left that opens — the only vocabulary was the parent's PIN, typed into the
 * child's phone in front of them. That PIN releases the device for ever; spending it to buy an
 * hour is spending the wrong thing.
 *
 * So it is read out loud instead: nothing is sent, nothing needs to arrive, and the code is worth
 * one use in one half hour. Deliberately shown WITHOUT any countdown of its own beyond the slot,
 * because the honest sentence is "read this out in the next few minutes" and not "this expires
 * at 18:30 and everything will be fine until then".
 */
@Composable
internal fun RescueCodeCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    var action by remember { mutableStateOf(RescueCode.ACTION_OPEN_1H) }
    var showing by remember { mutableStateOf(false) }
    // Its own clock: the code turns over on a half-hour boundary nothing else here cares about,
    // and a card showing a code that stopped working two minutes ago is worse than no card.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Whose phone: the code is bound to one device (see RescueCode.codeFor), so a family with
    // several children picks one. A child too old to bind still answers to the family-wide code.
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val phones = syncState.children
    var pickedDeviceId by remember { mutableStateOf<String?>(null) }
    val picked = phones.firstOrNull { it.deviceId == pickedDeviceId } ?: phones.firstOrNull()
    val codeDeviceId = picked?.takeIf { RescueCode.bindsToDevice(it.appVersionCode) }?.deviceId ?: ""
    val code = remember(action, RescueCode.slotOf(nowMs), codeDeviceId) {
        viewModel.rescueCodeNow(action, nowMs, codeDeviceId)
    }

    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rescue_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rescue_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!showing) {
                TextButton(onClick = { showing = true }, modifier = Modifier.padding(top = spacing.sm)) {
                    Text(stringResource(R.string.rescue_show))
                }
                return@Column
            }

            Spacer(Modifier.size(spacing.md))
            if (phones.isEmpty()) {
                Text(
                    stringResource(R.string.rescue_no_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            if (phones.size > 1) {
                Text(stringResource(R.string.rescue_pick_member), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    phones.forEach { phone ->
                        ActionChip(phone.displayName, enabled = phone.deviceId != picked?.deviceId) {
                            pickedDeviceId = phone.deviceId
                        }
                    }
                }
                Spacer(Modifier.size(spacing.sm))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                RescueCode.ACTIONS.forEach { candidate ->
                    ActionChip(
                        stringResource(actionLabel(candidate)),
                        enabled = candidate != action,
                    ) { action = candidate }
                }
            }
            Spacer(Modifier.size(spacing.md))
            if (code == null) {
                Text(
                    stringResource(R.string.rescue_no_family),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            val (digits, slotEndsAt) = code
            Text(
                // Spaced in threes: this is read out over a phone line to somebody upset.
                digits.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            val leftMinutes = ((slotEndsAt - nowMs) / 60_000L).coerceAtLeast(0)
            Text(
                stringResource(R.string.rescue_changes_in, leftMinutes + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.rescue_once),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

private fun actionLabel(action: String): Int = when (action) {
    RescueCode.ACTION_OPEN_1H -> R.string.rescue_action_1h
    RescueCode.ACTION_OPEN_3H -> R.string.rescue_action_3h
    else -> R.string.rescue_action_install
}
