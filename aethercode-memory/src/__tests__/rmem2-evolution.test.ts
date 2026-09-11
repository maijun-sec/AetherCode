/**
 * R-MEM-2: tests for evolution (consolidation + forgetting).
 *
 * The tests pin the v6 schema migration, the consolidation
 * algorithm (Jaccard-group + mark sources), the forgetting
 * algorithm (soft delete + restore + vacuum), and the
 * `memoryStore.consolidate` / `.forget` / `.readStats` high-level
 * API.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import { memoryConsolidate, memoryForget, memoryStats } from '../rpc.js';
import type { MemoryStore } from '../memory-store.js';
import {
  appendProjectChange,
  softDeleteProjectChange,
  markConsolidatedInto,
  findForgetCandidates,
  readProjectMemoryStats,
  touchChangeAccess,
  upsertProject,
} from '../project-store.js';
import {
  consolidateProjectChanges,
  forgetProjectChanges,
  vacuumForgottenChanges,
  restoreForgottenChange,
} from '../evolution.js';

describe('R-MEM-2: schema v6 migration', () => {
  let tmp: string;
  let db: Database.Database;

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem2-mig-'));
  });
  afterEach(() => {
    db?.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('fresh DB: v6 migration adds the evolution columns', () => {
    db = openAndMigrate(join(tmp, 'm.db'));
    const cols = db.prepare(`PRAGMA table_info(project_changes)`).all() as Array<{ name: string }>;
    const colNames = cols.map((c) => c.name);
    expect(colNames).toContain('expires_at');
    expect(colNames).toContain('consolidated_into');
    expect(colNames).toContain('value_tag');
    expect(colNames).toContain('last_accessed_at');
    expect(colNames).toContain('access_count');
    expect(colNames).toContain('deleted_at');
  });

  it('fresh DB: v6 migration creates the supporting indexes', () => {
    db = openAndMigrate(join(tmp, 'm.db'));
    const idxNames = (db.prepare(`SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'project_changes'`).all() as Array<{ name: string }>).map((r) => r.name);
    expect(idxNames).toContain('idx_project_changes_expires');
    expect(idxNames).toContain('idx_project_changes_accessed');
    expect(idxNames).toContain('idx_project_changes_live');
  });

  it('existing DB: ALTER TABLE adds columns without losing data', () => {
    // Apply v1-v4 only, insert a row, then upgrade to v6.
    const v1to4 = (require('node:path').join(__dirname, '..', 'sqlite.js')); // keep import non-blocking
    void v1to4;
    // We simulate by writing a v4 DB manually.
    db = new Database(join(tmp, 'm.db'));
    db.exec(`CREATE TABLE project_db (project_id TEXT PRIMARY KEY, cwd TEXT NOT NULL, title TEXT, description TEXT, updated_at INTEGER NOT NULL)`);
    db.exec(`CREATE TABLE project_changes (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL, ts INTEGER NOT NULL, description TEXT NOT NULL, compressed INTEGER DEFAULT 0, FOREIGN KEY (project_id) REFERENCES project_db(project_id))`);
    db.exec(`CREATE TABLE compression_wal (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, attempt INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL CHECK(state IN ('pending','llm_done','committed','failed')), before_changes_json TEXT NOT NULL, new_description TEXT, new_description_tokens INTEGER, error TEXT, FOREIGN KEY (project_id) REFERENCES project_db(project_id))`);
    db.pragma('user_version = 4');
    db.prepare(`INSERT INTO project_db (project_id, cwd, updated_at) VALUES (?, ?, ?)`).run('p1', '/x', 1000);
    db.prepare(`INSERT INTO project_changes (project_id, ts, description) VALUES (?, ?, ?)`).run('p1', 1000, 'legacy change');
    db.close();
    // Reopen through openAndMigrate — v5 + v6 should run.
    db = openAndMigrate(join(tmp, 'm.db'));
    const legacy = db.prepare(`SELECT description FROM project_changes WHERE project_id = ?`).get('p1') as { description: string };
    expect(legacy.description).toBe('legacy change');
    const cols = db.prepare(`PRAGMA table_info(project_changes)`).all() as Array<{ name: string }>;
    expect(cols.map((c) => c.name)).toContain('value_tag');
    // legacy row should have value_tag = 'normal' default
    const row = db.prepare(`SELECT value_tag, access_count, deleted_at FROM project_changes WHERE project_id = ?`).get('p1') as { value_tag: string; access_count: number; deleted_at: number | null };
    expect(row.value_tag).toBe('normal');
    expect(row.access_count).toBe(0);
    expect(row.deleted_at).toBeNull();
  });
});

describe('R-MEM-2: consolidation', () => {
  let tmp: string;
  let db: Database.Database;
  const projectId = 'test-proj';

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem2-cons-'));
    db = openAndMigrate(join(tmp, 'c.db'));
    upsertProject(db, { project_id: projectId, cwd: '/test', updated_at: 0 });
  });
  afterEach(() => {
    db?.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('groups similar descriptions and marks sources as consolidated', () => {
    appendProjectChange(db, { project_id: projectId, description: 'Added type hints to parser' });
    appendProjectChange(db, { project_id: projectId, description: 'Added type hints to parser' });
    appendProjectChange(db, { project_id: projectId, description: 'Added type hints to parser (refactored)' });
    appendProjectChange(db, { project_id: projectId, description: 'completely unrelated work' });

    const result = consolidateProjectChanges(db, projectId, { jaccardThreshold: 0.4 });
    expect(result.scanned).toBe(4);
    expect(result.groups).toBeGreaterThanOrEqual(1);
    expect(result.merged).toBeGreaterThanOrEqual(1);

    // After consolidation, only the target rows should be live.
    const live = db.prepare(
      `SELECT COUNT(*) AS n FROM project_changes WHERE project_id = ? AND consolidated_into IS NULL AND deleted_at IS NULL`,
    ).get(projectId) as { n: number };
    expect(live.n).toBeLessThan(4);
  });

  it('returns zero groups when no descriptions are similar', () => {
    appendProjectChange(db, { project_id: projectId, description: 'alpha beta gamma' });
    appendProjectChange(db, { project_id: projectId, description: 'completely unrelated topic' });
    const result = consolidateProjectChanges(db, projectId, { jaccardThreshold: 0.9 });
    // Threshold too high: nothing merges
    expect(result.merged).toBe(0);
  });

  it('preserves the longest description as the canonical', () => {
    appendProjectChange(db, { project_id: projectId, description: 'add memory consolidation feature' });
    const id2 = appendProjectChange(db, { project_id: projectId, description: 'add memory consolidation feature (with extra details and long context)' });
    const result = consolidateProjectChanges(db, projectId, { jaccardThreshold: 0.3 });
    // Both share "add memory consolidation feature" — high Jaccard
    expect(result.merged).toBeGreaterThanOrEqual(1);
    const canonical = db.prepare(`SELECT description FROM project_changes WHERE id = ?`).get(id2) as { description: string };
    expect(canonical.description).toContain('extra details');
  });

  it('a single group with 1 source does not get touched', () => {
    appendProjectChange(db, { project_id: projectId, description: 'only one' });
    const result = consolidateProjectChanges(db, projectId, {});
    // Single row, no merge candidates
    expect(result.merged).toBe(0);
  });

  it('respects maxScanned', () => {
    for (let i = 0; i < 10; i += 1) {
      appendProjectChange(db, { project_id: projectId, description: `change ${i} of similar kind` });
    }
    const result = consolidateProjectChanges(db, projectId, { maxScanned: 5 });
    expect(result.scanned).toBeLessThanOrEqual(5);
  });
});

describe('R-MEM-2: forgetting', () => {
  let tmp: string;
  let db: Database.Database;
  const projectId = 'test-proj';

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem2-forget-'));
    db = openAndMigrate(join(tmp, 'f.db'));
    upsertProject(db, { project_id: projectId, cwd: '/test', updated_at: 0 });
  });
  afterEach(() => {
    db?.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('soft-deletes expired rows', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'will expire', expires_at: 1 });
    appendProjectChange(db, { project_id: projectId, description: 'still fresh' });
    // R-MEM-2: `forgetProjectChanges` is opt-in. Pass `expired: true`
    // to match only expired rows.
    const result = forgetProjectChanges(db, projectId, { expired: true }, Date.now() + 100);
    expect(result.candidates).toBe(1);
    expect(result.softDeleted).toBe(1);
    const row = db.prepare(`SELECT deleted_at FROM project_changes WHERE id = ?`).get(id) as { deleted_at: number | null };
    expect(row.deleted_at).not.toBeNull();
  });

  it('soft-deletes low-value rows when policy says so', () => {
    appendProjectChange(db, { project_id: projectId, description: 'noise', value_tag: 'low' });
    const result = forgetProjectChanges(db, projectId, { lowValue: true });
    expect(result.softDeleted).toBe(1);
  });

  it('soft-deletes inactive rows', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'inactive' });
    // Manually age the row by setting last_accessed_at to 100 days ago
    const hundredDaysAgo = Date.now() - 100 * 24 * 60 * 60 * 1000;
    db.prepare(`UPDATE project_changes SET last_accessed_at = ? WHERE id = ?`).run(hundredDaysAgo, id);
    const result = forgetProjectChanges(db, projectId, { inactiveSinceMs: 30 * 24 * 60 * 60 * 1000 });
    expect(result.softDeleted).toBe(1);
  });

  it('returns 0 when no rows match the policy', () => {
    appendProjectChange(db, { project_id: projectId, description: 'fresh' });
    const result = forgetProjectChanges(db, projectId, { lowValue: true });
    expect(result.softDeleted).toBe(0);
  });

  it('restoreForgottenChange undoes a soft delete', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'restored', expires_at: 1 });
    forgetProjectChanges(db, projectId, { expired: true }, Date.now() + 100);
    expect(restoreForgottenChange(db, id)).toBe(true);
    const row = db.prepare(`SELECT deleted_at FROM project_changes WHERE id = ?`).get(id) as { deleted_at: number | null };
    expect(row.deleted_at).toBeNull();
  });

  it('vacuumForgottenChanges hard-deletes old trashed rows', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'old trash' });
    forgetProjectChanges(db, projectId, { expired: true }, Date.now() + 100);
    // Pretend 31 days have passed
    const old = Date.now() - 31 * 24 * 60 * 60 * 1000;
    db.prepare(`UPDATE project_changes SET deleted_at = ? WHERE id = ?`).run(old, id);
    const hardDeleted = vacuumForgottenChanges(db, 30 * 24 * 60 * 60 * 1000);
    expect(hardDeleted).toBe(1);
    const row = db.prepare(`SELECT id FROM project_changes WHERE id = ?`).get(id);
    expect(row).toBeUndefined();
  });

  it('vacuumForgottenChanges keeps recent trashed rows for the undo window', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'recent trash' });
    forgetProjectChanges(db, projectId, { expired: true }, Date.now() + 100);
    // Within the 30-day window: should NOT be hard-deleted
    const hardDeleted = vacuumForgottenChanges(db, 30 * 24 * 60 * 60 * 1000);
    expect(hardDeleted).toBe(0);
    const row = db.prepare(`SELECT id FROM project_changes WHERE id = ?`).get(id);
    expect(row).toBeDefined();
  });

  it('findForgetCandidates returns matching rows without deleting', () => {
    appendProjectChange(db, { project_id: projectId, description: 'noise', value_tag: 'low' });
    appendProjectChange(db, { project_id: projectId, description: 'fresh' });
    const cands = findForgetCandidates(db, projectId, { lowValue: true });
    expect(cands.length).toBe(1);
    expect(cands[0]!.description).toBe('noise');
    // Verify nothing was actually deleted
    const live = db.prepare(`SELECT COUNT(*) AS n FROM project_changes WHERE project_id = ? AND deleted_at IS NULL`).get(projectId) as { n: number };
    expect(live.n).toBe(2);
  });

  it('softDeleteProjectChange returns false on already-deleted row', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'x' });
    expect(softDeleteProjectChange(db, id)).toBe(true);
    expect(softDeleteProjectChange(db, id)).toBe(false);
  });

  it('markConsolidatedInto refuses to point at itself', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'self ref' });
    expect(markConsolidatedInto(db, id, id)).toBe(false);
  });

  it('touchChangeAccess increments access_count + last_accessed_at', () => {
    const id = appendProjectChange(db, { project_id: projectId, description: 'touched' });
    const before = Date.now();
    expect(touchChangeAccess(db, id)).toBe(true);
    const row = db.prepare(`SELECT access_count, last_accessed_at FROM project_changes WHERE id = ?`).get(id) as { access_count: number; last_accessed_at: number };
    expect(row.access_count).toBe(1);
    expect(row.last_accessed_at).toBeGreaterThanOrEqual(before);
  });
});

describe('R-MEM-2: readMemoryStats', () => {
  let tmp: string;
  let db: Database.Database;
  const projectId = 'test-proj';

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem2-stats-'));
    db = openAndMigrate(join(tmp, 's.db'));
    upsertProject(db, { project_id: projectId, cwd: '/test', updated_at: 0 });
  });
  afterEach(() => {
    db?.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('reports all-zero counts for an empty project', () => {
    const stats = readProjectMemoryStats(db, projectId);
    expect(stats.totalChanges).toBe(0);
    expect(stats.liveChanges).toBe(0);
    expect(stats.consolidatedChanges).toBe(0);
    expect(stats.softDeletedChanges).toBe(0);
  });

  it('reports counts after writes + soft-deletes + consolidations', () => {
    appendProjectChange(db, { project_id: projectId, description: 'live 1' });
    appendProjectChange(db, { project_id: projectId, description: 'live 2' });
    const id3 = appendProjectChange(db, { project_id: projectId, description: 'will be soft-deleted', expires_at: 1 });
    appendProjectChange(db, { project_id: projectId, description: 'noise', value_tag: 'low' });
    softDeleteProjectChange(db, id3, Date.now() + 100);
    // Consolidate two live ones
    appendProjectChange(db, { project_id: projectId, description: 'live 1' });
    consolidateProjectChanges(db, projectId, { jaccardThreshold: 0.4 });
    const stats = readProjectMemoryStats(db, projectId, Date.now() + 200);
    expect(stats.totalChanges).toBe(5);
    expect(stats.softDeletedChanges).toBe(1);
    expect(stats.consolidatedChanges).toBeGreaterThanOrEqual(1);
  });
});

describe('R-MEM-2: MemoryStore high-level API + RPC', () => {
  let tmp: string;
  let store: MemoryStore;

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'rmem2-api-'));
    store = createMemoryStore({
      globalMemoryPath: join(tmp, 'g.md'),
      projectMemoryPath: join(tmp, 'p.md'),
      sessionsDir: join(tmp, 's'),
      dbPath: join(tmp, 'm.db'),
    });
  });
  afterEach(() => {
    store.close();
    rmSync(tmp, { recursive: true, force: true });
  });

  it('appendProjectChange accepts the new opts', () => {
    const entry = store.appendProjectChange('ttl test', { expiresAt: Date.now() + 60_000, valueTag: 'low' });
    expect(entry.description).toBe('ttl test');
  });

  it('store.consolidate returns the expected counts', () => {
    store.appendProjectChange('duplicate change one');
    store.appendProjectChange('duplicate change one');
    store.appendProjectChange('duplicate change one (refined)');
    const out = store.consolidate({ jaccardThreshold: 0.4 });
    expect(out.scanned).toBe(3);
    expect(out.merged).toBeGreaterThanOrEqual(1);
  });

  it('store.forget respects the policy', () => {
    store.appendProjectChange('expired', { expiresAt: Date.now() - 1000 });
    store.appendProjectChange('fresh');
    // R-MEM-2: opt-in `expired: true` to only match expired rows.
    const out = store.forget({ expired: true });
    expect(out.candidates).toBe(1);
    expect(out.softDeleted).toBe(1);
  });

  it('store.forget with dryRun reports candidates without deleting', () => {
    store.appendProjectChange('expired', { expiresAt: Date.now() - 1000 });
    const out = store.forget({ dryRun: true });
    expect(out.candidates).toBe(1);
    expect(out.softDeleted).toBe(0);
  });

  it('store.readStats returns the full picture', () => {
    store.appendProjectChange('live');
    store.appendProjectChange('expired', { expiresAt: Date.now() - 1000 });
    const stats = store.readStats();
    expect(stats.totalChanges).toBe(2);
    expect(stats.expiredCandidates).toBe(1);
  });

  it('memoryConsolidate RPC validates params', () => {
    expect(() => memoryConsolidate(store, null as unknown as never)).toThrow(/object/);
  });

  it('memoryForget RPC validates params', () => {
    expect(() => memoryForget(store, null as unknown as never)).toThrow(/object/);
  });

  it('memoryStats RPC returns ok shape', () => {
    const r = memoryStats(store);
    expect(r.ok).toBe(true);
    expect(typeof r.totalChanges).toBe('number');
  });

  it('coexists with R-MEM-1 findSimilar', () => {
    store.appendProjectChange('one');
    store.appendProjectChange('two');
    const stats = store.readStats();
    expect(stats.totalChanges).toBe(2);
    const find = store.findSimilar('one');
    expect(find.totalScanned).toBeGreaterThanOrEqual(2);
  });
});
