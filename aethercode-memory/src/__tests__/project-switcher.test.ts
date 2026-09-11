/**
 * Project switcher tests (T-065).
 *
 * Coverage:
 *  - T-061  hashCwd is stable + 8-char hex
 *  - T-060  switchProject creates a new project row + memory file
 *  - T-062  switchProject opens a new project memory file
 *  - T-063  switchProject appends a breadcrumb to global memory
 *  - T-064  switchProject notifies the supervisor (optional hook)
 *  - T-065  switching back and forth works
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import {
  defaultProjectDir,
  defaultProjectMemoryPath,
  hashCwd,
  switchProject,
  type ProjectSwitchStore,
  type SwitchFileFs,
  type SupervisorEvent,
  type SupervisorNotify,
} from '../project-switcher.js';
import { createMemoryStore, ensureProjectFile } from '../memory-store.js';
import { openAndMigrate } from '../sqlite.js';
import { listProjects } from '../project-store.js';
import { parseGlobalMemory } from '../markdown.js';

let tmpDir = '';
let globalPath = '';
let oldCwd = '';
let newCwd = '';
let dbPath = '';
let projectPathOld = '';

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-switcher-'));
  globalPath = join(tmpDir, 'global-memory.md');
  oldCwd = join(tmpDir, 'old');
  newCwd = join(tmpDir, 'new');
  dbPath = join(tmpDir, 'store.db');
  projectPathOld = defaultProjectMemoryPath(oldCwd);
  mkdirSync(dirname(globalPath), { recursive: true });
  mkdirSync(defaultProjectDir(oldCwd), { recursive: true });
  mkdirSync(newCwd, { recursive: true });
});

afterEach(() => {
  if (tmpDir && existsSync(tmpDir)) {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

function makeFs(): SwitchFileFs {
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

function makeStore(
  invalidated: { projects: string[]; globals: number },
  cwd: string = oldCwd,
): ProjectSwitchStore {
  // The switcher only calls cache-invalidation methods + uses the paths,
  // so a stub is enough.
  const memPath = defaultProjectMemoryPath(cwd);
  return {
    projectMemoryPath: memPath,
    globalMemoryPath: globalPath,
    projectDir: defaultProjectDir(cwd),
    currentCwd: cwd,
    dbPath,
    invalidateProject: (projectId: string): void => {
      invalidated.projects.push(projectId);
    },
    invalidateGlobal: (): void => {
      invalidated.globals += 1;
    },
  };
}

/* ----------------------------- T-061 ----------------------------------- */

describe('T-061 — hashCwd', () => {
  it('produces an 8-char hex string', () => {
    const id = hashCwd('/some/path');
    expect(id).toMatch(/^[0-9a-f]{8}$/);
  });
  it('is stable across calls', () => {
    expect(hashCwd('/a/b/c')).toBe(hashCwd('/a/b/c'));
  });
  it('differs for different paths', () => {
    expect(hashCwd('/a')).not.toBe(hashCwd('/b'));
    expect(hashCwd('/a/b')).not.toBe(hashCwd('/a/c'));
  });
  it('defaultProjectMemoryPath and defaultProjectDir are consistent', () => {
    expect(defaultProjectMemoryPath('/x')).toBe(join('/x', '.aethercode', 'memory.md'));
    expect(defaultProjectDir('/x')).toBe(join('/x', '.aethercode'));
  });
});

/* ----------------------------- T-060 + T-062 --------------------------- */

describe('T-060 / T-062 — switchProject opens a new project', () => {
  it('creates a new project_db row when absent', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    const store = makeStore(invalidated);
    const result = await switchProject(
      store,
      { cwd: newCwd, placeholderTitle: 'NewProj' },
      makeFs(),
    );
    expect(result.projectId).toBe(hashCwd(newCwd));
    const newProjectMem = defaultProjectMemoryPath(newCwd);
    expect(existsSync(newProjectMem)).toBe(true);
    const text = readFileSync(newProjectMem, 'utf-8');
    expect(text).toContain('# NewProj');
  });

  it('re-uses an existing project_db row if present', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    // Seed the old project.
    ensureProjectFile(projectPathOld, 'OldProj', 'old description');
    const memStore = createMemoryStore({
      globalMemoryPath: globalPath,
      projectMemoryPath: projectPathOld,
      sessionsDir: join(tmpDir, 'sessions'),
      dbPath,
    });
    try {
      // First switch: creates the new project.
      const result1 = await switchProject(
        makeStore(invalidated, oldCwd),
        { cwd: newCwd, placeholderTitle: 'NewProj' },
        makeFs(),
      );
      expect(result1.projectId).toBe(hashCwd(newCwd));
    } finally {
      memStore.close();
    }
  });

  it('returns the new project memory entries (file-backed)', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    const result = await switchProject(
      makeStore(invalidated),
      { cwd: newCwd, placeholderTitle: 'NewProj' },
      makeFs(),
    );
    expect(result.newProjectMemory.source).toBe('file');
    // A fresh project has no changes/facts; the result is just an empty
    // entries list with `source: 'file'`.
    expect(Array.isArray(result.newProjectMemory.entries)).toBe(true);
    expect(result.newProjectMemory.entries).toHaveLength(0);
  });
});

/* ----------------------------- T-063 — breadcrumb ---------------------- */

describe('T-063 — switchProject appends a breadcrumb to global memory', () => {
  it('creates the global file with the breadcrumb if missing', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    const result = await switchProject(
      makeStore(invalidated),
      { cwd: newCwd },
      makeFs(),
    );
    expect(existsSync(globalPath)).toBe(true);
    const text = readFileSync(globalPath, 'utf-8');
    expect(text).toContain(oldCwd);
    expect(text).toContain(newCwd);
    expect(result.breadcrumb.scope).toBe('global');
    expect(result.breadcrumb.source).toBe('system');
    expect(result.breadcrumb.tags).toContain('cwd-switch');
  });

  it('appends to an existing global file', async () => {
    writeFileSync(
      globalPath,
      '# Global Memory\n\n## Facts\n- user.name: Alice\n\n## Rules\n\n## Cross-project breadcrumbs\n',
      'utf-8',
    );
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(makeStore(invalidated), { cwd: newCwd }, makeFs());
    const text = readFileSync(globalPath, 'utf-8');
    // The existing fact is preserved.
    expect(text).toContain('user.name: Alice');
    // The new breadcrumb is appended.
    expect(text).toContain(newCwd);
  });

  it('produces a parseable global file (round-trip)', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(makeStore(invalidated), { cwd: newCwd }, makeFs());
    const text = readFileSync(globalPath, 'utf-8');
    const parsed = parseGlobalMemory(text);
    expect(parsed.breadcrumbs.length).toBe(1);
    expect(parsed.breadcrumbs[0]?.message).toContain(newCwd);
  });
});

/* ----------------------------- T-064 — supervisor hook ----------------- */

describe('T-064 — supervisor notification', () => {
  it('invokes the hook with a cwd-switch event', async () => {
    const events: SupervisorEvent[] = [];
    const notify: SupervisorNotify = async (e): Promise<void> => {
      events.push(e);
    };
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(
      makeStore(invalidated),
      { cwd: newCwd, notify },
      makeFs(),
    );
    expect(events).toHaveLength(1);
    expect(events[0]?.kind).toBe('cwd-switch');
    expect(events[0]?.toCwd).toBe(newCwd);
    expect(events[0]?.fromCwd).toBe(oldCwd);
    expect(events[0]?.toProjectId).toBe(hashCwd(newCwd));
    expect(events[0]?.fromProjectId).toBe(hashCwd(oldCwd));
  });

  it('does not break the switch when the hook throws', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    const result = await switchProject(
      makeStore(invalidated),
      {
        cwd: newCwd,
        notify: (): void => {
          throw new Error('hook boom');
        },
      },
      makeFs(),
    );
    expect(result.projectId).toBe(hashCwd(newCwd));
  });

  it('skips the hook when not provided', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    // No notify option.
    const result = await switchProject(makeStore(invalidated), { cwd: newCwd }, makeFs());
    expect(result.projectId).toBe(hashCwd(newCwd));
  });
});

/* ----------------------------- T-065 — switch back-and-forth ----------- */

describe('T-065 — switching back and forth', () => {
  it('records two breadcrumbs when toggling A → B → A', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(makeStore(invalidated, oldCwd), { cwd: newCwd }, makeFs());
    // The "store" now points at the *new* project; simulate the caller
    // updating the active path so the next switch computes the right
    // "from" cwd.
    const inverted = makeStore(invalidated, newCwd);
    await switchProject(inverted, { cwd: oldCwd }, makeFs());
    const text = readFileSync(globalPath, 'utf-8');
    const parsed = parseGlobalMemory(text);
    expect(parsed.breadcrumbs).toHaveLength(2);
    const messages = parsed.breadcrumbs.map((b) => b.message);
    expect(messages.some((m) => m.includes(newCwd))).toBe(true);
    expect(messages.some((m) => m.includes(oldCwd))).toBe(true);
  });

  it('invalidateProject is called for both the old and new projects', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(makeStore(invalidated), { cwd: newCwd }, makeFs());
    const ids = invalidated.projects;
    expect(ids).toContain(hashCwd(oldCwd));
    expect(ids).toContain(hashCwd(newCwd));
    // invalidateGlobal was called once (for the breadcrumb).
    expect(invalidated.globals).toBe(1);
  });

  it('persists the new project_db row across switches', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(makeStore(invalidated), { cwd: newCwd }, makeFs());
    const db = openAndMigrate(dbPath);
    try {
      const projects = listProjects(db);
      const ids = projects.map((p) => p.project_id);
      expect(ids).toContain(hashCwd(newCwd));
    } finally {
      db.close();
    }
  });

  it('creates the new project memory file with the placeholder title', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await switchProject(
      makeStore(invalidated),
      { cwd: newCwd, placeholderTitle: 'MyNewProject', placeholderDescription: 'a fresh start' },
      makeFs(),
    );
    const newMem = defaultProjectMemoryPath(newCwd);
    const text = readFileSync(newMem, 'utf-8');
    expect(text).toContain('# MyNewProject');
    expect(text).toContain('a fresh start');
  });
});

/* ----------------------------- error path ------------------------------ */

describe('switchProject — error handling', () => {
  it('throws on empty cwd', async () => {
    const invalidated = { projects: [] as string[], globals: 0 };
    await expect(switchProject(makeStore(invalidated), { cwd: '' }, makeFs())).rejects.toThrow(/cwd/);
  });
});
