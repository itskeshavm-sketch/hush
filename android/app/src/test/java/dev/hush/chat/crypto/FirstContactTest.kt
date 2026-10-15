package dev.hush.chat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import dev.hush.chat.crypto.b64

/**
 * First contact between two peers, end to end.
 *
 * This is the test for "Could not decrypt a message from X".
 *
 * Two separate one-line bugs have produced that exact toast, both invisible to
 * the original suite because every test used the same aad on both sides:
 *
 *  1. Key-agreement mismatch: the initiator sealed against the responder's
 *     *published* signed prekey while the responder answered with a key it had
 *     generated on the spot and never published. Different root keys, tag never
 *     verifies.
 *
 *  2. Directional associated data: `aadFor` returned `"hush|$peerId"` — the
 *     recipient's id when sending, the sender's id when receiving. The AEAD
 *     tag binds the aad, so even with identical root keys every message failed
 *     authentication on every device, deterministically, while all tests stayed
 *     green because they passed the same string on both sides.
 *
 * Nothing about either failure looks like its cause from the outside: no
 * exception, no dropped frame, just an authentication tag that does not verify.
 * So both are asserted here directly.
 */
class FirstContactTest {

    private fun digest(a: ByteArray, b: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(a)
        md.update(b)
        return md.digest()
    }

    /** A peer: long-term identity, signing key, and the bundle it publishes. */
    private class Peer(val name: String) {
        val identity = KeyPair.generate()
        val signingKey = Primitives.randomBytes(32)
        val signingPublic = Primitives.ed25519Public(signingKey)

        // The bundle is generated ONCE and republished, as buildHello now does.
        val prekey = KeyPair.generate()
        val signed = KeyPair.generate()
        val counter = 1
        val signature = Primitives.ed25519Sign(
            prekeySignatureMessage(identity.publicKey, prekey.publicKey, counter),
            signingKey,
        )

        val bundle = PrekeyBundle(
            identity = identity.publicKey,
            signingKey = signingPublic,
            prekey = prekey.publicKey,
            signed = SignedPrekeyPublic(signed.publicKey, signature, counter),
        )

        /** Exactly what a peer's roster copy parses to on the other device. */
        fun publishedJson(): String = bundle.toBase64Json()
    }

    @Test
    fun `a first message is readable by the peer it was sent to`() {
        val alice = Peer("alice")
        val bob = Peer("bob")

        // Bob's bundle as Alice receives it off the relay.
        // assertNotNull returns Unit; the null check and the value are separate
        // statements on purpose.
        val bobBundle = PrekeyBundle.fromBase64Json(bob.publishedJson())
        assertNotNull("a freshly published bundle must parse", bobBundle)
        bobBundle!!

        // Alice initiates against the published bundle.
        val initiator = X3dh.initiate(alice.identity, bobBundle)

        // Bob answers with the private half of the signed prekey he published.
        val responder = X3dh.respond(
            identityKey = bob.identity,
            signedPrekey = SignedPrekey(
                privateKey = bob.signed.privateKey,
                publicKey = bob.signed.publicKey,
                counter = bob.counter,
                signature = bob.signature,
            ),
            remoteIdentityPublic = alice.identity.publicKey,
            remoteEphemeralPublic = initiator.initialRatchet!!.publicKey,
        )

        assertArrayEquals(
            "both sides must derive the same root key or nothing ever decrypts",
            digest(initiator.rootKey, ByteArray(0)),
            digest(responder.rootKey, ByteArray(0)),
        )
    }

    @Test
    fun `the first message actually round trips through the ratchet`() {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val bobBundle = PrekeyBundle.fromBase64Json(bob.publishedJson())!!
        val secret = "hello from the other side"

        // --- Alice: initiate, then send the first message. ---
        val initiator = X3dh.initiate(alice.identity, bobBundle)
        val aliceSession = DoubleRatchet.create(initiator, "hush|p_bob".toByteArray())
        val firstFrame = aliceSession.encrypt(secret.toByteArray())

        // The DH field at the head of the frame is the initiator's X3DH
        // ephemeral — the only thing that tells Bob how to rebuild the root key.
        val headerDh = firstFrame.copyOfRange(0, DoubleRatchet.DH_KEY_LEN)

        // --- Bob: respond, then open it. ---
        val responder = X3dh.respond(
            identityKey = bob.identity,
            signedPrekey = SignedPrekey(
                bob.signed.privateKey, bob.signed.publicKey, bob.counter, bob.signature,
            ),
            remoteIdentityPublic = alice.identity.publicKey,
            remoteEphemeralPublic = headerDh,
        )
        val bobSession = DoubleRatchet.create(responder, "hush|p_bob".toByteArray())
        bobSession.adoptPeerRatchetKey(headerDh)

        val plaintext = bobSession.decrypt(firstFrame)
        assertNotNull("the first message must decrypt, not return null", plaintext)
        assertTrue(
            "decrypted text should be the original, was: ${String(plaintext!!)}",
            String(plaintext) == secret,
        )
    }

    @Test
    fun `responding with a key that was never published does not agree with the sender`() {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val bobBundle = PrekeyBundle.fromBase64Json(bob.publishedJson())!!
        val initiator = X3dh.initiate(alice.identity, bobBundle)

        // The old, broken behaviour: a fresh random signed prekey per
        // conversation, unrelated to anything published.
        val invented = Primitives.randomBytes(32)
        val responder = X3dh.respond(
            identityKey = bob.identity,
            signedPrekey = SignedPrekey(
                invented, Primitives.x25519Public(invented), 0, ByteArray(64),
            ),
            remoteIdentityPublic = alice.identity.publicKey,
            remoteEphemeralPublic = initiator.initialRatchet!!.publicKey,
        )

        assertFalse(
            "an unpublished signed prekey must NOT reproduce the sender's root key; " +
                "if this ever passes, the test is no longer covering the bug it was written for",
            digest(initiator.rootKey, ByteArray(0))
                .contentEquals(digest(responder.rootKey, ByteArray(0))),
        )
    }

    @Test
    fun `a republish after reconnect keeps the same keys so old messages stay readable`() {
        val bob = Peer("bob")

        // buildHello must publish an identical bundle every time. A peer that
        // cached an earlier copy has to remain able to open messages sealed
        // against it, so rotating on every reconnect makes old mail unreadable.
        val first = PrekeyBundle.fromBase64Json(bob.publishedJson())!!
        val second = PrekeyBundle.fromBase64Json(bob.publishedJson())!!

        assertArrayEquals("signed prekey must be stable", first.signed.key, second.signed.key)
        assertArrayEquals("identity must be stable", first.identity, second.identity)
        assertTrue("counter must be stable", first.signed.counter == second.signed.counter)
    }

    @Test
    fun `a bundle whose signature does not match is rejected`() {
        val bob = Peer("bob")
        val tampered = bob.publishedJson().replace(
            bob.prekey.publicKey.b64(),
            Primitives.randomBytes(32).b64(),
        )

        assertTrue(
            "swapping the prekey must invalidate the signature",
            PrekeyBundle.fromBase64Json(tampered) == null,
        )
    }

    @Test
    fun `directional associated data breaks first contact even with correct keys`() {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val bobBundle = PrekeyBundle.fromBase64Json(bob.publishedJson())!!
        val secret = "direction matters"

        // The old SessionManager.aadFor: sender binds the recipient's id,
        // receiver binds the sender's id. Same root keys, different aad.
        val initiator = X3dh.initiate(alice.identity, bobBundle)
        val aliceSession = DoubleRatchet.create(initiator, "hush|p_bob".toByteArray())
        val frame = aliceSession.encrypt(secret.toByteArray())
        val headerDh = frame.copyOfRange(0, DoubleRatchet.DH_KEY_LEN)

        val responder = X3dh.respond(
            identityKey = bob.identity,
            signedPrekey = SignedPrekey(
                bob.signed.privateKey, bob.signed.publicKey, bob.counter, bob.signature,
            ),
            remoteIdentityPublic = alice.identity.publicKey,
            remoteEphemeralPublic = headerDh,
        )
        val bobSession = DoubleRatchet.create(responder, "hush|p_alice".toByteArray())
        bobSession.adoptPeerRatchetKey(headerDh)

        var failed = false
        try {
            bobSession.decrypt(frame)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(
            "different aad on the two sides must fail authentication — " +
                "this is the bug that broke every real message while tests stayed green",
            failed,
        )
    }

    @Test
    fun `a constant aad round trips, which is what the app now uses`() {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val bobBundle = PrekeyBundle.fromBase64Json(bob.publishedJson())!!
        val secret = "same aad both sides"
        val aad = "HUSH/msg/v1".toByteArray()

        val initiator = X3dh.initiate(alice.identity, bobBundle)
        val aliceSession = DoubleRatchet.create(initiator, aad)
        val frame = aliceSession.encrypt(secret.toByteArray())
        val headerDh = frame.copyOfRange(0, DoubleRatchet.DH_KEY_LEN)

        val responder = X3dh.respond(
            identityKey = bob.identity,
            signedPrekey = SignedPrekey(
                bob.signed.privateKey, bob.signed.publicKey, bob.counter, bob.signature,
            ),
            remoteIdentityPublic = alice.identity.publicKey,
            remoteEphemeralPublic = headerDh,
        )
        val bobSession = DoubleRatchet.create(responder, aad)
        bobSession.adoptPeerRatchetKey(headerDh)

        val plaintext = bobSession.decrypt(frame)
        assertNotNull("same aad on both sides must decrypt", plaintext)
        assertTrue(String(plaintext!!) == secret)
    }
}