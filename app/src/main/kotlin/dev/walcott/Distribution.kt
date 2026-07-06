package dev.walcott

/** Distribution constants. Keep the asset names stable so existing QR codes / installs keep working. */
object Distribution {
    /** Stable URL that always points to the latest release's APK asset. */
    const val CHILD_APK_URL = "https://github.com/olemoudi/walcott/releases/latest/download/walcott-alpha.apk"

    /** Small JSON published by CI describing the latest release (version code + apk url). */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/walcott/releases/latest/download/version.json"
}
