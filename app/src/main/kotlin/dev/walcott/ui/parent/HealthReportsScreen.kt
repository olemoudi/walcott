package dev.walcott.ui.parent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.DiagPayload
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.StoredDiag
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import java.util.Locale

/**
 * The child's recent health reports. Each one is a snapshot of a moment — deliberately kept
 * out of the child screen, where its dated rows would read as the device's current state
 * (that comes from the check-in, see [LiveHealthCard]). Here the date is the point.
 */
@Composable
fun HealthReportsScreen(viewModel: WalcottViewModel, childId: String, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val children by viewModel.children.collectAsStateWithLifecycle()
    val history by viewModel.diagHistory.collectAsStateWithLifecycle()
    val snapshot = children.firstOrNull { it.childId == childId }
    val reports = snapshot?.let { history[it.deviceId] }.orEmpty()
    val appLabels = remember(snapshot?.apps) {
        snapshot?.apps?.associate { it.packageName to it.label }.orEmpty()
    }
    var requestedAtMs by remember(childId) { mutableStateOf(0L) }
    val waiting = requestedAtMs > 0 && reports.firstOrNull()?.report?.atMs.let { it == null || it < requestedAtMs }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.diag_section), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.diag_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        snapshot?.let {
                            viewModel.sendRemoteCommand(it.deviceId, RemoteAction.DIAGNOSE)
                            requestedAtMs = System.currentTimeMillis()
                        }
                    },
                    enabled = snapshot != null && !waiting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (reports.isEmpty()) R.string.diag_request else R.string.diag_refresh)) }
            }
            if (waiting) {
                item {
                    Text(
                        stringResource(R.string.remote_command_sent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reports.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.diag_none_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
            // The newest is expanded on arrival; the rest are one line each until asked for.
            // Keyed by position as well as by instant: a parent updating from a build that could
            // file the same report twice (see SyncManager.applyDiagPayload) still has the
            // duplicate on disk, and a repeated key is a crash, not a cosmetic problem.
            itemsIndexed(reports, key = { index, it -> "${it.report.atMs}-$index" }) { index, stored ->
                ReportCard(stored = stored, appLabels = appLabels, initiallyExpanded = index == 0)
            }
        }
    }
}

@Composable
private fun ReportCard(stored: StoredDiag, appLabels: Map<String, String>, initiallyExpanded: Boolean) {
    val spacing = Tokens.spacing
    val report = stored.report
    var expanded by remember(report.atMs) { mutableStateOf(initiallyExpanded) }
    var showLog by remember(report.atMs) { mutableStateOf(false) }
    val problems = remember(stored) { report.problems(stored.seenAtVersionCode) }
    val stamp = remember(report.atMs) {
        java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
            .format(java.util.Date(report.atMs))
    }

    WalcottCard(onClick = { expanded = !expanded }) {
        Column(Modifier.padding(spacing.lg).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (problems == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (problems == 0) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(stamp, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (problems == 0) {
                            stringResource(R.string.diag_all_clear)
                        } else {
                            pluralStringResource(R.plurals.diag_problems, problems, problems)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                ReportRows(stored, appLabels)
                if (report.logLines.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(
                            if (showLog) {
                                stringResource(R.string.diag_hide_log)
                            } else {
                                stringResource(R.string.diag_show_log, report.logLines.size)
                            },
                        )
                    }
                    if (showLog) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                report.logLines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                modifier = Modifier.padding(spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRows(stored: StoredDiag, appLabels: Map<String, String>) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val report = stored.report
    DiagRow(
        label = stringResource(R.string.diag_enforcement),
        value = stringResource(
            when (report.enforcement) {
                EnforcementStatus.DEVICE_OWNER -> R.string.diag_enforcement_do
                EnforcementStatus.ACCESSIBILITY -> R.string.diag_enforcement_accessibility
                else -> R.string.diag_enforcement_none
            },
        ),
        ok = report.enforcement == EnforcementStatus.DEVICE_OWNER,
    )
    DiagRow(
        label = stringResource(R.string.diag_usage_access),
        value = stringResource(if (report.usageAccess) R.string.summary_on else R.string.summary_off),
        ok = report.usageAccess,
    )
    DiagRow(
        label = stringResource(R.string.diag_location_permission),
        value = stringResource(if (report.locationPermission) R.string.summary_on else R.string.summary_off),
        ok = report.locationPermission,
    )
    DiagRow(
        label = stringResource(R.string.diag_gps),
        value = stringResource(if (report.gpsOn) R.string.summary_on else R.string.summary_off),
        ok = report.gpsOn,
    )
    DiagRow(
        label = stringResource(R.string.diag_network_location),
        value = stringResource(if (report.networkLocationOn) R.string.summary_on else R.string.summary_off),
        ok = report.networkLocationOn,
    )
    if (report.batteryPercent in 0..100) {
        DiagRow(
            label = stringResource(R.string.diag_battery),
            value = stringResource(
                if (report.charging) R.string.diag_battery_charging else R.string.diag_battery_value,
                report.batteryPercent,
            ),
            ok = report.charging || report.batteryPercent >= LOW_BATTERY_PERCENT,
        )
    }
    if (report.appVersionCode > 0) {
        DiagRow(
            label = stringResource(R.string.diag_version),
            value = stringResource(R.string.diag_version_value, report.appVersionName, report.appVersionCode),
            ok = !stored.versionWasBehind(),
        )
    }
    if (report.updateError.isNotBlank()) {
        DiagRow(
            label = stringResource(R.string.diag_update_error),
            value = remoteResultLabel(context, report.updateError),
            ok = report.updateError in UPDATE_WAITS_ON_PURPOSE,
        )
    }
    if (report.suspendFailures.isNotEmpty()) {
        // Its own block, not a DiagRow: several package names on the value side of a two-column
        // row squeeze the label into a sliver and the row grows into a column of white space.
        Text(
            stringResource(R.string.diag_suspend_failures),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = spacing.xs),
        )
        report.suspendFailures.forEach { packageName ->
            Text(
                appLabels[packageName] ?: packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
    }
}

@Composable
internal fun DiagRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        // Weighted too: an unweighted value is measured first, so a long one takes the row and
        // crushes the label into a one-word-per-line column of white space.
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Below this, and not charging, the battery is worth flagging (matches the parent's alert). */
internal const val LOW_BATTERY_PERCENT = 20

/**
 * Whether the child was behind on the app when the report was FILED — never against what the
 * parent runs today. A release published afterwards is not this snapshot's fault, and an entry
 * filed before the stamp existed ([StoredDiag.seenAtVersionCode] = 0) can't tell either way.
 */
internal fun StoredDiag.versionWasBehind(): Boolean =
    seenAtVersionCode > 0 && report.appVersionCode in 1 until seenAtVersionCode

/**
 * How many things this report found wrong, so a list row can say what it holds without
 * rendering it. Pure, and the same conditions the rows colour red.
 */
internal fun DiagPayload.problems(seenAtVersionCode: Int): Int {
    var count = 0
    if (enforcement != EnforcementStatus.DEVICE_OWNER) count++
    if (!usageAccess) count++
    if (!locationPermission) count++
    if (!gpsOn) count++
    if (!networkLocationOn) count++
    if (batteryPercent in 0 until LOW_BATTERY_PERCENT && !charging) count++
    if (updateError.isNotBlank() && updateError !in UPDATE_WAITS_ON_PURPOSE) count++
    if (StoredDiag(this, seenAtVersionCode).versionWasBehind()) count++
    if (suspendFailures.isNotEmpty()) count++
    return count
}
