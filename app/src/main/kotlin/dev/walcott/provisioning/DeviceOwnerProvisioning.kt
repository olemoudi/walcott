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

    /**
     * The certificate checksum Android's provisioning checks the APK at [Distribution.CHILD_APK_URL]
     * against: base64url, no padding, of the SHA-256 of the ORIGINAL release certificate.
     *
     * Not this install's own signer, which is what it used to be read from, and that stopped
     * working at 0.107. ManagedProvisioning reads the downloaded APK with `GET_SIGNATURES` and
     * hashes `PackageInfo.signatures` (VerifyAdminPackageTask, ChecksumUtils), and for an APK that
     * carries a v3 rotation lineage the platform deliberately puts the OLDEST certificate there,
     * "so that programmatic checks keep working even if unaware of key rotation"
     * (PackageInfoUtils). The app read `apkContentsSigners`, the CURRENT one — so after the key
     * was rotated every enrollment QR a parent showed named a certificate the published APK does
     * not present that way, and a factory-reset phone refused to set up, with nothing on either
     * screen to say why. No test enrolled a phone from the QR; the harness makes Device Owner
     * with adb.
     *
     * A constant rather than read from this install, and for a second reason: the QR sends the
     * phone to the published APK, so the checksum has to be the PUBLISHED one. A parent running a
     * build signed any other way used to produce a QR no phone could use.
     *
     * It stays right across future rotations: a lineage keeps its first certificate. It changes
     * only if the family of keys is started over, which means a factory reset for every child
     * anyway (see docs/signing.md). Pinned by ProvisioningChecksumTest.
     */
    const val PUBLISHED_SIGNATURE_CHECKSUM = "noW0bJyNwMt00p0DC46ToBCrU3QN5t6fHvwDxZNE2CM"

    private fun signingChecksum(@Suppress("UNUSED_PARAMETER") context: Context): String =
        PUBLISHED_SIGNATURE_CHECKSUM

    /**
     * What `GET_SIGNATURES` reports for THIS install, hashed the way provisioning hashes it. For the
     * debug hook that proves on a device that the constant above is what the platform compares.
     */
    fun installedSignatureChecksum(context: Context): String {
        @Suppress("DEPRECATION")
        val signatures = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        val cert = signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
