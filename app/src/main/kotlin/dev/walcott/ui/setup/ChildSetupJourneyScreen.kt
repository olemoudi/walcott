package dev.walcott.ui.setup

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.setup.DeviceRequirement
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * The guided setup a parent runs on the child's phone, once, right after enrolling it.
 *
 * Enrollment used to end at the pairing QR, and everything the rules actually need to work —
 * usage access, the accessibility blocker, notifications, location, the battery exemption — was
 * left to a stack of cards on the child's home for whoever happened to read them. That is the
 * wrong person at the wrong moment: the child has no reason to grant any of it, and the parent
 * has already handed the phone back by the time the cards appear.
 *
 * So the app asks for them while the parent is still holding the device, one at a time, in the
 * order of how much breaks without them. Three things make it work:
 *
 * - every step deep-links to the exact system screen that grants it (see [DeviceSetupProbe]);
 * - coming back from that screen re-probes and the step advances by itself, so the flow never
 *   asks anyone to confirm what it can see for itself;
 * - it can be left at any point. A refusal is a real answer, and what is still missing survives
 *   in the home-screen cards, the device's own periodic nudge, and the parent's reminder.
 *
 * Deliberately NOT behind the parent PIN: it grants permissions and shows nothing private, and
 * a gate here would ask for the PIN at the exact moment the parent is halfway through typing
 * one into a system dialog. The screens that change rules are the ones the gate is for.
 */
@Composable
fun ChildSetupJourneyScreen(
    handle: DeviceSetupHandle,
    /** Whose phone this is, for the opening line; blank falls back to a generic title. */
    childName: String,
    /** Seen through to the end: the journey is recorded as done. */
    onFinish: () -> Unit,
    /** Left early: nothing is recorded, so the home keeps offering it. */
    onExit: () -> Unit,
) {
    val spacing = Tokens.spacing

    // The steps are the requirements that were missing when the parent pressed Start, captured
    // once, there. Live-tracking `handle.unmet` instead would renumber the flow under their
    // finger ("step 2 of 4" becoming "step 2 of 3" as they grant things) and the summary could
    // no longer say what was asked for. What each step DOES is still read live from `unmet`.
    //
    // At Start rather than when the screen opens, because this screen opens the instant a device
    // is paired — before the family's rules have arrived over the channel. A web filter or
    // location tracking the parent turned on is not yet KNOWN to be wanted here, and a list
    // frozen a second too early would silently skip the steps for both. The rules land while the
    // opening screen is being read.
    var steps by remember { mutableStateOf<List<DeviceRequirement>?>(null) }
    val stepList = steps.orEmpty()

    // 0 = the opening screen, 1..n = one requirement each, n+1 = the summary. Held at the
    // opening screen until the first probe answers: a cursor restored after a process death
    // would otherwise land on a summary computed from a list nothing had read yet.
    var cursor by rememberSaveable { mutableIntStateOf(0) }
    val summaryAt = stepList.size + 1
    val current = if (steps == null) 0 else cursor.coerceIn(0, summaryAt)
    val requirement = stepList.getOrNull(current - 1)
    val satisfied = requirement != null && requirement !in handle.unmet

    // Granted — either in the system screen we sent them to, or by a dialog that happened to
    // cover it. The tick is left on screen for a beat so the step reads as completed rather
    // than as one that flicked past.
    LaunchedEffect(current, satisfied) {
        if (satisfied) {
            delay(TICK_DWELL_MS)
            if (cursor == current) cursor = current + 1
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.journey_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = spacing.sm),
            )
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
        val progress by animateFloatAsState(
            targetValue = current.toFloat() / (summaryAt.coerceAtLeast(1)),
            animationSpec = tween(Tokens.motion.medium, easing = Tokens.motion.emphasized),
            label = "journeyProgress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen),
        )

        // Read here, not inside the spec: the motion tokens come from a composition local, and
        // the transition lambda runs outside composition.
        val slide = Tokens.motion.medium
        val fade = Tokens.motion.fast
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = if (targetState >= initialState) 1 else -1
                (slideInHorizontally(tween(slide)) { w -> dir * w / 4 } + fadeIn(tween(slide)))
                    .togetherWith(
                        slideOutHorizontally(tween(slide)) { w -> -dir * w / 4 } + fadeOut(tween(fade)),
                    )
            },
            label = "journeyStep",
            modifier = Modifier.weight(1f),
        ) { screen ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                when {
                    // Live count on the opening screen: it is the one screen where the list is
                    // still moving (see `steps`), and a stale number here is the first thing
                    // the parent would catch the app out on.
                    screen == 0 -> IntroStep(
                        childName = childName,
                        pending = handle.unmet.size,
                        ready = handle.loaded,
                    )
                    screen >= summaryAt -> SummaryStep(steps = stepList, unmet = handle.unmet)
                    else -> stepList.getOrNull(screen - 1)?.let { step ->
                        RequirementStep(
                            requirement = step,
                            index = screen,
                            total = stepList.size,
                            done = step !in handle.unmet,
                        )
                    }
                }
            }
        }

        // One primary action per screen, always in the same place. The way past a step the
        // parent will not grant is the quiet one — the same asymmetry the nudge cards use.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                current == 0 -> Button(
                    onClick = {
                        steps = handle.unmet
                        cursor = 1
                    },
                    enabled = handle.loaded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (handle.unmet.isEmpty()) R.string.journey_intro_check else R.string.journey_start,
                        ),
                    )
                }
                current >= summaryAt -> {
                    val stillMissing = stepList.any { it in handle.unmet }
                    if (stillMissing) {
                        OutlinedButton(
                            onClick = { cursor = stepList.indexOfFirst { it in handle.unmet } + 1 },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.journey_retry)) }
                    }
                    Button(
                        onClick = { handle.markJourneyDone(); onFinish() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (stillMissing) R.string.journey_finish_anyway else R.string.journey_finish,
                            ),
                        )
                    }
                }
                else -> {
                    val step = stepList[current - 1]
                    val fix = rememberFixAction(step, handle::refreshNow)
                    Button(onClick = fix, enabled = step in handle.unmet, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setup_action_turn_on))
                    }
                    TextButton(onClick = { cursor = current + 1 }) {
                        Text(
                            stringResource(R.string.journey_skip),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** How long a just-granted step keeps its tick on screen before the flow moves on. */
private const val TICK_DWELL_MS = 700L

@Composable
private fun ColumnScope.IntroStep(childName: String, pending: Int, ready: Boolean) {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xl))
    Icon(
        Icons.Outlined.PhonelinkSetup,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally),
    )
    Text(
        if (childName.isBlank()) {
            stringResource(R.string.journey_intro_title)
        } else {
            stringResource(R.string.journey_intro_title_named, childName)
        },
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.journey_intro_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    // Said up front, because "how long is this going to take" is the question that decides
    // whether the parent starts it now or means to come back later and never does.
    if (ready) {
        Text(
            if (pending == 0) {
                stringResource(R.string.journey_intro_nothing)
            } else {
                androidx.compose.ui.res.pluralStringResource(R.plurals.journey_intro_count, pending, pending)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RequirementStep(
    requirement: DeviceRequirement,
    index: Int,
    total: Int,
    done: Boolean,
) {
    val spacing = Tokens.spacing
    val accent =
        if (requirement.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    val color = if (done) MaterialTheme.colorScheme.primary else accent
    Text(
        stringResource(R.string.journey_step_of, index, total),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else iconFor(requirement),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Text(
            stringResource(requirement.titleRes),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(requirement.bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // What the button is about to do. A parent sent into the system settings without warning
    // reads it as the app having crashed out to somewhere else.
    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                stringResource(if (done) R.string.journey_step_done else R.string.journey_step_how),
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
            if (!done) {
                Text(
                    stringResource(R.string.journey_step_how_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SummaryStep(steps: List<DeviceRequirement>, unmet: List<DeviceRequirement>) {
    val spacing = Tokens.spacing
    val missing = steps.filter { it in unmet }
    Spacer(Modifier.height(spacing.md))
    Icon(
        if (missing.isEmpty()) Icons.Filled.CheckCircle else warningIcon(),
        contentDescription = null,
        tint = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
    )
    Text(
        stringResource(if (missing.isEmpty()) R.string.journey_done_title else R.string.journey_partial_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(if (missing.isEmpty()) R.string.journey_done_body else R.string.journey_partial_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    // Every step that was asked for, with what became of it. A summary of the failures alone
    // would leave the parent unable to tell a finished job from one that was never checked.
    if (steps.isNotEmpty()) {
        Spacer(Modifier.height(spacing.sm))
        WalcottCard {
            Column(Modifier.padding(spacing.lg)) {
                steps.forEach { requirement ->
                    val done = requirement !in unmet
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (done) Icons.Filled.CheckCircle else iconFor(requirement),
                            contentDescription = null,
                            tint = if (done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(spacing.md))
                        Text(
                            stringResource(requirement.titleRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(if (done) R.string.journey_state_on else R.string.journey_state_pending),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}
