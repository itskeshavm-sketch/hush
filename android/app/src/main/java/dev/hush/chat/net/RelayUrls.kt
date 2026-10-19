package dev.hush.chat.net

/**
 * Relay address rules.
 *
 * HUSH needs two different answers to "is this URL acceptable", and collapsing
 * them into one check is how a self-hosting feature quietly stays broken:
 *
 *  - **Secure transport.** Over the network, the connection must be `wss://`.
 *    The relay is an untrusted party by construction, and the safety numbers
 *    only mean something if an attacker cannot sit in the middle of the
 *    handshake.
 *  - **Loopback.** `ws://` to the same machine is a different situation. The
 *    bytes never touch a wire, so there is nothing to eavesdrop and nothing to
 *    intercept. Refusing it would force every self-hoster to install openssl
 *    and run a TLS stack on a laptop purely to talk to themselves, which is
 *    the single most common way to make self-hosting feel impossible.
 *
 * `10.0.2.2` is the Android emulator's alias for the host machine, so it gets
 * the same exemption — otherwise "run it on my PC and point the emulator at
 * it" cannot work at all.
 */
object RelayUrls {

    /**
     * Addresses where cleartext is permitted, as bare lowercase hostnames.
     *
     * This set is deliberately identical to the `domain-config` block in
     * `res/xml/network_security_config.xml`. If the two ever drift, the app
     * accepts an address that the platform then refuses to connect to, and the
     * user gets an opaque socket failure with no way to tell which half is
     * wrong. `::1` is absent for the same reason: it is genuinely loopback, but
     * Android's cleartext allowlist is matched by hostname and does not carry
     * IPv6 literals, so honouring it here would be a promise the OS breaks.
     * `127.0.0.1` covers the same machine either way.
     */
    private val LOOPBACK_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "10.0.2.2",
    )

    /** Host portion of a `ws://` / `wss://` URL, lowercased, or null if unparseable. */
    private fun hostOf(url: String): String? {
        val afterScheme = when {
            url.startsWith("wss://", ignoreCase = true) -> url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> url.substring(5)
            else -> return null
        }
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        // Strip userinfo, then the port. Bracketed IPv6 keeps its brackets.
        val hostAndPort = authority.substringAfterLast('@')
        return if (hostAndPort.startsWith("[")) {
            // `substringBefore` EXCLUDES the delimiter, so this yields "[::1"
            // and not "[::1]" — the leading bracket has to go separately.
            // Returning the bare form keeps LOOPBACK_HOSTS free of two
            // spellings of the same address.
            hostAndPort.substringBefore(']').removePrefix("[").lowercase()
        } else {
            hostAndPort.substringBefore(':').lowercase()
        }
    }

    /** True when the URL is a `ws://` address this build is willing to use. */
    fun isLoopbackCleartext(url: String): Boolean {
        if (!url.startsWith("ws://", ignoreCase = true)) return false
        return hostOf(url) in LOOPBACK_HOSTS
    }

    /**
     * True when the address is usable: secure, or cleartext to loopback only.
     *
     * A host is required in both cases. `wss://` on its own — or `wss:///ws`,
     * with the authority empty — has no name to validate a certificate against
     * and no route to resolve, so accepting it only defers the failure to the
     * socket layer with a much worse error message.
     */
    fun isAcceptable(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return url.startsWith("wss://", ignoreCase = true) || host in LOOPBACK_HOSTS
    }

    /**
     * A message explaining a rejection, tailored to why it was rejected.
     *
     * Returning the reason rather than a generic "invalid URL" matters here:
     * the two failure modes look identical to the user otherwise, and one of
     * them is fixed by installing a certificate while the other is fixed by
     * using a loopback address.
     */
    fun rejectionReason(url: String): String = when {
        url.isBlank() -> "Enter a relay address"
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("ws://", ignoreCase = true) ->
            "Cleartext (ws://) is only allowed to localhost. Use wss:// for a relay on another machine."
        else -> "Relay must be a wss:// address (or ws:// on localhost)"
    }

    /** Short hint shown under the relay field. */
    const val FIELD_HINT =
        "wss://your-relay.example/ws — or ws://127.0.0.1:8787/ws for a relay on this machine"
}