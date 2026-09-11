import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  upsertProject,
  readProject,
  listProjects,
  deleteProject,
  appendProjectChange,
  appendProjectChanges,
  listProjectChanges,
  countProjectChanges,
  markChangesCompressed,
  deleteProjectChange,
  projectChangeToEntry,
  projectRowToFacts,
} from '../project-store.js';
import { openAndMigrate } from '../sqlite.js';

let tmpDir = '';
let dbPath = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-proj-store-'));
  dbPath = join(tmpDir, 'p.db');
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

describe('project-store — T-020 project_db write/read', () => {
  it('inserts a project row', () => {
    const db = openAndMigrate(dbPath);
    try {
      const row = upsertProject(db, {
        project_id: 'p1',
        cwd: '/work/proj',
        title: 'AetherCode',
        description: 'memory module',
        updated_at: 1_000,
      });
      expect(row.project_id).toBe('p1');
      expect(row.cwd).toBe('/work/proj');
      expect(row.title).toBe('AetherCode');
      expect(row.description).toBe('memory module');
    } finally {
      db.close();
    }
  });

  it('reads a project by id', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/x' });
      const row = readProject(db, 'p1');
      expect(row?.project_id).toBe('p1');
      expect(row?.cwd).toBe('/x');
      expect(readProject(db, 'missing')).toBeNull();
    } finally {
      db.close();
    }
  });

  it('upsert updates title, description, and updated_at on conflict', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/x', title: 'old', updated_at: 100 });
      upsertProject(db, {
        project_id: 'p1',
        cwd: '/x',
        title: 'new',
        description: 'desc',
        updated_at: 200,
      });
      const row = readProject(db, 'p1');
      expect(row?.title).toBe('new');
      expect(row?.description).toBe('desc');
      expect(row?.updated_at).toBe(200);
    } finally {
      db.close();
    }
  });

  it('listProjects returns all projects newest first', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a', updated_at: 100 });
      upsertProject(db, { project_id: 'p2', cwd: '/b', updated_at: 300 });
      upsertProject(db, { project_id: 'p3', cwd: '/c', updated_at: 200 });
      const rows = listProjects(db);
      expect(rows.map((r) => r.project_id)).toEqual(['p2', 'p3', 'p1']);
    } finally {
      db.close();
    }
  });

  it('deleteProject removes a row', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      expect(deleteProject(db, 'p1')).toBe(true);
      expect(readProject(db, 'p1')).toBeNull();
      expect(deleteProject(db, 'p1')).toBe(false);
    } finally {
      db.close();
    }
  });
});

describe('project-store — T-021 project_changes write/read', () => {
  it('appends a change and returns the row id', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const id = appendProjectChange(db, { project_id: 'p1', description: 'first', ts: 100 });
      expect(id).toBeGreaterThan(0);
      const rows = listProjectChanges(db, 'p1');
      expect(rows).toHaveLength(1);
      expect(rows[0]?.description).toBe('first');
      expect(rows[0]?.compressed).toBe(0);
    } finally {
      db.close();
    }
  });

  it('batch insert returns ids in order', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const ids = appendProjectChanges(db, [
        { project_id: 'p1', description: 'a', ts: 100 },
        { project_id: 'p1', description: 'b', ts: 200 },
        { project_id: 'p1', description: 'c', ts: 300 },
      ]);
      expect(ids).toHaveLength(3);
      expect(new Set(ids).size).toBe(3); // unique
    } finally {
      db.close();
    }
  });

  it('listProjectChanges returns changes in ascending order by default', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      appendProjectChanges(db, [
        { project_id: 'p1', description: 'b', ts: 200 },
        { project_id: 'p1', description: 'a', ts: 100 },
        { project_id: 'p1', description: 'c', ts: 300 },
      ]);
      const rows = listProjectChanges(db, 'p1');
      expect(rows.map((r) => r.description)).toEqual(['a', 'b', 'c']);
    } finally {
      db.close();
    }
  });

  it('uncompressedOnly filters to compressed=0', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const id1 = appendProjectChange(db, { project_id: 'p1', description: 'a', ts: 100, compressed: 1 });
      appendProjectChange(db, { project_id: 'p1', description: 'b', ts: 200, compressed: 0 });
      const uncompressed = listProjectChanges(db, 'p1', { uncompressedOnly: true });
      expect(uncompressed).toHaveLength(1);
      expect(uncompressed[0]?.description).toBe('b');
      void id1;
    } finally {
      db.close();
    }
  });

  it('markChangesCompressed flips the bit', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const id1 = appendProjectChange(db, { project_id: 'p1', description: 'a', ts: 100 });
      const id2 = appendProjectChange(db, { project_id: 'p1', description: 'b', ts: 200 });
      expect(markChangesCompressed(db, 'p1', [id1, id2])).toBe(2);
      const uncompressed = listProjectChanges(db, 'p1', { uncompressedOnly: true });
      expect(uncompressed).toHaveLength(0);
    } finally {
      db.close();
    }
  });

  it('countProjectChanges reports the number of rows', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      appendProjectChanges(db, [
        { project_id: 'p1', description: 'a', ts: 100 },
        { project_id: 'p1', description: 'b', ts: 200 },
      ]);
      expect(countProjectChanges(db, 'p1')).toBe(2);
      expect(countProjectChanges(db, 'p2')).toBe(0);
    } finally {
      db.close();
    }
  });

  it('deleteProjectChange removes one row', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const id = appendProjectChange(db, { project_id: 'p1', description: 'a', ts: 100 });
      expect(deleteProjectChange(db, id)).toBe(true);
      expect(countProjectChanges(db, 'p1')).toBe(0);
    } finally {
      db.close();
    }
  });

  it('projectChangeToEntry maps to the public ChangeEntry shape', () => {
    const db = openAndMigrate(dbPath);
    try {
      upsertProject(db, { project_id: 'p1', cwd: '/a' });
      const id = appendProjectChange(db, { project_id: 'p1', description: 'init', ts: 100 });
      const rows = listProjectChanges(db, 'p1');
      const entry = projectChangeToEntry(rows[0]!);
      expect(entry.kind).toBe('change');
      expect(entry.scope).toBe('project');
      expect(entry.description).toBe('init');
      expect(entry.id).toBe(`project-change-${id}`);
      expect(entry.compressed).toBe(false);
    } finally {
      db.close();
    }
  });

  it('projectRowToFacts surfaces cwd/title/description', () => {
    const row = {
      project_id: 'p1',
      cwd: '/a',
      title: 'AetherCode',
      description: 'memory module',
      updated_at: 100,
    };
    const facts = projectRowToFacts(row);
    const byKey = new Map(facts.map((f) => [f.key, f.value]));
    expect(byKey.get('cwd')).toBe('/a');
    expect(byKey.get('title')).toBe('AetherCode');
    expect(byKey.get('description')).toBe('memory module');
  });
});
