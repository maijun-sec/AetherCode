/**
 * R-MEM-6.2 (F7+ Ed25519): provenance-chain signing helpers.
 *
 * The chain added in R-MEM-5.2 was "best-effort": anyone with
 * sqlite access could rewrite both the chain rows and the
 * backing content, and the verifier would still pass because
 * the hashes stayed consistent. This module layers an
 * Ed25519 signature on top so the chain can catch a writer
 * who has sqlite access but NOT the private signing key.
 *
 * The signing key is loaded once at startup from
 * `AETHERCODE_MEMORY_KEY` (a 32-byte ed25519 secret key,
 * base64-encoded). If the env var is absent, a fresh key is
 * generated and held in memory only — useful for tests and
 * single-process deployments, but the signatures will be
 * unverifiable across restarts. Production deployments MUST
 * set the env var.
 *
 * Signing input (canonical, byte-exact):
 *   `${prev_content_hash ?? ""}|${entry_id}|${content}|${ts}`
 *
 * The signature is over the SHA-256 of that input (signing
 * a digest is the standard pattern; Ed25519 itself operates
 * on arbitrary-length inputs but adding a hash keeps the
 * signing envelope tiny and easy to log).
 */

import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify, type KeyObject } from 'node:crypto';

const ENV_VAR = 'AETHERCODE_MEMORY_KEY';

interface SigningKey {
  readonly privateKey: KeyObject;
  readonly publicKey: KeyObject;
  /** Hex-encoded raw 32-byte ed25519 public key. */
  readonly publicKeyHex: string;
  /** True when the key was generated at startup, false when
   *  loaded from the env var. */
  readonly ephemeral: boolean;
}

let cached: SigningKey | null = null;

function loadKeyFromEnv(): KeyObject | null {
  const raw = process.env[ENV_VAR];
  if (raw === undefined || raw.length === 0) return null;
  // Accept base64 (preferred) or hex.
  let secret: Buffer;
  try {
    secret = Buffer.from(raw, 'base64');
    if (secret.length === 0) secret = Buffer.from(raw, 'hex');
  } catch {
    secret = Buffer.from(raw, 'hex');
  }
  if (secret.length !== 32) {
    throw new Error(
      `${ENV_VAR} must decode to exactly 32 bytes (got ${secret.length}). ` +
      `Provide a 32-byte ed25519 secret key as base64 or hex.`,
    );
  }
  // Wrap as a PKCS8 DER-encoded Ed25519 private key. Node's
  // crypto.sign('ed25519', ...) expects either a Buffer of
  // the raw 32 bytes, a KeyObject, or a PEM. PKCS8 DER is
  // the cleanest portable shape.
  const der = wrapEd25519PrivateKeyAsPkcs8(secret);
  return createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
}

function generateEphemeral(): KeyObject {
  const { privateKey } = generateKeyPairSync('ed25519');
  return privateKey;
}

function getSigningKey(): SigningKey {
  if (cached !== null) return cached;
  const fromEnv = loadKeyFromEnv();
  if (fromEnv !== null) {
    const publicKey = createPublicKey(fromEnv);
    cached = {
      privateKey: fromEnv,
      publicKey,
      publicKeyHex: publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex'),
      ephemeral: false,
    };
    return cached;
  }
  const priv = generateEphemeral();
  const publicKey = createPublicKey(priv);
  cached = {
    privateKey: priv,
    publicKey,
    publicKeyHex: publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex'),
    ephemeral: true,
  };
  return cached;
}

/** Build the canonical signing input. The verifier and the
 *  signer must use the exact same byte sequence. */
export function signingInput(prevContentHash: string | null, entryId: string, content: string, ts: number): Buffer {
  const text = `${prevContentHash ?? ''}|${entryId}|${content}|${ts}`;
  return createHash('sha256').update(text).digest();
}

/** Sign a row. Returns the signature as base64. */
export function signRow(prevContentHash: string | null, entryId: string, content: string, ts: number): string {
  const key = getSigningKey();
  const input = signingInput(prevContentHash, entryId, content, ts);
  const sig = sign(null, input, key.privateKey);
  return sig.toString('base64');
}

/** Verify a row's signature. Returns true on match, false on
 *  mismatch. The caller supplies the public key as hex
 *  (32 bytes). */
export function verifyRowSignature(
  signature: string,
  pubkeyHex: string,
  prevContentHash: string | null,
  entryId: string,
  content: string,
  ts: number,
): boolean {
  try {
    const pubkeyBuf = Buffer.from(pubkeyHex, 'hex');
    if (pubkeyBuf.length !== 32) return false;
    const pubKey = createPublicKey({ key: wrapEd25519PublicKeyAsSpki(pubkeyBuf), format: 'der', type: 'spki' });
    const input = signingInput(prevContentHash, entryId, content, ts);
    return verify(null, input, pubKey, Buffer.from(signature, 'base64'));
  } catch {
    return false;
  }
}

/** The hex public key for the currently loaded signing key.
 *  Persist this alongside the database if you want to verify
 *  signatures across machines. */
export function getSignerPublicKeyHex(): string {
  return getSigningKey().publicKeyHex;
}

/** True when the loaded signing key was generated at
 *  startup (and will be lost on restart). Production
 *  deployments should set `AETHERCODE_MEMORY_KEY` to load a
 *  persistent key. */
export function isEphemeralKey(): boolean {
  return getSigningKey().ephemeral;
}

/** Test hook: drop the cached key. */
export function _resetSigningKeyForTests(): void {
  cached = null;
}

/* ------------------------------------------------------------------ */
/* PKCS8 / SPKI wrapping for raw ed25519 keys                        */
/* ------------------------------------------------------------------ */
/* Node's crypto expects ed25519 keys in PKCS8 (private) or SPKI
 * (public) DER, not as raw 32-byte seed material. We hand-roll
 * the minimal ASN.1 wrappers to keep the dependency surface
 * zero. */

const PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
const SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

function wrapEd25519PrivateKeyAsPkcs8(raw32: Buffer): Buffer {
  if (raw32.length !== 32) {
    throw new Error(`Ed25519 private key must be 32 bytes (got ${raw32.length})`);
  }
  return Buffer.concat([PKCS8_PREFIX, raw32]);
}

function wrapEd25519PublicKeyAsSpki(raw32: Buffer): Buffer {
  if (raw32.length !== 32) {
    throw new Error(`Ed25519 public key must be 32 bytes (got ${raw32.length})`);
  }
  return Buffer.concat([SPKI_PREFIX, raw32]);
}
