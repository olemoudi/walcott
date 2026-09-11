package dev.walcott.sync

/**
 * The relay a family's phones talk through.
 *
 * Walcott has no server of its own: the phones exchange sealed envelopes over a public ntfy
 * relay, which by default is the hosted one. That default is a single point of failure the
 * family cannot route around — it can be down, blocked on their network, or (the likely one)
 * rate-limiting a family whose several children each check in, report rules and send locations
 * all day. Being able to name a different relay, including one the family runs themselves, is
 * what turns that from "the app stopped working" into a setting.
 *
 * Pure, because what counts as an acceptable address is a security question as much as a
 * usability one: this string becomes the base of every URL the device then talks to.
 */
object RelayServer {

    /** The hosted relay, used unless a family names another. */
    const val DEFAULT = "https://ntfy.sh"

    /**
     * [input] as a usable relay base URL, or null when it isn't one.
     *
     * Accepts a bare host ("ntfy.example.com" becomes https) and refuses anything carrying a
     * path, query or fragment — the topic is appended by the transport, so a path here would
     * silently produce URLs nobody intended.
     *
     * `http` is accepted only when [cleartextAllowed]. Release builds refuse cleartext at the
     * platform level (no network-security-config permits it), so an http relay accepted here
     * would be adopted by every phone in the family and then opened by none of them — parent
     * and children each listening to an address that cannot connect, with the migration window
     * blocking a second move for a week. The debug build permits cleartext to the test relay on
     * loopback, and passes true.
     */
    fun normalize(input: String, cleartextAllowed: Boolean = false): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && !(scheme == "http" && cleartextAllowed)) return null
        val host = uri.host ?: return null
        if (host.isBlank() || '.' !in host && host != "localhost") return null
        // A path would end up in front of the topic; a query or fragment would be carried into
        // every publish. Neither can be what someone typing a server address meant.
        if (!uri.path.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
        if (uri.userInfo != null) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /** True when [input] names a usable relay. */
    fun isValid(input: String, cleartextAllowed: Boolean = false): Boolean =
        normalize(input, cleartextAllowed) != null
}
