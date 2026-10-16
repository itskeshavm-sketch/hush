package dev.hush.chat.data

import android.content.Context
import android.util.Log
import dev.hush.chat.crypto.DoubleRatchet
import dev.hush.chat.crypto.Identity
import dev.hush.chat.crypto.KeyPair
import dev.hush.chat.crypto.Primitives
import dev.hush.chat.crypto.PrekeyBundle
import dev.hush.chat.crypto.RatchetException
import dev.hush.chat.crypto.SignedPrekey
import dev.hush.chat.crypto.SignedPrekeyPublic
import dev.hush.chat.crypto.X3dh
import dev.hush.chat.crypto.b64
import dev.hush.chat.crypto.prekeySignatureMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Result of decrypting an inbound frame. */
sealed interface InboundResult {
    /**
     * Ordinary chat text.
     *
     * [msgId] is the sender's stable message id, carried inside the encrypted
     * payload. It must be the sender's id — not a fresh random one — or delete-
     * for-everyone can never match the recipient's row against the sender's
     * delete frame. That was exactly the old bug: both sides minted their own
     * UUIDs, so a delete by the sender's id matched nothing on receipt.
     *
     * [expSentMs] / [expSeenMs] are the sender's disappearing timers for this
     * message, also inside the ciphertext: the receiver enforces the sender's
     * policy, so both sides agree without an extra handshake. Null means no
     * timer of that kind.
     */
    data class Text(
        val conversationId: String,
        val from: String,
        val fromName: String,
        val body: String,
        val at: Long,
        val msgId: String,
        val expSentMs: Long? = null,
        val expSeenMs: Long? = null,
    ) : InboundResult

    /** WebRTC signaling for an active call. */
    data class Signaling(val conversationId: String, val from: String, val payload: String) : InboundResult

    /**
     * One chunk of an image message.
     *
     * Images ride the same E2E ratchet as text, but the relay caps a frame at
     * 64 KiB — so a photo travels as numbered chunks sharing the sender's
     * message id. Every chunk carries the caption and timers, so any single
     * chunk describes the whole message and reassembly needs no side channel.
     * [data] is raw JPEG bytes (already base64-decoded from the payload).
     */
    data class ImageChunk(
        val conversationId: String,
        val from: String,
        val msgId: String,
        val caption: String,
        val index: Int,
        val total: Int,
        val data: ByteArray,
        val expSentMs: Long?,
        val expSeenMs: Long?,
    ) : InboundResult

    /** Nothing to do — duplicate, system noise, or a non-text message kind. */
    data object Ignored : InboundResult

    data class Failed(val conversationId: String, val reason: String) : InboundResult

    /**
     * The sender is not speaking the app protocol at all.
     *
     * Two concrete cases, both of which used to surface as the generic
     * "Could not decrypt a message from X":
     *
     *  - the browser preview client, which XOR-obfuscates instead of running
     *    X3DH + Double Ratchet, and whose prekey bundle carries a random
     *    signature that fails verification by design;
     *  - relay-level test probes, which send raw envelopes with random
     *    signatures for the same reason.
     *
     * Neither can ever decrypt against this device, no matter how many times
     * the session is reset, so the UI must say that instead of implying a
     * transient failure.
     */
    data class Incompatible(val conversationId: String, val reason: String) : InboundResult
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
        val local = localBundle()

        val bundle = PrekeyBundle(
            identity = id.identityPublic,
            // The Ed25519 public key travels alongside the X25519 identity key.
            // Verifying the bundle signature against the X25519 key would reject
            // roughly half of all peers, because the two key types are not
            // interchangeable even though both are 32 bytes.
            signingKey = id.signingPublic,
            prekey = local.prekeyPublic,
            signed = SignedPrekeyPublic(local.signedPublic, local.signature, local.counter),
        )

        // The private halves are deliberately NOT wiped here.
        //
        // They used to be, which is why first contact never worked. The
        // published bundle is a public API: once a peer has it, every message
        // sealed against it must stay decryptable by *this* device, for as long
        // as the relay keeps handing that bundle out. Wiping the private halves
        // at the end of this function made the device publish a key and
        // immediately destroy the ability to answer for it, so the responder
        // fell back to an invented key and derived a different root key than
        // the sender — "Could not decrypt a message from X", every time, for
        // every peer, with no error anywhere except the AEAD tag failing.
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

    /**
     * This device's own prekey bundle, private halves included.
     *
     * Generated once and reused for the lifetime of the install. Two properties
     * matter and both were violated before:
     *
     *  1. The signed prekey's private half must survive. It is the responder's
     *     half of X3DH: the initiator derives `DH2 = DH(EK, SPK_public)`, so
     *     only the holder of the matching private half can reproduce it.
     *
     *  2. The published bundle must be *stable across reconnects*. Peers cache
     *     the bundle from the roster; if every `hello` published fresh keys,
     *     a message sealed against yesterday's copy could never be opened, and
     *     the failure would look identical to a random one.
     *
     * Prekey rotation is a documented simplification — see the README. One
     * stable bundle is the honest version of that, not an oversight.
     */
    private fun localBundle(): LocalBundle {
        val id = requireNotNull(identity) { "identity not loaded" }
        val prefs = context.getSharedPreferences(PREFS_PREKEYS, Context.MODE_PRIVATE)

        val decode = { raw: String? ->
            raw?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        }

        val prekeyPrivate = decode(prefs.getString(KEY_LOCAL_PREKEY, null))
        val signedPrivate = decode(prefs.getString(KEY_LOCAL_SIGNED, null))

        if (prekeyPrivate?.size == 32 && signedPrivate?.size == 32) {
            val counter = prefs.getInt(KEY_LOCAL_COUNTER, 0)
            val prekeyPublic = Primitives.x25519Public(prekeyPrivate)
            val signedPublic = Primitives.x25519Public(signedPrivate)
            // Recomputed rather than stored: the signing key is already
            // persisted, and a bundle whose signature does not match its own
            // fields is rejected by every peer.
            val signature = Primitives.ed25519Sign(
                prekeySignatureMessage(id.identityPublic, prekeyPublic, counter),
                id.signingKey,
            )
            return LocalBundle(
                prekeyPrivate, prekeyPublic, signedPrivate, signedPublic, signature, counter,
            )
        }

        val prekey = KeyPair.generate()
        val signed = KeyPair.generate()
        val counter = (System.currentTimeMillis() / 86_400_000L).toInt()
        val signature = Primitives.ed25519Sign(
            prekeySignatureMessage(id.identityPublic, prekey.publicKey, counter),
            id.signingKey,
        )

        prefs.edit()
            .putString(KEY_LOCAL_PREKEY, java.util.Base64.getEncoder().encodeToString(prekey.privateKey))
            .putString(KEY_LOCAL_SIGNED, java.util.Base64.getEncoder().encodeToString(signed.privateKey))
            .putInt(KEY_LOCAL_COUNTER, counter)
            .apply()

        return LocalBundle(
            prekeyPrivate = prekey.privateKey.copyOf(),
            prekeyPublic = prekey.publicKey.copyOf(),
            signedPrivate = signed.privateKey.copyOf(),
            signedPublic = signed.publicKey.copyOf(),
            signature = signature,
            counter = counter,
        )
    }

    /** The private halves behind the bundle this device publishes. */
    private data class LocalBundle(
        val prekeyPrivate: ByteArray,
        val prekeyPublic: ByteArray,
        val signedPrivate: ByteArray,
        val signedPublic: ByteArray,
        val signature: ByteArray,
        val counter: Int,
    )

    fun noteLocalPeerId(id: String) {
        // Never blank a known id: a malformed welcome must not reopen the
        // self-in-roster hole that syncRoster guards below.
        if (id.isNotEmpty()) localPeerId = id
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
            // A ratchet frame is HEADER(40) + NONCE(24) + TAG(16) at minimum.
            // Anything shorter is not a failed decryption — it is a different
            // protocol. The browser preview sends short XOR envelopes; trying
            // to X3DH-respond to those would either crash on a short header
            // slice or poison the session with a garbage root key.
            if (payload.size < DoubleRatchet.HEADER_LEN + Primitives.NONCE_LEN + DoubleRatchet.MAC_LEN) {
                return InboundResult.Incompatible(peerId, "payload too short to be an app message (${payload.size} bytes)")
            }

            val existing = sessions[peerId]
            if (existing != null) {
                return try {
                    val plaintext = existing.decrypt(payload) ?: return InboundResult.Ignored
                    interpret(peerId, String(plaintext), kind)
                } catch (e: RatchetException) {
                    Log.w(TAG, "decrypt failed for $peerId: ${e.message}")
                    InboundResult.Failed(peerId, e.message ?: "decryption failed")
                } catch (e: Exception) {
                    // DoubleRatchet.decrypt uses require() for structural
                    // problems, which throws IllegalArgumentException rather
                    // than RatchetException. Letting that escape would crash
                    // the frame collector; a malformed frame is a failed
                    // message, never a crash.
                    Log.w(TAG, "decrypt malformed frame for $peerId: ${e.message}")
                    InboundResult.Failed(peerId, e.message ?: "malformed frame")
                }
            }

            // First contact from them: we may still be the initiator if they
            // messaged first, so build the responder session.
            //
            // A null bundle here almost always means the sender is the browser
            // preview or a test probe: both publish bundles with random
            // signatures that fail verification by design. Reporting that as a
            // generic decrypt failure sends the user chasing a key mismatch
            // that does not exist.
            val bundle = peerPrekeyJson?.let { PrekeyBundle.fromBase64Json(it) }
            if (bundle == null) {
                return InboundResult.Incompatible(peerId, "no verifiable app prekey bundle")
            }

            val identity = requireNotNull(identity) { "identity not loaded" }

            // The DH in the header of the *first* message from a peer is their
            // X3DH ephemeral. Recompute the root key from it, then point the
            // ratchet at that same key so the first dhRatchet step lines up.
            // Only the DH *field* — 32 bytes — is the X3DH ephemeral. The rest
            // of the header is chain counters, not key material.
            //
            // The length check above guarantees payload.size >= 80, so this
            // slice is always a full 32 bytes.
            val headerDh = payload.copyOfRange(0, DoubleRatchet.DH_KEY_LEN)

            // The signed prekey handed to `respond` MUST be the one this device
            // published in its bundle — private half included.
            //
            // This previously generated a fresh random key per conversation and
            // persisted that instead. The initiator had sealed against the
            // *published* signed prekey, so the two sides derived different
            // root keys and every first message failed to authenticate with
            // "Could not decrypt a message from X". `X3dh.respond` carries a
            // comment warning that generating a fresh key here makes the first
            // message permanently undecryptable; the caller did exactly that.
            val local = localBundle()
            val initial = try {
                // remoteIdentityPublic MUST be the sender's identity key, from
                // their verified bundle — not our own.
                //
                // This previously passed `identity.identityPublic` (our own key).
                // Responder DH1 is DH(SPK_ours, remoteIdentity), initiator DH1
                // is DH(IK_theirs, SPK_ours_pub). Those agree only when
                // remoteIdentity is THEIR identity: DH(SPK_B, IK_A) equals
                // DH(IK_A, SPK_B) by symmetry, while DH(SPK_B, IK_B) — what
                // the old code computed — is a different value entirely. Same
                // shape of failure as the wiped prekeys and the directional
                // aad: correct keys on both sides, different root keys, tag
                // never verifies, toast on every message.
                X3dh.respond(
                    identity.identityKey,
                    SignedPrekey(
                        privateKey = local.signedPrivate.copyOf(),
                        publicKey = local.signedPublic,
                        counter = local.counter,
                        signature = local.signature,
                    ),
                    bundle.identity,
                    headerDh,
                )
            } catch (e: Exception) {
                Log.w(TAG, "x3dh respond failed for $peerId: ${e.message}")
                return InboundResult.Failed(peerId, "key agreement failed")
            }
            val session = DoubleRatchet.create(initial, aadFor(peerId))
            session.adoptPeerRatchetKey(headerDh)
            sessions[peerId] = session

            try {
                val plaintext = session.decrypt(payload) ?: return InboundResult.Ignored
                interpret(peerId, String(plaintext), kind)
            } catch (e: RatchetException) {
                InboundResult.Failed(peerId, e.message ?: "decryption failed")
            } catch (e: Exception) {
                Log.w(TAG, "first-contact decrypt malformed for $peerId: ${e.message}")
                InboundResult.Failed(peerId, e.message ?: "malformed frame")
            }
        }
    }

    private fun interpret(peerId: String, plaintext: String, kind: String): InboundResult {
        if (kind == "rtc") {
            return InboundResult.Signaling(peerId, peerId, plaintext)
        }
        // Payload format v2: {"v":2,"id":"<sender uuid>","body":"...",
        // "ss":<after-sent ms>,"sn":<after-seen ms>}. v1 (no timers) is
        // accepted the same way. Older builds sent raw text with no id; those
        // get a fresh random id so they still display, but they can never be
        // deleted for everyone — there is no shared id to address.
        //
        // Timer bounds are enforced, not trusted: a malicious peer could
        // otherwise set a one-millisecond timer (instant wipe, annoying) or a
        // century (never expires, harmless but wrong). Out-of-range values are
        // dropped to "no timer" rather than rejecting the message, because a
        // bad timer must never cost the text.
        val parsed = runCatching { org.json.JSONObject(plaintext) }.getOrNull()
        if (parsed != null) {
            val version = parsed.optInt("v", 0)
            if (version == 1 || version == 2) {
                val id = parsed.optString("id")
                val body = parsed.optString("body")
                if (id.isNotEmpty()) {
                    return InboundResult.Text(
                        peerId, peerId, "", body, System.currentTimeMillis(), id,
                        expSentMs = parsed.optLong("ss", -1L).takeIf { it in 1..MAX_TIMER_MS },
                        expSeenMs = parsed.optLong("sn", -1L).takeIf { it in 1..MAX_TIMER_MS },
                    )
                }
            }
            if (version == 3) {
                // Image chunk. Bounds enforced before anything is allocated:
                // a malicious peer announcing total = 1_000_000 must not get
                // a million-slot array, and an index outside the range must
                // not write anywhere.
                val id = parsed.optString("id")
                val total = parsed.optInt("n", 0)
                val index = parsed.optInt("ch", -1)
                val dataB64 = parsed.optString("data")
                if (id.isNotEmpty() && total in 1..MAX_IMAGE_CHUNKS && index in 0 until total &&
                    dataB64.length in 1..MAX_CHUNK_B64
                ) {
                    val data = runCatching {
                        java.util.Base64.getDecoder().decode(dataB64)
                    }.getOrNull()
                    if (data != null && data.size in 1..MAX_CHUNK_RAW) {
                        return InboundResult.ImageChunk(
                            peerId, peerId, id,
                            caption = parsed.optString("body"),
                            index = index,
                            total = total,
                            data = data,
                            expSentMs = parsed.optLong("ss", -1L).takeIf { it in 1..MAX_TIMER_MS },
                            expSeenMs = parsed.optLong("sn", -1L).takeIf { it in 1..MAX_TIMER_MS },
                        )
                    }
                }
                // A malformed chunk is a failed image, never a crash and never
                // a row: without all chunks in range there is nothing to show.
                return InboundResult.Ignored
            }
        }
        return InboundResult.Text(
            peerId, peerId, "", plaintext, System.currentTimeMillis(),
            java.util.UUID.randomUUID().toString(),
        )
    }

    /**
     * Build the encrypted payload for an outgoing text.
     *
     * The id travels inside the ciphertext so the recipient stores the same row
     * id the sender holds. Delete-for-everyone is addressed by that id; without
     * it the two sides disagree and deletes silently match nothing. The timers
     * travel the same way, so the receiver enforces the sender's policy with no
     * extra handshake — and the relay never sees any of it.
     */
    fun sealText(id: String, body: String, expSentMs: Long?, expSeenMs: Long?): ByteArray {
        val o = org.json.JSONObject().put("v", 2).put("id", id).put("body", body)
        if (expSentMs != null && expSentMs in 1..MAX_TIMER_MS) o.put("ss", expSentMs)
        if (expSeenMs != null && expSeenMs in 1..MAX_TIMER_MS) o.put("sn", expSeenMs)
        return o.toString().toByteArray()
    }

    /**
     * Seal one image chunk. Same id/caption/timers on every chunk of the
     * message, so each chunk is self-describing and reassembly is just
     * concatenation in index order.
     */
    fun sealImageChunk(
        id: String,
        caption: String,
        index: Int,
        total: Int,
        dataB64: String,
        expSentMs: Long?,
        expSeenMs: Long?,
    ): ByteArray {
        val o = org.json.JSONObject()
            .put("v", 3).put("id", id).put("body", caption)
            .put("ch", index).put("n", total).put("data", dataB64)
        if (expSentMs != null && expSentMs in 1..MAX_TIMER_MS) o.put("ss", expSentMs)
        if (expSeenMs != null && expSeenMs in 1..MAX_TIMER_MS) o.put("sn", expSeenMs)
        return o.toString().toByteArray()
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
        // Keys sorted so both sides hash the same order. The previous version
        // hashed local-first, so A saw H(A,B) while B saw H(B,A) — two
        // different "safety" numbers for the same pair, making out-of-band
        // verification fail even with no attacker present.
        val first: ByteArray
        val second: ByteArray
        if (lexCompare(id.identityKey.publicKey, peerIdentityPublic) <= 0) {
            first = id.identityKey.publicKey
            second = peerIdentityPublic
        } else {
            first = peerIdentityPublic
            second = id.identityKey.publicKey
        }
        val material = Primitives.concat(
            "HUSH/safety/v1".toByteArray(),
            first,
            second,
        )
        val digest = Primitives.blake2b(material, 30)
        val digits = StringBuilder()
        digest.forEach { byte -> digits.append(((byte.toInt() and 0xFF) % 100).toString().padStart(2, '0')) }
        return digits.toString().chunked(5).joinToString(" ")
    }

    // -------------------------------------------------------------------------

    /**
     * Associated data for every message.
     *
     * This used to be `"hush|$peerId"` — the recipient's id on send, the
     * sender's id on receive. Those are different strings, so the AEAD tag
     * could never verify and EVERY first message failed with "Could not
     * decrypt", deterministically, on every device, while all unit tests
     * stayed green (they used the same aad on both sides).
     *
     * It is now a constant, which is the simpler correct choice the field
     * report asked for. Cross-conversation replay is already impossible
     * because each pair derives a distinct root key — a frame sealed for B
     * cannot open under C's session no matter what the aad says. Binding the
     * peer id as well would be defence in depth, but a directional binding
     * that breaks all traffic is worse than no binding. If per-conversation
     * binding ever returns, it must be derived from BOTH ids in a
     * direction-independent way (sorted pair), never from one side alone.
     */
    private fun aadFor(peerId: String): ByteArray = AAD_V1

    /** Lexicographic unsigned-byte comparison. */
    private fun lexCompare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Forget a live session, e.g. when a safety number mismatch is detected. */
    fun resetSession(peerId: String) {
        locks.remove(peerId)?.let { runCatching { it.unlock() } }
        sessions.remove(peerId)?.wipe()
    }

    /**
     * Scrub everything: every session's key material, the identity, the local
     * peer id. Used by the duress wipe — key material must not survive in the
     * process after the database is gone.
     */
    fun clearAll() {
        sessions.values.forEach { runCatching { it.wipe() } }
        sessions.clear()
        locks.clear()
        identity = null
        localPeerId = null
        _identityReady.value = false
    }

    companion object {
        private const val TAG = "HushSessions"
        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_SIGNING_PRIVATE = "signing_private"

        private const val PREFS_PREKEYS = "hush_prekeys"
        private const val KEY_LOCAL_PREKEY = "local_prekey_private"
        private const val KEY_LOCAL_SIGNED = "local_signed_prekey_private"
        private const val KEY_LOCAL_COUNTER = "local_prekey_counter"

        /** Largest timer the payload will carry: one year. See [interpret]. */
        const val MAX_TIMER_MS = 365L * 24L * 60L * 60L * 1000L

        /**
         * Image chunk bounds. A chunk's base64 must decode to at most 36 KiB,
         * so the sealed frame stays comfortably under the relay's 64 KiB
         * envelope cap after JSON + ratchet overhead + envelope base64.
         * At most 80 chunks ≈ 2.8 MB per image; anything bigger is refused
         * before a single frame is sent.
         */
        const val MAX_CHUNK_RAW = 36 * 1024
        const val MAX_CHUNK_B64 = 64 * 1024
        const val MAX_IMAGE_CHUNKS = 80

        @JvmStatic
        val AAD_V1: ByteArray = "HUSH/msg/v1".toByteArray()
    }
}
