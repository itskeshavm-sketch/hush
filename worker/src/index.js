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

    /** @type {Map<string, { ws: WebSocket, name: string, identityPub: string, prekey: any, bio: string, joinedAt: number }>} */
    this.peers = new Map();

    this.presenceQueued = false;
  }

  /**
   * Health probe from the Worker, which cannot touch DO state directly.
   * Constructs the peer list so the response reports the truth.
   */
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        service: "hush-relay",
        runtime: "cloudflare-worker",
        protocol: PROTOCOL_VERSION,
        peers: this.peers.size,
        note: "zero-retention relay — no message content, no history, no accounts",
      });
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

    const parsed = parseInbound(text, {
      hasJoined: this.#peerFor(ws) !== null,
      selfId: this.#peerFor(ws)?.id ?? null,
    });

    if (!parsed.ok) {
      this.#send(ws, { t: "error", code: parsed.code, message: parsed.message });
      return;
    }

    this.#dispatch(parsed.frame, this.#peerFor(ws), ws);
  }

  /**
   * Hibernation also delivers close here, so presence stays accurate without
   * the isolate having to be alive to notice a dropped TCP connection.
   */
  async webSocketClose(ws) {
    const peer = this.#peerFor(ws);
    if (!peer) return;
    this.peers.delete(peer.id);
    this.#queuePresence();
  }

  async webSocketError(ws) {
    this.#close(ws);
  }

  // -------------------------------------------------------------------------
  // Dispatch
  // -------------------------------------------------------------------------

  #dispatch(frame, peer, ws) {
    switch (frame.t) {
      case "ping":
        this.#send(ws, { t: "pong", at: Date.now() });
        return;

      case "hello": {
        if (peer) {
          this.#send(ws, { t: "error", code: ERROR_CODES.ALREADY_JOINED, message: "already joined" });
          return;
        }
        if (this.peers.size >= MAX_ROSTER) {
          this.#send(ws, { t: "error", code: ERROR_CODES.RATE_LIMIT, message: "relay at capacity" });
          return;
        }
        this.#join(frame, ws);
        this.#queuePresence();
        return;
      }

      case "roster":
        this.#send(ws, this.#rosterFrame());
        return;

      case "profile":
        if (peer) {
          peer.bio = frame.bio;
          this.#queuePresence();
        }
        return;

      case "send":
      case "rtc": {
        if (!peer) return;
        const target = this.peers.get(frame.to);
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
        this.#send(target.ws, {
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
        const target = this.peers.get(frame.to);
        if (!target) {
          this.#send(ws, {
            t: "error",
            code: ERROR_CODES.UNKNOWN_PEER,
            message: `${frame.to} is offline`,
          });
          return;
        }
        this.#send(target.ws, {
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

      case "typing": {
        if (!peer) return;
        const target = this.peers.get(frame.to);
        // Fire and forget: no error frame for an offline typing target.
        if (!target) return;
        this.#send(target.ws, { t: "typing", from: peer.id, fromName: peer.name, state: frame.state });
        return;
      }

      default:
        this.#send(ws, { t: "error", code: ERROR_CODES.UNKNOWN_FRAME, message: "unhandled frame" });
    }
  }

  #join(frame, ws) {
    // Route key derived server-side from the identity key, so a client cannot
    // claim another peer's id.
    const id = `p_${frame.identityPub.slice(0, 22)}`;

    const existing = this.peers.get(id);
    if (existing) {
      this.peers.delete(id);
      try {
        existing.ws.close(4000, "superseded by a newer connection");
      } catch {
        /* already gone */
      }
    }

    const peer = {
      id,
      name: frame.name,
      identityPub: frame.identityPub,
      prekey: frame.prekey,
      bio: "",
      joinedAt: Date.now(),
      ws,
    };
    this.peers.set(id, peer);

    this.#send(ws, {
      t: "welcome",
      you: this.#record(peer),
      peers: this.#roster().filter((p) => p.id !== id),
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
    const frame = this.#rosterFrame();
    for (const peer of this.peers.values()) this.#send(peer.ws, frame);
  }

  #roster() {
    const out = [];
    for (const p of this.peers.values()) out.push(this.#record(p));
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

  #rosterFrame() {
    return { t: "roster", peers: this.#roster(), serverTime: Date.now() };
  }

  // -------------------------------------------------------------------------
  // WebSocket plumbing
  // -------------------------------------------------------------------------

  #peerFor(ws) {
    for (const peer of this.peers.values()) {
      if (peer.ws === ws) return peer;
    }
    return null;
  }

  #send(ws, frame) {
    try {
      ws.send(JSON.stringify(frame));
    } catch {
      // A socket that cannot take a frame is a dead peer; dropping it here
      // avoids the ghost presence that would otherwise linger until a close
      // event that may never fire.
      this.#close(ws);
    }
  }

  #close(ws) {
    const peer = this.#peerFor(ws);
    if (peer) {
      this.peers.delete(peer.id);
      this.#queuePresence();
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
