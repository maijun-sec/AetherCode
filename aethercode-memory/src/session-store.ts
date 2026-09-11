/**
 * Session storage — T-018 (write) and T-019 (read by ts range).
 *
 * Backed by the `session_messages` table created by migration v1 (see sqlite.ts
 * DEFAULT_MIGRATIONS). The store does not own the connection; callers supply
 * a `better-sqlite3` `Database` handle. The store is intentionally thin so
 * the higher-level `MemoryStore` can compose it with the LRU cache, the
 * jsonl writer, and the fold routine.
 *
 * Concurrency: better-sqlite3 is synchronous and serialises operations inside
 * the JS event loop, so this module does not need to coordinate locks. The
 * `INSERT OR REPLACE` is the right call for the `(session_id, ts)` PK — a
 * re-emit of the same message overwrites it instead of erroring.
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement, queryAll } from './sqlite.js';
import type {
  MessageRole,
  SessionMessageInput,
  SessionMessageQuery,
  SessionMessageRow,
} from './types.js';
import { isMessageRole } from './types.js';

/** SQL: write one row, replacing on PK conflict. */
const SQL_INSERT = `INSERT OR REPLACE INTO session_messages
  (session_id, ts, role, content, metadata, token_count)
  VALUES (?, ?, ?, ?, ?, ?)`;

/** SQL: read rows in a ts range. */
const SQL_SELECT_RANGE = `SELECT session_id, ts, role, content, metadata, token_count
  FROM session_messages
  WHERE session_id = ?
    AND (? IS NULL OR ts >= ?)
    AND (? IS NULL OR ts <= ?)
  ORDER BY ts ASC`;

/** SQL: read rows in a ts range, descending. */
const SQL_SELECT_RANGE_DESC = `SELECT session_id, ts, role, content, metadata, token_count
  FROM session_messages
  WHERE session_id = ?
    AND (? IS NULL OR ts >= ?)
    AND (? IS NULL OR ts <= ?)
  ORDER BY ts DESC`;

/** SQL: count rows. */
const SQL_COUNT = `SELECT COUNT(*) AS n FROM session_messages WHERE session_id = ?`;

/** SQL: delete all rows for a session. */
const SQL_DELETE_SESSION = `DELETE FROM session_messages WHERE session_id = ?`;

/** SQL: delete a single row by PK. */
const SQL_DELETE_ONE = `DELETE FROM session_messages WHERE session_id = ? AND ts = ?`;

/** T-018 — append one message. Returns the row that was written. */
export function writeSessionMessage(
  db: DatabaseT,
  sessionId: string,
  msg: SessionMessageInput,
): SessionMessageRow {
  if (!sessionId) throw new Error('writeSessionMessage: sessionId must be non-empty');
  if (!Number.isFinite(msg.ts)) throw new Error('writeSessionMessage: ts must be a finite number');
  if (!isMessageRole(msg.role)) throw new Error(`writeSessionMessage: invalid role "${msg.role}"`);

  const metadataJson = msg.metadata === undefined ? null : JSON.stringify(msg.metadata);
  const tokenCount = msg.tokenCount ?? null;

  runStatement(db, SQL_INSERT, [
    sessionId,
    msg.ts,
    msg.role,
    msg.content,
    metadataJson,
    tokenCount,
  ]);

  return {
    session_id: sessionId,
    ts: msg.ts,
    role: msg.role,
    content: msg.content,
    metadata: metadataJson,
    token_count: tokenCount,
  };
}

/** Append many messages inside a single transaction (faster, atomic). */
export function writeSessionMessages(
  db: DatabaseT,
  sessionId: string,
  msgs: ReadonlyArray<SessionMessageInput>,
): number {
  if (!sessionId) throw new Error('writeSessionMessages: sessionId must be non-empty');
  const apply = db.transaction((rows: ReadonlyArray<SessionMessageInput>): number => {
    let n = 0;
    for (const m of rows) {
      if (!Number.isFinite(m.ts)) throw new Error('writeSessionMessages: ts must be finite');
      if (!isMessageRole(m.role)) throw new Error(`writeSessionMessages: invalid role "${m.role}"`);
      const metadataJson = m.metadata === undefined ? null : JSON.stringify(m.metadata);
      const tokenCount = m.tokenCount ?? null;
      runStatement(db, SQL_INSERT, [sessionId, m.ts, m.role, m.content, metadataJson, tokenCount]);
      n += 1;
    }
    return n;
  });
  return apply(msgs);
}

/** T-019 — read messages for one session, optionally filtered by ts range. */
export function readSessionMessages(
  db: DatabaseT,
  sessionId: string,
  query: SessionMessageQuery = {},
): SessionMessageRow[] {
  if (!sessionId) throw new Error('readSessionMessages: sessionId must be non-empty');
  const fromTs = query.fromTs ?? null;
  const toTs = query.toTs ?? null;
  const sql = query.descending === true ? SQL_SELECT_RANGE_DESC : SQL_SELECT_RANGE;
  const rows = queryAll<SessionMessageRow>(
    db,
    sql,
    [sessionId, fromTs, fromTs, toTs, toTs],
    (r) => mapRow(r),
  );
  const limit = query.limit;
  if (typeof limit === 'number' && limit >= 0 && rows.length > limit) {
    return rows.slice(0, limit);
  }
  return rows;
}

/** Convenience: count messages for a session. */
export function countSessionMessages(db: DatabaseT, sessionId: string): number {
  if (!sessionId) throw new Error('countSessionMessages: sessionId must be non-empty');
  const rows = queryAll<{ n: number }>(
    db,
    SQL_COUNT,
    [sessionId],
    (r) => ({ n: Number(r['n'] ?? 0) }),
  );
  return rows[0]?.n ?? 0;
}

/** Drop every row for a session. Returns the number of rows removed. */
export function deleteSession(db: DatabaseT, sessionId: string): number {
  if (!sessionId) throw new Error('deleteSession: sessionId must be non-empty');
  const info = runStatement(db, SQL_DELETE_SESSION, [sessionId]);
  return info.changes;
}

/** Drop a single message by PK. */
export function deleteSessionMessage(db: DatabaseT, sessionId: string, ts: number): boolean {
  if (!sessionId) throw new Error('deleteSessionMessage: sessionId must be non-empty');
  const info = runStatement(db, SQL_DELETE_ONE, [sessionId, ts]);
  return info.changes > 0;
}

/**
 * Internal: map a raw row from better-sqlite3 into a SessionMessageRow.
 * `metadata` is preserved as the raw JSON string; callers can JSON.parse on
 * demand. `token_count` is coerced to number | null. `role` is narrowed to
 * MessageRole via `isMessageRole`.
 */
function mapRow(r: Record<string, unknown>): SessionMessageRow {
  const role = r['role'];
  const safeRole: MessageRole = isMessageRole(typeof role === 'string' ? role : '')
    ? (role as MessageRole)
    : 'system';
  const meta = r['metadata'];
  const tc = r['token_count'];
  return {
    session_id: String(r['session_id'] ?? ''),
    ts: Number(r['ts'] ?? 0),
    role: safeRole,
    content: String(r['content'] ?? ''),
    metadata: meta === null || meta === undefined ? null : String(meta),
    token_count: tc === null || tc === undefined ? null : Number(tc),
  };
}
