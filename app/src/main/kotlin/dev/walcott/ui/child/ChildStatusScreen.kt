package dev.walcott.ui.child

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.walcott.R
import dev.walcott.rules.BlockReason
import dev.walcott.rules.CategoryState
import dev.walcott.rules.TimeWindow
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
    onOpenParent: () -> Unit,
) {
    val state by viewModel.childState.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<CategoryStatusUi?>(null) }
    var pendingRemote by remember { mutableStateOf<CategoryStatusUi?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text -> scope.launch { viewModel.pairAsChild(text) } }
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
            state.bedtimeTonight?.let { window ->
                if (!state.bedtimeActive) {
                    item { BedtimeTonightRow(window) }
                }
            }
            items(state.categories, key = { it.category.id }) { card ->
                CategoryCard(card, onRequestExtra = {
                    if (identity.role == Role.CHILD) pendingRemote = card else pending = card
                })
            }
            item { Spacer(Modifier.height(spacing.xl)) }
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
            },
        )
    }
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
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (state.bedtimeActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
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
                        val summary = if (state.categories.isEmpty()) {
                            stringResource(R.string.hero_all_free)
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
private fun CategoryCard(card: CategoryStatusUi, onRequestExtra: () -> Unit) {
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
                        RequestExtraButton(category.color, onRequestExtra)
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
