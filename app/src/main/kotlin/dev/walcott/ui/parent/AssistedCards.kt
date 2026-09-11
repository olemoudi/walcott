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
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.walcott.R
import dev.walcott.data.MemberKind
import dev.walcott.enforcement.LockScreen
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.RemoteAction
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.PinEntryField
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * The cards for supporting somebody's phone from a distance rather than limiting it: is the ringer
 * audible, can the lock be reset, and what has arrived on that phone.
 *
 * Offered for every member, adult or child (see [dev.walcott.data.MemberKind]) — a teenager's phone
 * on silent for two days is the same problem as a grandparent's. What the member's kind changes is
 * whether these are the FIRST thing on the screen or the last.
 *
 * Every card states what the device itself reports rather than what was asked of it. That is the
 * whole difference between a switch and support: "keep the ringer up" is a wish, and "this phone is
 * on silent right now, and it has done that eleven times this month" is something to act on.
 */

/** The icon that stands for a member everywhere they appear, so the two kinds read apart at a glance. */
fun memberIcon(kind: String): ImageVector =
    if (kind == MemberKind.ADULT) Icons.Outlined.SupportAgent else Icons.Outlined.Face

/**
 * The choice made once, at enrollment: whose phone this is.
 *
 * Two rows rather than a switch, because a switch implies one of them is the default state and the
 * other a modification, and these are two answers to the same question. The wording says what each
 * one DOES to the phone rather than naming a category — "a child" and "an adult" are ages, and the
 * thing being chosen is whether this phone gets limits or gets looked after.
 */
@Composable
fun MemberKindChooser(
    kind: String,
    onSelect: (String) -> Unit,
    onExplain: () -> Unit,
    /** A question while creating somebody, a statement once they exist. */
    label: String = stringResource(R.string.member_kind_question),
) {
    val spacing = Tokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        CardGroup {
            MemberKindRow(
                kind = MemberKind.CHILD,
                selected = kind != MemberKind.ADULT,
                title = stringResource(R.string.member_kind_child),
                description = stringResource(R.string.member_kind_child_desc),
                position = CardPosition.First,
                onSelect = onSelect,
            )
            MemberKindRow(
                kind = MemberKind.ADULT,
                selected = kind == MemberKind.ADULT,
                title = stringResource(R.string.member_kind_adult),
                description = stringResource(R.string.member_kind_adult_desc),
                position = CardPosition.Last,
                onSelect = onSelect,
            )
        }
        TextButton(onClick = onExplain) { Text(stringResource(R.string.member_kind_compare)) }
    }
}

@Composable
private fun MemberKindRow(
    kind: String,
    selected: Boolean,
    title: String,
    description: String,
    position: CardPosition,
    onSelect: (String) -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position, onClick = { onSelect(kind) }) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = { onSelect(kind) })
            Spacer(Modifier.width(spacing.xs))
            Icon(
                memberIcon(kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The long answer to "what changes?", one sheet away from the choice.
 *
 * It leads with what does NOT change, because that is the misreading this whole feature invites:
 * a parent who believes picking "adult" locks away the rules would pick "child" for a grandparent
 * and then be surprised by a bedtime, and one who believes the opposite would never look for the
 * ringer guard on a teenager's phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberKindSheet(onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = spacing.screen).padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(stringResource(R.string.member_kind_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.member_kind_sheet_intro), style = MaterialTheme.typography.bodyMedium)
            KindParagraph(
                icon = memberIcon(MemberKind.CHILD),
                title = stringResource(R.string.member_kind_sheet_child_title),
                body = stringResource(R.string.member_kind_sheet_child_body),
            )
            KindParagraph(
                icon = memberIcon(MemberKind.ADULT),
                title = stringResource(R.string.member_kind_sheet_adult_title),
                body = stringResource(R.string.member_kind_sheet_adult_body),
            )
            Text(
                stringResource(R.string.member_kind_sheet_shared_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Text(
                stringResource(R.string.member_kind_sheet_shared_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindParagraph(icon: ImageVector, title: String, body: String) {
    val spacing = Tokens.spacing
    Row(Modifier.padding(top = spacing.sm)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The way back from a wrong pick, long after enrollment.
 *
 * Changing the kind moves what this screen leads with and what the guided setup asks; it does NOT
 * re-apply the other kind's defaults. Re-applying them would silently switch rules and locks on a
 * phone somebody has already configured by hand, which is the one thing a row this small must
 * never do — and the note says so, because "you can change this" reads as "this will change
 * things" unless it is denied out loud.
 */
@Composable
fun MemberKindCard(kind: String, onSelect: (String) -> Unit) {
    val spacing = Tokens.spacing
    var explaining by remember { mutableStateOf(false) }
    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            MemberKindChooser(
                kind = kind,
                onSelect = onSelect,
                onExplain = { explaining = true },
                label = stringResource(R.string.member_kind_row_title),
            )
            Text(
                stringResource(R.string.member_kind_row_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (explaining) MemberKindSheet(onDismiss = { explaining = false })
}

/** Whether any of these is worth showing yet: a device that has never checked in has nothing to say. */
@Composable
fun RingerCard(
    snapshot: ChildSnapshot?,
    keepAudible: Boolean,
    onToggle: (Boolean) -> Unit,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ringer_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.ringer_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Switch(checked = keepAudible, onCheckedChange = onToggle)
            }
            if (snapshot != null) {
                // What the phone says, not what the switch says. A silenced phone with the guard ON
                // means something else is holding the ringer down, and that is the sentence below.
                val audible = snapshot.ringerAudible
                Text(
                    stringResource(if (audible) R.string.ringer_state_audible else R.string.ringer_state_silent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (audible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                if (snapshot.ringerDndSilencing) {
                    Text(
                        stringResource(R.string.ringer_dnd_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (snapshot.ringerRestores > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.ringer_restores,
                            snapshot.ringerRestores,
                            snapshot.ringerRestores,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The lock screen: whether it can be reset from here, and the two things to do about it.
 *
 * The state line is the point of the card. A family only ever needs this on the day somebody is
 * locked out, and on that day it is too late to arm it — so the card says, on every ordinary day,
 * whether the escape hatch is ready, and what makes it ready (the person unlocking their own phone
 * once).
 */
@Composable
fun LockScreenCard(
    snapshot: ChildSnapshot?,
    lastPin: String,
    onSetPin: (String) -> Unit,
    onRemoveLock: () -> Unit,
    onLockNow: () -> Unit,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    var asking by remember { mutableStateOf(false) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    val ready = snapshot?.lockResetReady == true

    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (ready) R.string.lock_ready else R.string.lock_not_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            if (lastPin.isNotBlank()) {
                // Shown, not hidden: the whole point is being able to read it back to somebody who
                // cannot get into their phone. It lives on this phone alone (see SyncState).
                Text(
                    stringResource(R.string.lock_last_pin, lastPin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { asking = true },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.lock_set_pin)) }
            OutlinedButton(
                onClick = { confirmingRemoval = true },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.lock_remove)) }
            OutlinedButton(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock_now))
            }
        }
    }

    if (asking) {
        SetLockPinDialog(
            onDismiss = { asking = false },
            onConfirm = { pin ->
                asking = false
                onSetPin(pin)
            },
        )
    }
    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.lock_remove)) },
            text = { Text(stringResource(R.string.lock_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemoval = false
                    onRemoveLock()
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Asks for the new PIN, and refuses anything that cannot be read down a phone line. */
@Composable
private fun SetLockPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val valid = LockScreen.isValidPin(pin)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_set_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                Text(stringResource(R.string.lock_set_pin_hint))
                // Shown rather than hidden: the parent is about to read these digits out loud
                // to the person whose phone it is, and this screen is the only place they exist.
                PinEntryField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.lock_pin_label),
                    slots = 4,
                    maxLength = 8,
                    autoFocus = true,
                    masked = false,
                    onImeAction = { if (valid) onConfirm(pin) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(pin) }) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The notification log: the switch, what the phone says about it, and the way in.
 *
 * The switch is worded as what it is — a recording of somebody's messages — because it is the one
 * setting in this app that a family should hesitate over, and a screen that makes it feel routine
 * would be doing them a disservice.
 */
@Composable
fun NotificationLogCard(
    snapshot: ChildSnapshot?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notiflog_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.notiflog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled && snapshot != null && !snapshot.notificationAccess) {
                // The one failure this feature has: a permission only the phone's owner can grant.
                Text(
                    stringResource(R.string.notiflog_no_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (enabled) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = spacing.xs)) {
                    Text(stringResource(R.string.notiflog_open))
                }
            }
        }
    }
}

/**
 * Finding the phone: ring it, or lock it down and keep it reporting (see [RemoteAction.RING_NOW]
 * and [RemoteAction.LOST_MODE]).
 *
 * The state line reads what the PHONE says ([ChildSnapshot.lostMode]) and says "asked for" in
 * between: a command still in the queue is a phone that is not yet locked, and on the day this
 * card is used the difference is the whole question. The line on the lock screen is what this
 * phone asked for ([asked]) — it never travels back.
 */
@Composable
fun FindPhoneCard(
    snapshot: ChildSnapshot?,
    /** The lock-screen line this phone asked for, while lost mode is asked for; null otherwise. */
    asked: String?,
    onRing: () -> Unit,
    onLost: (message: String) -> Unit,
    onFound: () -> Unit,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    var askingLost by remember { mutableStateOf(false) }
    val supported = snapshot != null && RemoteAction.canFind(snapshot.appVersionCode)
    val lost = snapshot?.lostMode == true
    // Ticked rather than read off the clock while drawing. This card's inputs only change when a
    // snapshot lands, so a deadline compared during composition would keep saying "reporting its
    // position" for as long as nothing else happened — the same freeze the map's own countdown
    // was fixed for. Alive only while there is something to count.
    val until = snapshot?.liveTrackingUntilMs ?: 0L
    val tracking by produceState(initialValue = until > System.currentTimeMillis(), until) {
        while (until > System.currentTimeMillis()) {
            value = true
            delay(TRACKING_TICK_MS)
        }
        value = false
    }

    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.find_title), style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    lost && !asked.isNullOrBlank() -> stringResource(R.string.lost_on_line, asked)
                    lost -> stringResource(R.string.lost_on_line_plain)
                    asked != null -> stringResource(R.string.lost_asked_line)
                    else -> stringResource(R.string.lost_off_line)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (lost || asked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (lost) {
                Text(
                    stringResource(if (tracking) R.string.lost_tracking_line else R.string.lost_tracking_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRing, enabled = supported, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.find_ring))
            }
            if (lost || asked != null) {
                OutlinedButton(onClick = onFound, enabled = supported, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lost_disable))
                }
            } else {
                OutlinedButton(onClick = { askingLost = true }, enabled = supported, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lost_enable))
                }
            }
            if (snapshot != null && !supported) {
                Text(
                    stringResource(R.string.find_needs_update),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (askingLost) {
        LostModeDialog(
            onDismiss = { askingLost = false },
            onConfirm = { message ->
                askingLost = false
                onLost(message)
            },
        )
    }
}

/**
 * What lost mode does and the one thing it needs from the parent: the line a finder reads on the
 * lock screen. Prefilled with the shape of a sentence and left for them to finish with a number,
 * because the app does not know theirs and must not guess.
 */
@Composable
fun LostModeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val template = stringResource(R.string.lost_message_default)
    var message by remember { mutableStateOf(template) }
    val valid = message.isNotBlank() && message.trim() != template.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lost_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                Text(stringResource(R.string.lost_dialog_body))
                OutlinedTextField(
                    value = message,
                    onValueChange = { entered -> message = entered.take(RemoteAction.LOST_MESSAGE_MAX_CHARS) },
                    minLines = 2,
                    label = { Text(stringResource(R.string.lost_message_label)) },
                    supportingText = { Text(stringResource(R.string.lost_message_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(message.trim()) }) {
                Text(stringResource(R.string.lost_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** How often the find card re-asks whether a lost phone's tracking session is still running. */
private const val TRACKING_TICK_MS = 30_000L
