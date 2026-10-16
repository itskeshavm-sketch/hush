import { WebSocket } from 'ws';
import { randomBytes } from 'node:crypto';

const url = process.argv[2] ?? 'wss://spudpotato.itskeshavm.workers.dev/ws';
const b64 = (b) => Buffer.from(b).toString('base64');
const key = (tag) => b64(randomBytes(32).map((_, i) => (tag + i) & 0xff));
const bundle = (tag) => ({
  identity: key(tag),
  signingKey: key(tag + 1),
  prekey: key(tag + 2),
  signedPrekey: { key: key(tag + 3), sig: b64(randomBytes(64)), counter: 1 },
});

const framesA = [];
const a = new WebSocket(url);
await new Promise((res, rej) => { a.once('open', res); a.once('error', rej); });
a.on('message', (d) => framesA.push(JSON.parse(d.toString())));
a.send(JSON.stringify({ t: 'hello', name: 'ProbeA', identityPub: key(1), prekey: bundle(10) }));
const wA = await new Promise((res) => {
  const h = (d) => { const f = JSON.parse(d.toString()); if (f.t === 'welcome') { a.off('message', h); res(f); } };
  a.on('message', h);
});
console.log('A joined as', wA.you.id);

const b = new WebSocket(url);
await new Promise((res, rej) => { b.once('open', res); b.once('error', rej); });
b.send(JSON.stringify({ t: 'hello', name: 'ProbeB', identityPub: key(2), prekey: bundle(20) }));
const wB = await new Promise((res) => {
  const h = (d) => { const f = JSON.parse(d.toString()); if (f.t === 'welcome') { b.off('message', h); res(f); } };
  b.on('message', h);
});
console.log('B joined as', wB.you.id);

// Wait until A sees both peers.
const both = await new Promise((res, rej) => {
  const timer = setTimeout(() => rej(new Error('A never saw both peers')), 15000);
  const h = (d) => {
    const f = JSON.parse(d.toString());
    if (f.t === 'roster' && f.peers.length >= 2) { clearTimeout(timer); a.off('message', h); res(f); }
  };
  a.on('message', h);
});
console.log('A sees roster:', both.peers.map((p) => p.name).join(','));

// B goes away WITHOUT a close frame (app kill: kernel drops TCP).
framesA.length = 0;
b.terminate();

// Does A get a roster without B?
const t0 = Date.now();
const after = await new Promise((res) => {
  const timer = setTimeout(() => res(null), 90000);
  const h = (d) => {
    const f = JSON.parse(d.toString());
    if (f.t === 'roster' && f.peers.every((p) => p.id !== wB.you.id)) {
      clearTimeout(timer); a.off('message', h); res(f);
    }
  };
  a.on('message', h);
});
if (after) {
  console.log(`A got offline update after ${((Date.now() - t0) / 1000).toFixed(1)}s, roster now:`, after.peers.map((p) => p.name).join(',') || '(just A)');
  console.log('RESULT: relay broadcasts disconnects live');
} else {
  console.log('A got no offline update within 90s of unclean drop');
  console.log('RESULT: relay does NOT notice unclean disconnects promptly');
}
a.close();
process.exit(0);
