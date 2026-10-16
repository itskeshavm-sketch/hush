/**
 * End-to-end check against a *running* relay.
 *
 *   node tools/e2e.js wss://localhost:8443/ws
 *
 * Connects two real TLS WebSocket clients, walks the full protocol, and exits
 * non-zero on the first divergence. This is the script that proves the server
 * works outside the unit-test harness, including the certificate.
 */

import { WebSocket } from 'ws';
import { randomBytes } from 'node:crypto';

const url = process.argv[2] ?? 'wss://localhost:8443/ws';
const NODE_TLS_REJECT_UNAUTHORIZED = process.env.HUSH_INSECURE === '1' ? '0' : undefined;
if (NODE_TLS_REJECT_UNAUTHORIZED) process.env.NODE_TLS_REJECT_UNAUTHORIZED = NODE_TLS_REJECT_UNAUTHORIZED;

const pass = (m) => console.log(`  PASS  ${m}`);
const fail = (m) => {
  console.error(`  FAIL  ${m}`);
  process.exitCode = 1;
};

const b64 = (b) => Buffer.from(b).toString('base64');
const key = (tag) => b64(randomBytes(32).fill(0x41, 0, 1).map((_, i) => (tag + i) & 0xff));

/**
 * A shape-valid prekey bundle.
 *
 * This client originally sent no prekey at all, which is exactly why the
 * string-vs-object mismatch between the Android client and the relay survived
 * a full test cycle â€” the field was undefined, so the validation branch was
 * never entered. Every real client must publish one.
 */
const prekeyBundle = (tag) => ({
  identity: key(tag),
  signingKey: key(tag + 1),
  prekey: key(tag + 2),
  signedPrekey: { key: key(tag + 3), sig: b64(randomBytes(64)), counter: 1 },
});

class Client {
  constructor(name, url) {
    this.name = name;
    this.queue = [];
    this.waiters = [];
    this.ws = new WebSocket(url, { rejectUnauthorized: NODE_TLS_REJECT_UNAUTHORIZED !== '0' });
    this.opened = new Promise((res, rej) => {
      this.ws.once('open', res);
      this.ws.once('error', rej);
    });
    this.ws.on('message', (d) => this.#onFrame(JSON.parse(d.toString())));
  }

  #onFrame(f) {
    const i = this.waiters.findIndex((w) => w.match(f));
    if (i >= 0) {
      const [w] = this.waiters.splice(i, 1);
      clearTimeout(w.timer);
      w.resolve(f);
    } else {
      this.queue.push(f);
    }
  }

  send(o) {
    this.ws.send(JSON.stringify(o));
  }

  next(match, ms = 5000) {
    const i = this.queue.findIndex(match);
    if (i >= 0) return Promise.resolve(this.queue.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { match, resolve, reject };
      w.timer = setTimeout(() => reject(new Error(`${this.name}: timeout`)), ms);
      this.waiters.push(w);
    });
  }

  ofType(t) {
    return this.next((f) => f.t === t);
  }

  async join(displayName, idKey, tag) {
    await this.opened;
    this.send({
      t: 'hello',
      name: displayName,
      identityPub: idKey,
      prekey: prekeyBundle(tag),
    });
    const w = await this.ofType('welcome');
    this.id = w.you.id;
    this.displayName = displayName;
    if (!w.you.prekey) fail('relay dropped our prekey bundle');
    return this;
  }

  close() {
    this.ws.close();
  }
}

console.log(`\nHUSH end-to-end against ${url}\n`);

const alex = await new Client('alex', url).join('Alex', key(1), 10);
pass(`Alex joined as ${alex.id}`);

const alex2 = await new Client('alex2', url).join('Alex', key(2), 20);
pass(`Alex2 joined as ${alex2.id}`);

// Wait for the roster to actually CONTAIN both peers, rather than asserting on
// the next frame to arrive. Over a tunnel the presence broadcast is debounced
// 120ms and the round trip is longer than that, so the first roster frame
// Alex sees legitimately contains one peer. Asserting on arrival instead of on
// state is a flaky test, not a relay bug.
const roster = await alex.next((f) => f.t === 'roster' && f.peers.length >= 2, 15000);
const names = roster.peers.map((p) => p.name);
if (!names.includes('Alex') || roster.peers.length < 2) {
  fail(`roster should show both Alexes, got ${JSON.stringify(names)}`);
} else {
  pass(`Alex sees ${roster.peers.length} peers online: ${names.join(', ')}`);
}
if (alex.id === alex2.id) fail('two identities must get different peer ids');
else pass('two people named "Alex" are two distinct peers');

// A realistic sealed envelope: random-looking ciphertext, as a real client sends.
const envelope = b64(randomBytes(512));
alex.send({ t: 'send', to: alex2.id, envelope });
const got = await alex2.ofType('msg');
if (got.envelope === envelope && got.from === alex.id && got.kind === 'msg') {
  pass(`ciphertext routed intact (${envelope.length} b64 chars, ${Buffer.from(envelope, 'base64').length} bytes)`);
} else {
  fail('envelope did not survive routing');
}

alex.send({ t: 'typing', to: alex2.id, state: true });
const typing = await alex2.ofType('typing');
typing.state === true ? pass('typing indicator relayed') : fail('typing state wrong');

alex.send({ t: 'delete', to: alex2.id, msgId: 'msg-0001', forAll: true });
const del = await alex2.ofType('delete');
del.msgId === 'msg-0001' ? pass('delete propagated (no edit path exists)') : fail('delete wrong msgId');

alex2.send({ t: 'seen', to: alex.id, msgId: 'msg-0001' });
const seen = await alex.ofType('seen');
seen.msgId === 'msg-0001' && seen.by === alex2.id
  ? pass('read receipt routed with sender attribution')
  : fail('seen receipt wrong');

alex2.send({ t: 'kept', to: alex.id, msgId: 'msg-0001' });
const kept = await alex.ofType('kept');
kept.msgId === 'msg-0001' && kept.by === alex2.id
  ? pass('keep notice routed with sender attribution')
  : fail('kept notice wrong');

alex2.send({ t: 'reset', to: alex.id });
const reset = await alex.ofType('reset');
reset.by === alex2.id
  ? pass('session reset request routed with sender attribution')
  : fail('reset routing wrong');

const sdp = b64(Buffer.from('v=0\r\no=- 99 2 IN IP4 127.0.0.1\r\na=ice-ufrag:HUSH\r\n'));
alex.send({ t: 'rtc', to: alex2.id, envelope: sdp });
const rtc = await alex2.ofType('msg');
if (rtc.kind === 'rtc' && rtc.envelope === sdp) pass('WebRTC signaling relayed with kind=rtc');
else fail('rtc signaling not tagged correctly');

alex.queue.length = 0;
alex.send({ t: 'send', to: 'p_nobody000000000000000', envelope });
const err = await alex.ofType('error');
err.code === 'unknown_peer' ? pass('offline peer rejected, nothing queued') : fail(`expected unknown_peer, got ${err.code}`);

// Drain queued presence frames so the next one we assert on is the one
// triggered by Alex's disconnect and not a stale frame that happened to match.
alex2.queue.length = 0;
alex.close();
const after = await alex2
  .next((f) => f.t === 'roster' && f.peers.filter((p) => p.id !== alex2.id).length === 0, 15000)
  .catch(() => null);
after
  ? pass('Alex2 sees the roster shrink to just itself')
  : fail('roster never reflected the departure');

alex2.close();
console.log(process.exitCode ? '\nE2E FAILED\n' : '\nE2E PASSED\n');
process.exit(process.exitCode ?? 0);