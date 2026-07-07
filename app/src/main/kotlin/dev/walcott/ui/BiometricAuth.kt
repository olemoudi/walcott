package dev.walcott.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Thin wrapper over the framework [BiometricPrompt] (API 28+, so no extra dependency and
 * it works with a plain ComponentActivity). Used only as an optional unlock method for the
 * parent app lock — the PIN is always the fallback.
 */
object BiometricAuth {

    /** True when the device has an enrolled biometric that can authenticate right now. */
    fun isAvailable(context: Context): Boolean {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION") bm.canAuthenticate()
        }
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        negativeButton: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negativeButton, executor) { _, _ -> onCancel() }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancel()
                }
            },
        )
    }
}
