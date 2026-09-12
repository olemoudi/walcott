package dev.walcott.sync

/**
 * What a scanned QR turned out to be.
 *
 * Three different codes are in play while a family is being set up — the Device Owner enrollment
 * JSON, the link to the APK, and the pairing payload — and they are scanned in that order, by
 * different apps, on two phones. Answering all three with "couldn't read that code" leaves a
 * parent with nothing to correct: the code scanned perfectly, it was simply the wrong one of
 * three, and only this app can say which.
 */
sealed interface ScannedCode {
    /** A pairing payload: this phone can join a family with it. */
    data class Pairing(val payload: PairingPayload) : ScannedCode

    /** The provisioning JSON shown to a factory-reset phone's setup wizard. */
    data object EnrollmentCode : ScannedCode

    /** The download link on the README and the install card. */
    data object DownloadLink : ScannedCode

    /** Not one of ours. */
    data object Unknown : ScannedCode
}

/** Reads a scanned string without acting on it, so a screen can say what was scanned. */
object CodeScan {

    fun classify(text: String): ScannedCode {
        val trimmed = text.trim()
        PairingPayload.decode(trimmed)?.let { return ScannedCode.Pairing(it) }
        // The provisioning payload is a flat JSON object of android.app.extra.PROVISIONING_*
        // keys. Matched on the key rather than on being JSON: what makes it recognisable is
        // what it is FOR, and no other code this app shows carries that prefix.
        if (trimmed.startsWith("{") && trimmed.contains("android.app.extra.PROVISIONING_")) {
            return ScannedCode.EnrollmentCode
        }
        if (trimmed.startsWith("https://") && trimmed.endsWith(".apk")) return ScannedCode.DownloadLink
        return ScannedCode.Unknown
    }
}
