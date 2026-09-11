/**
 * R-MEM-1: tests for VectorStore.
 *
 * The vector store is a SQLite-backed in-memory index. The tests
 * pin its contract:
 *  - Schema: `vec_index` table is created on construction.
 *  - Upsert is idempotent: (scope, entry_id) collision replaces
 *    the row, not adds a new one.
 *  - bulkUpsert runs in a single transaction (verified by
 *    post-condition row count, not by transaction introspection).
 *  - Search returns up to topK above threshold, sorted by score.
 *  - Stale-model rows (model_id mismatch) are skipped silently.
 *  - delete / clearScope / clearAll are working.
 *  - Entry-id filter: search can exclude the query's own row.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import {
  VectorStore,
  cosineSimilarity,
} from '../vec-store.js';
import { HashEmbeddingProvider } from '../embedding/hash-embedding.js';
import { withDatabase } from '../sqlite.js';

describe('R-MEM-1: VectorStore', () => {
  let db: Database.Database;
  let store: VectorStore;
  let tmp: string;

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem1-vec-'));
    db = new Database(join(tmp, 'test.db'));
    store = new VectorStore(db, new HashEmbeddingProvider(64));
  });

  afterEach(() => {
    db.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('exposes the provider modelId and dim', () => {
    expect(store.modelId).toBe('hash-v1-dim64');
    expect(store.dim).toBe(64);
  });

  it('upsert + count', () => {
    expect(store.count()).toBe(0);
    store.upsert('project', 'a', 'hello world');
    store.upsert('project', 'b', 'goodbye world');
    expect(store.count()).toBe(2);
    expect(store.count('project')).toBe(2);
    expect(store.count('global')).toBe(0);
  });

  it('upsert is idempotent on (scope, entry_id)', () => {
    store.upsert('project', 'a', 'hello world');
    store.upsert('project', 'a', 'hello world v2');
    expect(store.count('project')).toBe(1);
  });

  it('search returns up to topK sorted by score', () => {
    store.upsert('project', 'a', 'alpha beta gamma');
    store.upsert('project', 'b', 'alpha beta delta');
    store.upsert('project', 'c', 'completely unrelated topic');
    const q = store['provider'].embed('alpha beta');
    const result = store.search({ query: q, topK: 2 });
    expect(result.rows.length).toBe(2);
    // First two should be the alpha/beta rows (highest scores)
    expect(['a', 'b']).toContain(result.rows[0]!.entry_id);
    expect(['a', 'b']).toContain(result.rows[1]!.entry_id);
    expect(result.rows[0]!.score).toBeGreaterThanOrEqual(result.rows[1]!.score!);
  });

  it('search respects threshold', () => {
    store.upsert('project', 'a', 'alpha beta');
    store.upsert('project', 'b', 'completely unrelated topic');
    const q = store['provider'].embed('alpha beta');
    const result = store.search({ query: q, threshold: 0.99 });
    // Only the very-close match should pass
    expect(result.rows.length).toBeLessThanOrEqual(1);
  });

  it('search filters by scope', () => {
    store.upsert('project', 'a', 'apple');
    store.upsert('global', 'b', 'apple');
    const q = store['provider'].embed('apple');
    const projectOnly = store.search({ query: q, scope: 'project' });
    expect(projectOnly.rows.map((r) => r.entry_id)).toEqual(['a']);
  });

  it('search excludes its own entryId when filter is set', () => {
    store.upsert('project', 'a', 'alpha');
    store.upsert('project', 'b', 'alpha');
    const q = store['provider'].embed('alpha');
    const result = store.search({ query: q, entryId: 'a' });
    expect(result.rows.map((r) => r.entry_id)).toEqual(['b']);
  });

  it('search rejects query of wrong length', () => {
    const wrong = new Float32Array(32);
    expect(() => store.search({ query: wrong })).toThrow(/length/);
  });

  it('search silently skips stale-model rows', () => {
    // Manually insert a row with a different model_id, then
    // search — the row should be filtered out.
    const stmt = db.prepare(
      `INSERT INTO vec_index (scope, entry_id, content_text, embedding, model_id, ts)
       VALUES (?, ?, ?, ?, ?, ?)`,
    );
    const fakeVec = Buffer.alloc(64 * 4);
    stmt.run('project', 'stale', 'old text', fakeVec, 'different-model-v99', Date.now());
    expect(store.count('project')).toBe(1);
    const q = store['provider'].embed('old text');
    const result = store.search({ query: q });
    expect(result.rows.length).toBe(0);
  });

  it('delete removes a single (scope, entry_id) row', () => {
    store.upsert('project', 'a', 'a');
    store.upsert('project', 'b', 'b');
    const out = store.delete('project', 'a');
    expect(out.changes).toBe(1);
    expect(store.count('project')).toBe(1);
  });

  it('clearScope removes only that scope', () => {
    store.upsert('project', 'a', 'a');
    store.upsert('global', 'b', 'b');
    store.clearScope('project');
    expect(store.count('project')).toBe(0);
    expect(store.count('global')).toBe(1);
  });

  it('clearAll removes everything', () => {
    store.upsert('project', 'a', 'a');
    store.upsert('global', 'b', 'b');
    store.upsert('session', 'c', 'c');
    store.clearAll();
    expect(store.count()).toBe(0);
  });

  it('bulkUpsert inserts many rows in one call', () => {
    store.bulkUpsert([
      { scope: 'project', entryId: 'a', contentText: 'a' },
      { scope: 'project', entryId: 'b', contentText: 'b' },
      { scope: 'global', entryId: 'c', contentText: 'c' },
    ]);
    expect(store.count('project')).toBe(2);
    expect(store.count('global')).toBe(1);
  });

  it('bulkUpsert with empty array is a no-op', () => {
    expect(() => store.bulkUpsert([])).not.toThrow();
    expect(store.count()).toBe(0);
  });

  it('upsertEmbedding rejects wrong-dim embedding', () => {
    expect(() => store.upsertEmbedding('project', 'a', 'a', new Float32Array(8))).toThrow(/length/);
  });

  it('cosineSimilarity is symmetric and bounded', () => {
    const a = new Float32Array([1, 0, 0]);
    const b = new Float32Array([0, 1, 0]);
    const c = new Float32Array([1, 0, 0]);
    expect(cosineSimilarity(a, b)).toBeCloseTo(0, 5);
    expect(cosineSimilarity(a, c)).toBeCloseTo(1, 5);
  });

  it('cosineSimilarity throws on length mismatch', () => {
    expect(() => cosineSimilarity(new Float32Array(3), new Float32Array(4))).toThrow();
  });

  it('coexists with the memory store migrations on the same db', () => {
    // Realistic scenario: the memory module's openAndMigrate
    // already created the v1-v4 tables; the VectorStore's
    // constructor adds v5. They must not collide.
    const tmp2 = mkdtempSync(join(tmpdir(), 'rmem1-coexist-'));
    try {
      withDatabase(join(tmp2, 'mem.db'), (db2) => {
        // openAndMigrate applies v1-v5
        const s = new VectorStore(db2, new HashEmbeddingProvider(64));
        s.upsert('project', 'x', 'hello');
        expect(s.count('project')).toBe(1);
      });
    } finally {
      rmSync(tmp2, { recursive: true, force: true });
    }
  });
});
