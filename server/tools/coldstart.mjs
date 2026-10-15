/**
 * Cold-start regression check for the Cloudflare relay.
 *
 * Run against a live deployment:
 *
 *   node tools/coldstart.mjs [relay-url]
 *
 * Why this exists
 * ---------------
 * The relay keeps peer records on the Durable Object. An earlier version kept
 * them in a Map on the instance instead, which the platform discards whenever it
 * hibernates the isolate — and it hibernates constantly, because that is how the
 * free tier stays affordable. The WebSocket survived; the peer list did not.
 *
 * The result was an app that stayed connected, showed no error, and reported
 * "Nobody else is here yet" forever, because the relay genuinely had no peers.
 * Two installs on the same relay could never see each other.
 *
 * This cannot be reproduced against the Node relay: a Node process is not evicted
 * mid-connection, so the bug is invisible there. It has to be checked against
 * the real thing, which is why it is a script and not a unit test.
 *
 * The check that matters is the idle one. A roster that survives a connection
 * arriving 10 ms after another is worthless; the bug only shows up once the
 * isolate has had a chance to be torn down.
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
    ws,
    frames,
    open,
    send: (f) => ws.send(JSON.stringify(f)),
    async hello() {
      await open;
      this.send({ t: "hello", name, identityPub: key(tag), prekey: bundle(tag + 10) });
      await sleep(700);
      return this.frames.find((f) => f.t === "welcome");
    },
    // Method shorthand, not an arrow property. `roster: () => this.frames` binds
    // `this` to the object literal, so `this.frames` was undefined the moment
    // the method was called on its own.
    roster() {
      return this.frames.filter((f) => f.t === "roster").pop()?.peers ?? [];
    },
  };
}

const health = async () => {
  const r = await fetch(HEALTH);
  if (!r.ok) throw new Error(`${HEALTH} returned ${r.status}`);
  return r.json();
};

console.log(`relay: ${RELAY}\n`);

// ---------------------------------------------------------------------------
console.log("1. a peer that connects and then goes quiet is still online later");
{
  const a = client("Idle", 30);
  const w = await a.hello();
  check(w?.you?.name === "Idle", "joined and received a welcome");

  const start = (await health()).peers;
  check(start >= 1, `relay counts it immediately (peers=${start})`);

  // Long enough for the platform to consider the isolate idle and evict it.
  // The socket is never touched during this window, so anything the relay
  // "remembers" has to have come back from storage.
  const IDLE_MS = 75_000;
  process.stdout.write(`  idling ${IDLE_MS / 1000}s with the socket open`);
  for (let waited = 0; waited < IDLE_MS; waited += 15_000) {
    await sleep(15_000);
    process.stdout.write(".");
  }
  process.stdout.write("\n");

  const after = await health();
  check(after.peers >= 1, `still counted after idling (peers=${after.peers})`);
  check(
    after.names?.includes("Idle") ?? false,
    `still named in the roster (names=${JSON.stringify(after.names)})`,
  );

  a.send({ t: "roster" });
  await sleep(700);
  const seen = a.roster().map((p) => p.name);
  check(seen.includes("Idle"), `still sees itself in its own roster (${JSON.stringify(seen)})`);

  a.ws.close();
  await sleep(600);
}

// ---------------------------------------------------------------------------
console.log("\n2. two peers that connect at completely different times still see each other");
{
  const a = client("First", 130);
  await a.hello();
  await sleep(70_000); // let A's connection go quiet and be forgotten by memory

  const b = client("Second", 230);
  const w2 = await b.hello();

  check(
    w2?.you?.name === "Second",
    "the late joiner is welcomed",
  );
  check(
    (w2?.peers ?? []).some((p) => p.name === "First"),
    `the late joiner is handed the early peer (${JSON.stringify((w2?.peers ?? []).map((p) => p.name))})`,
  );

  await sleep(1000);
  check(
    a.roster().some((p) => p.name === "Second"),
    `the early peer is told about the late one (${JSON.stringify(a.roster().map((p) => p.name))})`,
  );

  // The whole point of a roster: can they actually talk?
  //
  // Address it to the *other* peer's id. `w2.you` is the late joiner, so that
  // is the destination; `w2.peers` holds the early peer, which is the sender.
  const destination = w2?.you?.id;
  if (destination) {
    a.send({ t: "send", to: destination, kind: "msg", envelope: b64(Buffer.from("ping")) });
    await sleep(900);
    check(
      b.frames.some((f) => f.t === "msg" && f.fromName === "First"),
      `a message routes from First to Second (frames=${JSON.stringify(b.frames.map((f) => f.t))})`,
    );
  } else {
    check(false, "a message routes between the two (no destination id)");
  }

  a.ws.close();
  b.ws.close();
  await sleep(800);
}

// ---------------------------------------------------------------------------
console.log("\n3. roster records carry the fields the app renders");
{
  const a = client("Fields", 60);
  await a.hello();
  const b = client("Viewer", 90);
  const w2 = await b.hello();
  const rec = (w2?.peers ?? []).find((p) => p.name === "Fields");

  check(!!rec, "the viewer was handed a record for the other peer");
  // A SQLite-backed Durable Object returns a Map from a multi-key `get`; reading
  // it as an array silently produced records with no id and no name, which
  // rendered as blank roster rows. These four assertions are that bug.
  check(typeof rec?.id === "string" && rec.id.length > 0, "record has an id");
  check(typeof rec?.name === "string" && rec.name.length > 0, "record has a name");
  check(typeof rec?.identityPub === "string" && rec.identityPub.length > 0, "record has an identity key");
  check(rec?.online === true, "record is marked online");

  a.ws.close();
  b.ws.close();
  await sleep(600);
}

// ---------------------------------------------------------------------------
console.log("\n4. a departing peer disappears");
{
  const a = client("Leaver", 150);
  await a.hello();
  await sleep(600);
  check((await health()).peers >= 1, "present while connected");

  a.ws.close();
  await sleep(2500);
  const after = await health();
  check(
    !(after.names ?? []).includes("Leaver"),
    `gone after closing (names=${JSON.stringify(after.names)})`,
  );
}

console.log(
  failures === 0 ? "\nCOLD-START CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`,
);
process.exit(failures === 0 ? 0 : 1);