/**
 * Compression tests (T-058).
 *
 * Coverage:
 *  - T-050  trigger fires when uncompressed count >= N
 *  - T-051  4-shot prompt contains description + changes
 *  - T-052  requestCompression runs end-to-end and writes back the file
 *  - T-053  WAL records pending / llm_done / committed / failed transitions
 *  - T-054  resume from a leftover WAL row skips the LLM call
 *  - T-055  modify-记录 list is trimmed to N in the file
 *  - T-056  compressed=1 is set on the folded rows
 *  - T-058  compression is idempotent (running twice does not double-fold)
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import {
  DEFAULT_CHANGE_LIMIT,
  DEFAULT_COMPRESSION_PROMPT,
  adaptMemoryStore,
  buildCompressionPrompt,
  estimateDescriptionTokens,
  findResumableWal,
  listWalRows,
  markWalCommitted,
  markWalFailed,
  markWalLlmDone,
  renderChangesForPrompt,
  requestCompression,
  runCompressionPass,
  shouldTriggerCompression,
  startWalEntry,
  type CompressionFileFs,
  type CompressionLlmClient,
} from '../compression.js';
import { openAndMigrate, queryAll } from '../sqlite.js';
import { listProjectChanges, markChangesCompressed, appendProjectChange as appendChange } from '../project-store.js';
import { createMemoryStore, ensureProjectFile } from '../memory-store.js';
import type { ChangeEntry } from '../types.js';

let tmpDir = '';
let globalPath = '';
let projectPath = '';
let sessionsDir = '';
let dbPath = '';
let projectDir = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-compression-'));
  globalPath = join(tmpDir, 'global-memory.md');
  projectDir = join(tmpDir, 'proj', '.aethercode');
  projectPath = join(projectDir, 'memory.md');
  sessionsDir = join(tmpDir, 'sessions');
  dbPath = join(tmpDir, 'store.db');
  mkdirSync(dirname(projectPath), { recursive: true });
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

/* ----------------------------- helpers --------------------------------- */

function makeFs(): CompressionFileFs {
  return {
    readFileSync: (p: string, enc: 'utf-8'): string => readFileSync(p, enc),
    writeFileSync: (p: string, d: string, enc: 'utf-8'): void => {
      writeFileSync(p, d, enc);
    },
    existsSync: (p: string): boolean => existsSync(p),
    mkdirSync: (p: string, opts: { recursive: boolean }): void => {
      mkdirSync(p, opts);
    },
  };
}

/** A simple LLM that returns the description as a single merged line. */
function makeFakeLlm(respond: (prompt: string) => string = (p) => `merged: ${p.length}`): CompressionLlmClient & { callCount: () => number } {
  let calls = 0;
  return {
    complete: async (prompt: string): Promise<string> => {
      calls += 1;
      return respond(prompt);
    },
    callCount: (): number => calls,
  };
}

/* ----------------------------- T-050 ----------------------------------- */

describe('T-050 — compression trigger', () => {
  it('does not trigger below the threshold', () => {
    expect(shouldTriggerCompression(0)).toBe(false);
    expect(shouldTriggerCompression(1)).toBe(false);
    expect(shouldTriggerCompression(19, { changeLimit: 20 })).toBe(false);
  });
  it('triggers at the threshold', () => {
    expect(shouldTriggerCompression(20, { changeLimit: 20 })).toBe(true);
    expect(shouldTriggerCompression(21, { changeLimit: 20 })).toBe(true);
  });
  it('honors a custom threshold', () => {
    expect(shouldTriggerCompression(5, { changeLimit: 5 })).toBe(true);
    expect(shouldTriggerCompression(4, { changeLimit: 5 })).toBe(false);
  });
  it('honors headroom (T-050, fire slightly earlier)', () => {
    expect(shouldTriggerCompression(15, { changeLimit: 20, triggerHeadroom: 5 })).toBe(true);
    expect(shouldTriggerCompression(14, { changeLimit: 20, triggerHeadroom: 5 })).toBe(false);
  });
});

/* ----------------------------- T-051 ----------------------------------- */

describe('T-051 — 4-shot prompt', () => {
  it('contains four worked examples by default', () => {
    expect(DEFAULT_COMPRESSION_PROMPT).toContain('示例 1');
    expect(DEFAULT_COMPRESSION_PROMPT).toContain('示例 2');
    expect(DEFAULT_COMPRESSION_PROMPT).toContain('示例 3');
    expect(DEFAULT_COMPRESSION_PROMPT).toContain('示例 4');
  });
  it('inlines the current description', () => {
    const prompt = buildCompressionPrompt('AetherCode is a CLI', [
      { kind: 'change', id: 'c1', ts: 1700000000000, scope: 'project', source: 'system', tags: [], description: 'fix bug', compressed: false },
    ]);
    expect(prompt).toContain('AetherCode is a CLI');
    expect(prompt).not.toContain('{description}');
  });
  it('renders changes as a bullet list', () => {
    const changes: ChangeEntry[] = [
      { kind: 'change', id: 'c1', ts: 1700000001000, scope: 'project', source: 'system', tags: [], description: 'add foo', compressed: false },
      { kind: 'change', id: 'c2', ts: 1700000002000, scope: 'project', source: 'system', tags: [], description: 'add bar', compressed: false },
    ];
    const rendered = renderChangesForPrompt(changes);
    expect(rendered.split('\n')).toHaveLength(2);
    expect(rendered).toMatch(/add foo/);
    expect(rendered).toMatch(/add bar/);
  });
  it('handles an empty change list gracefully', () => {
    const rendered = renderChangesForPrompt([]);
    expect(rendered).toBe('(none)');
  });
  it('respects a custom template', () => {
    const tpl = 'desc={description} ch={changes} max={max_tokens}';
    const out = buildCompressionPrompt('d', [], { promptTemplate: tpl, maxDescriptionTokens: 1234 });
    expect(out).toBe('desc=d ch=(none) max=1234');
  });
  it('estimates description tokens', () => {
    expect(estimateDescriptionTokens('')).toBe(1);
    expect(estimateDescriptionTokens('abcd')).toBe(1);
    expect(estimateDescriptionTokens('abcde')).toBe(2);
  });
});

/* ----------------------------- T-052 / T-053 ---------------------------- */

describe('T-052 / T-053 — requestCompression + WAL', () => {
  it('runs end-to-end, marks rows compressed=1, and trims the file', async () => {
    // Seed the project memory file with a description so we have something to merge.
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nold description\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      // Append 20 changes to cross the trigger threshold.
      for (let i = 0; i < 20; i += 1) {
        store.appendProjectChange(`change #${i + 1}`);
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm(() => 'merged new description');
      const result = await requestCompression({
        store: adapter,
        projectId: hashPath(store.projectMemoryPath),
        llm,
      });
      expect(result).not.toBeNull();
      expect(result?.skipped).toBe(false);
      expect(result?.changesCompressed).toBe(20);
      // File is rewritten with the new description.
      const text = readFileSync(projectPath, 'utf-8');
      expect(text).toContain('merged new description');
      // Compressed=1 is set in sqlite.
      const db = openAndMigrate(dbPath);
      try {
        const rows = queryAll<{ n: number }>(
          db,
          'SELECT COUNT(*) AS n FROM project_changes WHERE compressed = 1',
          [],
          (r) => ({ n: Number(r['n'] ?? 0) }),
        );
        expect(rows[0]?.n ?? 0).toBe(20);
      } finally {
        db.close();
      }
      // WAL row is committed.
      const db2 = openAndMigrate(dbPath);
      try {
        const wal = listWalRows(db2, hashPath(store.projectMemoryPath));
        expect(wal.length).toBeGreaterThan(0);
        const last = wal[0]!;
        expect(last.state).toBe('committed');
        expect(last.finished_at).not.toBeNull();
      } finally {
        db2.close();
      }
    } finally {
      store.close();
    }
  });

  it('skips when no uncompressed changes are pending', async () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm();
      const result = await requestCompression({
        store: adapter,
        projectId: hashPath(store.projectMemoryPath),
        llm,
      });
      expect(result).toBeNull();
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-053 — WAL store unit tests ------------ */

describe('T-053 — compression_wal store helpers', () => {
  it('startWalEntry inserts a pending row', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      // FK requires a project_db row.
      store.appendProjectChange('seed');
      const db = openAndMigrate(dbPath);
      try {
        const id = startWalEntry(db, projectId, [], 1000);
        const rows = listWalRows(db, projectId);
        expect(rows[0]?.id).toBe(id);
        expect(rows[0]?.state).toBe('pending');
        expect(rows[0]?.started_at).toBe(1000);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });

  it('markWalLlmDone / markWalCommitted transition states', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      store.appendProjectChange('seed');
      const db = openAndMigrate(dbPath);
      try {
        const id = startWalEntry(db, projectId, [], 1000);
        markWalLlmDone(db, id, 'new desc', 42);
        expect(listWalRows(db, projectId)[0]?.state).toBe('llm_done');
        markWalCommitted(db, id, 2000);
        const row = listWalRows(db, projectId)[0]!;
        expect(row.state).toBe('committed');
        expect(row.finished_at).toBe(2000);
        expect(row.new_description).toBe('new desc');
        expect(row.new_description_tokens).toBe(42);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });

  it('markWalFailed records the error', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      store.appendProjectChange('seed');
      const db = openAndMigrate(dbPath);
      try {
        const id = startWalEntry(db, projectId, [], 1000);
        markWalFailed(db, id, 'llm timeout', 5000);
        const row = listWalRows(db, projectId)[0]!;
        expect(row.state).toBe('failed');
        expect(row.error).toBe('llm timeout');
        expect(row.finished_at).toBe(5000);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-054 — resume after crash --------------- */

describe('T-054 — resume after crash', () => {
  it('resumes from a pending WAL row by re-issuing the LLM call', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      // Append 20 changes and pre-seed a pending WAL row.
      for (let i = 0; i < 20; i += 1) {
        store.appendProjectChange(`change #${i + 1}`);
      }
      const db = openAndMigrate(dbPath);
      try {
        const allChanges = listProjectChanges(db, projectId);
        startWalEntry(
          db,
          projectId,
          allChanges,
          1234,
        );
      } finally {
        db.close();
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm(() => 'resumed description');
      const result = await runCompressionPass({
        store: adapter,
        projectId,
        llm,
        fs: makeFs(),
      });
      expect(result.resumed).toBe(true);
      expect(result.changesCompressed).toBe(20);
      const text = readFileSync(projectPath, 'utf-8');
      expect(text).toContain('resumed description');
      // Only one LLM call (the resume; the previous pending was abandoned).
      expect(llm.callCount()).toBe(1);
    } finally {
      store.close();
    }
  });

  it('resumes from an llm_done WAL row without re-calling the LLM', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      for (let i = 0; i < 5; i += 1) {
        store.appendProjectChange(`change #${i + 1}`);
      }
      const db = openAndMigrate(dbPath);
      try {
        const allChanges = listProjectChanges(db, projectId);
        const walId = startWalEntry(
          db,
          projectId,
          allChanges,
          1000,
        );
        markWalLlmDone(db, walId, 'preserved llm response', 5);
      } finally {
        db.close();
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm(() => 'should-not-be-called');
      const result = await runCompressionPass({
        store: adapter,
        projectId,
        llm,
        fs: makeFs(),
      });
      expect(result.resumed).toBe(true);
      expect(result.changesCompressed).toBe(5);
      expect(llm.callCount()).toBe(0);
      const text = readFileSync(projectPath, 'utf-8');
      expect(text).toContain('preserved llm response');
    } finally {
      store.close();
    }
  });

  it('findResumableWal returns null when only committed rows exist', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      store.appendProjectChange('seed');
      const db = openAndMigrate(dbPath);
      try {
        expect(findResumableWal(db, projectId)).toBeNull();
        const id = startWalEntry(db, projectId, [], 1000);
        markWalCommitted(db, id, 2000);
        expect(findResumableWal(db, projectId)).toBeNull();
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-055 / T-056 --------------------------- */

describe('T-055 / T-056 — trim + mark compressed', () => {
  it('file contains only the most recent N changes after compression', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      // 25 changes — only the last N=20 should remain visible in the file.
      for (let i = 0; i < 25; i += 1) {
        store.appendProjectChange(`c${i + 1}`);
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm(() => 'trimmed');
      await requestCompression({
        store: adapter,
        projectId,
        llm,
      });
      const text = readFileSync(projectPath, 'utf-8');
      const bullets = text.split('\n').filter((l) => /^-\s/.test(l));
      // 20 change bullets (description bullet is part of the 说明 block, not a "- " bullet).
      expect(bullets).toHaveLength(DEFAULT_CHANGE_LIMIT);
      // The first 5 (oldest) changes are not in the file. Use a word boundary
      // since 'c1' is a substring of 'c10'..'c19'.
      expect(text).not.toMatch(/\bc1\b/);
      expect(text).not.toMatch(/\bc5\b/);
      // The most recent ones are in the file.
      expect(text).toMatch(/\bc25\b/);
    } finally {
      store.close();
    }
  });

  it('folded rows are marked compressed=1 in sqlite', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      for (let i = 0; i < 20; i += 1) {
        store.appendProjectChange(`c${i + 1}`);
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      await requestCompression({
        store: adapter,
        projectId,
        llm: makeFakeLlm(() => 'x'),
      });
      const db = openAndMigrate(dbPath);
      try {
        const remaining = listProjectChanges(db, projectId, { uncompressedOnly: true });
        expect(remaining).toHaveLength(0);
        const all = listProjectChanges(db, projectId);
        expect(all.every((c) => c.compressed === 1)).toBe(true);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- T-058 — idempotency --------------------- */

describe('T-058 — compression idempotency', () => {
  it('running compression twice does not fold the same changes twice', async () => {
    writeFileSync(
      projectPath,
      '# P\n\n## 说明\nstart\n\n## 修改记录 (最近 20 次)\n\n## Project facts\n',
      'utf-8',
    );
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      for (let i = 0; i < 20; i += 1) {
        store.appendProjectChange(`c${i + 1}`);
      }
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      const llm = makeFakeLlm(() => 'merged');
      const r1 = await requestCompression({ store: adapter, projectId, llm });
      expect(r1?.changesCompressed).toBe(20);
      // Second call: no work to do.
      const r2 = await requestCompression({ store: adapter, projectId, llm });
      expect(r2).toBeNull();
      // WAL still has exactly one committed row.
      const db = openAndMigrate(dbPath);
      try {
        const wal = listWalRows(db, projectId);
        const committed = wal.filter((w) => w.state === 'committed');
        expect(committed).toHaveLength(1);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });

  it('markChangesCompressed is idempotent at the row level', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const projectId = hashPath(projectPath);
      // FK requires a project_db row first.
      store.appendProjectChange('seed');
      const db = openAndMigrate(dbPath);
      try {
        const id = appendChange(db, { project_id: projectId, description: 'x2', ts: 1 });
        expect(markChangesCompressed(db, projectId, [id])).toBe(1);
        // Calling again leaves the row state unchanged. SQLite's
        // `changes` count reports matched rows, not actual writes, so
        // we assert on the row state instead.
        markChangesCompressed(db, projectId, [id]);
        const rows = listProjectChanges(db, projectId);
        const target = rows.find((r) => r.id === id);
        expect(target?.compressed).toBe(1);
        // Unknown ids are silently ignored.
        expect(markChangesCompressed(db, projectId, [99999])).toBe(0);
      } finally {
        db.close();
      }
    } finally {
      store.close();
    }
  });

  it('adapting a MemoryStore is purely additive (no write side effects)', () => {
    ensureProjectFile(projectPath, 'P', '');
    const store = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPath,
      sessionsDir,
      dbPath,
    });
    try {
      const adapter = adaptMemoryStore({
        dbPath: store.dbPath,
        projectMemoryPath: store.projectMemoryPath,
        getProject: () => store.getProject(),
        invalidateProject: () => store.cache.invalidateScope('project'),
      });
      // countUncompressed / listUncompressed are read-only.
      expect(adapter.countUncompressed(hashPath(projectPath))).toBe(0);
      expect(adapter.listUncompressed(hashPath(projectPath))).toHaveLength(0);
    } finally {
      store.close();
    }
  });
});

/* ----------------------------- FNV-1a helper --------------------------- */

function hashPath(p: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < p.length; i += 1) {
    h ^= p.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}
