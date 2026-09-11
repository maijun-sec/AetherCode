/**
 * R-MEM-5.2 (F7 Trust): provenance chain.
 *
 * Each write to a memory table is mirrored here as a chain
 * row. The (scope, ts) UNIQUE constraint is the natural
 * ordering. `content_hash` is SHA-256 over
 * `entry_id | content | ts`. `prev_content_hash` is the
 * previous row's content_hash in the same scope, or NULL
 * for the first row.
 *
 * `verifyChain(scope)` walks the chain in ts order and
 * returns either { ok: true, count } or { ok: false,
 * brokenAt, reason }.
 *
 * The chain is best-effort: it's *not* tamper-proof (anyone
 * with sqlite access can rewrite both the chain and the
 * content), but it catches casual corruption, sqlite WAL
 * races, and "I ran a script that mass-edited the memory
 * file" mistakes. For cryptographic tamper resistance
 * (Ed25519 / signed audit log), see the F7 follow-up
 * `memory/signEntry` RPC.
 */

import { createHash } from 'node:crypto';
import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement, queryAll } from './sqlite.js';
import { signRow, verifyRowSignature, getSignerPublicKeyHex } from './signing.js';

/** A row in the `provenance_chain` table. */
export interface ProvenanceRow {
  readonly id: number;
  readonly scope: string;
  readonly entry_id: string;
  readonly content_hash: string;
  readonly prev_content_hash: string | null;
  readonly ts: number;
  /** R-MEM-6.2: Ed25519 signature (base64) over
   *  `SHA-256(prev_content_hash | entry_id | content | ts)`.
   *  Null for legacy (pre-v12) rows that weren't signed. */
  readonly signature: string | null;
  /** R-MEM-6.2: hex public key used to sign. Null for
   *  legacy rows. */
  readonly signer_pubkey: string | null;
}

/** SQL: insert a chain row. */
const SQL_INSERT = `INSERT INTO provenance_chain
  (scope, entry_id, content_hash, prev_content_hash, ts, signature, signer_pubkey)
  VALUES (?, ?, ?, ?, ?, ?, ?)`;

/** SQL: read the most recent row in a scope (chain head). */
const SQL_HEAD = `SELECT id, scope, entry_id, content_hash, prev_content_hash, ts, signature, signer_pubkey
  FROM provenance_chain
  WHERE scope = ?
  ORDER BY ts DESC, id DESC
  LIMIT 1`;

/** SQL: read the chain in ts order. */
const SQL_CHAIN = `SELECT id, scope, entry_id, content_hash, prev_content_hash, ts, signature, signer_pubkey
  FROM provenance_chain
  WHERE scope = ?
  ORDER BY ts ASC, id ASC`;

/** SQL: read a single chain row by (scope, entry_id). */
const SQL_BY_ENTRY = `SELECT id, scope, entry_id, content_hash, prev_content_hash, ts, signature, signer_pubkey
  FROM provenance_chain
  WHERE scope = ? AND entry_id = ?
  ORDER BY ts ASC, id ASC`;

/** SQL: count rows in a scope's chain. */
const SQL_COUNT = `SELECT COUNT(*) AS n FROM provenance_chain WHERE scope = ?`;

/** SQL: drop a scope's chain (test-only). */
const SQL_DROP = `DELETE FROM provenance_chain WHERE scope = ?`;

/** Compute the canonical content hash for a chain entry. */
export function contentHash(entryId: string, content: string, ts: number): string {
  return createHash('sha256').update(`${entryId}|${content}|${ts}`).digest('hex');
}

/**
 * Append a chain row. Reads the current chain head for the
 * scope and uses its content_hash as the new row's
 * prev_content_hash. Returns the new row id.
 *
 * This is the R-MEM-5.2 unsigned variant — kept for
 * back-compat with any code that wants to record rows
 * without signing. For new code, prefer
 * `appendProvenanceSigned` (R-MEM-6.2), which adds the
 * Ed25519 signature.
 */
export function appendProvenance(
  db: DatabaseT,
  scope: string,
  entryId: string,
  content: string,
  ts: number = Date.now(),
): number {
  if (!scope) throw new Error('appendProvenance: scope must be non-empty');
  if (!entryId) throw new Error('appendProvenance: entryId must be non-empty');
  const head = queryAll<ProvenanceRow>(db, SQL_HEAD, [scope], mapProvenanceRow);
  const prevHash = head[0]?.content_hash ?? null;
  const hash = contentHash(entryId, content, ts);
  const info = runStatement(db, SQL_INSERT, [scope, entryId, hash, prevHash, ts, null, null]);
  return typeof info.lastInsertRowid === 'bigint'
    ? Number(info.lastInsertRowid)
    : (info.lastInsertRowid as number);
}

/**
 * R-MEM-6.2 — append a signed chain row. Same shape as
 * `appendProvenance` but the row is also Ed25519-signed so
 * `verifySignedChain` can catch a writer who has sqlite
 * access but not the private key. Returns the new row id
 * and the public key used to sign.
 */
export interface SignedProvenanceResult {
  readonly id: number;
  readonly signerPubkey: string;
}
export function appendProvenanceSigned(
  db: DatabaseT,
  scope: string,
  entryId: string,
  content: string,
  ts: number = Date.now(),
): SignedProvenanceResult {
  if (!scope) throw new Error('appendProvenanceSigned: scope must be non-empty');
  if (!entryId) throw new Error('appendProvenanceSigned: entryId must be non-empty');
  const head = queryAll<ProvenanceRow>(db, SQL_HEAD, [scope], mapProvenanceRow);
  const prevHash = head[0]?.content_hash ?? null;
  const hash = contentHash(entryId, content, ts);
  const signature = signRow(prevHash, entryId, content, ts);
  const pubkey = getSignerPublicKeyHex();
  const info = runStatement(db, SQL_INSERT, [scope, entryId, hash, prevHash, ts, signature, pubkey]);
  return {
    id: typeof info.lastInsertRowid === 'bigint'
      ? Number(info.lastInsertRowid)
      : (info.lastInsertRowid as number),
    signerPubkey: pubkey,
  };
}

/** Read the full chain for a scope, in ts order. */
export function readChain(db: DatabaseT, scope: string): ProvenanceRow[] {
  return queryAll<ProvenanceRow>(db, SQL_CHAIN, [scope], mapProvenanceRow);
}

/** Read the chain rows that belong to a given entry. */
export function readChainForEntry(
  db: DatabaseT,
  scope: string,
  entryId: string,
): ProvenanceRow[] {
  return queryAll<ProvenanceRow>(db, SQL_BY_ENTRY, [scope, entryId], mapProvenanceRow);
}

/** Count chain rows for a scope. */
export function countChain(db: DatabaseT, scope: string): number {
  const row = db.prepare(SQL_COUNT).get(scope) as { n: number };
  return Number(row.n);
}

/** Drop a scope's entire chain. Useful for tests. */
export function dropChain(db: DatabaseT, scope: string): number {
  const info = runStatement(db, SQL_DROP, [scope]);
  return info.changes;
}

/** Result of `verifyChain`. */
export type VerifyChainResult =
  | { readonly ok: true; readonly count: number; readonly headHash: string | null }
  | {
      readonly ok: false;
      readonly count: number;
      readonly brokenAt: number;
      readonly reason: 'broken-link' | 'tamper';
      readonly details: string;
    };

/**
 * Walk the chain in ts order and confirm every (entry_id,
 * content, ts) hashes to its `content_hash`, and every
 * `prev_content_hash` matches the previous row's
 * `content_hash`.
 *
 * The caller passes a `contentProvider` so the verifier can
 * recompute the hash from the *current* content. This is the
 * key trick: if the underlying data is rewritten without
 * rewriting the chain, the recomputed hash won't match.
 */
export function verifyChain(
  db: DatabaseT,
  scope: string,
  contentProvider: (entryId: string, ts: number) => string | null,
): VerifyChainResult {
  const rows = readChain(db, scope);
  if (rows.length === 0) {
    return { ok: true, count: 0, headHash: null };
  }
  let prevHash: string | null = null;
  for (const row of rows) {
    // 1) prev_content_hash must match the previous row's hash.
    if (row.prev_content_hash !== prevHash) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'broken-link',
        details:
          `chain link broken at id=${row.id}: expected prev=${prevHash ?? 'NULL'}, ` +
          `got prev=${row.prev_content_hash ?? 'NULL'}`,
      };
    }
    // 2) content_hash must match the current content.
    const content = contentProvider(row.entry_id, row.ts);
    if (content === null) {
      // Provider doesn't know about this entry — treat as
      // tamper (we can't recompute without the content).
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'tamper',
        details: `entry_id=${row.entry_id} ts=${row.ts} not found in the backing store`,
      };
    }
    const expected = contentHash(row.entry_id, content, row.ts);
    if (expected !== row.content_hash) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'tamper',
        details:
          `content_hash mismatch at id=${row.id}: expected ${expected}, ` +
          `got ${row.content_hash}`,
      };
    }
    prevHash = row.content_hash;
  }
  return { ok: true, count: rows.length, headHash: prevHash };
}

/** R-MEM-6.2 — `verifyChain` + Ed25519 signature check. The
 *  result extends the chain verifier with a `signerInconsistent`
 *  reason: a row's signature doesn't match the row's recorded
 *  public key, OR the signature is missing for a row that
 *  should have one. */
export type VerifySignedChainResult =
  | { readonly ok: true; readonly count: number; readonly headHash: string | null; readonly signedCount: number }
  | {
      readonly ok: false;
      readonly count: number;
      readonly brokenAt: number;
      readonly reason: 'broken-link' | 'tamper' | 'bad-signature' | 'missing-signature';
      readonly details: string;
    };

/** R-MEM-6.2 — verify a chain's hashes *and* its signatures.
 *  Legacy rows (pre-v12) have no signature; they pass the
 *  signature check vacuously but contribute to the "missing
 *  signature" warning if the operator chose to enforce it.
 *  Set `requireSignatures = true` to fail on any unsigned row. */
export function verifySignedChain(
  db: DatabaseT,
  scope: string,
  contentProvider: (entryId: string, ts: number) => string | null,
  options: { requireSignatures?: boolean } = {},
): VerifySignedChainResult {
  const requireSignatures = options.requireSignatures === true;
  const rows = readChain(db, scope);
  if (rows.length === 0) {
    return { ok: true, count: 0, headHash: null, signedCount: 0 };
  }
  let prevHash: string | null = null;
  let signedCount = 0;
  for (const row of rows) {
    if (row.prev_content_hash !== prevHash) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'broken-link',
        details:
          `chain link broken at id=${row.id}: expected prev=${prevHash ?? 'NULL'}, ` +
          `got prev=${row.prev_content_hash ?? 'NULL'}`,
      };
    }
    const content = contentProvider(row.entry_id, row.ts);
    if (content === null) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'tamper',
        details: `entry_id=${row.entry_id} ts=${row.ts} not found in the backing store`,
      };
    }
    const expected = contentHash(row.entry_id, content, row.ts);
    if (expected !== row.content_hash) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'tamper',
        details:
          `content_hash mismatch at id=${row.id}: expected ${expected}, ` +
          `got ${row.content_hash}`,
      };
    }
    // Signature check (R-MEM-6.2).
    if (row.signature !== null && row.signer_pubkey !== null) {
      const ok = verifyRowSignature(
        row.signature,
        row.signer_pubkey,
        row.prev_content_hash,
        row.entry_id,
        content,
        row.ts,
      );
      if (!ok) {
        return {
          ok: false,
          count: rows.length,
          brokenAt: row.id,
          reason: 'bad-signature',
          details: `signature at id=${row.id} failed to verify against signer_pubkey`,
        };
      }
      signedCount += 1;
    } else if (requireSignatures) {
      return {
        ok: false,
        count: rows.length,
        brokenAt: row.id,
        reason: 'missing-signature',
        details: `id=${row.id} has no signature (legacy row + requireSignatures=true)`,
      };
    }
    prevHash = row.content_hash;
  }
  return { ok: true, count: rows.length, headHash: prevHash, signedCount };
}

function mapProvenanceRow(r: Record<string, unknown>): ProvenanceRow {
  return {
    id: Number(r['id'] ?? 0),
    scope: String(r['scope'] ?? ''),
    entry_id: String(r['entry_id'] ?? ''),
    content_hash: String(r['content_hash'] ?? ''),
    prev_content_hash:
      r['prev_content_hash'] === null || r['prev_content_hash'] === undefined
        ? null
        : String(r['prev_content_hash']),
    ts: Number(r['ts'] ?? 0),
    signature: r['signature'] === null || r['signature'] === undefined ? null : String(r['signature']),
    signer_pubkey: r['signer_pubkey'] === null || r['signer_pubkey'] === undefined ? null : String(r['signer_pubkey']),
  };
}
