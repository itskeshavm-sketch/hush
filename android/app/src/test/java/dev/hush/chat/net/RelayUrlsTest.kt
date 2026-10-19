package dev.hush.chat.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay address rules.
 *
 * The rule this pins is a security boundary, not a convenience: cleartext is
 * refused everywhere except loopback. That distinction has to be tested,
 * because the natural "fix" for a self-hoster who cannot connect is to widen
 * the check — and widening it silently removes the only thing stopping a
 * relay operator from reading routing metadata off the wire.
 */
class RelayUrlsTest {

    @Test
    fun `wss is always accepted`() {
        assertTrue(RelayUrls.isAcceptable("wss://spudpotato.itskeshavm.workers.dev/ws"))
        assertTrue(RelayUrls.isAcceptable("wss://relay.example.com:8443/ws"))
        assertTrue(RelayUrls.isAcceptable("WSS://RELAY.EXAMPLE.COM/ws"))
    }

    @Test
    fun `ws to loopback is accepted so a local relay needs no certificate`() {
        assertTrue(RelayUrls.isAcceptable("ws://127.0.0.1:8787/ws"))
        assertTrue(RelayUrls.isAcceptable("ws://localhost:8787/ws"))
        assertTrue(RelayUrls.isAcceptable("ws://LOCALHOST:8787/ws"))
        // The Android emulator's alias for the host machine.
        assertTrue(RelayUrls.isAcceptable("ws://10.0.2.2:8787/ws"))
    }

    @Test
    fun `ws to anything that is not loopback is refused`() {
        // A LAN peer, a public host, a lookalike domain, and a host that merely
        // *starts* with "127.0.0.1" — all of which a naive prefix check accepts.
        assertFalse(RelayUrls.isAcceptable("ws://192.168.1.5:8787/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://example.com/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://127.0.0.1.evil.example/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://localhost.evil.example/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://notlocalhost/ws"))
    }

    @Test
    fun `plain http is refused in every form`() {
        assertFalse(RelayUrls.isAcceptable("http://127.0.0.1:8787/ws"))
        assertFalse(RelayUrls.isAcceptable("http://example.com"))
        assertFalse(RelayUrls.isAcceptable("HTTPS://example.com"))
    }

    @Test
    fun `nonsense is refused rather than passed to the socket layer`() {
        assertFalse(RelayUrls.isAcceptable(""))
        assertFalse(RelayUrls.isAcceptable("   "))
        assertFalse(RelayUrls.isAcceptable("example.com/ws"))
        assertFalse(RelayUrls.isAcceptable("wss://"))
        assertFalse(RelayUrls.isAcceptable("wss:///ws"))
    }

    @Test
    fun `a rejection explains which mistake was made`() {
        // The two failure modes look identical to a user unless we say which.
        val cleartext = RelayUrls.rejectionReason("ws://192.168.1.5:8787/ws")
        assertTrue("cleartext rejection should mention localhost", cleartext.contains("localhost"))
        val garbage = RelayUrls.rejectionReason("not a url")
        assertTrue("generic rejection should name the expected form", garbage.contains("wss://"))
    }

    @Test
    fun `ipv6 loopback is refused because the platform would refuse it too`() {
        // Kept as an explicit assertion rather than left untested: this is the
        // case where "client says yes, Android says no" would surface as an
        // unexplained socket error. See LOOPBACK_HOSTS.
        assertFalse(RelayUrls.isAcceptable("ws://[::1]:8787/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://[2001:db8::1]:8787/ws"))
    }

    @Test
    fun `userinfo cannot smuggle a remote host past the loopback check`() {
        // ws://127.0.0.1@evil.example/ws routes to evil.example.
        assertFalse(RelayUrls.isAcceptable("ws://127.0.0.1@evil.example/ws"))
        assertFalse(RelayUrls.isAcceptable("ws://localhost@evil.example/ws"))
    }

    @Test
    fun `a query or fragment after the path does not affect host parsing`() {
        assertTrue(RelayUrls.isAcceptable("ws://127.0.0.1:8787/ws?x=1"))
        assertTrue(RelayUrls.isAcceptable("ws://127.0.0.1:8787/ws#frag"))
        assertFalse(RelayUrls.isAcceptable("ws://evil.example/ws?x=127.0.0.1"))
    }
}