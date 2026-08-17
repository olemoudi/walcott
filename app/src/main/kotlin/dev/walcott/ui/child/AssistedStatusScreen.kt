package dev.walcott.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.BuildConfig
import dev.walcott.R
import dev.walcott.sync.ChildRequest
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
    val deviceSetup = dev.walcott.ui.setup.rememberDeviceSetup()

    // One unanswered ask at a time. The button that sent it says so instead of offering to send a
    // second one, which is what somebody who is not sure it worked will otherwise do — five times.
    val helpPending = myAsks.any { it.kind == ChildRequest.KIND_HELP }
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
            item {
                // Resolved outside the lambda: the text is what the family's feed and their
                // "waiting on" list will read, so it is localised on THIS phone, in the language
                // its owner set — not looked up when the button happens to be pressed.
                val helpText = stringResource(R.string.assist_help_text)
                HelpCard(
                    pending = helpPending,
                    onAsk = { viewModel.askFor(ChildRequest.KIND_HELP, helpText) },
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
private fun HelpCard(pending: Boolean, onAsk: () -> Unit) {
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
                stringResource(if (pending) R.string.assist_help_sent else R.string.assist_help_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(if (pending) R.string.assist_help_waiting else R.string.assist_help_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            if (!pending) {
                Button(
                    onClick = onAsk,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = spacing.xs),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        stringResource(R.string.assist_help_button),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

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
