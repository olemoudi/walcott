package dev.walcott.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.walcott.R

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
        NumberInputDialog(
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

/** A small dialog to type a whole number in [minValue]..[maxValue] (e.g. custom minutes). */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    minValue: Int = 1,
    maxValue: Int = 1440,
) {
    var text by remember { mutableStateOf(initial.coerceIn(minValue, maxValue).toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in minValue..maxValue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.minutes_label)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { Text(stringResource(R.string.minutes_range, minValue, maxValue)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(parsed!!) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
