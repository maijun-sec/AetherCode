/**
 * Project-memory compression pipeline (T-050 ~ T-058).
 *
 * Compression is triggered when `appendProjectChange` notices that the
 * uncompressed change count has reached the threshold (default N=20).
 * The runtime then asks the LLM to fold the old changes into the
 * project description and trims the change list back to N.
 *
 * Design anchors:
 *  - `design.md §1.4` — pipeline definition, 4-shot prompt shape.
 *  - `spec.md §1.2` — project memory layout and N=20 default.
 *
 * Idempotency / crash safety (T-053, T-054): every in-flight pass is
 * recorded in the `compression_wal` table (added in migration v4). On
 * startup (and on every `requestCompression` call), uncompleted passes
 * are picked up from the WAL and replayed.
 *
 * Trigger model (T-050, T-052): the runtime owns scheduling. This module
 * exposes `shouldTriggerCompression(count, opts)` and
 * `requestCompression(store, opts)`. The caller decides whether to call
 * it inline or schedule it via `aethercode-tasks`. We do not depend on
 * that module here to keep the dependency graph clean.
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { openAndMigrate, queryAll, runStatement } from './sqlite.js';
import {
  appendProjectChange as appendProjectChangeRow,
  listProjectChanges,
  markChangesCompressed,
  readProject,
  upsertProject,
  type ProjectChangeRow,
} from './project-store.js';
import { readProjectMemoryFile, serializeProjectMemory, parseProjectMemory } from './project-markdown.js';
import type { ChangeEntry, MemoryReadResult } from './types.js';
import type { ProjectMemory } from './project-markdown.js';

/* ----------------------------- constants ------------------------------- */

/** Default N — keep the most recent N changes in the file. Matches design.md §1.4. */
export const DEFAULT_CHANGE_LIMIT = 20;

/** Max output tokens the LLM should target for the new project description. */
export const DEFAULT_MAX_DESCRIPTION_TOKENS = 4000;

/* ----------------------------- types ----------------------------------- */

/** Minimal LLM contract used by compression. Mirrors `aethercode-compact`'s `LlmClient`. */
export interface CompressionLlmClient {
  /**
   * Send `prompt` and return the model's raw text response. The compression
   * pipeline does the parsing/validation. The implementation may add
   * retries/timeouts internally; the pipeline only sees a string.
   */
  complete(prompt: string, options?: { maxTokens?: number; model?: string }): Promise<string>;
}

/** Configuration for the compression pipeline. */
export interface CompressionOptions {
  /** When the uncompressed change count reaches this, trigger compression. Default 20. */
  readonly changeLimit?: number;
  /** Cap on LLM output tokens. Default 4000. */
  readonly maxDescriptionTokens?: number;
  /** Override the 4-shot prompt template (must contain `{description}` and `{changes}`). */
  readonly promptTemplate?: string;
  /** Override clock for tests. */
  readonly now?: () => number;
  /**
   * Override the threshold for *uncompressed* changes that triggers
   * compression. Default 0 (trigger the moment count >= changeLimit).
   * Treated as a floor: compression fires when uncompressedCount >=
   * changeLimit - triggerHeadroom.
   */
  readonly triggerHeadroom?: number;
}

/** What compression returns. */
export interface CompactResult {
  /** Number of uncompressed changes folded into the description. */
  readonly beforeTokens: number;
  /** Number of tokens in the new project description (post-compression). */
  readonly afterTokens: number;
  /** Number of change rows marked compressed=1. */
  readonly changesCompressed: number;
  /** Wall-clock duration of the pass. */
  readonly ms: number;
  /** True when there was nothing to compress. */
  readonly skipped: boolean;
  /** True when this pass resumed a previous unfinished WAL entry. */
  readonly resumed: boolean;
}

/** A row in the `compression_wal` table (T-053). */
export interface CompressionWalRow {
  readonly id: number;
  readonly project_id: string;
  readonly started_at: number;
  readonly finished_at: number | null;
  readonly attempt: number;
  readonly state: 'pending' | 'llm_done' | 'committed' | 'failed';
  readonly before_changes_json: string;
  readonly new_description: string | null;
  readonly new_description_tokens: number | null;
  readonly error: string | null;
}

/** The shape of the `MemoryStore` that compression needs. We keep it narrow so
 * this module can be unit-tested with a fake. */
export interface CompressionStore {
  readonly dbPath: string;
  readonly projectMemoryPath: string;
  /** Used by tests to count uncompressed changes. */
  countUncompressed(projectId: string): number;
  /** Used by tests to list uncompressed changes. */
  listUncompressed(projectId: string): ReadonlyArray<ChangeEntry>;
  /** Read the full project memory (file + sqlite). */
  getProject(): MemoryReadResult;
  /** Called by compression after a successful pass; should invalidate caches. */
  invalidateProject(): void;
  /** Open the raw better-sqlite3 handle for WAL operations. Borrowed, not owned. */
  openDb(): DatabaseT;
  /** Append a fresh change — re-used by some tests as a no-op. */
  appendProjectChange(description: string): ChangeEntry;
}

/* ----------------------------- prompt ---------------------------------- */

/**
 * 4-shot prompt template for the project-memory compression. The model is
 * shown four short (input, output) pairs that demonstrate how to fold a
 * batch of project changes into a concise project description. The fifth
 * request is the live one. Shots are kept terse to save input budget.
 *
 * Placeholders: `{description}` (current description), `{changes}` (one
 * bullet per change), `{max_tokens}` (output budget).
 */
export const DEFAULT_COMPRESSION_PROMPT = `你是项目记忆整合助手。给定当前的项目说明和一批新增的修改记录，把新增内容中的关键信息融入到项目说明里。

规则:
- 保持简洁 (最多 {max_tokens} tokens)
- 不删除现有说明中仍然成立的事实
- 不要引入新事实，只整合已有信息
- 输出为纯文本项目说明，不要 markdown 标题或代码块

【示例 1】
当前说明: 空
待整合: - 添加用户登录接口
输出: 项目提供用户登录接口。

【示例 2】
当前说明: 项目使用 SQLite 存用户。
待整合: - 改用 Postgres
- 增加连接池
输出: 项目使用 Postgres 存用户，启用连接池。

【示例 3】
当前说明: CLI 提供 ship-it / quick-review 工作流。
待整合: - 删除 ship-it
- 新增 explain-code
输出: CLI 提供 quick-review 与 explain-code 工作流 (ship-it 已废弃)。

【示例 4】
当前说明: TUI 走 React + Ink。
待整合: - 主题系统抽出 aethercode-themes
- 主题切换走 ThemeSelector
输出: TUI 走 React + Ink；主题系统由 aethercode-themes 提供，运行时通过 ThemeSelector 切换。

【待整合】
当前说明:
{description}

待整合的修改记录:
{changes}

请只输出整合后的项目说明正文 (不要标题、不要前后缀):`;

/* ----------------------------- helpers --------------------------------- */

/**
 * Decide whether a project should trigger a compression pass right now.
 * `uncompressedCount` is the number of project_changes rows with
 * compressed=0; the function returns true when the threshold is reached.
 */
export function shouldTriggerCompression(
  uncompressedCount: number,
  opts: CompressionOptions = {},
): boolean {
  const limit = opts.changeLimit ?? DEFAULT_CHANGE_LIMIT;
  const headroom = Math.max(0, opts.triggerHeadroom ?? 0);
  return uncompressedCount >= Math.max(1, limit - headroom);
}

/** Estimate the number of tokens in a string using the same 4-chars-per-token rule. */
export function estimateDescriptionTokens(text: string): number {
  return Math.max(1, Math.ceil(text.length / 4));
}

/** Render a list of changes as a bullet list for the prompt. */
export function renderChangesForPrompt(changes: ReadonlyArray<ChangeEntry>): string {
  if (changes.length === 0) return '(none)';
  return changes.map((c) => `- ${new Date(c.ts).toISOString()} ${c.description}`).join('\n');
}

/** Compose the final 4-shot prompt for the given inputs. */
export function buildCompressionPrompt(
  description: string,
  changes: ReadonlyArray<ChangeEntry>,
  opts: CompressionOptions = {},
): string {
  const template = opts.promptTemplate ?? DEFAULT_COMPRESSION_PROMPT;
  const maxTokens = opts.maxDescriptionTokens ?? DEFAULT_MAX_DESCRIPTION_TOKENS;
  return template
    .replace('{description}', description || '(空)')
    .replace('{changes}', renderChangesForPrompt(changes))
    .replace('{max_tokens}', String(maxTokens));
}

/* ----------------------------- WAL store ------------------------------- */

/** Read every WAL row, newest first. */
export function listWalRows(db: DatabaseT, projectId: string): CompressionWalRow[] {
  return queryAll<CompressionWalRow>(
    db,
    `SELECT id, project_id, started_at, finished_at, attempt, state,
            before_changes_json, new_description, new_description_tokens, error
       FROM compression_wal
      WHERE project_id = ?
      ORDER BY id DESC`,
    [projectId],
    (r) => ({
      id: Number(r['id'] ?? 0),
      project_id: String(r['project_id'] ?? ''),
      started_at: Number(r['started_at'] ?? 0),
      finished_at: r['finished_at'] === null || r['finished_at'] === undefined
        ? null
        : Number(r['finished_at']),
      attempt: Number(r['attempt'] ?? 1),
      state: normalizeWalState(r['state']),
      before_changes_json: String(r['before_changes_json'] ?? '[]'),
      new_description: r['new_description'] === null || r['new_description'] === undefined
        ? null
        : String(r['new_description']),
      new_description_tokens:
        r['new_description_tokens'] === null || r['new_description_tokens'] === undefined
          ? null
          : Number(r['new_description_tokens']),
      error: r['error'] === null || r['error'] === undefined ? null : String(r['error']),
    }),
  );
}

/** Find the most recent unfinished WAL row for the project (T-054). */
export function findResumableWal(db: DatabaseT, projectId: string): CompressionWalRow | null {
  const rows = listWalRows(db, projectId);
  for (const r of rows) {
    if (r.state === 'pending' || r.state === 'llm_done') {
      return r;
    }
  }
  return null;
}

function normalizeWalState(raw: unknown): CompressionWalRow['state'] {
  const v = typeof raw === 'string' ? raw : '';
  if (v === 'pending' || v === 'llm_done' || v === 'committed' || v === 'failed') {
    return v;
  }
  return 'pending';
}

/** Insert a new pending WAL row. Returns the new row id. */
export function startWalEntry(
  db: DatabaseT,
  projectId: string,
  changes: ReadonlyArray<ProjectChangeRow>,
  now: number,
): number {
  const payload = JSON.stringify(changes.map((c) => ({ id: c.id, ts: c.ts, description: c.description })));
  const info = runStatement(
    db,
    `INSERT INTO compression_wal
       (project_id, started_at, attempt, state, before_changes_json)
     VALUES (?, ?, 1, 'pending', ?)`,
    [projectId, now, payload],
  );
  return typeof info.lastInsertRowid === 'bigint'
    ? Number(info.lastInsertRowid)
    : (info.lastInsertRowid as number);
}

/** Mark a WAL row as `llm_done` (LLM returned, not yet persisted to file). */
export function markWalLlmDone(
  db: DatabaseT,
  walId: number,
  newDescription: string,
  tokens: number,
): void {
  runStatement(
    db,
    `UPDATE compression_wal
        SET state = 'llm_done',
            new_description = ?,
            new_description_tokens = ?
      WHERE id = ?`,
    [newDescription, tokens, walId],
  );
}

/** Mark a WAL row as `committed` (file + sqlite persisted). */
export function markWalCommitted(db: DatabaseT, walId: number, now: number): void {
  runStatement(
    db,
    `UPDATE compression_wal
        SET state = 'committed',
            finished_at = ?,
            error = NULL
      WHERE id = ?`,
    [now, walId],
  );
}

/** Mark a WAL row as `failed` (caller can retry). */
export function markWalFailed(db: DatabaseT, walId: number, error: string, now: number): void {
  runStatement(
    db,
    `UPDATE compression_wal
        SET state = 'failed',
            finished_at = ?,
            error = ?
      WHERE id = ?`,
    [now, error, walId],
  );
}

/* ----------------------------- apply ----------------------------------- */

/**
 * Persist a compression result: rewrite the project memory file with the
 * new description + the N most recent change rows, update the
 * `project_db.description`, mark the folded changes `compressed=1`, and
 * mark the WAL row as `committed`. All writes go through one sqlite
 * transaction for atomicity (T-053).
 *
 * `fileFs` is injected so tests can use an in-memory FS; production
 * passes the real `fs`.
 */
export interface CompressionFileFs {
  readFileSync(path: string, encoding: 'utf-8'): string;
  writeFileSync(path: string, data: string, encoding: 'utf-8'): void;
  existsSync(path: string): boolean;
  mkdirSync(path: string, opts: { recursive: boolean }): void;
}

export function applyCompression(opts: {
  db: DatabaseT;
  projectId: string;
  projectMemoryPath: string;
  walId: number;
  newDescription: string;
  changeLimit: number;
  changesToCompress: ReadonlyArray<ProjectChangeRow>;
  fs: CompressionFileFs;
  now: number;
}): CompactResult {
  const start = opts.now;
  // Read current file.
  const current: ProjectMemory = opts.fs.existsSync(opts.projectMemoryPath)
    ? parseProjectMemory(opts.fs.readFileSync(opts.projectMemoryPath, 'utf-8'), start)
    : { title: 'Untitled Project', description: '', changes: [], facts: [] };

  const beforeTokens = estimateDescriptionTokens(current.description);
  const newDescriptionTokens = estimateDescriptionTokens(opts.newDescription);

  // Most recent N (uncompressed + future incoming) — we drop everything
  // older than the latest `changeLimit` change rows in the DB. The
  // canonical "newest" key is the autoincrement `id` (ties on `ts` are
  // common in fast tests; we want a stable, well-defined order).
  const allChangesAsc = listProjectChanges(opts.db, opts.projectId, { descending: false });
  const allChanges = [...allChangesAsc].sort((a, b) => a.id - b.id);
  const trimmed = allChanges.slice(-opts.changeLimit);
  const recent = trimmed.map(
    (c): ChangeEntry => ({
      kind: 'change',
      id: `project-change-${c.id}`,
      ts: c.ts,
      scope: 'project',
      source: 'system',
      tags: ['change'],
      description: c.description,
      compressed: c.compressed === 1,
    }),
  );

  const next: ProjectMemory = {
    title: current.title,
    description: opts.newDescription,
    changes: recent,
    facts: current.facts,
  };

  // Persist (file + sqlite) atomically.
  const apply = opts.db.transaction((): void => {
    // 1) Update the project_db row (description is the project description).
    const existing = readProject(opts.db, opts.projectId);
    if (existing !== null) {
      upsertProject(opts.db, {
        project_id: opts.projectId,
        cwd: existing.cwd,
        title: existing.title,
        description: opts.newDescription,
        updated_at: start,
      });
    }
    // 2) Mark the folded change rows compressed=1.
    const compressedIds = opts.changesToCompress.map((c) => c.id);
    if (compressedIds.length > 0) {
      markChangesCompressed(opts.db, opts.projectId, compressedIds);
    }
    // 3) Close out the WAL row.
    markWalCommitted(opts.db, opts.walId, start);
  });
  apply();

  // Write the file *after* the sqlite transaction commits so a failure
  // here can be detected (and the next call replays the WAL) but the
  // source-of-truth (sqlite) is already consistent.
  opts.fs.mkdirSync(dirname(opts.projectMemoryPath), { recursive: true });
  opts.fs.writeFileSync(opts.projectMemoryPath, serializeProjectMemory(next, opts.changeLimit), 'utf-8');

  return {
    beforeTokens,
    afterTokens: newDescriptionTokens,
    changesCompressed: opts.changesToCompress.length,
    ms: 0,
    skipped: false,
    resumed: false,
  };
}

/* ----------------------------- entry point ----------------------------- */

/**
 * Run a single compression pass. Idempotent: a previously committed pass
 * is a no-op; a pending/llm_done WAL row is resumed (T-054). The function
 * returns `skipped: true` when the project has no work to do.
 */
export async function runCompressionPass(opts: {
  store: CompressionStore;
  projectId: string;
  llm: CompressionLlmClient;
  config?: CompressionOptions;
  fs?: CompressionFileFs;
}): Promise<CompactResult> {
  const config = opts.config ?? {};
  const changeLimit = config.changeLimit ?? DEFAULT_CHANGE_LIMIT;
  const maxTokens = config.maxDescriptionTokens ?? DEFAULT_MAX_DESCRIPTION_TOKENS;
  const useFs: CompressionFileFs = opts.fs ?? {
    readFileSync,
    writeFileSync,
    existsSync: (p: string): boolean => {
      // Lazy import for prod paths; tests always pass `fs`.
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      return (require('node:fs') as { existsSync: (p: string) => boolean }).existsSync(p);
    },
    mkdirSync,
  };

  const startedAt = config.now?.() ?? Date.now();
  const t0 = startedAt;
  const now = (): number => config.now?.() ?? Date.now();

  // Open a one-shot db handle so we can do the WAL dance without
  // borrowing the store's connection (which may be in the middle of
  // a write transaction).
  const db = opts.store.openDb();
  let walId: number;
  let resumed = false;
  let changesToCompress: ProjectChangeRow[];

  try {
    // Resume a pending pass if one exists.
    const resumable = findResumableWal(db, opts.projectId);
    if (resumable !== null) {
      walId = resumable.id;
      resumed = true;
      const parsed = JSON.parse(resumable.before_changes_json) as ReadonlyArray<{
        id: number;
        ts: number;
        description: string;
      }>;
      changesToCompress = parsed.map(
        (c): ProjectChangeRow => ({
          id: c.id,
          project_id: opts.projectId,
          ts: c.ts,
          description: c.description,
          compressed: 0,
          expires_at: null,
          consolidated_into: null,
          value_tag: 'normal' as const,
          last_accessed_at: 0,
          access_count: 0,
          deleted_at: null,
          team_session_id: null,
        }),
      );
      // If the LLM step already finished, we can skip straight to apply.
      if (resumable.state === 'llm_done' && resumable.new_description !== null) {
        const result = applyCompression({
          db,
          projectId: opts.projectId,
          projectMemoryPath: opts.store.projectMemoryPath,
          walId,
          newDescription: resumable.new_description,
          changeLimit,
          changesToCompress,
          fs: useFs,
          now: now(),
        });
        opts.store.invalidateProject();
        return { ...result, ms: now() - t0, resumed };
      }
      // 'pending' → re-issue the LLM call.
    } else {
      // Fresh pass.
      changesToCompress = listProjectChanges(db, opts.projectId, { uncompressedOnly: true });
      if (changesToCompress.length === 0) {
        return {
          beforeTokens: 0,
          afterTokens: 0,
          changesCompressed: 0,
          ms: now() - t0,
          skipped: true,
          resumed: false,
        };
      }
      walId = startWalEntry(db, opts.projectId, changesToCompress, startedAt);
    }

    // Read current project memory (file) to build the prompt.
    const defaultTitle = `project-${opts.projectId.slice(0, 8) || 'untitled'}`;
    const projectMem = readProjectMemoryFile(
      { readFileSync: useFs.readFileSync, existsSync: useFs.existsSync },
      opts.store.projectMemoryPath,
      defaultTitle,
      startedAt,
    );

    const entries: ChangeEntry[] = changesToCompress.map(
      (c): ChangeEntry => ({
        kind: 'change',
        id: `project-change-${c.id}`,
        ts: c.ts,
        scope: 'project',
        source: 'system',
        tags: ['change'],
        description: c.description,
        compressed: false,
      }),
    );

    const prompt = buildCompressionPrompt(projectMem.description, entries, config);

    // Call the LLM. On error, mark the WAL row failed so the next
    // resume attempt can retry. We don't throw — the caller can decide.
    let raw: string;
    try {
      raw = await opts.llm.complete(prompt, { maxTokens });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      markWalFailed(db, walId, message, now());
      return {
        beforeTokens: 0,
        afterTokens: 0,
        changesCompressed: 0,
        ms: now() - t0,
        skipped: false,
        resumed,
      };
    }
    const cleaned = raw.trim();
    const newTokens = estimateDescriptionTokens(cleaned);
    markWalLlmDone(db, walId, cleaned, newTokens);

    const result = applyCompression({
      db,
      projectId: opts.projectId,
      projectMemoryPath: opts.store.projectMemoryPath,
      walId,
      newDescription: cleaned,
      changeLimit,
      changesToCompress,
      fs: useFs,
      now: now(),
    });
    opts.store.invalidateProject();
    return { ...result, ms: now() - t0, resumed };
  } finally {
    db.close();
  }
}

/* --------------------------- entry: request ---------------------------- */

/**
 * High-level entry point: called by the runtime after each
 * `appendProjectChange`. Returns `null` when the trigger is not met;
 * otherwise schedules a compression pass and resolves when the pass
 * finishes. The runtime can choose to call this without awaiting
 * (fire-and-forget) by chaining `.catch(...)`.
 */
export async function requestCompression(opts: {
  store: CompressionStore;
  projectId: string;
  llm: CompressionLlmClient;
  config?: CompressionOptions;
  fs?: CompressionFileFs;
}): Promise<CompactResult | null> {
  const config = opts.config ?? {};
  const uncompressed = opts.store.countUncompressed(opts.projectId);
  if (shouldTriggerCompression(uncompressed, config)) {
    return runCompressionPass({
      store: opts.store,
      projectId: opts.projectId,
      llm: opts.llm,
      config: opts.config,
      fs: opts.fs,
    });
  }
  // Otherwise, only run if a previous pass was interrupted (T-054). Open
  // a one-shot handle, check for a resumable WAL row, and close it before
  // returning — otherwise the connection leaks and Windows file locks
  // block the test cleanup.
  const db = opts.store.openDb();
  try {
    if (findResumableWal(db, opts.projectId) !== null) {
      return runCompressionPass({
        store: opts.store,
        projectId: opts.projectId,
        llm: opts.llm,
        config: opts.config,
        fs: opts.fs,
      });
    }
  } finally {
    db.close();
  }
  return null;
}

/* ----------------------- used-by-memory-store glue --------------------- */

/**
 * Adapter: turn a real `MemoryStore` into the narrow `CompressionStore`
 * interface required by this module. Defined here (instead of in
 * memory-store.ts) to keep the dependency direction one-way: this file
 * imports from memory-store only via the type.
 */
export function adaptMemoryStore(store: {
  dbPath: string;
  projectMemoryPath: string;
  getProject(): MemoryReadResult;
  invalidateProject(): void;
}): CompressionStore {
  return {
    dbPath: store.dbPath,
    projectMemoryPath: store.projectMemoryPath,
    countUncompressed(projectId) {
      const db = openAndMigrate(store.dbPath);
      try {
        const rows = queryAll<{ n: number }>(
          db,
          'SELECT COUNT(*) AS n FROM project_changes WHERE project_id = ? AND compressed = 0',
          [projectId],
          (r) => ({ n: Number(r['n'] ?? 0) }),
        );
        return rows[0]?.n ?? 0;
      } finally {
        db.close();
      }
    },
    listUncompressed(projectId) {
      const db = openAndMigrate(store.dbPath);
      try {
        const rows = listProjectChanges(db, projectId, { uncompressedOnly: true });
        return rows.map(
          (r): ChangeEntry => ({
            kind: 'change',
            id: `project-change-${r.id}`,
            ts: r.ts,
            scope: 'project',
            source: 'system',
            tags: ['change'],
            description: r.description,
            compressed: false,
          }),
        );
      } finally {
        db.close();
      }
    },
    getProject: store.getProject,
    invalidateProject: store.invalidateProject,
    openDb() {
      return openAndMigrate(store.dbPath);
    },
    appendProjectChange(description) {
      // Should be unused by compression in production; provided so the
      // type stays consistent if a future caller wires it up.
      const projectId = currentProjectIdFromPath(store.projectMemoryPath);
      const db = openAndMigrate(store.dbPath);
      try {
        const ts = Date.now();
        const id = appendProjectChangeRow(db, { project_id: projectId, description, ts });
        return {
          kind: 'change',
          id: `project-change-${id}`,
          ts,
          scope: 'project',
          source: 'system',
          tags: ['change'],
          description,
          compressed: false,
        };
      } finally {
        db.close();
      }
    },
  };
}

/**
 * Re-implement the FNV-1a project id hash here so the adapter is
 * self-contained (memory-store.ts's `currentProjectId` is not exported).
 * The two implementations must stay in sync; the canonical version lives
 * in memory-store.ts.
 */
function currentProjectIdFromPath(projectMemoryPath: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < projectMemoryPath.length; i += 1) {
    h ^= projectMemoryPath.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}
