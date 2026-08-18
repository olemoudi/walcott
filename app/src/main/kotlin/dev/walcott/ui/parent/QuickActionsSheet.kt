package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.rules.ExtraTime
import dev.walcott.rules.nightOf
import dev.walcott.sync.SyncEngine
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.LocalSnackbar
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** The pause lengths offered, in minutes: "come to the table", "do your homework", "that's enough". */
private val PAUSE_MINUTES = listOf(15, 30, 60)

/** How much later bedtime can be, in minutes. Beyond an hour is a night, not a late night. */
private val BEDTIME_DELAYS = listOf(30, 60)

/** How much time the sheet hands out in one tap. */
private val BONUS_MINUTES = listOf(15, 30, 60)

/**
 * The four things a parent does to a phone in the middle of an ordinary day, one tap from the
 * home: give minutes, pause it, move tonight's bedtime, find it.
 *
 * All of them existed and none of them was reachable in fewer than four taps — a bonus lived
 * inside the child's page, under a card about today's usage; a pause and a late bedtime did not
 * exist at all, and were done by editing a standing rule and remembering to put it back. What
 * they have in common is that they are decided in seconds, usually with the child in the room,
 * which is exactly the situation in which nobody goes looking through a settings tree.
 *
 * Deliberately small: everything here is instantaneous and undoable, so it needs no confirming
 * and no explaining. Anything that needs either belongs on the member's own page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickActionsSheet(
    viewModel: WalcottViewModel,
    childId: String,
    onDismiss: () -> Unit,
    /** Offered only where it goes somewhere new — not on the member's own page. */
    onOpenDetail: (() -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val sheetState = rememberModalBottomSheetState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val entry = settings.children.firstOrNull { it.childId == childId } ?: return
    val snapshot = snapshots.firstOrNull { it.childId == childId }
    val exception = entry.overrides.todayException

    // The member's own rules, so the bedtime row can say what tonight looks like as it stands.
    val now = LocalDateTime.now()
    val config = remember(settings, childId) {
        settings.resolveForChild(childId).toFamilyConfig(emptySet())
    }
    val bedtimeTonight = config.bedtimeAt(now)
    // The night the buttons below would change — the one the member is in, which after midnight
    // is yesterday's (see nightOf). Taken from the RULE rather than from what is left of it: a
    // bedtime already lifted tonight answers null, and dating the exception from that puts it on
    // tomorrow night, which is how the row for putting it back disappears at 00:01.
    val night = config.scheduledBedtimeAt(now)?.nightOf(now) ?: now.toLocalDate()
    val bedtimeChanged = exception != null &&
        exception.bedtimeNightEpochDay == night.toEpochDay() &&
        (exception.bedtimeOff || exception.bedtimeDelayMinutes > 0)
    val pausedUntilMs = exception?.pauseUntilMs?.takeIf { it > System.currentTimeMillis() }

    // An older build decodes the policy and simply ignores what it does not know, so the pause
    // would be sent, acknowledged and never applied. Say so instead.
    val understandsExceptions = snapshot == null ||
        SyncEngine.appliesTodayException(snapshot.appVersionCode)

    // Every action here closes the sheet. Slide it out rather than deleting it from under the
    // finger that tapped: the confirmation lands as the sheet leaves, which is one movement, and
    // a panel that vanishes mid-tap reads as a mis-tap even when it worked.
    fun done(message: String, undoLabel: String? = null, onUndo: (() -> Unit)? = null) {
        snackbar.show(message, undoLabel, onUndo)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Resolved here rather than inside the taps: every one of these closes the sheet, and the
    // sentence it leaves behind has to be built while there is still a composition to build it in.
    val undo = stringResource(R.string.action_undo)
    val locating = stringResource(R.string.locate_in_progress)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = spacing.lg).padding(bottom = spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(entry.name, style = MaterialTheme.typography.headlineSmall)

            if (snapshot == null) {
                Text(
                    stringResource(R.string.device_not_linked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // --- More time, right now ---
                QuickRow(Icons.Outlined.MoreTime, stringResource(R.string.quick_give_time)) {
                    BONUS_MINUTES.forEach { minutes ->
                        val label = stringResource(R.string.quick_plus_minutes, minutes)
                        val said = stringResource(R.string.quick_gave_time, minutes, entry.name)
                        ActionChip(label) {
                            viewModel.giveBonus(snapshot.deviceId, ExtraTime.ALL_APPS, minutes)
                            done(said)
                        }
                    }
                }

                // --- Pause ---
                QuickRow(
                    Icons.Outlined.PauseCircle,
                    stringResource(R.string.quick_pause),
                    detail = pausedUntilMs?.let {
                        stringResource(
                            R.string.quick_paused_until,
                            java.time.Instant.ofEpochMilli(it)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalTime().hhmm(),
                        )
                    },
                ) {
                    if (pausedUntilMs != null) {
                        val resumed = stringResource(R.string.quick_resumed, entry.name)
                        ActionChip(stringResource(R.string.quick_resume), enabled = understandsExceptions) {
                            viewModel.resumeChild(childId)
                            done(resumed)
                        }
                    } else {
                        PAUSE_MINUTES.forEach { minutes ->
                            val label = stringResource(R.string.quick_minutes, minutes)
                            val said = stringResource(R.string.quick_paused, entry.name, minutes)
                            ActionChip(label, enabled = understandsExceptions) {
                                viewModel.pauseChild(childId, minutes)
                                done(said, undo) { viewModel.resumeChild(childId) }
                            }
                        }
                    }
                }

                // --- Tonight's bedtime. Only where there is one to move, or one already moved. ---
                if (config.scheduledBedtimeAt(now) != null || bedtimeChanged) {
                    QuickRow(
                        Icons.Filled.Bedtime,
                        stringResource(R.string.quick_bedtime_tonight),
                        detail = bedtimeTonight?.start?.hhmm()
                            ?: stringResource(R.string.quick_bedtime_lifted),
                    ) {
                        if (bedtimeChanged) {
                            val restored = stringResource(R.string.quick_bedtime_restored, entry.name)
                            ActionChip(
                                stringResource(R.string.quick_bedtime_restore),
                                enabled = understandsExceptions,
                            ) {
                                viewModel.setBedtimeTonight(childId, 0, off = false)
                                done(restored)
                            }
                        } else {
                            BEDTIME_DELAYS.forEach { minutes ->
                                val label = stringResource(R.string.quick_plus_minutes, minutes)
                                val said = stringResource(R.string.quick_bedtime_delayed, entry.name, minutes)
                                ActionChip(label, enabled = understandsExceptions) {
                                    viewModel.setBedtimeTonight(childId, minutes, off = false)
                                    done(said, undo) { viewModel.setBedtimeTonight(childId, 0, off = false) }
                                }
                            }
                            val liftedSaid = stringResource(R.string.quick_bedtime_off_done, entry.name)
                            ActionChip(
                                stringResource(R.string.quick_bedtime_off),
                                enabled = understandsExceptions,
                            ) {
                                viewModel.setBedtimeTonight(childId, 0, off = true)
                                done(liftedSaid, undo) { viewModel.setBedtimeTonight(childId, 0, off = false) }
                            }
                        }
                    }
                }

                if (!understandsExceptions) {
                    Text(
                        stringResource(R.string.quick_needs_update),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (snapshot != null) {
                    TextButton(onClick = {
                        viewModel.requestLocation(snapshot.deviceId)
                        done(locating)
                    }) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, Modifier.size(18.dp))
                        Text("  " + stringResource(R.string.locate_now))
                    }
                }
                onOpenDetail?.let { open ->
                    TextButton(onClick = {
                        onDismiss()
                        open()
                    }) {
                        Text(stringResource(R.string.quick_open_detail))
                    }
                }
            }
        }
    }
}

/** One labelled line of the sheet: what this is, how it stands, and the taps that change it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String? = null,
    chips: @Composable () -> Unit,
) {
    val spacing = Tokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "  " + title,
                style = MaterialTheme.typography.titleSmall,
            )
            detail?.let {
                Text(
                    "  · $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) { chips() }
    }
}

/** A chip that DOES something, sized for a thumb rather than for a form. */
@Composable
private fun ActionChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        modifier = Modifier.heightIn(min = 44.dp),
    )
}
