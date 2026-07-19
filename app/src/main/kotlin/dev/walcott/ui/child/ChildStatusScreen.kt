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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import dev.walcott.rules.CategoryState
import dev.walcott.rules.TimeWindow
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.FamilyIdentity
import dev.walcott.sync.Role
import dev.walcott.ui.CategoryStatusUi
import dev.walcott.ui.ChildUiState
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.ModeBadge
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.NumberDisplay
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
) {
    val state by viewModel.childState.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val pendingInstall by viewModel.pendingInstall.collectAsStateWithLifecycle()
    val myRequests by viewModel.myPendingRequests.collectAsStateWithLifecycle()
    val myAsks by viewModel.myPendingAsks.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val installExemption by viewModel.installExemption.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<CategoryStatusUi?>(null) }
    var pendingRemote by remember { mutableStateOf<CategoryStatusUi?>(null) }
    var showAsk by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Without Device Owner nobody force-grants location, so if the child denied (or never got) the
    // runtime prompt, location check-ins silently stop. Nudge to fix it; re-check on resume so the
    // card disappears once granted from settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationGranted by remember { mutableStateOf(LocationPolicy.hasFineLocation(context)) }
    // Usage access can't be force-granted (it's an AppOp), and without it the enforcement
    // loop fails closed and suspends the managed apps — so the child needs to see why
    // everything is locked and exactly where to fix it.
    var usageAccessOn by remember { mutableStateOf(UsageAccess.granted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = LocationPolicy.hasFineLocation(context)
                usageAccessOn = UsageAccess.granted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val showLocationNudge = identity.role == Role.CHILD && !deviceOwner && !locationGranted
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locationGranted = true
        } else {
            // Only send to settings once the system won't prompt again (denied for good).
            val activity = context.findActivity()
            if (activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                openAppDetails(context)
            }
        }
    }
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
            if (!usageAccessOn) {
                item {
                    UsageAccessCard(onFix = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    })
                }
            }
            if (showLocationNudge) {
                item {
                    LocationPermissionCard(
                        onFix = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    )
                }
            }
            state.bedtimeTonight?.let { window ->
                if (!state.bedtimeActive) {
                    item { BedtimeTonightRow(window) }
                }
            }
            items(state.categories, key = { it.category.id }) { card ->
                CategoryCard(
                    card,
                    // While this category's request is unanswered the button says so,
                    // instead of inviting a duplicate.
                    requestPending = myRequests.any { it.categoryId == card.category.id },
                    onRequestExtra = {
                        if (identity.role == Role.CHILD) pendingRemote = card else pending = card
                    },
                )
            }
            // Everything sent and still unanswered, so "did it go through?" has an answer.
            if (myAsks.isNotEmpty()) {
                item { WaitingCard(myAsks.map { it.text }) }
            }
            if (identity.role == Role.CHILD) {
                item { AskCard(onClick = { showAsk = true }) }
            }
            // Version visible on the child home without unlocking settings (self-updates are
            // silent, so this is the only easy way to confirm a device is on the latest build).
            item {
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = spacing.xl),
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
                viewModel.requestExtraTimeRemote(card.category.id, minutes, reason)
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
}

/** Unwraps the Activity behind a Compose context, for permission-rationale checks. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Opens this app's system settings page so location can be granted after a runtime denial. */
private fun openAppDetails(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Heads-up on the child when location permission is missing (non-Device-Owner): check-ins won't run. */
@Composable
private fun LocationPermissionCard(onFix: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    Surface(
        onClick = onFix,
        shape = RoundedCornerShape(22.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.location_permission_title), style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    stringResource(R.string.location_permission_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

/**
 * Heads-up when usage access is off: the enforcement loop is failing closed (apps with
 * limits stay suspended), so this explains the lock and deep-links the exact setting.
 */
@Composable
private fun UsageAccessCard(onFix: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    Surface(
        onClick = onFix,
        shape = RoundedCornerShape(22.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.usage_access_card_title), style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    stringResource(R.string.usage_access_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

/** The parents' latest answer: approval, denial or bonus. Stays until the child dismisses it. */
@Composable
private fun NoticeCard(notice: dev.walcott.sync.NoticeEntry, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    val categoryName = dev.walcott.AppCategory.byId(notice.categoryId)
        ?.let { stringResource(it.nameRes) } ?: notice.categoryId
    val title = when {
        notice.kind == "bonus" -> stringResource(R.string.notice_bonus, notice.minutes, categoryName)
        !notice.approved -> stringResource(R.string.notice_denied)
        notice.kind == "time" -> stringResource(R.string.notice_approved_time, notice.minutes, categoryName)
        notice.kind == ChildRequest.KIND_APP -> stringResource(R.string.notice_approved_app, notice.text)
        else -> stringResource(R.string.notice_approved_other, notice.text)
    }
    val subtitle = when {
        !notice.approved && notice.text.isNotBlank() -> notice.text
        !notice.approved -> stringResource(R.string.notice_denied_desc)
        else -> null
    }
    val positive = notice.approved
    val container = if (positive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(shape = RoundedCornerShape(22.dp), color = container, modifier = Modifier.fillMaxWidth()) {
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
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
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

/** Entry point for the child to ask the parents for something (an app, anything). */
@Composable
private fun AskCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                    FilterChip(
                        selected = kind == ChildRequest.KIND_APP,
                        onClick = { kind = ChildRequest.KIND_APP },
                        label = { Text(stringResource(R.string.ask_kind_app)) },
                    )
                    FilterChip(
                        selected = kind == ChildRequest.KIND_OTHER,
                        onClick = { kind = ChildRequest.KIND_OTHER },
                        label = { Text(stringResource(R.string.ask_kind_other)) },
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
    Surface(
        onClick = onLink,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                        val budgeted = state.categories.count { it.status.state == CategoryState.BUDGETED }
                        // No categories means nothing is classified yet, and the rule engine
                        // blocks unclassified apps — so "all free" was misleading. Say so plainly.
                        val summary = if (state.categories.isEmpty()) {
                            stringResource(R.string.hero_pending_setup)
                        } else {
                            pluralStringResource(R.plurals.hero_available_count, budgeted, budgeted)
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

@Composable
private fun CategoryCard(card: CategoryStatusUi, requestPending: Boolean, onRequestExtra: () -> Unit) {
    val spacing = Tokens.spacing
    val category = card.category
    val status = card.status

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                        .background(category.color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(category.icon, contentDescription = null, tint = category.color)
                }
                Spacer(Modifier.width(spacing.md))
                Text(
                    stringResource(category.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status.state)
            }

            when (status.state) {
                CategoryState.BUDGETED -> {
                    Spacer(Modifier.height(spacing.md))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            (status.remaining ?: Duration.ZERO).humanize(),
                            style = NumberDisplay,
                            color = category.color,
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            stringResource(R.string.label_remaining),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(spacing.sm))
                    BudgetBar(fraction = fractionUsed(card), color = category.color)
                    if (card.earned > Duration.ZERO) {
                        Spacer(Modifier.height(spacing.sm))
                        Text(
                            stringResource(R.string.earned_bonus, card.earned.humanize()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                CategoryState.ALLOWED -> {
                    Spacer(Modifier.height(spacing.sm))
                    Text(stringResource(R.string.no_limit_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                CategoryState.BLOCKED -> {
                    Spacer(Modifier.height(spacing.sm))
                    Text(blockedReasonText(status.blockReason), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status.blockReason == BlockReason.BUDGET_EXHAUSTED) {
                        Spacer(Modifier.height(spacing.md))
                        if (requestPending) {
                            // Already asked: say so instead of inviting a duplicate request.
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.HourglassEmpty,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(spacing.xs))
                                Text(
                                    stringResource(R.string.request_waiting_button),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            RequestExtraButton(category.color, onRequestExtra)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: CategoryState) {
    val (labelRes, color, icon) = when (state) {
        CategoryState.BUDGETED -> Triple(R.string.status_available, MaterialTheme.colorScheme.secondary, Icons.Filled.CheckCircle)
        CategoryState.ALLOWED -> Triple(R.string.status_free, MaterialTheme.colorScheme.secondary, Icons.Filled.CheckCircle)
        CategoryState.BLOCKED -> Triple(R.string.status_blocked, MaterialTheme.colorScheme.error, Icons.Filled.Lock)
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
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(animated).height(10.dp).clip(RoundedCornerShape(50)).background(color))
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private fun fractionUsed(card: CategoryStatusUi): Float {
    val budget = card.status.budget?.seconds ?: return 0f
    if (budget <= 0) return 0f
    val used = card.status.used.seconds.toFloat()
    return used / budget.toFloat()
}

@Composable
private fun blockedReasonText(reason: BlockReason?): String = when (reason) {
    BlockReason.BEDTIME -> stringResource(R.string.reason_bedtime)
    BlockReason.BLOCKED_WINDOW -> stringResource(R.string.reason_blocked_window)
    BlockReason.BUDGET_EXHAUSTED -> stringResource(R.string.reason_budget_exhausted)
    BlockReason.UNCLASSIFIED -> stringResource(R.string.reason_unclassified)
    null -> ""
}
