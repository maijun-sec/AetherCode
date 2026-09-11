/**
 * R-MEM-3: tests for subagent shared memory.
 *
 * Pin the v7 schema migration, the project-store helpers
 * (appendTeamChange / listTeamChanges / promoteTeamChanges /
 * countTeamChanges), the high-level MemoryStore API
 * (shareToSubagent / readTeamMemory / promoteFromSubagent), and
 * the RPC layer (memoryShareToSubagent / memoryReadTeamMemory /
 * memoryPromoteFromSubagent).
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import {
  memoryShareToSubagent,
  memoryReadTeamMemory,
  memoryPromoteFromSubagent,
  MemoryRpcError,
} from '../rpc.js';
import {
  appendTeamChange,
  listTeamChanges,
  countTeamChanges,
  promoteTeamChanges,
  appendProjectChange,
  listProjectChanges,
  softDeleteProjectChange,
} from '../project-store.js';
import { upsertProject } from '../project-store.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem3-'));
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

const currentProjectId = (): string => {
  // FNV-1a over the project memory path
  const path = store.projectMemoryPath;
  let h = 0x811c9dc5;
  for (let i = 0; i < path.length; i += 1) {
    h ^= path.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
};

describe('R-MEM-3: schema v7 migration', () => {
  it('adds team_session_id column to project_changes', () => {
    const cols = db.prepare(`PRAGMA table_info(project_changes)`).all() as Array<{ name: string }>;
    const names = cols.map((c) => c.name);
    expect(names).toContain('team_session_id');
  });

  it('creates idx_project_changes_team on team_session_id', () => {
    const indexes = db.prepare(`PRAGMA index_list(project_changes)`).all() as Array<{ name: string }>;
    const names = indexes.map((i) => i.name);
    expect(names).toContain('idx_project_changes_team');
  });

  it('migrates existing rows with team_session_id = NULL', () => {
    const projectId = currentProjectId();
    upsertProject(db, { project_id: projectId, cwd: '/x' });
    const id = appendProjectChange(db, { project_id: projectId, description: 'legacy' });
    const row = db.prepare(`SELECT team_session_id FROM project_changes WHERE id = ?`).get(id) as { team_session_id: string | null };
    expect(row.team_session_id).toBeNull();
  });
});

describe('R-MEM-3: project-store helpers', () => {
  let projectId: string;
  beforeEach(() => {
    projectId = currentProjectId();
    upsertProject(db, { project_id: projectId, cwd: '/x' });
  });

  it('appendTeamChange tags the row with team_session_id', () => {
    const id = appendTeamChange(db, projectId, 'fact-a', 'subagent-1');
    const row = db.prepare(`SELECT team_session_id, description FROM project_changes WHERE id = ?`).get(id) as { team_session_id: string; description: string };
    expect(row.team_session_id).toBe('subagent-1');
    expect(row.description).toBe('fact-a');
  });

  it('appendTeamChange rejects empty project_id / description / teamSessionId', () => {
    expect(() => appendTeamChange(db, '', 'fact', 'sid')).toThrow();
    expect(() => appendTeamChange(db, projectId, '', 'sid')).toThrow();
    expect(() => appendTeamChange(db, projectId, 'fact', '')).toThrow();
  });

  it('listTeamChanges returns team rows and skips normal rows', () => {
    appendProjectChange(db, { project_id: projectId, description: 'normal-1' });
    appendTeamChange(db, projectId, 'team-1', 'sid-A');
    appendTeamChange(db, projectId, 'team-2', 'sid-B');
    appendProjectChange(db, { project_id: projectId, description: 'normal-2' });
    const all = listTeamChanges(db, projectId);
    expect(all).toHaveLength(2);
    expect(all.every((r) => r.team_session_id !== null)).toBe(true);
    expect(all.map((r) => r.description).sort()).toEqual(['team-1', 'team-2']);
  });

  it('listTeamChanges filters by teamSessionId when provided', () => {
    appendTeamChange(db, projectId, 'team-1', 'sid-A');
    appendTeamChange(db, projectId, 'team-2', 'sid-B');
    appendTeamChange(db, projectId, 'team-3', 'sid-A');
    const onlyA = listTeamChanges(db, projectId, 'sid-A');
    expect(onlyA).toHaveLength(2);
    expect(onlyA.every((r) => r.team_session_id === 'sid-A')).toBe(true);
  });

  it('listTeamChanges skips soft-deleted rows', () => {
    const id1 = appendTeamChange(db, projectId, 'team-1', 'sid-A');
    appendTeamChange(db, projectId, 'team-2', 'sid-A');
    softDeleteProjectChange(db, id1);
    const all = listTeamChanges(db, projectId);
    expect(all).toHaveLength(1);
    expect(all[0]?.description).toBe('team-2');
  });

  it('countTeamChanges counts team rows only', () => {
    appendProjectChange(db, { project_id: projectId, description: 'normal' });
    appendTeamChange(db, projectId, 'team-1', 'sid-A');
    appendTeamChange(db, projectId, 'team-2', 'sid-B');
    expect(countTeamChanges(db, projectId)).toBe(2);
  });

  it('promoteTeamChanges clears team_session_id on the given ids', () => {
    const id1 = appendTeamChange(db, projectId, 'team-1', 'sid-A');
    const id2 = appendTeamChange(db, projectId, 'team-2', 'sid-A');
    const id3 = appendTeamChange(db, projectId, 'team-3', 'sid-A');
    const promoted = promoteTeamChanges(db, projectId, 'sid-A', [id1, id2]);
    expect(promoted).toBe(2);
    const rows = db.prepare(
      `SELECT id, team_session_id FROM project_changes WHERE id IN (?, ?, ?) ORDER BY id`,
    ).all(id1, id2, id3) as Array<{ id: number; team_session_id: string | null }>;
    expect(rows[0]?.team_session_id).toBeNull();
    expect(rows[1]?.team_session_id).toBeNull();
    expect(rows[2]?.team_session_id).toBe('sid-A');
  });

  it('promoteTeamChanges returns 0 for empty ids', () => {
    expect(promoteTeamChanges(db, projectId, 'sid', [])).toBe(0);
  });
});

describe('R-MEM-3: MemoryStore high-level API', () => {
  it('shareToSubagent writes team rows and returns ids', () => {
    const out = store.shareToSubagent('sub-1', [
      { description: 'fact-alpha' },
      { description: 'fact-beta' },
    ]);
    expect(out.shared).toBe(2);
    expect(out.ids).toHaveLength(2);
    expect(out.ids[0]).toBeGreaterThan(0);
    const projectId = currentProjectId();
    const rows = listTeamChanges(db, projectId, 'sub-1');
    expect(rows).toHaveLength(2);
    expect(rows.every((r) => r.team_session_id === 'sub-1')).toBe(true);
  });

  it('shareToSubagent returns shared=0 for empty input', () => {
    expect(store.shareToSubagent('sub', [])).toEqual({ shared: 0, ids: [] });
  });

  it('shareToSubagent rejects empty teamSessionId', () => {
    expect(() => store.shareToSubagent('', [{ description: 'x' }])).toThrow();
  });

  it('shareToSubagent rejects empty description', () => {
    expect(() => store.shareToSubagent('sub', [{ description: '' }])).toThrow();
  });

  it('readTeamMemory returns all team rows when no filter', () => {
    store.shareToSubagent('sub-1', [{ description: 'a' }, { description: 'b' }]);
    store.shareToSubagent('sub-2', [{ description: 'c' }]);
    const out = store.readTeamMemory();
    expect(out.count).toBe(3);
    expect(out.entries.map((e) => e.description).sort()).toEqual(['a', 'b', 'c']);
  });

  it('readTeamMemory filters by teamSessionId', () => {
    store.shareToSubagent('sub-1', [{ description: 'a' }]);
    store.shareToSubagent('sub-2', [{ description: 'b' }, { description: 'c' }]);
    const only1 = store.readTeamMemory({ teamSessionId: 'sub-1' });
    expect(only1.count).toBe(1);
    expect(only1.entries[0]?.description).toBe('a');
  });

  it('readTeamMemory skips soft-deleted by default', () => {
    const shared = store.shareToSubagent('sub-1', [
      { description: 'live' },
      { description: 'gone' },
    ]);
    softDeleteProjectChange(db, shared.ids[1]!);
    const out = store.readTeamMemory();
    expect(out.count).toBe(1);
    expect(out.entries[0]?.description).toBe('live');
  });

  it('readTeamMemory includeDeleted returns soft-deleted too', () => {
    const shared = store.shareToSubagent('sub-1', [
      { description: 'live' },
      { description: 'gone' },
    ]);
    softDeleteProjectChange(db, shared.ids[1]!);
    const out = store.readTeamMemory({ includeDeleted: true });
    expect(out.count).toBe(2);
  });

  it('promoteFromSubagent moves rows to normal project memory', () => {
    const shared = store.shareToSubagent('sub-1', [
      { description: 'p1' },
      { description: 'p2' },
    ]);
    const promoted = store.promoteFromSubagent('sub-1', [shared.ids[0]!]);
    expect(promoted).toBe(1);
    // After promote, listTeamChanges no longer sees the promoted row
    const teamRows = store.readTeamMemory({ teamSessionId: 'sub-1' });
    expect(teamRows.count).toBe(1);
    expect(teamRows.entries[0]?.description).toBe('p2');
    // After promote, listProjectChanges sees the promoted row
    const projectId = currentProjectId();
    const normalRows = listProjectChanges(db, projectId, { uncompressedOnly: true });
    const promotedRow = normalRows.find((r) => r.description === 'p1');
    expect(promotedRow).toBeDefined();
    expect(promotedRow?.team_session_id).toBeNull();
  });

  it('promoteFromSubagent returns 0 for empty ids', () => {
    expect(store.promoteFromSubagent('sub', [])).toBe(0);
  });
});

describe('R-MEM-3: RPC handlers', () => {
  it('memoryShareToSubagent writes team rows', () => {
    const out = memoryShareToSubagent(store, {
      teamSessionId: 'sub-rpc',
      entries: [{ description: 'rpc-1' }, { description: 'rpc-2' }],
    });
    expect(out.ok).toBe(true);
    expect(out.shared).toBe(2);
    expect(out.ids).toHaveLength(2);
  });

  it('memoryShareToSubagent validates params', () => {
    expect(() => memoryShareToSubagent(store, { teamSessionId: '', entries: [{ description: 'x' }] }))
      .toThrow(MemoryRpcError);
    expect(() => memoryShareToSubagent(store, { teamSessionId: 'sub', entries: 'not-array' as unknown as Array<{ description: string }> }))
      .toThrow(MemoryRpcError);
    expect(() =>
      memoryShareToSubagent(store, {
        teamSessionId: 'sub',
        entries: [{ description: '' }],
      }),
    ).toThrow(MemoryRpcError);
  });

  it('memoryReadTeamMemory returns shared rows', () => {
    store.shareToSubagent('sub-rpc', [{ description: 'a' }, { description: 'b' }]);
    const out = memoryReadTeamMemory(store, { teamSessionId: 'sub-rpc' });
    expect(out.ok).toBe(true);
    expect(out.count).toBe(2);
    expect(out.entries[0]?.teamSessionId).toBe('sub-rpc');
  });

  it('memoryReadTeamMemory validates params', () => {
    expect(() => memoryReadTeamMemory(store, { teamSessionId: '' })).toThrow(MemoryRpcError);
    expect(() => memoryReadTeamMemory(store, { teamSessionId: 42 as unknown as string })).toThrow(MemoryRpcError);
  });

  it('memoryPromoteFromSubagent promotes ids', () => {
    const shared = store.shareToSubagent('sub-promote', [{ description: 'p' }]);
    const out = memoryPromoteFromSubagent(store, {
      teamSessionId: 'sub-promote',
      ids: [shared.ids[0]!],
    });
    expect(out.ok).toBe(true);
    expect(out.promoted).toBe(1);
  });

  it('memoryPromoteFromSubagent validates params', () => {
    expect(() => memoryPromoteFromSubagent(store, { teamSessionId: '', ids: [1] })).toThrow(MemoryRpcError);
    expect(() => memoryPromoteFromSubagent(store, { teamSessionId: 'sub', ids: 'not-array' as unknown as number[] })).toThrow(MemoryRpcError);
    expect(() => memoryPromoteFromSubagent(store, { teamSessionId: 'sub', ids: [1, 'two' as unknown as number] })).toThrow(MemoryRpcError);
  });
});
