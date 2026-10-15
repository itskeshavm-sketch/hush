package dev.hush.chat.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Primitive wrappers over BouncyCastle.
 *
 * Everything here is pure JVM with no Android dependency, which is what lets
 * [dev.hush.chat.crypto.DoubleRatchetTest] run as a plain `gradlew test` on a
 * developer's machine instead of needing an emulator.
 *
 * Algorithm choices, and why:
 *
 *  - **X25519** for key agreement. Not Curve25519/ECDSA: X25519 has no
 *    contributory behaviour and no known small-subgroup footgun. Its
 *    all-zero-output check is enforced in [x25519].
 *
 *  - **ChaCha20-Poly1305** for AEAD, with a 96-bit nonce. The 192-bit
 *    "XChaCha" variant would remove nonce-reuse anxiety entirely, but
 *    BouncyCastle ships it in a different artifact than `bcprov-jdk18on`, and
 *    HUSH generates a *fresh message key per message* — every key seals
 *    exactly one payload. Uniqueness is therefore guaranteed by the ratchet
 *    rather than by a wide nonce, which is a stronger guarantee, not a weaker
 *    one.
 *
 *  - **HKDF-SHA256** everywhere a key is derived, with an explicit info string
 *    for domain separation. Never a bare hash.
 *
 *  - **Ed25519** for prekey bundle signatures. This is what stops a malicious
 *    relay from handing Alice a prekey bundle that lets the relay itself
 *    decrypt her first message.
 *
 *  - **BLAKE2b** only for the safety-number digest, never for key material.
 */
internal object Primitives {

    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    private const val MAC_BITS = 128

    /** One shared RNG. Constructing a SecureRandom per call reseeds from the
     *  kernel pool, which is slow and an entropy-management smell. Every use
     *  here is already off the UI thread. */
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // -----------------------------------------------------------------------
    // X25519
    // -----------------------------------------------------------------------

    /**
     * Raw scalar multiplication per RFC 7748.
     *
     * @throws IllegalArgumentException if the result is all zeros. A
     *   small-order peer key produces a zero shared secret; rejecting it is
     *   mandatory, because continuing would derive every session key from a
     *   value the peer also knows.
     */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_LEN) { "private key must be 32 bytes, was ${privateKey.size}" }
        require(publicKey.size == KEY_LEN) { "public key must be 32 bytes, was ${publicKey.size}" }

        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = X25519PublicKeyParameters(publicKey, 0)

        val shared = ByteArray(KEY_LEN)
        try {
            priv.generateSecret(pub, shared, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle throws IllegalStateException("X25519 agreement
            // failed") for an all-zero result rather than returning it. That
            // happens for any small-order peer key. Convert it so callers have
            // one failure mode to handle instead of two.
            throw IllegalArgumentException(
                "X25519 produced an all-zero shared secret: small-order peer key",
                e,
            )
        }

        if (shared.all { it == 0.toByte() }) {
            throw IllegalArgumentException("X25519 produced an all-zero shared secret: small-order peer key")
        }
        return shared
    }

    /** Derive the public key for an X25519 private key. */
    fun x25519Public(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_LEN) { "private key must be 32 bytes, was ${privateKey.size}" }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    // -----------------------------------------------------------------------
    // Ed25519
    // -----------------------------------------------------------------------

    fun ed25519Public(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_LEN) { "Ed25519 seed must be 32 bytes, was ${privateKey.size}" }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != KEY_LEN) return false

        // The key is parsed INSIDE the try. `Ed25519PublicKeyParameters` throws
        // IllegalArgumentException("invalid public key") for any 32 bytes that
        // is not a valid curve point — which is roughly half of all random
        // input. Constructing it outside the guard turns a corrupted or
        // hostile prekey bundle into a crash instead of a rejected bundle.
        return try {
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // KDFs
    // -----------------------------------------------------------------------

    /** HMAC-SHA256, the only HMAC in the system. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** HKDF-SHA256 extract-and-expand into [length] bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_LEN): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    /** BLAKE2b-[outLen*8]. Used only for the safety number, never for keys. */
    fun blake2b(data: ByteArray, outLen: Int = 32): ByteArray {
        val digest = Blake2bDigest(outLen * 8)
        digest.update(data, 0, data.size)
        return ByteArray(outLen).also { digest.doFinal(it, 0) }
    }

    // -----------------------------------------------------------------------
    // AEAD
    // -----------------------------------------------------------------------

    /**
     * ChaCha20-Poly1305 seal.
     *
     * @param aad additional authenticated data — bound into the tag but not
     *   encrypted. HUSH binds the conversation id here, which is what stops a
     *   ciphertext being replayed into a different conversation by anyone who
     *   can reorder frames, including a hostile relay.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AEAD key must be 32 bytes" }
        require(nonce.size == NONCE_LEN) { "ChaCha20 nonce must be $NONCE_LEN bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    /** Open a sealed box. Throws [org.bouncycastle.crypto.InvalidCipherTextException] on tampering. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AEAD key must be 32 bytes" }
        require(nonce.size == NONCE_LEN) { "ChaCha20 nonce must be $NONCE_LEN bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }
}
