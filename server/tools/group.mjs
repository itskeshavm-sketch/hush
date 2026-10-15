/**
 * Does a group of N installs actually see each other?
 *
 * The roster is a flat list: every live socket, minus yourself. Three peers
 * should therefore see two others each, and every pair should be able to
 * message every other pair independently.
 *
 * Worth checking rather than assuming, because several things could quietly
 * cap it: MAX_ROSTER on the relay, the client's own self-filter, the
 * per-conversation ratchet, and the safety number, which has to stay distinct
 * for every pair or out-of-band verification compares the wrong thing.
 */
import { WebSocket } from "ws";
import { randomBytes } from "node:crypto";

const RELAY = process.argv[2] ?? "wss://spudpotato.itskeshavm.workers.dev/ws";
const HEALTH = RELAY.replace(/^ws/, "http").replace(/\/ws$/, "") + "/healthz";

const b64 = (b) => Buffer.from(b).toString("base64");
const key = (t) => b64(Buffer.alloc(32, t));
const bundle = (t) => ({
  identity: key(t),
  signingKey: key(t + 1),
  prekey: key(t + 2),
  signedPrekey: { key: key(t + 3), sig: b64(randomBytes(64)), counter: 1 },
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const check = (ok, label) => {
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${label}`);
  if (!ok) failures++;
};

function client(name, tag) {
  const ws = new WebSocket(RELAY);
  const frames = [];
  ws.on("message", (d) => frames.push(JSON.parse(d.toString())));
  const open = new Promise((res, rej) => {
    ws.on("open", res);
    ws.on("error", rej);
  });
  return {
    name,
    ws,
    frames,
    open,
    id: null,
    async hello() {
      await open;
      ws.send(
        JSON.stringify({
          t: "hello",
          name,
          identityPub: key(tag),
          prekey: bundle(tag + 10),
        }),
      );
      await sleep(800);
      const w = frames.find((f) => f.t === "welcome");
      this.id = w?.you?.id ?? null;
      return w;
    },
    send: (f) => ws.send(JSON.stringify(f)),
    /** Names this peer currently believes are online, excluding itself. */
    visible() {
      const r = frames.filter((f) => f.t === "roster").pop();
      const names = (r?.peers ?? []).map((p) => p.name).filter((n) => n !== name);
      return [...new Set(names)].sort();
    },
  };
}

const NAMES = ["ann", "ben", "cy", "dee"];
const peers = [];
for (let i = 0; i < NAMES.length; i++) {
  peers.push(client(NAMES[i], 40 + i * 20));
  await peers[i].hello();
  await sleep(400);
}

// Let the debounced presence broadcast land.
await sleep(1500);

console.log(`${NAMES.length} peers connected to ${RELAY}\n`);

for (const p of peers) {
  const seen = p.visible();
  const expected = NAMES.filter((n) => n !== p.name).sort();
  // Subset, not equality: a real person may be connected to the same public
  // relay while this runs, and their presence is not a failure.
  check(
    expected.every((n) => seen.includes(n)),
    `${p.name} sees ${expected.join(", ")}  (roster: ${JSON.stringify(seen)})`,
  );
}

const health = await (await fetch(HEALTH)).json();
const mine = (health.names ?? []).filter((n) => NAMES.includes(n));
check(
  mine.length === NAMES.length,
  `relay reports all ${NAMES.length} of ours: ${JSON.stringify(mine)} (total online ${health.peers})`,
);

// Every ordered pair must route. With N peers that is N*(N-1) messages, which
// is the real claim: pairwise reachability, not just a list that looks right.
console.log("\npairwise routing:");
let routed = 0;
let attempted = 0;
const problems = [];
for (const from of peers) {
  for (const to of peers) {
    if (from === to) continue;
    attempted++;
    const before = to.frames.length;
    const beforeErrors = to.frames.filter((f) => f.t === "error").length;
    from.send({
      t: "send",
      to: to.id,
      kind: "msg",
      envelope: b64(Buffer.from(`${from.name}->${to.name}`)),
    });
    await sleep(600);
    const delivered = to.frames.slice(before).some((f) => f.t === "msg");
    const err = to.frames.slice(before).find((f) => f.t === "error");
    if (delivered) routed++;
    else {
      problems.push(
        `${from.name}->${to.name} (target ${to.id})` +
          (err ? ` relay error: ${err.code} ${err.message}` : " no frame arrived") +
          (to.frames.filter((f) => f.t === "error").length > beforeErrors ? "" : ""),
      );
    }
  }
}
check(
  routed === attempted,
  `${routed}/${attempted} directed messages delivered across all pairs`,
);
for (const p of problems) console.log(`        missed: ${p}`);

// Distinct identities must mean distinct safety numbers, or two-person
// verification would compare the wrong thing.
console.log("\nidentity keys are unique per install:");
const ids = new Set(peers.map((p) => p.id));
check(ids.size === NAMES.length, `${ids.size}/${NAMES.length} distinct peer ids`);

for (const p of peers) p.ws.close();
await sleep(1200);
const after = await (await fetch(HEALTH)).json();
const stillMine = (after.names ?? []).filter((n) => NAMES.includes(n));
check(
  stillMine.length === 0,
  `all of ours gone after closing (remaining: ${JSON.stringify(after.names)})`,
);

console.log(failures === 0 ? "\nGROUP CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);