# Hosting the relay, free, no card

Four options, cheapest-effort first. All four are verified against the real
protocol — the e2e suite in `server/tools/e2e.js` runs against each of them.

```bash
node server/tools/e2e.js ws://127.0.0.1:8787/ws
```

---

## Option 0 — Plain relay on your own machine

**Cost:** nothing. **Card required:** no. **Certificate required:** no.
**Uptime:** only while the process runs.

The fastest way to see HUSH working, and the only option that needs nothing at
all — not a domain, not an account, not a certificate.

```bash
cd server
npm install
HUSH_INSECURE=1 HUSH_PORT=8787 node src/index.js
```

```
  HUSH relay
  ws://127.0.0.1:8787/ws
  http://127.0.0.1:8787/healthz

  PLAINTEXT MODE. Routing metadata is readable by anyone on the path.
  Fine on loopback (ws://127.0.0.1) — not fine on a shared network.
```

Point the app at `ws://127.0.0.1:8787/ws`. On an emulator, where the host PC is
`10.0.2.2`, use `ws://10.0.2.2:8787/ws` instead.

**Cleartext is only accepted for loopback**, and that limit is enforced twice on
purpose: `RelayUrls.isAcceptable` in the client, and the `domain-config` block
in `network_security_config.xml`. Anywhere else, `wss://` is required. Loopback
is exempt because those bytes never touch a wire — making a laptop install
openssl purely to talk to itself is the surest way to make self-hosting feel
impossible.

On a real phone, `127.0.0.1` is the phone, not your PC. For that, see
[Option 3](#option-3--node-relay-on-a-box-you-control), which covers reaching a
PC over Wi-Fi with a self-signed certificate and no domain.

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

Your relay URL will be:

```
wss://<worker-name>.<your-subdomain>.workers.dev/ws
```

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

### Reaching a PC over Wi-Fi from a real phone

No domain and no CA required. Give the relay your PC's LAN address and it puts
an `IP:` SAN in the generated certificate:

```bash
HUSH_DOMAIN=192.168.1.50 node src/index.js
```

```
  wss://192.168.1.50:8443/ws
  fingerprint A1:B2:C3:...  (self-signed)
```

Enter `wss://192.168.1.50:8443/ws` in the app. The first connection shows a
certificate prompt — accept it, and the fingerprint is pinned from then on. A
changed certificate stops the app rather than reconnecting silently.

Both devices must be on the same network, and Windows Firewall will prompt on
first run; allow it on the private profile only.

Note the asymmetry with Option 0, and it is deliberate: a LAN IP over cleartext
is refused, because those bytes *do* cross a network that other devices can
sniff. `127.0.0.1` and `10.0.2.2` do not have that property, which is the only
reason they are allowed to skip TLS.

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
