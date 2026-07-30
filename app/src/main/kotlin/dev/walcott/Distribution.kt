package dev.walcott

/** Distribution constants. Keep the asset names stable so existing QR codes / installs keep working. */
object Distribution {
    /**
     * Stable URL that always points to the latest release's APK asset.
     *
     * Deliberately carries no release stage: this name is baked into every onboarding QR ever
     * shown, so it has to outlive alpha, beta and 1.0. The stage is user-facing information and
     * lives in the version label instead. `walcott-alpha.apk` is still published as a
     * byte-identical copy for parent phones that haven't updated and still show the old QR.
     */
    const val CHILD_APK_URL = "https://github.com/olemoudi/walcott/releases/latest/download/walcott.apk"

    /** Small JSON published by CI describing the latest release (version code + apk url). */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/walcott/releases/latest/download/version.json"
}
