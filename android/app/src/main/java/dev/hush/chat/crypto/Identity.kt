package dev.hush.chat.crypto

/**
 * A device's long-term cryptographic identity.
 *
 * Two secrets, not one, and the split is load-bearing:
 *
 *  - [identityKey] (X25519) is used for key agreement. Publishing it is what
 *    lets someone open a session with you.
 *  - [signingKey] (Ed25519) is used *only* to sign prekey bundles. Publishing
 *    the corresponding public key is what lets a third party — the relay —
 *    verify a bundle really came from you.
 *
 * Deriving the signing key from the identity key would create a correlation
 * between "the person you are talking to" and "the person who signs prekeys"
 * that neither protocol needs. Two independent keys keep the two roles
 * separable, matching the Signal identity/signing split.
 *
 * **There is no recovery.** These keys are not backed up (see
 * `res/xml/data_extraction_rules.xml`) and not exportable. A new install is a
 * new identity. That is the cost of having no account to reset, and it is
 * stated plainly in Settings rather than discovered later.
 */
class Identity(
    val identityKey: KeyPair,
    val signingKey: ByteArray,
) {
    val identityPublic: ByteArray get() = identityKey.publicKey
    val signingPublic: ByteArray by lazy { Primitives.ed25519Public(signingKey) }

    /** Fingerprint fragment shown to users comparing safety numbers. */
    fun shortFingerprint(): String =
        Primitives.blake2b(identityPublic, 6).joinToString("") { "%02X".format(it) }

    fun wipe() {
        identityKey.wipe()
        Primitives.wipe(signingKey)
    }
}
