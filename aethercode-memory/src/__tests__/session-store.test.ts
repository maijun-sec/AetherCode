import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  writeSessionMessage,
  writeSessionMessages,
  readSessionMessages,
  countSessionMessages,
  deleteSession,
  deleteSessionMessage,
} from '../session-store.js';
import { openAndMigrate } from '../sqlite.js';

let tmpDir = '';
let dbPath = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-sess-store-'));
  dbPath = join(tmpDir, 's.db');
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

describe('session-store — T-018 write', () => {
  it('inserts a single message and returns the row', () => {
    const db = openAndMigrate(dbPath);
    try {
      const row = writeSessionMessage(db, 's1', {
        ts: 100,
        role: 'user',
        content: 'hello',
        tokenCount: 1,
      });
      expect(row.session_id).toBe('s1');
      expect(row.role).toBe('user');
      expect(row.content).toBe('hello');
      expect(row.token_count).toBe(1);
      expect(row.metadata).toBeNull();
    } finally {
      db.close();
    }
  });

  it('serialises metadata to JSON', () => {
    const db = openAndMigrate(dbPath);
    try {
      const row = writeSessionMessage(db, 's1', {
        ts: 200,
        role: 'tool',
        content: 'result',
        metadata: { toolName: 'grep', exitCode: 0 },
      });
      expect(row.metadata).toBe('{"toolName":"grep","exitCode":0}');
    } finally {
      db.close();
    }
  });

  it('upserts on (session_id, ts) conflict', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessage(db, 's1', { ts: 100, role: 'user', content: 'first' });
      writeSessionMessage(db, 's1', { ts: 100, role: 'user', content: 'second' });
      const rows = readSessionMessages(db, 's1');
      expect(rows).toHaveLength(1);
      expect(rows[0]?.content).toBe('second');
    } finally {
      db.close();
    }
  });

  it('rejects empty session_id', () => {
    const db = openAndMigrate(dbPath);
    try {
      expect(() =>
        writeSessionMessage(db, '', { ts: 1, role: 'user', content: 'x' }),
      ).toThrow(/sessionId/);
    } finally {
      db.close();
    }
  });

  it('rejects unknown role', () => {
    const db = openAndMigrate(dbPath);
    try {
      expect(() =>
        // @ts-expect-error -- bad role on purpose
        writeSessionMessage(db, 's1', { ts: 1, role: 'bogus', content: 'x' }),
      ).toThrow(/role/);
    } finally {
      db.close();
    }
  });

  it('writeSessionMessages batch is atomic', () => {
    const db = openAndMigrate(dbPath);
    try {
      const n = writeSessionMessages(db, 's1', [
        { ts: 1, role: 'user', content: 'a' },
        { ts: 2, role: 'assistant', content: 'b' },
        { ts: 3, role: 'user', content: 'c' },
      ]);
      expect(n).toBe(3);
      expect(countSessionMessages(db, 's1')).toBe(3);
    } finally {
      db.close();
    }
  });
});

describe('session-store — T-019 read by ts range', () => {
  it('reads messages in ascending order by ts', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 200, role: 'user', content: 'b' },
        { ts: 100, role: 'user', content: 'a' },
        { ts: 300, role: 'assistant', content: 'c' },
      ]);
      const rows = readSessionMessages(db, 's1');
      expect(rows.map((r) => r.content)).toEqual(['a', 'b', 'c']);
    } finally {
      db.close();
    }
  });

  it('filters by fromTs (inclusive)', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 100, role: 'user', content: 'a' },
        { ts: 200, role: 'user', content: 'b' },
        { ts: 300, role: 'user', content: 'c' },
      ]);
      const rows = readSessionMessages(db, 's1', { fromTs: 200 });
      expect(rows.map((r) => r.content)).toEqual(['b', 'c']);
    } finally {
      db.close();
    }
  });

  it('filters by toTs (inclusive)', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 100, role: 'user', content: 'a' },
        { ts: 200, role: 'user', content: 'b' },
        { ts: 300, role: 'user', content: 'c' },
      ]);
      const rows = readSessionMessages(db, 's1', { toTs: 200 });
      expect(rows.map((r) => r.content)).toEqual(['a', 'b']);
    } finally {
      db.close();
    }
  });

  it('descending option reverses order', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 100, role: 'user', content: 'a' },
        { ts: 200, role: 'user', content: 'b' },
        { ts: 300, role: 'user', content: 'c' },
      ]);
      const rows = readSessionMessages(db, 's1', { descending: true });
      expect(rows.map((r) => r.content)).toEqual(['c', 'b', 'a']);
    } finally {
      db.close();
    }
  });

  it('limit truncates the result set', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 1, role: 'user', content: 'a' },
        { ts: 2, role: 'user', content: 'b' },
        { ts: 3, role: 'user', content: 'c' },
        { ts: 4, role: 'user', content: 'd' },
      ]);
      const rows = readSessionMessages(db, 's1', { limit: 2 });
      expect(rows).toHaveLength(2);
      expect(rows[0]?.content).toBe('a');
    } finally {
      db.close();
    }
  });

  it('isolates messages by session_id', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [{ ts: 1, role: 'user', content: 'A' }]);
      writeSessionMessages(db, 's2', [{ ts: 1, role: 'user', content: 'B' }]);
      expect(readSessionMessages(db, 's1').map((r) => r.content)).toEqual(['A']);
      expect(readSessionMessages(db, 's2').map((r) => r.content)).toEqual(['B']);
    } finally {
      db.close();
    }
  });

  it('count returns 0 for unknown sessions', () => {
    const db = openAndMigrate(dbPath);
    try {
      expect(countSessionMessages(db, 'nope')).toBe(0);
    } finally {
      db.close();
    }
  });
});

describe('session-store — delete', () => {
  it('deleteSession removes all rows for a session', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 1, role: 'user', content: 'a' },
        { ts: 2, role: 'user', content: 'b' },
      ]);
      expect(deleteSession(db, 's1')).toBe(2);
      expect(countSessionMessages(db, 's1')).toBe(0);
    } finally {
      db.close();
    }
  });

  it('deleteSessionMessage removes a single row by (session_id, ts)', () => {
    const db = openAndMigrate(dbPath);
    try {
      writeSessionMessages(db, 's1', [
        { ts: 1, role: 'user', content: 'a' },
        { ts: 2, role: 'user', content: 'b' },
      ]);
      expect(deleteSessionMessage(db, 's1', 1)).toBe(true);
      expect(countSessionMessages(db, 's1')).toBe(1);
      expect(deleteSessionMessage(db, 's1', 999)).toBe(false);
    } finally {
      db.close();
    }
  });
});
