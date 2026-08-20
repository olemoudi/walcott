package dev.walcott.ui.child

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.PanicProtocol
import dev.walcott.sync.SyncManager
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * The child's way out when the parent device is gone AND the parent PIN is lost: a 24-hour
 * request that keeps telling the parents what it is doing (see [PanicProtocol]).
 *
 * The screen leads with what will happen, not with the button. Everything about this flow is
 * meant to be slow, loud and refusable, so the honest thing is to say so before it starts —
 * a child who reads this and still wants out is exactly who it exists for.
 */
@Composable
fun PanicScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    val status by viewModel.panicStatus.collectAsStateWithLifecycle()
    val snackbar = dev.walcott.ui.components.LocalSnackbar.current
    val refusedMessage = stringResource(R.string.panic_start_refused)
    var confirming by remember { mutableStateOf(false) }

    // Local tick: the countdown is anchored to the server's clock but extrapolated locally,
    // so it keeps moving between the messages that actually re-anchor it.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.panic_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen).padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IntroCard()
            HowItWorksCard()

            val request = status.request
            if (request != null) {
                ActiveRequestCard(status, nowMs)
                OutlinedButton(
                    onClick = { viewModel.cancelPanic() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.panic_cancel_action)) }
            } else {
                val canStart = status.canStart(nowMs)
                Button(
                    onClick = { confirming = true },
                    enabled = canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.panic_start_action)) }
                blockedReason(status, nowMs)?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Only promise the instant way out when it exists: with no PIN on this device
            // nothing can authorise a release, and telling a child to go ask their parents for
            // one sends them into a dialog that rejects every answer.
            val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
            if (hasPin) {
                Text(
                    stringResource(R.string.panic_pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.panic_confirm_title)) },
            text = { Text(stringResource(R.string.panic_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    // The gate is checked again where the request is written, against the store
                    // rather than this screen's copy of it — so it can say no even though the
                    // button was live. Silently doing nothing is the one answer this screen must
                    // never give: the child came here because they are out of other options.
                    scope.launch { if (!viewModel.startPanic()) snackbar.show(refusedMessage) }
                }) { Text(stringResource(R.string.panic_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Why the button is greyed out, or null when it isn't. Ordered by what the child can fix. */
@Composable
private fun blockedReason(status: SyncManager.PanicStatus, nowMs: Long): String? = when {
    // "No server clock yet" is told as being offline, because for the child that is exactly what
    // it is: the family has just moved relay and this phone has not heard from the new one. It has
    // to come BEFORE the lockout line as well as before the grey button — measured against a clock
    // of zero, three days of cooldown reads as fifty-seven years of it.
    !status.channelProven(nowMs) || !PanicProtocol.anchored(status.serverNowSec) ->
        stringResource(R.string.panic_blocked_offline)
    !PanicProtocol.cooldownPassed(status.blockedUntilSec, status.serverNowSec) -> stringResource(
        R.string.panic_blocked_denied,
        Duration.ofSeconds(status.cooldownRemainingSec).humanize(),
    )
    !status.parentSupported -> stringResource(R.string.panic_blocked_old_parent)
    else -> null
}

@Composable
private fun IntroCard() {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(color = color.copy(alpha = 0.10f), modifier = Modifier.padding(top = spacing.sm)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(stringResource(R.string.panic_intro_title), style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    stringResource(R.string.panic_intro_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.panic_how_title), style = MaterialTheme.typography.titleSmall)
            listOf(
                R.string.panic_how_1,
                R.string.panic_how_2,
                R.string.panic_how_3,
                R.string.panic_how_4,
            ).forEach { step ->
                Text(
                    stringResource(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Progress of a live request: notices sent, when the next one goes out, how long is left. */
@Composable
private fun ActiveRequestCard(status: SyncManager.PanicStatus, nowMs: Long) {
    val spacing = Tokens.spacing
    val request = status.request ?: return
    val remaining = PanicProtocol.remainingCheckpoints(request)
    val nextSec = secondsToNextNotice(status, nowMs)
    val leftSec = (remaining - 1).coerceAtLeast(0) * PanicProtocol.CHECKPOINT_INTERVAL_SEC + nextSec
    val progress by animateFloatAsState(PanicProtocol.progress(request), tween(Tokens.motion.medium), label = "panic")

    WalcottCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                stringResource(R.string.panic_active_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.panic_active_notices,
                    request.checkpoints,
                    PanicProtocol.REQUIRED_CHECKPOINTS,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                stringResource(R.string.panic_active_next, Duration.ofSeconds(nextSec).humanize()),
                style = MaterialTheme.typography.bodySmall,
            )
            // Rounded, not truncated: 23 h 58 m left is "about 24 hours", not "about 23".
            val hoursLeft = ((leftSec + 1800) / 3600).toInt()
            Text(
                pluralStringResource(R.plurals.panic_active_left, hoursLeft, hoursLeft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Seconds until the next notice is due: the server-clock gap, extrapolated with the time
 * elapsed locally since that clock was last confirmed. Server-anchored so it can't be gamed,
 * locally ticked so it doesn't freeze between messages.
 */
private fun secondsToNextNotice(status: SyncManager.PanicStatus, nowMs: Long): Long {
    val request = status.request ?: return 0
    val sinceProofSec = ((nowMs - status.lastChannelOkMs) / 1000).coerceAtLeast(0)
    return (PanicProtocol.dueSec(request) - status.serverNowSec - sinceProofSec).coerceAtLeast(0)
}
