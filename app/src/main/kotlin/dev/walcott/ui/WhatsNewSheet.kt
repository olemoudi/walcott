package dev.walcott.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import dev.walcott.BuildConfig
import dev.walcott.R
import dev.walcott.data.WhatsNewStore
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shown once after the app has updated itself, listing what changed since the build this device
 * was last running.
 *
 * Every string comes from resources, so the sheet speaks whatever language the app is set to
 * without this code doing anything about it. Dismissing marks the current build as seen — and so
 * does showing it at all being impossible on a fresh install, where there is no "before".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(store: WhatsNewStore) {
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<WhatsNew.Release>>(emptyList()) }
    var resolved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastSeen = store.lastSeenVersionCode.first()
        releases = WhatsNew.entriesFor(lastSeen, BuildConfig.VERSION_CODE)
        // Whether or not there is anything to show, this build is now the one they have seen:
        // a fresh install must not be handed the whole changelog at its next update either.
        if (releases.isEmpty()) store.markSeen(BuildConfig.VERSION_CODE)
        resolved = true
    }

    if (!resolved || releases.isEmpty()) return

    val sheetState = rememberModalBottomSheetState()
    val dismiss = {
        scope.launch {
            store.markSeen(BuildConfig.VERSION_CODE)
            releases = emptyList()
        }
        Unit
    }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.spacing.screen)
                .padding(bottom = Tokens.spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacing.md),
        ) {
            Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.headlineSmall)
            releases.forEach { release ->
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                    Text(
                        release.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    stringArrayResource(release.bulletsRes).forEach { bullet ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(Tokens.spacing.sm))
                            Text(bullet, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.whats_new_dismiss))
            }
        }
    }
}
