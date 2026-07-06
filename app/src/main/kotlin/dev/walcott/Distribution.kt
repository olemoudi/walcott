package dev.walcott

/** Distribution constants. Keep the asset name stable so existing QR codes keep working. */
object Distribution {
    /** Stable URL that always points to the latest release's APK asset. */
    const val CHILD_APK_URL = "https://github.com/olemoudi/walcott/releases/latest/download/walcott-alpha.apk"
}
