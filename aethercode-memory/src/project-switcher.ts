/**
 * Project switcher (T-060 ~ T-064).
 *
 * `switchProject(cwd)` closes the current project handle, opens a new
 * one for the new cwd, appends a cross-project breadcrumb to global
 * memory, and notifies a supervisor hook.
 *
 * Design anchors:
 *  - `design.md §1.5` — five-step procedure for `switchProject`.
 *  - `spec.md §1.3` — `/cwd <new>` updates the session pointer and the
 *    project memory; the LLM sees the new context.
 *
 * Side effects (all of these are also covered by tests):
 *  1. Old project cache is invalidated.
 *  2. New project row is created if absent (with a placeholder title).
 *  3. New project memory file is created if absent.
 *  4. A breadcrumb is appended to global memory.
 *  5. The supervisor hook is invoked (T-064, optional).
 */

import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import {
  parseGlobalMemory,
  serializeGlobalMemory,
  type GlobalMemory,
} from './markdown.js';
import { readProjectMemoryFile, serializeProjectMemory } from './project-markdown.js';
import {
  readProject as readProjectRow,
  upsertProject,
  type ProjectRow,
} from './project-store.js';
import { readFileSync as fsReadFileSync } from 'node:fs';
import { openAndMigrate } from './sqlite.js';
import type { Breadcrumb, MemoryReadResult } from './types.js';
import type { ProjectMemory as ProjectMemoryT } from './project-markdown.js';

/* ----------------------------- types ----------------------------------- */

/** Minimal handle into the surrounding runtime (so the switcher can
 *  invalidate caches and read/write files). */
export interface ProjectSwitchStore {
  /** Project memory path for the *current* (old) cwd. */
  readonly projectMemoryPath: string;
  /** Global memory path (cross-project breadcrumbs live here). */
  readonly globalMemoryPath: string;
  /** Path to the per-project `<cwd>/.aethercode/` directory. */
  readonly projectDir: string;
  /** The actual cwd that the project is rooted at (used to compute the
   *  "from" side of the breadcrumb). */
  readonly currentCwd: string;
  /** Path to the sqlite database. */
  readonly dbPath: string;
  /** Invalidate the cache for one project (typically the old one). */
  invalidateProject(projectId: string): void;
  /** Invalidate the global cache (after the breadcrumb is appended). */
  invalidateGlobal(): void;
}

/** A simple supervisor hook. T-064 marks this optional. */
export type SupervisorNotify = (event: SupervisorEvent) => void | Promise<void>;

/** Event passed to the supervisor. */
export interface SupervisorEvent {
  readonly kind: 'cwd-switch';
  readonly ts: number;
  readonly fromCwd: string | null;
  readonly toCwd: string;
  readonly fromProjectId: string | null;
  readonly toProjectId: string;
}

/** Result of a `switchProject` call. */
export interface SwitchResult {
  readonly projectId: string;
  readonly newCwd: string;
  /** Memory entries now associated with the new project. */
  readonly newProjectMemory: MemoryReadResult;
  /** Memory entries now associated with the global layer. */
  readonly newGlobalMemory: MemoryReadResult;
  /** The breadcrumb that was appended. */
  readonly breadcrumb: Breadcrumb;
}

/** Public options for `switchProject`. */
export interface SwitchOptions {
  /** The new cwd. Absolute path expected. */
  readonly cwd: string;
  /** Placeholder title if the project row needs to be created. */
  readonly placeholderTitle?: string;
  /** Placeholder description if the project file needs to be created. */
  readonly placeholderDescription?: string;
  /** Override clock for tests. */
  readonly now?: () => number;
  /** Optional supervisor notification hook (T-064). */
  readonly notify?: SupervisorNotify;
}

/* ----------------------------- helpers --------------------------------- */

/**
 * Compute a stable project id from an absolute path. Uses FNV-1a 32-bit
 * (same algorithm as memory-store.ts's `currentProjectId`). The two
 * implementations must stay in sync.
 */
export function hashCwd(cwd: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < cwd.length; i += 1) {
    h ^= cwd.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/** Compute the default project memory path for a given cwd. */
export function defaultProjectMemoryPath(cwd: string): string {
  return join(cwd, '.aethercode', 'memory.md');
}

/** Compute the default per-cwd `.aethercode` directory. */
export function defaultProjectDir(cwd: string): string {
  return join(cwd, '.aethercode');
}

/* ----------------------------- file IO --------------------------------- */

export interface SwitchFileFs {
  readFileSync(path: string, encoding: 'utf-8'): string;
  writeFileSync(path: string, data: string, encoding: 'utf-8'): void;
  existsSync(path: string): boolean;
  mkdirSync(path: string, opts: { recursive: boolean }): void;
}

const DEFAULT_FS: SwitchFileFs = {
  readFileSync: fsReadFileSync,
  writeFileSync,
  existsSync,
  mkdirSync,
};

/* ----------------------------- main entry ------------------------------ */

/**
 * Close the current project handle and open a new one for `cwd`. Returns
 * the new project_id plus the new memory state. The caller is expected
 * to swap the active `MemoryStore` configuration (projectMemoryPath,
 * projectDir) before the next read.
 */
export async function switchProject(
  store: ProjectSwitchStore,
  opts: SwitchOptions,
  fs: SwitchFileFs = DEFAULT_FS,
): Promise<SwitchResult> {
  if (!opts.cwd) throw new Error('switchProject: cwd must be non-empty');
  const now = opts.now?.() ?? Date.now();
  const ts = now;

  // 1) Resolve old project id (may be absent on first switch).
  const oldCwd = store.currentCwd;
  const oldProjectId = hashCwd(oldCwd);
  const newProjectId = hashCwd(opts.cwd);

  // 2) Create / look up the new project row in sqlite.
  const db = openAndMigrate(store.dbPath);
  let newProjectRow: ProjectRow | null;
  try {
    newProjectRow = readProjectRow(db, newProjectId);
    if (newProjectRow === null) {
      upsertProject(db, {
        project_id: newProjectId,
        cwd: opts.cwd,
        title: opts.placeholderTitle ?? null,
        description: opts.placeholderDescription ?? null,
        updated_at: ts,
      });
      newProjectRow = readProjectRow(db, newProjectId);
    }
  } finally {
    db.close();
  }

  // 3) Ensure the new project memory file exists.
  const newProjectMemPath = defaultProjectMemoryPath(opts.cwd);
  if (!fs.existsSync(newProjectMemPath)) {
    fs.mkdirSync(dirname(newProjectMemPath), { recursive: true });
    const title = opts.placeholderTitle ?? newProjectRow?.title ?? `project-${newProjectId.slice(0, 8)}`;
    const description = opts.placeholderDescription ?? newProjectRow?.description ?? '';
    const empty: ProjectMemoryT = { title, description, changes: [], facts: [] };
    fs.writeFileSync(newProjectMemPath, serializeProjectMemory(empty), 'utf-8');
  }

  // 4) Read the new project memory (so the caller can hand it to the LLM
  //    without an extra disk hit).
  const newProjectMemory: MemoryReadResult = readProjectMemoryIntoResult(
    fs,
    newProjectMemPath,
    newProjectId,
    ts,
  );

  // 5) Append a breadcrumb to global memory.
  const breadcrumb: Breadcrumb = {
    kind: 'breadcrumb',
    id: `global-crumb-${ts}-${newProjectId}`,
    ts,
    scope: 'global',
    source: 'system',
    tags: ['cwd-switch'],
    message: `${oldCwd} \u2192 ${opts.cwd}`,
  };
  const newGlobalMemory: MemoryReadResult = appendBreadcrumbToGlobal(
    fs,
    store.globalMemoryPath,
    breadcrumb,
    ts,
  );

  // 6) Invalidate caches (old + new + global) so the next read picks up
  //    the new project state.
  store.invalidateProject(oldProjectId);
  store.invalidateGlobal();
  store.invalidateProject(newProjectId);

  // 7) Notify the supervisor (T-064). Errors are swallowed because the
  //    supervisor is optional; the switch itself has already succeeded.
  if (opts.notify !== undefined) {
    try {
      await opts.notify({
        kind: 'cwd-switch',
        ts,
        fromCwd: oldCwd,
        toCwd: opts.cwd,
        fromProjectId: oldProjectId,
        toProjectId: newProjectId,
      });
    } catch {
      // best-effort; do not break the switch.
    }
  }

  return {
    projectId: newProjectId,
    newCwd: opts.cwd,
    newProjectMemory,
    newGlobalMemory,
    breadcrumb,
  };
}

/* ----------------------------- helpers --------------------------------- */

function readProjectMemoryIntoResult(
  fs: SwitchFileFs,
  projectMemoryPath: string,
  projectId: string,
  now: number,
): MemoryReadResult {
  const defaultTitle = `project-${projectId.slice(0, 8) || 'untitled'}`;
  const mem = readProjectMemoryFile(
    { readFileSync: fs.readFileSync, existsSync: fs.existsSync },
    projectMemoryPath,
    defaultTitle,
    now,
  );
  const entries = [...mem.changes, ...mem.facts];
  const totalTokens = entries.reduce(
    (acc, e) => acc + estimateEntryTokens(e as import('./types.js').MemoryEntry),
    0,
  );
  return { source: 'file', entries, totalTokens, truncated: false };
}

function estimateEntryTokens(e: import('./types.js').MemoryEntry): number {
  let text: string;
  switch (e.kind) {
    case 'change':
      text = e.description;
      break;
    case 'fact':
      text = `${e.key}: ${e.value}`;
      break;
    case 'rule':
      text = e.text;
      break;
    case 'breadcrumb':
      text = e.message;
      break;
  }
  return Math.max(1, Math.ceil(text.length / 4));
}

function appendBreadcrumbToGlobal(
  fs: SwitchFileFs,
  globalPath: string,
  crumb: Breadcrumb,
  now: number,
): MemoryReadResult {
  let current: GlobalMemory;
  if (!fs.existsSync(globalPath)) {
    current = { facts: [], rules: [], breadcrumbs: [] };
  } else {
    const text = fs.readFileSync(globalPath, 'utf-8');
    current = parseGlobalMemory(text, now);
  }
  const next: GlobalMemory = {
    facts: [...current.facts],
    rules: [...current.rules],
    breadcrumbs: [...current.breadcrumbs, crumb],
  };
  fs.mkdirSync(dirname(globalPath), { recursive: true });
  fs.writeFileSync(globalPath, serializeGlobalMemory(next), 'utf-8');
  const entries = [...next.facts, ...next.rules, ...next.breadcrumbs];
  return { source: 'file', entries, totalTokens: estimateTokens(entries), truncated: false };
}

function estimateTokens(entries: ReadonlyArray<{ kind: string }>): number {
  let total = 0;
  for (const e of entries) {
    // Cheap estimator: 4 chars per token. The exact value isn't important
    // here — the caller only uses it for budget reporting.
    const text = JSON.stringify(e);
    total += Math.max(1, Math.ceil(text.length / 4));
  }
  return total;
}

// Reference unused import so the type checker is honest about it.
void mkdirSync;
