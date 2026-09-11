/**
 * Memory RPC tests (T-070 ~ T-074, T-075).
 *
 * Coverage:
 *  - T-070  memory/get — global / project / session
 *  - T-071  memory/appendProjectChange
 *  - T-072  memory/appendSessionFact
 *  - T-073  memory/compact (no-LLM = skipped; with fake LLM = end-to-end)
 *  - T-074  memory/switchProject
 *  - T-075  memory/list
 *  - bad-input → MemoryRpcError
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { createMemoryStore, ensureProjectFile, type MemoryStore } from '../memory-store.js';
import {
  MemoryRpcError,
  memoryAppendProjectChange,
  memoryAppendSessionFact,
  memoryCompact,
  memoryGet,
  memoryList,
  memorySwitchProject,
  type CompressionLlmClient,
} from '../rpc.js';
import { defaultProjectMemoryPath } from '../project-switcher.js';
import type { Fact } from '../types.js';
import { isFact } from '../types.js';

let tmpDir = '';
let globalPath = '';
let projectPath = '';
let sessionsDir = '';
let dbPath = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-rpc-'));
  globalPath = join(tmpDir, 'global-memory.md');
  projectPath = join(tmpDir, 'proj', '.aethercode', 'memory.md');
  sessionsDir = join(tmpDir, 'sessions');
  dbPath = join(tmpDir, 'store.db');
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
    cwd: join(tmpDir, 'proj'),
  });
}

function fakeLlm(respond: string = 'merged'): CompressionLlmClient & { callCount: () => number } {
  let calls = 0;
  return {
    complete: async (_prompt: string): Promise<string> => {
      calls += 1;
      return respond;
    },
    callCount: (): number => calls,
  };
}

/* ----------------------------- T-070 ----------------------------------- */

describe('T-070 — memory/get', () => {
  it('returns global entries', () => {
    writeFileSync(
      globalPath,
      '# Global Memory\n\n## Facts\n- user.name: Alice\n\n## Rules\n\n## Cross-project breadcrumbs\n',
      'utf-8',
    );
    const store = makeStore();
    try {
      const r = memoryGet(store, { scope: 'global' });
      const fact = r.entries.find((e): e is Fact => isFact(e) && e.key === 'user.name');
      expect(fact?.value).toBe('Alice');
    } finally {
      store.close();
    }
  });

  it('returns project entries', () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\ndesc\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = makeStore();
    try {
      const r = memoryGet(store, { scope: 'project' });
      expect(r.entries.some((e) => e.kind === 'change' || e.kind === 'fact')).toBe(true);
    } finally {
      store.close();
    }
  });

  it('returns session entries', () => {
    const store = makeStore();
    try {
      store.appendSessionMessage('s1', { ts: 1, role: 'user', content: 'hi' });
      const r = memoryGet(store, { scope: 'session', sessionId: 's1' });
      expect(r.entries).toHaveLength(1);
    } finally {
      store.close();
    }
  });

  it('rejects an unknown scope with MemoryRpcError', () => {
    const store = makeStore();
    try {
      expect(() => memoryGet(store, { scope: 'bogus' as 'global' })).toThrow(MemoryRpcError);
    } finally {
      store.close();
    }
  });

  it('requires sessionId when scope=session', () => {
    const store = makeStore();
    try {
      expect(() => memoryGet(store, { scope: 'session' })).toThrow(MemoryRpcError);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-071 ----------------------------------- */

describe('T-071 — memory/appendProjectChange', () => {
  it('appends a change and returns ok + id', () => {
    const store = makeStore();
    try {
      const r = memoryAppendProjectChange(store, { description: 'init' });
      expect(r.ok).toBe(true);
      expect(r.id).toMatch(/^project-change-/);
      const re = store.getProject();
      expect(re.entries.some((e) => e.kind === 'change')).toBe(true);
    } finally {
      store.close();
    }
  });

  it('rejects an empty description', () => {
    const store = makeStore();
    try {
      expect(() => memoryAppendProjectChange(store, { description: '' })).toThrow(MemoryRpcError);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-072 ----------------------------------- */

describe('T-072 — memory/appendSessionFact', () => {
  it('appends a fact and returns ok + id', () => {
    const store = makeStore();
    try {
      const r = memoryAppendSessionFact(store, {
        sessionId: 's1',
        key: 'cwd',
        value: '/work/proj',
      });
      expect(r.ok).toBe(true);
      expect(r.id).toMatch(/^rpc-fact-/);
      const re = store.getSession('s1');
      expect(re.entries).toHaveLength(1);
    } finally {
      store.close();
    }
  });

  it('rejects an empty key', () => {
    const store = makeStore();
    try {
      expect(() =>
        memoryAppendSessionFact(store, { sessionId: 's1', key: '', value: 'v' }),
      ).toThrow(MemoryRpcError);
    } finally {
      store.close();
    }
  });

  it('rejects an empty sessionId', () => {
    const store = makeStore();
    try {
      expect(() =>
        memoryAppendSessionFact(store, { sessionId: '', key: 'k', value: 'v' }),
      ).toThrow(MemoryRpcError);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-073 ----------------------------------- */

describe('T-073 — memory/compact', () => {
  it('returns skipped when no LLM client is provided', async () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = makeStore();
    try {
      const r = await memoryCompact(store, { force: true });
      expect(r.ok).toBe(true);
      expect(r.skipped).toBe(true);
      expect(r.changesCompressed).toBe(0);
    } finally {
      store.close();
    }
  });

  it('runs end-to-end with a fake LLM and folds the changes', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = makeStore();
    try {
      for (let i = 0; i < 20; i += 1) {
        store.appendProjectChange(`c${i + 1}`);
      }
      const llm = fakeLlm('merged description');
      const r = await memoryCompact(store, { force: true }, { llm });
      expect(r.ok).toBe(true);
      expect(r.skipped).toBe(false);
      expect(r.changesCompressed).toBe(20);
      const text = readFileSync(projectPath, 'utf-8');
      expect(text).toContain('merged description');
      expect(llm.callCount()).toBe(1);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-074 ----------------------------------- */

describe('T-074 — memory/switchProject', () => {
  it('switches to a new cwd and updates the store', async () => {
    const newCwd = join(tmpDir, 'other');
    mkdirSync(newCwd, { recursive: true });
    const store = makeStore();
    try {
      const r = await memorySwitchProject(store, { cwd: newCwd });
      expect(r.ok).toBe(true);
      expect(r.projectId).toMatch(/^[0-9a-f]{8}$/);
      expect(store.currentCwd).toBe(newCwd);
      // New project file exists.
      expect(existsSync(defaultProjectMemoryPath(newCwd))).toBe(true);
      // A breadcrumb was appended to global memory.
      const text = readFileSync(globalPath, 'utf-8');
      expect(text).toContain(newCwd);
    } finally {
      store.close();
    }
  });

  it('rejects an empty cwd', async () => {
    const store = makeStore();
    try {
      await expect(memorySwitchProject(store, { cwd: '' })).rejects.toBeInstanceOf(MemoryRpcError);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-075 ----------------------------------- */

describe('T-075 — memory/list', () => {
  it('returns just the entries and totalTokens', () => {
    writeFileSync(
      globalPath,
      '# Global Memory\n\n## Facts\n- user.name: Alice\n',
      'utf-8',
    );
    const store = makeStore();
    try {
      const r = memoryList(store, { scope: 'global' });
      expect(r.entries.some((e) => e.kind === 'fact' && e.key === 'user.name')).toBe(true);
      expect(r.totalTokens).toBeGreaterThan(0);
    } finally {
      store.close();
    }
  });
});
