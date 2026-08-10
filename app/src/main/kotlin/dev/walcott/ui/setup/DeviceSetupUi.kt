package dev.walcott.ui.setup

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.walcott.R
import dev.walcott.setup.DeviceRequirement
import dev.walcott.setup.DeviceSetup
import dev.walcott.setup.DeviceSetupProbe
import dev.walcott.setup.DeviceSetupStore
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Live setup state for a screen: what is unmet, what is still worth interrupting about. */
class DeviceSetupHandle internal constructor(
    val unmet: List<DeviceRequirement>,
    val dismissed: Set<String>,
    private val onRefresh: () -> Unit,
    private val onDismiss: (DeviceRequirement) -> Unit,
    private val onRestore: (DeviceRequirement) -> Unit,
) {
    /** What the home screen shows: unmet minus dismissed. */
    val toNag: List<DeviceRequirement> get() = DeviceSetup.toNag(unmet, dismissed)

    /** Re-reads the device. Called after a fix, so the card disappears without leaving the screen. */
    fun refreshNow() = onRefresh()

    fun dismiss(requirement: DeviceRequirement) = onDismiss(requirement)

    fun restore(requirement: DeviceRequirement) = onRestore(requirement)
}

/**
 * Probes the device on every RESUME and keeps the answer live.
 *
 * RESUME rather than first composition because every fix happens in another app: the user leaves
 * for the system settings and comes back, and a card that only checked once would still be
 * telling them to do the thing they just did.
 */
@Composable
fun rememberDeviceSetup(): DeviceSetupHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DeviceSetupStore(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var unmet by remember { mutableStateOf<List<DeviceRequirement>>(emptyList()) }
    var dismissed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        val facts = DeviceSetupProbe.read(context)
        val currentlyUnmet = DeviceSetup.unmet(facts)
        // A dismissal only ever hides one outage: once the requirement is satisfied it is
        // forgotten, so the next time it breaks the person is asked again.
        store.pruneSatisfied(currentlyUnmet)
        unmet = currentlyUnmet
        dismissed = store.dismissed.first()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return DeviceSetupHandle(
        unmet = unmet,
        dismissed = dismissed,
        onRefresh = { tick++ },
        onDismiss = { req -> scope.launch { store.dismiss(req); dismissed = store.dismissed.first() } },
        onRestore = { req -> scope.launch { store.restore(req); dismissed = store.dismissed.first() } },
    )
}

/**
 * The home-screen nudges: one card per outstanding requirement, each with the button that fixes
 * it and a way out that costs more than fixing it does.
 */
@Composable
fun SetupNudgeCards(handle: DeviceSetupHandle) {
    handle.toNag.forEach { requirement ->
        SetupNudgeCard(
            requirement = requirement,
            onFixed = handle::refreshNow,
            onDismiss = { handle.dismiss(requirement) },
        )
    }
}

/**
 * The way back from a dismissal, for a screen that has no settings behind it.
 *
 * On the child's phone the settings screen sits behind the parent PIN, so a child who hides a
 * reminder would have no way to see it again — a one-way door, which is not what "not now" says.
 * This quiet row is the undo: it appears only once something is hidden, and says how much.
 */
@Composable
fun HiddenSetupReminderRow(handle: DeviceSetupHandle) {
    val hidden = handle.unmet.filter { it.key in handle.dismissed }
    if (hidden.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(
                R.plurals.setup_hidden_count, hidden.size, hidden.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { hidden.forEach { handle.restore(it) } }) {
            Text(stringResource(R.string.setup_hidden_show), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SetupNudgeCard(
    requirement: DeviceRequirement,
    onFixed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val color =
        if (requirement.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    var confirming by remember { mutableStateOf(false) }
    val fix = rememberFixAction(requirement, onFixed)

    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(requirement),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Text(
                    stringResource(requirement.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(requirement.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = fix) { Text(stringResource(R.string.setup_action_turn_on)) }
                Spacer(Modifier.weight(1f))
                // Deliberately the quiet option: one tap fixes it, three hide it.
                TextButton(onClick = { confirming = true }) {
                    Text(
                        stringResource(R.string.setup_action_not_now),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    if (confirming) {
        DismissConfirmation(
            requirement = requirement,
            onKeep = { confirming = false },
            onHide = { confirming = false; onDismiss() },
        )
    }
}

/**
 * Hiding a nudge, made deliberately expensive.
 *
 * Two dialogs, not one. The first says what stops working — in the concrete, not "some features
 * may be limited" — and the second says where it goes and what will bring it back. Someone who
 * genuinely means it gets there in three taps; someone swatting a card away lands on a sentence
 * explaining that their child's screen time is not being counted, which is the whole point.
 */
@Composable
private fun DismissConfirmation(
    requirement: DeviceRequirement,
    onKeep: () -> Unit,
    onHide: () -> Unit,
) {
    var stage by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onKeep,
        title = {
            Text(
                stringResource(
                    if (stage == 1) R.string.setup_dismiss_title else R.string.setup_dismiss_confirm_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                if (stage == 1) {
                    Text(stringResource(R.string.setup_dismiss_consequence))
                    Text(
                        stringResource(requirement.bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(stringResource(R.string.setup_dismiss_where))
                }
            }
        },
        confirmButton = {
            // The prominent button is always "keep it", at both stages.
            TextButton(onClick = onKeep) { Text(stringResource(R.string.setup_dismiss_keep)) }
        },
        dismissButton = {
            TextButton(onClick = { if (stage == 1) stage = 2 else onHide() }) {
                Text(
                    stringResource(
                        if (stage == 1) R.string.setup_dismiss_continue else R.string.setup_dismiss_hide,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}

/**
 * What "Turn on" does for a requirement.
 *
 * Two of these are runtime permissions rather than settings screens, and asking the system first
 * is strictly better when it will still prompt: it is one tap instead of a trip through Settings.
 * When the system has stopped prompting (denied for good), the deep link is the only way left.
 */
@Composable
private fun rememberFixAction(requirement: DeviceRequirement, onFixed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onFixed() else DeviceSetupProbe.openFix(context, DeviceRequirement.NOTIFICATIONS)
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onFixed() else DeviceSetupProbe.openFix(context, DeviceRequirement.LOCATION_PERMISSION)
    }
    // Consent for the DNS tunnel: prepare() returns an intent when the user must agree, and null
    // when they already have (which is always the case on a Device Owner child).
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        startWebFilter(context)
        onFixed()
    }

    return when (requirement) {
        DeviceRequirement.NOTIFICATIONS -> {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Pre-13 there is no runtime permission — it was switched off in Settings.
                    DeviceSetupProbe.openFix(context, requirement)
                }
            }
        }
        DeviceRequirement.LOCATION_PERMISSION -> {
            { location.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        }
        DeviceRequirement.WEB_FILTER -> {
            {
                val consent = runCatching { android.net.VpnService.prepare(context) }.getOrNull()
                if (consent != null) {
                    vpnConsent.launch(consent)
                } else {
                    startWebFilter(context)
                    // The tunnel takes a moment to establish; re-probe once it has had one.
                    scope.launch {
                        kotlinx.coroutines.delay(1_500)
                        onFixed()
                    }
                }
            }
        }
        else -> {
            { DeviceSetupProbe.openFix(context, requirement) }
        }
    }
}

private fun startWebFilter(context: Context) {
    runCatching { dev.walcott.net.VpnController.apply(context, true) }
}

internal fun iconFor(requirement: DeviceRequirement): ImageVector = when (requirement) {
    DeviceRequirement.NOTIFICATIONS -> Icons.Outlined.NotificationsOff
    DeviceRequirement.USAGE_ACCESS -> Icons.Outlined.Timer
    DeviceRequirement.ACCESSIBILITY -> Icons.Outlined.TouchApp
    DeviceRequirement.WEB_FILTER -> Icons.Outlined.Language
    DeviceRequirement.LOCATION_PERMISSION, DeviceRequirement.LOCATION_SERVICE -> Icons.Outlined.LocationOff
    DeviceRequirement.BATTERY_OPTIMIZATION -> Icons.Outlined.BatteryAlert
}

internal fun warningIcon(): ImageVector = Icons.Filled.Warning
