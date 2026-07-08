package dev.walcott.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Completes the modern (API 29+) fully-managed provisioning handshake that the setup wizard drives
 * after it installs Walcott from the Device Owner QR:
 *  - [DevicePolicyManager.ACTION_GET_PROVISIONING_MODE]: tell the platform we want a fully-managed
 *    (device owner) device.
 *  - [DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE]: final step; report compliance so the
 *    wizard can finish provisioning.
 *
 * Without these handlers the wizard aborts with "Can't set up device" right after installing the
 * DPC. There's nothing to configure here: the app performs its own Device-Owner bootstrap on normal
 * launch/boot (it detects [DevicePolicyManager.isDeviceOwnerApp]), so we just complete the flow.
 */
class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                val result = Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                )
                setResult(RESULT_OK, result)
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> setResult(RESULT_OK)
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
