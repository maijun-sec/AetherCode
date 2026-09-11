/**
 * R-MEM-4: tests for skill memory.
 *
 * Pin the v8 schema migration, the skill-store helpers
 * (upsertSkill / incrementSkillSuccess / recordSkillFailure /
 * listSkills / getSkill / deleteSkill / findSkillsByText),
 * the high-level MemoryStore API
 * (upsertSkill / recordSkillOutcome / findSkill), and the
 * RPC layer (memoryUpsertSkill / memoryRecordSkillOutcome /
 * memoryFindSkill).
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import {
  memoryUpsertSkill,
  memoryRecordSkillOutcome,
  memoryFindSkill,
  MemoryRpcError,
} from '../rpc.js';
import {
  upsertSkill,
  incrementSkillSuccess,
  recordSkillFailure,
  getSkill,
  listSkills,
  countSkills,
  deleteSkill,
  findSkillsByText,
} from '../skill-store.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem4-'));
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

describe('R-MEM-4: schema v8 migration', () => {
  it('creates the skills table', () => {
    const cols = db.prepare(`PRAGMA table_info(skills)`).all() as Array<{ name: string }>;
    const names = cols.map((c) => c.name);
    for (const expected of [
      'id', 'scope', 'name', 'signature', 'description',
      'tags', 'source', 'success_count', 'last_success_at', 'last_failure_at', 'ts',
    ]) {
      expect(names).toContain(expected);
    }
  });

  it('creates idx_skills_scope and idx_skills_success', () => {
    const indexes = db.prepare(`PRAGMA index_list(skills)`).all() as Array<{ name: string }>;
    const names = indexes.map((i) => i.name);
    expect(names).toContain('idx_skills_scope');
    expect(names).toContain('idx_skills_success');
  });
});

describe('R-MEM-4: skill-store helpers', () => {
  it('upsertSkill inserts a fresh row', () => {
    const id = upsertSkill(db, {
      scope: 'global',
      name: 'git_rebase',
      signature: 'git rebase -i HEAD~N',
      description: 'interactive rebase of last N commits',
      tags: ['git', 'rewrite-history'],
    });
    expect(id).toBeGreaterThan(0);
    const row = getSkill(db, 'global', 'git_rebase');
    expect(row).not.toBeNull();
    expect(row?.signature).toBe('git rebase -i HEAD~N');
    expect(row?.tags).toEqual(['git', 'rewrite-history']);
    expect(row?.success_count).toBe(0);
  });

  it('upsertSkill on conflict updates signature+description and preserves counters', () => {
    const id1 = upsertSkill(db, {
      scope: 'global', name: 'ls_l', signature: 'ls -l', description: 'long list',
    });
    incrementSkillSuccess(db, 'global', 'ls_l');
    incrementSkillSuccess(db, 'global', 'ls_l');
    const id2 = upsertSkill(db, {
      scope: 'global', name: 'ls_l', signature: 'ls -la', description: 'long list + hidden',
    });
    expect(id2).toBe(id1);
    const row = getSkill(db, 'global', 'ls_l');
    expect(row?.signature).toBe('ls -la');
    expect(row?.success_count).toBe(2);
  });

  it('upsertSkill rejects empty scope/name/signature/description', () => {
    const base = { scope: 'global', name: 'n', signature: 's', description: 'd' };
    expect(() => upsertSkill(db, { ...base, scope: '' })).toThrow();
    expect(() => upsertSkill(db, { ...base, name: '' })).toThrow();
    expect(() => upsertSkill(db, { ...base, signature: '' })).toThrow();
    expect(() => upsertSkill(db, { ...base, description: '' })).toThrow();
  });

  it('incrementSkillSuccess bumps success_count + last_success_at', () => {
    upsertSkill(db, { scope: 'g', name: 'n', signature: 's', description: 'd' });
    const t1 = Date.now() - 1000;
    const t2 = Date.now();
    expect(incrementSkillSuccess(db, 'g', 'n', t1)).toBe(true);
    expect(incrementSkillSuccess(db, 'g', 'n', t2)).toBe(true);
    const row = getSkill(db, 'g', 'n');
    expect(row?.success_count).toBe(2);
    expect(row?.last_success_at).toBe(t2);
  });

  it('incrementSkillSuccess returns false for missing skill', () => {
    expect(incrementSkillSuccess(db, 'g', 'missing')).toBe(false);
  });

  it('recordSkillFailure bumps last_failure_at', () => {
    upsertSkill(db, { scope: 'g', name: 'n', signature: 's', description: 'd' });
    const t = Date.now();
    expect(recordSkillFailure(db, 'g', 'n', t)).toBe(true);
    const row = getSkill(db, 'g', 'n');
    expect(row?.last_failure_at).toBe(t);
    expect(row?.success_count).toBe(0);
  });

  it('listSkills returns all skills in scope, ordered by reliability', () => {
    upsertSkill(db, { scope: 'g', name: 'a', signature: 's', description: 'd' });
    upsertSkill(db, { scope: 'g', name: 'b', signature: 's', description: 'd' });
    upsertSkill(db, { scope: 'g', name: 'c', signature: 's', description: 'd' });
    incrementSkillSuccess(db, 'g', 'a', 100);
    incrementSkillSuccess(db, 'g', 'a', 200);
    incrementSkillSuccess(db, 'g', 'b', 300);
    const list = listSkills(db, 'g');
    expect(list.map((s) => s.name)).toEqual(['a', 'b', 'c']);
  });

  it('listSkills is scoped (does not leak across scopes)', () => {
    upsertSkill(db, { scope: 'g1', name: 'shared', signature: 's', description: 'd' });
    upsertSkill(db, { scope: 'g2', name: 'shared', signature: 's', description: 'd' });
    expect(listSkills(db, 'g1')).toHaveLength(1);
    expect(listSkills(db, 'g2')).toHaveLength(1);
  });

  it('countSkills returns the right count', () => {
    upsertSkill(db, { scope: 'g', name: 'a', signature: 's', description: 'd' });
    upsertSkill(db, { scope: 'g', name: 'b', signature: 's', description: 'd' });
    expect(countSkills(db, 'g')).toBe(2);
  });

  it('deleteSkill removes by (scope, name)', () => {
    upsertSkill(db, { scope: 'g', name: 'n', signature: 's', description: 'd' });
    expect(deleteSkill(db, 'g', 'n')).toBe(true);
    expect(getSkill(db, 'g', 'n')).toBeNull();
    expect(deleteSkill(db, 'g', 'n')).toBe(false);
  });

  it('findSkillsByText scores by Jaccard on signature+description+tags', () => {
    upsertSkill(db, { scope: 'g', name: 'git_rebase', signature: 'git rebase -i HEAD~N', description: 'interactive rebase of last N commits', tags: ['git', 'rewrite'] });
    upsertSkill(db, { scope: 'g', name: 'git_reset', signature: 'git reset --hard', description: 'throw away local commits', tags: ['git'] });
    upsertSkill(db, { scope: 'g', name: 'docker_build', signature: 'docker build -t X .', description: 'build a docker image', tags: ['docker'] });
    const hits = findSkillsByText(db, 'git rebase');
    expect(hits.length).toBeGreaterThan(0);
    expect(hits[0]?.skill.name).toBe('git_rebase');
    // 'docker_build' should not appear (no shared tokens)
    expect(hits.every((h) => h.skill.name !== 'docker_build')).toBe(true);
  });

  it('findSkillsByText honours scope filter', () => {
    upsertSkill(db, { scope: 'g1', name: 'a', signature: 'foo bar', description: 'foo' });
    upsertSkill(db, { scope: 'g2', name: 'a', signature: 'foo baz', description: 'foo' });
    const hits = findSkillsByText(db, 'foo', { scope: 'g1' });
    expect(hits).toHaveLength(1);
    expect(hits[0]?.skill.scope).toBe('g1');
  });

  it('findSkillsByText honours topK + minScore', () => {
    upsertSkill(db, { scope: 'g', name: 'a', signature: 'common common', description: 'common' });
    upsertSkill(db, { scope: 'g', name: 'b', signature: 'common', description: 'common' });
    upsertSkill(db, { scope: 'g', name: 'c', signature: 'common', description: 'unique-token' });
    const top1 = findSkillsByText(db, 'common', { topK: 1 });
    expect(top1).toHaveLength(1);
    const onlyAbove = findSkillsByText(db, 'common', { minScore: 0.9 });
    expect(onlyAbove.every((h) => h.score >= 0.9)).toBe(true);
  });

  it('findSkillsByText rejects empty query', () => {
    expect(() => findSkillsByText(db, '')).toThrow();
  });
});

describe('R-MEM-4: MemoryStore high-level API', () => {
  it('upsertSkill returns the id and is queryable via findSkill', () => {
    const id = store.upsertSkill({
      scope: 'global',
      name: 'cargo_test',
      signature: 'cargo test --workspace',
      description: 'run all tests in the workspace',
      tags: ['rust', 'test'],
    });
    expect(id).toBeGreaterThan(0);
    const hits = store.findSkill('cargo test');
    expect(hits.length).toBeGreaterThan(0);
    expect(hits[0]?.skill.name).toBe('cargo_test');
  });

  it('upsertSkill embeds the skill into the vector index', () => {
    store.upsertSkill({
      scope: 'global',
      name: 'kb_compact',
      signature: 'sqlite VACUUM',
      description: 'shrink the sqlite database file',
      tags: ['sqlite'],
    });
    const vec = store.findSimilar('shrink sqlite database');
    const hit = vec.rows.find((r) => r.entry_id === 'skill-global-kb_compact');
    expect(hit).toBeDefined();
  });

  it('recordSkillOutcome ok=true increments success', () => {
    store.upsertSkill({ scope: 'global', name: 'n', signature: 'do the thing', description: 'thing to do' });
    expect(store.recordSkillOutcome('global', 'n', true)).toBe(true);
    expect(store.recordSkillOutcome('global', 'n', true)).toBe(true);
    const hits = store.findSkill('thing');
    expect(hits[0]?.skill.success_count).toBe(2);
  });

  it('recordSkillOutcome ok=false bumps last_failure_at', () => {
    store.upsertSkill({ scope: 'global', name: 'n', signature: 'do the thing', description: 'thing to do' });
    store.recordSkillOutcome('global', 'n', false);
    const hits = store.findSkill('thing');
    expect(hits[0]?.skill.last_failure_at).not.toBeNull();
    expect(hits[0]?.skill.success_count).toBe(0);
  });

  it('recordSkillOutcome returns false for missing skill', () => {
    expect(store.recordSkillOutcome('global', 'missing', true)).toBe(false);
  });

  it('findSkill orders by score desc + success_count desc', () => {
    store.upsertSkill({ scope: 'global', name: 'high-trust', signature: 'test runner', description: 'run the test suite' });
    store.upsertSkill({ scope: 'global', name: 'low-trust', signature: 'test runner', description: 'run the test suite' });
    // Bump high-trust's success count
    store.recordSkillOutcome('global', 'high-trust', true);
    store.recordSkillOutcome('global', 'high-trust', true);
    store.recordSkillOutcome('global', 'high-trust', true);
    const hits = store.findSkill('test runner');
    expect(hits[0]?.skill.name).toBe('high-trust');
  });
});

describe('R-MEM-4: RPC handlers', () => {
  it('memoryUpsertSkill returns id + name + scope', () => {
    const out = memoryUpsertSkill(store, {
      scope: 'global',
      name: 'kb_skill',
      signature: 'skill',
      description: 'a knowledge-base skill',
    });
    expect(out.ok).toBe(true);
    expect(out.name).toBe('kb_skill');
    expect(out.scope).toBe('global');
    expect(out.id).toBeGreaterThan(0);
  });

  it('memoryUpsertSkill validates params', () => {
    expect(() => memoryUpsertSkill(store, {
      scope: '', name: 'n', signature: 's', description: 'd',
    })).toThrow(MemoryRpcError);
    expect(() => memoryUpsertSkill(store, {
      scope: 'g', name: 'n', signature: '', description: 'd',
    })).toThrow(MemoryRpcError);
    expect(() => memoryUpsertSkill(store, {
      scope: 'g', name: 'n', signature: 's', description: '',
    })).toThrow(MemoryRpcError);
  });

  it('memoryRecordSkillOutcome validates ok', () => {
    store.upsertSkill({ scope: 'g', name: 'n', signature: 's', description: 'd' });
    expect(() => memoryRecordSkillOutcome(store, {
      scope: 'g', name: 'n', ok: 'true' as unknown as boolean,
    })).toThrow(MemoryRpcError);
    const ok = memoryRecordSkillOutcome(store, { scope: 'g', name: 'n', ok: true });
    expect(ok.recorded).toBe(true);
  });

  it('memoryFindSkill returns hits with score', () => {
    store.upsertSkill({ scope: 'g', name: 'git_status', signature: 'git status -sb', description: 'short branch status', tags: ['git'] });
    const out = memoryFindSkill(store, { query: 'git status', scope: 'g' });
    expect(out.ok).toBe(true);
    expect(out.count).toBeGreaterThan(0);
    expect(out.hits[0]?.name).toBe('git_status');
    expect(out.hits[0]?.score).toBeGreaterThan(0);
  });

  it('memoryFindSkill validates params', () => {
    expect(() => memoryFindSkill(store, { query: '' })).toThrow(MemoryRpcError);
    expect(() => memoryFindSkill(store, { query: 'q', scope: '' })).toThrow(MemoryRpcError);
  });
});
