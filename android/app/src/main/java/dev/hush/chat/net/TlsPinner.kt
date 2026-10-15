package dev.hush.chat.net

import android.util.Log
import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Certificate trust for the relay connection.
 *
 * A self-hosted HUSH relay serves a self-signed certificate, which Android's
 * system trust store rejects outright. There are two ways to handle that, and
 * one of them is wrong:
 *
 *  - **Wrong:** a permissive `X509TrustManager` or
 *    `hostnameVerifier { _, _ -> true }`. That accepts any certificate from
 *    any server, on every connection, forever â€” which defeats TLS entirely.
 *  - **Right:** trust on first use. The first connection records the relay's
 *    certificate fingerprint; every later connection must present exactly
 *    that one, and a change is a hard failure.
 *
 * The residual risk of TOFU is real and is stated in the UI: an attacker
 * present at the very first connection is recorded as the genuine relay. That
 * is why the safety number exists â€” comparing it with the other person
 * out of band is the only thing that catches a MITM, and it is the reason the
 * verification screen exists at all.
 */
object TlsPinner {

    private const val PIN_ALGORITHM = "SHA-256"

    /** Normalise a stored fingerprint to bare uppercase hex. */
    fun normalise(hex: String): String = hex.replace(":", "").replace(" ", "").uppercase()

    /** SHA-256 of the leaf certificate, colon-separated uppercase. */
    fun fingerprintOf(chain: List<X509Certificate>): String? {
        val leaf = chain.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance(PIN_ALGORITHM).digest(leaf.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** The bare hex form stored in preferences. */
    fun fingerprintHex(chain: List<X509Certificate>): String? {
        val leaf = chain.firstOrNull() ?: return null
        return MessageDigest.getInstance(PIN_ALGORITHM).digest(leaf.encoded)
            .joinToString("") { "%02X".format(it) }
    }

    /** Grouped for display in Settings. */
    fun format(hex: String): String {
        val n = normalise(hex)
        if (n.length != 64) return n
        return n.chunked(4).joinToString(" ")
    }

    /**
     * OkHttp's pin format is base64(SHA-256(der)). Kept for callers that want
     * a [CertificatePinner] instead of the trust manager below â€” for example
     * when the relay has a certificate signed by a real CA.
     */
    fun toOkHttpPin(sha256FingerprintHex: String): String {
        val normalised = normalise(sha256FingerprintHex)
        require(normalised.length == 64) { "not a SHA-256 fingerprint: $sha256FingerprintHex" }
        val bytes = ByteArray(32) { i -> normalised.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return "sha256/${java.util.Base64.getEncoder().encodeToString(bytes)}"
    }

    fun pinnerFor(host: String, sha256FingerprintHex: String): CertificatePinner {
        val normalised = normalise(sha256FingerprintHex)
        require(normalised.length == 64) {
            "expected a 32-byte SHA-256 fingerprint, got ${normalised.length / 2} bytes"
        }
        return CertificatePinner.Builder().add(host, "pin-sha256/$normalised").build()
    }
}

/**
 * Trust manager implementing trust-on-first-use.
 *
 * Behaviour, precisely:
 *  - [pinnedHex] is null â†’ this is first contact. The chain is accepted and
 *    [observedFingerprint] is set so the caller can record it. Nothing is
 *    persisted until the app chooses to.
 *  - [pinnedHex] is set â†’ the leaf must hash to exactly that value, otherwise
 *    [CertificateException]. There is no fallback to the system store and no
 *    "proceed anyway" path, because that is what makes pinning meaningful.
 *
 * @param pinnedHex the recorded fingerprint, or null on first contact
 * @param onObserved invoked with the leaf fingerprint whenever a chain is
 *   accepted, so the caller can surface it in the UI
 */
class TofuTrustManager(
    private val pinnedHex: String?,
    private val onObserved: (String) -> Unit = {},
) : X509TrustManager {

    // Top-level rather than a member of the TlsPinner object: this class is
    // declared outside that object, so it cannot see the object's private
    // constants.
    private val tag = "HushTls"

    /**
     * Fingerprint of the last accepted chain. Written by the TLS thread and
     * read by the WebSocket callback thread, hence volatile.
     */
    @Volatile
    var observedFingerprint: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val hex = TlsPinner.fingerprintHex(chain.toList())
            ?: throw CertificateException("relay presented an empty certificate chain")

        val pinned = pinnedHex?.let { TlsPinner.normalise(it) }

        if (pinned == null) {
            // First contact. Record, do not persist.
            observedFingerprint = hex
            onObserved(hex)
            Log.i(tag, "first contact with relay; observed certificate ${TlsPinner.format(hex)}")
            return
        }

        if (hex != pinned) {
            Log.e(tag, "relay certificate changed")
            throw CertificateException(
                "The relay presented a different certificate than the one recorded for this " +
                    "relay address.\n\nrecorded: ${TlsPinner.format(pinned)}\n" +
                    "offered:  ${TlsPinner.format(hex)}",
            )
        }

        observedFingerprint = hex
        onObserved(hex)
    }

    /**
     * No client certificates. HUSH authenticates nobody with a certificate â€”
     * peers are identified by a key generated on device, never issued.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("HUSH does not use client certificates")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Wrap the trust manager in an [SSLContext] for OkHttp.
 *
 * Deliberately NOT seeded with the system trust store: a HUSH relay is either
 * self-signed or presented by a host we pin explicitly, and letting a public CA
 * validate a relay we are pinning would mean two independent trust decisions
 * for one connection.
 */
fun buildSslContext(trustManager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), java.security.SecureRandom())
    }

