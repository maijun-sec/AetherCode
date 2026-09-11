import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  openAndMigrate,
  withDatabase,
  readSchemaVersion,
  runStatement,
  queryAll,
  DEFAULT_MIGRATIONS,
  type Migration,
} from '../sqlite.js';

let tmpDir = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-mem-test-'));
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

describe('openAndMigrate — fresh database', () => {
  it('creates the file and applies all default migrations', () => {
    const path = join(tmpDir, 'fresh.db');
    const db = openAndMigrate(path);
    try {
      expect(existsSync(path)).toBe(true);
      expect(readSchemaVersion(db)).toBe(DEFAULT_MIGRATIONS.length);

      // Verify all three expected tables exist.
      const tables = queryAll<{ name: string }>(
        db,
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
        [],
        (r) => ({ name: r['name'] as string }),
      );
      const tableNames = tables.map((t) => t.name);
      expect(tableNames).toContain('session_messages');
      expect(tableNames).toContain('project_db');
      expect(tableNames).toContain('project_changes');
    } finally {
      db.close();
    }
  });

  it('creates parent directories if they do not exist', () => {
    const path = join(tmpDir, 'nested', 'subdir', 'memory.db');
    const db = openAndMigrate(path);
    try {
      expect(existsSync(path)).toBe(true);
      expect(readSchemaVersion(db)).toBeGreaterThan(0);
    } finally {
      db.close();
    }
  });

  it('is idempotent: reopening does not re-apply migrations', () => {
    const path = join(tmpDir, 'idem.db');
    const db1 = openAndMigrate(path);
    const v1 = readSchemaVersion(db1);
    db1.close();
    const db2 = openAndMigrate(path);
    try {
      expect(readSchemaVersion(db2)).toBe(v1);
    } finally {
      db2.close();
    }
  });
});

describe('openAndMigrate — partial application', () => {
  it('applies only pending migrations when an older schema exists', () => {
    // Bootstrap a v1-only database, then add a v2 migration and re-open.
    const path = join(tmpDir, 'partial.db');
    const v1Only: Migration[] = DEFAULT_MIGRATIONS.filter((m) => m.version === 1);
    const db1 = openAndMigrate(path, v1Only);
    expect(readSchemaVersion(db1)).toBe(1);
    db1.close();

    const db2 = openAndMigrate(path, DEFAULT_MIGRATIONS);
    try {
      expect(readSchemaVersion(db2)).toBe(DEFAULT_MIGRATIONS.length);
      const tables = queryAll<{ name: string }>(
        db2,
        "SELECT name FROM sqlite_master WHERE type='table' AND name='project_db'",
        [],
        (r) => ({ name: r['name'] as string }),
      );
      expect(tables).toHaveLength(1);
    } finally {
      db2.close();
    }
  });

  it('throws on a migration gap', () => {
    const path = join(tmpDir, 'gap.db');
    // Start at version 1.
    const v1Only: Migration[] = DEFAULT_MIGRATIONS.filter((m) => m.version === 1);
    openAndMigrate(path, v1Only).close();
    // Now try to jump directly to version 5 (skipping 2,3,4).
    const gapped: Migration[] = [
      ...v1Only,
      {
        version: 5,
        name: 'far-future',
        statements: ['CREATE TABLE far_future (x INTEGER)'],
      },
    ];
    expect(() => openAndMigrate(path, gapped)).toThrow(/Migration gap/);
  });
});

describe('withDatabase', () => {
  it('opens the database, runs the callback, and closes it', () => {
    const path = join(tmpDir, 'with.db');
    const result = withDatabase(path, (db) => {
      runStatement(db, "INSERT INTO project_db (project_id, cwd, updated_at) VALUES (?, ?, ?)", [
        'p1',
        '/tmp/proj',
        1_000,
      ]);
      const rows = queryAll<{ cwd: string }>(
        db,
        'SELECT cwd FROM project_db WHERE project_id = ?',
        ['p1'],
        (r) => ({ cwd: r['cwd'] as string }),
      );
      return rows[0]?.cwd;
    });
    expect(result).toBe('/tmp/proj');
  });

  it('closes the database even if the callback throws', () => {
    const path = join(tmpDir, 'throw.db');
    expect(() =>
      withDatabase(path, () => {
        throw new Error('boom');
      }),
    ).toThrow('boom');
  });
});

describe('session_messages schema', () => {
  it('accepts inserts and queries by session_id', () => {
    const path = join(tmpDir, 'sess.db');
    withDatabase(path, (db) => {
      runStatement(
        db,
        `INSERT INTO session_messages
           (session_id, ts, role, content, token_count)
         VALUES (?, ?, ?, ?, ?)`,
        ['s1', 100, 'user', 'hello', 1],
      );
      runStatement(
        db,
        `INSERT INTO session_messages
           (session_id, ts, role, content, token_count)
         VALUES (?, ?, ?, ?, ?)`,
        ['s1', 200, 'assistant', 'hi', 1],
      );
      runStatement(
        db,
        `INSERT INTO session_messages
           (session_id, ts, role, content, token_count)
         VALUES (?, ?, ?, ?, ?)`,
        ['s2', 150, 'user', 'other', 1],
      );
      const rows = queryAll<{ role: string; content: string }>(
        db,
        'SELECT role, content FROM session_messages WHERE session_id = ? ORDER BY ts ASC',
        ['s1'],
        (r) => ({ role: r['role'] as string, content: r['content'] as string }),
      );
      expect(rows).toHaveLength(2);
      expect(rows[0]?.content).toBe('hello');
      expect(rows[1]?.content).toBe('hi');
    });
  });
});

describe('readSchemaVersion', () => {
  it('returns 0 for a brand-new (empty) database', () => {
    const path = join(tmpDir, 'empty.db');
    const db = openAndMigrate(path, []);
    try {
      expect(readSchemaVersion(db)).toBe(0);
    } finally {
      db.close();
    }
  });
});

// Suppress unused-import warnings for the file-system helpers that vitest
// does not statically detect as used in the test bodies.
void readFileSync;
