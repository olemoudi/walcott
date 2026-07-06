package dev.walcott.ui.parent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.Distribution
import dev.walcott.R
import dev.walcott.sync.PairingPayload
import dev.walcott.sync.Role
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.qr.rememberQrBitmap
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Two steps to set up a child's phone: (1) install the app via the download QR, then
 * (2) link the device via the pairing QR the child scans with the in-app scanner.
 */
@Composable
fun ChildSetupScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val parentName = stringResource(R.string.parent_default_name)

    val pairingText = if (identity.role == Role.PARENT) {
        PairingPayload(identity.topic, identity.familyKeyB64, identity.parentPublicKeyB64, identity.ntfyServer).encode()
    } else {
        null
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.qr_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // Step 1 — install
            SectionTitle(stringResource(R.string.pairing_step_download))
            Text(stringResource(R.string.qr_instructions), style = MaterialTheme.typography.bodyMedium)
            QrCard(rememberQrBitmap(Distribution.CHILD_APK_URL, size = 200.dp))

            // Step 2 — link
            SectionTitle(stringResource(R.string.pairing_step_link))
            if (pairingText != null) {
                Text(stringResource(R.string.pairing_qr_instructions), style = MaterialTheme.typography.bodyMedium)
                QrCard(rememberQrBitmap(pairingText, size = 200.dp))
            } else {
                Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Text(stringResource(R.string.create_family_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.create_family_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = { scope.launch { viewModel.becomeParent(parentName) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.create_family_button)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun QrCard(bitmap: androidx.compose.ui.graphics.ImageBitmap?) {
    val spacing = Tokens.spacing
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, tonalElevation = 2.dp) {
            Box(Modifier.padding(spacing.lg).size(200.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(200.dp))
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
