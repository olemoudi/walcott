package dev.walcott.ui.parent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.sync.DeviceMode
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.format.humanize
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.res.stringResource

@Composable
fun WeeklyReportScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val localWeekly by viewModel.weeklyUsage.collectAsStateWithLifecycle()
    val childrenWeekly by viewModel.childrenWeeklyUsage.collectAsStateWithLifecycle()
    // On a parent phone the local usage is empty; show the children's aggregate instead.
    val weekly = if (identity.effectiveMode == DeviceMode.PARENT) childrenWeekly else localWeekly

    val today = LocalDate.now().toEpochDay()
    val days = (0..6).map { today - 6 + it }
    val dayTotals = days.map { day ->
        weekly[day]?.values?.fold(Duration.ZERO) { acc, d -> acc + d } ?: Duration.ZERO
    }
    val maxSeconds = (dayTotals.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)

    val categoryTotals = mutableMapOf<String, Duration>()
    weekly.values.forEach { byCat ->
        byCat.forEach { (cat, d) -> categoryTotals[cat] = (categoryTotals[cat] ?: Duration.ZERO) + d }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_report_title), onBack)
        Column(Modifier.fillMaxSize().padding(spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            if (categoryTotals.isEmpty()) {
                Text(stringResource(R.string.report_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(180.dp).padding(spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    days.forEachIndexed { i, day ->
                        DayBar(
                            fraction = dayTotals[i].seconds.toFloat() / maxSeconds,
                            label = LocalDate.ofEpochDay(day).dayOfWeek
                                .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            categoryTotals.entries
                .sortedByDescending { it.value.seconds }
                .forEach { (catId, total) ->
                    val category = AppCategory.byId(catId)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(12.dp).height(12.dp).clip(RoundedCornerShape(50))
                            .background(category?.color ?: MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            category?.let { stringResource(it.nameRes) } ?: catId,
                            Modifier.weight(1f),
                        )
                        Text(total.humanize(), style = MaterialTheme.typography.titleSmall)
                    }
                }
        }
    }
}

@Composable
private fun DayBar(fraction: Float, label: String, modifier: Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "bar")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(130.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(animated.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
