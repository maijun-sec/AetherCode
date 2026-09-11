/**
 * R-MEM-6.2: tests for F7+ Ed25519 signed chain.
 *
 * Pin the signing helpers (signingInput, signRow,
 * verifyRowSignature), the v12 schema migration (signature +
 * signer_pubkey columns), appendProvenanceSigned,
 * verifySignedChain (good / bad-signature / missing-signature
 * / requireSignatures paths), the auto-record hook on the
 * standard write paths, and the RPC layer.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import {
  memoryVerifySignedChain,
  MemoryRpcError,
} from '../rpc.js';
import {
  signRow,
  verifyRowSignature,
  signingInput,
  getSignerPublicKeyHex,
  isEphemeralKey,
  _resetSigningKeyForTests,
} from '../signing.js';
import {
  appendProvenance,
  appendProvenanceSigned,
  readChain,
  verifySignedChain,
} from '../provenance-store.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  _resetSigningKeyForTests();
  tmp = mkdtempSync(join(tmpdir(), 'rmem6-sig-'));
  store = createMemoryStore({
    globalMemoryPath: join(tmp, 'g.md'),
    projectMemoryPath: join(tmp, 'p.md'),
    sessionsDir: join(tmp, 's'),
    dbPath: join(tmp, 'm.db'),
  });
  db = openAndMigrate(join(tmp, 'm.db'));
});

afterEach(() => {
  db.close();
  store.close();
  rmSync(tmp, { recursive: true, force: true });
  _resetSigningKeyForTests();
});

describe('R-MEM-6.2: schema v12 migration', () => {
  it('adds signature + signer_pubkey columns to provenance_chain', () => {
    const cols = db.prepare(`PRAGMA table_info(provenance_chain)`).all() as Array<{ name: string }>;
    const names = cols.map((c) => c.name);
    expect(names).toContain('signature');
    expect(names).toContain('signer_pubkey');
  });
});

describe('R-MEM-6.2: signing helpers', () => {
  it('signingInput is a 32-byte SHA-256 digest', () => {
    const buf = signingInput('prev', 'entry', 'content', 1000);
    expect(buf.length).toBe(32);
  });

  it('signRow + verifyRowSignature roundtrip succeeds', () => {
    const sig = signRow('prev', 'entry', 'content', 1000);
    const pubkey = getSignerPublicKeyHex();
    expect(verifyRowSignature(sig, pubkey, 'prev', 'entry', 'content', 1000)).toBe(true);
  });

  it('verifyRowSignature detects tampering', () => {
    const sig = signRow('prev', 'entry', 'content', 1000);
    const pubkey = getSignerPublicKeyHex();
    expect(verifyRowSignature(sig, pubkey, 'TAMPERED', 'entry', 'content', 1000)).toBe(false);
    expect(verifyRowSignature(sig, pubkey, 'prev', 'WRONG', 'content', 1000)).toBe(false);
  });

  it('isEphemeralKey is true without AETHERCODE_MEMORY_KEY', () => {
    expect(isEphemeralKey()).toBe(true);
  });
});

describe('R-MEM-6.2: appendProvenanceSigned', () => {
  it('writes signature + signer_pubkey columns', () => {
    const out = appendProvenanceSigned(db, 'project', 'project-change-1', 'first', 1000);
    expect(out.id).toBeGreaterThan(0);
    expect(out.signerPubkey).toMatch(/^[0-9a-f]{64}$/);
    const rows = readChain(db, 'project');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.signature).toBeTruthy();
    expect(rows[0]?.signer_pubkey).toBe(out.signerPubkey);
  });

  it('links prev_content_hash to the previous row', () => {
    appendProvenanceSigned(db, 'project', 'a', 'first', 1);
    appendProvenanceSigned(db, 'project', 'b', 'second', 2);
    const rows = readChain(db, 'project');
    expect(rows[1]?.prev_content_hash).toBe(rows[0]?.content_hash);
  });
});

describe('R-MEM-6.2: appendProvenance (legacy unsigned)', () => {
  it('writes rows with NULL signature (legacy compat)', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    const rows = readChain(db, 'project');
    expect(rows[0]?.signature).toBeNull();
    expect(rows[0]?.signer_pubkey).toBeNull();
  });
});

describe('R-MEM-6.2: verifySignedChain', () => {
  it('returns ok for a clean signed chain', () => {
    appendProvenanceSigned(db, 'project', 'a', 'first', 1);
    appendProvenanceSigned(db, 'project', 'b', 'second', 2);
    const r = verifySignedChain(db, 'project', (eid) => eid === 'a' ? 'first' : 'second');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.count).toBe(2);
      expect(r.signedCount).toBe(2);
    }
  });

  it('detects bad-signature when content is tampered', () => {
    appendProvenanceSigned(db, 'project', 'a', 'first', 1);
    appendProvenanceSigned(db, 'project', 'b', 'second', 2);
    const r = verifySignedChain(db, 'project', (eid) => eid === 'a' ? 'first' : 'TAMPERED');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      // The first row's signature still verifies; the second
      // row's hash recompute fails first (tamper, not bad-signature).
      expect(['tamper', 'bad-signature']).toContain(r.reason);
    }
  });

  it('returns ok with missing-signature for legacy rows when requireSignatures=false', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    const r = verifySignedChain(db, 'project', () => 'first');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.signedCount).toBe(0);
    }
  });

  it('fails missing-signature when requireSignatures=true', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    const r = verifySignedChain(db, 'project', () => 'first', { requireSignatures: true });
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toBe('missing-signature');
    }
  });
});

describe('R-MEM-6.2: auto-record hooks (signed)', () => {
  it('appendProjectChange writes a signed provenance row', () => {
    store.appendProjectChange('a change');
    const rows = readChain(db, 'project');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.signature).toBeTruthy();
    expect(rows[0]?.signer_pubkey).toMatch(/^[0-9a-f]{64}$/);
  });

  it('verifySignedChain returns ok on auto-recorded chain', () => {
    store.appendProjectChange('a change');
    store.appendProjectChange('b change');
    const r = store.verifySignedChain('project');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.count).toBe(2);
      expect(r.signedCount).toBe(2);
    }
  });
});

describe('R-MEM-6.2: RPC layer', () => {
  it('memoryVerifySignedChain returns ok for a clean chain', () => {
    store.appendProjectChange('a');
    store.appendProjectChange('b');
    const out = memoryVerifySignedChain(store, { scope: 'project' });
    expect(out.ok).toBe(true);
    expect(out.signedCount).toBe(2);
  });

  it('memoryVerifySignedChain returns signedCount=null on tamper', () => {
    store.appendProjectChange('a');
    db.prepare(`DELETE FROM project_changes WHERE id = 1`).run();
    const out = memoryVerifySignedChain(store, { scope: 'project' });
    expect(out.ok).toBe(false);
    expect(out.signedCount).toBeNull();
  });

  it('memoryVerifySignedChain validates params', () => {
    expect(() => memoryVerifySignedChain(store, { scope: '' })).toThrow(MemoryRpcError);
  });
});
