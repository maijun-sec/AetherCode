/**
 * R-MEM-6.3: tests for F8+ type-specific ranking.
 *
 * Pin the procedural success boost, the episodic recency decay,
 * the semantic (no boost) path, and the MemoryStore.findSimilar
 * integration. Also pin the RPC layer exposing
 * `cosineScore` / `boost` / `boostReason`.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import { memoryFind } from '../rpc.js';
import {
  rankByType,
  shouldApplyTypeRanking,
  type RankedVectorRow,
} from '../ranking.js';
import type { VectorRow, MemoryType } from '../vec-store.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem6-rk-'));
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

function makeRow(entryId: string, scope: 'project' | 'global' | 'session' = 'project', memoryType: MemoryType = 'episodic', ts: number = 1000, score: number = 0.5): VectorRow {
  return {
    id: 1,
    scope,
    entry_id: entryId,
    content_text: 'x',
    embedding: new Float32Array([0.5]),
    model_id: 'hash',
    ts,
    media_type: 'text',
    media_ref: null,
    memory_type: memoryType,
    score,
  };
}

describe('R-MEM-6.3: shouldApplyTypeRanking', () => {
  it('returns true only when memoryType is set', () => {
    expect(shouldApplyTypeRanking(undefined)).toBe(false);
    expect(shouldApplyTypeRanking('episodic')).toBe(true);
    expect(shouldApplyTypeRanking('semantic')).toBe(true);
    expect(shouldApplyTypeRanking('procedural')).toBe(true);
  });
});

describe('R-MEM-6.3: rankByType — procedural boost', () => {
  it('boosts a high-success skill over a low-success skill', () => {
    const now = 1000;
    const rows: VectorRow[] = [
      makeRow('skill-global-git_rebase', 'global', 'procedural', 500, 0.45),
      makeRow('skill-global-cargo_test', 'global', 'procedural', 500, 0.50),
    ];
    const ranked = rankByType(rows, {
      now,
      getSuccessCount: (r) => r.entry_id === 'skill-global-git_rebase' ? 50 : 1,
    });
    // The 50-success row should win despite the lower cosine.
    expect(ranked[0]?.entry_id).toBe('skill-global-git_rebase');
    expect(ranked[0]?.boostReason).toBe('procedural-success');
    expect(ranked[0]?.boost ?? 0).toBeGreaterThan(0);
    expect(ranked[0]?.cosine_score).toBe(0.45);
  });

  it('does not boost a procedural row with 0 successes', () => {
    const ranked = rankByType([makeRow('skill-global-foo', 'global', 'procedural', 1000, 0.5)], {
      getSuccessCount: () => 0,
    });
    expect(ranked[0]?.boostReason).toBe('none');
    expect(ranked[0]?.boost).toBe(0);
    expect(ranked[0]?.score).toBe(0.5);
  });
});

describe('R-MEM-6.3: rankByType — episodic recency', () => {
  it('boosts a fresh event over an old one', () => {
    const now = 1_000_000_000;
    const rows: VectorRow[] = [
      makeRow('project-change-1', 'project', 'episodic', now - 5_000, 0.6),
      makeRow('project-change-2', 'project', 'episodic', now - 29 * 24 * 60 * 60 * 1000, 0.6),
    ];
    const ranked = rankByType(rows, { now });
    expect(ranked[0]?.entry_id).toBe('project-change-1');
    expect(ranked[0]?.boostReason).toBe('episodic-recency');
    expect(ranked[0]?.boost ?? 0).toBeGreaterThan(0);
  });

  it('does not boost an old event past the recency window', () => {
    const now = 1_000_000_000;
    const rows: VectorRow[] = [
      makeRow('project-change-1', 'project', 'episodic', now - 60 * 24 * 60 * 60 * 1000, 0.5),
    ];
    const ranked = rankByType(rows, { now });
    expect(ranked[0]?.boostReason).toBe('none');
    expect(ranked[0]?.boost).toBe(0);
  });
});

describe('R-MEM-6.3: rankByType — semantic (no boost)', () => {
  it('does not apply a boost to semantic rows', () => {
    const ranked = rankByType([makeRow('image-abc', 'project', 'semantic', 1000, 0.5)]);
    expect(ranked[0]?.boostReason).toBe('none');
    expect(ranked[0]?.score).toBe(0.5);
  });
});

describe('R-MEM-6.3: rankByType — stability + empty', () => {
  it('returns [] for empty input', () => {
    expect(rankByType([])).toEqual([]);
  });

  it('preserves cosine score in cosine_score', () => {
    const ranked: RankedVectorRow[] = rankByType([makeRow('a', 'project', 'episodic', 1000, 0.7)]);
    expect(ranked[0]?.cosine_score).toBe(0.7);
  });
});

describe('R-MEM-6.3: MemoryStore.findSimilar integration', () => {
  it('findSimilar with procedural memoryType ranks high-success skills first', () => {
    store.upsertSkill({ scope: 'global', name: 'proven', signature: 's', description: 'proven skill' });
    store.upsertSkill({ scope: 'global', name: 'fresh', signature: 's', description: 'fresh skill' });
    // Bump proven's success count.
    for (let i = 0; i < 10; i += 1) store.recordSkillOutcome('global', 'proven', true);
    const out = store.findSimilar('skill', { topK: 10, memoryType: 'procedural' });
    expect(out.rows.length).toBe(2);
    // The proven skill should outrank the fresh one despite
    // the boost on procedural scoring.
    expect(out.rows[0]?.entry_id).toBe('skill-global-proven');
  });

  it('findSimilar without memoryType does not enrich rows', () => {
    store.appendProjectChange('event happened');
    const out = store.findSimilar('event happened', { topK: 10 });
    expect(out.rows.length).toBe(1);
    // Untyped query → no boost fields.
    expect('boost' in (out.rows[0] ?? {})).toBe(false);
  });
});

describe('R-MEM-6.3: RPC layer', () => {
  it('memoryFind returns boost fields for typed queries', () => {
    store.upsertSkill({ scope: 'global', name: 's', signature: 's', description: 'a skill' });
    for (let i = 0; i < 5; i += 1) store.recordSkillOutcome('global', 's', true);
    const out = memoryFind(store, { query: 'skill', memoryType: 'procedural' });
    expect(out.hits.length).toBe(1);
    const hit = out.hits[0];
    expect(hit?.boostReason).toBe('procedural-success');
    expect(hit?.boost ?? 0).toBeGreaterThan(0);
    expect(hit?.cosineScore).not.toBeNull();
  });

  it('memoryFind returns null boost for untyped queries', () => {
    store.appendProjectChange('a change');
    const out = memoryFind(store, { query: 'change' });
    expect(out.hits[0]?.boostReason).toBeNull();
    expect(out.hits[0]?.cosineScore).not.toBeNull();
  });
});
