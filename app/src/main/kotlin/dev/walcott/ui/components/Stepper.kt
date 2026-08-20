package dev.walcott.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R

/** Compact stepper: −/+ around a centered value. Immediate feedback (no dialogs). */
@Composable
fun Stepper(
    valueLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementEnabled: Boolean = true,
    /** False shows the value but refuses both buttons (a read-only, inherited setting). */
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            Icons.Filled.Remove,
            stringResource(R.string.decrement),
            enabled = enabled && decrementEnabled,
            onClick = onDecrement,
        )
        Text(
            valueLabel,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            // A MINIMUM, not a width: 72dp holds the durations this shows ("1h 30m") and keeps
            // the two buttons from shuffling as the value steps. Fixed, it also had to hold
            // "No limit"/"Sin límite", which it cannot — that broke across two lines and pushed
            // the whole row taller than the buttons beside it.
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        StepButton(Icons.Filled.Add, stringResource(R.string.increment), enabled = enabled, onClick = onIncrement)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}
