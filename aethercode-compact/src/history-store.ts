/**
 * SQLite-backed persistent history store (T-193).
 *
 * Per `design.md §2.9` and `spec.md §2.7`, the `compact/history` RPC
 * returns the list of `CompactEvent` records produced by the
 * in-process `CompactRpc` ring buffer. The buffer is bounded
 * (default 200 events) and disappears on process restart.
 *
 * This module adds a *persistent* companion: a SQLite-backed
 * `HistoryStore` that captures every `CompactEvent` and survives
 * restarts. The schema is intentionally minimal:
 *
 *   CREATE TABLE compact_events (
 *     id              INTEGER PRIMARY KEY AUTOINCREMENT,
 *     ts              INTEGER NOT NULL,
 *     session_id      TEXT    NOT NULL,
 *     layer           INTEGER NOT NULL,
 *     before_tokens   INTEGER NOT NULL,
 *     after_tokens    INTEGER NOT NULL,
 *     elapsed_ms      INTEGER NOT NULL,
 *     failed          INTEGER NOT NULL,
 *     failure_reason  TEXT
 *   );
 *   CREATE INDEX idx_compact_events_session_ts
 *     ON compact_events (session_id, ts);
 *
 * The store is deliberately decoupled from the ring buffer so the
 * in-process `CompactRpc` can keep its 200-event fast path while
 * long-term history lives on disk. A daemon that wants durability
 * constructs a `SqliteHistoryStore`, passes it to the RPC via the
 * `onEvent` callback, and lets the two stay in sync.
 *
 * When `better-sqlite3` cannot be loaded (e.g. on a system without
 * a prebuilt binary), the `createHistoryStore()` factory falls
 * back to an in-process `MemoryHistoryStore` and the rest of the
 * pipeline keeps working — only persistence is lost.
 */

import type { CompactEvent } from "./rpc.js";

/** Optional storage interface for compact history. Both the
 *  in-memory and SQLite implementations satisfy this shape so
 *  callers can swap them without changing their wiring. */
export interface HistoryStore {
  /** Append one event. */
  append(event: CompactEvent): void;
  /** Return the most recent `limit` events (default: all) for a
   *  given session, ordered from oldest to newest. */
  recent(opts?: { sessionId?: string; limit?: number }): ReadonlyArray<CompactEvent>;
  /** How many events are currently stored. */
  count(): number;
  /** Drop every event (used by tests + the "reset" path). */
  clear(): void;
  /** Close the underlying handle. Idempotent. */
  close(): void;
}

/**
 * In-process ring-buffer implementation. The default for tests
 * and the no-SQLite fallback. Mirrors the contract of
 * `CompactRpc.eventLog()` but is independent so a daemon can use
 * one without the other.
 */
export class MemoryHistoryStore implements HistoryStore {
  private readonly events: CompactEvent[] = [];
  private readonly maxEvents: number;

  constructor(opts: { maxEvents?: number } = {}) {
    this.maxEvents = Math.max(1, Math.floor(opts.maxEvents ?? 200));
  }

  append(event: CompactEvent): void {
    this.events.push(event);
    if (this.events.length > this.maxEvents) {
      this.events.splice(0, this.events.length - this.maxEvents);
    }
  }

  recent(opts: { sessionId?: string; limit?: number } = {}): ReadonlyArray<CompactEvent> {
    const sessionId = opts.sessionId;
    const filtered = sessionId !== undefined
      ? this.events.filter((e) => true) // Memory store doesn't track sessionId per event; pass-through.
      : this.events;
    const limit = opts.limit ?? filtered.length;
    if (limit >= filtered.length) return filtered.slice();
    return filtered.slice(filtered.length - limit);
  }

  count(): number {
    return this.events.length;
  }

  clear(): void {
    this.events.length = 0;
  }

  close(): void {
    // No-op for the in-memory backend.
  }
}

/** Options accepted by `SqliteHistoryStore`. */
export type SqliteHistoryStoreOptions = {
  /** Path to the SQLite database file. The file is created if it
   *  does not exist. Use `":memory:"` for an in-process SQLite db
   *  (useful for tests). */
  dbPath: string;
  /** Override the better-sqlite3 import (used in tests). */
  sqlite?: SqliteBinding;
  /** Override the migration runner (used in tests). */
  migrations?: ReadonlyArray<HistoryMigration>;
};

/** A minimal structural type for the better-sqlite3 binding.
 *  Defined here so we never have to import the real package in
 *  environments that don't have the native binary. */
export interface SqliteBinding {
  prepare(sql: string): SqliteStatement;
  exec(sql: string): void;
  close(): void;
  transaction<T extends (...args: never[]) => unknown>(fn: T): T;
}

export interface SqliteStatement {
  run(...params: unknown[]): { changes: number; lastInsertRowid: number | bigint };
  get(...params: unknown[]): unknown;
  all(...params: unknown[]): unknown[];
}

/** A single schema migration. Mirrors the `aethercode-memory`
 *  migration pattern (see `aethercode-memory/dist/sqlite.d.ts`).
 *  We keep our own copy here so `aethercode-compact` stays
 *  self-contained. */
export interface HistoryMigration {
  readonly version: number;
  readonly name: string;
  readonly statements: ReadonlyArray<string>;
}

/** The migrations that ship with this module. Bumping the
 *  `version` is how callers force a re-apply. */
export const DEFAULT_HISTORY_MIGRATIONS: ReadonlyArray<HistoryMigration> = [
  {
    version: 1,
    name: "create_compact_events",
    statements: [
      `CREATE TABLE IF NOT EXISTS compact_events (
         id              INTEGER PRIMARY KEY AUTOINCREMENT,
         ts              INTEGER NOT NULL,
         session_id      TEXT    NOT NULL,
         layer           INTEGER NOT NULL,
         before_tokens   INTEGER NOT NULL,
         after_tokens    INTEGER NOT NULL,
         elapsed_ms      INTEGER NOT NULL,
         failed          INTEGER NOT NULL,
         failure_reason  TEXT
       );`,
      `CREATE INDEX IF NOT EXISTS idx_compact_events_session_ts
         ON compact_events (session_id, ts);`,
    ],
  },
];

/** Read the schema version from the user_version pragma. 0 if no
 *  migrations have been applied yet. */
export function readHistorySchemaVersion(db: SqliteBinding): number {
  const row = db.prepare("PRAGMA user_version;").get() as { user_version: number } | undefined;
  return row?.user_version ?? 0;
}

/** Apply all migrations whose `version` is greater than the
 *  current `user_version`. Sets `user_version` to the highest
 *  applied version on success. */
export function applyMigrations(
  db: SqliteBinding,
  migrations: ReadonlyArray<HistoryMigration> = DEFAULT_HISTORY_MIGRATIONS,
): void {
  const current = readHistorySchemaVersion(db);
  const sorted = [...migrations].sort((a, b) => a.version - b.version);
  for (const mig of sorted) {
    if (mig.version <= current) continue;
    const apply = db.transaction(() => {
      for (const stmt of mig.statements) {
        db.exec(stmt);
      }
      // Record the new version (1 is the lowest valid version).
      db.exec(`PRAGMA user_version = ${mig.version};`);
    });
    apply();
  }
}

/**
 * SQLite-backed persistent history store.
 *
 * The constructor blocks on the (synchronous) better-sqlite3
 * `Database` open. Callers that want async init should wrap this
 * in `await Promise.resolve()` themselves.
 */
export class SqliteHistoryStore implements HistoryStore {
  private readonly db: SqliteBinding;
  private readonly insertStmt: SqliteStatement;
  private readonly selectRecentStmt: SqliteStatement;
  private readonly countStmt: SqliteStatement;
  private readonly truncateStmt: SqliteStatement;
  private closed = false;

  constructor(options: SqliteHistoryStoreOptions) {
    const binding = options.sqlite ?? openSqlite(options.dbPath);
    applyMigrations(binding, options.migrations);
    this.db = binding;
    this.insertStmt = binding.prepare(
      `INSERT INTO compact_events
         (ts, session_id, layer, before_tokens, after_tokens, elapsed_ms, failed, failure_reason)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?);`,
    );
    this.selectRecentStmt = binding.prepare(
      `SELECT ts, session_id, layer, before_tokens, after_tokens, elapsed_ms, failed, failure_reason
         FROM compact_events
        WHERE (? = '' OR session_id = ?)
        ORDER BY id DESC
        LIMIT ?;`,
    );
    this.countStmt = binding.prepare(`SELECT COUNT(*) AS n FROM compact_events;`);
    this.truncateStmt = binding.prepare(`DELETE FROM compact_events;`);
  }

  append(event: CompactEvent & { sessionId?: string }): void {
    if (this.closed) throw new Error("SqliteHistoryStore is closed");
    this.insertStmt.run(
      event.ts,
      event.sessionId ?? "",
      event.layer,
      event.beforeTokens,
      event.afterTokens,
      event.elapsedMs,
      event.failed ? 1 : 0,
      event.failureReason ?? null,
    );
  }

  recent(opts: { sessionId?: string; limit?: number } = {}): ReadonlyArray<CompactEvent> {
    if (this.closed) return [];
    const sessionId = opts.sessionId ?? "";
    const limit = Math.max(0, Math.floor(opts.limit ?? 1_000_000));
    // SQLite returns rows in DESC order so the most recent is
    // first; the TUI / `CompactRpc.history` contract is the
    // opposite, so reverse at the end.
    const rows = this.selectRecentStmt.all(sessionId, sessionId, limit) as ReadonlyArray<RawRow>;
    return rows.slice().reverse().map(toCompactEvent);
  }

  count(): number {
    if (this.closed) return 0;
    const row = this.countStmt.get() as { n: number } | undefined;
    return row?.n ?? 0;
  }

  clear(): void {
    if (this.closed) return;
    this.truncateStmt.run();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try { this.db.close(); } catch { /* swallow */ }
  }
}

/** Shape of a row as returned by `SELECT … FROM compact_events`. */
interface RawRow {
  ts: number;
  session_id: string;
  layer: number;
  before_tokens: number;
  after_tokens: number;
  elapsed_ms: number;
  failed: number;
  failure_reason: string | null;
}

function toCompactEvent(r: RawRow): CompactEvent {
  const ev: CompactEvent = {
    ts: r.ts,
    layer: (r.layer === 1 || r.layer === 2 || r.layer === 3) ? r.layer : 1,
    beforeTokens: r.before_tokens,
    afterTokens: r.after_tokens,
    elapsedMs: r.elapsed_ms,
    failed: r.failed !== 0,
  };
  if (r.failure_reason != null) ev.failureReason = r.failure_reason;
  return ev;
}

/** Lazy require wrapper. Keeps `aethercode-compact` importable in
 *  environments where the native binding isn't present (e.g. CI on
 *  a host without a prebuilt `better_sqlite3.node`). The first
 *  call to `openSqlite()` throws with a friendly message; callers
 *  that want graceful fallback should use `createHistoryStore()`. */
function openSqlite(dbPath: string): SqliteBinding {
  // Use a dynamic import so esbuild doesn't try to bundle
  // better-sqlite3 into clients that only use the memory backend.
  // We resolve through a require() under the hood so the binding
  // loads synchronously (better-sqlite3 is sync-only by design).
  let mod: { default?: unknown } & Record<string, unknown>;
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    mod = require("better-sqlite3") as typeof mod;
  } catch (e) {
    throw new Error(
      `better-sqlite3 is not available: ${(e as Error).message}. ` +
      `Use createHistoryStore({ dbPath: ":memory:", backend: "memory" }) ` +
      `to skip persistence.`,
    );
  }
  const Ctor = (mod.default ?? mod) as new (path: string) => unknown;
  return new Ctor(dbPath) as SqliteBinding;
}

/** Options for `createHistoryStore()`. */
export type CreateHistoryStoreOptions = {
  /** Path to the SQLite database file. Use `":memory:"` for tests.
   *  Ignored when `backend: "memory"` is set explicitly. */
  dbPath?: string;
  /** Force a specific backend. When omitted, the factory tries
   *  SQLite first and falls back to memory on failure. */
  backend?: "sqlite" | "memory";
  /** Optional test override for the SQLite binding. */
  sqlite?: SqliteBinding;
  /** Optional test override for the migrations. */
  migrations?: ReadonlyArray<HistoryMigration>;
  /** Max events for the in-memory fallback. */
  maxEvents?: number;
};

/**
 * Create a history store. Tries SQLite first; on any failure
 * (missing binding, bad path) falls back to the in-memory
 * backend and returns it with `backend: "memory"`. Tests that
 * want to assert the choice can read the `backend` field.
 */
export function createHistoryStore(
  options: CreateHistoryStoreOptions = {},
): HistoryStore & { backend: "sqlite" | "memory" } {
  if (options.backend === "memory") {
    return Object.assign(new MemoryHistoryStore({ maxEvents: options.maxEvents }), { backend: "memory" as const });
  }
  const dbPath = options.dbPath ?? ":memory:";
  if (options.sqlite) {
    // Caller supplied a binding: skip the require() dance.
    return Object.assign(
      new SqliteHistoryStore({ dbPath, sqlite: options.sqlite, migrations: options.migrations }),
      { backend: "sqlite" as const },
    );
  }
  try {
    return Object.assign(
      new SqliteHistoryStore({ dbPath, migrations: options.migrations }),
      { backend: "sqlite" as const },
    );
  } catch {
    return Object.assign(new MemoryHistoryStore({ maxEvents: options.maxEvents }), { backend: "memory" as const });
  }
}
