/**
 * HUSH relay hub — presence, routing, fan-out.
 *
 * Deliberately boring: a Map of sockets, a Map of peer-id -> peer record, and
 * a routing switch. There is no database, no message log, no history, no
 * queue-on-disconnect. A message addressed to an offline peer is *dropped* and
 * the sender is told so. That is the whole privacy story: there is nowhere for
 * a message to sit and rot.
 *
 * Lifecycle invariants this module guarantees:
 *   1. `peers` and `bySocket` are exact inverses of each other at all times.
 *   2. A peer id is derived from the device identity key, not from the display
 *      name, so two people named "Alex" are two distinct peers.
 *   3. Nothing a peer sends is retained after the send loop processes it.
 */

import { randomUUID } from 'node:crypto';
import {
  ERROR_CODES,
  MAX_ROSTER,
  encode,
  fail,
  parseInbound,
  sanitizeName,
} from './protocol.js';

/** Token-bucket refill rate and capacity, in frames per second and frames. */
const RATE_BUCKET_CAPACITY = 120;
const RATE_BUCKET_REFILL_PER_SEC = 40;

/** Frames we will queue for one socket before declaring it a slow consumer. */
const SEND_HIGH_WATERMARK = 256;

/** How often we broadcast presence churn to everyone. */
const PRESENCE_BROADCAST_DEBOUNCE_MS = 120;

export class Hub {
  /** @param {{ now?: () => number, id?: () => string }} [deps] */
  constructor(deps = {}) {
    this.now = deps.now ?? Date.now;
    this.nextId = deps.id ?? randomUUID;

    /** @type {Map<string, Peer>} peerId -> peer */
    this.peers = new Map();
    /** @type {Map<object, Peer>} socket -> peer */
    this.bySocket = new Map();

    this.presenceTimer = null;
    this.stats = { accepted: 0, dropped: 0, rejected: 0 };
  }

  get size() {
    return this.peers.size;
  }

  // -------------------------------------------------------------------------
  // Socket lifecycle
  // -------------------------------------------------------------------------

  /**
   * Attach a socket. The returned handler is intentionally a closure so the
   * socket cannot be paired with a stale peer record after a reconnect.
   *
   * @param {object} socket an object exposing `send(string)`, `close()`, `on(event, cb)`
   */
  attach(socket) {
    /** @type {Peer|null} */
    let peer = null;
    let tokens = RATE_BUCKET_CAPACITY;
    let lastRefill = this.now();

    const send = (frame) => {
      if (socket.readyState !== 1 /* OPEN */) return false;
      const queued = socket.bufferedAmount ?? 0;
      // Hard drop rather than unbounded buffering: a client that cannot keep up
      // must not be able to grow this relay's heap without limit.
      if (queued > 4 * 1024 * 1024) return false;
      try {
        socket.send(encode(frame));
        return true;
      } catch {
        return false;
      }
    };

    socket.on('message', (data, isBinary) => {
      const nowMs = this.now();
      const elapsed = Math.max(0, (nowMs - lastRefill) / 1000);
      tokens = Math.min(RATE_BUCKET_CAPACITY, tokens + elapsed * RATE_BUCKET_REFILL_PER_SEC);
      lastRefill = nowMs;

      if (tokens < 1) {
        this.stats.rejected++;
        send({ t: 'error', code: ERROR_CODES.RATE_LIMIT, message: 'slow down' });
        return;
      }
      tokens -= 1;

      if (isBinary) {
        this.stats.rejected++;
        send({ t: 'error', code: ERROR_CODES.BAD_JSON, message: 'binary frames not supported' });
        return;
      }

      const parsed = parseInbound(data, { hasJoined: peer !== null, selfId: peer?.id ?? null });
      if (!parsed.ok) {
        this.stats.rejected++;
        send({ t: 'error', code: parsed.code, message: parsed.message });
        return;
      }

      this.#dispatch(parsed.frame, peer, send, socket, (created) => {
        peer = created;
        this.bySocket.set(socket, created);
      });
    });

    socket.on('close', () => this.detach(socket));
    socket.on('error', () => this.detach(socket));

    return { isJoined: () => peer !== null, peerId: () => peer?.id ?? null };
  }

  detach(socket) {
    const peer = this.bySocket.get(socket);
    if (!peer) return;
    this.bySocket.delete(socket);
    // Guard against id reuse after a rapid reconnect/drop flap.
    if (this.peers.get(peer.id) === peer) {
      this.peers.delete(peer.id);
      this.#schedulePresence();
    }
  }

  // -------------------------------------------------------------------------
  // Frame dispatch
  // -------------------------------------------------------------------------

  #dispatch(frame, peer, send, socket, adopt) {
    switch (frame.t) {
      case 'ping':
        send({ t: 'pong', at: this.now() });
        return;

      case 'hello': {
        if (peer) {
          send({ t: 'error', code: ERROR_CODES.ALREADY_JOINED, message: 'already joined' });
          return;
        }
        if (this.peers.size >= MAX_ROSTER) {
          send({ t: 'error', code: ERROR_CODES.RATE_LIMIT, message: 'relay at capacity' });
          return;
        }
        const created = this.#join(frame, send, socket);
        if (!created) return;
        adopt(created);
        this.stats.accepted++;
        this.#schedulePresence();
        return;
      }

      // Every frame below requires an established peer.
      case 'roster':
        send(this.#rosterFrame());
        return;

      case 'profile': {
        peer.bio = frame.bio;
        peer.avatar = frame.avatar;
        peer.updatedAt = this.now();
        this.#schedulePresence();
        return;
      }

      case 'send':
      case 'rtc': {
        const target = this.peers.get(frame.to);
        if (!target) {
          this.stats.dropped++;
          send({ t: 'error', code: ERROR_CODES.UNKNOWN_PEER, message: `${frame.to} is offline` });
          return;
        }
        const out = { t: 'msg', from: peer.id, fromName: peer.name, kind: frame.kind, envelope: frame.envelope, at: this.now() };
        if (!target.deliver(out)) {
          this.stats.dropped++;
          send({ t: 'error', code: ERROR_CODES.SLOW_CONSUMER, message: 'recipient not draining its socket' });
        }
        return;
      }

      case 'delete': {
        const target = this.peers.get(frame.to);
        if (!target) {
          this.stats.dropped++;
          send({ t: 'error', code: ERROR_CODES.UNKNOWN_PEER, message: `${frame.to} is offline` });
          return;
        }
        target.deliver({ t: 'delete', by: peer.id, byName: peer.name, to: frame.to, msgId: frame.msgId, forAll: frame.forAll, at: this.now() });
        return;
      }

      case 'seen':
      case 'kept': {
        const target = this.peers.get(frame.to);
        // Receipts are fire-and-forget like typing: a receipt for an offline
        // peer is dropped, and the sender's client re-sends unacked receipts
        // when the peer reappears. Silence is correct.
        if (!target) return;
        target.deliver({ t: frame.t, by: peer.id, byName: peer.name, to: frame.to, msgId: frame.msgId, at: this.now() });
        return;
      }

      case 'reset': {
        const target = this.peers.get(frame.to);
        // Fire-and-forget like typing: erroring for an offline peer would
        // surface raw peer ids for a routine event, and the next message
        // re-establishes the session regardless.
        if (!target) return;
        target.deliver({ t: 'reset', by: peer.id, byName: peer.name, to: frame.to, at: this.now() });
        return;
      }

      case 'typing': {
        const target = this.peers.get(frame.to);
        if (!target) return; // Typing indicators are fire-and-forget; silence is correct.
        target.deliver({ t: 'typing', from: peer.id, fromName: peer.name, state: frame.state });
        return;
      }

      default:
        send({ t: 'error', code: ERROR_CODES.UNKNOWN_FRAME, message: 'unhandled frame' });
    }
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  #join(frame, send, socket) {
    // The peer id is the relay's route key. Deriving it server-side from the
    // client's identity key (rather than trusting a client-chosen id) stops a
    // client from squatting on someone else's route.
    const id = `p_${frame.identityPub.slice(0, 22)}`;

    const existing = this.peers.get(id);
    if (existing) {
      // Same device, second socket. Retire the older one so presence stays true
      // and there is never a live route pointing at a dead socket.
      this.peers.delete(id);
      if (this.bySocket.get(existing.socket) === existing) {
        this.bySocket.delete(existing.socket);
      }
      try {
        existing.socket?.close(4000, 'superseded by newer connection');
      } catch {
        /* already gone */
      }
    }

    const peer = new Peer({
      id,
      name: frame.name,
      identityPub: frame.identityPub,
      prekey: frame.prekey,
      joinedAt: this.now(),
      send,
      socket,
    });

    this.peers.set(id, peer);

    send({
      t: 'welcome',
      you: peer.publicRecord(),
      peers: this.#roster().filter((p) => p.id !== id),
      serverTime: this.now(),
      protocol: 1,
    });

    return peer;
  }

  #roster() {
    const out = [];
    for (const p of this.peers.values()) out.push(p.publicRecord());
    // Stable ordering by name so the "All Users" list does not reshuffle on
    // every roster refresh. Client sorts anyway; this makes diffing cheap.
    out.sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
    return out;
  }

  #rosterFrame() {
    return { t: 'roster', peers: this.#roster(), serverTime: this.now() };
  }

  #schedulePresence() {
    if (this.presenceTimer) return;
    this.presenceTimer = setTimeout(() => {
      this.presenceTimer = null;
      const frame = this.#rosterFrame();
      for (const p of this.peers.values()) p.deliver(frame);
    }, PRESENCE_BROADCAST_DEBOUNCE_MS);
    if (typeof this.presenceTimer.unref === 'function') this.presenceTimer.unref();
  }

  shutdown() {
    if (this.presenceTimer) clearTimeout(this.presenceTimer);
    this.presenceTimer = null;
    for (const peer of this.peers.values()) {
      try {
        peer.socket?.close(1001, 'relay shutting down');
      } catch {
        /* ignore */
      }
    }
    this.peers.clear();
    this.bySocket.clear();
  }
}

/** A connected peer. Holds nothing but routing state and its socket writer. */
class Peer {
  constructor({ id, name, identityPub, prekey, joinedAt, send, socket }) {
    this.id = id;
    this.name = name;
    this.identityPub = identityPub;
    this.prekey = prekey;
    this.bio = '';
    this.avatar = '';
    this.joinedAt = joinedAt;
    this.updatedAt = joinedAt;
    this.socket = socket;
    this._send = send;
    this.sent = 0;
  }

  /**
   * Write a frame straight to this peer's socket. Never routes back through the
   * hub — that indirection is both pointless and a recursion trap.
   * @param {object} frame
   * @returns {boolean} false if the socket would not accept the write
   */
  deliver(frame) {
    this.sent++;
    return this._send(frame) !== false;
  }

  publicRecord() {
    return {
      id: this.id,
      name: this.name,
      identityPub: this.identityPub,
      prekey: this.prekey,
      bio: this.bio,
      avatar: this.avatar,
      joinedAt: this.joinedAt,
      lastSeen: this.updatedAt,
      online: true,
    };
  }
}

export { Peer, sanitizeName, fail };