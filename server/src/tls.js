/**
 * TLS material bootstrap.
 *
 * HUSH is worthless over plaintext: every property it claims (no accounts, no
 * logs, E2E) evaporates the moment a café wifi sees your socket. So TLS is
 * not optional and not "bring your own cert" in the default path.
 *
 * Three ways to get a certificate, tried in order:
 *   1. HUSH_TLS_CERT / HUSH_TLS_KEY  — real certs (Caddy, certbot, LE).
 *   2. HUSH_DOMAIN                   — self-signed leaf minted for that hostname.
 *   3. nothing                       — self-signed leaf for localhost + LAN IPs.
 *
 * Self-signed is acceptable for this threat model because the client pins the
 * relay's cert fingerprint on first connect and refuses to change it, so an
 * active MITM cannot silently swap the certificate out. See TlsPinner.kt.
 */

import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

/**
 * @param {{ certDir?: string, domain?: string }} [opts]
 * @returns {Promise<{ key: Buffer, cert: Buffer, fingerprint: string, selfSigned: boolean, sans: string[] }>}
 */
export async function loadTls(opts = {}) {
  const certDir = resolve(opts.certDir ?? process.env.HUSH_TLS_DIR ?? './certs');

  const certPath = process.env.HUSH_TLS_CERT;
  const keyPath = process.env.HUSH_TLS_KEY;
  if (certPath && keyPath) {
    if (!existsSync(certPath)) throw new Error(`HUSH_TLS_CERT not found: ${certPath}`);
    if (!existsSync(keyPath)) throw new Error(`HUSH_TLS_KEY not found: ${keyPath}`);
    const key = readFileSync(keyPath);
    const cert = readFileSync(certFile());
    return { key, cert, fingerprint: fingerprint(cert), selfSigned: false, sans: [] };
  }

  mkdirSync(certDir, { recursive: true });
  const keyFile = join(certDir, 'relay.key');
  const certFile = join(certDir, 'relay.crt');

  if (existsSync(keyFile) && existsSync(certFile)) {
    const key = readFileSync(keyFile);
    const cert = readFileSync(certFile);
    const fp = fingerprint(cert);
    console.log(`[tls] reusing self-signed relay cert at ${certFile}`);
    console.log(`[tls] relay fingerprint: ${fp}`);
    return { key, cert, fingerprint: fp, selfSigned: true, sans: [] };
  }

  const sans = buildSan(opts.domain);
  console.log(`[tls] minting self-signed certificate for: ${sans.join(', ')}`);

  const cnfPath = join(certDir, 'openssl.cnf');
  writeFileSync(cnfPath, opensslCnf(sans), 'utf8');

  try {
    await run('openssl', [
      'req', '-x509',
      '-newkey', 'rsa:4096',
      '-sha256',
      '-days', '825',
      '-nodes',
      '-keyout', keyFile,
      '-out', certFile,
      '-config', cnfPath,
      '-extensions', 'v3_req',
    ]);
  } catch (err) {
    throw new Error(
      'openssl is required to mint a self-signed relay certificate. ' +
      'Install openssl or set HUSH_TLS_CERT / HUSH_TLS_KEY to real certificate files.\n' +
      `underneath: ${err.message}`,
    );
  }

  try {
    chmodSync(keyFile, 0o600);
  } catch {
    /* best effort; Windows ACLs ignore POSIX bits */
  }

  const check = await verifyCertificate(keyFile, certFile, sans);
  if (!check.ok) {
    throw new Error(
      'the generated relay certificate failed verification:\n  - ' +
      check.problems.join('\n  - ') +
      `\nDelete ${certDir} and restart to regenerate.`,
    );
  }

  const key = readFileSync(keyFile);
  const cert = readFileSync(certFile);
  const fp = fingerprint(cert);
  console.log(`[tls] certificate written to ${certFile}`);
  console.log(`[tls] relay fingerprint: ${fp}`);
  console.log('[tls] clients pin this on first connect — a changed pin means a different relay.');
  return { key, cert, fingerprint: fp, selfSigned: true, sans };
}

/**
 * Prove the pair is actually usable before the listener opens.
 *
 * This exists because OpenSSL will happily emit a certificate whose SAN list
 * came out empty, and because a cert written by one invocation can be paired
 * with a key from another if the directory was half-populated. Both failures
 * surface to the *client* as a handshake error with no useful context, which is
 * a miserable thing to debug at 2am. Fail here, loudly, instead.
 *
 * @param {string} keyFile
 * @param {string} certFile
 * @param {string[]} expectedSans
 * @returns {Promise<{ ok: true } | { ok: false, problems: string[] }>}
 */
export async function verifyCertificate(keyFile, certFile, expectedSans = []) {
  const problems = [];

  // 1. Does the key parse at all?
  let keyPub = '';
  try {
    const { stdout } = await run('openssl', ['pkey', '-in', keyFile, '-pubout']);
    keyPub = stdout.trim();
  } catch (err) {
    return { ok: false, problems: [`private key is unreadable: ${err.message}`] };
  }
  if (!keyPub) problems.push('private key produced no public key');

  // 2. Do key and certificate actually belong to each other?
  try {
    const { stdout } = await run('openssl', ['x509', '-in', certFile, '-noout', '-pubkey']);
    if (stdout.trim() !== keyPub) {
      problems.push('private key does not match the certificate — delete certs/ and restart');
    }
  } catch (err) {
    return { ok: false, problems: [`certificate is unreadable: ${err.message}`] };
  }

  // 3. Did the SAN list survive OpenSSL? An empty SAN means every client fails
  //    hostname verification, which looks exactly like "the app just doesn't
  //    work" on the operator's phone.
  //
  //    Note the match string: `openssl x509 -ext` prints the *human* name
  //    ("X509v3 Subject Alternative Name"), not the OID, and puts the values on
  //    the following line. Matching on "subjectAltName=" here is always false
  //    and makes the check useless.
  let certSans = '';
  try {
    const { stdout } = await run('openssl', [
      'x509', '-in', certFile, '-noout', '-ext', 'subjectAltName',
    ]);
    certSans = stdout;
  } catch {
    problems.push('could not read subjectAltName from the certificate');
  }

  const sansHeader = 'X509v3 Subject Alternative Name';
  if (!certSans.includes(sansHeader)) {
    problems.push('certificate has no subjectAltName extension');
  } else {
    // Every expected entry must be present. A cert missing the LAN address
    // still works on localhost, so the breakage only shows up on a phone.
    const valueLine = certSans.slice(certSans.indexOf(sansHeader) + sansHeader.length);
    const missing = expectedSans
      .map((s) => {
        const [kind, value] = s.split(':');
        return kind === 'IP' ? `IP Address:${value}` : `DNS:${value}`;
      })
      .filter((needle) => !valueLine.includes(needle));
    if (missing.length > 0) {
      problems.push(`subjectAltName is missing: ${missing.join(', ')}`);
    }
  }

  return { ok: problems.length === 0, problems };
}

/** SHA-256 of the DER cert, colon-separated — the form TlsPinner compares against. */
export function fingerprint(certBuffer) {
  return createHash('sha256')
    .update(certBuffer)
    .digest('hex')
    .toUpperCase()
    .match(/.{2}/g)
    .join(':');
}

function certFile() {
  return process.env.HUSH_TLS_CERT;
}

function buildSan(domain) {
  /** @type {Set<string>} */
  const hosts = new Set(['localhost', '127.0.0.1', '::1']);
  if (domain) {
    hosts.add(domain);
  } else {
    // Include LAN addresses so a phone on the same wifi can reach a relay
    // running on a laptop without anyone editing a SAN list by hand.
    for (const addrs of Object.values(networkInterfaces())) {
      for (const a of addrs ?? []) {
        if (a.family === 'IPv4' && !a.internal) hosts.add(a.address);
      }
    }
  }
  return [...hosts].map((h) =>
    /^\d+\.\d+\.\d+\.\d+$/.test(h) || h.includes(':') ? `IP:${h}` : `DNS:${h}`,
  );
}

function opensslCnf(sans) {
  // SANs are written as one inline comma-separated value rather than via an
  // [alt] section. Section-index form (`IP.1 = ...`) gets entries silently
  // dropped when types interleave; the inline form is parsed verbatim.
  const cn = sans[0].replace(/^(DNS|IP):/, '');

  return `[req]
default_bits = 4096
distinguished_name = dn
x509_extensions = v3_req
prompt = no

[dn]
C  = XX
ST = Nowhere
L  = Nowhere
O  = HUSH Relay (self-hosted)
OU = HUSH
CN = ${cn}

[v3_req]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = ${sans.join(',')}
`;
}