import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { createMemoryStore, ensureProjectFile, ensureGlobalFile } from '../memory-store.js';
import { isFact, type Fact } from '../types.js';
import type { MemoryStore } from '../memory-store.js';

let tmpDir = '';
let globalPath = '';
let projectPath = '';
let sessionsDir = '';
let dbPath = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-memstore-'));
  globalPath = join(tmpDir, 'global-memory.md');
  projectPath = join(tmpDir, 'proj', 'memory.md');
  sessionsDir = join(tmpDir, 'sessions');
  dbPath = join(tmpDir, 'store.db');
  // Pre-create the proj directory so writeFileSync on projectPath succeeds.
  mkdirSync(dirname(projectPath), { recursive: true });
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

function makeStore(): MemoryStore {
  return createMemoryStore({
    globalMemoryPath: globalPath,
    projectMemoryPath: projectPath,
    sessionsDir,
    dbPath,
  });
}

describe('MemoryStore — read paths (T-040, T-041, T-042)', () => {
  it('getGlobal returns an empty result when the file does not exist', () => {
    const store = makeStore();
    try {
      const r = store.getGlobal();
      expect(r.entries).toEqual([]);
      expect(r.totalTokens).toBe(0);
      expect(r.source).toBe('file');
    } finally {
      store.close();
    }
  });

  it('getGlobal parses a global memory markdown file (T-040)', () => {
    writeFileSync(
      globalPath,
      [
        '# Global Memory',
        '',
        '## Facts',
        '- user.name: Alice',
        '- project.build_cmd: pnpm test',
        '',
        '## Rules',
        '- No mocks in production code',
        '',
        '## Cross-project breadcrumbs',
        '- 2026-08-28 — switched from /a to /b',
        '',
      ].join('\n'),
      'utf-8',
    );
    const store = makeStore();
    try {
      const r = store.getGlobal();
      expect(r.source).toBe('file');
      const facts = r.entries.filter((e) => e.kind === 'fact');
      const rules = r.entries.filter((e) => e.kind === 'rule');
      const crumbs = r.entries.filter((e) => e.kind === 'breadcrumb');
      expect(facts).toHaveLength(2);
      expect(rules).toHaveLength(1);
      expect(crumbs).toHaveLength(1);
      expect(r.totalTokens).toBeGreaterThan(0);
    } finally {
      store.close();
    }
  });

  it('getGlobal caches the result (second call returns source=cache)', () => {
    writeFileSync(
      globalPath,
      '# Global Memory\n\n## Facts\n- user.name: Alice\n',
      'utf-8',
    );
    const store = makeStore();
    try {
      const r1 = store.getGlobal();
      expect(r1.source).toBe('file');
      const r2 = store.getGlobal();
      expect(r2.source).toBe('cache');
    } finally {
      store.close();
    }
  });

  it('getProject reads from the project file and sqlite (T-041)', () => {
    writeFileSync(
      projectPath,
      [
        '# AetherCode',
        '',
        '## 说明',
        'memory module',
        '',
        '## 修改记录 (最近 20 次)',
        '',
        '## Project facts',
        '- build_cmd: pnpm test',
        '',
      ].join('\n'),
      'utf-8',
    );
    const store = makeStore();
    try {
      const r = store.getProject();
      expect(r.source).toBe('file');
      const desc = r.entries.find((e): e is Fact => isFact(e) && e.key === 'description');
      expect(desc?.value).toBe('memory module');
    } finally {
      store.close();
    }
  });

  it('getSession returns 0 entries for an unknown session (T-042)', () => {
    const store = makeStore();
    try {
      const r = store.getSession('s-nope');
      expect(r.entries).toEqual([]);
      expect(r.source).toBe('sqlite');
    } finally {
      store.close();
    }
  });

  it('getSession returns rows for a session that has messages (T-042)', () => {
    const store = makeStore();
    try {
      store.appendSessionMessage('s1', { ts: 1, role: 'user', content: 'hi', tokenCount: 1 });
      store.appendSessionMessage('s1', { ts: 2, role: 'assistant', content: 'hello', tokenCount: 2 });
      const r = store.getSession('s1');
      expect(r.source).toBe('sqlite');
      expect(r.entries).toHaveLength(2);
      expect(r.totalTokens).toBe(3);
    } finally {
      store.close();
    }
  });

  it('getSession caches the result', () => {
    const store = makeStore();
    try {
      store.appendSessionMessage('s1', { ts: 1, role: 'user', content: 'hi' });
      const r1 = store.getSession('s1');
      expect(r1.source).toBe('sqlite');
      const r2 = store.getSession('s1');
      expect(r2.source).toBe('cache');
    } finally {
      store.close();
    }
  });
});

describe('MemoryStore — write paths (T-043, T-044, T-045)', () => {
  it('appendProjectChange writes to sqlite and returns a ChangeEntry (T-043)', () => {
    const store = makeStore();
    try {
      const entry = store.appendProjectChange('initial scaffold');
      expect(entry.kind).toBe('change');
      expect(entry.scope).toBe('project');
      expect(entry.description).toBe('initial scaffold');
      expect(entry.compressed).toBe(false);

      // Re-read to confirm persistence.
      const r = store.getProject();
      const changes = r.entries.filter((e) => e.kind === 'change');
      expect(changes).toHaveLength(1);
    } finally {
      store.close();
    }
  });

  it('appendProjectChange creates the project_db row on first call', () => {
    const store = makeStore();
    try {
      store.appendProjectChange('x');
      // Force a re-read so we exercise the project_db path.
      const r = store.getProject();
      const cwdFact = r.entries.find((e): e is Fact => isFact(e) && e.key === 'cwd');
      expect(cwdFact?.value).toBe(dirname(projectPath));
    } finally {
      store.close();
    }
  });

  it('appendProjectChange invalidates the project cache so the next read sees the new change', () => {
    const store = makeStore();
    try {
      // Seed the project file with a known description so getProject has something to read.
      writeFileSync(
        projectPath,
        '# P\n\n## 说明\ninitial\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
        'utf-8',
      );
      const r0 = store.getProject();
      expect(r0.source).toBe('file');
      store.appendProjectChange('after-scaffold');
      const r1 = store.getProject();
      // Cache was invalidated → next read comes from disk+sqlite.
      expect(r1.source).toBe('file');
      const desc = r1.entries.find((e) => e.kind === 'change');
      expect(desc?.description).toBe('after-scaffold');
    } finally {
      store.close();
    }
  });

  it('appendSessionMessage writes to session_messages and invalidates cache (T-044)', () => {
    const store = makeStore();
    try {
      store.appendSessionMessage('s1', { ts: 1, role: 'user', content: 'a' });
      store.appendSessionMessage('s1', { ts: 2, role: 'assistant', content: 'b' });
      const r = store.getSession('s1');
      expect(r.entries).toHaveLength(2);
    } finally {
      store.close();
    }
  });

  it('appendSessionMessage invalidates the session cache (next read goes to sqlite)', () => {
    const store = makeStore();
    try {
      store.appendSessionMessage('s1', { ts: 1, role: 'user', content: 'a' });
      // Prime the cache.
      const r0 = store.getSession('s1');
      expect(r0.source).toBe('sqlite');
      const r0Cached = store.getSession('s1');
      expect(r0Cached.source).toBe('cache');
      // Append a new message → cache invalidates.
      store.appendSessionMessage('s1', { ts: 2, role: 'user', content: 'b' });
      const r1 = store.getSession('s1');
      expect(r1.source).toBe('sqlite');
      expect(r1.entries).toHaveLength(2);
    } finally {
      store.close();
    }
  });

  it('appendSessionFact stores a fact-shaped record (T-045)', () => {
    const store = makeStore();
    try {
      store.appendSessionFact('s1', {
        kind: 'fact',
        id: 'f1',
        ts: 100,
        scope: 'session',
        source: 'user',
        tags: [],
        key: 'cwd',
        value: '/work/proj',
      });
      const r = store.getSession('s1');
      expect(r.entries).toHaveLength(1);
      expect(r.entries[0]?.kind).toBe('fact');
      expect((r.entries[0] as { key: string }).key).toBe('msg.100');
    } finally {
      store.close();
    }
  });

  it('appendSessionFact tracks a token count in sqlite', () => {
    const store = makeStore();
    try {
      store.appendSessionFact('s1', {
        kind: 'fact',
        id: 'f1',
        ts: 1,
        scope: 'session',
        source: 'user',
        tags: [],
        key: 'k',
        value: 'value',
      });
      const r = store.getSession('s1');
      expect(r.totalTokens).toBeGreaterThan(0);
    } finally {
      store.close();
    }
  });
});

describe('MemoryStore — session handle (jsonl streaming)', () => {
  it('openSession returns a writer that fsyncs each append by default', async () => {
    const store = makeStore();
    try {
      const handle = await store.openSession('s1');
      await handle.append({ ts: 1, role: 'user', content: 'hi' });
      await handle.append({ ts: 2, role: 'assistant', content: 'hello' });
      await handle.close();

      const { existsSync, readFileSync } = await import('node:fs');
      const jsonl = join(sessionsDir, 's1.jsonl');
      expect(existsSync(jsonl)).toBe(true);
      const lines = readFileSync(jsonl, 'utf-8').split('\n').filter((l) => l.length > 0);
      expect(lines).toHaveLength(2);
    } finally {
      store.close();
    }
  });

  it('openSession returns the same handle on a second call (re-uses the writer)', async () => {
    const store = makeStore();
    try {
      const h1 = await store.openSession('s1');
      const h2 = await store.openSession('s1');
      expect(h1).toBe(h2);
      await h1.close();
    } finally {
      store.close();
    }
  });

  it('openSession + fold round-trip lands records in sqlite', async () => {
    const store = makeStore();
    try {
      const h = await store.openSession('s1');
      await h.append({ ts: 1, role: 'user', content: 'hi', tokenCount: 1 });
      await h.append({ ts: 2, role: 'assistant', content: 'hello', tokenCount: 2 });
      await h.close();
      // Now manually fold the jsonl into sqlite via the same store.
      const { foldSessionJsonl } = await import('../jsonl-fold.js');
      const { openAndMigrate } = await import('../sqlite.js');
      const db = openAndMigrate(dbPath);
      try {
        const r = await foldSessionJsonl(db, 's1', join(sessionsDir, 's1.jsonl'));
        expect(r.written).toBe(2);
      } finally {
        db.close();
      }
      const r = store.getSession('s1');
      expect(r.entries).toHaveLength(2);
    } finally {
      store.close();
    }
  });
});

describe('MemoryStore — cache invalidation on write', () => {
  it('write to project invalidates the project cache only', () => {
    const store = makeStore();
    try {
      // Seed both caches.
      writeFileSync(globalPath, '# Global Memory\n', 'utf-8');
      writeFileSync(projectPath, '# P\n\n## 说明\ninit\n', 'utf-8');
      store.getGlobal(); // prime
      store.getProject(); // prime
      expect(store.cache.size).toBe(2);

      store.appendProjectChange('change');
      // Project cache was invalidated, but global is still warm.
      const r1 = store.getProject();
      expect(r1.source).toBe('file');
      const r2 = store.getGlobal();
      expect(r2.source).toBe('cache');
    } finally {
      store.close();
    }
  });
});

describe('MemoryStore — ensure* helpers', () => {
  it('ensureGlobalFile writes a canonical empty file when missing', () => {
    ensureGlobalFile(globalPath);
    expect(existsSync(globalPath)).toBe(true);
    const text = require('node:fs').readFileSync(globalPath, 'utf-8');
    expect(text).toContain('# Global Memory');
    expect(text).toContain('## Facts');
  });

  it('ensureProjectFile writes a canonical empty file when missing', () => {
    ensureProjectFile(projectPath, 'MyProject', 'desc');
    expect(existsSync(projectPath)).toBe(true);
    const text = require('node:fs').readFileSync(projectPath, 'utf-8');
    expect(text).toContain('# MyProject');
    expect(text).toContain('## 说明');
    expect(text).toContain('desc');
  });
});
