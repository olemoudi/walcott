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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.Distribution
import dev.walcott.R
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.qr.rememberQrBitmap
import dev.walcott.ui.theme.Tokens

/**
 * Shows a QR code the child scans with their camera to download the APK and sideload it.
 * We deliberately don't build a scanner into the child app — the system camera handles it.
 */
@Composable
fun ChildSetupScreen(onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val qr = rememberQrBitmap(Distribution.CHILD_APK_URL, size = 240.dp)

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.qr_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                stringResource(R.string.qr_instructions),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            // White card keeps the QR scannable regardless of light/dark theme.
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, tonalElevation = 2.dp) {
                Box(Modifier.padding(spacing.lg).size(240.dp), contentAlignment = Alignment.Center) {
                    if (qr != null) {
                        Image(bitmap = qr, contentDescription = null, modifier = Modifier.size(240.dp))
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.qr_url_caption),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Distribution.CHILD_APK_URL,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.qr_provision_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.md),
                )
            }
        }
    }
}
