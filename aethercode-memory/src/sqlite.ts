/**
 * SQLite migration runner (versioned).
 *
 * A simple versioned migration scheme:
 *
 * - The `schema_version` PRAGMA user-version holds the highest applied version.
 * - `migrations` is an ordered list of `{ version, up }` pairs; `up` runs SQL
 *   statements inside a single transaction and must be idempotent within
 *   itself (the runner will not re-run applied versions).
 * - `openAndMigrate(dbPath, migrations)` opens (or creates) the database
 *   file, ensures the directory exists, and applies any pending migrations.
 *
 * This module does not own the schema. Callers (session-store, project-store)
 * supply the migrations they want. The first three migrations are provided
 * out of the box (session_messages, project_db, project_changes) to match
 * design.md §1.2.
 */

import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import Database from 'better-sqlite3';
import type { Database as DatabaseT, Statement } from 'better-sqlite3';

/** A single migration: a version number and the SQL to apply. */
export interface Migration {
  /** Monotonic version, starting at 1. */
  readonly version: number;
  /** Human-readable name (logged on apply). */
  readonly name: string;
  /** SQL statements to run inside a transaction. */
  readonly statements: ReadonlyArray<string>;
}

/** The migrations that ship with this module. */
export const DEFAULT_MIGRATIONS: ReadonlyArray<Migration> = [
  {
    version: 1,
    name: 'session_messages',
    statements: [
      `CREATE TABLE IF NOT EXISTS session_messages (
         session_id TEXT NOT NULL,
         ts INTEGER NOT NULL,
         role TEXT NOT NULL,
         content TEXT NOT NULL,
         metadata TEXT,
         token_count INTEGER,
         PRIMARY KEY (session_id, ts)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_session_messages_ts
         ON session_messages(session_id, ts DESC)`,
    ],
  },
  {
    version: 2,
    name: 'project_db',
    statements: [
      `CREATE TABLE IF NOT EXISTS project_db (
         project_id TEXT PRIMARY KEY,
         cwd TEXT NOT NULL,
         title TEXT,
         description TEXT,
         updated_at INTEGER NOT NULL
       )`,
    ],
  },
  {
    version: 3,
    name: 'project_changes',
    statements: [
      `CREATE TABLE IF NOT EXISTS project_changes (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         project_id TEXT NOT NULL,
         ts INTEGER NOT NULL,
         description TEXT NOT NULL,
         compressed INTEGER DEFAULT 0,
         FOREIGN KEY (project_id) REFERENCES project_db(project_id)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_project_changes_pid
         ON project_changes(project_id, ts DESC)`,
    ],
  },
  {
    // v4 (T-053): write-ahead log for project-memory compression.
    // Each row records a single in-flight (or completed) compression pass
    // for a project. Resume-on-crash logic (T-054) reads `state` to find
    // the most recent unfinished pass and replays it. After the LLM has
    // returned and the new project 说明 is persisted, the row is moved to
    // `state='committed'`. The `attempt` counter is bumped on retry.
    version: 4,
    name: 'compression_wal',
    statements: [
      `CREATE TABLE IF NOT EXISTS compression_wal (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         project_id TEXT NOT NULL,
         started_at INTEGER NOT NULL,
         finished_at INTEGER,
         attempt INTEGER NOT NULL DEFAULT 1,
         state TEXT NOT NULL CHECK(state IN ('pending','llm_done','committed','failed')),
         before_changes_json TEXT NOT NULL,
         new_description TEXT,
         new_description_tokens INTEGER,
         error TEXT,
         FOREIGN KEY (project_id) REFERENCES project_db(project_id)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_compression_wal_pid_state
         ON compression_wal(project_id, state)`,
    ],
  },
  {
    // v5 (R-MEM-1): vector index for semantic search.
    // One row per indexed memory entry. The (scope, entry_id)
    // pair is the natural key — entries from different scopes
    // (global / project / session / skill) can have the same
    // entry_id without collision. The embedding is a raw
    // Float32Array BLOB (4 * dim bytes). `model_id` lets us
    // detect when the index was built with a different
    // embedding model and trigger a rebuild.
    version: 5,
    name: 'vec_index',
    statements: [
      `CREATE TABLE IF NOT EXISTS vec_index (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         scope TEXT NOT NULL,
         entry_id TEXT NOT NULL,
         content_text TEXT NOT NULL,
         embedding BLOB NOT NULL,
         model_id TEXT NOT NULL,
         ts INTEGER NOT NULL,
         UNIQUE(scope, entry_id)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_vec_index_ts ON vec_index(ts DESC)`,
      `CREATE INDEX IF NOT EXISTS idx_vec_index_scope ON vec_index(scope)`,
    ],
  },
  {
    // v6 (R-MEM-2): evolution columns on project_changes. The
    // four new columns drive the consolidation + forgetting
    // machinery:
    //  - expires_at: explicit TTL (NULL = no expiry)
    //  - consolidated_into: when set, this row was merged into
    //    another row (id of the target). Reads skip these rows.
    //  - value_tag: 'normal' or 'low'. Low-tagged rows are
    //    candidates for aggressive forgetting.
    //  - last_accessed_at: updated on every read. Used by
    //    "forget by inactivity".
    //  - access_count: incremented on every read. The LRU +
    //    frequency combo drives the forget policy.
    //  - deleted_at: NULL while live. Set when soft-deleted by
    //    forget. Reads skip these rows. A separate vacuum pass
    //    hard-deletes rows whose deleted_at is older than the
    //    retention window.
    //
    // Existing rows get safe defaults: no expiry, not
    // consolidated, normal value, accessed now with count 0.
    version: 6,
    name: 'project_changes_evolution',
    statements: [
      `ALTER TABLE project_changes ADD COLUMN expires_at INTEGER`,
      `ALTER TABLE project_changes ADD COLUMN consolidated_into INTEGER`,
      `ALTER TABLE project_changes ADD COLUMN value_tag TEXT NOT NULL DEFAULT 'normal'`,
      `ALTER TABLE project_changes ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0`,
      `ALTER TABLE project_changes ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0`,
      `ALTER TABLE project_changes ADD COLUMN deleted_at INTEGER`,
      // Forgetting: scan rows by expiry / last access
      `CREATE INDEX IF NOT EXISTS idx_project_changes_expires
         ON project_changes(expires_at) WHERE expires_at IS NOT NULL`,
      `CREATE INDEX IF NOT EXISTS idx_project_changes_accessed
         ON project_changes(last_accessed_at)`,
      // Consolidation: filter out already-consolidated + soft-deleted rows
      `CREATE INDEX IF NOT EXISTS idx_project_changes_live
         ON project_changes(project_id, ts) WHERE consolidated_into IS NULL AND deleted_at IS NULL`,
    ],
  },
  {
    // v7 (R-MEM-3): team-shared memory. When a subagent
    // shares a fact to the project, the change is tagged with
    // the subagent's session_id. The main agent can read all
    // rows for the project (including team rows) and choose
    // which to promote to permanent project memory.
    //
    // The team_session_id column is NULL for "normal" project
    // changes (default) and non-NULL for shared team entries.
    // Reads can filter by IS NULL / IS NOT NULL.
    version: 7,
    name: 'project_changes_team',
    statements: [
      `ALTER TABLE project_changes ADD COLUMN team_session_id TEXT`,
      `CREATE INDEX IF NOT EXISTS idx_project_changes_team
         ON project_changes(project_id, team_session_id) WHERE team_session_id IS NOT NULL`,
    ],
  },
  {
    // v8 (R-MEM-4): skill memory. The `skills` table stores
    // experience-based skill records: a stable name + signature
    // (the "how" — e.g. "git rebase --onto" or "use -L for
    // follow symlinks"), a description (the "what"), and
    // success/failure counters. The (scope, name) UNIQUE
    // constraint is the natural key: a skill called
    // "git_rebase" can live once per scope (global / project)
    // without collision.
    //
    // The signature is the canonical invocation shape — a
    // short string the agent matches against. ts is the
    // creation time (immutable). success_count +
    // last_success_at / last_failure_at are the experience
    // counters that drive the "is this skill reliable" check.
    version: 8,
    name: 'skills',
    statements: [
      `CREATE TABLE IF NOT EXISTS skills (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         scope TEXT NOT NULL,
         name TEXT NOT NULL,
         signature TEXT NOT NULL,
         description TEXT NOT NULL,
         tags TEXT NOT NULL DEFAULT '[]',
         source TEXT NOT NULL DEFAULT 'system',
         success_count INTEGER NOT NULL DEFAULT 0,
         last_success_at INTEGER,
         last_failure_at INTEGER,
         ts INTEGER NOT NULL,
         UNIQUE(scope, name)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_skills_scope ON skills(scope, ts DESC)`,
      `CREATE INDEX IF NOT EXISTS idx_skills_success
         ON skills(scope, success_count DESC, last_success_at DESC)`,
    ],
  },
  {
    // v9 (R-MEM-5.1, F4 Multimodal): extend `vec_index` to
    // support non-text content. Two new columns:
    //  - media_type: 'text' (default, all pre-v9 rows) or
    //    'image'. The check constraint blocks other values
    //    so future media types can be added explicitly.
    //  - media_ref: for image rows, this is the file path or
    //    SHA-256 hash of the file content. For text rows it's
    //    NULL. Callers can read the original image back by
    //    looking up the ref.
    //
    // Existing text rows are safe: media_type defaults to
    // 'text' and media_ref stays NULL.
    version: 9,
    name: 'vec_index_multimodal',
    statements: [
      `ALTER TABLE vec_index ADD COLUMN media_type TEXT NOT NULL DEFAULT 'text'`,
      `ALTER TABLE vec_index ADD COLUMN media_ref TEXT`,
      `CREATE INDEX IF NOT EXISTS idx_vec_index_media_type
         ON vec_index(media_type, scope)`,
    ],
  },
  {
    // v10 (R-MEM-5.2, F7 Trust): provenance chain. Each
    // write to a memory table also writes a row here, with
    // (scope, ts) as the natural key. The chain works as a
    // linked list: prev_content_hash points to the previous
    // row's content_hash in the same scope. verifyChain()
    // walks the chain in ts order and confirms every link.
    //
    // The content_hash is SHA-256 of (entry_id + '|' +
    // content + '|' + ts). A new scope always starts with
    // prev_content_hash = NULL. The first link in a chain
    // is special — its prev is NULL but its content_hash
    // must still match.
    //
    // Existing rows are safe: the table starts empty, so
    // pre-v10 entries aren't in the chain until they're
    // rewritten.
    version: 10,
    name: 'provenance_chain',
    statements: [
      `CREATE TABLE IF NOT EXISTS provenance_chain (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         scope TEXT NOT NULL,
         entry_id TEXT NOT NULL,
         content_hash TEXT NOT NULL,
         prev_content_hash TEXT,
         ts INTEGER NOT NULL,
         UNIQUE(scope, ts)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_provenance_chain_scope
         ON provenance_chain(scope, ts)`,
      `CREATE INDEX IF NOT EXISTS idx_provenance_chain_entry
         ON provenance_chain(scope, entry_id)`,
    ],
  },
  {
    // v11 (R-MEM-5.3, F8 Cognition): cognitive memory types.
    // The `vec_index.memory_type` column classifies every
    // entry along Tulving's classic split:
    //  - 'episodic': a time-stamped event (project changes,
    //    session messages). Default for legacy rows.
    //  - 'semantic': a stable fact or rule (image descriptions
    //    with their captions, global rules). General
    //    knowledge.
    //  - 'procedural': a how-to / skill. The skills table
    //    rows map to this.
    //
    // Existing rows default to 'episodic' — the safest
    // fall-back since most legacy entries are project
    // changes. Callers can re-classify on write.
    version: 11,
    name: 'vec_index_memory_type',
    statements: [
      `ALTER TABLE vec_index ADD COLUMN memory_type TEXT NOT NULL DEFAULT 'episodic'`,
      `CREATE INDEX IF NOT EXISTS idx_vec_index_memory_type
         ON vec_index(memory_type, scope)`,
    ],
  },
  {
    // v12 (R-MEM-6.2, F7+ Ed25519): cryptographic tamper
    // resistance for the provenance chain. Each chain row
    // gets a `signature` (base64) and `signer_pubkey` (hex)
    // over the canonical signing input
    //   prev_content_hash | entry_id | content | ts
    //
    // Existing rows have NULL signatures — they were
    // recorded under the v10/v11 best-effort scheme and
    // weren't signed. New rows are signed automatically by
    // `recordProvenanceSigned`; legacy rows can be re-signed
    // in a one-shot migration pass (out of scope for this
    // round — legacy rows remain "untrusted" but still chain-
    // verified).
    version: 12,
    name: 'provenance_chain_signatures',
    statements: [
      `ALTER TABLE provenance_chain ADD COLUMN signature TEXT`,
      `ALTER TABLE provenance_chain ADD COLUMN signer_pubkey TEXT`,
    ],
  },
  {
    // v13 (R-MEM-6.4, F3 RL-tuned memory): lightweight
    // retrieval-feedback loop. The agent calls
    // `memory/recordRetrievalOutcome(entryId, used)` after a
    // memory hit; the row accumulates `used_count` /
    // `not_used_count` counters that bias future
    // `memory/find` results. The bias is a small additive
    // term in the score (linear "log-count" style), not a
    // trained model — keeping it lightweight is the point.
    //
    // Index: by (scope, entry_id) so per-entry counters are
    // a single-row lookup at find-time.
    version: 13,
    name: 'retrieval_feedback',
    statements: [
      `CREATE TABLE IF NOT EXISTS retrieval_feedback (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         scope TEXT NOT NULL,
         entry_id TEXT NOT NULL,
         used_count INTEGER NOT NULL DEFAULT 0,
         not_used_count INTEGER NOT NULL DEFAULT 0,
         last_feedback_at INTEGER,
         UNIQUE(scope, entry_id)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_retrieval_feedback_scope
         ON retrieval_feedback(scope)`,
    ],
  },
];

/** Read the current schema version (0 if none applied). */
export function readSchemaVersion(db: DatabaseT): number {
  const row = db.pragma('user_version', { simple: true }) as number;
  return typeof row === 'number' ? row : 0;
}

/** Apply a single migration inside a transaction. */
function applyMigration(db: DatabaseT, migration: Migration): void {
  const apply = db.transaction((): void => {
    for (const sql of migration.statements) {
      db.exec(sql);
    }
    db.pragma(`user_version = ${migration.version}`);
  });
  apply();
}

/**
 * Open (or create) the SQLite database at `dbPath` and apply all pending
 * migrations. Returns the open Database handle. The caller is responsible
 * for closing it (or wrap the call in `withDatabase`).
 *
 * If a migration error occurs, the partially-opened handle is closed before
 * the error is rethrown — so the caller never has to clean up a leaked
 * handle on the failure path.
 */
export function openAndMigrate(
  dbPath: string,
  migrations: ReadonlyArray<Migration> = DEFAULT_MIGRATIONS,
): DatabaseT {
  mkdirSync(dirname(dbPath), { recursive: true });
  const db = new Database(dbPath);
  let succeeded = false;
  try {
    db.pragma('journal_mode = WAL');
    db.pragma('synchronous = NORMAL');
    db.pragma('foreign_keys = ON');

    const current = readSchemaVersion(db);
    const sorted = [...migrations].sort((a, b) => a.version - b.version);
    let next = current;
    for (const m of sorted) {
      if (m.version <= current) continue;
      if (m.version !== next + 1) {
        throw new Error(
          `Migration gap detected: applied up to ${current}, ` +
            `next expected ${next + 1}, got ${m.version}`,
        );
      }
      applyMigration(db, m);
      next = m.version;
    }
    succeeded = true;
    return db;
  } finally {
    if (!succeeded) {
      try {
        db.close();
      } catch {
        // best-effort cleanup
      }
    }
  }
}

/**
 * Run a function with an open database and guarantee it is closed.
 * Useful for one-shot scripts and tests.
 */
export function withDatabase<T>(
  dbPath: string,
  fn: (db: DatabaseT) => T,
  migrations: ReadonlyArray<Migration> = DEFAULT_MIGRATIONS,
): T {
  const db = openAndMigrate(dbPath, migrations);
  try {
    return fn(db);
  } finally {
    db.close();
  }
}

/** Internal: prepare and run a statement, binding positional parameters. */
export function runStatement(
  db: DatabaseT,
  sql: string,
  params: ReadonlyArray<unknown> = [],
): { changes: number; lastInsertRowid: number | bigint } {
  const stmt: Statement = db.prepare(sql);
  const info = stmt.run(...params);
  return {
    changes: typeof info.changes === 'number' ? info.changes : Number(info.changes),
    lastInsertRowid: info.lastInsertRowid,
  };
}

/** Internal: query rows and map them through a transform. */
export function queryAll<T>(
  db: DatabaseT,
  sql: string,
  params: ReadonlyArray<unknown> = [],
  map: (row: Record<string, unknown>) => T,
): T[] {
  const stmt = db.prepare(sql);
  const rows = stmt.all(...params) as Record<string, unknown>[];
  return rows.map(map);
}
