package dev.walcott.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import dev.walcott.Distribution
import dev.walcott.WalcottAdminReceiver
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Builds the Android "QR code enrollment" payload that provisions Walcott as **Device Owner** on
 * a factory-reset device (scanned during the setup wizard after tapping the welcome screen 6
 * times). The setup wizard downloads the APK from [Distribution.CHILD_APK_URL] and verifies its
 * signing certificate against the checksum below, so this only matches in release builds signed
 * with the committed release key (the debug cert won't match the published APK).
 */
object DeviceOwnerProvisioning {

    fun qrPayload(context: Context): String {
        val admin = ComponentName(context, WalcottAdminReceiver::class.java).flattenToString()
        return JSONObject().apply {
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", admin)
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", Distribution.CHILD_APK_URL)
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", signingChecksum(context))
            put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", true)
            put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
        }.toString()
    }

    /** SHA-256 of this app's signing certificate, base64url without padding (provisioning format). */
    private fun signingChecksum(context: Context): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
