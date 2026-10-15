/**
 * Relay behaviour tests.
 *
 * These run against a real WebSocket server over a real TCP loopback. No mocks:
 * the thing under test is a network protocol, and mocking the network out from
 * under a network protocol tests nothing.
 *
 *   node --test test/
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';
import { WebSocket } from 'ws';

import { Hub } from '../src/hub.js';
import { parseInbound, isBase64, sanitizeName, MAX_ENVELOPE_BYTES } from '../src/protocol.js';

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** Spin up a real relay on an ephemeral port. Returns { url, hub, close }. */
async function startRelay() {
  const hub = new Hub();
  const http = createServer((_req, res) => res.writeHead(404).end());
  const wss = new WebSocketServer({ server: http, path: '/ws', perMessageDeflate: false });

  wss.on('connection', (socket) => {
    hub.attach(socket);
    openSockets.add(socket);
    socket.once('close', () => openSockets.delete(socket));
  });

  await new Promise((r) => http.listen(0, '127.0.0.1', r));
  const { port } = http.address();

  return {
    hub,
    url: `ws://127.0.0.1:${port}/ws`,
    async close() {
      hub.shutdown();
      // wss.close() only completes once every client is gone, and test peers do
      // not close themselves. Terminate first or teardown hangs forever.
      for (const s of openSockets) s.terminate();
      openSockets.clear();
      await new Promise((r) => wss.close(r));
      // http.close() waits for idle keep-alive sockets to drain on their own,
      // which never happens under a test harness. Force them.
      http.closeAllConnections?.();
      await new Promise((r) => http.close(r));
    },
  };
}

/** Sockets belonging to the currently-running relay, for hard teardown. */
const openSockets = new Set();

/** Client-side sockets, so the process can always exit even on a failed test. */
const clientSockets = new Set();

test.afterEach(() => {
  // Terminating is not cleanup enough on its own: a client socket left open
  // keeps the Node event loop alive and the runner never exits. Destroying it
  // releases the handle immediately.
  for (const ws of clientSockets) ws.terminate();
  clientSockets.clear();
});

/** A connected peer with an inbox you can await. */
class TestPeer {
  constructor(url, name, identityPub) {
    this.name = name;
    this.identityPub = identityPub;
    this.inbox = [];
    this.waiters = [];
    this.ws = new WebSocket(url);
    clientSockets.add(this.ws);
    this.ws.on('close', () => clientSockets.delete(this.ws));
    this.ready = new Promise((resolve, reject) => {
      this.ws.once('open', resolve);
      this.ws.once('error', reject);
    });
    this.ws.on('message', (data) => {
      const frame = JSON.parse(data.toString());
      if (frame.t === 'welcome') {
        this.id = frame.you.id;
        this.peers = frame.peers;
      }
      const idx = this.waiters.findIndex((w) => w.match(frame));
      if (idx >= 0) {
        const [w] = this.waiters.splice(idx, 1);
        clearTimeout(w.timer);
        w.resolve(frame);
      } else {
        this.inbox.push(frame);
      }
    });
  }

  send(obj) {
    this.ws.send(JSON.stringify(obj));
  }

  /** Wait for the first frame matching `pred`. Drains the inbox first. */
  next(pred = () => true, timeoutMs = 3000) {
    const idx = this.inbox.findIndex(pred);
    if (idx >= 0) return Promise.resolve(this.inbox.splice(idx, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { match: pred, resolve, reject };
      w.timer = setTimeout(() => {
        this.waiters = this.waiters.filter((x) => x !== w);
        reject(new Error(`${this.name}: timed out waiting for frame`));
      }, timeoutMs);
      this.waiters.push(w);
    });
  }

  nextOfType(t, timeoutMs) {
    return this.next((f) => f.t === t, timeoutMs);
  }

  async join(extra = {}) {
    await this.ready;
    this.send({ t: 'hello', name: this.name, identityPub: this.identityPub, ...extra });
    await this.nextOfType('welcome');
    return this;
  }

  close() {
    this.ws.close();
  }
}

/** Deterministic stand-in for a real X25519 public key. */
const pub = (seed) => Buffer.alloc(32, seed).toString('base64');
const b64 = (text) => Buffer.from(text, 'utf8').toString('base64');

// ---------------------------------------------------------------------------
// Discovery: the "Alex installs, Alex2 installs, they see each other" rule
// ---------------------------------------------------------------------------

test('a new peer sees every peer already online, and existing peers see the new one', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const alex = await new TestPeer(relay.url, 'Alex', pub(1)).join();
  assert.equal(alex.peers.length, 0, 'first peer sees an empty room');

  const alex2 = await new TestPeer(relay.url, 'Alex', pub(2)).join();
  assert.equal(alex2.peers.length, 1, 'second peer sees the first');
  assert.equal(alex2.peers[0].name, 'Alex');
  assert.equal(alex2.peers[0].online, true);

  // Presence is pushed, not polled. The roster is the same frame for
  // everyone and therefore includes the recipient's own entry; the client
  // filters itself out when it renders "All Users".
  const roster = await alex.nextOfType('roster');
  assert.equal(roster.peers.length, 2, 'roster includes both peers, including self');
  assert.deepEqual(
    roster.peers.map((p) => p.id).sort(),
    [alex.id, alex2.id].sort(),
    'both identities present exactly once',
  );

  // Two people with the same display name are two distinct peers.
  assert.notEqual(alex.id, alex2.id, 'identity key, not display name, keys the roster');
});

test('a peer leaving removes them from the roster for everyone else', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(3)).join();
  const b = await new TestPeer(relay.url, 'Grace', pub(4)).join();

  const bothOnline = await a.nextOfType('roster');
  assert.equal(bothOnline.peers.length, 2, 'both peers online');

  // Drain everything queued from the join churn, so the next roster we see is
  // unambiguously the one triggered by Grace leaving.
  a.inbox.length = 0;
  b.close();

  const after = await a.next(
    (f) => f.t === 'roster' && f.peers.length === 1,
    5000,
  );
  assert.deepEqual(
    after.peers.map((p) => p.id),
    [a.id],
    'only Ada remains in her own roster',
  );
  assert.equal(relay.hub.size, 1);
});

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

test('an encrypted envelope routes to the named peer and only that peer', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(5)).join();
  const b = await new TestPeer(relay.url, 'Bob', pub(6)).join();
  const c = await new TestPeer(relay.url, 'Cara', pub(7)).join();

  // Drain the presence bursts so we can prove non-delivery.
  a.inbox.length = 0;
  b.inbox.length = 0;
  c.inbox.length = 0;

  const envelope = b64('nacl-encrypted-ciphertext-goes-here');
  a.send({ t: 'send', to: b.id, envelope });

  const got = await b.nextOfType('msg');
  assert.equal(got.from, a.id);
  assert.equal(got.envelope, envelope);
  assert.equal(got.kind, 'msg');
  assert.equal(typeof got.at, 'number', 'server stamps a monotonic-ish clock');

  // Give the relay a beat to (not) misroute.
  await new Promise((r) => setTimeout(r, 120));
  assert.equal(c.inbox.filter((f) => f.t === 'msg').length, 0, 'third party receives nothing');
  assert.equal(a.inbox.filter((f) => f.t === 'msg').length, 0, 'sender gets no echo');
});

test('a message to an offline peer is dropped and the sender is told', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(8)).join();
  a.send({ t: 'send', to: 'p_doesnotexist000000', envelope: b64('x') });

  const err = await a.nextOfType('error');
  assert.equal(err.code, 'unknown_peer');
  assert.equal(relay.hub.stats.dropped, 1, 'nothing queued anywhere');
});

test('the relay retains nothing after delivery', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(9)).join();
  const b = await new TestPeer(relay.url, 'Bob', pub(10)).join();

  const SECRET = 'the-message-body-should-never-persist';
  a.send({ t: 'send', to: b.id, envelope: Buffer.from(SECRET, 'utf8').toString('base64') });
  await b.nextOfType('msg');

  // Walk every object the relay holds and assert the plaintext never appears.
  // A hand-rolled walk rather than JSON.stringify, because the hub holds live
  // sockets and timers whose graphs are circular.
  const hits = findStrings(relay.hub, SECRET);
  assert.deepEqual(hits, [], 'no message content retained anywhere in relay state');
});

/**
 * Depth-first search of all string values reachable from `root`, excluding
 * functions and sockets, returning the object paths whose value contains
 * `needle`. Cyclic references are visited once via a WeakSet.
 */
function findStrings(root, needle, path = '$', seen = new WeakSet(), hits = []) {
  if (typeof root === 'string') {
    if (root.includes(needle)) hits.push(path);
    return hits;
  }
  if (root === null || typeof root !== 'object') return hits;
  if (seen.has(root)) return hits;
  seen.add(root);

  for (const key of Object.keys(root)) {
    // Skip the socket: it is a live transport, not relay state, and walking it
    // reaches into libuv internals that tell us nothing about retention.
    if (key === 'socket' || key === '_send') continue;
    findStrings(root[key], needle, `${path}.${key}`, seen, hits);
  }
  return hits;
}

// ---------------------------------------------------------------------------
// Delete (no edit)
// ---------------------------------------------------------------------------

test('delete propagates to the recipient with the msgId intact', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(11)).join();
  const b = await new TestPeer(relay.url, 'Bob', pub(12)).join();

  a.send({ t: 'delete', to: b.id, msgId: 'm-42', forAll: true });
  const del = await b.nextOfType('delete');

  assert.equal(del.msgId, 'm-42');
  assert.equal(del.forAll, true);
  assert.equal(del.by, a.id);
  assert.equal(del.byName, 'Ada');
});

// ---------------------------------------------------------------------------
// Call signaling
// ---------------------------------------------------------------------------

test('rtc signaling is relayed with kind=rtc so the client can route it to WebRTC', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(13)).join();
  const b = await new TestPeer(relay.url, 'Bob', pub(14)).join();

  // SDP/ICE arrives inside the sealed box, exactly like a chat message.
  const SDP = 'v=0\r\no=- 1234 2 IN IP4 127.0.0.1\r\na=ice-ufrag:9Yk2\r\n';
  a.send({ t: 'rtc', to: b.id, envelope: Buffer.from(SDP, 'utf8').toString('base64') });

  const got = await b.nextOfType('msg');
  assert.equal(got.kind, 'rtc');
  assert.equal(got.from, a.id);
  // Compare after decoding — the wire format is base64, so asserting on raw
  // SDP text inside the envelope would never match.
  assert.equal(
    Buffer.from(got.envelope, 'base64').toString('utf8'),
    SDP,
    'payload survives the relay byte-for-byte',
  );
});

// ---------------------------------------------------------------------------
// Typing
// ---------------------------------------------------------------------------

test('typing indicators relay; sends to offline peers are silently ignored', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const a = await new TestPeer(relay.url, 'Ada', pub(15)).join();
  const b = await new TestPeer(relay.url, 'Bob', pub(16)).join();

  a.send({ t: 'typing', to: b.id, state: true });
  const t1 = await b.nextOfType('typing');
  assert.equal(t1.state, true);
  assert.equal(t1.from, a.id);

  // Offline target: no error, because a typing tick is not worth an exception.
  a.send({ t: 'typing', to: 'p_ghost000000000000', state: true });
  await new Promise((r) => setTimeout(r, 150));
  assert.equal(
    a.inbox.filter((f) => f.t === 'error').length,
    0,
    'no error frame for offline typing target',
  );
});

// ---------------------------------------------------------------------------
// Protocol hardening
// ---------------------------------------------------------------------------

test('frames before hello are rejected', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = new TestPeer(relay.url, 'Early', pub(17));
  await p.ready;
  p.send({ t: 'send', to: 'p_x', envelope: b64('y') });

  const err = await p.nextOfType('error');
  assert.equal(err.code, 'not_ready');
});

test('a second hello on the same socket is refused', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = await new TestPeer(relay.url, 'Twice', pub(18)).join();
  p.send({ t: 'hello', name: 'Twice', identityPub: pub(18) });

  const err = await p.nextOfType('error');
  assert.equal(err.code, 'already_joined');
});

test('malformed input produces an error frame, never a dropped connection', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = new TestPeer(relay.url, 'Fuzzer', pub(19));
  await p.ready;

  const hostile = [
    'not json at all',
    '[]',
    'null',
    '{"t":"nope"}',
    '{"t":"send"}',
    '{"t":"send","to":"","envelope":""}',
    '{"t":"hello","name":"","identityPub":"x"}',
    '{"t":"hello","name":"ok","identityPub":"!!!!"}',
  ];
  for (const raw of hostile) {
    p.ws.send(raw);
    const err = await p.nextOfType('error');
    assert.ok(err.code, `expected an error code for ${raw.slice(0, 32)}`);
  }

  // Still alive and still usable after all of that.
  await p.join();
  assert.equal(relay.hub.size, 1);
});

test('an oversized envelope is refused before it can be buffered', () => {
  const r = parseInbound(JSON.stringify({ t: 'send', to: 'p_x', envelope: 'A'.repeat(200_000) }), {
    hasJoined: true,
    selfId: 'p_self',
  });
  assert.equal(r.ok, false);
  assert.ok(['oversize', 'bad_frame'].includes(r.code), `got ${r.code}`);
});

test('the rate limiter throttles a flood without killing the socket', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = await new TestPeer(relay.url, 'Flooder', pub(20)).join();
  const b = await new TestPeer(relay.url, 'Sink', pub(21)).join();
  p.inbox.length = 0;

  for (let i = 0; i < 400; i++) p.send({ t: 'ping' });

  const err = await p.nextOfType('error');
  assert.equal(err.code, 'rate_limit');
  assert.equal(p.ws.readyState, WebSocket.OPEN, 'socket survives throttling');

  // And it recovers once the bucket refills.
  await new Promise((r) => setTimeout(r, 1500));
  p.send({ t: 'ping' });
  const pong = await p.nextOfType('pong');
  assert.equal(pong.t, 'pong');
});

// ---------------------------------------------------------------------------
// Prekey bundles
// ---------------------------------------------------------------------------

/**
 * A shape-valid prekey bundle. The relay does not verify the signature — that
 * is the client's job, and it is the only thing that actually matters for
 * security — it only checks the shape so a malformed bundle never reaches the
 * roster.
 */
function validBundle() {
  return {
    identity: Buffer.alloc(32, 1).toString('base64'),
    signingKey: Buffer.alloc(32, 2).toString('base64'),
    prekey: Buffer.alloc(32, 3).toString('base64'),
    signedPrekey: {
      key: Buffer.alloc(32, 4).toString('base64'),
      sig: Buffer.alloc(64, 5).toString('base64'),
      counter: 1,
    },
  };
}

test('a hello carrying a valid prekey bundle is accepted', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = new TestPeer(relay.url, 'Keys', pub(30));
  await p.ready;
  p.send({ t: 'hello', name: 'Keys', identityPub: pub(30), prekey: validBundle() });

  const welcome = await p.nextOfType('welcome');
  assert.equal(welcome.you.prekey.identity, Buffer.alloc(32, 1).toString('base64'));
  assert.equal(welcome.you.prekey.signingKey, Buffer.alloc(32, 2).toString('base64'));
});

test('a prekey sent as a JSON string is accepted rather than rejected on type', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  // This is the shape the Android client originally sent. The validator
  // required an object, so every real install was rejected with "prekey must
  // be a valid X25519 prekey bundle" and the app could never connect to
  // anyone. It went unnoticed because the Node e2e client sent no prekey at
  // all, so the field was undefined and the branch was never entered.
  const p = new TestPeer(relay.url, 'Stringy', pub(31));
  await p.ready;
  p.send({
    t: 'hello',
    name: 'Stringy',
    identityPub: pub(31),
    prekey: JSON.stringify(validBundle()),
  });

  const welcome = await p.nextOfType('welcome');
  assert.ok(welcome.you.prekey, 'bundle survived the string round-trip');
  assert.equal(welcome.you.prekey.prekey, Buffer.alloc(32, 3).toString('base64'));
});

test('a hello with no prekey at all is still accepted', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const p = new TestPeer(relay.url, 'Bare', pub(32));
  await p.ready;
  p.send({ t: 'hello', name: 'Bare', identityPub: pub(32) });

  const welcome = await p.nextOfType('welcome');
  assert.equal(welcome.you.prekey, null);
});

test('a structurally broken prekey is rejected with a message naming the field', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const missingSigning = validBundle();
  delete missingSigning.signingKey; // not `.signing` — that typo made this case
                                   // a valid bundle, so it was accepted and the
                                   // test hung waiting for an error that could
                                   // never arrive.

  const shortIdentity = validBundle();
  shortIdentity.identity = btoa('too short');

  const badSigned = validBundle();
  badSigned.signedPrekey = 'not an object';

  const badCounter = validBundle();
  badCounter.signedPrekey.counter = 'one';

  const cases = [
    ['missing signingKey', missingSigning],
    ['short identity', shortIdentity],
    ['signedPrekey not an object', badSigned],
    ['counter not an integer', badCounter],
  ];

  for (const [label, bundle] of cases) {
    const p = new TestPeer(relay.url, 'Broken', pub(33));
    await p.ready;
    p.send({ t: 'hello', name: 'Broken', identityPub: pub(33), prekey: bundle });
    const err = await p.nextOfType('error');
    assert.equal(err.code, 'bad_frame', `${label} should be rejected`);
    assert.match(err.message, /prekey/i, `${label}: error should name the field`);
    p.close();
  }
});

// ---------------------------------------------------------------------------
// Identity spoofing
// ---------------------------------------------------------------------------

test('display names with control and bidi-override characters are normalised', () => {
  assert.equal(sanitizeName('  Sam  '), 'Sam');
  assert.equal(sanitizeName('Sam‍'), 'Sam', 'zero-width stripped');
  assert.equal(sanitizeName('‮gnitset‬'), 'gnitset', 'bidi override stripped');
  assert.equal(sanitizeName(' Alex'), 'Alex', 'control chars stripped');
  assert.equal(sanitizeName(''), null);
  assert.equal(sanitizeName('   '), null);
  assert.equal(sanitizeName('x'.repeat(33)), null);
  assert.equal(sanitizeName('x'.repeat(32)), 'x'.repeat(32));
  assert.equal(sanitizeName('🎮 potato'), '🎮 potato', 'emoji are legal display names');
});

test('the same identity key connecting twice supersedes the older socket', async (t) => {
  const relay = await startRelay();
  t.after(() => relay.close());

  const first = await new TestPeer(relay.url, 'Same', pub(22)).join();
  const firstId = first.id;

  await new Promise((r) => setTimeout(r, 80));
  const second = await new TestPeer(relay.url, 'Same', pub(22)).join();

  assert.equal(second.id, firstId, 'identity key produces a stable id across reconnects');
  assert.equal(relay.hub.size, 1, 'one live socket per identity');

  // The superseded socket must actually go away, not linger as a phantom route.
  // readyState 2 is CLOSING: the close frame is in flight, which is the correct
  // instant-state expectation. Asserting CLOSED would race the close handshake.
  const closed = new Promise((resolve) => {
    if (first.ws.readyState === WebSocket.CLOSED) resolve();
    else first.ws.once('close', resolve);
  });
  await Promise.race([
    closed,
    new Promise((_, reject) => setTimeout(() => reject(new Error('stale socket never closed')), 4000)),
  ]);
  assert.equal(first.ws.readyState, WebSocket.CLOSED);
  assert.equal(relay.hub.size, 1, 'supersede did not resurrect the old peer');
});

// ---------------------------------------------------------------------------
// base64 validation
// ---------------------------------------------------------------------------

test('isBase64 rejects non-canonical and oversized encodings', () => {
  assert.equal(isBase64(b64('hello'), 64), true, 'canonical input accepted');
  assert.equal(isBase64('aGVsbG8=', 64), true, 'correct padding accepted');
  assert.equal(isBase64('aGVsbG8', 64), false, 'truncated input rejected');
  assert.equal(isBase64('aGVsbG8===', 64), false, 'over-padded input rejected');
  assert.equal(isBase64('aGVsbG9-', 64), false, 'url-safe alphabet rejected');
  assert.equal(isBase64('****', 64), false, 'non-alphabet rejected');
  assert.equal(isBase64('', 64), false, 'empty rejected');
  assert.equal(isBase64('aa==', 64), false, 'non-canonical trailing bits rejected');
  assert.equal(isBase64('AA==', 64), true, 'canonical single zero byte accepted');

  // The decoded-size guard is the one that matters: base64 padding means
  // encoded length does not determine decoded length, so an arithmetic check
  // on the string alone lets 65 bytes through a 64-byte cap.
  assert.equal(isBase64(b64('x'.repeat(63)), 64), true, '63 bytes under cap');
  assert.equal(isBase64(b64('x'.repeat(64)), 64), true, '64 bytes exactly at cap');
  assert.equal(isBase64(b64('x'.repeat(65)), 64), false, '65 bytes over cap');
  assert.equal(isBase64(b64('x'.repeat(100)), 64), false, 'well over cap');
});

test('envelope cap is 64 KiB', () => {
  assert.equal(MAX_ENVELOPE_BYTES, 65_536);
});