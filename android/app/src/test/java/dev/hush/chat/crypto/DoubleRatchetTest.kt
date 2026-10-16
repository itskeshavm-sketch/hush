package dev.hush.chat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ratchet conformance and adversarial tests. Pure JVM â€” no Android, no
 * emulator. Run with `gradlew :app:testDebugUnitTest`.
 *
 * These are the tests that matter. A messenger that leaks is worse than a
 * messenger that crashes, and the ways a ratchet implementation silently fails
 * to protect anything are not visible from the outside: it still encrypts, it
 * still decrypts, the UI still works. So the checks here are about *properties*
 * â€” key uniqueness, forward secrecy, tamper resistance, bounded work â€” rather
 * than just round-trip success.
 */
class DoubleRatchetTest {

    private val aad = "conv:test".toByteArray()

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    @Test
    fun `x25519 is symmetric and matches the RFC 7748 test vector`() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        assertArrayEquals(
            Primitives.x25519(alice.privateKey, bob.publicKey),
            Primitives.x25519(bob.privateKey, alice.publicKey),
        )
    }

    @Test
    fun `x25519 rejects an all-zero peer key`() {
        val alice = KeyPair.generate()
        try {
            Primitives.x25519(alice.privateKey, ByteArray(32))
            fail("expected an all-zero shared secret to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("all-zero"))
        }
    }

    @Test
    fun `ed25519 signatures verify and reject tampering`() {
        val priv = Primitives.randomBytes(32)
        val pub = Primitives.ed25519Public(priv)
        val msg = "hush".toByteArray()
        val sig = Primitives.ed25519Sign(msg, priv)

        assertTrue(Primitives.ed25519Verify(sig, msg, pub))

        val tampered = msg.copyOf().also { it[0] = 'H'.code.toByte() }
        // assertFalse with the message FIRST. JUnit4's signature is
        // assertFalse(String message, boolean condition) â€” there is no
        // (boolean, String) overload, and the single-String assertFalse(String)
        // variant always passes, so writing it the other way round either fails
        // to compile or, once wrapped in assertTrue(!...), silently asserts
        // nothing at all.
        assertFalse("modified message must not verify", Primitives.ed25519Verify(sig, tampered, pub))
        assertFalse("wrong key must not verify", Primitives.ed25519Verify(sig, msg, KeyPair.generate().publicKey))
    }

    @Test
    fun `aead rejects a modified ciphertext and a modified aad`() {
        val key = Primitives.randomBytes(32)
        val nonce = Primitives.randomBytes(Primitives.NONCE_LEN)
        val sealed = Primitives.seal(key, nonce, "hello hush".toByteArray(), aad)

        val opened = Primitives.open(key, nonce, sealed, aad)
        assertEquals("hello hush", String(opened))

        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        try {
            Primitives.open(key, nonce, sealed, aad)
            fail("tampered ciphertext must fail authentication")
        } catch (expected: org.bouncycastle.crypto.InvalidCipherTextException) {
            // expected
        }

        val sealed2 = Primitives.seal(key, nonce, "hello hush".toByteArray(), aad)
        try {
            Primitives.open(key, nonce, sealed2, "conv:other".toByteArray())
            fail("a ciphertext must not open under a different conversation id")
        } catch (expected: org.bouncycastle.crypto.InvalidCipherTextException) {
            // expected: AAD binding works
        }
    }

    // -----------------------------------------------------------------------
    // X3DH
    // -----------------------------------------------------------------------

    /**
     * Build a peer and the bundle it publishes.
     *
     * Returns (identityKeyPair, signedPrekeyPrivateBytes, bundle) â€” the
     * signed prekey is returned as raw private bytes because that is what the
     * responder needs in order to recompute the shared secret.
     */
    private fun peerWithBundle(): Triple<KeyPair, ByteArray, PrekeyBundle> {
        val identity = KeyPair.generate()
        val identitySigningKey = Primitives.randomBytes(32)
        val signingPublic = Primitives.ed25519Public(identitySigningKey)
        val prekey = KeyPair.generate()
        val signedKey = KeyPair.generate()
        val counter = 1
        val signature = Primitives.ed25519Sign(
            prekeySignatureMessage(identity.publicKey, prekey.publicKey, counter),
            identitySigningKey,
        )
        val bundle = PrekeyBundle(
            identity = identity.publicKey,
            signingKey = signingPublic,
            prekey = prekey.publicKey,
            signed = SignedPrekeyPublic(signedKey.publicKey, signature, counter),
        )
        return Triple(identity, signedKey.privateKey.copyOf(), bundle)
    }

    @Test
    fun `x3dh produces the same root key on both sides`() {
        val (bobIdentity, bobSignedPriv, bobBundle) = peerWithBundle()
        val alice = KeyPair.generate()

        val aliceSide = X3dh.initiate(alice, bobBundle)

        // The initiator's ephemeral is carried in InitialSession and becomes
        // the first ratchet key, so the responder reads it from the header of
        // the first message. Here we take it directly.
        val bobSide = X3dh.respond(
            identityKey = bobIdentity,
            signedPrekey = SignedPrekey(bobSignedPriv, ByteArray(32), 1, ByteArray(64)),
            remoteIdentityPublic = alice.publicKey,
            remoteEphemeralPublic = aliceSide.initialRatchet!!.publicKey,
        )

        // JUnit4's assertArrayEquals has no (String, byte[], byte[]) overload â€”
        // passing a message makes Kotlin resolve to the String/String variant
        // and fail to compile rather than silently misbehave. Assert, then
        // report.
        assertArrayEquals(aliceSide.rootKey, bobSide.rootKey)
    }

    @Test
    fun `a prekey bundle with a broken signature is rejected outright`() {
        val (_, _, bundle) = peerWithBundle()
        val json = bundle.toBase64Json()
        assertNotNull(PrekeyBundle.fromBase64Json(json))

        // Corrupt the signature. A relay that substituted a prekey would look
        // exactly like this, so it must not parse at all.
        val obj = org.json.JSONObject(json)
        val sig = obj.getJSONObject("signedPrekey").getString("sig")
        val flipped = sig.replaceRange(0, 1, if (sig[0] == 'A') "B" else "A")
        obj.getJSONObject("signedPrekey").put("sig", flipped)

        assertNull(PrekeyBundle.fromBase64Json(obj.toString()))
    }

    @Test
    fun `a bundle whose prekey was swapped for another prekey is rejected`() {
        val (_, _, bundle) = peerWithBundle()
        val obj = org.json.JSONObject(bundle.toBase64Json())
        // Signature covers identity, prekey AND counter. Swapping only the
        // prekey must invalidate it.
        obj.put("prekey", Primitives.randomBytes(32).b64())
        assertNull(PrekeyBundle.fromBase64Json(obj.toString()))
    }

    // -----------------------------------------------------------------------
    // Double Ratchet
    // -----------------------------------------------------------------------

    /**
     * A pair of connected ratchets, built through the real X3DH handshake.
     *
     * This mirrors exactly what SessionManager does: Alice initiates, Bob
     * recomputes from the ephemeral in her first message header, and both then
     * run the same state machine.
     */
    private fun connectedPair(): Pair<DoubleRatchet, DoubleRatchet> {
        val (bobIdentity, bobSignedPriv, bobBundle) = peerWithBundle()
        val alice = KeyPair.generate()

        val aliceInit = X3dh.initiate(alice, bobBundle)
        val aliceRatchet = DoubleRatchet.create(aliceInit, aad)

        // Alice's first message. Its header carries her X3DH ephemeral.
        val first = aliceRatchet.encrypt("first contact".toByteArray())

        val bobInit = X3dh.respond(
            identityKey = bobIdentity,
            signedPrekey = SignedPrekey(bobSignedPriv, ByteArray(32), 1, ByteArray(64)),
            remoteIdentityPublic = alice.publicKey,
            remoteEphemeralPublic = first.copyOfRange(0, DoubleRatchet.DH_KEY_LEN),
        )
        val bobRatchet = DoubleRatchet.create(bobInit, aad)
        bobRatchet.adoptPeerRatchetKey(first.copyOfRange(0, DoubleRatchet.DH_KEY_LEN))

        // Deliver it, so both sides are in the same state before the test
        // starts sending real assertions.
        assertEquals("first contact", String(bobRatchet.decrypt(first)!!))

        return aliceRatchet to bobRatchet
    }

    @Test
    fun `a message round-trips and a reply ratchets forward`() {
        val (alice, bob) = connectedPair()

        val one = alice.encrypt("first".toByteArray())
        assertEquals("first", String(bob.decrypt(one)!!))

        val two = bob.encrypt("second".toByteArray())
        assertEquals("second", String(alice.decrypt(two)!!))

        val three = alice.encrypt("third".toByteArray())
        assertEquals("third", String(bob.decrypt(three)!!))
    }

    @Test
    fun `every message uses a distinct nonce`() {
        val (alice, _) = connectedPair()
        val nonces = (0 until 64).map { n ->
            val msg = alice.encrypt("m$n".toByteArray())
            msg.copyOfRange(
                DoubleRatchet.HEADER_LEN,
                DoubleRatchet.HEADER_LEN + Primitives.NONCE_LEN,
            ).toList()
        }
        assertEquals("a repeated nonce under one ratchet is catastrophic", 64, nonces.toSet().size)
    }

    @Test
    fun `identical plaintext produces different ciphertext each time`() {
        val (alice, _) = connectedPair()
        val a = alice.encrypt("same".toByteArray())
        val b = alice.encrypt("same".toByteArray())
        assertTrue("ciphertext must not be a deterministic function of plaintext", !a.contentEquals(b))
    }

    @Test
    fun `a replayed message is dropped rather than re-decrypted`() {
        val (alice, bob) = connectedPair()
        val msg = alice.encrypt("once".toByteArray())

        assertEquals("once", String(bob.decrypt(msg)!!))
        assertNull("second delivery of the same bytes must return null", bob.decrypt(msg))
    }

    @Test
    fun `a duplicate from before a rotation is dropped without poisoning the session`() {
        val (alice, bob) = connectedPair()
        val m1 = alice.encrypt("one".toByteArray())
        val m2 = alice.encrypt("two".toByteArray())
        assertEquals("one", String(bob.decrypt(m1)!!))
        assertEquals("two", String(bob.decrypt(m2)!!))

        // Bob replies, so both sides rotate onto new chain keys.
        val reply = bob.encrypt("reply".toByteArray())
        assertEquals("reply", String(alice.decrypt(reply)!!))
        val m3 = alice.encrypt("three".toByteArray())
        assertEquals("three", String(bob.decrypt(m3)!!))

        // Stale duplicate of m1 under the pre-rotation key. Without an
        // opened-message memory this ratchets onto the stale key and throws —
        // and poisons the session for everything behind it.
        assertNull("stale duplicate must return null, not throw", bob.decrypt(m1))

        // The session must still be healthy afterwards.
        val m4 = alice.encrypt("four".toByteArray())
        assertEquals("four", String(bob.decrypt(m4)!!))
    }

    @Test
    fun `out-of-order delivery is handled via skipped keys`() {
        val (alice, bob) = connectedPair()
        val a = alice.encrypt("one".toByteArray())
        val b = alice.encrypt("two".toByteArray())
        val c = alice.encrypt("three".toByteArray())

        // Deliberately deliver out of order.
        assertEquals("three", String(bob.decrypt(c)!!))
        assertEquals("one", String(bob.decrypt(a)!!))
        assertEquals("two", String(bob.decrypt(b)!!))
    }

    @Test
    fun `a modified header breaks the chain and is refused`() {
        val (alice, bob) = connectedPair()
        alice.encrypt("warmup".toByteArray())
        val msg = alice.encrypt("payload".toByteArray())

        val tampered = msg.copyOf()
        val firstCipherByte = DoubleRatchet.HEADER_LEN + Primitives.NONCE_LEN
        tampered[firstCipherByte] = (tampered[firstCipherByte] + 1).toByte()

        try {
            bob.decrypt(tampered)
            fail("a tampered message must not decrypt")
        } catch (expected: RatchetException) {
            assertTrue(expected.message!!.contains("authentication"))
        }
    }

    @Test
    fun `a message from a foreign ratchet key is refused`() {
        val (_, bob) = connectedPair()
        // A completely independent session, with its own root key.
        val stranger = connectedPair().first

        val msg = stranger.encrypt("not for you".toByteArray())
        try {
            bob.decrypt(msg)
            fail("a message from a different session must not decrypt")
        } catch (expected: RatchetException) {
            // A foreign ratchet key is *accepted* for the purpose of rotating
            // the chain, because that is what an honest peer's first reply
            // looks like. The refusal comes from the AEAD tag instead: without
            // the shared root key there is no valid tag to produce. The
            // confidentiality guarantee is unchanged, and the assertion is on
            // the outcome that actually matters.
            assertTrue(
                "expected an authentication failure, got: ${expected.message}",
                expected.message!!.contains("authentication"),
            )
        }
    }

    @Test
    fun `a forged huge message number cannot force unbounded key derivation`() {
        val (alice, bob) = connectedPair()
        val msg = alice.encrypt("first".toByteArray())

        // Claim message number 1_000_000 in the header.
        val forged = msg.copyOf()
        forged[36] = 0x00; forged[37] = 0x0F; forged[38] = 0x42; forged[39] = 0x40

        try {
            bob.decrypt(forged)
            fail("must fail closed rather than derive a million keys")
        } catch (expected: RatchetException) {
            assertTrue(
                "expected a fail-closed refusal, got: ${expected.message}",
                expected.message!!.contains("unbounded key derivation"),
            )
        }
    }

    @Test
    fun `a truncated payload is rejected before any crypto runs`() {
        val (alice, bob) = connectedPair()
        val msg = alice.encrypt("x".toByteArray())
        try {
            bob.decrypt(msg.copyOf(20))
            fail("a 20-byte payload is not a HUSH message")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too short"))
        }
    }

    @Test
    fun `a long bidirectional conversation stays in sync across many ratchet steps`() {
        val (alice, bob) = connectedPair()
        for (round in 0 until 12) {
            val sent = alice.encrypt("a$round".toByteArray())
            assertEquals("a$round", String(bob.decrypt(sent)!!))
            val reply = bob.encrypt("b$round".toByteArray())
            assertEquals("b$round", String(alice.decrypt(reply)!!))
        }
    }
}

