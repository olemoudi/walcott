package dev.walcott

/** Distribution constants. Keep the asset names stable so existing QR codes / installs keep working. */
object Distribution {
    /**
     * Stable URL that always points to the latest release's APK asset.
     *
     * Renamed from `walcott-alpha.apk` when the app went to beta. Every release still publishes
     * the old name as a byte-identical copy, because a parent phone that hasn't updated yet is
     * still showing a QR that encodes it — dropping the alias would 404 those onboarding scans.
     * Auto-update was never at risk: it follows the url inside version.json, not this constant.
     */
    const val CHILD_APK_URL = "https://github.com/olemoudi/walcott/releases/latest/download/walcott-beta.apk"

    /** Small JSON published by CI describing the latest release (version code + apk url). */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/walcott/releases/latest/download/version.json"
}
