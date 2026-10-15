package dev.hush.chat.net

import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Certificate pinning for the relay connection.
 *
 * A self-hosted HUSH relay serves a self-signed certificate, which Android
 * rejects. The usual "fix" — a permissive TrustManager, or `hostnameVerifier
 * { _, _ -> true }` — is a total defeat of TLS: it accepts *any* certificate
 * from *any* server, which is exactly the active MITM the protocol is supposed
 * to prevent.
 *
 * What this does instead:
 *
 *  - On the first connection to a relay, the leaf certificate's SHA-256 is
 *    recorded and shown to the user as a verifiable string.
 *  - On every later connection, the pin must match. A changed certificate is
 *    a hard failure, not a warning.
 *  - If the user replaces the relay's certificate (a legitimate, common
 *    operation), the pin can be re-baselined explicitly, in Settings, with
 *    the old and new fingerprints displayed for comparison.
 *
 * The residual risk is the well-known one: a pin recorded on first contact
 * cannot detect a MITM that was already present at that moment. That is why
 * the fingerprint is displayed rather than hidden, so two people can confirm it
 * out of band.
 */
object TlsPinner {

    private const val PIN_ALGORITHM = "SHA-256"

    /**
     * Build a [CertificatePinner] for [host] pinned to [sha256FingerprintHex].
     *
     * @param sha256FingerprintHex uppercase hex, colons optional
     * @throws IllegalArgumentException if the fingerprint is not 32 hex bytes
     */
    fun pinnerFor(host: String, sha256FingerprintHex: String): CertificatePinner {
        val normalised = normalise(sha256FingerprintHex)
        require(normalised.length == 64) {
            "expected a 32-byte SHA-256 fingerprint, got ${normalised.length / 2} bytes"
        }
        return CertificatePinner.Builder()
            .add(host, "pin-sha256/$normalised")
            .build()
    }

    /**
     * OkHttp's pin format encodes the SHA-256 of the DER certificate in
     * base64. This is the bridge from a stored hex fingerprint to that form.
     */
    fun toOkHttpPin(sha256FingerprintHex: String): String {
        val normalised = normalise(sha256FingerprintHex)
        require(normalised.length == 64) { "not a SHA-256 fingerprint: $sha256FingerprintHex" }
        val bytes = ByteArray(32) { i ->
            normalised.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return "sha256/${java.util.Base64.getEncoder().encodeToString(bytes)}"
    }

    /** The fingerprint shown in Settings, grouped in blocks for transcription. */
    fun format(sha256FingerprintHex: String): String {
        val n = normalise(sha256FingerprintHex)
        if (n.length != 64) return n
        return n.chunked(4).joinToString(" ")
    }

    /** Derive a fingerprint from a live certificate chain, using the leaf. */
    fun fingerprintOf(chain: List<X509Certificate>): String? {
        val leaf = chain.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance(PIN_ALGORITHM).digest(leaf.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * Group an 8-digit-per-block fingerprint into twelve lines of five blocks,
     * which is the layout people can actually read aloud and compare.
     */
    fun formatForComparison(sha256FingerprintHex: String): List<String> {
        val flat = normalise(sha256FingerprintHex).replace(Regex("(..)(?=.)"), "$1 ")
        // 32 bytes as 64 hex chars, grouped in twos, batched five per line.
        val groups = flat.split(" ").map { it.replace(" ", "") }.filter { it.isNotEmpty() }
        return groups.chunked(5).map { it.joinToString(" ") }
    }

    private fun normalise(hex: String): String = hex.replace(":", "").replace(" ", "").uppercase()
}
