# HUSH

A private messenger. Open source, self-hosted, no accounts.

Three things it does that other messengers don't:

- **No sign-up.** You type a display name. That's the whole flow. No email, no
  phone number, no password, no recovery flow to attack.
- **No server history.** The relay has no database. A message addressed to
  someone who is offline is dropped and the sender is told, because there is
  nowhere for it to sit.
- **No edit.** Not hidden behind a flag — there is no edit path in the
  codebase. Messages can be deleted for everyone, and deletion is a tombstone
  both sides can see.

Calls are peer-to-peer. The relay carries signaling and nothing else.

---

## What "private" actually means here

Being precise about this, because "private" is a word that gets used to mean
things the software does not do.

**What is true:**

- Message content is end-to-end encrypted with X25519 + ChaCha20-Poly1305.
  The relay holds no key that can decrypt a message body.
- There is no server-side copy of anything. No database, no queue, no cache, no
  message-log file, no crash dump containing plaintext.
- Display names and message sizes never reach a log file. The relay logs
  connection lifecycle and peer count only.
- A message to an offline peer is dropped, not queued.
- The relay's TLS certificate is pinned on first contact. A changed certificate
  stops the app rather than connecting silently.

**What is not true, and can't be:**

- **It is not untraceable.** No software is. The relay sees *who* connected
  when, and that A sent a 340-byte ciphertext to B. That metadata is inherent to
  any router. The code says so in `server/src/hub.js` rather than pretending.
- **A relay operator can see who talks to whom and when.** They cannot read
  the messages. If you need to hide the social graph, run your own relay.
- **A relay operator can attempt a man-in-the-middle.** The defence is the
  safety number: compare it with the other person out of band. This is the one
  thing that genuinely matters, which is why it has a full screen instead of a
  buried menu item.
- **Call metadata is visible to the relay** for the duration of a call — the
  same routing metadata as a message.

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

   Voice/video: direct device-to-device over WebRTC.
   The relay is not in the media path.
```

### Repository layout

```
hush/
├── server/                  Node relay, zero dependencies beyond `ws`
│   ├── src/hub.js           presence, routing, fan-out
│   ├── src/protocol.js      wire format + validation
│   ├── src/tls.js           self-signed cert minting + verification
│   ├── test/relay.test.js   17 tests against a real socket
│   └── tools/e2e.js         end-to-end against a running relay
├── android/                 Kotlin + Jetpack Compose client
│   └── app/src/main/java/dev/hush/chat/
│       ├── crypto/          X25519, X3DH, Double Ratchet, AEAD
│       ├── net/             relay client, TLS pinning
│       ├── data/            SQLite store, session manager, ratchet owner
│       ├── call/            WebRTC P2P voice + video
│       └── ui/              Compose screens
└── scripts/build-apk.ps1    build wrapper (see "Building")
```

---

## Running the relay

```bash
cd server
npm install
npm start
```

```
  HUSH relay
  wss://localhost:8443/ws
  https://localhost:8443/healthz
  fingerprint 2F:21:D3:3E:...  (self-signed)
```

It mints a self-signed certificate on first run using `openssl`, and prints the
SHA-256 fingerprint. Clients pin that fingerprint on first contact.

Bring your own certificate by setting `HUSH_TLS_CERT` and `HUSH_TLS_KEY`.

| Variable | Default | Meaning |
|---|---|---|
| `HUSH_PORT` | `8443` | Listen port |
| `HUSH_HOST` | `0.0.0.0` | Bind address |
| `HUSH_DOMAIN` | *(auto)* | Hostname to put in the certificate SAN |
| `HUSH_TLS_CERT` / `HUSH_TLS_KEY` | *(none)* | Real certificate paths |
| `HUSH_TLS_DIR` | `./certs` | Where the self-signed pair is cached |

### Tests

```bash
cd server
npm test                    # 17 tests, real sockets on loopback
HUSH_INSECURE=1 node tools/e2e.js wss://localhost:8443/ws
```

---

## Building the APK

The Android toolchain is not vendored. Provision JDK 17, the Android SDK
(build-tools 34, platform 34) and Gradle 8.7, then:

```powershell
.\scripts\build-apk.ps1 -Task assembleDebug
.\scripts\build-apk.ps1 -Task testDebugUnitTest
```

The script exists for a specific reason: Gradle's Android tooling on Windows
fails with *"The filename, directory name, or volume label syntax is incorrect"*
when the project path contains a space. The script copies sources to a
space-free mirror, builds there, and copies the APK back to `dist/`. If your
checkout path is already space-free you can just run `gradlew` directly.

Outputs land in `dist/`.

---

## How the crypto works

**X3DH** establishes a session with forward secrecy on first contact, so a
recorded conversation stays unreadable even if both identity keys are later
stolen:

```
DH1 = DH(IK_alice,  SPK_bob)   authenticated
DH2 = DH(EK_alice, SPK_bob)   ephemeral
DH3 = HKDF(DH1 ‖ DH2 ‖ DH3, salt, "HUSH/x3dh/v1")
```

The signed prekey is Ed25519-signed over `(identity ‖ prekey ‖ counter)`, and
clients **refuse a bundle whose signature does not verify**. That check is the
reason the relay is permitted to broker prekeys at all.

**Double Ratchet** then gives forward secrecy on every message and
post-compromise recovery on every reply. Per-message keys are used exactly
once, skipped keys are bounded at 1000, and the implementation **fails closed**
rather than silently skipping if a peer claims an absurd message number.

The conversation id is bound into every AEAD tag as associated data, so a
ciphertext cannot be replayed into a different conversation by anyone who can
reorder frames — including the relay.

Conformance lives in `android/app/src/test/java/dev/hush/chat/crypto/DoubleRatchetTest.kt`:
17 tests covering key uniqueness, replay, reordering, tampering, prekey
substitution, and unbounded-work attacks. Run with `gradlew test`.

---

## Simplifications worth knowing about

Stated plainly rather than buried:

- **Prekeys are not rotated.** Each session uses one signed prekey held in
  preferences. A production deployment keeps a pool and rotates on use.
- **No TURN.** Peer-to-peer calls work on most networks with STUN alone. Two
  peers behind symmetric NATs will fail to connect. A TURN relay would see
  media, which would contradict the privacy claim, so it is not bundled.
  `CallManager.configureIceServers` accepts a list if you want to supply your
  own.
- **Ratchet state is memory-only.** Process death loses it and the next message
  re-establishes via X3DH. Persisting ratchet keys would mean protecting them
  at rest, which would compromise the forward secrecy the ratchet bought.
- **No group messaging.** One-to-one only. Group calls would need Sender Keys
  or a pairwise-mesh protocol, neither of which is here.
- **Video mesh, not SFU.** Fine for two people, which is all this supports.
- **The relay is a single process with no clustering.** Peer state is
  in-memory, so two relay instances do not share a roster.

---

## License

Apache-2.0. See `LICENSE`.
