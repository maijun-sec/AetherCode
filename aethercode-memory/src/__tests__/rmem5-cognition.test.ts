/**
 * R-MEM-5.3: tests for F8 Cognition (memory types).
 *
 * Pin the v11 schema migration (memory_type column on
 * vec_index), the auto-classification on the standard write
 * paths, the search memoryType filter, the
 * `memory/findByType` RPC, and the auto-marking of legacy
 * rows as 'episodic' on migration.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import {
  memoryFind,
  memoryFindByType,
  MemoryRpcError,
} from '../rpc.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem5-cog-'));
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

describe('R-MEM-5.3: schema v11 migration', () => {
  it('adds memory_type column to vec_index', () => {
    const cols = db.prepare(`PRAGMA table_info(vec_index)`).all() as Array<{ name: string }>;
    expect(cols.map((c) => c.name)).toContain('memory_type');
  });

  it('creates idx_vec_index_memory_type', () => {
    const indexes = db.prepare(`PRAGMA index_list(vec_index)`).all() as Array<{ name: string }>;
    expect(indexes.map((i) => i.name)).toContain('idx_vec_index_memory_type');
  });

  it('legacy rows default to episodic', () => {
    store.appendProjectChange('a legacy change');
    const row = db.prepare(`SELECT memory_type FROM vec_index WHERE scope = 'project'`).get() as { memory_type: string };
    expect(row.memory_type).toBe('episodic');
  });
});

describe('R-MEM-5.3: auto-classification on writes', () => {
  it('appendProjectChange marks as episodic', () => {
    store.appendProjectChange('something happened');
    const row = db.prepare(`SELECT memory_type FROM vec_index WHERE scope = 'project'`).get() as { memory_type: string };
    expect(row.memory_type).toBe('episodic');
  });

  it('appendSessionFact marks as episodic', () => {
    store.appendSessionFact('sess-1', {
      kind: 'fact', id: 'f1', ts: 1000, scope: 'session', source: 'user',
      tags: [], key: 'foo', value: 'bar',
    });
    const row = db.prepare(`SELECT memory_type FROM vec_index WHERE scope = 'session'`).get() as { memory_type: string };
    expect(row.memory_type).toBe('episodic');
  });

  it('upsertSkill marks as procedural', () => {
    store.upsertSkill({ scope: 'global', name: 'n', signature: 's', description: 'd' });
    const row = db.prepare(`SELECT memory_type FROM vec_index WHERE scope = 'skill'`).get() as { memory_type: string };
    expect(row.memory_type).toBe('procedural');
  });

  it('appendImage marks as semantic', () => {
    store.appendImage({ scope: 'project', description: 'a screenshot', mediaRef: 'a'.repeat(64) });
    const row = db.prepare(`SELECT memory_type FROM vec_index WHERE media_type = 'image'`).get() as { memory_type: string };
    expect(row.memory_type).toBe('semantic');
  });

  it('global warm-up marks global facts as semantic', () => {
    // Write a global memory file with a fact + a rule.
    const globalPath = join(tmp, 'global-seed.md');
    writeFileSync(globalPath, [
      '# Global Memory',
      '',
      '## Facts',
      '',
      '- user.name: Alice',
      '- user.locale: en-US',
      '',
      '## Rules',
      '',
      '- Always verify signatures before applying patches.',
      '',
    ].join('\n'), 'utf-8');
    // Force a warm by querying global scope.
    const freshDbPath = join(tmp, 'm2.db');
    const fresh = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: join(tmp, 'p.md'),
      sessionsDir: join(tmp, 's'),
      dbPath: freshDbPath,
    });
    fresh.findSimilar('Alice');
    const freshDb = openAndMigrate(freshDbPath);
    const rows = freshDb
      .prepare(`SELECT entry_id, memory_type FROM vec_index WHERE scope = 'global'`)
      .all() as Array<{ entry_id: string; memory_type: string }>;
    // All global rows should be 'semantic'.
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) {
      expect(r.memory_type).toBe('semantic');
    }
    freshDb.close();
    fresh.close();
  });
});

describe('R-MEM-5.3: search memoryType filter', () => {
  it('findSimilar with memoryType filter excludes other types', () => {
    store.appendProjectChange('event happened today');
    store.upsertSkill({ scope: 'global', name: 'evt', signature: 's', description: 'event happened today' });
    // Without filter: both hits
    const all = store.findSimilar('event happened today', { topK: 10 });
    expect(all.rows.length).toBe(2);
    // With episodic filter: only the project change
    const onlyEpisodic = store.findSimilar('event happened today', { topK: 10, memoryType: 'episodic' });
    expect(onlyEpisodic.rows.length).toBe(1);
    expect(onlyEpisodic.rows[0]?.memory_type).toBe('episodic');
    // With procedural filter: only the skill
    const onlyProcedural = store.findSimilar('event happened today', { topK: 10, memoryType: 'procedural' });
    expect(onlyProcedural.rows.length).toBe(1);
    expect(onlyProcedural.rows[0]?.memory_type).toBe('procedural');
  });
});

describe('R-MEM-5.3: RPC handlers', () => {
  it('memory/find returns memoryType in hits', () => {
    store.appendProjectChange('an event');
    store.upsertSkill({ scope: 'global', name: 'k', signature: 's', description: 'an event' });
    const out = memoryFind(store, { query: 'an event' });
    expect(out.hits.length).toBe(2);
    const types = new Set(out.hits.map((h) => h.memoryType));
    expect(types).toContain('episodic');
    expect(types).toContain('procedural');
  });

  it('memory/find passes memoryType through to filter', () => {
    store.appendProjectChange('something happened');
    store.upsertSkill({ scope: 'global', name: 'k', signature: 's', description: 'something happened' });
    const out = memoryFind(store, { query: 'something happened', memoryType: 'procedural' });
    expect(out.hits.length).toBe(1);
    expect(out.hits[0]?.memoryType).toBe('procedural');
  });

  it('memory/find validates memoryType', () => {
    expect(() => memoryFind(store, { query: 'q', memoryType: 'invalid' as 'episodic' })).toThrow(MemoryRpcError);
  });

  it('memory/findByType requires memoryType', () => {
    store.upsertSkill({ scope: 'global', name: 'k', signature: 's', description: 'thing' });
    const out = memoryFindByType(store, { query: 'thing', memoryType: 'procedural' });
    expect(out.ok).toBe(true);
    expect(out.memoryType).toBe('procedural');
    expect(out.hits[0]?.memoryType).toBe('procedural');
  });

  it('memory/findByType validates params', () => {
    expect(() => memoryFindByType(store, { query: '', memoryType: 'episodic' })).toThrow(MemoryRpcError);
    expect(() => memoryFindByType(store, { query: 'q', memoryType: 'invalid' as 'episodic' })).toThrow(MemoryRpcError);
    expect(() => memoryFindByType(store, { query: 'q', memoryType: 'episodic', scope: 'invalid' as 'project' })).toThrow(MemoryRpcError);
    expect(() => memoryFindByType(store, { query: 'q', memoryType: 'episodic', scope: 'session' })).toThrow(MemoryRpcError);
  });
});
