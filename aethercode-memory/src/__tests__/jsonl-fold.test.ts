import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { foldSessionJsonl, toSessionMessageInput } from '../jsonl-fold.js';
import { writeJsonlFile, appendJsonlLine } from '../jsonl-writer.js';
import { readSessionMessages, countSessionMessages } from '../session-store.js';
import { openAndMigrate } from '../sqlite.js';
import type { Database as DatabaseT } from 'better-sqlite3';

let tmpDir = '';
let dbPath = '';

/** Convenience: open, run the fold, close — guards against leaked DB handles. */
async function foldAndClose(
  jsonl: string,
  sessionId: string,
): ReturnType<typeof foldSessionJsonl> {
  const db: DatabaseT = openAndMigrate(dbPath);
  try {
    return await foldSessionJsonl(db, sessionId, jsonl);
  } finally {
    db.close();
  }
}

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-fold-'));
  dbPath = join(tmpDir, 'sessions.db');
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

describe('jsonl-fold — T-023 jsonl → sqlite', () => {
  it('folds a well-formed jsonl into session_messages', async () => {
    const jsonl = join(tmpDir, 's1.jsonl');
    await writeJsonlFile(jsonl, [
      { ts: 100, role: 'user', content: 'hello', tokenCount: 2 },
      { ts: 200, role: 'assistant', content: 'hi', tokenCount: 3 },
      { ts: 300, role: 'tool', content: 'result', metadata: { tool: 'grep' } },
    ]);

    const result = await foldAndClose(jsonl, 's1');
    expect(result.found).toBe(true);
    expect(result.written).toBe(3);
    expect(result.skipped).toBe(0);
    expect(result.bytesRemoved).toBeGreaterThan(0);

    const db = openAndMigrate(dbPath);
    try {
      const rows = readSessionMessages(db, 's1');
      expect(rows).toHaveLength(3);
      expect(rows[0]?.content).toBe('hello');
      expect(rows[0]?.token_count).toBe(2);
      expect(rows[2]?.role).toBe('tool');
      expect(rows[2]?.metadata).toBe('{"tool":"grep"}');
    } finally {
      db.close();
    }
  });

  it('deletes the jsonl file on success', async () => {
    const jsonl = join(tmpDir, 's2.jsonl');
    await writeJsonlFile(jsonl, [{ ts: 1, role: 'user', content: 'x' }]);
    expect(existsSync(jsonl)).toBe(true);
    await foldAndClose(jsonl, 's2');
    expect(existsSync(jsonl)).toBe(false);
  });

  it('skips invalid records but keeps the valid ones', async () => {
    const jsonl = join(tmpDir, 's3.jsonl');
    await writeJsonlFile(jsonl, [
      { ts: 1, role: 'user', content: 'good' },
    ]);
    // Append bad lines manually (writeJsonlFile won't accept them).
    await appendJsonlLine(jsonl, { ts: 2, role: 'unknown-role', content: 'bad' });
    await appendJsonlLine(jsonl, { content: 'no-ts' });
    await appendJsonlLine(jsonl, 'not-json-at-all{');
    await appendJsonlLine(jsonl, { ts: 3, role: 'user', content: 'also good' });

    const result = await foldAndClose(jsonl, 's3');
    expect(result.written).toBe(2);
    expect(result.skipped).toBeGreaterThanOrEqual(3);

    const db = openAndMigrate(dbPath);
    try {
      const rows = readSessionMessages(db, 's3');
      expect(rows.map((r) => r.content)).toEqual(['good', 'also good']);
    } finally {
      db.close();
    }
  });

  it('returns found=false when the file does not exist', async () => {
    const jsonl = join(tmpDir, 'missing.jsonl');
    const result = await foldAndClose(jsonl, 'sX');
    expect(result.found).toBe(false);
    expect(result.written).toBe(0);
    expect(result.skipped).toBe(0);
  });

  it('is idempotent: re-running on a missing file writes nothing', async () => {
    const jsonl = join(tmpDir, 's4.jsonl');
    await writeJsonlFile(jsonl, [{ ts: 1, role: 'user', content: 'one' }]);
    await foldAndClose(jsonl, 's4');
    // jsonl is gone now; second fold is a no-op.
    const r2 = await foldAndClose(jsonl, 's4');
    expect(r2.found).toBe(false);
    const dbFinal = openAndMigrate(dbPath);
    try {
      expect(countSessionMessages(dbFinal, 's4')).toBe(1);
    } finally {
      dbFinal.close();
    }
  });
});

describe('toSessionMessageInput', () => {
  it('converts a valid record', () => {
    const out = toSessionMessageInput({ ts: 1, role: 'user', content: 'x', tokenCount: 5 });
    expect(out).toEqual({
      ts: 1,
      role: 'user',
      content: 'x',
      metadata: undefined,
      tokenCount: 5,
    });
  });

  it('rejects non-object records', () => {
    expect(toSessionMessageInput(null)).toBeNull();
    expect(toSessionMessageInput(42)).toBeNull();
    expect(toSessionMessageInput('string')).toBeNull();
    expect(toSessionMessageInput([])).toBeNull();
  });

  it('rejects records with bad ts / role / content', () => {
    expect(toSessionMessageInput({ ts: 'x', role: 'user', content: 'x' })).toBeNull();
    expect(toSessionMessageInput({ ts: NaN, role: 'user', content: 'x' })).toBeNull();
    expect(toSessionMessageInput({ ts: 1, role: 'bogus', content: 'x' })).toBeNull();
    expect(toSessionMessageInput({ ts: 1, role: 'user', content: 42 })).toBeNull();
  });

  it('accepts records with token_count alias', () => {
    const out = toSessionMessageInput({ ts: 1, role: 'user', content: 'x', token_count: 7 });
    expect(out?.tokenCount).toBe(7);
  });
});
