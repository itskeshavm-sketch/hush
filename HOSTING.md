# Hosting the relay, free, no card

Three options, cheapest-effort first. All three are verified against the real
protocol — the e2e suite in `server/tools/e2e.js` runs against each of them.

---

## Option 1 — Cloudflare Workers (recommended)

**Cost:** free tier. **Card required:** no.
**Uptime:** 24/7, no machine to keep awake.

This is the right answer. A Worker runs at Cloudflare's edge, gets a real
Let's Encrypt certificate for a `workers.dev` hostname, and costs nothing at
HUSH's scale. The relay is a Durable Object, so the roster is globally
consistent — two peers see each other no matter which edge either connected to.

```bash
cd worker
npm install
npx wrangler login      # opens a browser; free account, no card asked for
npx wrangler deploy
```

```
Published hush-relay
  https://hush-relay.<your-subdomain>.workers.dev
```

Put that in the app's relay field as `wss://hush-relay.<your-subdomain>.workers.dev/ws`.

Verify it:

```bash
cd ../server
node tools/e2e.js wss://hush-relay.<your-subdomain>.workers.dev/ws
```

To test locally before deploying, no account needed:

```bash
cd worker
npx wrangler dev --local
node ../server/tools/e2e.js ws://127.0.0.1:8787/ws
```

### What the free tier gives you

| | Limit | HUSH at 2 people |
|---|---|---|
| Worker requests | 100,000 / day | ~nothing |
| Durable Object requests | 1M / day | ~nothing |
| Concurrent WebSockets | unbounded on the free tier | 2 |
| Hibernation | included | yes |

Every WebSocket message counts toward the request limit. A two-person
conversation is on the order of a few hundred requests a day, so you would
need something like three hundred active conversations before this became a
problem — and if you ever do, that is a good problem to have.

### One design note worth knowing

There is exactly **one** Durable Object, not a sharded set. A DO is a
single-threaded, globally-addressable process with private storage, which is
exactly the shape of "one room, one roster, no database". Sharding would mean
inventing a distributed presence protocol to serve a two-person test.

It uses the WebSocket **Hibernation API**, so idle sockets cost storage rather
than a live isolate. Holding sockets open instead bills continuously and stops
being free almost immediately.

---

## Option 2 — Cloudflare quick tunnel

**Cost:** free. **Card required:** no. **Account required:** no.
**Uptime:** only while the tunnel process is running.

For testing when you don't want to deploy anything. This solves a blocked
port, because nothing needs to be forwarded inbound.

```bash
cloudflared tunnel --no-tls-verify --url https://localhost:8443
```

```
https://<random-words>.trycloudflare.com
```

Handy properties: the hostname changes every run, and Cloudflare issues a real
certificate for it — so the Android client skips trust-on-first-use entirely.

**The catch:** the tunnel dies when the process dies, and the hostname is
ephemeral. Fine for an afternoon of testing, useless as a deployment.

---

## Option 3 — Node relay on a box you control

```bash
cd server
npm install
HUSH_PORT=8443 node src/index.js
```

Needs the machine awake, a reachable port, and a certificate. `server/src/tls.js`
mints a self-signed one on first run, and the client pins it on first contact.

Best when you want a relay you can inspect with `tcpdump`, or you already have
a VPS.

---

## What is *not* free and not being suggested

Anything requiring a card for signup — Fly.io, Railway, DigitalOcean, Oracle
Cloud, Vultr. The Workers free tier and quick tunnels need neither, so there is
no reason to hand over a card for this.

---

## Privacy, one more time, now that it is on shared infrastructure

Running the relay on Cloudflare means a third party is in the path. That does
**not** weaken the message encryption, because the relay only ever handles
ciphertext and has no key. What it does mean:

- Cloudflare can see **who** connected **when**, and the **size** of each
  ciphertext. That is the same metadata any router sees.
- A compromised relay can attempt a man-in-the-middle. The defence is still the
  safety number, and comparing it with the other person is still the only
  thing that catches it.

If even the metadata is too much, run Option 3 on a machine you own.
