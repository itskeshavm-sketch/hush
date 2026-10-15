/**
 * HUSH relay — entrypoint.
 *
 * Zero-retention by construction: there is no store, no queue, no cache. This
 * file wires a TLS listener to a WebSocket endpoint and hands every connection
 * to a Hub. The Hub's only job is "peer A sent bytes, give them to peer B".
 *
 * Logging policy (this is the part that matters if you ever get subpoenaed):
 *   LOGGED:   connection open/close, peer count, capacity events, TLS errors.
 *   NOT LOGGED: display names, message bytes, sizes, timings, targets, IPs.
 * A display name in a log file is a directory entry that outlives every message
 * it ever accompanied, so names never touch stdout.
 */

// node:https, not node:http. `server.listen()` has no TLS parameter — a plain
// http.Server handed a {key, cert} pair just speaks cleartext, which fails at
// handshake time with a baffling "wrong version number" from every client.
import { createServer as createHttpsServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { WebSocketServer } from 'ws';

import { Hub } from './hub.js';
import { loadTls } from './tls.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.HUSH_PORT ?? 8443);
const HOST = process.env.HUSH_HOST ?? '0.0.0.0';
const HEALTH_PATH = '/healthz';

const started = Date.now();

const healthBody = () =>
  JSON.stringify({
    service: 'hush-relay',
    protocol: 1,
    uptimeSeconds: Math.floor((Date.now() - started) / 1000),
    peers: hub.size,
    note: 'zero-retention relay — no message content, no history, no accounts',
  });

/**
 * Static client bundle. HUSH ships the web client next to the relay so a single
 * self-hosted process is a complete deployment. Android talks to this same
 * WebSocket endpoint, so the protocol is exercised by two clients.
 */
function serveStatic(req, res) {
  const rel = (req.url ?? '/').split('?')[0];
  const file = rel === '/' ? '/index.html' : rel;

  // Reject traversal before touching the filesystem.
  if (!/^\/[A-Za-z0-9._\-/]*$/.test(file) || file.includes('..')) {
    res.writeHead(400).end('bad path');
    return;
  }

  const abs = join(HERE, '..', 'public', file);
  try {
    const data = readFileSync(abs);
    const type = MIME[file.slice(file.lastIndexOf('.'))] ?? 'application/octet-stream';
    res.writeHead(200, {
      'content-type': type,
      'cache-control': 'no-store',
      // The web client is a thin shim; lock it down regardless of TLS.
      'content-security-policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self' wss:",
      'referrer-policy': 'no-referrer',
      'x-content-type-options': 'nosniff',
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
};

const hub = new Hub();

// TLS material is loaded before the server is constructed because an https
// server takes its key/cert at construction time, not at listen time.
const tls = await loadTls({ domain: process.env.HUSH_DOMAIN });

const httpServer = createHttpsServer(
  { key: tls.key, cert: tls.cert, minVersion: 'TLSv1.2' },
  (req, res) => {
    const path = (req.url ?? '/').split('?')[0];

    // Health endpoint must answer before auth or presence matters, so an
    // operator can probe a self-signed relay with curl -k.
    if (path === HEALTH_PATH) {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(healthBody());
      return;
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { allow: 'GET, HEAD' }).end();
      return;
    }

    serveStatic(req, res);
  },
);

// Bound so a slow-loris client cannot hold a socket open forever.
httpServer.headersTimeout = 20_000;
httpServer.requestTimeout = 30_000;
httpServer.keepAliveTimeout = 10_000;

const wss = new WebSocketServer({
  server: httpServer,
  path: '/ws',
  maxPayload: 128 * 1024,
  perMessageDeflate: false, // ciphertext does not compress; skip the CPU and the CRIME surface
  clientTracking: true,
});

let openSockets = 0;

wss.on('connection', (socket) => {
  openSockets++;
  hub.attach(socket);

  socket.once('close', () => {
    openSockets--;
    if (hub.size === 0) log('no peers online');
  });

  socket.once('error', () => {
    /* transport-level; Hub.detach runs on close */
  });
});

wss.on('error', (err) => log(`websocket server error: ${err.message}`));

// Drop half-open connections so presence is not a lie.
const heartbeat = setInterval(() => {
  for (const socket of wss.clients) {
    if (socket.isAlive === false) {
      socket.terminate();
      continue;
    }
    socket.isAlive = false;
    socket.ping();
  }
}, 30_000);
heartbeat.unref?.();

wss.on('connection', (socket) => {
  socket.isAlive = true;
  socket.on('pong', () => {
    socket.isAlive = true;
  });
});

// --- boot -----------------------------------------------------------------

httpServer.listen({ port: PORT, host: HOST }, () => {
  const shown = HOST === '0.0.0.0' ? 'localhost' : HOST;
  log('');
  log('  HUSH relay');
  log(`  wss://${shown}:${PORT}/ws`);
  log(`  https://${shown}:${PORT}${HEALTH_PATH}`);
  log(`  fingerprint ${tls.fingerprint}${tls.selfSigned ? '  (self-signed)' : ''}`);
  log(`  online at boot: ${hub.size}`);
  log('');
});

httpServer.on('error', (err) => {
  log(`listen failed: ${err.message}`);
  process.exit(1);
});

function log(line) {
  const ts = new Date().toISOString().replace('T', ' ').slice(0, 19);
  process.stdout.write(`${ts}  ${line}\n`);
}

// --- shutdown --------------------------------------------------------------

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log(`${signal} — draining ${hub.size} peers`);
  clearInterval(heartbeat);
  hub.shutdown();
  wss.close();
  httpServer.close(() => process.exit(0));
  // Do not let a stuck socket hold the process open. Nothing is persisted, so
  // a hard exit costs us exactly nothing.
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('uncaughtException', (err) => {
  log(`uncaught: ${err.message}`);
  log(err.stack ?? '');
});