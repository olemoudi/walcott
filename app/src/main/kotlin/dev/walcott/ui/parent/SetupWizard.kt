package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.SetupPresets
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.rules.DayType
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.LocalTime

/**
 * Guided setup: three presets a parent can run right after creating the family — or any time
 * later from the family screen. Every step teaches what the setting does, applies the change
 * immediately (so skipping a step simply changes nothing), shows how far along the wizard is,
 * and nothing here is irreversible: each screen the wizard touches stays editable on its own.
 */

enum class WizardStep { BEDTIME, SCREEN_TIME, PROTECTION, LOCATION, EARN, WEBFILTER, SUMMARY }

enum class SetupPreset(val minutes: Int, val steps: List<WizardStep>) {
    BASIC(2, listOf(WizardStep.BEDTIME, WizardStep.SCREEN_TIME, WizardStep.SUMMARY)),
    RECOMMENDED(
        4,
        listOf(
            WizardStep.BEDTIME, WizardStep.SCREEN_TIME, WizardStep.PROTECTION,
            WizardStep.LOCATION, WizardStep.SUMMARY,
        ),
    ),
    FULL(
        8,
        listOf(
            WizardStep.BEDTIME, WizardStep.SCREEN_TIME, WizardStep.PROTECTION, WizardStep.LOCATION,
            WizardStep.EARN, WizardStep.WEBFILTER, WizardStep.SUMMARY,
        ),
    ),
}

// --- Chooser ---

@Composable
fun SetupPresetChooserScreen(onPick: (SetupPreset) -> Unit, onSkip: () -> Unit) {
    val spacing = Tokens.spacing
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.setup_presets_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.setup_presets_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm, bottom = spacing.xl),
        )
        PresetCard(
            title = stringResource(R.string.preset_basic_title),
            description = stringResource(R.string.preset_basic_desc),
            preset = SetupPreset.BASIC,
            onClick = { onPick(SetupPreset.BASIC) },
        )
        Spacer(Modifier.height(spacing.md))
        PresetCard(
            title = stringResource(R.string.preset_recommended_title),
            description = stringResource(R.string.preset_recommended_desc),
            preset = SetupPreset.RECOMMENDED,
            highlighted = true,
            onClick = { onPick(SetupPreset.RECOMMENDED) },
        )
        Spacer(Modifier.height(spacing.md))
        PresetCard(
            title = stringResource(R.string.preset_full_title),
            description = stringResource(R.string.preset_full_desc),
            preset = SetupPreset.FULL,
            onClick = { onPick(SetupPreset.FULL) },
        )
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().padding(top = spacing.xl)) {
            Text(stringResource(R.string.setup_presets_skip))
        }
    }
}

@Composable
private fun PresetCard(
    title: String,
    description: String,
    preset: SetupPreset,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (highlighted) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        stringResource(R.string.preset_minutes, preset.minutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

// --- Wizard ---

@Composable
fun SetupWizardScreen(
    viewModel: WalcottViewModel,
    preset: SetupPreset,
    onDone: () -> Unit,
    onExit: () -> Unit,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = preset.steps
    val step = steps[stepIndex.coerceIn(0, steps.size - 1)]

    Column(Modifier.fillMaxSize()) {
        // Header: where we are, how much is left.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = spacing.sm)) {
                Text(
                    stringResource(
                        when (preset) {
                            SetupPreset.BASIC -> R.string.preset_basic_title
                            SetupPreset.RECOMMENDED -> R.string.preset_recommended_title
                            SetupPreset.FULL -> R.string.preset_full_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.wizard_progress, stepIndex + 1, steps.size) + " · " +
                        stringResource(R.string.preset_minutes, preset.minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
        // Progress eases forward instead of jumping — the bar itself rewards each step.
        val progress by animateFloatAsState(
            targetValue = (stepIndex + 1f) / steps.size,
            animationSpec = tween(Tokens.motion.medium, easing = Tokens.motion.emphasized),
            label = "wizardProgress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen),
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = steps.indexOf(targetState) >= steps.indexOf(initialState)
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(220)) { w -> dir * w / 4 } + fadeIn(tween(180)))
                    .togetherWith(slideOutHorizontally(tween(220)) { w -> -dir * w / 4 } + fadeOut(tween(140)))
            },
            label = "wizardStep",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                when (current) {
                    WizardStep.BEDTIME -> BedtimeStep(viewModel)
                    WizardStep.SCREEN_TIME -> ScreenTimeStep(viewModel)
                    WizardStep.PROTECTION -> ProtectionStep(viewModel)
                    WizardStep.LOCATION -> LocationStep(viewModel)
                    WizardStep.EARN -> EarnStep(viewModel)
                    WizardStep.WEBFILTER -> WebFilterStep(viewModel)
                    WizardStep.SUMMARY -> SummaryStep(settings = settings, steps = steps)
                }
            }
        }

        // Footer: back / next. Every step applies its change the moment it's made, so
        // "next" never loses anything and there is no separate skip.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (stepIndex > 0) {
                OutlinedButton(onClick = { stepIndex-- }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.back))
                }
            }
            Button(
                onClick = { if (stepIndex == steps.size - 1) onDone() else stepIndex++ },
                modifier = Modifier.weight(2f),
            ) {
                Text(
                    stringResource(
                        if (stepIndex == steps.size - 1) R.string.wizard_finish else R.string.enroll_next,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, teach: String) {
    val spacing = Tokens.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(spacing.sm))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    Text(teach, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BedtimeStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    StepHeader(
        Icons.Outlined.Bedtime,
        stringResource(R.string.bedtime_title),
        stringResource(R.string.step_bedtime_teach),
    )
    // The shared bedtime editor: the switch turns it on with 21:30–07:30 prefilled.
    BedtimeCard(bedtime = settings.bedtime, onChange = { viewModel.setBedtime(it) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenTimeStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.Schedule,
        stringResource(R.string.step_screen_time_title),
        stringResource(R.string.step_screen_time_teach),
    )
    // Selection derives from the stored policy (games' school budget as the representative),
    // so re-entering the wizard shows what is actually configured.
    val current = settings.budgets[SetupPresets.LEISURE_CATEGORY_IDS.first()]?.get(DayType.SCHOOL.name)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        FilterChip(
            selected = current == null,
            onClick = { viewModel.setLeisureBudget(null) },
            label = { Text(stringResource(R.string.no_limit)) },
        )
        listOf(60, 90, 120, 180).forEach { minutes ->
            FilterChip(
                selected = current == minutes,
                onClick = { viewModel.setLeisureBudget(minutes) },
                label = { Text(Duration.ofMinutes(minutes.toLong()).humanize()) },
            )
        }
    }
}

@Composable
private fun ProtectionStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.Security,
        stringResource(R.string.nav_protection_title),
        stringResource(R.string.step_protection_teach),
    )
    val blockInstalls = DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions
    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.step_protection_installs), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.step_protection_installs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = blockInstalls, onCheckedChange = { viewModel.applyProtectionPreset(it) })
        }
    }
    // When installs aren't blocked, offer the softer option: just be told a new app appeared.
    if (!blockInstalls) {
        Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.new_app_alerts_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.new_app_alerts_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.newAppAlerts, onCheckedChange = { viewModel.setNewAppAlerts(it) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.LocationOn,
        stringResource(R.string.nav_location_title),
        stringResource(R.string.step_location_teach),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        FilterChip(
            selected = settings.trackingIntervalMinutes == 0,
            onClick = { viewModel.setFamilyTrackingInterval(0) },
            label = { Text(stringResource(R.string.tracking_off)) },
        )
        listOf(15, 30, 60).forEach { minutes ->
            FilterChip(
                selected = settings.trackingIntervalMinutes == minutes,
                onClick = { viewModel.setFamilyTrackingInterval(minutes) },
                label = { Text(stringResource(R.string.tracking_minutes_fmt, minutes)) },
            )
        }
    }
}

@Composable
private fun EarnStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.EmojiEvents,
        stringResource(R.string.nav_earn_title),
        stringResource(R.string.step_earn_teach),
    )
    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.step_earn_enable), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.step_earn_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.idleEarn != null,
                onCheckedChange = { on -> viewModel.setIdleEarn(if (on) SetupPresets.defaultIdleEarn() else null) },
            )
        }
    }
}

@Composable
private fun WebFilterStep(viewModel: WalcottViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.Language,
        stringResource(R.string.nav_webfilter_title),
        stringResource(R.string.step_webfilter_teach),
    )
    var domain by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            label = { Text(stringResource(R.string.step_webfilter_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = domain.isNotBlank(),
            onClick = {
                viewModel.addBlockedDomain(domain)
                domain = ""
            },
        ) { Text(stringResource(R.string.action_add)) }
    }
    if (settings.blockedDomains.isNotEmpty()) {
        Text(
            settings.blockedDomains.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryStep(settings: dev.walcott.data.PolicySettings, steps: List<WizardStep>) {
    val spacing = Tokens.spacing
    StepHeader(
        Icons.Outlined.AutoFixHigh,
        stringResource(R.string.step_summary_title),
        stringResource(R.string.step_summary_teach),
    )
    // Only recap what THIS preset walked through: the basic wizard never asked about
    // protection or location, so listing them as "Off" would read as something undone.
    val bedtime = settings.bedtime[DayType.SCHOOL.name]
    val leisure = settings.budgets[SetupPresets.LEISURE_CATEGORY_IDS.first()]?.get(DayType.SCHOOL.name)
    if (WizardStep.BEDTIME in steps) {
        SummaryRow(
            stringResource(R.string.bedtime_title),
            bedtime?.let {
                stringResource(
                    R.string.bedtime_range,
                    LocalTime.ofSecondOfDay(it.startMinute * 60L).hhmm(),
                    LocalTime.ofSecondOfDay(it.endMinute * 60L).hhmm(),
                )
            } ?: stringResource(R.string.summary_not_set),
            bedtime != null,
        )
    }
    if (WizardStep.SCREEN_TIME in steps) {
        SummaryRow(
            stringResource(R.string.step_screen_time_title),
            leisure?.let { Duration.ofMinutes(it.toLong()).humanize() } ?: stringResource(R.string.no_limit),
            leisure != null,
        )
    }
    if (WizardStep.PROTECTION in steps) {
        SummaryRow(
            stringResource(R.string.step_protection_installs),
            stringResource(
                if (DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions) R.string.summary_on
                else R.string.summary_off,
            ),
            DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions,
        )
    }
    if (WizardStep.LOCATION in steps) {
        SummaryRow(
            stringResource(R.string.nav_location_title),
            if (settings.trackingIntervalMinutes > 0) {
                stringResource(R.string.tracking_minutes_fmt, settings.trackingIntervalMinutes)
            } else {
                stringResource(R.string.tracking_off)
            },
            settings.trackingIntervalMinutes > 0,
        )
    }
    if (WizardStep.EARN in steps) {
        SummaryRow(
            stringResource(R.string.nav_earn_title),
            stringResource(if (settings.idleEarn != null) R.string.summary_on else R.string.summary_off),
            settings.idleEarn != null,
        )
    }
    Spacer(Modifier.height(spacing.sm))
    Text(
        stringResource(R.string.summary_next_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SummaryRow(label: String, value: String, done: Boolean) {
    val spacing = Tokens.spacing
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
