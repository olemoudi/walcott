package dev.walcott.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import dev.walcott.R
import dev.walcott.ui.theme.Tokens

/**
 * "Updating…" — this setting has been changed here and is still waiting to be sent.
 *
 * Deliberately its own visual language, distinct from the warnings elsewhere: nothing is wrong,
 * something is simply in flight. Amber rather than red, and the circular arrows turning, because
 * a still icon beside the word "updating" reads as stuck.
 *
 * The rotation is the one animation in the app that runs indefinitely, which is affordable
 * because it only exists while a burst of edits is being held — at most half a minute, on a
 * screen the parent is actively looking at.
 */
@Composable
fun PendingChip(modifier: Modifier = Modifier) {
    val amber = Color(0xFFB26A00)
    val transition = rememberInfiniteTransition(label = "pending")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(amber.copy(alpha = 0.14f))
            .padding(horizontal = Tokens.spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Sync,
            contentDescription = null,
            tint = amber,
            modifier = Modifier.size(13.dp).rotate(angle),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.pending_push_chip),
            style = MaterialTheme.typography.labelSmall,
            color = amber,
        )
    }
}
