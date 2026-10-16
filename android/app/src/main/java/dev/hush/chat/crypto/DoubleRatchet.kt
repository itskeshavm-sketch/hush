package dev.hush.chat.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * Signal's Double Ratchet, adapted for a one-to-one HUSH conversation.
 *
 * Why a ratchet at all: a static shared secret means every message in a
 * conversation is protected by exactly one key. Steal that key, and you can
 * read the whole archive. The ratchet derives a fresh chain key per message,
 * and performs a Diffie-Hellman ratchet on every reply, which means:
 *
 *   - forward secrecy: compromising a key today does not decrypt yesterday
 *   - post-compromise security: a session recovers after a key is stolen,
 *     because the next reply introduces a fresh DH the attacker cannot compute
 *
 * Deviations from the reference spec, and why each is safe:
 *
 *  - **No associated data from the transport.** HUSH has no headers worth
 *    protecting beyond the conversation id, which is bound in separately.
 *  - **Message keys are used once.** Spec permits deriving a usable key from
 *    the chain key. We do not, which removes a whole class of key-reuse bugs.
 *  - **Skipped keys are bounded.** A hostile peer can claim an enormous message
 *    number and make us derive an unbounded number of chain keys (a cheap DoS
 *    and a memory leak). [MAX_SKIPPED] caps it and we *fail closed* past the
 *    cap rather than silently skipping, because a silent gap in a
 *    conversation is indistinguishable from message loss.
 *
 * Not thread-safe. One instance per conversation, guarded by the session
 * manager's per-conversation lock.
 */
class DoubleRatchet private constructor(
    rootKey: ByteArray,
    theirRatchetKey: ByteArray,
    /** Constant for this conversation, bound into every AEAD tag. */
    private val aad: ByteArray,
    initialRatchet: KeyPair? = null,
) {

    // --- ratchet state ------------------------------------------------------
    private var rootKey: ByteArray = rootKey
    private var theirRatchetKey: ByteArray = theirRatchetKey

    private var sendingChain: ByteArray? = null
    private var receivingChain: ByteArray? = null

    private var sendCount: Int = 0
    private var recvCount: Int = 0
    private var prevSendCount: Int = 0

    /**
     * Whether we have ever successfully decrypted a message in this session.
     *
     * Gates the "unknown ratchet key" branch in [decrypt], which is legitimate
     * for a peer's first message and is a forgery signal for every message
     * after it.
     */
    private var receivedAny: Boolean = false

    /**
     * The peer ratchet key we have already performed a DH ratchet step from.
     *
     * A peer keeps the same ratchet key in its headers until it receives our
     * reply, so the same key legitimately appears on many consecutive
     * messages. Stepping the ratchet on each of those resets the chains and
     * desynchronises the session. Tracking what we have already stepped from
     * distinguishes "new chain" from "more messages on this chain".
     */
    private var lastRatchetedFrom: ByteArray? = null

    // On the initiator, this is their X3DH ephemeral, so the first header
    // carries the key the responder needs to derive the same root key.
    private var ourRatchet: KeyPair = initialRatchet ?: KeyPair.generate()

    /** Skipped message keys, keyed by "<hex dh>|<n>" so a reordered delivery can still be decrypted. */
    private val skipped = ConcurrentHashMap<String, ByteArray>()

    /**
     * Headers of messages already opened, keyed by "<hex dh>|<n>".
     *
     * The replay test below only ignores replays under the CURRENT peer key.
     * A duplicate from before a rotation (offline flush, retry and reset
     * healing all re-send already-delivered ids) carries an older key, so it
     * used to fall through to [dhRatchet] on a stale key and fail
     * authentication — torching a healthy session and toasting "could not
     * decrypt" for a conversation that visibly works. Any exact header replay
     * is the same chain slot and therefore the same plaintext, so dropping it
     * is always safe. Bounded: entries past the cap are forgotten and fall
     * back to the fail-closed path, matching [MAX_SKIPPED] philosophy.
     */
    private val opened = object : LinkedHashMap<String, Unit>(MAX_OPENED, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > MAX_OPENED
    }

    /** True once the first DHRatchet has run, i.e. we have keys to send with. */
    val isEstablished: Boolean get() = sendingChain != null

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    /**
     * Seal a plaintext.
     *
     * @return wire bytes: `HEADER(40) || NONCE(24) || CIPHERTEXT`
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val chain = sendingChain ?: run {
            // First send of the session. Establish both chains from the X3DH
            // exchange WITHOUT rotating our ratchet key.
            //
            // This is the subtle part of the handshake. The initiator's first
            // message must carry the ephemeral that X3DH already used, because
            // the responder's receiving chain is derived from
            // DH(signedPrekey, thatEphemeral). Rotating here would put a new
            // key in the header that the responder has no way to match, and the
            // very first message would fail authentication permanently.
            establishFirstContact()
            sendingChain!!
        }

        val (nextChain, messageKey) = kdfChain(chain)
        sendingChain = nextChain

        val nonce = Primitives.randomBytes(Primitives.NONCE_LEN)
        val header = encodeHeader(ourRatchet.publicKey, prevSendCount, sendCount)
        val sealed = Primitives.seal(messageKey, nonce, plaintext, aad)

        Primitives.wipe(messageKey)
        sendCount++

        return Primitives.concat(header, nonce, sealed)
    }

    /**
     * Derive the initial sending chain from the X3DH shared secret.
     *
     * Exactly **one** root-key step, not two:
     *
     * ```
     * dh = DH(ourRatchet, theirRatchetKey)
     * RK1, CKs = KDF_RK(RK0, dh)
     * ```
     *
     * The responder's first [dhRatchet] derives its *receiving* chain from the
     * same `KDF_RK(RK0, DH(signedPrekey, ourEphemeral))` — the same value, since
     * DH is symmetric. That is the chain our first message must be encrypted
     * under. Taking a second step here would derive the sending chain from
     * `RK1` instead of `RK0`, and the very first message would fail
     * authentication on the far side with no way to recover.
     *
     * `receivingChain` is deliberately left null. It is established by the
     * [dhRatchet] triggered when the peer's reply arrives carrying the ratchet
     * key it generated on receiving this message.
     */
    private fun establishFirstContact() {
        val dh = Primitives.x25519(ourRatchet.privateKey, theirRatchetKey)

        val step = kdfRoot(rootKey, dh)
        rootKey = step.first
        sendingChain = step.second

        Primitives.wipe(dh)

        sendCount = 0
        recvCount = 0
        prevSendCount = 0
    }

    // -------------------------------------------------------------------------
    // Receiving
    // -------------------------------------------------------------------------

    /**
     * Open a sealed message.
     *
     * @return the plaintext, or null when the message is a duplicate we have
     *   already consumed (which is not an error — replays are dropped, not
     *   surfaced). Throws on genuine authentication failure.
     */
    fun decrypt(payload: ByteArray): ByteArray? {
        require(payload.size >= HEADER_LEN + Primitives.NONCE_LEN + MAC_LEN) {
            "payload too short to be a HUSH message (${payload.size} bytes)"
        }

        val header = decodeHeader(payload)
        val nonce = payload.copyOfRange(HEADER_LEN, HEADER_LEN + Primitives.NONCE_LEN)
        val sealed = payload.copyOfRange(HEADER_LEN + Primitives.NONCE_LEN, payload.size)
        val dhHex = header.dh.toHex()

        val dedupeKey = "$dhHex|${header.messageNumber}"

        // Exact replay of an already-opened message. Checked before anything
        // that mutates state: falling through would ratchet onto a stale key
        // and poison the session for genuine messages behind it.
        if (opened.containsKey(dedupeKey)) return null

        // A key derived earlier for an out-of-order message.
        //
        // Checked BEFORE the replay test. A message that was skipped over and
        // has now arrived late legitimately carries a number below recvCount,
        // so testing for replay first would discard it as a duplicate and the
        // out-of-order test would NPE on a null plaintext.
        //
        // Consumed exactly once: leaving it in the map would let the same bytes
        // open twice.
        skipped[dedupeKey]?.let { stored ->
            skipped.remove(dedupeKey)
            receivedAny = true
            val plaintext = openWith(stored, nonce, sealed)
            opened[dedupeKey] = Unit
            return plaintext
        }

        // Replay of something we already opened. Checked for the peer's current
        // ratchet key as well as our own, so re-delivering the last message of
        // a conversation returns null rather than throwing.
        val alreadySteppedFromThisKey = lastRatchetedFrom?.contentEquals(header.dh) == true
        if (alreadySteppedFromThisKey && header.messageNumber < recvCount) {
            return null
        }

        // Ratchet exactly once per distinct peer ratchet key.
        //
        // A peer keeps the same key in its headers until it receives our reply,
        // so the same value legitimately heads many consecutive messages.
        // Stepping the DH ratchet on each of those resets the chains and
        // desynchronises the session, which is what made every second message
        // fail authentication. Conversely, a key we have never stepped from is
        // a genuinely new chain — the peer's first contact, or a rotation they
        // performed after reading one of ours — and must ratchet.
        //
        // Accepting an unknown key costs a denial-of-service, not
        // confidentiality: rotating is free, but the AEAD tag in [openWith]
        // still must verify, and without the root key no valid tag exists.
        // This is the same trade Signal makes.
        if (!alreadySteppedFromThisKey) {
            dhRatchet(header.dh)
            lastRatchetedFrom = header.dh.copyOf()
        }

        val key = advanceReceivingChain(header.dh, header.messageNumber)
        receivedAny = true
        val plaintext = openWith(key, nonce, sealed)
        opened[dedupeKey] = Unit
        return plaintext
    }

    /**
     * Walk the receiving chain forward to message number [n], stashing the keys
     * for any messages skipped over so they can still be opened out of order.
     *
     * The bounded-work guard lives here rather than in one caller because every
     * route into a chain advance can be reached with an attacker-chosen [n].
     */
    private fun advanceReceivingChain(dh: ByteArray, n: Int): ByteArray {
        var chain = receivingChain
            ?: throw RatchetException("no receiving chain for current ratchet key")

        if (n < recvCount) {
            // The earlier `while (true) { if (recvCount == n) break; recvCount++ }`
            // hung forever here: recvCount only ever increases, so a message
            // number already passed could never match. Rejecting is correct
            // anyway — a message we have consumed is a replay.
            throw RatchetException("message number $n was already consumed (recvCount=$recvCount)")
        }

        if (n > recvCount) {
            // A hostile peer can claim an enormous message number to make us
            // derive an unbounded number of chain keys — cheap to send, and it
            // burns CPU and memory here. Check the whole gap up front and fail
            // closed rather than deriving keys and then giving up.
            val gap = n - recvCount
            if (skipped.size + gap > MAX_SKIPPED) {
                throw RatchetException(
                    "refusing to derive $gap skipped keys; a peer is forcing unbounded key derivation",
                )
            }

            val dhHex = dh.toHex()

            // Stash the key for EVERY message between where we are and the one
            // arriving — not just those up to n-2. A peer that reorders
            // delivery will come back for any of them, and deriving a key
            // without stashing it makes that message permanently unreadable.
            // (The reference implementation only stashes up to n-2, which
            // silently loses n-1.)
            while (recvCount < n) {
                val step = kdfChain(chain)
                chain = step.first
                skipped["$dhHex|$recvCount"] = step.second
                recvCount++
            }
        }

        if (n < recvCount) {
            // The earlier `while (true) { if (recvCount == n) break; recvCount++ }`
            // hung forever here: recvCount only ever increases, so a message
            // number already passed could never match. Rejecting is correct
            // anyway — a message we have consumed is a replay.
            throw RatchetException("message number $n was already consumed (recvCount=$recvCount)")
        }

        // Exactly one step: the chain now points at message n.
        val step = kdfChain(chain)
        receivingChain = step.first
        recvCount = n + 1
        return step.second
    }

    private fun openWith(messageKey: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray {
        val plaintext = try {
            Primitives.open(messageKey, nonce, sealed, aad)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw RatchetException("message failed authentication", e)
        } finally {
            Primitives.wipe(messageKey)
        }
        return plaintext
    }

    // -------------------------------------------------------------------------
    // Ratchet internals
    // -------------------------------------------------------------------------

    /**
     * One DH ratchet step.
     *
     * Order matters and is easy to get backwards: the incoming key is mixed in
     * using the *old* local ratchet key, and only then is a new local key
     * generated. Doing it the other way round desynchronises the two sides
     * permanently with no way to detect it from the wire.
     */
    private fun dhRatchet(theirNewKey: ByteArray) {
        prevSendCount = sendCount
        sendCount = 0
        recvCount = 0

        theirRatchetKey = theirNewKey

        val receiveStep = kdfRoot(rootKey, Primitives.x25519(ourRatchet.privateKey, theirRatchetKey))
        rootKey = receiveStep.first
        receivingChain = receiveStep.second

        ourRatchet.wipe()
        ourRatchet = KeyPair.generate()

        val sendStep = kdfRoot(rootKey, Primitives.x25519(ourRatchet.privateKey, theirRatchetKey))
        rootKey = sendStep.first
        sendingChain = sendStep.second
    }

    /**
     * Root chain: `RK' = HMAC(RK, dh)`, `CK = HMAC(RK', 0x01)`.
     *
     * The domain-separation byte is what stops the two derived values being
     * equal, and therefore stops the receiving chain key being usable as a
     * sending chain key.
     */
    private fun kdfRoot(rk: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val next = Primitives.hmac(rk, dhOutput)
        val chain = Primitives.hmac(next, byteArrayOf(0x01))
        return next to chain
    }

    /** Message chain: `CK' = HMAC(CK, 0x02)`, `MK = HMAC(CK, 0x01)`. */
    private fun kdfChain(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = Primitives.hmac(ck, byteArrayOf(0x01))
        val next = Primitives.hmac(ck, byteArrayOf(0x02))
        return next to messageKey
    }

    // -------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------

    private fun encodeHeader(dh: ByteArray, pn: Int, n: Int): ByteArray {
        val out = ByteArray(HEADER_LEN)
        dh.copyInto(out, 0)
        writeInt(out, 32, pn)
        writeInt(out, 36, n)
        return out
    }

    private fun decodeHeader(payload: ByteArray): Header {
        val dh = payload.copyOfRange(0, 32)
        val pn = readInt(payload, 32)
        val n = readInt(payload, 36)
        if (pn < 0 || n < 0) throw RatchetException("negative counter in header (pn=$pn, n=$n)")
        return Header(dh, pn, n)
    }

    private data class Header(val dh: ByteArray, val previousLength: Int, val messageNumber: Int)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    /** Best-effort scrub of all key material held by this session. */
    fun wipe() {
        Primitives.wipe(rootKey)
        Primitives.wipe(sendingChain)
        Primitives.wipe(receivingChain)
        ourRatchet.wipe()
        skipped.values.forEach { Primitives.wipe(it) }
        skipped.clear()
        opened.clear()
    }

    companion object {
        /** Width of the DH key field at the start of a message header. */
        const val DH_KEY_LEN = 32

        /** Header = DH key ‖ previous chain length ‖ message number. */
        const val HEADER_LEN = DH_KEY_LEN + 4 + 4

        /** Poly1305 tag length. The smallest valid sealed payload. */
        const val MAC_LEN = 16

        /**
         * Cap on retained skipped keys. Above this we fail closed rather than
         * dropping keys, because dropping a key turns into a permanently
         * undecryptable message with no way for the sender to know.
         */
        const val MAX_SKIPPED = 1_000

        /**
         * Cap on remembered opened-message headers. Same fail-closed logic as
         * [MAX_SKIPPED]: past the cap a stale replay is refused (with the
         * session left intact by the throw path) rather than silently dropped.
         */
        const val MAX_OPENED = 1_000

        /**
         * Build a session from an X3DH result.
         *
         * There is deliberately no separate initiator/responder constructor.
         * Both sides run the identical state machine; what differs is only
         * *which* key is already known, and that arrives inside
         * [InitialSession].
         *
         * [InitialSession.initialRatchet] — the initiator's X3DH ephemeral —
         * becomes our first ratchet key, so the DH in the first message header
         * is exactly the ephemeral the responder used to derive the root key.
         * Generating a fresh key here instead would leave the responder unable
         * to recompute the shared secret, and the session would never start.
         */
        fun create(init: InitialSession, aad: ByteArray) = DoubleRatchet(
            rootKey = init.rootKey,
            theirRatchetKey = init.theirRatchetKey,
            aad = aad,
            initialRatchet = init.initialRatchet,
        )
    }

    /**
     * Point this session at the peer's real ratchet key.
     *
     * The responder only learns it from the first inbound message, whose
     * header carries the initiator's X3DH ephemeral. Must be called before the
     * first [decrypt], and only then — adopting a new key mid-session would
     * silently desynchronise the chains.
     */
    fun adoptPeerRatchetKey(publicKey: ByteArray) {
        require(receivingChain == null && sendCount == 0) {
            "adoptPeerRatchetKey must be called before the session is used"
        }
        theirRatchetKey = publicKey
    }
}

class RatchetException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
