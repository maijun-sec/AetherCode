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
/**
 * In-process ring-buffer implementation. The default for tests
 * and the no-SQLite fallback. Mirrors the contract of
 * `CompactRpc.eventLog()` but is independent so a daemon can use
 * one without the other.
 */
export class MemoryHistoryStore {
    events = [];
    maxEvents;
    constructor(opts = {}) {
        this.maxEvents = Math.max(1, Math.floor(opts.maxEvents ?? 200));
    }
    append(event) {
        this.events.push(event);
        if (this.events.length > this.maxEvents) {
            this.events.splice(0, this.events.length - this.maxEvents);
        }
    }
    recent(opts = {}) {
        const sessionId = opts.sessionId;
        const filtered = sessionId !== undefined
            ? this.events.filter((e) => true) // Memory store doesn't track sessionId per event; pass-through.
            : this.events;
        const limit = opts.limit ?? filtered.length;
        if (limit >= filtered.length)
            return filtered.slice();
        return filtered.slice(filtered.length - limit);
    }
    count() {
        return this.events.length;
    }
    clear() {
        this.events.length = 0;
    }
    close() {
        // No-op for the in-memory backend.
    }
}
/** The migrations that ship with this module. Bumping the
 *  `version` is how callers force a re-apply. */
export const DEFAULT_HISTORY_MIGRATIONS = [
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
export function readHistorySchemaVersion(db) {
    const row = db.prepare("PRAGMA user_version;").get();
    return row?.user_version ?? 0;
}
/** Apply all migrations whose `version` is greater than the
 *  current `user_version`. Sets `user_version` to the highest
 *  applied version on success. */
export function applyMigrations(db, migrations = DEFAULT_HISTORY_MIGRATIONS) {
    const current = readHistorySchemaVersion(db);
    const sorted = [...migrations].sort((a, b) => a.version - b.version);
    for (const mig of sorted) {
        if (mig.version <= current)
            continue;
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
export class SqliteHistoryStore {
    db;
    insertStmt;
    selectRecentStmt;
    countStmt;
    truncateStmt;
    closed = false;
    constructor(options) {
        const binding = options.sqlite ?? openSqlite(options.dbPath);
        applyMigrations(binding, options.migrations);
        this.db = binding;
        this.insertStmt = binding.prepare(`INSERT INTO compact_events
         (ts, session_id, layer, before_tokens, after_tokens, elapsed_ms, failed, failure_reason)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?);`);
        this.selectRecentStmt = binding.prepare(`SELECT ts, session_id, layer, before_tokens, after_tokens, elapsed_ms, failed, failure_reason
         FROM compact_events
        WHERE (? = '' OR session_id = ?)
        ORDER BY id DESC
        LIMIT ?;`);
        this.countStmt = binding.prepare(`SELECT COUNT(*) AS n FROM compact_events;`);
        this.truncateStmt = binding.prepare(`DELETE FROM compact_events;`);
    }
    append(event) {
        if (this.closed)
            throw new Error("SqliteHistoryStore is closed");
        this.insertStmt.run(event.ts, event.sessionId ?? "", event.layer, event.beforeTokens, event.afterTokens, event.elapsedMs, event.failed ? 1 : 0, event.failureReason ?? null);
    }
    recent(opts = {}) {
        if (this.closed)
            return [];
        const sessionId = opts.sessionId ?? "";
        const limit = Math.max(0, Math.floor(opts.limit ?? 1_000_000));
        // SQLite returns rows in DESC order so the most recent is
        // first; the TUI / `CompactRpc.history` contract is the
        // opposite, so reverse at the end.
        const rows = this.selectRecentStmt.all(sessionId, sessionId, limit);
        return rows.slice().reverse().map(toCompactEvent);
    }
    count() {
        if (this.closed)
            return 0;
        const row = this.countStmt.get();
        return row?.n ?? 0;
    }
    clear() {
        if (this.closed)
            return;
        this.truncateStmt.run();
    }
    close() {
        if (this.closed)
            return;
        this.closed = true;
        try {
            this.db.close();
        }
        catch { /* swallow */ }
    }
}
function toCompactEvent(r) {
    const ev = {
        ts: r.ts,
        layer: (r.layer === 1 || r.layer === 2 || r.layer === 3) ? r.layer : 1,
        beforeTokens: r.before_tokens,
        afterTokens: r.after_tokens,
        elapsedMs: r.elapsed_ms,
        failed: r.failed !== 0,
    };
    if (r.failure_reason != null)
        ev.failureReason = r.failure_reason;
    return ev;
}
/** Lazy require wrapper. Keeps `aethercode-compact` importable in
 *  environments where the native binding isn't present (e.g. CI on
 *  a host without a prebuilt `better_sqlite3.node`). The first
 *  call to `openSqlite()` throws with a friendly message; callers
 *  that want graceful fallback should use `createHistoryStore()`. */
function openSqlite(dbPath) {
    // Use a dynamic import so esbuild doesn't try to bundle
    // better-sqlite3 into clients that only use the memory backend.
    // We resolve through a require() under the hood so the binding
    // loads synchronously (better-sqlite3 is sync-only by design).
    let mod;
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        mod = require("better-sqlite3");
    }
    catch (e) {
        throw new Error(`better-sqlite3 is not available: ${e.message}. ` +
            `Use createHistoryStore({ dbPath: ":memory:", backend: "memory" }) ` +
            `to skip persistence.`);
    }
    const Ctor = (mod.default ?? mod);
    return new Ctor(dbPath);
}
/**
 * Create a history store. Tries SQLite first; on any failure
 * (missing binding, bad path) falls back to the in-memory
 * backend and returns it with `backend: "memory"`. Tests that
 * want to assert the choice can read the `backend` field.
 */
export function createHistoryStore(options = {}) {
    if (options.backend === "memory") {
        return Object.assign(new MemoryHistoryStore({ maxEvents: options.maxEvents }), { backend: "memory" });
    }
    const dbPath = options.dbPath ?? ":memory:";
    if (options.sqlite) {
        // Caller supplied a binding: skip the require() dance.
        return Object.assign(new SqliteHistoryStore({ dbPath, sqlite: options.sqlite, migrations: options.migrations }), { backend: "sqlite" });
    }
    try {
        return Object.assign(new SqliteHistoryStore({ dbPath, migrations: options.migrations }), { backend: "sqlite" });
    }
    catch {
        return Object.assign(new MemoryHistoryStore({ maxEvents: options.maxEvents }), { backend: "memory" });
    }
}
