package dev.walcott.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.format.humanize
import java.time.Duration

/**
 * A comfortably sized selection chip: a Material [FilterChip] with a taller touch target and
 * a larger label than the compact default, so choices are easy to tap on a phone. Use this
 * for every fixed-value picker (times, intervals, minutes) rather than a bare FilterChip.
 */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        // ~44dp tall with roomier sides — above the compact 32dp default, close to the 48dp target.
        modifier = modifier.heightIn(min = 44.dp),
        shape = FilterChipDefaults.shape,
    )
}

/** Comfortable content padding for chip labels (used where a Surface-based pill mimics a chip). */
val ComfortableChipPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

private val MINUTE_PRESETS = listOf(15, 30, 60)

/**
 * The shared minutes picker used by every "how much time?" dialog (child requests, parent
 * bonus): the common presets plus a custom option up to 24h, all comfortably sized.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MinutesChips(value: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        MINUTE_PRESETS.forEach { m ->
            ChoiceChip(selected = value == m, onClick = { onSelect(m) }, label = stringResource(R.string.extra_minutes, m))
        }
        CustomValueChip(
            selected = value !in MINUTE_PRESETS,
            customLabel = if (value !in MINUTE_PRESETS) stringResource(R.string.extra_minutes, value) else null,
            dialogTitle = stringResource(R.string.custom_minutes_title),
            initial = value,
            onConfirm = onSelect,
        )
    }
}

/**
 * The "custom" tail of a chip-based value picker: a chip that shows the current custom value
 * when one is set, or a generic "Custom" label otherwise, and opens a numeric dialog so the
 * parent can enter any value the presets don't cover. Manages the dialog state itself, so each
 * picker just drops it in after its preset chips.
 */
@Composable
fun CustomValueChip(
    selected: Boolean,
    customLabel: String?,
    dialogTitle: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    minValue: Int = 1,
    maxValue: Int = 1440,
) {
    var editing by remember { mutableStateOf(false) }
    ChoiceChip(
        selected = selected,
        onClick = { editing = true },
        label = customLabel ?: stringResource(R.string.custom_value),
    )
    if (editing) {
        MinutesPickerDialog(
            title = dialogTitle,
            initial = initial,
            minValue = minValue,
            maxValue = maxValue,
            onDismiss = { editing = false },
            onConfirm = {
                onConfirm(it)
                editing = false
            },
        )
    }
}

/** How much one tap of the +/- buttons moves the value. */
private const val STEP_MINUTES = 30

/**
 * Next/previous value when stepping by [step]: from a value that isn't a multiple of the step,
 * the buttons snap to the surrounding multiples (45 → 30 or 60) rather than drifting off-grid.
 * Pure, so the stepping is unit-tested.
 */
internal fun nextStep(value: Int, step: Int = STEP_MINUTES): Int = ((value / step) + 1) * step

internal fun previousStep(value: Int, step: Int = STEP_MINUTES): Int =
    if (value % step == 0) value - step else (value / step) * step

/**
 * Picks a duration in minutes: big +/- buttons that move in 30-minute steps up to 24h, with the
 * value editable by hand for anything in between, and a readable preview ("2h 30m") of what the
 * number actually means.
 */
@Composable
fun MinutesPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    minValue: Int = 1,
    maxValue: Int = 24 * 60,
) {
    var text by remember { mutableStateOf(initial.coerceIn(minValue, maxValue).toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in minValue..maxValue
    // Steppers act on the last valid value, so typing garbage never strands the buttons.
    val current = parsed?.coerceIn(minValue, maxValue) ?: initial.coerceIn(minValue, maxValue)

    fun set(value: Int) {
        text = value.coerceIn(minValue, maxValue).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { set(previousStep(current)) },
                        enabled = current > minValue,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.minutes_decrease),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        isError = text.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.width(120.dp),
                    )
                    FilledTonalIconButton(
                        onClick = { set(nextStep(current)) },
                        enabled = current < maxValue,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.minutes_increase),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                // What the number means, so nobody has to divide 150 by 60 in their head.
                Text(
                    if (valid) Duration.ofMinutes(parsed!!.toLong()).humanize() else "—",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.minutes_range, minValue, maxValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(parsed!!) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
