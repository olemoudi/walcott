package dev.walcott.ui.child

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import dev.walcott.BuildConfig
import dev.walcott.R
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.HelpAsks
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens

/**
 * The home screen of a phone belonging to somebody being helped rather than limited (see
 * [dev.walcott.data.MemberKind]).
 *
 * It is the child screen with almost everything taken away, and the subtractions are the design.
 * That screen answers "how much time have I got left, and how do I get more" — questions this
 * person does not have. What is left is what they DO have: is this thing working, and how do I
 * reach somebody when it isn't.
 *
 * **One button, and it needs no words.** Asking for help here is not a message: it is a kind of
 * its own on the wire ([ChildRequest.KIND_HELP]) precisely so nothing has to be typed, spelled or
 * explained by the person least able to do it right then. The family gets a name and the fact that
 * they pressed it — the conversation itself happens on the telephone, as it always did.
 *
 * The two things that survive from the full screen are the ones that would strand this phone if
 * they were dropped: the permissions it still needs, and the emergency release at the bottom.
 */
@Composable
fun AssistedStatusScreen(
    viewModel: WalcottViewModel,
    onOpenParent: () -> Unit,
    onOpenPanic: () -> Unit,
    onOpenSetupJourney: () -> Unit,
) {
    val spacing = Tokens.spacing
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val channelOfflineSince by viewModel.channelOfflineSince.collectAsStateWithLifecycle()
    val myAsks by viewModel.myPendingAsks.collectAsStateWithLifecycle()
    val askReceipts by viewModel.myAskReceipts.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val panicStatus by viewModel.panicStatus.collectAsStateWithLifecycle()
    val deviceSetup = dev.walcott.ui.setup.rememberDeviceSetup()

    // One unanswered ask at a time. The button that sent it says so instead of offering to send a
    // second one, which is what somebody who is not sure it worked will otherwise do — five times.
    // And it says which of two things is true: written down on this phone and waiting for a
    // connection, or taken by the relay and on its way. It used to say "sent" for both, under a
    // line saying the phone could not reach anybody.
    val helpAsk = myAsks.firstOrNull { it.kind == ChildRequest.KIND_HELP }
    val helpState = when {
        helpAsk == null -> HelpState.READY
        helpAsk.requestId in askReceipts -> HelpState.SENT
        else -> HelpState.WAITING
    }
    // ...for the first ten minutes. After that the button comes back, because nothing except the
    // family pressing "I've helped" ever closes a call for help, and they answer it by telephone:
    // an ask nobody closed used to leave this screen without its one button for two days. Ticked
    // rather than read while drawing — nothing else here changes when the window opens, so a
    // comparison made during composition would sit at "sending" until something unrelated
    // happened (the same freeze the find card's countdown was fixed for).
    val reaskAt = helpAsk?.let { it.createdAtEpochMs + HelpAsks.REASK_AFTER_MS } ?: 0L
    val canReask by produceState(initialValue = reaskAt > 0 && reaskAt <= System.currentTimeMillis(), reaskAt) {
        value = reaskAt > 0 && reaskAt <= System.currentTimeMillis()
        while (reaskAt > System.currentTimeMillis()) {
            delay(REASK_TICK_MS)
            value = reaskAt <= System.currentTimeMillis()
        }
    }
    // The family's answer, which on this screen has one form only: somebody has dealt with it.
    val helpSeen = notice?.takeIf {
        it.kind == ChildRequest.KIND_HELP && it.approved &&
            !dev.walcott.sync.SyncEngine.noticeExpired(it.atMs, System.currentTimeMillis())
    }
    // And the other end of the same story: two days in which nobody came. The ask retires itself
    // and the button simply reappeared, so the one person who needed telling was the only one not
    // told (see SyncManager.NOTICE_HELP_EXPIRED).
    val helpRanOut = notice?.takeIf {
        it.kind == dev.walcott.sync.SyncManager.NOTICE_HELP_EXPIRED &&
            !dev.walcott.sync.SyncEngine.noticeExpired(it.atMs, System.currentTimeMillis())
    }
    val offline = channelOfflineSince != null

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                // Tapping the family name is the way into parent mode, exactly as on the child
                // home: unlabelled on purpose, and behind the PIN either way.
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onOpenParent)
                        .padding(top = spacing.xxl, bottom = spacing.sm),
                ) {
                    Text(
                        settings.familyName.ifBlank { stringResource(R.string.family_default_name) },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.assist_home_title), style = MaterialTheme.typography.headlineMedium)
                }
            }
            // Whether this phone can be reached at all, said in one line and in plain words. It is
            // the only status an assisted phone needs, and the only one it can act on: everything
            // else about its health is a sentence for the person supporting it, not for its owner.
            item {
                ConnectionLine(offline)
            }
            // A release already running is the most important thing on any phone, this one
            // included: it offers the same way out below and used to go quiet the moment it was
            // taken, which is the "did it work?" this screen exists to answer.
            if (panicStatus.request != null) {
                item { PanicProgressRow(panicStatus, onOpen = onOpenPanic) }
            }
            if (helpSeen != null) {
                item { HelpSeenCard(onDismiss = { viewModel.dismissNotice() }) }
            }
            if (helpRanOut != null) {
                item { HelpRanOutCard(onDismiss = { viewModel.dismissNotice() }) }
            }
            item {
                // Resolved outside the lambda: the text is what the family's feed and their
                // "waiting on" list will read, so it is localised on THIS phone, in the language
                // its owner set — not looked up when the button happens to be pressed.
                val helpText = stringResource(R.string.assist_help_text)
                HelpCard(
                    state = helpState,
                    canReask = canReask,
                    onAsk = { viewModel.askForHelp(helpText) },
                )
            }
            // Permissions this phone still needs. Kept because without them the support tools
            // simply do not work, and nobody else is standing here to grant them.
            val journeyPending =
                !deviceSetup.journeyDone && deviceSetup.loaded && deviceSetup.unmet.isNotEmpty()
            if (journeyPending) {
                item { AssistedSetupCard(deviceSetup.unmet.size, onOpenSetupJourney) }
            } else {
                items(deviceSetup.toNag, key = { it.key }) { requirement ->
                    dev.walcott.ui.setup.SetupNudgeCard(
                        requirement = requirement,
                        onFixed = deviceSetup::refreshNow,
                        onDismiss = { deviceSetup.dismiss(requirement) },
                    )
                }
                item { dev.walcott.ui.setup.HiddenSetupReminderRow(deviceSetup) }
            }
            // The way out when the family's phone AND the PIN are gone. A plain line, as on the
            // child home: findable in a real emergency, not an inviting button to poke at.
            if (identity.role == dev.walcott.sync.Role.CHILD) {
                item {
                    Text(
                        stringResource(R.string.panic_entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = onOpenPanic)
                            .padding(top = spacing.xxl, bottom = spacing.sm),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = spacing.xl),
                )
            }
        }
    }
}

@Composable
private fun ConnectionLine(offline: Boolean) {
    val spacing = Tokens.spacing
    Column {
        Icon(
            if (offline) Icons.Outlined.CloudOff else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            stringResource(if (offline) R.string.assist_home_offline else R.string.assist_home_ok),
            style = MaterialTheme.typography.bodyLarge,
            color = if (offline) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}

/**
 * The one thing this screen is for.
 *
 * Deliberately the largest tap target in the app: this is pressed by somebody who may be flustered,
 * far-sighted, and holding the phone at arm's length. Once pressed it stops being a button and
 * becomes a statement, because "did it send?" is the next thing they will wonder and the only way
 * to answer it is on the screen in front of them.
 */
@Composable
private fun HelpCard(state: HelpState, canReask: Boolean, onAsk: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.SupportAgent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
            Text(
                stringResource(
                    when (state) {
                        HelpState.READY -> R.string.assist_help_title
                        HelpState.WAITING -> R.string.assist_help_queued_title
                        HelpState.SENT -> R.string.assist_help_sent
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when {
                        // "You don't need to press again" is true for ten minutes and a lie
                        // after them, and this is the card that has to stop saying it before
                        // the button under it reappears.
                        canReask && state != HelpState.READY -> R.string.assist_help_still_waiting
                        state == HelpState.WAITING -> R.string.assist_help_queued_body
                        state == HelpState.SENT -> R.string.assist_help_waiting
                        else -> R.string.assist_help_body
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            // The same button, and only its word changes: somebody who has been waiting a
            // quarter of an hour is not pressing a different control, they are pressing this one
            // again. Full size in both states, because the second press is made by somebody who
            // is by then rather more anxious than the first.
            if (state == HelpState.READY || canReask) {
                Button(
                    onClick = onAsk,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = spacing.xs),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (state == HelpState.READY) R.string.assist_help_button
                            else R.string.assist_help_again,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

/** Where the one ask on this screen stands, as far as this phone can know. */
private enum class HelpState { READY, WAITING, SENT }

/**
 * The family's answer to a call for help: somebody has dealt with it.
 *
 * Without it the card simply went back to offering the button, and a person who had asked and heard
 * nothing could not tell "they know" from "it never went" — the same doubt that makes somebody press
 * again.
 */
@Composable
private fun HelpSeenCard(onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    stringResource(R.string.assist_help_seen_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = onColor,
                )
            }
            Text(
                stringResource(R.string.assist_help_seen_body),
                style = MaterialTheme.typography.bodyLarge,
                color = onColor,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}

/**
 * Two days in which nobody pressed "I've helped".
 *
 * Said plainly and without blame — the commonest reason is a family who dealt with it on the
 * telephone and never opened the app — because the alternative is what this screen used to do:
 * retire the ask in silence and put the button back, leaving somebody to wonder whether their
 * call for help had ever existed.
 */
@Composable
private fun HelpRanOutCard(onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    val onColor = MaterialTheme.colorScheme.onSurfaceVariant
    WalcottCard(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(R.string.assist_help_ranout_title),
                style = MaterialTheme.typography.titleLarge,
                color = onColor,
            )
            Text(
                stringResource(R.string.assist_help_ranout_body),
                style = MaterialTheme.typography.bodyLarge,
                color = onColor,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}

/** How often the help card re-asks whether the window to send it again has opened. */
private const val REASK_TICK_MS = 30_000L

/** The permissions run, in one card and in the same voice as the rest of this screen. */
@Composable
private fun AssistedSetupCard(count: Int, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onOpen, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                stringResource(R.string.journey_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.journey_card_desc, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
