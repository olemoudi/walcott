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
