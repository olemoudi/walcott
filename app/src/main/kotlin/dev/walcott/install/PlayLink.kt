package dev.walcott.install

/**
 * Extracts an Android package name from whatever the parent shares out of the Play Store.
 *
 * Pure and Android-free so it is unit-testable. Accepts the three things a share actually
 * produces — a full Play web URL, a `market://` URI, or (rarely) a bare package — and returns
 * null for anything it can't resolve offline, so the caller can tell the parent to share the
 * app's full Play page instead of, say, a `play.app.goo.gl` short link (which would need a
 * network round-trip to expand).
 */
object PlayLink {

    // A conservative Android package: dot-separated segments, each starting with a letter.
    private val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    fun parsePackage(shared: String?): String? {
        val text = shared?.trim().orEmpty()
        if (text.isEmpty()) return null

        // Shared text is often "App Name\nhttps://play.google.com/…"; scan every whitespace token.
        for (token in text.split(Regex("\\s+"))) {
            idFromUrl(token)?.let { return it }
        }
        // A bare package pasted on its own.
        if (text.matches(PACKAGE)) return text
        return null
    }

    /**
     * A human title for the shared app, best effort. Play shares an `EXTRA_SUBJECT` and a text
     * like "Check out X\nhttps://…"; prefer the subject, else the first line of the text that
     * isn't a URL. Returns "" when nothing readable is found — callers fall back to the package
     * name.
     *
     * Both candidates are stripped of Play's own sentence ([clean]) rather than only the second:
     * the subject is boilerplate as often as the text is, and it is what a parent ends up reading
     * on the request card. A subject that cleans away to nothing falls through to the text
     * instead of winning with an empty string.
     */
    fun parseLabel(subject: String?, text: String?): String {
        val fromSubject = subject?.trim().orEmpty()
        if (fromSubject.isNotEmpty() && !fromSubject.looksLikeUrl()) {
            clean(fromSubject).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val line = text.orEmpty().lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.looksLikeUrl() && !it.matches(PACKAGE) }
            .orEmpty()
        return clean(line)
    }

    /**
     * What Play wraps the app's name in, in the two languages this app ships. Sharing from the
     * Play page does not hand over a bare title — it hands over a sentence — and that sentence
     * was going straight onto the parent's request card, so a child asking for Duolingo showed up
     * as "Check out Duolingo".
     *
     * Best-effort and cosmetic by construction: an unrecognised phrasing (another locale, a Play
     * rewording) leaves the label exactly as it arrives today, which is wordy but never wrong,
     * and the package name travels beside it either way (ChildRequest.pkg), so nothing here can
     * make a request ambiguous about WHICH app it is for.
     */
    private val LEAD_INS = listOf(
        // Multi-word: unambiguous enough to strip wherever they start the line.
        "check out this app", "check this out", "check out", "take a look at", "have a look at",
        "i found this app", "i found",
        "echa un vistazo a", "mira esta aplicación", "mira esta app", "descubre esta aplicación",
        "te recomiendo", "he encontrado esta app", "he encontrado",
    )

    /** Single words that only lead a sentence when a separator follows, so an app named "Mira"
     *  (or "Try") survives being its own title. */
    private val LEAD_WORDS = listOf("mira", "descubre", "prueba", "try", "download", "consulta")

    /** Play's trailing shop line, the other half of the same problem. */
    private val STORE_SUFFIXES = listOf(
        "apps on google play", "aplicaciones en google play", "google play", "en google play",
        "on google play",
    )

    /** Strips Play's sentence from a candidate title, leaving the app's name. */
    private fun clean(raw: String): String {
        var value = raw.trimQuotes()
        val lower = value.lowercase()
        LEAD_INS.firstOrNull { lower.startsWith(it) }?.let { value = value.drop(it.length) }
        if (value == raw.trimQuotes()) {
            // No phrase matched; try the single words, which need a separator to count.
            LEAD_WORDS.firstOrNull { lower.startsWith("$it:") || lower.startsWith("$it -") }
                ?.let { value = value.drop(it.length) }
        }
        value = value.trimStart(':', '-', '–', '—', ' ', ' ').trimQuotes()
        // "Duolingo - Apps on Google Play" -> "Duolingo". Only after a dash, so an app whose real
        // name ends in one of these words keeps it.
        for (separator in listOf(" - ", " – ", " — ", " | ")) {
            val tail = value.substringAfterLast(separator, "").trim().lowercase()
            if (tail.isNotEmpty() && tail in STORE_SUFFIXES) {
                value = value.substringBeforeLast(separator).trim()
                break
            }
        }
        return value.trimQuotes()
    }

    private fun String.looksLikeUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) ||
            startsWith("market://", ignoreCase = true)

    private fun String.trimQuotes(): String = trim().trim('"', '“', '”', '\'')

    /** The `id=` package from a play.google.com or market:// URL, or null. */
    private fun idFromUrl(token: String): String? {
        val lower = token.lowercase()
        val isPlay = lower.startsWith("http") && "play.google.com" in lower && "id=" in lower
        val isMarket = lower.startsWith("market://") && "id=" in lower
        if (!isPlay && !isMarket) return null
        val id = token.substringAfter("id=", "").substringBefore('&').substringBefore('#').trim()
        return id.takeIf { it.matches(PACKAGE) }
    }
}
