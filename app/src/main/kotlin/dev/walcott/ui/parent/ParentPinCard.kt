package dev.walcott.ui.parent

import android.app.Activity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.PinResult
import dev.walcott.ui.BiometricAuth
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Parent-mode PIN management: change it, or set a new one when it's been forgotten.
 *
 * There is deliberately no "show my PIN". It is stored only as a PBKDF2 hash + salt (see
 * [dev.walcott.data.Pin]), and that material rides inside the policy to every child device so
 * they can verify an emergency release offline — a recoverable copy would therefore put the
 * parent's PIN, in the clear, on the phone of the person it exists to keep out. Forgetting it
 * is answered by setting a new one, which is why that path asks for biometrics instead.
 */
@Composable
internal fun ParentPinCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    var changing by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        // The list grows as the children's snapshots land; grow with it instead of popping.
        Column(Modifier.padding(spacing.lg).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Password,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.parent_pin_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.parent_pin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.parent_pin_not_shown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
            // Who can already verify this PIN. A child only adopts rules strictly newer than
            // the ones it applied, so "up to date" is proof it has the current PIN; anything
            // else is "can't tell yet", never "definitely stale".
            if (settings.children.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = spacing.md))
                Text(
                    stringResource(R.string.parent_pin_children_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                settings.children.forEach { entry ->
                    AdoptionRow(
                        name = entry.name,
                        adoption = policyAdoption(
                            appliedPolicyVersion = snapshots
                                .firstOrNull { it.childId == entry.childId }?.appliedPolicyVersion,
                            parentVersion = parentVersion,
                        ),
                    )
                }
            }
            OutlinedButton(
                onClick = { changing = true },
                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            ) { Text(stringResource(R.string.parent_pin_change)) }
        }
    }

    if (changing) ChangePinDialog(viewModel) { changing = false }
}

/** Whether a child's phone is known to be running the parent's current rules. */
internal enum class PolicyAdoption { UP_TO_DATE, PENDING, UNKNOWN }

/**
 * Read-side mirror of [dev.walcott.sync.SyncEngine.adoptsPolicy]: a child only ever adopts a
 * snapshot strictly newer than the one it applied, so a child reporting the parent's current
 * version is certainly running the current rules — PIN included.
 *
 * [UNKNOWN] covers both "never reported" (no snapshot) and the 0 a child too old to report the
 * field sends. The imprecision only goes one way: the parent's counter also bumps for bonuses
 * and answers, so a child can read [PENDING] while already holding the current PIN — but a
 * child reading [UP_TO_DATE] can never be missing it.
 */
internal fun policyAdoption(appliedPolicyVersion: Long?, parentVersion: Long): PolicyAdoption = when {
    appliedPolicyVersion == null || appliedPolicyVersion <= 0L -> PolicyAdoption.UNKNOWN
    appliedPolicyVersion >= parentVersion -> PolicyAdoption.UP_TO_DATE
    else -> PolicyAdoption.PENDING
}

@Composable
private fun AdoptionRow(name: String, adoption: PolicyAdoption) {
    val spacing = Tokens.spacing
    val (icon, labelRes, tint) = when (adoption) {
        PolicyAdoption.UP_TO_DATE -> Triple(
            Icons.Filled.CheckCircle,
            R.string.parent_pin_child_up_to_date,
            MaterialTheme.colorScheme.secondary,
        )
        PolicyAdoption.PENDING -> Triple(
            Icons.Outlined.Sync,
            R.string.parent_pin_child_pending,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PolicyAdoption.UNKNOWN -> Triple(
            Icons.Outlined.HelpOutline,
            R.string.parent_pin_child_unknown,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(spacing.sm))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

/** What "I forgot it" may do, given how the parent has set this phone up. */
internal enum class PinResetPath {
    /** Biometrics stands in for the forgotten PIN. */
    BIOMETRIC,

    /** Nothing was guarding this screen anyway, so asking for proof would be theatre. */
    DIRECT,

    /** No usable biometric on this phone: the current PIN is the only way through. */
    NEEDS_BIOMETRIC_HARDWARE,

    /** Biometrics exists, but the parent hasn't accepted it as equivalent to the PIN. */
    NEEDS_APP_LOCK_BIOMETRIC,
}

/**
 * The rule for resetting a forgotten PIN. Pure, so the thing guarding the family's PIN is
 * unit-tested rather than only clicked through.
 *
 * Biometrics may stand in for the PIN only where the parent already said it may — with the app
 * lock on, that is exactly the [dev.walcott.sync.FamilyIdentity.appLockBiometric] toggle. A
 * parent who turned it off usually did so because someone else's finger is enrolled on this
 * phone, and honouring that here is the whole point. With the app lock off, nothing guarded
 * this screen to begin with, so refusing would strand the family without protecting anything.
 */
internal fun pinResetPath(
    appLock: Boolean,
    appLockBiometric: Boolean,
    biometricAvailable: Boolean,
): PinResetPath = when {
    !appLock -> if (biometricAvailable) PinResetPath.BIOMETRIC else PinResetPath.DIRECT
    !biometricAvailable -> PinResetPath.NEEDS_BIOMETRIC_HARDWARE
    !appLockBiometric -> PinResetPath.NEEDS_APP_LOCK_BIOMETRIC
    else -> PinResetPath.BIOMETRIC
}

@Composable
private fun ChangePinDialog(viewModel: WalcottViewModel, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    // remember() unconditionally: behind a short-circuiting && it would be skipped on some
    // compositions and not others, which is exactly the slot mismatch Compose forbids.
    val biometricHardware = remember { BiometricAuth.isAvailable(context) }
    val biometricAvailable = activity != null && biometricHardware

    // Cleared once the current PIN checks out, or biometrics vouched for whoever is holding
    // the phone. Only then are the new-PIN fields shown.
    var authorized by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // PBKDF2 at 120k iterations runs off-main but still takes a beat.
    var busy by remember { mutableStateOf(false) }

    val wrongPin = stringResource(R.string.pin_incorrect)
    val lockedFmt = stringResource(R.string.pin_locked)
    val tooShort = stringResource(R.string.pin_too_short)
    val mismatch = stringResource(R.string.pin_mismatch)
    val promptTitle = stringResource(R.string.pin_forgot_biometric_title)
    val promptSubtitle = stringResource(R.string.pin_forgot_biometric_subtitle)
    val cancelLabel = stringResource(R.string.action_cancel)

    val resetPath = pinResetPath(
        appLock = identity.appLock,
        appLockBiometric = identity.appLockBiometric,
        biometricAvailable = biometricAvailable,
    )

    fun forget() {
        when (resetPath) {
            PinResetPath.BIOMETRIC -> activity?.let {
                BiometricAuth.authenticate(
                    activity = it,
                    title = promptTitle,
                    subtitle = promptSubtitle,
                    negativeButton = cancelLabel,
                    onSuccess = { authorized = true; error = null },
                    onCancel = {},
                )
            }
            PinResetPath.DIRECT -> { authorized = true; error = null }
            else -> Unit
        }
    }

    fun verifyCurrent() {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = viewModel.verifyPin(current)) {
                is PinResult.Ok -> { authorized = true; error = null }
                is PinResult.Wrong -> error = wrongPin
                is PinResult.Locked -> error = lockedFmt.format(((result.remainingMs + 59_999) / 60_000).toInt())
            }
            busy = false
        }
    }

    fun save() {
        if (busy) return
        when {
            // Same rules as creating the first PIN (see PinGateScreen).
            next.length < 4 -> error = tooShort
            next != repeat -> error = mismatch
            else -> {
                busy = true
                scope.launch {
                    viewModel.setPin(next).join()
                    busy = false
                    onDismiss()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.parent_pin_change)) },
        text = {
            Column {
                if (!authorized) {
                    Text(stringResource(R.string.parent_pin_change_prompt))
                    PinField(
                        value = current,
                        onValueChange = { current = it; error = null },
                        label = stringResource(R.string.pin_current_label),
                        isError = error != null,
                        enabled = !busy,
                    )
                    when (resetPath) {
                        PinResetPath.BIOMETRIC, PinResetPath.DIRECT -> TextButton(
                            onClick = ::forget,
                            modifier = Modifier.padding(top = spacing.xs),
                        ) { Text(stringResource(R.string.pin_forgot)) }
                        // Say which door is shut, not just that one is.
                        PinResetPath.NEEDS_BIOMETRIC_HARDWARE, PinResetPath.NEEDS_APP_LOCK_BIOMETRIC -> Text(
                            stringResource(
                                if (resetPath == PinResetPath.NEEDS_BIOMETRIC_HARDWARE) {
                                    R.string.pin_forgot_needs_biometrics
                                } else {
                                    R.string.pin_forgot_needs_app_lock_biometrics
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.sm),
                        )
                    }
                } else {
                    Text(stringResource(R.string.parent_pin_new_prompt))
                    PinField(
                        value = next,
                        onValueChange = { next = it; error = null },
                        label = stringResource(R.string.pin_new_label),
                        isError = error != null,
                        enabled = !busy,
                    )
                    PinField(
                        value = repeat,
                        onValueChange = { repeat = it; error = null },
                        label = stringResource(R.string.pin_repeat_label),
                        isError = error != null,
                        enabled = !busy,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            if (authorized) {
                TextButton(
                    enabled = next.isNotEmpty() && repeat.isNotEmpty() && !busy,
                    onClick = ::save,
                ) { Text(stringResource(R.string.action_save)) }
            } else {
                TextButton(enabled = current.isNotEmpty() && !busy, onClick = ::verifyCurrent) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().padding(top = Tokens.spacing.md),
    )
}
