/**
 * HUSH relay — Cloudflare Worker + Durable Object.
 *
 * Why this exists alongside the Node relay: the Node relay needs a machine
 * awake, a port, and a certificate. A Worker needs none of those. Cloudflare's
 * free tier runs it 24/7, the free tier needs no card, and the platform hands
 * out a real Let's Encrypt certificate for a `workers.dev` hostname — which
 * also means the Android client never has to deal with a self-signed
 * certificate or trust-on-first-use in the common case.
 *
 * Same wire protocol as the Node relay. `protocol.js` is imported, not copied,
 * so the two cannot drift.
 *
 * Everything lives in ONE Durable Object.
 *
 * That is a deliberate trade, and it is the right one here. A Durable Object
 * is a single-threaded, globally-addressable process with its own private
 * storage, which is exactly the shape of "one room, one roster, no database".
 * The roster has to be globally consistent — two peers must see each other no
 * matter which edge either connected to — and sharding it across objects would
 * mean inventing a distributed-presence protocol to serve a two-person test.
 * A DO's 128 MB and single-threaded event loop comfortably hold thousands of
 * idle sockets, and the whole point is that there is no stored state to grow.
 */

// `DurableObject` is a real export of this module and is the base class every
// Durable Object must extend. There is no `DO` export — an earlier draft
// imported `{ DO, DurableObject }` and failed at bundle time with
// "module 'cloudflare:workers' does not provide an export named 'DO'".
import { DurableObject } from "cloudflare:workers";

import {
  ERROR_CODES,
  MAX_ROSTER,
  PROTOCOL_VERSION,
  parseInbound,
  sanitizeName,
} from "../../shared/protocol.js";

/** Presence broadcasts are debounced so a burst of joins is one fan-out. */
const PRESENCE_DEBOUNCE_MS = 120;

/** Storage key prefix for one peer's record. */
const PEER_KEY = "peer:";

/**
 * Worker entry point.
 *
 * Deliberately thin: it upgrades the socket and hands it to the Durable
 * Object. Doing any state work here would be per-edge and therefore wrong —
 * two clients on different edges would each have their own roster.
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/healthz") {
      const stub = env.HUB.get(env.HUB.idFromName("global"));
      return stub.fetch("https://internal/health");
    }

    // Operator-only: drop every socket and erase every peer record, so the
    // roster empties globally. Gated by a bearer secret held as a wrangler
    // encrypted env var (never in the repo) — and a wrong/missing key gets a
    // bare 404, so the endpoint does not advertise itself to scanners.
    if (url.pathname === "/admin/evict") {
      if (request.method !== "POST") {
        return new Response("not found", { status: 404 });
      }
      const want = env.ADMIN_KEY;
      const got = request.headers.get("authorization");
      if (typeof want !== "string" || want.length < 16 || got !== `Bearer ${want}`) {
        return new Response("not found", { status: 404 });
      }
      const stub = env.HUB.get(env.HUB.idFromName("global"));
      return stub.fetch("https://internal/admin/evict", { method: "POST" });
    }

    if (url.pathname === "/ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("expected a websocket upgrade", { status: 400 });
      }
      const id = env.HUB.idFromName("global");
      return env.HUB.get(id).fetch(request);
    }

    return serveClient(url);
  },
};

/**
 * The relay itself.
 *
 * Uses the WebSocket **Hibernation API** throughout. Hibernated sockets are
 * evicted from memory between messages and restored on demand, so an idle
 * connection costs storage, not a live isolate — which is what makes this
 * viable on the free tier. The alternative, holding sockets open, bills
 * continuously and would stop working for free almost immediately.
 */
export class HushRelay extends DurableObject {
  constructor(state, env) {
    super(state, env);
    this.state = state;

    // There is deliberately no `this.peers` registry here.
    //
    // An earlier version kept the roster in a Map on the instance. That looks
    // fine right up until Cloudflare evicts the isolate to save memory, which is
    // exactly what the Hibernation API is for: the WebSocket survives, anything
    // hung off `this` does not. The observable symptom was an app that stayed
    // connected — socket state OPEN, no errors — while the relay reported zero
    // peers, so every client saw an empty "nobody else is here" list and could
    // not message anyone. It was intermittent because eviction is not on a
    // timer you control, which is what made it look like a UI bug.
    //
    // Peer records now live in DO storage and are reached through
    // `ctx.getWebSockets()`, both of which are defined to outlive an eviction.

    this.presenceQueued = false;
  }

  /**
   * Health probe from the Worker, which cannot touch DO state directly.
   * Constructs the peer list so the response reports the truth.
   */
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      const roster = await this.#roster();
      return json({
        service: "hush-relay",
        runtime: "cloudflare-worker",
        protocol: PROTOCOL_VERSION,
        peers: roster.length,
        // Names, not just a count. A count that reads 0 while a client sits
        // there connected is exactly the failure this endpoint exists to make
        // visible.
        names: roster.map((p) => p.name),
        note: "zero-retention relay — no message content, no history, no accounts",
      });
    }

    // Reached only via the Worker's authed /admin/evict route — the DO itself
    // is never directly addressable, so no second auth check here.
    if (url.pathname === "/admin/evict" && request.method === "POST") {
      const live = this.ctx.getWebSockets();
      for (const ws of live) {
        try {
          ws.close(4001, "relay cleared by operator");
        } catch {
          /* already gone */
        }
      }
      // Belt and braces: sockets closing will also trigger per-socket
      // cleanup, but records are deleted here so a missed close event can
      // never leave a ghost row behind.
      const stored = await this.state.storage.list({ prefix: PEER_KEY });
      for (const key of stored.keys()) {
        await this.state.storage.delete(key);
      }
      this.presenceQueued = false;
      return json({ evicted: live.length, cleared: stored.size });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected a websocket upgrade", { status: 400 });
    }

    // Hibernation requires the full WebSocketPair dance. Two things to get
    // right, both of which fail at runtime rather than at bundle time:
    //
    //   - `WebSocketPair` must be constructed here. There is no
    //     `state.acceptWebSocket(id)` form; that was the first attempt and it
    //     returned a 500 on every upgrade.
    //   - `acceptWebSocket` lives on `this.ctx`, and takes an optional *tag*
    //     string, not a Durable Object id. `idFromName` does not exist inside
    //     a Durable Object at all — it belongs to the namespace binding, which
    //     is only reachable from the Worker.
    const pair = new WebSocketPair();
    const client = /** @type {WebSocket} */ (pair[0]);
    const server = /** @type {WebSocket} */ (pair[1]);

    this.ctx.acceptWebSocket(server);

    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * Called on every inbound frame while hibernated.
   *
   * The relay is a dumb pipe: everything that crosses here is either routing
   * metadata or an opaque base64 blob it has no key to read.
   */
  async webSocketMessage(ws, raw) {
    const text = typeof raw === "string" ? raw : new TextDecoder().decode(raw);

    // Resolved once and reused. Three storage reads for the same value was
    // three chances to observe a different answer.
    const peer = await this.#peerFor(ws);

    const parsed = parseInbound(text, {
      hasJoined: peer !== null,
      selfId: peer?.id ?? null,
    });

    if (!parsed.ok) {
      this.#send(ws, { t: "error", code: parsed.code, message: parsed.message });
      return;
    }

    await this.#dispatch(parsed.frame, peer, ws);
  }

  /**
   * Hibernation also delivers close here, so presence stays accurate without
   * the isolate having to be alive to notice a dropped TCP connection.
   */
  async webSocketClose(ws) {
    await this.#close(ws);
  }

  async webSocketError(ws) {
    await this.#close(ws);
  }

  // -------------------------------------------------------------------------
  // Dispatch
  // -------------------------------------------------------------------------

  async #dispatch(frame, peer, ws) {
    switch (frame.t) {
      case "ping":
        this.#send(ws, { t: "pong", at: Date.now() });
        return;

      case "hello": {
        if (peer) {
          this.#send(ws, { t: "error", code: ERROR_CODES.ALREADY_JOINED, message: "already joined" });
          return;
        }
        // Capacity is counted from live sockets, which is the only count that
        // cannot drift once a record outlives its connection.
        if (this.ctx.getWebSockets().length >= MAX_ROSTER) {
          this.#send(ws, { t: "error", code: ERROR_CODES.RATE_LIMIT, message: "relay at capacity" });
          return;
        }
        await this.#join(frame, ws);
        this.#queuePresence();
        return;
      }

      case "roster":
        this.#send(ws, await this.#rosterFrame());
        return;

      case "profile":
        if (peer) {
          await this.state.storage.put(PEER_KEY + peer.id, { ...peer, bio: frame.bio });
          this.#queuePresence();
        }
        return;

      case "send":
      case "rtc": {
        if (!peer) return;
        const target = this.#socketFor(frame.to);
        if (!target) {
          this.#send(ws, {
            t: "error",
            code: ERROR_CODES.UNKNOWN_PEER,
            message: `${frame.to} is offline`,
          });
          return;
        }
        // `kind` tells the client whether to route this to the chat pipeline
        // or to WebRTC signaling. It reveals nothing about the ciphertext.
        this.#send(target, {
          t: "msg",
          from: peer.id,
          fromName: peer.name,
          kind: frame.kind,
          envelope: frame.envelope,
          at: Date.now(),
        });
        return;
      }

      case "delete": {
        if (!peer) return;
        const target = this.#socketFor(frame.to);
        if (!target) {
          this.#send(ws, {
            t: "error",
            code: ERROR_CODES.UNKNOWN_PEER,
            message: `${frame.to} is offline`,
          });
          return;
        }
        this.#send(target, {
          t: "delete",
          by: peer.id,
          byName: peer.name,
          to: frame.to,
          msgId: frame.msgId,
          forAll: frame.forAll,
          at: Date.now(),
        });
        return;
      }

      case "seen":
      case "kept": {
        if (!peer) return;
        const target = this.#socketFor(frame.to);
        // Receipts are fire-and-forget like typing: a receipt for an offline
        // peer is dropped, and the sender's client re-sends unacked receipts
        // when the peer reappears. Silence is correct.
        if (!target) return;
        this.#send(target, {
          t: frame.t,
          by: peer.id,
          byName: peer.name,
          to: frame.to,
          msgId: frame.msgId,
          at: Date.now(),
        });
        return;
      }

      case "reset": {
        if (!peer) return;
        const target = this.#socketFor(frame.to);
        // Fire-and-forget like typing: erroring for an offline peer would
        // surface raw peer ids for a routine event, and the next message
        // re-establishes the session regardless.
        if (!target) return;
        this.#send(target, {
          t: "reset",
          by: peer.id,
          byName: peer.name,
          to: frame.to,
          at: Date.now(),
        });
        return;
      }

      case "typing": {
        if (!peer) return;
        const target = this.#socketFor(frame.to);
        // Fire and forget: no error frame for an offline typing target.
        if (!target) return;
        this.#send(target, { t: "typing", from: peer.id, fromName: peer.name, state: frame.state });
        return;
      }

      default:
        this.#send(ws, { t: "error", code: ERROR_CODES.UNKNOWN_FRAME, message: "unhandled frame" });
    }
  }

  async #join(frame, ws) {
    // Route key derived server-side from the identity key, so a client cannot
    // claim another peer's id.
    const id = `p_${frame.identityPub.slice(0, 22)}`;

    // Same identity reconnecting: drop the older socket so the roster never
    // shows one human twice.
    for (const other of this.ctx.getWebSockets()) {
      if (other === ws) continue;
      if (other.deserializeAttachment()?.id === id) {
        try {
          other.close(4000, "superseded by a newer connection");
        } catch {
          /* already gone */
        }
      }
    }

    const peer = {
      id,
      name: frame.name,
      identityPub: frame.identityPub,
      prekey: frame.prekey,
      bio: "",
      joinedAt: Date.now(),
    };

    // Durable storage, not instance memory. This is the line the whole bug
    // turned on.
    await this.state.storage.put(PEER_KEY + id, peer);

    // The attachment is how a socket is tied back to its record after the
    // isolate is rebuilt. Without it, a live socket is unattributable and the
    // relay cannot tell who is online.
    ws.serializeAttachment({ id });

    this.#send(ws, {
      t: "welcome",
      you: this.#record(peer),
      peers: (await this.#roster()).filter((p) => p.id !== id),
      serverTime: Date.now(),
      protocol: PROTOCOL_VERSION,
    });
  }

  // -------------------------------------------------------------------------
  // Presence
  // -------------------------------------------------------------------------

  #queuePresence() {
    if (this.presenceQueued) return;
    this.presenceQueued = true;

    // Durable Object alarms are the only timer that survives an isolate being
    // evicted, which matters precisely because every socket here is hibernated.
    this.state.storage.setAlarm(Date.now() + PRESENCE_DEBOUNCE_MS);
  }

  async alarm() {
    this.presenceQueued = false;
    const frame = await this.#rosterFrame();
    for (const ws of this.ctx.getWebSockets()) this.#send(ws, frame);
  }

  /**
   * The live roster, rebuilt from storage on every call.
   *
   * A peer counts as online if and only if an open socket carries its id.
   * Deriving presence from sockets rather than from a member list is what makes
   * this self-healing: a record whose socket has gone is simply not reported,
   * with no dependence on a close event having been delivered first.
   */
  async #roster() {
    const live = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      const id = ws.deserializeAttachment()?.id;
      if (typeof id === "string") live.add(id);
    }
    if (live.size === 0) return [];

    // `list({ prefix })` rather than `get([...keys])`.
    //
    // This DO uses the SQLite-backed storage class, where a multi-key `get`
    // returns a **Map**, not an array. Iterating that Map yields `[key, value]`
    // pairs, so every field read off a "peer" came back undefined and the
    // roster rendered as rows with no name and no id — while `lastSeen` and
    // `online` survived, because `#record` computes those locally. A failure
    // that looks like "the fields I invented are the only ones that work".
    //
    // `list` returns a Map on both the KV and SQLite backends, so reading by
    // key is the one shape that does not depend on which class is deployed.
    const stored = await this.state.storage.list({ prefix: PEER_KEY });

    const out = [];
    for (const id of live) {
      const peer = stored.get(PEER_KEY + id);
      if (peer) out.push(this.#record(peer));
    }

    // Stable ordering, so the "All Users" list does not reshuffle on every
    // refresh. The client sorts anyway; this makes diffing cheap.
    out.sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
    return out;
  }

  #record(peer) {
    return {
      id: peer.id,
      name: peer.name,
      identityPub: peer.identityPub,
      prekey: peer.prekey,
      bio: peer.bio,
      joinedAt: peer.joinedAt,
      lastSeen: Date.now(),
      online: true,
    };
  }

  async #rosterFrame() {
    return { t: "roster", peers: await this.#roster(), serverTime: Date.now() };
  }

  // -------------------------------------------------------------------------
  // WebSocket plumbing
  // -------------------------------------------------------------------------

  /**
   * The socket belonging to a peer id, or null.
   *
   * Linear over the live sockets. At this roster size that is a handful of
   * `deserializeAttachment` calls, and unlike a cached map it cannot go stale.
   */
  #socketFor(peerId) {
    if (typeof peerId !== "string" || peerId.length === 0) return null;
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.deserializeAttachment()?.id === peerId) return ws;
    }
    return null;
  }

  /** The stored record for the peer behind this socket, or null. */
  async #peerFor(ws) {
    const id = ws.deserializeAttachment()?.id;
    if (typeof id !== "string") return null;
    const peer = await this.state.storage.get(PEER_KEY + id);
    return peer ?? null;
  }

  #send(ws, frame) {
    try {
      ws.send(JSON.stringify(frame));
    } catch {
      // A socket that cannot take a frame is a dead peer. Closing it makes the
      // next roster rebuild drop it, which is what prevents ghost presence.
      //
      // Deliberately not awaited: `#send` is on the hot path for every frame to
      // every peer, and making it async to await a cleanup path that only runs
      // for already-dead sockets would cost a microtask on all of them. The
      // `.catch` keeps a storage failure here from surfacing as an unhandled
      // rejection.
      this.#close(ws).catch(() => {});
    }
  }

  /**
   * Drop a socket's storage record and broadcast the change.
   *
   * Async because the record lives in storage. Any caller that must not race
   * this has to await it — a `webSocketClose` that returns early leaves a
   * record behind, and presence is derived from sockets rather than records so
   * the stale record is harmless but still consumes space.
   */
  async #close(ws) {
    try {
      const id = ws.deserializeAttachment()?.id;
      if (typeof id === "string") {
        await this.state.storage.delete(PEER_KEY + id);
        this.#queuePresence();
      }
    } catch {
      /* nothing to clean up */
    }
    try {
      ws.close();
    } catch {
      /* already closed */
    }
  }
}

// ---------------------------------------------------------------------------
// Static client
// ---------------------------------------------------------------------------

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
};

async function serveClient(url) {
  if (url.pathname !== "/") {
    return new Response("not found", { status: 404 });
  }

  // Embedded so a deployment is a single self-contained unit — no KV binding,
  // no R2 bucket, no extra configuration. The client is ~9 KB.
  return new Response(CLIENT_HTML, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      // The client only ever talks to its own origin.
      "content-security-policy":
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self' wss:",
    },
  });
}

function json(value) {
  return new Response(JSON.stringify(value), {
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

const CLIENT_HTML = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>HUSH</title>
<style>
:root{--bg:#0B0C10;--surface:#13151C;--elev:#1B1E27;--accent:#7C5CFF;--text:#EDEFF5;--dim:#9AA0B4;--faint:#6B7183;--on:#3DDC97}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
#app{display:flex;flex-direction:column;height:100%;max-width:720px;margin:0 auto}
header{display:flex;align-items:center;gap:12px;padding:16px 20px;border-bottom:1px solid var(--elev);position:sticky;top:0;background:var(--bg);z-index:2}
.mark{width:34px;height:34px;border-radius:10px;background:var(--accent);display:grid;place-items:center;font-weight:700;color:#fff}
.title{font-weight:700;letter-spacing:2px}.sub{font-size:12px;color:var(--faint)}
.status{margin-left:auto;font-size:12px;color:var(--dim);display:flex;align-items:center;gap:6px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--faint)}.dot.on{background:var(--on)}
main{flex:1;overflow-y:auto}
.banner{padding:9px 20px;font-size:13px;background:rgba(255,92,104,.12);color:#FF5C68}.hidden{display:none}
.list{list-style:none;margin:0;padding:0}
.row{display:flex;align-items:center;gap:14px;padding:11px 20px;cursor:pointer;border-bottom:1px solid rgba(255,255,255,.03)}
.row:hover{background:var(--surface)}
.avatar{width:42px;height:42px;border-radius:50%;display:grid;place-items:center;font-weight:700;background:rgba(124,92,255,.18);color:#a68fff;border:1px solid rgba(124,92,255,.35);position:relative;flex-shrink:0}
.avatar .p{position:absolute;right:-1px;bottom:-1px;width:11px;height:11px;border-radius:50%;background:var(--faint);border:2px solid var(--bg)}
.avatar .p.on{background:var(--on)}
.meta{min-width:0;flex:1}.name{font-weight:600}.preview{font-size:13px;color:var(--dim);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.empty{padding:56px 40px;text-align:center;color:var(--dim)}.empty h3{color:var(--text);margin:0 0 8px;font-size:16px}
footer{display:flex;gap:10px;padding:12px 16px;border-top:1px solid var(--elev);background:var(--surface)}
input{flex:1;padding:12px 16px;border-radius:22px;background:var(--elev);border:1px solid transparent;color:var(--text);font:inherit;outline:none;min-width:0}
input:focus{border-color:var(--accent)}
button{padding:12px 20px;border-radius:22px;border:0;background:var(--accent);color:#fff;font:inherit;font-weight:600;cursor:pointer}
button:disabled{background:var(--elev);color:var(--faint);cursor:default}
.setup{padding:40px 28px}.setup label{display:block;font-size:12px;color:var(--faint);margin:18px 0 6px}
.setup input{width:100%;border-radius:12px}.setup button{width:100%;margin-top:22px;border-radius:12px;height:50px}
.note{font-size:13px;color:var(--faint);margin-top:22px;line-height:1.6}
.warn{margin-top:24px;padding:12px 14px;border-radius:12px;background:rgba(255,176,32,.1);color:#ffb020;font-size:13px;line-height:1.5}
</style></head><body>
<div id="app">
<header><div class="mark">H</div><div><div class="title">HUSH</div><div class="sub" id="who">not connected</div></div>
<div class="status"><span class="dot" id="dot"></span><span id="state">offline</span></div></header>
<div class="banner hidden" id="banner"></div>
<main id="main"></main>
<footer id="composer" class="hidden"><input id="text" placeholder="Message" autocomplete="off"><button id="send" disabled>Send</button></footer>
</div>
<script type="module">
/*
 * Minimal relay client, same frames as the Android app.
 *
 * This build obfuscates with a per-device XOR key. It is NOT the X3DH +
 * Double Ratchet session the Android client implements, and the page says so
 * rather than implying otherwise. Use it to test a relay's behaviour, never to
 * carry anything you would mind reading.
 */
const $ = (id) => document.getElementById(id);
const S = { ws:null, name:localStorage.getItem('hush.name')||'', id:null,
            peers:new Map(), active:null, messages:new Map(), backoff:1000 };
const b64 = {
  enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))),
  dec: (str) => Uint8Array.from(atob(str), c => c.charCodeAt(0)),
};
const key = crypto.getRandomValues(new Uint8Array(32));
const seal = (p) => { const o = new Uint8Array(p.length); for (let i=0;i<p.length;i++) o[i] = p[i] ^ key[i%32]; return o; };
const open = (c) => { const o = new Uint8Array(c.length); for (let i=0;i<c.length;i++) o[i] = c[i] ^ key[i%32]; return o; };
const prekey = () => JSON.stringify({
  identity: b64.enc(key), signingKey: b64.enc(crypto.getRandomValues(new Uint8Array(32))),
  prekey: b64.enc(crypto.getRandomValues(new Uint8Array(32))),
  signedPrekey: { key: b64.enc(crypto.getRandomValues(new Uint8Array(32))),
                  sig: b64.enc(crypto.getRandomValues(new Uint8Array(64))), counter: 0 },
});

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const initials = (n) => String(n||'?').trim().split(/\\s+/).slice(0,2).map(w=>w[0]).join('').toUpperCase() || '?';

function setState(label, on){ $('state').textContent=label; $('dot').classList.toggle('on',on); $('who').textContent=on?S.name:'not connected'; }
function banner(t){ const b=$('banner'); b.textContent=t; b.classList.remove('hidden'); }

function setup(){
  $('main').innerHTML = \`
    <div class="setup"><h2>Connect to a relay</h2>
    <label for="relay">Relay address</label><input id="relay" value="\${location.origin.replace(/^http/,'ws')}/ws">
    <label for="nick">Display name</label><input id="nick" value="\${S.name}" placeholder="Alex" maxlength="32">
    <button id="go">Continue</button>
    <p class="note">HUSH has no accounts. The display name is a label, not an identity — anyone can claim any name.</p>
    <div class="warn"><strong>Development client.</strong> This web build obfuscates traffic but does not implement the X3DH + Double Ratchet session the Android app uses. Use the Android app when message content matters.</div>
    </div>\`;
  $('go').onclick = () => {
    const n = $('nick').value.trim(), r = $('relay').value.trim();
    if (!n || !r) return;
    localStorage.setItem('hush.name', n); localStorage.setItem('hush.relay', r);
    S.name = n; connect(r);
  };
}

function roster(){
  const list = [...S.peers.values()].sort((a,b)=>Number(b.online)-Number(a.online)||a.name.localeCompare(b.name));
  $('main').innerHTML = list.length ? \`<ul class="list">\${list.map(p=>\`
    <li class="row" data-id="\${p.id}"><div class="avatar">\${esc(initials(p.name))}<span class="p \${p.online?'on':''}"></span></div>
    <div class="meta"><div class="name">\${esc(p.name)}</div><div class="preview">\${p.online?'online':'offline'}</div></div></li>\`).join('')}</ul>\`
    : \`<div class="empty"><h3>Nobody else is here yet</h3><p>Open this page elsewhere against the same relay and they will appear here.</p></div>\`;
  $('main').querySelectorAll('.row').forEach(r => r.onclick = () => openChat(r.dataset.id));
}

function chat(){
  const peer = S.peers.get(S.active); if (!peer) return;
  const t = S.messages.get(peer.id) || [];
  $('main').innerHTML = \`<ul class="list">
    <li class="row" id="back">← <div class="name">\${esc(peer.name)}</div></li>
    \${t.length ? t.map(m=>\`<li class="row" style="cursor:default"><div class="avatar" style="width:28px;height:28px;font-size:11px">\${esc(initials(m.mine?'me':peer.name))}</div>
      <div class="meta"><div class="preview" style="white-space:pre-wrap;color:var(--text)">\${esc(m.text)}</div></div></li>\`).join('')
      : '<li class="row" style="cursor:default"><div class="preview">No messages yet.</div></li>'}</ul>\`;
  $('back').onclick = () => { S.active=null; $('composer').classList.add('hidden'); roster(); };
}
function openChat(id){ S.active=id; $('composer').classList.remove('hidden'); chat(); }

function applyRoster(peers){ S.peers = new Map(peers.filter(p=>p.id!==S.id).map(p=>[p.id,p])); }
const send = (o) => { if (S.ws?.readyState === WebSocket.OPEN) S.ws.send(JSON.stringify(o)); };

function connect(relay){
  const ws = new WebSocket(relay); S.ws = ws;
  ws.onopen = () => { S.backoff = 1000; send({t:'hello',name:S.name,identityPub:b64.enc(key),prekey:prekey()}); };
  ws.onmessage = (ev) => {
    const f = JSON.parse(ev.data);
    if (f.t === 'welcome') { S.id = f.you.id; setState('connected',true); $('banner').classList.add('hidden');
      applyRoster(f.peers); $('composer').classList.add('hidden'); roster(); }
    else if (f.t === 'roster') { applyRoster(f.peers); S.active ? chat() : roster(); }
    else if (f.t === 'msg') { if (f.kind === 'rtc') return;
      const text = new TextDecoder().decode(open(b64.dec(f.envelope)));
      const th = S.messages.get(f.from) || []; th.push({text,mine:false}); S.messages.set(f.from, th);
      S.active === f.from ? chat() : roster(); }
    else if (f.t === 'error') banner(f.message || 'relay error');
  };
  ws.onclose = () => { setState('offline',false);
    // Backoff with jitter so every client dropped at once does not return in
    // lockstep and re-create the same load spike.
    const d = S.backoff/2 + Math.random()*(S.backoff/2); S.backoff = Math.min(60000, S.backoff*2);
    setTimeout(() => connect(relay), d); };
  ws.onerror = () => ws.close();
}

$('text').addEventListener('input', e => { $('send').disabled = !e.target.value.trim(); });
$('text').addEventListener('keydown', e => { if (e.key === 'Enter') $('send').click(); });
$('send').onclick = () => {
  const input = $('text'), text = input.value.trim();
  if (!text || !S.active) return;
  send({ t:'send', to:S.active, envelope: b64.enc(seal(new TextEncoder().encode(text))) });
  const th = S.messages.get(S.active) || []; th.push({text,mine:true}); S.messages.set(S.active, th);
  input.value=''; $('send').disabled=true; chat();
};

if (S.name && localStorage.getItem('hush.relay')) connect(localStorage.getItem('hush.relay'));
else { setup(); setState('offline',false); }
</script></body></html>`;
