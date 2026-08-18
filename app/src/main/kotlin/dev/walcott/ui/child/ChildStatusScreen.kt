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
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.delay
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
    onOpenRules: () -> Unit,
    /** The guided run through everything this phone still needs (see ChildSetupJourneyScreen). */
    onOpenSetupJourney: () -> Unit,
) {
    val state by viewModel.childState.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val channelOfflineSince by viewModel.channelOfflineSince.collectAsStateWithLifecycle()
    val pendingInstall by viewModel.pendingInstall.collectAsStateWithLifecycle()
    val pendingInstallLabel by viewModel.pendingInstallLabel.collectAsStateWithLifecycle()
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
    // Two entry points into the same ask, each arriving with its kind already decided: the
    // chips that used to choose between them made "ask for an app" invisible.
    var showAskApp by remember { mutableStateOf(false) }
    var showAskOther by remember { mutableStateOf(false) }
    // "Request more time" flow: pick a target (all apps or one app), then the minutes.
    var showRequestSheet by remember { mutableStateOf(false) }
    var requestTarget by remember { mutableStateOf<RequestTarget?>(null) }
    val myApps by viewModel.myApps.collectAsStateWithLifecycle()
    val screenTimeToday by viewModel.childScreenTimeToday.collectAsStateWithLifecycle()
    val insight by viewModel.childInsight.collectAsStateWithLifecycle()
    // Rendered here rather than in the hero so the day's number picks the phrasing too — the
    // same rotation that chose the fact (see Insights.forToday).
    val insightLine = insight?.let {
        insightText(it, label = { pkg -> viewModel.repository.inventory.label(pkg) ?: pkg }, rotation = java.time.LocalDate.now().dayOfYear)
    }

    // This screen's own clock, for the two things on it that age without anything being written:
    // the install window's countdown and how old the parents' last answer is. Everything else
    // rides on childState — which is a StateFlow, and therefore conflated: its 15s clock
    // re-derives the same value and emits nothing, so a screen with nothing else happening on it
    // never recomposed. The window's countdown froze, and the card outlived the window it was
    // counting down. Ten seconds is finer than anything either of them prints.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(10_000)
        }
    }

    // Whatever is closing the whole phone right now. The hero says it once, up top.
    val deviceWideWindow = state.bedtimeActive || state.screenFreeNow != null

    // The apps worth showing: the ones with minutes left, not every limited app on the phone.
    val lowApps = remember(state.apps, deviceWideWindow) {
        state.apps.filter { dev.walcott.rules.CloseWatch.runningLow(it.remaining, it.blocked) }
            // While bedtime or a screen-free window has the whole phone closed, every limited
            // app reads as blocked. Listing them all under "Running out", each offering to ask
            // for more time, said two false things at once: they have not run out, and no
            // number of minutes can end a window (see AppStatusUi.moreTimeWouldHelp).
            .filterNot { deviceWideWindow && !it.moreTimeWouldHelp }
    }

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
                    // Straight into the permissions, while the parent is still holding the
                    // phone. This is the whole reason the journey exists: the same list left
                    // as cards on this screen is read by the child, an hour later, and acted
                    // on by nobody. Pairing has just reset the flag (see pairAsChild).
                    onOpenSetupJourney()
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
            // What is about to bite, and the rules that shape the day. Everything below is
            // either an exception (an alert, a pending answer) or something to DO.
            // Two counts, not one: an app that has already run out is not "running out", and
            // saying so over a card that reads "Blocked" was the screen disagreeing with itself.
            // "Run out" means the budget, only — an app shut by a window of its own has not run
            // out of anything, and its card says which window.
            item {
                HeroCard(
                    state,
                    runningLow = lowApps.count { !it.blocked },
                    outOfTime = lowApps.count { it.blocked && it.moreTimeWouldHelp },
                    screenTimeToday = screenTimeToday,
                    insight = insightLine,
                )
            }
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
            // where the surprise minutes came from. Stays until dismissed — but only while it
            // is still true: the minutes an approval announces are today's, and die at the
            // child's midnight with the rest of the day's extra time (see SyncEngine).
            notice?.takeUnless { dev.walcott.sync.SyncEngine.noticeExpired(it.atMs, nowMs) }?.let { n ->
                item { NoticeCard(n, onDismiss = { viewModel.dismissNotice() }) }
            }
            // An approved app ask opened the timed install window: say so, with the countdown.
            val exemptionLeftMs = installExemption - nowMs
            if (identity.role == Role.CHILD && exemptionLeftMs > 0 && pendingInstall.isEmpty()) {
                item { InstallWindowCard(exemptionLeftMs) }
            }
            // Backstop for the silent install-prompt notification: a parent-pushed install
            // stays visible here until it completes, and tapping re-opens the install window.
            if (pendingInstall.isNotEmpty()) {
                item {
                    PendingInstallCard(
                        // The name the parent's device sent: the app isn't installed here yet,
                        // so the package name is all this device could work out by itself.
                        appName = pendingInstallLabel.ifBlank { pendingInstall },
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
            // An enrollment that stopped at the QR: nobody has been walked through the
            // permissions on this phone yet. One card that opens the guided run, INSTEAD of the
            // per-requirement cards below — the same list twice, once as a job to do and once as
            // four things to dismiss, is how a parent ends up doing neither.
            val journeyPending =
                !deviceSetup.journeyDone && deviceSetup.loaded && deviceSetup.unmet.isNotEmpty()
            if (journeyPending) {
                item { FinishSetupCard(count = deviceSetup.unmet.size, onOpen = onOpenSetupJourney) }
            } else {
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
            }
            // Only the apps about to run out (see CloseWatch.runningLow). Listing every limited
            // app put a wall of cards between the child and the two things they came here to do,
            // and a card reading "1h 40m left" is not news — it is the ones with minutes left
            // that the child needs, and those now carry the way to ask for more.
            if (lowApps.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.home_section_running_low)) }
                items(lowApps, key = { "app-" + it.packageName }) { app ->
                    AppCard(
                        app,
                        inventory = viewModel.repository.inventory,
                        // While this app's request is unanswered the button says so, instead of
                        // inviting a duplicate.
                        requestPending = myRequests.any { it.categoryId == app.packageName },
                        onRequestExtra = {
                            if (identity.role == Role.CHILD) pendingRemote = app else pending = app
                        },
                    )
                }
            }
            // The two things a child actually opens this app for, at the top of what they can
            // do and given equal, unmistakable weight. Asking for an app used to be a chip
            // inside the "ask for something" dialog — one tap and one guess away from being
            // found at all.
            if (identity.role == Role.CHILD) {
                item { RequestTimeCard(onClick = { showRequestSheet = true }) }
                item { AskAppCard(onClick = { showAskApp = true }) }
                // Both quiet rows, and in this order: looking up your own rules is a real
                // question a child has, and a commoner one than writing a message. Neither is
                // a card, because neither is what this screen is FOR.
                item { QuietRow(Icons.Outlined.Rule, stringResource(R.string.child_rules_entry), onOpenRules) }
                item { AskOtherRow(onClick = { showAskOther = true }) }
            }
            // Everything sent and still unanswered, so "did it go through?" has an answer.
            // Kept with the cards that send them rather than with the apps.
            if (myAsks.isNotEmpty()) {
                item { WaitingCard(myAsks.map { it.text }) }
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
    if (showAskApp || showAskOther) {
        val kind = if (showAskApp) ChildRequest.KIND_APP else ChildRequest.KIND_OTHER
        AskDialog(
            kind = kind,
            onDismiss = { showAskApp = false; showAskOther = false },
            onSend = { text ->
                viewModel.askFor(kind, text)
                showAskApp = false
                showAskOther = false
                Toast.makeText(context, R.string.request_sent, Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showRequestSheet) {
        RequestTimeSheet(
            apps = myApps,
            // The sheet is the only place left that shows every limited app, now that the home
            // keeps to the ones running out — so it has to say how much is left of each.
            limits = state.apps,
            // The per-app cards have always refused to send a second request for something
            // already waiting; this list never did, which is how a parent ended up looking at
            // three cards for one question.
            alreadyAsked = remember(myRequests) { myRequests.mapTo(mutableSetOf()) { it.categoryId } },
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
    // The grant's target. "Everything" is only ever said when the request really was for
    // everything: falling back to it whenever the label was missing is what turned every
    // single-app approval into "15 minutes for all apps" (see SyncEngine.latestResolutionSummary).
    // The package name is a poor label but a true one, and beats naming the wrong thing.
    val everything = stringResource(R.string.request_all_apps)
    val categoryName = notice.text.ifBlank {
        notice.categoryId.takeIf { it.isNotBlank() && it != dev.walcott.rules.ExtraTime.ALL_APPS }
            ?: everything
    }
    val title = when {
        notice.kind == "bonus" -> stringResource(R.string.notice_bonus, notice.minutes, categoryName)
        // Ahead of the plain denial: nobody said no, nobody said anything (see
        // SyncEngine.REQUEST_TTL_MS). Telling a child they were refused would be a lie.
        notice.kind == dev.walcott.sync.SyncManager.NOTICE_EXPIRED ->
            stringResource(R.string.notice_expired, categoryName)
        !notice.approved -> stringResource(R.string.notice_denied)
        notice.kind == "time" -> stringResource(R.string.notice_approved_time, notice.minutes, categoryName)
        // An install ask is answered with the app itself: it arrives with its own prompt.
        notice.kind == ChildRequest.KIND_INSTALL -> stringResource(R.string.notice_approved_app, notice.text)
        // A written "can I have an app?" is answered with a yes, and nothing else — installing
        // it means sharing it from Play, which is the path that installs exactly that one app.
        notice.kind == ChildRequest.KIND_APP -> stringResource(R.string.notice_approved_app_ask, notice.text)
        else -> stringResource(R.string.notice_approved_other, notice.text)
    }
    val subtitle = when {
        notice.kind == dev.walcott.sync.SyncManager.NOTICE_EXPIRED ->
            stringResource(R.string.notice_expired_desc)
        !notice.approved && notice.text.isNotBlank() -> notice.text
        !notice.approved -> stringResource(R.string.notice_denied_desc)
        notice.kind == ChildRequest.KIND_APP -> stringResource(R.string.notice_approved_app_ask_desc)
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

/** An approved app waiting to be installed: the tap that opens Play (window re-opens on tap). */
@Composable
private fun PendingInstallCard(appName: String, onOpen: () -> Unit) {
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
                    stringResource(R.string.install_child_card_title, appName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.install_child_card_desc),
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

/**
 * Bottom sheet: pick "all apps" or a single app to request extra time for.
 *
 * Also the full view of what this child has left. The home deliberately keeps to the apps that
 * are running out, so this list is where "how much do I have in the others?" is answered — and
 * a picker that named apps without saying anything about them made the child choose blind.
 *
 * A target already waiting on an answer is shown and disabled rather than hidden: it is the
 * answer to "did I already ask for this?", and hiding it would read as the app having lost the
 * request. Its absence here is what let a child send the same question three times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestTimeSheet(
    apps: List<dev.walcott.data.InstalledApp>,
    limits: List<AppStatusUi>,
    alreadyAsked: Set<String>,
    inventory: dev.walcott.data.AppInventory,
    onDismiss: () -> Unit,
    onPick: (RequestTarget) -> Unit,
) {
    val spacing = Tokens.spacing
    val allApps = stringResource(R.string.request_all_apps)
    val limitByPackage = remember(limits) { limits.associateBy { it.packageName } }
    // The same rule the parent's pickers use (see AppPickerSheet): a search box once the list is
    // long enough to be scrolled rather than read. A child's phone is exactly where that bites —
    // forty apps, and the one they want is the one they were just using.
    var query by remember { mutableStateOf("") }
    val matches = remember(apps, query) {
        dev.walcott.ui.components.matching(
            apps,
            query,
            label = { it.label },
            packageName = { it.packageName },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text(
                    stringResource(R.string.request_time_pick),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
            if (apps.size > dev.walcott.ui.components.SEARCH_ABOVE) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        placeholder = { Text(stringResource(R.string.search_app)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.xs),
                    )
                }
            }
            // Hidden while searching: "every app" is not a result, and leaving it pinned above a
            // filtered list is the one row a child could tap by accident having meant the other.
            if (query.isBlank()) {
                item {
                    RequestTargetRow(
                        label = allApps,
                        icon = { Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                        asked = dev.walcott.rules.ExtraTime.ALL_APPS in alreadyAsked,
                        onClick = { onPick(RequestTarget(dev.walcott.rules.ExtraTime.ALL_APPS, allApps)) },
                    )
                }
            }
            if (matches.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.app_picker_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                }
            }
            items(matches, key = { it.packageName }) { app ->
                RequestTargetRow(
                    label = app.label,
                    icon = { AppIcon(app.packageName, inventory, size = 40.dp) },
                    // Absent from the limits map means no limit today: there is nothing to run
                    // out of, so the row says nothing rather than inventing a zero.
                    status = limitByPackage[app.packageName],
                    asked = app.packageName in alreadyAsked,
                    onClick = { onPick(RequestTarget(app.packageName, app.label)) },
                )
            }
            item { Spacer(Modifier.height(spacing.lg)) }
        }
    }
}

/**
 * One target in the picker: what it is, what is left of it, and whether it can still be asked
 * about. [asked] wins over [status] on the right-hand side — "you already asked" is the more
 * useful answer to someone about to ask again.
 */
@Composable
private fun RequestTargetRow(
    label: String,
    icon: @Composable () -> Unit,
    status: AppStatusUi? = null,
    asked: Boolean = false,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !asked, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (asked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(spacing.sm))
        when {
            asked -> Text(
                stringResource(R.string.request_asked_short),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status == null -> Unit
            status.blocked -> Text(
                stringResource(R.string.status_blocked),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                (status.remaining ?: Duration.ZERO).humanize(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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

/**
 * Asking for an app, given the same weight as asking for time — the other reason a child opens
 * Walcott at all. It was a chip inside a generic "ask for something" dialog, which is one tap and
 * one guess away from not existing.
 */
@Composable
private fun AskAppCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onClick) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.ask_app_card_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ask_app_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Ask for something else": a plain row, not a card.
 *
 * A free-form message to a parent is the rarest thing on this screen and the one with no shape —
 * there is nothing for the app to do with it beyond passing it on. Three identical cards taught
 * the eye that the three were equals; this one is reachable, legible, and quiet.
 */
@Composable
private fun AskOtherRow(onClick: () -> Unit) =
    QuietRow(Icons.Filled.WavingHand, stringResource(R.string.ask_other_row), onClick)

/**
 * A secondary destination: reachable, legible, and quiet.
 *
 * The two things below the action cards are neither urgent nor frequent, and giving either a
 * card of its own would say they rank with asking for time — which is the mistake this screen
 * was rebuilt to undo.
 */
@Composable
private fun QuietRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Quiet label above a group of rows, so the home reads as sections rather than a stack. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Tokens.spacing.sm, top = Tokens.spacing.xs),
    )
}

/**
 * The ask itself, for a [kind] the caller has already chosen.
 *
 * It used to open on "an app / something else" chips, which is a question the child had already
 * answered by deciding to tap something — and it hid the commoner of the two behind a control
 * nobody reads. Each entry point now arrives knowing what it is for.
 */
@Composable
private fun AskDialog(kind: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val app = kind == ChildRequest.KIND_APP
    var text by remember(kind) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (app) R.string.ask_dialog_title_app else R.string.ask_dialog_title_other,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = {
                    Text(
                        stringResource(
                            if (app) R.string.ask_text_label_app else R.string.ask_text_label_other,
                        ),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) {
                Text(stringResource(R.string.send_request))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The other half of enrollment, unfinished: this phone is paired but nobody has granted what
 * the rules need. Loud and singular on purpose — it stands in for every individual reminder
 * while it is showing, and one job with one button is what a parent will actually finish.
 */
@Composable
private fun FinishSetupCard(count: Int, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(onClick = onOpen, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.PhonelinkSetup,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.journey_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
                Text(
                    pluralStringResource(R.plurals.journey_card_desc, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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

/**
 * The one thing the child should read first: what is about to close, and the rules that shape
 * the rest of the day.
 *
 * It used to count the apps that were FINE ("2 with time available"), which is the reassuring
 * half of the news and the half nobody needs — a child opens this screen because something is
 * running out, and the number that answers that question was nowhere on it. The limits below
 * are the standing rules, kept here rather than scattered down the page, so "why did it stop"
 * has an answer in the same glance.
 */
@Composable
private fun HeroCard(
    state: ChildUiState,
    runningLow: Int,
    outOfTime: Int,
    screenTimeToday: Duration,
    insight: String?,
) {
    val spacing = Tokens.spacing
    // Whatever has the whole phone closed right now, and when it lets go. Screen-free time
    // belongs here for the same reason bedtime does: it is the answer to "why did everything
    // just stop", and without it this card went on reporting the day's app limits — which are
    // not what stopped anything — over a screen where nothing would open.
    val closed: Pair<Int, java.time.LocalTime>? = when {
        state.bedtimeActive -> state.bedtimeTonight?.let { R.string.bedtime_title to it.end }
        state.screenFreeNow != null -> R.string.screen_free_title to state.screenFreeNow.end
        else -> null
    }
    // The signature gradient marks "your time today"; a closed phone swaps to the calm container.
    val heroBrush = Tokens.heroBrush
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (closed != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
            .then(
                if (closed != null) {
                    Modifier
                } else {
                    Modifier.background(heroBrush, RoundedCornerShape(28.dp))
                },
            ),
    ) {
        AnimatedContent(
            targetState = closed,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "hero",
        ) { window ->
            if (window != null) {
                val (titleRes, endsAt) = window
                Row(
                    Modifier.padding(spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (titleRes == R.string.bedtime_title) Icons.Filled.Bedtime else Icons.Outlined.DoNotDisturbOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(spacing.lg))
                    Column {
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            // When it ends, rather than "until tomorrow" — which was a guess
                            // about the shape of the window, and wrong for every one that ends
                            // the same day. The child's question is when they get the phone back.
                            stringResource(R.string.window_until, endsAt.hhmm()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            } else {
                Column(Modifier.padding(spacing.xl)) {
                    Text(
                        stringResource(R.string.hero_today_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    // "No limits today" has to mean it. A family default or a bedtime IS a limit,
                    // even before the child has opened anything for a card to exist about — and
                    // the standing rules are printed directly underneath, so getting this wrong
                    // put "No time limits set today" one line above "1h a day for each app".
                    val anyLimit = state.apps.isNotEmpty() ||
                        state.defaultBudget != null || state.bedtimeTonight != null
                    // Already out ranks about to be out: it is the one the child came to do
                    // something about, and calling it "running out" was simply not true.
                    val summary = when {
                        !anyLimit -> stringResource(R.string.hero_pending_setup)
                        // Both, when there are both: one number over two cards that plainly say
                        // different things is the screen disagreeing with itself again.
                        outOfTime > 0 && runningLow > 0 ->
                            stringResource(R.string.hero_out_and_low, outOfTime, runningLow)
                        outOfTime > 0 ->
                            pluralStringResource(R.plurals.hero_out_of_time, outOfTime, outOfTime)
                        runningLow > 0 ->
                            pluralStringResource(R.plurals.hero_running_low, runningLow, runningLow)
                        else -> stringResource(R.string.hero_nothing_urgent)
                    }
                    Text(
                        summary,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    // Their own number, which the card is titled after and never actually said.
                    // A limit is somebody else's decision; this is just what the day has been.
                    if (screenTimeToday >= Duration.ofMinutes(1)) {
                        Text(
                            stringResource(R.string.child_today_screen_time, screenTimeToday.humanize()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(top = spacing.xs),
                        )
                    }
                    // The standing rules, as short lines rather than cards of their own. Absent
                    // entirely when the family has set none, which is a real configuration.
                    val bedtimeTonight = state.bedtimeTonight
                    if (state.defaultBudget != null || bedtimeTonight != null) {
                        Spacer(Modifier.height(spacing.md))
                        state.defaultBudget?.let { budget ->
                            // "for each app", never "screen time": the engine gives every app
                            // its own allowance rather than one shared pot, and a child told
                            // otherwise would count their day wrong (see FamilyConfig).
                            HeroLimitLine(
                                Icons.Outlined.Schedule,
                                stringResource(R.string.home_limit_default, budget.humanize()),
                            )
                        }
                        bedtimeTonight?.let { window ->
                            HeroLimitLine(
                                Icons.Filled.Bedtime,
                                stringResource(
                                    R.string.home_limit_bedtime,
                                    window.start.hhmm(),
                                    window.end.hhmm(),
                                ),
                            )
                        }
                    }
                    // One thing about their own week or month, under a rule of its own: only
                    // when it is worth saying, only one at a time, and never twice in the same
                    // words (see Insights). It sits below the limits deliberately — the rules
                    // are somebody else's, this part is theirs.
                    if (insight != null) {
                        Spacer(Modifier.height(spacing.md))
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                        )
                        Text(
                            insight,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(top = spacing.md),
                        )
                    }
                }
            }
        }
    }
}

/** One standing rule inside the hero: an icon and a short line, on the gradient. */
@Composable
private fun HeroLimitLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val spacing = Tokens.spacing
    Row(
        Modifier.padding(top = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * One app with a limit today: what is left of it, and — when it has run out — the way to ask
 * for more. Deliberately a small row rather than a card with a headline number: there is one
 * per limited app, and a child with six of them was scrolling past six posters to reach
 * anything else on the screen.
 */
@Composable
private fun AppCard(
    app: AppStatusUi,
    inventory: dev.walcott.data.AppInventory,
    requestPending: Boolean,
    onRequestExtra: () -> Unit,
) {
    val spacing = Tokens.spacing
    val accent = MaterialTheme.colorScheme.primary

    WalcottCard {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The app is installed here, so this is a local lookup — no sync involved.
                AppIcon(app.packageName, inventory, size = 32.dp)
                Spacer(Modifier.width(spacing.md))
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
            // Every card here is one the child can act on: the home only shows apps that are
            // running out (CloseWatch.runningLow), and the reason to show them is that asking
            // for more is now possible. Gating the shortcut on "blocked" used to leave the last
            // minute — the one moment a child most wants it — with a card that said "1m left"
            // and offered nothing to do about it.
            Spacer(Modifier.height(spacing.sm))
            if (!app.blocked) {
                BudgetBar(fraction = fractionUsed(app), color = accent)
                Spacer(Modifier.height(spacing.sm))
            }
            if (!app.moreTimeWouldHelp) {
                // Blocked by a window, not by a budget: bedtime and screen-free time outrank
                // every allowance (see appStatus), so minutes cannot lift them. Offering to ask
                // for more sent the child to their parents for something a yes could not deliver
                // — the request was granted, the app stayed shut, and neither of them knew why.
                // Say what is holding it instead.
                Text(
                    blockedReasonText(app),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (requestPending) {
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
private fun blockedReasonText(app: AppStatusUi): String = when (app.blockReason) {
    BlockReason.BEDTIME -> stringResource(R.string.reason_bedtime)
    BlockReason.BLOCKED_WINDOW -> stringResource(R.string.reason_blocked_window)
    // An app whose limit is zero never had time to run out of, and telling the child it did is
    // the same sentence their parent was reading on the other side — "0 of 0 used" — with the
    // numbers hidden. Only when nothing was used: a grant spent down to zero really is a
    // budget that ran out, whatever the underlying limit says.
    BlockReason.BUDGET_EXHAUSTED ->
        if (app.budget.isZero && app.used.isZero) {
            stringResource(R.string.reason_blocked_today)
        } else {
            stringResource(R.string.reason_budget_exhausted)
        }
    BlockReason.FAIL_CLOSED -> stringResource(R.string.reason_fail_closed)
    null -> ""
}
