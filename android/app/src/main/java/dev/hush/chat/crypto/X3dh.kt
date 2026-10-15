package dev.hush.chat.crypto

/**
 * X3DH: extended triple Diffie-Hellman key agreement.
 *
 * Purpose is forward secrecy on *first* contact. Plain X25519 between two
 * long-term identity keys has none: anyone who later steals Alice's identity
 * private key can decrypt a recorded first message, because that message was
 * sealed under `DH(IK_alice, IK_bob)`.
 *
 * X3DH fixes that by mixing in an ephemeral key that never touches disk:
 *
 *   DH1 = DH(IK_alice,  SPK_bob)   authenticated, both long-term
 *   DH2 = DH(EK_alice, SPK_bob)   ephemeral on the initiator side
 *   DH3 = DH(EK_alice, IK_bob)    ephemeral, authenticated by SPK's signature
 *
 * Bob computes the same three values with his own private keys, so both sides
 * agree without a round trip.
 *
 * The relay cannot substitute its own prekey bundle: [PrekeyBundle.signed] is
 * an Ed25519 signature over Bob's identity key, and Alice refuses a bundle
 * whose signature does not verify against the identity key the relay
 * advertised. That check is the whole reason the relay is allowed to broker
 * prekeys at all.
 */
object X3dh {

    private val INFO = "HUSH/x3dh/v1".toByteArray()

    /**
     * HKDF salt.
     *
     * MUST be identical on both sides. An earlier draft used a random 32-byte
     * salt in each direction, which guarantees the two halves derive different
     * root keys and the session never starts. There is no secret here worth
     * salting with â€” the input keying material is already a fresh DH output â€”
     * so it is a fixed domain-separation value.
     */
    private val SALT = "HUSH/x3dh/salt/v1".toByteArray()

    /**
     * @param identityKey the local long-term X25519 key pair
     * @param remote their published [PrekeyBundle]
     * @return an initial Double Ratchet root key plus the ratchet public key
     *   the initiator should treat as the peer's first "their" key
     */
    fun initiate(
        identityKey: KeyPair,
        remote: PrekeyBundle,
    ): InitialSession {
        val ephemeral = KeyPair.generate()

        val dh1 = Primitives.x25519(identityKey.privateKey, remote.signed.key)
        val dh2 = Primitives.x25519(ephemeral.privateKey, remote.signed.key)
        val dh3 = Primitives.x25519(ephemeral.privateKey, remote.identity)

        val shared = Primitives.concat(dh1, dh2, dh3)
        val rootKey = Primitives.hkdf(shared, SALT, INFO)

        // Zero the raw material as soon as the root key exists. These buffers
        // are the most valuable bytes in the process.
        Primitives.wipe(dh1); Primitives.wipe(dh2); Primitives.wipe(dh3); Primitives.wipe(shared)

        // Alice's first ratchet peer key is Bob's signed prekey, not his
        // identity key: the ratchet must start from something that can be
        // rotated, otherwise the first DHRatchet yields no forward secrecy.
        //
        // The ephemeral key pair is returned rather than discarded. It *is* the
        // initiator's first ratchet key, so the DH key in the first message
        // header tells the responder which ephemeral was used and lets it
        // recompute the same root key. Discarding it would make the responder's
        // half of the handshake unreconstructable.
        return InitialSession(
            rootKey = rootKey,
            theirRatchetKey = remote.signed.key,
            initialRatchet = ephemeral,
        )
    }

    /**
     * Responder side. Recomputes the identical root key from its own keys plus
     * the initiator's public identity and ephemeral keys.
     */
    fun respond(
        identityKey: KeyPair,
        signedPrekey: SignedPrekey,
        remoteIdentityPublic: ByteArray,
        remoteEphemeralPublic: ByteArray,
    ): InitialSession {
        val dh1 = Primitives.x25519(signedPrekey.privateKey, remoteIdentityPublic)
        val dh2 = Primitives.x25519(signedPrekey.privateKey, remoteEphemeralPublic)
        val dh3 = Primitives.x25519(identityKey.privateKey, remoteEphemeralPublic)

        val shared = Primitives.concat(dh1, dh2, dh3)
        val rootKey = Primitives.hkdf(shared, SALT, INFO)

        Primitives.wipe(dh1); Primitives.wipe(dh2); Primitives.wipe(dh3); Primitives.wipe(shared)

        return InitialSession(
            rootKey = rootKey,
            // Until the initiator's first message arrives we only know their
            // identity key. DoubleRatchet.adoptPeerRatchetKey replaces this
            // with the real ratchet key from the message header.
            theirRatchetKey = remoteIdentityPublic,
            // The responder's first ratchet key is its SIGNED PREKEY, not a
            // fresh key. The initiator derives its first receiving chain from
            // DH(their_ephemeral, signedPrekey), so the only local key the
            // responder can match that against is the signed prekey itself.
            // Generating a fresh key here makes the first message permanently
            // undecryptable.
            initialRatchet = KeyPair.from(
                signedPrekey.privateKey.copyOf(),
                Primitives.x25519Public(signedPrekey.privateKey),
            ),
        )
    }
}

/** An X25519 key pair. [privateKey] is 32 bytes, [publicKey] is 32 bytes. */
class KeyPair private constructor(val privateKey: ByteArray, val publicKey: ByteArray) {

    /** Overwrite private material. Safe to call more than once. */
    fun wipe() = Primitives.wipe(privateKey)

    companion object {
        fun generate(): KeyPair {
            val priv = Primitives.randomBytes(32)
            // Clamp happens inside X25519PrivateKeyParameters, and the public
            // key is derived from the same clamped scalar, so the two are
            // always consistent.
            return KeyPair(priv, Primitives.x25519Public(priv))
        }

        internal fun from(priv: ByteArray, pub: ByteArray) = KeyPair(priv.copyOf(), pub.copyOf())
    }
}

/** A prekey plus the Ed25519 signature that binds it to the identity key. */
class SignedPrekey(val privateKey: ByteArray, val publicKey: ByteArray, val counter: Int, val signature: ByteArray) {
    fun wipe() = Primitives.wipe(privateKey)
}

/**
 * Everything a peer publishes so a stranger can open a session with them.
 *
 * @property identity their X25519 public key, for key agreement
 * @property signingKey their Ed25519 public key, for signature verification
 * @property prekey a one-time X25519 key
 * @property signed the signed prekey bundle
 *
 * [identity] and [signingKey] are separate keys and both must be carried. An
 * X25519 public key and an Ed25519 public key are both 32 bytes but encode
 * different mathematical objects, and roughly half of all X25519 public keys
 * are not valid Ed25519 curve points at all. Verifying a prekey signature with
 * the X25519 identity key therefore rejects about half of all legitimate
 * peers, and the symptom reads as: some people simply cannot message me.
 */
data class PrekeyBundle(
    val identity: ByteArray,
    val signingKey: ByteArray,
    val prekey: ByteArray,
    val signed: SignedPrekeyPublic,
) {
    fun toBase64Json(): String = buildString {
        append('{')
        append("\"identity\":\"${identity.b64()}\",")
        append("\"signingKey\":\"${signingKey.b64()}\",")
        append("\"prekey\":\"${prekey.b64()}\",")
        append("\"signedPrekey\":{")
        append("\"key\":\"${signed.key.b64()}\",")
        append("\"sig\":\"${signed.signature.b64()}\",")
        append("\"counter\":${signed.counter}")
        append("}}")
    }

    companion object {
        /**
         * Parse and, critically, verify. Returns null if anything is off,
         * because a bundle that fails verification is indistinguishable from
         * an attack and must never be used.
         */
        fun fromBase64Json(json: String): PrekeyBundle? {
            val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
            val signedJson = root.optJSONObject("signedPrekey") ?: return null

            val identity = root.optString("identity").unB64() ?: return null
            val signingKey = root.optString("signingKey").unB64() ?: return null
            val prekey = root.optString("prekey").unB64() ?: return null
            val signedKey = signedJson.optString("key").unB64() ?: return null
            val sig = signedJson.optString("sig").unB64() ?: return null
            val counter = signedJson.optInt("counter", -1)
            if (counter < 0) return null

            if (identity.size != 32 || signingKey.size != 32 || prekey.size != 32 || signedKey.size != 32) {
                return null
            }

            // The signature covers identity, prekey and counter. Binding all
            // three stops a relay from swapping the prekey while keeping a
            // valid signature. Verified against the SIGNING key, never the
            // identity key.
            if (!Primitives.ed25519Verify(sig, prekeySignatureMessage(identity, prekey, counter), signingKey)) {
                return null
            }

            return PrekeyBundle(identity, signingKey, prekey, SignedPrekeyPublic(signedKey, sig, counter))
        }
    }
}

/** The exact bytes a peer signs when publishing a bundle. */
fun prekeySignatureMessage(identity: ByteArray, prekey: ByteArray, counter: Int): ByteArray =
    Primitives.concat(
        "HUSH/prekey/v1".toByteArray(),
        identity,
        prekey,
        counter.toString().toByteArray(),
    )

data class SignedPrekeyPublic(val key: ByteArray, val signature: ByteArray, val counter: Int)

/**
 * The result of a completed X3DH exchange.
 *
 * @param rootKey seed for the Double Ratchet
 * @param theirRatchetKey the peer's current ratchet public key, if known
 * @param initialRatchet our own X3DH ephemeral, which becomes the first
 *   ratchet key. Null on the responder side, which has no ephemeral.
 */
data class InitialSession(
    val rootKey: ByteArray,
    val theirRatchetKey: ByteArray,
    val initialRatchet: KeyPair? = null,
)

internal fun ByteArray.b64(): String = java.util.Base64.getEncoder().encodeToString(this)

internal fun String.unB64(): ByteArray? = runCatching {
    java.util.Base64.getDecoder().decode(this)
}.getOrNull()
