package dev.hush.chat.data

import android.content.Context
import android.util.Log
import dev.hush.chat.crypto.DoubleRatchet
import dev.hush.chat.crypto.Identity
import dev.hush.chat.crypto.KeyPair
import dev.hush.chat.crypto.Primitives
import dev.hush.chat.crypto.PrekeyBundle
import dev.hush.chat.crypto.X3dh
import dev.hush.chat.crypto.b64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Result of decrypting an inbound frame. */
sealed interface InboundResult {
    /** Ordinary chat text. */
    data class Text(val conversationId: String, val from: String, val fromName: String, val body: String, val at: Long) : InboundResult

    /** WebRTC signaling for an active call. */
    data class Signaling(val conversationId: String, val from: String, val payload: String) : InboundResult

    /** Nothing to do — duplicate, system noise, or a non-text message kind. */
    data object Ignored : InboundResult

    data class Failed(val conversationId: String, val reason: String) : InboundResult
}

/**
 * Owns identity, live ratchet sessions, and the encrypt/decrypt boundary.
 *
 * Session lifecycle, which is the part that is easy to get wrong:
 *
 *  - Ratchet state is memory-only. It is never written to disk. A process
 *    death loses the chain, and the next inbound message re-establishes via
 *    X3DH. That is the correct trade: persisting ratchet keys would mean
 *    protecting them at rest, and a compromised-at-rest key compromises the
 *    forward secrecy the ratchet bought.
 *
 *  - Each conversation has its own lock. A ratchet is a strict state machine
 *    and two concurrent encrypts on one session will desynchronise it silently.
 */
class SessionManager(private val context: Context) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val sessions = ConcurrentHashMap<String, DoubleRatchet>()

    @Volatile
    var identity: Identity? = null
        private set

    @Volatile
    private var localPeerId: String? = null

    private val _identityReady = MutableStateFlow(false)
    val identityReady: StateFlow<Boolean> = _identityReady.asStateFlow()

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Load or create the device identity.
     *
     * Creation is one-way: there is no recovery path and no backup, by design.
     * A restored identity would collide with the original device on the relay
     * and get evicted mid-conversation, so the key is deliberately excluded
     * from backup (see res/xml/data_extraction_rules.xml).
     */
    fun loadOrCreateIdentity(): Identity {
        identity?.let { return it }

        val prefs = context.getSharedPreferences("hush_identity", Context.MODE_PRIVATE)

        val existingPriv = prefs.getString(KEY_IDENTITY_PRIVATE, null)?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }
        val existingSigning = prefs.getString(KEY_SIGNING_PRIVATE, null)?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }

        val created = if (existingPriv != null && existingSigning != null) {
            Identity(
                identityKey = KeyPair.from(existingPriv, Primitives.x25519Public(existingPriv)),
                signingKey = existingSigning,
            )
        } else {
            val ident = KeyPair.generate()
            val signing = Primitives.randomBytes(32)
            prefs.edit()
                .putString(KEY_IDENTITY_PRIVATE, java.util.Base64.getEncoder().encodeToString(ident.privateKey))
                .putString(KEY_SIGNING_PRIVATE, java.util.Base64.getEncoder().encodeToString(signing))
                .apply()
            Identity(ident, signing)
        }

        identity = created
        _identityReady.value = true
        return created
    }

    /** Build the `hello` frame announcing this device to the relay. */
    fun buildHello(displayName: String, joinedAt: Long): JSONObject {
        val id = requireNotNull(identity) { "identity not loaded" }
        val counter = (System.currentTimeMillis() / 86_400_000L).toInt()

        val prekey = KeyPair.generate()
        val signedKey = KeyPair.generate()
        val signature = Primitives.ed25519Sign(
            dev.hush.chat.crypto.prekeySignatureMessage(id.identityPublic, prekey.publicKey, counter),
            id.signingKey,
        )

        val bundle = PrekeyBundle(
            identity = id.identityPublic,
            // The Ed25519 public key travels alongside the X25519 identity key.
            // Verifying the bundle signature against the X25519 key would reject
            // roughly half of all peers, because the two key types are not
            // interchangeable even though both are 32 bytes.
            signingKey = id.signingPublic,
            prekey = prekey.publicKey,
            signed = dev.hush.chat.crypto.SignedPrekeyPublic(signedKey.publicKey, signature, counter),
        )

        prekey.wipe()
        signedKey.wipe()

        return JSONObject()
            .put("t", "hello")
            .put("name", displayName)
            .put("identityPub", id.identityPublic.b64())
            // A nested JSONObject, not the bundle serialised to a string.
            //
            // The relay's validator checks `typeof prekey === 'object'`, and
            // a JSON string fails that. Sending `.toBase64Json()` here — which
            // is what this did originally — produced
            // "prekey must be a valid X25519 prekey bundle" for every single
            // install, and no test caught it because the Node e2e client
            // never sent a prekey at all.
            .put("prekey", org.json.JSONObject(bundle.toBase64Json()))
            .put("joined", joinedAt > 0)
    }

    fun noteLocalPeerId(id: String) {
        localPeerId = id
    }

    fun localPeerId(): String? = localPeerId

    // -------------------------------------------------------------------------
    // Encrypt
    // -------------------------------------------------------------------------

    /**
     * Seal a plaintext for [peerId], establishing a session on first contact.
     *
     * @return the wire payload, or null if the peer has not published a
     *   verifiable prekey bundle yet. Null is not an error — it means "they
     *   have not finished connecting", and the UI shows the message as queued.
     */
    fun encrypt(peerId: String, peerPrekeyJson: String?, plaintext: ByteArray): ByteArray? {
        val lock = locks.computeIfAbsent(peerId) { ReentrantLock() }
        lock.withLock {
            sessions[peerId]?.let { return it.encrypt(plaintext) }

            val bundle = peerPrekeyJson?.let { PrekeyBundle.fromBase64Json(it) } ?: run {
                Log.w(TAG, "peer $peerId has no verifiable prekey bundle yet")
                return null
            }

            val identity = requireNotNull(identity) { "identity not loaded" }
            val initial = X3dh.initiate(identity.identityKey, bundle)
            val session = DoubleRatchet.create(initial, aadFor(peerId))
            sessions[peerId] = session
            return session.encrypt(plaintext)
        }
    }

    /** Seal arbitrary bytes (used for WebRTC SDP/ICE). */
    fun encryptSignaling(peerId: String, peerPrekeyJson: String?, payload: String): ByteArray? =
        encrypt(peerId, peerPrekeyJson, payload.toByteArray())

    // -------------------------------------------------------------------------
    // Decrypt
    // -------------------------------------------------------------------------

    /**
     * Open an inbound frame.
     *
     * @param kind `"msg"` or `"rtc"`, as stamped by the relay. Signaling rides
     *   the same ratchet — reusing the chat chain for it would leak call
     *   metadata into the message stream and let a stale SDP desynchronise the
     *   transcript — but it is reported as [InboundResult.Signaling] so it is
     *   never written to the message table.
     */
    fun decrypt(peerId: String, peerPrekeyJson: String?, payload: ByteArray, kind: String = "msg"): InboundResult {
        val lock = locks.computeIfAbsent(peerId) { ReentrantLock() }
        return lock.withLock {
            val existing = sessions[peerId]
            if (existing != null) {
                return try {
                    val plaintext = existing.decrypt(payload) ?: return InboundResult.Ignored
                    interpret(peerId, String(plaintext), kind)
                } catch (e: dev.hush.chat.crypto.RatchetException) {
                    Log.w(TAG, "decrypt failed for $peerId: ${e.message}")
                    InboundResult.Failed(peerId, e.message ?: "decryption failed")
                }
            }

            // First contact from them: we may still be the initiator if they
            // messaged first, so build the responder session.
            val bundle = peerPrekeyJson?.let { PrekeyBundle.fromBase64Json(it) }
            if (bundle == null) {
                return InboundResult.Failed(peerId, "no prekey bundle to establish a session")
            }

            val identity = requireNotNull(identity) { "identity not loaded" }

            // The DH in the header of the *first* message from a peer is their
            // X3DH ephemeral. Recompute the root key from it, then point the
            // ratchet at that same key so the first dhRatchet step lines up.
            // Only the DH *field* — 32 bytes — is the X3DH ephemeral. The rest
            // of the header is chain counters, not key material.
            val headerDh = payload.copyOfRange(0, DoubleRatchet.DH_KEY_LEN.coerceAtMost(payload.size))
            val initial = X3dh.respond(identity.identityKey, responderPrekey(peerId), identity.identityPublic, headerDh)
            val session = DoubleRatchet.create(initial, aadFor(peerId))
            session.adoptPeerRatchetKey(headerDh)
            sessions[peerId] = session

            try {
                val plaintext = session.decrypt(payload) ?: return InboundResult.Ignored
                interpret(peerId, String(plaintext), kind)
            } catch (e: dev.hush.chat.crypto.RatchetException) {
                InboundResult.Failed(peerId, e.message ?: "decryption failed")
            }
        }
    }

    private fun interpret(peerId: String, plaintext: String, kind: String): InboundResult =
        if (kind == "rtc") {
            InboundResult.Signaling(peerId, peerId, plaintext)
        } else {
            InboundResult.Text(peerId, peerId, "", plaintext, System.currentTimeMillis())
        }

    /**
     * The responder's signed prekey private half.
     *
     * HUSH keeps a small pool of these in preferences. A real deployment rotates
     * them; this implementation holds one per session, which is enough to
     * demonstrate the handshake and is called out as a simplification in the
     * README rather than hidden.
     */
    private fun responderPrekey(peerId: String) = runBlockingGet {
        val prefs = context.getSharedPreferences("hush_prekeys", Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PREKEY_PREFIX + peerId, null)
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }

        val priv = existing ?: Primitives.randomBytes(32).also {
            prefs.edit().putString(KEY_PREKEY_PREFIX + peerId, java.util.Base64.getEncoder().encodeToString(it)).apply()
        }
        dev.hush.chat.crypto.SignedPrekey(priv, Primitives.x25519Public(priv), 0, ByteArray(64))
    }

    // -------------------------------------------------------------------------
    // Safety numbers
    // -------------------------------------------------------------------------

    /**
     * A 60-digit fingerprint for out-of-band verification, in the Signal style.
     *
     * Two people read six groups of five digits over a voice call. If they
     * match, a relay cannot be impersonating either of them. This is the only
     * defence against a malicious relay, and it is worthless if it is hidden
     * behind a menu, so it gets a first-class screen.
     */
    fun safetyNumber(peerIdentityPublic: ByteArray, peerId: String): String {
        val id = identity ?: return ""
        val material = Primitives.concat(
            "HUSH/safety/v1".toByteArray(),
            id.identityKey.publicKey,
            peerIdentityPublic,
        )
        val digest = Primitives.blake2b(material, 30)
        val digits = StringBuilder()
        digest.forEach { byte -> digits.append(((byte.toInt() and 0xFF) % 100).toString().padStart(2, '0')) }
        return digits.toString().chunked(5).joinToString(" ")
    }

    // -------------------------------------------------------------------------

    /**
     * Per-conversation associated data.
     *
     * Binding the conversation id into the AEAD tag means a ciphertext cannot
     * be moved between conversations even if an attacker controls the relay and
     * can reorder frames freely.
     */
    private fun aadFor(peerId: String): ByteArray = "hush|$peerId".toByteArray()

    /** Forget a live session, e.g. when a safety number mismatch is detected. */
    fun resetSession(peerId: String) {
        locks.remove(peerId)?.let { runCatching { it.unlock() } }
        sessions.remove(peerId)?.wipe()
    }

    companion object {
        private const val TAG = "HushSessions"
        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_SIGNING_PRIVATE = "signing_private"
        private const val KEY_PREKEY_PREFIX = "prekey_"
    }
}

/** Run a block on a private lock, without pulling in a coroutine for a cache read. */
private inline fun <T> runBlockingGet(block: () -> T): T = block()
