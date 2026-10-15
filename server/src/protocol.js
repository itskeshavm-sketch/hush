/**
 * HUSH relay — wire protocol.
 *
 * Hard rule enforced here: the relay is a *dumb pipe*. Every field below is
 * either routing metadata (who -> whom) or an opaque base64 blob it has no
 * key to read. If you find yourself wanting to add a `body` field to a
 * message envelope, you are about to destroy the entire security model.
 *
 * What the relay can see, unavoidably:
 *   - that peer A connected, under some display name
 *   - that A sent a ~N-byte ciphertext to B
 *   - timing and size metadata
 * That is the metadata floor of any router. Messages are E2E; routing is not.
 */

export const PROTOCOL_VERSION = 1;

/** Max plaintext-equivalent ciphertext size we will buffer per message (64 KiB). */
export const MAX_ENVELOPE_BYTES = 64 * 1024;
/** C0 and C1 control characters. */
const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/g;

/** Zero-width joiners/separators, bidi embedding and overrides, and BOM. */
const INVISIBLE_CHARS = /[\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]/g;

/** Max display-name length after unicode trim. */
export const MAX_NAME_CHARS = 32;
/** Fan-out ceiling for a single room broadcast. */
export const MAX_ROSTER = 5000;

// ---------------------------------------------------------------------------
// Client -> Server
// ---------------------------------------------------------------------------

/** @typedef {'hello'|'roster'|'send'|'delete'|'typing'|'rtc'|'profile'|'ping'} ClientFrame */

export const CLIENT_FRAMES = Object.freeze([
  'hello',
  'roster',
  'send',
  'delete',
  'typing',
  'rtc',
  'profile',
  'ping',
]);

/** Messages that are legal before `hello` completes. */
export const PRE_HELLO_FRAMES = Object.freeze(['hello', 'ping']);

// ---------------------------------------------------------------------------
// Server -> Client
// ---------------------------------------------------------------------------

/** @typedef {'welcome'|'roster'|'presence'|'msg'|'delete'|'typing'|'rtc'|'error'|'pong'} ServerFrame */

export const SERVER_FRAMES = Object.freeze([
  'welcome',
  'roster',
  'presence',
  'msg',
  'delete',
  'typing',
  'rtc',
  'error',
  'pong',
]);

export const ERROR_CODES = Object.freeze({
  BAD_JSON: 'bad_json',
  UNKNOWN_FRAME: 'unknown_frame',
  NOT_READY: 'not_ready',
  ALREADY_JOINED: 'already_joined',
  BAD_NAME: 'bad_name',
  BAD_FRAME: 'bad_frame',
  OVERSIZE: 'oversize',
  UNKNOWN_PEER: 'unknown_peer',
  SELF_TARGET: 'self_target',
  RATE_LIMIT: 'rate_limit',
  SLOW_CONSUMER: 'slow_consumer',
});

/**
 * Parse a raw inbound socket payload into a validated frame.
 * Returns `{ ok: true, frame }` or `{ ok: false, code, message }` — never throws,
 * because a throwing parser on a public socket is a free DoS.
 *
 * @param {string|Buffer} raw
 * @param {{ hasJoined: boolean, selfId: string|null }} ctx
 */
export function parseInbound(raw, ctx) {
  if (raw.length > MAX_ENVELOPE_BYTES * 2) {
    return fail(ERROR_CODES.OVERSIZE, 'frame exceeds 128 KiB');
  }

  let msg;
  try {
    msg = JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8'));
  } catch {
    return fail(ERROR_CODES.BAD_JSON, 'payload is not valid JSON');
  }

  if (msg === null || typeof msg !== 'object' || Array.isArray(msg)) {
    return fail(ERROR_CODES.BAD_FRAME, 'frame must be a JSON object');
  }

  const type = msg.t;
  if (typeof type !== 'string' || !CLIENT_FRAMES.includes(type)) {
    return fail(ERROR_CODES.UNKNOWN_FRAME, `unknown frame type ${String(type).slice(0, 24)}`);
  }

  if (!ctx.hasJoined && !PRE_HELLO_FRAMES.includes(type)) {
    return fail(ERROR_CODES.NOT_READY, 'send hello first');
  }

  switch (type) {
    case 'hello':
      return validateHello(msg);
    case 'send':
    case 'rtc':
      return validateSend(msg, ctx);
    case 'delete':
      return validateDelete(msg, ctx);
    case 'typing':
      return validateTyping(msg, ctx);
    case 'profile':
      return validateProfile(msg);
    case 'roster':
    case 'ping':
      return { ok: true, frame: { t: type } };
    default:
      return fail(ERROR_CODES.UNKNOWN_FRAME, 'unreachable');
  }
}

// ---------------------------------------------------------------------------
// Per-frame validators
// ---------------------------------------------------------------------------

function validateHello(msg) {
  const name = sanitizeName(msg.name);
  if (name === null) {
    return fail(
      ERROR_CODES.BAD_NAME,
      `display name must be ${MAX_NAME_CHARS} printable chars or fewer`,
    );
  }

  const identityPub = msg.identityPub;
  if (!isBase64(identityPub, 64)) {
    return fail(ERROR_CODES.BAD_FRAME, 'identityPub must be base64 X25519 public key');
  }

  // Bundle public prekeys for first-contact encryption. Optional: a client that
  // has never received a prekey bundle yet still gets a usable identity for
  // the roster, it just cannot be messaged until it publishes its own.
  const prekey = msg.prekey === undefined ? null : msg.prekey;
  if (prekey !== null && !isPrekeyBundle(prekey)) {
    return fail(ERROR_CODES.BAD_FRAME, 'prekey must be a valid X25519 prekey bundle');
  }

  return {
    ok: true,
    frame: { t: 'hello', name, identityPub, prekey, joined: Boolean(msg.joinedAt) },
  };
}

function validateSend(msg, ctx) {
  const to = msg.to;
  if (typeof to !== 'string' || to.length === 0 || to.length > 64) {
    return fail(ERROR_CODES.BAD_FRAME, 'to must be a peer id');
  }
  if (to === ctx.selfId) {
    return fail(ERROR_CODES.SELF_TARGET, 'cannot send to yourself');
  }

  const envelope = msg.envelope;
  if (!isBase64(envelope, MAX_ENVELOPE_BYTES)) {
    return fail(
      ERROR_CODES.BAD_FRAME,
      'envelope must be base64 and at most 64 KiB',
    );
  }

  // `kind` is routing metadata only — it tells the client whether to hand the
  // sealed blob to the chat pipeline or the WebRTC signaling pipeline. It is
  // derived from the frame type so a client cannot label a payload as
  // something it is not, and it reveals nothing about the ciphertext.
  //
  // The frame's own `t` stays a *client* frame type (`send` / `rtc`), because
  // `parseInbound` output is dispatched by that field.
  const isRtc = msg.t === 'rtc';
  const kind = isRtc ? 'rtc' : 'msg';

  return { ok: true, frame: { t: isRtc ? 'rtc' : 'send', to, envelope, kind } };
}

function validateDelete(msg, ctx) {
  if (typeof msg.msgId !== 'string' || msg.msgId.length === 0 || msg.msgId.length > 64) {
    return fail(ERROR_CODES.BAD_FRAME, 'msgId required');
  }
  const to = msg.to;
  if (typeof to !== 'string' || to.length === 0 || to.length > 64) {
    return fail(ERROR_CODES.BAD_FRAME, 'to must be a peer id');
  }
  if (to === ctx.selfId) {
    return fail(ERROR_CODES.SELF_TARGET, 'cannot address yourself');
  }
  return {
    ok: true,
    frame: { t: 'delete', to, msgId: msg.msgId, forAll: msg.forAll === true },
  };
}

function validateTyping(msg, ctx) {
  if (typeof msg.to !== 'string' || msg.to.length === 0 || msg.to.length > 64) {
    return fail(ERROR_CODES.BAD_FRAME, 'to must be a peer id');
  }
  return { ok: true, frame: { t: 'typing', to: msg.to, state: msg.state === true } };
}

function validateProfile(msg) {
  const bio = typeof msg.bio === 'string' ? trimTo(msg.bio, 160) : '';
  // Avatar is a data URL or remote URL the client renders. The relay validates
  // shape only; it never fetches it (that would make it an SSRF honeypot).
  let avatar = '';
  if (typeof msg.avatar === 'string') {
    const a = msg.avatar.trim();
    if (a.startsWith('data:image/')) {
      avatar = a.length <= 512 * 1024 ? a : '';
    } else if (/^https:\/\//i.test(a)) {
      avatar = a.length <= 512 ? a : '';
    }
  }
  return { ok: true, frame: { t: 'profile', bio, avatar } };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Normalise a display name.
 *
 * Two classes of codepoint are removed outright:
 *   - C0/C1 controls. Lets an attacker put a newline or an ANSI escape into
 *     your roster list and reformat the UI around it.
 *   - Zero-width and bidi-override codepoints. This is the name-spoofing
 *     set: they render as nothing but reorder what a reader actually sees,
 *     so a peer called "nimda" can be made to display as "admin".
 *
 * Written with explicit unicode escapes on purpose. Literal invisible
 * characters in a source file are unreviewable and get silently mangled by
 * editors, formatters and copy-paste.
 *
 * @param {unknown} input
 * @returns {string|null} cleaned name, or null if unusable
 */
export function sanitizeName(input) {
  if (typeof input !== 'string') return null;
  const cleaned = input
    .replace(CONTROL_CHARS, '')
    .replace(INVISIBLE_CHARS, '')
    .trim();
  if (cleaned.length === 0 || cleaned.length > MAX_NAME_CHARS) return null;
  return cleaned;
}
function trimTo(s, n) {
  return s.length <= n ? s : s.slice(0, n);
}

/**
 * Strict base64 check with a real decoded-size limit.
 *
 * Three guards, because each catches something the others miss:
 *   1. Alphabet   — rejects url-safe (`-_`) and any stray character.
 *   2. Length     — rejects truncated input that is not a multiple of 4.
 *   3. Decoded    — the actual byte count, checked against `maxBytes`.
 *   4. Round-trip — `Buffer.from` ignores non-alphabet characters instead of
 *                  throwing, so a crafted string can decode to something other
 *                  than its length implies. Re-encoding and comparing catches
 *                  both that and non-canonical trailing bits ('aa==' vs 'AA==').
 *
 * @param {unknown} v
 * @param {number} maxBytes inclusive ceiling on the *decoded* length
 */
export function isBase64(v, maxBytes) {
  if (typeof v !== 'string' || v.length === 0) return false;
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(v)) return false;
  if (v.length % 4 !== 0) return false;

  let decoded;
  try {
    decoded = Buffer.from(v, 'base64');
  } catch {
    return false;
  }

  // Guard 3. The pre-decode arithmetic on encoded length is only an
  // optimisation hint and is deliberately *not* relied on: encoding padding
  // means encoded length does not determine decoded length.
  if (decoded.length > maxBytes) return false;

  // Guard 4.
  return decoded.toString('base64') === v;
}

function isPrekeyBundle(b) {
  return (
    b !== null &&
    typeof b === 'object' &&
    isBase64(b.identity, 64) &&
    isBase64(b.prekey, 64) &&
    typeof b.signedPrekey === 'object' &&
    b.signedPrekey !== null &&
    isBase64(b.signedPrekey.key, 64) &&
    typeof b.signedPrekey.sig === 'string' &&
    b.signedPrekey.sig.length <= 128 &&
    Number.isInteger(b.signedPrekey.counter) &&
    b.signedPrekey.counter >= 0
  );
}

export function fail(code, message) {
  return { ok: false, code, message };
}

/** Encode a server frame. Kept here so both hub and tests share one encoder. */
export function encode(frame) {
  return JSON.stringify(frame);
}