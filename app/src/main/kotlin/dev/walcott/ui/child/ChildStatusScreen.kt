package dev.walcott.ui.child

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.walcott.BuildConfig
import dev.walcott.R
import dev.walcott.enforcement.UsageAccess
import dev.walcott.location.LocationPolicy
import dev.walcott.rules.BlockReason
import dev.walcott.rules.TimeWindow
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.FamilyIdentity
import dev.walcott.sync.Role
import dev.walcott.ui.AppStatusUi
import dev.walcott.ui.ChildUiState
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.ModeBadge
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.NumberDisplay
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun ChildStatusScreen(
    viewModel: WalcottViewModel,
    deviceOwner: Boolean,
    onOpenParent: () -> Unit,
    onOpenPanic: () -> Unit,
) {
    val state by viewModel.childState.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val channelOfflineSince by viewModel.channelOfflineSince.collectAsStateWithLifecycle()
    val pendingInstall by viewModel.pendingInstall.collectAsStateWithLifecycle()
    val myRequests by viewModel.myPendingRequests.collectAsStateWithLifecycle()
    val myAsks by viewModel.myPendingAsks.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val installExemption by viewModel.installExemption.collectAsStateWithLifecycle()
    val panicStatus by viewModel.panicStatus.collectAsStateWithLifecycle()
    val clockTampered by viewModel.clockTampered.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<AppStatusUi?>(null) }
    var pendingRemote by remember { mutableStateOf<AppStatusUi?>(null) }
    var showAsk by remember { mutableStateOf(false) }
    // "Request more time" flow: pick a target (all apps or one app), then the minutes.
    var showRequestSheet by remember { mutableStateOf(false) }
    var requestTarget by remember { mutableStateOf<RequestTarget?>(null) }
    val myApps by viewModel.myApps.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Everything this phone needs switched on — usage access, the accessibility blocker, location,
    // the DNS filter, notifications, battery optimisation — in one place that re-checks itself on
    // every resume (see DeviceSetup). It replaced two hand-rolled cards here that between them
    // covered a third of the list.
    val deviceSetup = dev.walcott.ui.setup.rememberDeviceSetup()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            scope.launch {
                if (viewModel.pairAsChild(text)) {
                    // Positive confirmation: scanning worked and this phone now belongs
                    // to the family — otherwise success just looks like "nothing happened".
                    Toast.makeText(context, R.string.pairing_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.pairing_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { Header(identity, settings.familyName, onOpenParent) }
            if (identity.role == Role.UNPAIRED) {
                item {
                    JoinFamilyCard(onLink = {
                        scanLauncher.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false))
                    })
                }
            }
            item { HeroCard(state) }
            // Honest channel health: without this, a dead channel (server unreachable,
            // network filtered) looks exactly like a dead app — to the child AND to the
            // parent asking "did you get my extra time?".
            if (identity.role == Role.CHILD) {
                channelOfflineSince?.let { since ->
                    item { ChannelOfflineCard(since) }
                }
                // A running emergency release is the most important thing on this phone: it
                // ends with Walcott gone, and it dies if the channel does. Never buried.
                panicStatus.request?.let { request ->
                    item { PanicProgressRow(request, onOpen = onOpenPanic) }
                }
            }
            // The parents' latest answer: approvals celebrate, denials are said out loud
            // (a request that just vanishes teaches the child to spam it), bonuses explain
            // where the surprise minutes came from. Stays until dismissed.
            notice?.let { n ->
                item { NoticeCard(n, onDismiss = { viewModel.dismissNotice() }) }
            }
            // An approved app ask opened the timed install window: say so, with the countdown.
            val exemptionLeftMs = installExemption - System.currentTimeMillis()
            if (identity.role == Role.CHILD && exemptionLeftMs > 0 && pendingInstall.isEmpty()) {
                item { InstallWindowCard(exemptionLeftMs) }
            }
            // Backstop for the silent install-prompt notification: a parent-pushed install
            // stays visible here until it completes, and tapping re-opens the install window.
            if (pendingInstall.isNotEmpty()) {
                item {
                    PendingInstallCard(
                        pkg = pendingInstall,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(context, dev.walcott.install.InstallPromptActivity::class.java)
                                        .putExtra(dev.walcott.install.InstallPromptActivity.EXTRA_PACKAGE, pendingInstall),
                                )
                            }
                        },
                    )
                }
            }
            // Everything is blocked because the clock can't be trusted — say so, and say what
            // fixes it. Without this the child just sees every app dead for no stated reason.
            if (clockTampered) {
                item {
                    ClockWrongCard(onFix = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    })
                }
            }
            // Whatever this phone still needs, each with the button that opens the exact screen
            // that grants it. Dismissed ones move to Settings → Device setup rather than vanishing.
            items(deviceSetup.toNag, key = { it.key }) { requirement ->
                dev.walcott.ui.setup.SetupNudgeCard(
                    requirement = requirement,
                    onFixed = deviceSetup::refreshNow,
                    onDismiss = { deviceSetup.dismiss(requirement) },
                )
            }
            // The undo: this device's settings screen is behind the parent PIN, so without this
            // a child who hid a reminder could never bring it back.
            item { dev.walcott.ui.setup.HiddenSetupReminderRow(deviceSetup) }
            state.bedtimeTonight?.let { window ->
                if (!state.bedtimeActive) {
                    item { BedtimeTonightRow(window) }
                }
            }
            // The two things the child comes here to DO, above what they have left: asking is
            // the point of this screen, and it used to sit under a list that grows with every
            // app they install — off the bottom of the screen on a phone with a few of them.
            if (identity.role == Role.CHILD) {
                item {
                    RequestTimeCard(onClick = { showRequestSheet = true })
                }
                item { AskCard(onClick = { showAsk = true }) }
            }
            // Everything sent and still unanswered, so "did it go through?" has an answer.
            // Kept with the cards that send them rather than with the apps.
            if (myAsks.isNotEmpty()) {
                item { WaitingCard(myAsks.map { it.text }) }
            }
            // One row per app with a limit today: the rules the child actually lives with,
            // closest to running out first.
            items(state.apps, key = { "app-" + it.packageName }) { app ->
                AppCard(
                    app,
                    // While this app's request is unanswered the button says so, instead of
                    // inviting a duplicate.
                    requestPending = myRequests.any { it.categoryId == app.packageName },
                    onRequestExtra = {
                        if (identity.role == Role.CHILD) pendingRemote = app else pending = app
                    },
                )
            }
            // The way out when the parents lost their phone AND the PIN: deliberately a plain
            // line at the very bottom, not a card. It has to be findable in a real emergency
            // without being an inviting button to poke at. An active request is loud, though —
            // it belongs at the top of the screen (see PanicStatusCard above).
            if (identity.role == Role.CHILD) {
                item {
                    Text(
                        stringResource(R.string.panic_entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = onOpenPanic)
                            .padding(top = spacing.xl, bottom = spacing.sm),
                    )
                }
            }
            // Version visible on the child home without unlocking settings (self-updates are
            // silent, so this is the only easy way to confirm a device is on the latest build).
            item {
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = spacing.xl),
                )
            }
        }
    }

    pending?.let { card ->
        ExtraTimeDialog(viewModel = viewModel, card = card, onDismiss = { pending = null })
    }
    pendingRemote?.let { card ->
        RemoteRequestDialog(
            card = card,
            onDismiss = { pendingRemote = null },
            onSend = { minutes, reason ->
                viewModel.requestExtraTimeRemote(card.packageName, minutes, reason, targetLabel = card.label)
                pendingRemote = null
                Toast.makeText(context, R.string.request_sent, Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showAsk) {
        AskDialog(
            onDismiss = { showAsk = false },
            onSend = { kind, text ->
                viewModel.askFor(kind, text)
                showAsk = false
                Toast.makeText(context, R.string.request_sent, Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showRequestSheet) {
        RequestTimeSheet(
            apps = myApps,
            inventory = viewModel.repository.inventory,
            onDismiss = { showRequestSheet = false },
            onPick = { target ->
                showRequestSheet = false
                requestTarget = target
            },
        )
    }
    requestTarget?.let { target ->
        RequestTimeDialog(
            title = target.label,
            onDismiss = { requestTarget = null },
            onSend = { minutes, reason ->
                viewModel.requestExtraTimeRemote(target.key, minutes, reason, target.label)
                requestTarget = null
                Toast.makeText(context, R.string.request_sent, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/** A target for an extra-time request: all apps, or one app. */
private data class RequestTarget(val key: String, val label: String)

/** Honest line when the family channel hasn't worked for hours: "it's the connection, not you". */
@Composable
private fun ChannelOfflineCard(sinceMs: Long) {
    val spacing = Tokens.spacing
    val since = android.text.format.DateUtils.getRelativeTimeSpanString(sinceMs).toString()
    WalcottCard(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.channel_offline_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.channel_offline_desc, since),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact live status of an emergency release; tapping opens the full screen. */
@Composable
private fun PanicProgressRow(request: dev.walcott.sync.PanicRequest, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(onClick = onOpen, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.panic_active_title), style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    stringResource(
                        R.string.panic_active_notices,
                        request.checkpoints,
                        dev.walcott.sync.PanicProtocol.REQUIRED_CHECKPOINTS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

/**
 * The device clock disagrees with the family's server far beyond drift, so the rules fail
 * closed (see [dev.walcott.rules.RuleEngine.blockedPackages]) — every managed app is blocked
 * until it is right again. Explains the lock and points at the setting that fixes it.
 */
@Composable
private fun ClockWrongCard(onFix: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(onClick = onFix, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.clock_wrong_title), style = MaterialTheme.typography.titleMedium, color = color)
                Text(stringResource(R.string.clock_wrong_desc), style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

/** The parents' latest answer: approval, denial or bonus. Stays until the child dismisses it. */
@Composable
private fun NoticeCard(notice: dev.walcott.sync.NoticeEntry, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    // The grant's target: one app (its label travelled in the notice) or everything.
    val categoryName = notice.text.ifBlank { stringResource(R.string.request_all_apps) }
    val title = when {
        notice.kind == "bonus" -> stringResource(R.string.notice_bonus, notice.minutes, categoryName)
        // Ahead of the plain denial: nobody said no, nobody said anything (see
        // SyncEngine.REQUEST_TTL_MS). Telling a child they were refused would be a lie.
        notice.kind == dev.walcott.sync.SyncManager.NOTICE_EXPIRED ->
            stringResource(R.string.notice_expired, categoryName)
        !notice.approved -> stringResource(R.string.notice_denied)
        notice.kind == "time" -> stringResource(R.string.notice_approved_time, notice.minutes, categoryName)
        notice.kind == ChildRequest.KIND_APP || notice.kind == ChildRequest.KIND_INSTALL ->
            stringResource(R.string.notice_approved_app, notice.text)
        else -> stringResource(R.string.notice_approved_other, notice.text)
    }
    val subtitle = when {
        notice.kind == dev.walcott.sync.SyncManager.NOTICE_EXPIRED ->
            stringResource(R.string.notice_expired_desc)
        !notice.approved && notice.text.isNotBlank() -> notice.text
        !notice.approved -> stringResource(R.string.notice_denied_desc)
        else -> null
    }
    val positive = notice.approved
    val container = if (positive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant

    WalcottCard(color = container) {
        Row(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (positive) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

/** An approved app ask opened the install window — tell the child, with the countdown. */
@Composable
private fun InstallWindowCard(remainingMs: Long) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(
                    stringResource(R.string.install_window_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(
                        R.string.install_window_desc,
                        java.time.Duration.ofMillis(remainingMs).humanize(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Everything sent and still unanswered, so "did it go through?" always has an answer. */
@Composable
private fun WaitingCard(texts: List<String>) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.child_waiting_title), style = MaterialTheme.typography.titleSmall)
                texts.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A parent pushed an app to install: the tap that opens Play (window re-opens on tap). */
@Composable
private fun PendingInstallCard(pkg: String, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onOpen, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.install_child_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.install_child_card_desc, pkg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Child entry point to request more time — for everything, or for a single app. */
@Composable
private fun RequestTimeCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onClick) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.MoreTime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.request_time_card_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.request_time_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Bottom sheet: pick "all apps" or a single app to request extra time for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestTimeSheet(
    apps: List<dev.walcott.data.InstalledApp>,
    inventory: dev.walcott.data.AppInventory,
    onDismiss: () -> Unit,
    onPick: (RequestTarget) -> Unit,
) {
    val spacing = Tokens.spacing
    val allApps = stringResource(R.string.request_all_apps)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text(
                    stringResource(R.string.request_time_pick),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
            item {
                RequestTargetRow(
                    label = allApps,
                    icon = { Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    onClick = { onPick(RequestTarget(dev.walcott.rules.ExtraTime.ALL_APPS, allApps)) },
                )
            }
            items(apps, key = { it.packageName }) { app ->
                RequestTargetRow(
                    label = app.label,
                    icon = { AppIcon(app.packageName, inventory, size = 40.dp) },
                    onClick = { onPick(RequestTarget(app.packageName, app.label)) },
                )
            }
            item { Spacer(Modifier.height(spacing.lg)) }
        }
    }
}

@Composable
private fun RequestTargetRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Minutes + optional reason for a chosen target (all apps or one app). */
@Composable
private fun RequestTimeDialog(title: String, onDismiss: () -> Unit, onSend: (Int, String) -> Unit) {
    val spacing = Tokens.spacing
    var minutes by remember { mutableStateOf(15) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.request_time_for, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(stringResource(R.string.extra_how_much), style = MaterialTheme.typography.bodyMedium)
                dev.walcott.ui.components.MinutesChips(value = minutes, onSelect = { minutes = it })
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(120) },
                    label = { Text(stringResource(R.string.reason_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(minutes, reason.trim()) }) { Text(stringResource(R.string.send_request)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Entry point for the child to ask the parents for something (an app, anything). */
@Composable
private fun AskCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onClick) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.WavingHand,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.ask_card_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ask_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AskDialog(onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    val spacing = Tokens.spacing
    var kind by remember { mutableStateOf(ChildRequest.KIND_APP) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ask_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    dev.walcott.ui.components.ChoiceChip(
                        selected = kind == ChildRequest.KIND_APP,
                        onClick = { kind = ChildRequest.KIND_APP },
                        label = stringResource(R.string.ask_kind_app),
                    )
                    dev.walcott.ui.components.ChoiceChip(
                        selected = kind == ChildRequest.KIND_OTHER,
                        onClick = { kind = ChildRequest.KIND_OTHER },
                        label = stringResource(R.string.ask_kind_other),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = {
                        Text(
                            stringResource(
                                if (kind == ChildRequest.KIND_APP) R.string.ask_text_label_app
                                else R.string.ask_text_label_other,
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSend(kind, text.trim()) }) {
                Text(stringResource(R.string.send_request))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Primary enrollment call-to-action for a child device not yet linked to a family. */
@Composable
private fun JoinFamilyCard(onLink: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onLink, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.join_family_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.join_family_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Header(identity: FamilyIdentity, familyName: String, onOpenParent: () -> Unit) {
    val spacing = Tokens.spacing
    val today = LocalDate.now()
    val dateText = remember(today) {
        today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
            .replaceFirstChar { it.uppercase() }
    }
    val enrolled = identity.role == Role.CHILD
    Row(
        Modifier.fillMaxWidth().padding(top = spacing.xxl, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (enrolled && identity.displayName.isNotBlank()) {
                    stringResource(R.string.child_greeting_named, identity.displayName)
                } else {
                    stringResource(R.string.child_greeting)
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (enrolled && familyName.isNotBlank()) familyName else dateText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ModeBadge(DeviceMode.CHILD)
        IconButton(onClick = onOpenParent) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_content_desc))
        }
    }
}

/** Small heads-up with tonight's bedtime window, hidden while bedtime is active. */
@Composable
private fun BedtimeTonightRow(window: TimeWindow) {
    val spacing = Tokens.spacing
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                stringResource(R.string.bedtime_tonight),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.bedtime_range, window.start.hhmm(), window.end.hhmm()),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun HeroCard(state: ChildUiState) {
    val spacing = Tokens.spacing
    // The signature gradient marks "your time today"; bedtime swaps to the calm container.
    val heroBrush = Tokens.heroBrush
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (state.bedtimeActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
            .then(
                if (state.bedtimeActive) {
                    Modifier
                } else {
                    Modifier.background(heroBrush, RoundedCornerShape(28.dp))
                },
            ),
    ) {
        AnimatedContent(
            targetState = state.bedtimeActive,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "hero",
        ) { bedtime ->
            Row(
                Modifier.padding(spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bedtime) {
                    Icon(
                        Icons.Filled.Bedtime, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(spacing.lg))
                    Column {
                        Text(
                            stringResource(R.string.bedtime_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            stringResource(R.string.bedtime_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Column {
                        Text(
                            stringResource(R.string.hero_today_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        val available = state.apps.count { !it.blocked }
                        // No limited apps at all is the honest "nothing is capped today", not a
                        // setup that is missing something: limits are opt-in now.
                        val summary = if (state.apps.isEmpty()) {
                            stringResource(R.string.hero_pending_setup)
                        } else {
                            pluralStringResource(R.plurals.hero_available_count, available, available)
                        }
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One app with a limit today: what is left of it, and — when it has run out — the way to ask
 * for more. Deliberately a small row rather than a card with a headline number: there is one
 * per limited app, and a child with six of them was scrolling past six posters to reach
 * anything else on the screen.
 */
@Composable
private fun AppCard(app: AppStatusUi, requestPending: Boolean, onRequestExtra: () -> Unit) {
    val spacing = Tokens.spacing
    val accent = MaterialTheme.colorScheme.primary

    WalcottCard {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                if (app.blocked) {
                    StatusPill(blocked = true)
                } else {
                    // The time left says "available" better than a pill ever did, so the pill
                    // is gone from the ones that are.
                    Text(
                        (app.remaining ?: Duration.ZERO).humanize(),
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        stringResource(R.string.label_remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (app.blocked) {
                Spacer(Modifier.height(spacing.sm))
                if (requestPending) {
                    // Already asked: say so instead of inviting a duplicate request.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            stringResource(R.string.request_waiting_button),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    RequestExtraButton(accent, onRequestExtra)
                }
            } else {
                Spacer(Modifier.height(spacing.sm))
                BudgetBar(fraction = fractionUsed(app), color = accent)
            }
        }
    }
}

@Composable
private fun StatusPill(blocked: Boolean) {
    val (labelRes, color, icon) = when (blocked) {
        false -> Triple(R.string.status_available, MaterialTheme.colorScheme.secondary, Icons.Filled.CheckCircle)
        true -> Triple(R.string.status_blocked, MaterialTheme.colorScheme.error, Icons.Filled.Lock)
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BudgetBar(fraction: Float, color: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "budget",
    )
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
private fun RequestExtraButton(color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.request_more_time),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private fun fractionUsed(app: AppStatusUi): Float {
    val budget = app.budget.seconds
    if (budget <= 0) return 0f
    return app.used.seconds.toFloat() / budget.toFloat()
}

@Composable
private fun blockedReasonText(reason: BlockReason?): String = when (reason) {
    BlockReason.BEDTIME -> stringResource(R.string.reason_bedtime)
    BlockReason.BLOCKED_WINDOW -> stringResource(R.string.reason_blocked_window)
    BlockReason.BUDGET_EXHAUSTED -> stringResource(R.string.reason_budget_exhausted)
    BlockReason.FAIL_CLOSED -> stringResource(R.string.reason_fail_closed)
    null -> ""
}
