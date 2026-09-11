/**
 * R-MEM-5.2: tests for F7 Trust (provenance chain).
 *
 * Pin the v10 schema migration, the contentHash helper, the
 * chain CRUD (appendProvenance / readChain / countChain /
 * dropChain), the auto-record hooks on the existing write
 * paths, and the RPC layer (memoryRecordProvenance /
 * memoryVerifyChain).
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import {
  memoryRecordProvenance,
  memoryVerifyChain,
  MemoryRpcError,
} from '../rpc.js';
import {
  appendProvenance,
  readChain,
  countChain,
  dropChain,
  verifyChain,
  contentHash,
} from '../provenance-store.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem5-trust-'));
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
});

describe('R-MEM-5.2: schema v10 migration', () => {
  it('creates provenance_chain table', () => {
    const cols = db.prepare(`PRAGMA table_info(provenance_chain)`).all() as Array<{ name: string }>;
    const names = cols.map((c) => c.name);
    for (const expected of ['id', 'scope', 'entry_id', 'content_hash', 'prev_content_hash', 'ts']) {
      expect(names).toContain(expected);
    }
  });

  it('creates idx_provenance_chain_scope and idx_provenance_chain_entry', () => {
    const indexes = db.prepare(`PRAGMA index_list(provenance_chain)`).all() as Array<{ name: string }>;
    const names = indexes.map((i) => i.name);
    expect(names).toContain('idx_provenance_chain_scope');
    expect(names).toContain('idx_provenance_chain_entry');
  });
});

describe('R-MEM-5.2: contentHash helper', () => {
  it('produces a stable 64-char hex hash', () => {
    const h = contentHash('e1', 'hello', 1000);
    expect(h).toMatch(/^[0-9a-f]{64}$/);
    expect(contentHash('e1', 'hello', 1000)).toBe(h);
  });

  it('differs on entry_id, content, or ts change', () => {
    const a = contentHash('a', 'x', 1);
    const b = contentHash('b', 'x', 1);
    const c = contentHash('a', 'y', 1);
    const d = contentHash('a', 'x', 2);
    expect(new Set([a, b, c, d]).size).toBe(4);
  });
});

describe('R-MEM-5.2: chain CRUD', () => {
  it('first row has prev_content_hash = NULL', () => {
    appendProvenance(db, 'project', 'project-change-1', 'first', 1000);
    const rows = readChain(db, 'project');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.prev_content_hash).toBeNull();
    expect(rows[0]?.content_hash).toBe(contentHash('project-change-1', 'first', 1000));
  });

  it('subsequent rows link to the previous hash', () => {
    appendProvenance(db, 'project', 'project-change-1', 'first', 1000);
    appendProvenance(db, 'project', 'project-change-2', 'second', 2000);
    appendProvenance(db, 'project', 'project-change-3', 'third', 3000);
    const rows = readChain(db, 'project');
    expect(rows).toHaveLength(3);
    expect(rows[0]?.prev_content_hash).toBeNull();
    expect(rows[1]?.prev_content_hash).toBe(rows[0]?.content_hash);
    expect(rows[2]?.prev_content_hash).toBe(rows[1]?.content_hash);
  });

  it('chains are scoped (do not leak across scopes)', () => {
    appendProvenance(db, 'project', 'a', 'x', 1);
    appendProvenance(db, 'global', 'b', 'y', 2);
    expect(readChain(db, 'project')).toHaveLength(1);
    expect(readChain(db, 'global')).toHaveLength(1);
  });

  it('countChain returns the right count', () => {
    appendProvenance(db, 'project', 'a', 'x', 1);
    appendProvenance(db, 'project', 'b', 'y', 2);
    expect(countChain(db, 'project')).toBe(2);
  });

  it('dropChain clears a scope', () => {
    appendProvenance(db, 'project', 'a', 'x', 1);
    expect(dropChain(db, 'project')).toBe(1);
    expect(countChain(db, 'project')).toBe(0);
  });

  it('rejects empty scope/entryId', () => {
    expect(() => appendProvenance(db, '', 'e', 'c', 1)).toThrow();
    expect(() => appendProvenance(db, 'p', '', 'c', 1)).toThrow();
  });
});

describe('R-MEM-5.2: verifyChain', () => {
  it('returns ok for an empty chain', () => {
    const r = verifyChain(db, 'project', () => null);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.count).toBe(0);
      expect(r.headHash).toBeNull();
    }
  });

  it('returns ok for a clean chain', () => {
    appendProvenance(db, 'project', 'project-change-1', 'first', 1);
    appendProvenance(db, 'project', 'project-change-2', 'second', 2);
    const r = verifyChain(db, 'project', (eid) => eid === 'project-change-1' ? 'first' : 'second');
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.count).toBe(2);
      expect(r.headHash).toBe(contentHash('project-change-2', 'second', 2));
    }
  });

  it('detects broken-link (rewritten prev_content_hash)', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    appendProvenance(db, 'project', 'b', 'second', 2);
    // Tamper with the chain: rewrite the second row's prev to garbage.
    const garbage = 'f'.repeat(64);
    db.prepare(`UPDATE provenance_chain SET prev_content_hash = ? WHERE entry_id = 'b'`).run(garbage);
    const r = verifyChain(db, 'project', (eid) => eid === 'a' ? 'first' : 'second');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toBe('broken-link');
    }
  });

  it('detects tamper (content hash mismatch with backing store)', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    appendProvenance(db, 'project', 'b', 'second', 2);
    // Provider says 'a' content is "DIFFERENT" — should fail.
    const r = verifyChain(db, 'project', (eid) => eid === 'a' ? 'DIFFERENT' : 'second');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toBe('tamper');
    }
  });

  it('detects missing backing entry', () => {
    appendProvenance(db, 'project', 'a', 'first', 1);
    const r = verifyChain(db, 'project', () => null);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toBe('tamper');
    }
  });
});

describe('R-MEM-5.2: auto-record hooks on write paths', () => {
  it('appendProjectChange records a provenance row', () => {
    expect(countChain(db, 'project')).toBe(0);
    store.appendProjectChange('auto-recorded');
    expect(countChain(db, 'project')).toBe(1);
    const r = store.verifyChain('project');
    expect(r.ok).toBe(true);
  });

  it('appendImage records a provenance row', () => {
    expect(countChain(db, 'project')).toBe(0);
    store.appendImage({ scope: 'project', description: 'shot', mediaRef: 'a'.repeat(64) });
    expect(countChain(db, 'project')).toBe(1);
  });

  it('upsertSkill records a provenance row in the skill scope', () => {
    expect(countChain(db, 'global')).toBe(0);
    store.upsertSkill({ scope: 'global', name: 'n', signature: 's', description: 'd' });
    expect(countChain(db, 'global')).toBe(1);
  });

  it('chain breaks if the backing project_changes row is deleted', () => {
    store.appendProjectChange('first');
    store.appendProjectChange('second');
    expect(store.verifyChain('project').ok).toBe(true);
    // Wipe the underlying content (simulate a rogue cleanup script).
    db.prepare(`DELETE FROM project_changes WHERE id = 1`).run();
    const r = store.verifyChain('project');
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toBe('tamper');
    }
  });
});

describe('R-MEM-5.2: RPC handlers', () => {
  it('memoryRecordProvenance appends a row', () => {
    const out = memoryRecordProvenance(store, { scope: 'project', entryId: 'manual-1', content: 'manual' });
    expect(out.ok).toBe(true);
    expect(out.id).toBeGreaterThan(0);
    expect(countChain(db, 'project')).toBe(1);
  });

  it('memoryRecordProvenance validates params', () => {
    expect(() => memoryRecordProvenance(store, { scope: '', entryId: 'e', content: 'c' })).toThrow(MemoryRpcError);
    expect(() => memoryRecordProvenance(store, { scope: 'project', entryId: '', content: 'c' })).toThrow(MemoryRpcError);
    expect(() => memoryRecordProvenance(store, { scope: 'project', entryId: 'e', content: 42 as unknown as string })).toThrow(MemoryRpcError);
  });

  it('memoryVerifyChain returns ok for a clean chain', () => {
    store.appendProjectChange('a');
    store.appendProjectChange('b');
    const r = memoryVerifyChain(store, { scope: 'project' });
    expect(r.ok).toBe(true);
    expect(r.count).toBe(2);
    expect(r.brokenAt).toBeNull();
  });

  it('memoryVerifyChain returns brokenAt for a tampered chain', () => {
    store.appendProjectChange('a');
    db.prepare(`DELETE FROM project_changes WHERE id = 1`).run();
    const r = memoryVerifyChain(store, { scope: 'project' });
    expect(r.ok).toBe(false);
    expect(r.brokenAt).not.toBeNull();
    expect(r.reason).toBe('tamper');
  });

  it('memoryVerifyChain validates params', () => {
    expect(() => memoryVerifyChain(store, { scope: '' })).toThrow(MemoryRpcError);
  });
});
