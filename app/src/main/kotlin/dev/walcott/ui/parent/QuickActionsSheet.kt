package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.sync.RemoteAction
import dev.walcott.R
import dev.walcott.ui.components.MinutesPickerDialog
import dev.walcott.ui.components.TimePickerDialog
import dev.walcott.rules.ExtraTime
import dev.walcott.rules.nightOf
import dev.walcott.sync.LiveTracking
import dev.walcott.sync.SyncEngine
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.ActionChip
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.CustomValueChip
import dev.walcott.ui.components.LocalSnackbar
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** The pause lengths offered, in minutes: "come to the table", "do your homework", "that's enough". */
private val PAUSE_MINUTES = listOf(15, 30, 60)

/** How much later bedtime can be, in minutes. Beyond an hour is a night, not a late night. */
private val BEDTIME_DELAYS = listOf(30, 60)

/**
 * Bedtime EARLIER tonight, in minutes. The other half of the same answer: "half an hour more,
 * it's a Friday" was expressible and "bed half an hour early, you were up all night" was not,
 * and the only way to say the second was to edit the standing rule and remember to put it back.
 */
private val BEDTIME_EARLIER = listOf(30, 60)

/**
 * The latest a "until I say so" pause may run to, as an hour of the morning.
 *
 * A pause with no end is a phone somebody has to remember to give back, and the night it gets
 * forgotten is the night it becomes a rule nobody wrote. Six in the morning is late enough that
 * nothing in the evening escapes it and early enough that a forgotten pause has ended before
 * anybody needs the phone.
 */
private const val OPEN_PAUSE_ENDS_AT_HOUR = 6

/** How much time the sheet hands out in one tap. */
private val BONUS_MINUTES = listOf(15, 30, 60)

/** How long a parent can let a phone install anything for: a quick setup, and a long one. */
private val INSTALL_WINDOW_MINUTES = listOf(30, 120)

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
        (exception.bedtimeOff || exception.bedtimeDelayMinutes != 0)
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

    // The only thing on this sheet that asks before it acts. Everything else here is instant and
    // undoable, which is why none of it confirms; close tracking is neither — it holds the
    // child's phone awake and drinks its battery, so the parent is told the price first.
    var askLive by remember { mutableStateOf(false) }
    var askLost by remember { mutableStateOf(false) }
    // The two that ask for a number before they act. Neither confirms anything — they are the
    // same instant, undoable actions as the chips beside them, with the value typed in.
    var askPauseUntil by remember { mutableStateOf(false) }
    var askBedtimeMinutes by remember { mutableStateOf(false) }
    // A child too old to understand a NEGATIVE delay reads it as no change at all, which is a
    // bedtime the parent moved earlier and a phone that did not (see RemoteAction).
    val understandsEarlierBedtime = understandsExceptions &&
        (snapshot == null || RemoteAction.canBedtimeEarlier(snapshot.appVersionCode))

    // Installs. What the phone reports is the truth — it already leaves out the nightly update
    // hour, which is not the parent's to close — and what is still on its way there makes the
    // card appear on the tap rather than a round trip later.
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val queuedForPhone = snapshot?.let { s -> syncState.commands.filter { it.deviceId == s.deviceId } }.orEmpty()
    val installsOpenUntilMs = snapshot?.installExemptionUntilMs?.takeIf { it > System.currentTimeMillis() }
    val installsOpening = queuedForPhone.any { it.action == RemoteAction.ALLOW_INSTALLS }
    val installsClosing = installsOpenUntilMs != null &&
        queuedForPhone.any { it.action == RemoteAction.REAPPLY_POLICY }
    val understandsInstallWindow = snapshot == null || RemoteAction.canAllowInstalls(snapshot.appVersionCode)
    // Offered only where installing is held back at all — blocked, or watched by the install guard.
    // On a family that does neither there is nothing to open, and a button that does nothing
    // teaches that the ones beside it might not do anything either.
    val installsHeld = remember(settings, childId) {
        val resolved = settings.resolveForChild(childId)
        dev.walcott.enforcement.DeviceRestrictions.KEY_INSTALLS in resolved.deviceRestrictions ||
            dev.walcott.enforcement.AppUpdates.modeOf(resolved.installMode) ==
            dev.walcott.enforcement.AppUpdates.MODE_GUARDED
    }
    val installsClosedSaid = stringResource(R.string.quick_installs_closed, entry.name)
    val lostAskedAll by viewModel.lostModeAsked.collectAsStateWithLifecycle()
    // The template rather than the finished sentence, because the duration is only known at the
    // tap — resolved up here like `undo` and `locating` for the same reason they are.
    val liveStartedFmt = stringResource(R.string.quick_live_started)
    if (askLost && snapshot != null) {
        val lostAsked = stringResource(R.string.lost_done, entry.name)
        LostModeDialog(
            onDismiss = { askLost = false },
            onConfirm = { message ->
                askLost = false
                viewModel.setChildLostMode(snapshot.deviceId, true, message)
                done(lostAsked)
            },
        )
    }
    if (askPauseUntil) {
        val pausedFmt = stringResource(R.string.quick_paused_until_done)
        TimePickerDialog(
            title = stringResource(R.string.quick_pause_until_title),
            // An hour from now rather than the current time: a picker that opens on a moment
            // already past would take a tap to become a pause at all.
            initial = now.toLocalTime().plusHours(1).withSecond(0).withNano(0),
            onDismiss = { askPauseUntil = false },
            onConfirm = { at ->
                askPauseUntil = false
                // The NEXT time it is that hour: "until 21:00" typed at half past nine means
                // tomorrow evening to nobody, and this evening to everybody.
                val until = nextOccurrenceOf(now, at)
                viewModel.pauseChildUntil(childId, until)
                done(String.format(pausedFmt, entry.name, at.hhmm()), undo) { viewModel.resumeChild(childId) }
            },
        )
    }
    if (askBedtimeMinutes) {
        val delayedFmt = stringResource(R.string.quick_bedtime_delayed)
        MinutesPickerDialog(
            title = stringResource(R.string.quick_bedtime_custom_title),
            initial = 45,
            minValue = 5,
            maxValue = 4 * 60,
            onDismiss = { askBedtimeMinutes = false },
            onConfirm = { minutes ->
                askBedtimeMinutes = false
                viewModel.setBedtimeTonight(childId, minutes, off = false)
                done(String.format(delayedFmt, entry.name, minutes), undo) {
                    viewModel.setBedtimeTonight(childId, 0, off = false)
                }
            },
        )
    }
    if (askLive && snapshot != null) {
        val interval = remember(settings, childId) {
            settings.resolveForChild(childId).trackingIntervalMinutes
        }
        LiveTrackingDialog(
            name = entry.name,
            ordinaryIntervalMinutes = interval,
            drain = snapshot.batteryDrain,
            onDismiss = { askLive = false },
            onConfirm = { minutes ->
                askLive = false
                viewModel.setLiveTracking(snapshot.deviceId, minutes)
                done(
                    String.format(
                        liveStartedFmt,
                        entry.name,
                        java.time.Duration.ofMinutes(minutes.toLong()).humanize(),
                    ),
                )
            },
        )
    }

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
                // --- Installs open: the way to close them comes first ---
                // On top because it is the one state on this sheet that should not be left running
                // by accident, and a parent who opened installs for a setup and was then called
                // away is exactly who opens this sheet again later, for something else.
                if (installsOpenUntilMs != null || installsOpening) {
                    InstallsOpenCard(
                        untilMs = installsOpenUntilMs,
                        closing = installsClosing,
                        onClose = {
                            viewModel.closeInstallsOn(snapshot.deviceId)
                            done(installsClosedSaid)
                        },
                    )
                }

                // Minutes and pauses are answers to limits, and an adult being helped has none unless
                // somebody set them on purpose — in which case their page is where that lives.
                if (!entry.isAdult) {
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
                            // "Until dinner is over", "until we get home" — the answers that are an
                            // hour rather than a duration, and that a parent would otherwise have to
                            // do the subtraction for.
                            ActionChip(stringResource(R.string.quick_pause_until), enabled = understandsExceptions) {
                                askPauseUntil = true
                            }
                            // The open-ended one, which is still not open-ended: it ends at
                            // OPEN_PAUSE_ENDS_AT_HOUR whatever happens, because a pause nobody
                            // remembers to lift is a rule nobody wrote.
                            val openSaid = stringResource(R.string.quick_paused_open, entry.name)
                            ActionChip(stringResource(R.string.quick_pause_open), enabled = understandsExceptions) {
                                viewModel.pauseChildUntil(childId, nextMorning(now, OPEN_PAUSE_ENDS_AT_HOUR))
                                done(openSaid, undo) { viewModel.resumeChild(childId) }
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
                            // Earlier first, then later: the same row reads as one axis with
                            // tonight's usual hour in the middle of it.
                            BEDTIME_EARLIER.forEach { minutes ->
                                val label = stringResource(R.string.quick_minus_minutes, minutes)
                                val said = stringResource(R.string.quick_bedtime_earlier_done, entry.name, minutes)
                                ActionChip(label, enabled = understandsEarlierBedtime) {
                                    viewModel.setBedtimeTonight(childId, -minutes, off = false)
                                    done(said, undo) { viewModel.setBedtimeTonight(childId, 0, off = false) }
                                }
                            }
                            BEDTIME_DELAYS.forEach { minutes ->
                                val label = stringResource(R.string.quick_plus_minutes, minutes)
                                val said = stringResource(R.string.quick_bedtime_delayed, entry.name, minutes)
                                ActionChip(label, enabled = understandsExceptions) {
                                    viewModel.setBedtimeTonight(childId, minutes, off = false)
                                    done(said, undo) { viewModel.setBedtimeTonight(childId, 0, off = false) }
                                }
                            }
                            // Anything else: the two chips cover most nights and not the one
                            // where the film ends at a quarter past.
                            ActionChip(
                                stringResource(R.string.quick_bedtime_custom),
                                enabled = understandsExceptions,
                            ) { askBedtimeMinutes = true }
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

                // --- Installs, for setting a phone up ---
                // Below the everyday answers on purpose: this is a setup afternoon, not a Tuesday.
                if (installsHeld) {
                    QuickRow(
                        Icons.Outlined.InstallMobile,
                        stringResource(R.string.quick_installs_title),
                        detail = installsOpenUntilMs?.let {
                            stringResource(R.string.quick_installs_until, localTimeOf(it).hhmm())
                        },
                    ) {
                        INSTALL_WINDOW_MINUTES.forEach { minutes ->
                            // The same chip words as the rows above ("30 min"), not the compact
                            // duration ("30m"), which read as a different kind of button beside them.
                            val label = if (minutes % 60 == 0) {
                                stringResource(R.string.quick_hours, minutes / 60)
                            } else {
                                stringResource(R.string.quick_minutes, minutes)
                            }
                            val said = stringResource(R.string.quick_installs_opened, entry.name, label)
                            ActionChip(label, enabled = understandsInstallWindow) {
                                viewModel.allowInstallsOn(snapshot.deviceId, minutes)
                                done(said, undo) { viewModel.closeInstallsOn(snapshot.deviceId) }
                            }
                        }
                    }
                    Text(
                        stringResource(
                            if (understandsInstallWindow) R.string.quick_installs_hint
                            else R.string.quick_installs_needs_update,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // --- Catch up: re-adopt the rules, and take a new build if there is one. ---
                val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()
                val rulesBehind = snapshot.appliedPolicyVersion in 1 until parentVersion
                val buildBehind = snapshot.appVersionCode in 1 until dev.walcott.BuildConfig.VERSION_CODE
                QuickRow(
                    Icons.Outlined.Sync,
                    stringResource(R.string.quick_catch_up_title),
                    // Says what is actually behind rather than implying something is. Most of the
                    // time nothing is: rules arrive by push in about a second, and the phone
                    // checks for a build on its own every half hour.
                    detail = when {
                        rulesBehind && buildBehind -> stringResource(R.string.quick_catch_up_both)
                        rulesBehind -> stringResource(R.string.quick_catch_up_rules)
                        buildBehind -> stringResource(R.string.quick_catch_up_build)
                        else -> stringResource(R.string.quick_catch_up_current)
                    },
                ) {
                    val asked = stringResource(R.string.quick_catch_up_asked, entry.name)
                    ActionChip(stringResource(R.string.quick_catch_up)) {
                        viewModel.forceCatchUp(snapshot.deviceId)
                        done(asked)
                    }
                }

                // --- Close tracking. The one thing here that asks before it acts. ---
                val liveUntilMs = snapshot.liveTrackingUntilMs.takeIf { it > System.currentTimeMillis() }
                val understandsLive = LiveTracking.isSupported(snapshot.appVersionCode)
                QuickRow(
                    Icons.Outlined.MyLocation,
                    stringResource(R.string.quick_live_title),
                    detail = liveUntilMs?.let {
                        stringResource(
                            R.string.quick_live_left,
                            java.time.Duration.ofMillis(it - System.currentTimeMillis()).humanize(),
                        )
                    },
                ) {
                    if (liveUntilMs != null) {
                        val stopped = stringResource(R.string.quick_live_stopped)
                        // Half an hour more without asking again: the price was accepted when the
                        // session started, and a parent tapping this is watching a phone move.
                        val asked = LiveTracking.extendedMinutes(liveUntilMs - System.currentTimeMillis())
                        val extended = stringResource(
                            R.string.live_extended,
                            java.time.Duration.ofMinutes(asked.toLong()).humanize(),
                        )
                        ActionChip(stringResource(R.string.live_extend_fmt, LiveTracking.EXTEND_MINUTES)) {
                            viewModel.setLiveTracking(snapshot.deviceId, asked)
                            done(extended)
                        }
                        ActionChip(stringResource(R.string.quick_live_stop)) {
                            viewModel.setLiveTracking(snapshot.deviceId, 0)
                            done(stopped)
                        }
                    } else {
                        ActionChip(stringResource(R.string.quick_live_start), enabled = understandsLive) {
                            askLive = true
                        }
                    }
                }
                // What the session costs, where the tap that spends it is.
                if (understandsLive || liveUntilMs != null) {
                    LiveBatteryTag(snapshot.batteryPercent, snapshot.charging)
                    LiveCostNote(snapshot.batteryDrain)
                }
                if (!understandsLive && liveUntilMs == null) {
                    Text(
                        stringResource(R.string.quick_live_needs_update),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // --- Finding the phone: ring it, or lock it down and keep it reporting. ---
                val canFind = RemoteAction.canFind(snapshot.appVersionCode)
                val lostAskedHere = lostAskedAll.containsKey(snapshot.deviceId)
                val ringing = stringResource(R.string.find_ringing, entry.name)
                val lostCleared = stringResource(R.string.lost_cleared, entry.name)
                QuickRow(
                    Icons.Outlined.NotificationsActive,
                    stringResource(R.string.find_title),
                    detail = when {
                        snapshot.lostMode -> stringResource(R.string.lost_on_short)
                        lostAskedHere -> stringResource(R.string.lost_asked_line)
                        else -> null
                    },
                ) {
                    ActionChip(stringResource(R.string.find_ring), enabled = canFind) {
                        viewModel.ringChild(snapshot.deviceId)
                        done(ringing)
                    }
                    if (snapshot.lostMode || lostAskedHere) {
                        ActionChip(stringResource(R.string.lost_disable), enabled = canFind) {
                            viewModel.setChildLostMode(snapshot.deviceId, false)
                            done(lostCleared)
                        }
                    } else {
                        ActionChip(stringResource(R.string.lost_enable), enabled = canFind) { askLost = true }
                    }
                }
                if (!canFind) {
                    Text(
                        stringResource(R.string.find_needs_update),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!understandsExceptions && (!entry.isAdult || config.scheduledBedtimeAt(now) != null || bedtimeChanged)) {
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
                // Weighted so the title, not the detail, absorbs a narrow phone. Unweighted it
                // was measured first and took the whole line, leaving the detail beside it —
                // "· 45m left", the part that changes — a couple of characters to wrap into.
                modifier = Modifier.weight(1f, fill = false),
            )
            detail?.let {
                Text(
                    "  · $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) { chips() }
    }
}

/**
 * The next time it is [at] o'clock, from [now]: today if that hour is still ahead, tomorrow if
 * it has been and gone.
 *
 * "Until 21:00" typed at half past nine means this evening to nobody and tomorrow evening to
 * everybody — which is the wrong way round, so it is said explicitly here rather than left to
 * whichever date the caller happened to have.
 */
internal fun nextOccurrenceOf(now: LocalDateTime, at: java.time.LocalTime): LocalDateTime {
    val today = now.toLocalDate().atTime(at)
    return if (today.isAfter(now)) today else today.plusDays(1)
}

/** The next [hour] in the morning — the ceiling an open-ended pause runs to. */
internal fun nextMorning(now: LocalDateTime, hour: Int): LocalDateTime =
    nextOccurrenceOf(now, java.time.LocalTime.of(hour, 0))

/**
 * Installs are open on this phone, or on their way to being: what is open, until when, and the
 * button that shuts it without waiting for the time to run out.
 */
@Composable
private fun InstallsOpenCard(untilMs: Long?, closing: Boolean, onClose: () -> Unit) {
    val spacing = Tokens.spacing
    val color = Tokens.warning
    dev.walcott.ui.components.WalcottCard(color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (untilMs != null) R.string.installs_open_title else R.string.installs_opening_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        closing -> stringResource(R.string.installs_closing)
                        untilMs != null -> stringResource(R.string.installs_open_until, localTimeOf(untilMs).hhmm())
                        else -> stringResource(R.string.installs_waiting_phone)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            androidx.compose.material3.FilledTonalButton(onClick = onClose, enabled = !closing) {
                Text(stringResource(R.string.installs_close_now))
            }
        }
    }
}

/** An instant on this phone's clock, as the time of day it shows. */
private fun localTimeOf(epochMs: Long): java.time.LocalTime =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()

