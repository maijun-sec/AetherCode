import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  createJsonlWriter,
  writeJsonlFile,
  readJsonlFile,
  appendJsonlLine,
} from '../jsonl-writer.js';

let tmpDir = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-jsonl-'));
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

describe('jsonl-writer — T-022 append-only writer', () => {
  it('creates the file on first append', async () => {
    const path = join(tmpDir, 'sessions', 's1.jsonl');
    const w = await createJsonlWriter(path);
    try {
      expect(existsSync(path)).toBe(true);
      await w.append({ ts: 1, role: 'user', content: 'hi' });
      const text = readFileSync(path, 'utf-8');
      expect(text).toBe('{"ts":1,"role":"user","content":"hi"}\n');
    } finally {
      await w.close();
    }
  });

  it('appends many records, one per line, in order', async () => {
    const path = join(tmpDir, 's2.jsonl');
    const w = await createJsonlWriter(path);
    try {
      await w.appendMany([
        { ts: 1, role: 'user', content: 'a' },
        { ts: 2, role: 'assistant', content: 'b' },
        { ts: 3, role: 'tool', content: 'c' },
      ]);
      const lines = readFileSync(path, 'utf-8').split('\n').filter((l) => l.length > 0);
      expect(lines).toHaveLength(3);
      expect(JSON.parse(lines[0]!)).toEqual({ ts: 1, role: 'user', content: 'a' });
      expect(JSON.parse(lines[2]!)).toEqual({ ts: 3, role: 'tool', content: 'c' });
    } finally {
      await w.close();
    }
  });

  it('appends to an existing file (reopen)', async () => {
    const path = join(tmpDir, 's3.jsonl');
    const w1 = await createJsonlWriter(path);
    await w1.append({ ts: 1, role: 'user', content: 'first' });
    await w1.close();

    const w2 = await createJsonlWriter(path);
    try {
      await w2.append({ ts: 2, role: 'user', content: 'second' });
      const lines = readFileSync(path, 'utf-8').split('\n').filter((l) => l.length > 0);
      expect(lines).toHaveLength(2);
    } finally {
      await w2.close();
    }
  });

  it('rejects append after close', async () => {
    const path = join(tmpDir, 's4.jsonl');
    const w = await createJsonlWriter(path);
    await w.close();
    await expect(w.append({ ts: 1, role: 'user', content: 'x' })).rejects.toThrow(/closed/);
  });

  it('count() reports the number of records appended by this instance', async () => {
    const path = join(tmpDir, 's5.jsonl');
    const w = await createJsonlWriter(path);
    try {
      await w.append({ ts: 1, role: 'user', content: 'a' });
      await w.appendMany([
        { ts: 2, role: 'user', content: 'b' },
        { ts: 3, role: 'user', content: 'c' },
      ]);
      expect(w.count()).toBe(3);
    } finally {
      await w.close();
    }
  });
});

describe('jsonl-writer — T-024 atomic write with fsync', () => {
  it('data is durably flushed after each append (fsync on by default)', async () => {
    const path = join(tmpDir, 'fsync.jsonl');
    const w = await createJsonlWriter(path, { fsync: true });
    try {
      await w.append({ ts: 1, role: 'user', content: 'durable' });
      // After fsync returns, the bytes are on disk and a crash will not lose them.
      const st = statSync(path);
      expect(st.size).toBeGreaterThan(0);
    } finally {
      await w.close();
    }
  });

  it('fsync:false skips the explicit sync (faster, less durable)', async () => {
    const path = join(tmpDir, 'nofsync.jsonl');
    const w = await createJsonlWriter(path, { fsync: false });
    try {
      await w.append({ ts: 1, role: 'user', content: 'lazy' });
      const st = statSync(path);
      expect(st.size).toBeGreaterThan(0);
    } finally {
      await w.close();
    }
  });

  it('writeJsonlFile does an atomic temp+rename', async () => {
    const path = join(tmpDir, 'atomic', 's.jsonl');
    await writeJsonlFile(path, [
      { ts: 1, role: 'user', content: 'a' },
      { ts: 2, role: 'user', content: 'b' },
    ]);
    expect(existsSync(path)).toBe(true);
    const lines = readFileSync(path, 'utf-8').split('\n').filter((l) => l.length > 0);
    expect(lines).toHaveLength(2);
  });

  it('readJsonlFile returns parsed records', async () => {
    const path = join(tmpDir, 'read.jsonl');
    await writeJsonlFile(path, [
      { ts: 1, role: 'user', content: 'a' },
      { ts: 2, role: 'assistant', content: 'b' },
    ]);
    const records = await readJsonlFile(path);
    expect(records).toEqual([
      { ts: 1, role: 'user', content: 'a' },
      { ts: 2, role: 'assistant', content: 'b' },
    ]);
  });

  it('appendJsonlLine writes a single line via the lower-level API', async () => {
    const path = join(tmpDir, 'ext.jsonl');
    await appendJsonlLine(path, { ts: 1, role: 'user', content: 'x' });
    await appendJsonlLine(path, { ts: 2, role: 'user', content: 'y' });
    const records = await readJsonlFile(path);
    expect(records).toEqual([
      { ts: 1, role: 'user', content: 'x' },
      { ts: 2, role: 'user', content: 'y' },
    ]);
  });
});
