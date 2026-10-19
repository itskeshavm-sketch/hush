# HUSH

**End-to-end encrypted messaging for Android. No accounts, no server history, no
edit button — and the entire system, relay included, is open source.**

You can run the relay yourself: on your laptop, on a home server, or on a free
Cloudflare Worker. The relay hands out ciphertext and nothing else. It is about
four hundred lines of Node with no database, and it physically has nowhere to
store a message even if you asked it to.

Default branch is `modern`. The original look is preserved on `legacy`. Both are
Apache-2.0 and both build from the same sources.

---

## Contents

- [What HUSH actually does](#what-hush-actually-does)
- [Status: what works and what does not](#status-what-works-and-what-does-not)
- [Quick start — relay on your own machine](#quick-start--relay-on-your-own-machine)
- [Reaching that relay from a real phone](#reaching-that-relay-from-a-real-phone)
- [Hosting on a public domain](#hosting-on-a-public-domain)
- [This is fully open source](#this-is-fully-open-source)
- [Architecture](#architecture)
- [Repository layout](#repository-layout)
- [Building the APK](#building-the-apk)
- [Pointing the APK at your own relay](#pointing-the-apk-at-your-own-relay)
- [How the crypto works](#how-the-crypto-works)
- [Threat model](#threat-model)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)

---

## What HUSH actually does

- **No sign-up.** You type a display name. No email, no phone number, no
  password, no recovery flow to attack.
- **No server history.** A message addressed to an offline peer is dropped and
  the sender is told, because there is nowhere for it to sit.
- **No edit.** Not disabled behind a flag — there is no edit path in the
  codebase. Deletion exists and propagates as a tombstone both sides can see.
- **Private DMs and private groups.** Groups are invisible to everyone outside
  the member list; there is no group directory to search or discover.
- **Disappearing messages.** A per-chat timer, defaulting to two minutes after
  the message is seen. The sender's timer governs the sender's messages.
- **No edit of history, no read receipts you can fake, no online dots.** The app
  never shows whether someone is online.

---

## Status: what works and what does not

Written plainly, because a project that overstates itself is worse than one with
a short feature list.

| Feature | State |
|---|---|
| E2E DMs, offline queue, receipts, resends | **Working** |
| Delete for everyone, disappearing timers, keep | **Working** |
| Images with per-send metadata warning | **Working** |
| Private groups (text only, up to 50 members) | **Working** |
| App PIN lock + duress-wipe PIN | **Working** |
| Five themes, self-host relay URL | **Working** |
| **Voice and video calls** | **Not working** |
| Group images, group calls | **Not built** |
| Release-signed APK / Play Store build | **Not provided** |

**Calls do not work.** The WebRTC layer, the call Activity and the service are
in the tree, and the relay has a frame type that carries signaling — but
`SignalingRoute.emit` only delivers to an in-process listener and never reaches
the transport, so an offer goes nowhere. The code is written to be finished;
it is not finished. An earlier version of this README claimed calls were
peer-to-peer, which was false, and the table above is the correction.

The APKs in `dist/` are **debug-signed**. They install fine by sideload, but the
Play Store will not accept them. See [Contributing](#contributing) if you want
to produce your own signed build.

---

## Quick start — relay on your own machine

Four commands. No certificate, no domain, no account, no card.

```bash
git clone https://github.com/itskeshavm-sketch/hush.git
cd hush/server
npm install
HUSH_INSECURE=1 HUSH_PORT=8787 node src/index.js
```

On Windows PowerShell:

```powershell
git clone https://github.com/itskeshavm-sketch/hush.git
cd hush\server
npm install
$env:HUSH_INSECURE='1'; $env:HUSH_PORT='8787'; node src\index.js
```

You should see:

```
  HUSH relay
  ws://127.0.0.1:8787/ws
  http://127.0.0.1:8787/healthz
  online at boot: 0

  PLAINTEXT MODE. Routing metadata is readable by anyone on the path.
  Fine on loopback (ws://127.0.0.1) — not fine on a shared network.
```

Check it is alive:

```bash
curl http://127.0.0.1:8787/healthz
```

Then point the app at it. In onboarding, open **Advanced → Use your own
server** and enter:

```
ws://127.0.0.1:8787/ws
```

Or change it later in **Settings → Relay**. Nothing else is required — no
account, no token, no registration step.

### Which address to use

`ws://` cleartext is accepted **only** for loopback, and that is enforced in two
places that are deliberately kept in step:

| Where the app runs | Address to enter |
|---|---|
| Android emulator, relay on the host PC | `ws://10.0.2.2:8787/ws` |
| Relay running on the same machine as the app | `ws://127.0.0.1:8787/ws` |
| Real phone, relay on your PC over Wi-Fi | `wss://192.168.x.x:8443/ws` — see below |

Anywhere else, `wss://` is mandatory. Android's cleartext policy is disabled
globally and re-enabled only for `localhost`, `127.0.0.1` and `10.0.2.2`, so
there is no way to downgrade a real network connection. On loopback the bytes
never touch a wire, which is why it is allowed there at all: requiring a
certificate just to talk to yourself is the fastest way to make self-hosting
feel impossible.

---

## Reaching that relay from a real phone

On a real phone, `127.0.0.1` is the *phone*, not your PC. To use a relay running
on your PC from your phone you need TLS — but you do **not** need a domain, a
CA, or a port forward. The relay mints its own certificate with an IP address in
the SAN, and the app pins it on first contact.

On the PC:

```bash
HUSH_DOMAIN=192.168.1.50 node src/index.js      # your PC's LAN address
```

It prints a SHA-256 fingerprint and serves `wss://192.168.1.50:8443/ws`. Enter
that URL in the app. The first connection shows a certificate prompt — accept
it, and the fingerprint is pinned. If the certificate ever changes, the app
stops rather than connecting silently.

The phone and the PC must be on the same network, and the port must be reachable
(Windows Firewall will ask on first run — allow it on the private profile).

---

## Hosting on a public domain

Give it a real certificate and it works identically:

```bash
HUSH_DOMAIN=relay.example.com node src/index.js
```

| Variable | Default | Meaning |
|---|---|---|
| `HUSH_PORT` | `8443` | Listen port |
| `HUSH_HOST` | `0.0.0.0` | Bind address |
| `HUSH_DOMAIN` | *(auto)* | Hostname or IP for the certificate SAN |
| `HUSH_TLS_CERT` / `HUSH_TLS_KEY` | *(none)* | Your own certificate, e.g. from Caddy or certbot |
| `HUSH_TLS_DIR` | `./certs` | Where the self-signed pair is cached |
| `HUSH_INSECURE` | off | `1` or `HUSH_TLS=off` runs plaintext, loopback only |

TLS is on unless you explicitly disable it, and the relay loads certificate
material before constructing the server so it never briefly listens in the
clear. See `HOSTING.md` for Cloudflare Workers, which costs nothing at HUSH's
scale and needs no machine kept awake.

---

## This is fully open source

Everything needed to run HUSH is in this repository. Nothing is proprietary,
nothing is fetched at runtime, and nothing is withheld.

- **The client is here.** Kotlin and Jetpack Compose, complete. Not a wrapper,
  not a stub — the ratchet, the group protocol, the database and the UI are all
  in `android/`.
- **The relay is here.** ~400 lines in `server/`. Its only dependency is `ws`.
- **The Cloudflare Worker is here too.** `worker/` is the same relay logic
  adapted to a Durable Object, so the free-tier deployment is auditable rather
  than a mystery binary.
- **The wire protocol is here.** `shared/protocol.js` is the canonical
  definition, used by the Node relay, the Worker and the tests.
- **The prebuilt APK is here.** `dist/` carries debug-signed builds. You can
  read every line before you run it, and build your own from source instead.

There is no telemetry, no analytics, no crash reporting, and no phone-home of
any kind. The app talks to the relay you configured and to nothing else. The
default relay URL is baked into the APK as a convenience; it is a string in
`build.gradle.kts`, visible in the source and changeable at build time or at
runtime — not a hidden dependency.

---

## Architecture

```
  ┌──────────────┐   wss (ciphertext + routing metadata)   ┌──────────────┐
  │  HUSH client │ ──────────────────────────────────────▶ │    Relay     │
  │  Kotlin/     │ ◀────────────────────────────────────── │  Node, ~400  │
  │  Compose     │            E2E, no plaintext ever        │  lines, no DB│
  └──────────────┘                                          └──────────────┘
          │                                                       ▲
          │  X3DH + Double Ratchet                                │
          └───────────────────────────────────────────────────────┘
                             (prekey bundles, via relay)
```

The relay is deliberately dumb. It holds sockets in memory, routes ciphertext
frames between them, answers roster queries, and drops everything else on the
floor. It cannot read a message because it has no key, and it cannot recover one
afterwards because it never wrote one down.

### Groups

Groups fan out pairwise over the existing one-to-one sessions. There is no group
frame type, no group state on the relay, and nothing in the protocol that knows
a group exists — a group lives only in its members' databases. The relay cannot
tell you who is in a group, because it was never told.

That is also why group membership has to be reasoned about carefully: there is
no server to arbitrate who is oldest, so membership uses a monotonic join
counter and the admin is *derived* from the lowest counter rather than read from
a cached field. Two members can never disagree about who is in charge.

---

## Repository layout

```
hush/
├── android/                 Kotlin + Jetpack Compose client
│   └── app/src/main/java/dev/hush/chat/
│       ├── crypto/          X3DH, Double Ratchet, AEAD, key storage
│       ├── data/            SQLite store, session manager, ratchet owner
│       ├── net/             relay client, TLS pinning, relay URL rules
│       ├── call/            WebRTC voice/video — INCOMPLETE, see status
│       └── ui/              Compose screens
├── server/                  Node relay
│   ├── src/hub.js           presence, routing, fan-out
│   ├── src/index.js         HTTP + WebSocket server, TLS or plaintext
│   ├── src/protocol.js      wire format + validation
│   ├── src/tls.js           self-signed cert minting + SAN verification
│   ├── test/relay.test.js   21 tests against real sockets
│   └── tools/e2e.js         end-to-end against a running relay
├── worker/                  same relay as a Cloudflare Durable Object
├── shared/protocol.js       canonical frame validation, shared by all of the above
├── docs/                    design notes
└── scripts/build-apk.ps1    Windows build wrapper (see "Building")
```

---

## Building the APK

You need JDK 17, the Android SDK (build-tools 34, platform 34) and Gradle 8.7.
The toolchain is not vendored.

```powershell
.\scripts\build-apk.ps1 -Task assembleDebug
.\scripts\build-apk.ps1 -Task testDebugUnitTest
```

Outputs land in `dist/`.

The wrapper script exists for one reason: Gradle's Android tooling on Windows
fails with *"The filename, directory name, or volume label syntax is incorrect"*
when the project path contains a space. It mirrors the sources to a space-free
directory, builds there, and copies the APK back. If your checkout path has no
spaces, `gradlew` works directly.

Two branches are maintained:

| Branch | Description |
|---|---|
| `modern` | Current look. Solid dividers, `HUSH Modern` label, `.modern` application id — installs alongside `legacy`. |
| `legacy` | The original appearance, frozen. Receives every bug fix. |

Both branches receive every functional change. They differ only in five
presentation files.

---

## Pointing the APK at your own relay

Three ways, in order of how permanent they are:

1. **At runtime.** Onboarding → *Advanced → Use your own server*, or *Settings →
   Connection → Relay server* at any time afterwards. Nothing needs
   rebuilding, and you are not locked into whatever relay you happened to
   install with. This is the recommended path for self-hosting.

   Switching to a different host clears the pinned certificate, because a pin
   belongs to one relay rather than to the app — otherwise every new server
   would look like a man-in-the-middle and you would be stuck on a trust error
   for a relay you chose on purpose. Changing only the port or path keeps the
   pin, since that is the same server.
2. **At build time.** Edit `DEFAULT_RELAY` in `android/app/build.gradle.kts`:

   ```kotlin
   buildConfigField("String", "DEFAULT_RELAY", "\"wss://relay.example.com/ws\"")
   ```

   This changes the pre-filled value in onboarding.
3. **Behind a reverse proxy.** Terminate TLS in Caddy or nginx and forward to
   the relay. The relay's own TLS is optional when something in front already
   handles it.

---

## How the crypto works

**X3DH** establishes a session with forward secrecy on first contact, so a
recorded conversation stays unreadable even if both identity keys are later
stolen:

```
DH1 = DH(IK_alice,  SPK_bob)   authenticated
DH2 = DH(EK_alice, SPK_bob)    ephemeral
DH3 = HKDF(DH1 ‖ DH2 ‖ DH3, salt, "HUSH/x3dh/v1")
```

The signed prekey is Ed25519-signed over `(identity ‖ prekey ‖ counter)`, and
clients **refuse a bundle whose signature does not verify**. That check is the
reason the relay is permitted to broker prekeys at all — without it, the relay
could substitute its own and mount a machine-in-the-middle.

**Double Ratchet** then gives forward secrecy on every message and
post-compromise recovery on every reply. Per-message keys are used exactly once,
skipped keys are bounded, and the implementation **fails closed** rather than
silently skipping if a peer claims an absurd message number.

Every message is bound to its conversation id as AEAD associated data, so a
ciphertext cannot be replayed into a different conversation by anyone who can
reorder frames — including the relay. Replay is also tracked in memory with a
bounded window, so a redelivered frame is dropped instead of surfacing as a
spurious decrypt failure.

Safety numbers are a sorted digest of both prekey bundles, so both people
compute the same string and can compare it out of band.

---

## Threat model

**What is true:**

- Message content is end-to-end encrypted with X25519 + ChaCha20-Poly1305.
  The relay holds no key that can decrypt a message body.
- There is no server-side copy of anything. No database, no queue, no cache, no
  message log, no crash dump containing plaintext.
- Display names and message sizes never reach a log file. The relay logs
  connection lifecycle and peer count only.
- A message to an offline peer is dropped, not queued.
- The relay's certificate is pinned on first contact. A changed certificate
  stops the app rather than connecting silently.
- The app refuses to downgrade to cleartext except on loopback.

**What is not true, and cannot be:**

- **It is not untraceable.** No software is. The relay sees *who* connected
  when, and that A sent a 340-byte ciphertext to B. That metadata is inherent to
  any router, and `server/src/hub.js` says so in comments rather than
  pretending otherwise.
- **A relay operator can see who talks to whom and when.** They cannot read the
  messages. If you need to hide the social graph, run your own relay — which is
  exactly what this project is set up to let you do.
- **A relay operator can attempt a man-in-the-middle.** The defence is the
  safety number: compare it with the other person out of band. This is the one
  thing that genuinely matters, which is why it has a full screen instead of a
  buried menu item.
- **Metadata is the weak point, not content.** Disappearing timers protect
  message bodies, not the fact that a conversation happened.

---

## Testing

```bash
# relay, real sockets on loopback
cd server && npm test                # 21 tests

# full protocol against a running relay
HUSH_INSECURE=1 node tools/e2e.js ws://127.0.0.1:8787/ws   # 13 checks

# client crypto and state
.\scripts\build-apk.ps1 -Task testDebugUnitTest             # 44 tests
```

The client suite is `DoubleRatchetTest` (18), `FirstContactTest` (9),
`StateEqualityTest` (7) and `RelayUrlsTest` (10), covering key uniqueness,
replay, reordering, tampering, prekey substitution, unbounded-work attacks, and
the relay-URL security boundary.

`RelayUrlsTest` is worth singling out: cleartext being refused everywhere except
loopback is a security decision, and the natural "fix" for a self-hoster who
cannot connect is to widen that check. The test pins the boundary — including
hosts that merely *start* with `127.0.0.1`, and userinfo such as
`ws://127.0.0.1@evil.example` — so widening it has to be a deliberate act. It
also covers the host parse that decides when a pinned certificate is dropped.

---

## Contributing

Pull requests are welcome, including from people who disagree with the design.

Before opening one:

- **Run the tests.** `npm test` in `server/` and `testDebugUnitTest` on the
  client. Both are expected to be green.
- **Do not add an edit path.** The absence of message editing is a feature. A
  PR that introduces one will be declined, and the README will keep saying so.
- **Keep the relay stateless.** If your change requires the relay to remember
  anything about a conversation across requests, that is a design change worth
  discussing in an issue first. A relay that stores messages is a different
  product, with a different set of guarantees.
- **Do not weaken a security boundary quietly.** The cleartext allowlist, the
  certificate pinning, and the prekey signature check are each load-bearing. If
  one has to move for your feature, say so explicitly in the PR description.

If you want to ship a real signed APK, bring your own keystore — the debug-signed
builds here are for testing and are not suitable for distribution.

---

## License

Apache-2.0. See [`LICENSE`](LICENSE).

That covers the client, the relay, the Worker and the documentation. You may use,
modify, audit and redistribute all of it, including commercially.