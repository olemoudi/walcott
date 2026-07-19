package dev.walcott.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.SupervisorAccount
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.sync.DeviceMode
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * First-launch choice between parent and child mode. The parent card expands into the
 * family creation form; the child card just persists the mode — enrollment happens on
 * the child home by scanning a per-child QR.
 */
@Composable
fun ModeSelectScreen(
    viewModel: WalcottViewModel,
    onParentCreated: () -> Unit,
    onChildSelected: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    var parentExpanded by rememberSaveable { mutableStateOf(false) }
    var familyName by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val defaultFamilyName = stringResource(R.string.family_default_name)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.mode_select_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.mode_select_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm, bottom = spacing.xl),
        )

        ModeCard(
            icon = Icons.Outlined.SupervisorAccount,
            title = stringResource(R.string.mode_parent_card_title),
            description = stringResource(R.string.mode_parent_card_desc),
            selected = parentExpanded,
            onClick = { parentExpanded = !parentExpanded },
        )
        AnimatedVisibility(parentExpanded) {
            Column(Modifier.padding(top = spacing.md)) {
                OutlinedTextField(
                    value = familyName,
                    onValueChange = { familyName = it },
                    label = { Text(stringResource(R.string.family_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        creating = true
                        scope.launch {
                            viewModel.becomeParent(familyName.trim().ifBlank { defaultFamilyName })
                            onParentCreated()
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                ) {
                    // Creating the family generates keys in the Keystore — worth a beat of feedback.
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(stringResource(R.string.action_continue))
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.md))

        ModeCard(
            icon = Icons.Outlined.Face,
            title = stringResource(R.string.mode_child_card_title),
            description = stringResource(R.string.mode_child_card_desc),
            selected = false,
            onClick = {
                viewModel.setMode(DeviceMode.CHILD)
                onChildSelected()
            },
        )
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
