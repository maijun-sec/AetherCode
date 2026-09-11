/**
 * R-MEM-6.4 (F3 RL-tuned memory): retrieval-feedback store.
 *
 * Lightweight "did the agent actually use this memory?" loop.
 * Every time `memory/find` returns a hit, the caller (a
 * higher-level agent loop) eventually decides whether the hit
 * helped. They call `recordRetrievalOutcome` with `used=true`
 * or `used=false`, and the row accumulates counters.
 *
 * `findSimilar` then boosts rows with a high used_count:
 *   boost = log(1 + used_count) * 0.1  (max ~0.46)
 *   and penalises rows with a high not_used_count:
 *   penalty = log(1 + not_used_count) * 0.05  (max ~0.23)
 *
 * This is a deliberately small "linear bandit" — no
 * reinforcement learning, no gradient updates, no model
 * checkpoints. The point is to give the agent a way to teach
 * the memory store "this is useful, that isn't" without
 * shipping a training pipeline. The numbers come straight
 * from the v13 schema.
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement, queryAll } from './sqlite.js';

export interface RetrievalFeedbackRow {
  readonly id: number;
  readonly scope: string;
  readonly entry_id: string;
  readonly used_count: number;
  readonly not_used_count: number;
  readonly last_feedback_at: number | null;
}

const SQL_UPSERT_FEEDBACK = `INSERT INTO retrieval_feedback
  (scope, entry_id, used_count, not_used_count, last_feedback_at)
  VALUES (?, ?, ?, ?, ?)
  ON CONFLICT(scope, entry_id) DO UPDATE SET
    used_count = used_count + excluded.used_count,
    not_used_count = not_used_count + excluded.not_used_count,
    last_feedback_at = excluded.last_feedback_at`;

const SQL_BUMP_USED = `UPDATE retrieval_feedback
  SET used_count = used_count + 1, last_feedback_at = ?
  WHERE scope = ? AND entry_id = ?`;

const SQL_BUMP_NOT_USED = `UPDATE retrieval_feedback
  SET not_used_count = not_used_count + 1, last_feedback_at = ?
  WHERE scope = ? AND entry_id = ?`;

const SQL_GET = `SELECT id, scope, entry_id, used_count, not_used_count, last_feedback_at
  FROM retrieval_feedback
  WHERE scope = ? AND entry_id = ?`;

const SQL_GET_BULK = `SELECT id, scope, entry_id, used_count, not_used_count, last_feedback_at
  FROM retrieval_feedback
  WHERE scope = ? AND entry_id IN (SELECT value FROM json_each(?))`;

const SQL_STATS = `SELECT
    COUNT(*) AS rows_total,
    SUM(used_count) AS used_total,
    SUM(not_used_count) AS not_used_total
  FROM retrieval_feedback`;

/** Record a single retrieval outcome. The row is upserted
 *  on (scope, entry_id). */
export function recordRetrievalOutcome(
  db: DatabaseT,
  scope: string,
  entryId: string,
  used: boolean,
  atMs: number = Date.now(),
): boolean {
  if (!scope) throw new Error('recordRetrievalOutcome: scope must be non-empty');
  if (!entryId) throw new Error('recordRetrievalOutcome: entryId must be non-empty');
  const info = runStatement(db, SQL_UPSERT_FEEDBACK, [scope, entryId, used ? 1 : 0, used ? 0 : 1, atMs]);
  return info.changes > 0;
}

/** Increment used_count by 1 for an existing row. Returns true
 *  if a row was updated. */
export function bumpUsed(db: DatabaseT, scope: string, entryId: string, atMs: number = Date.now()): boolean {
  const info = runStatement(db, SQL_BUMP_USED, [atMs, scope, entryId]);
  return info.changes > 0;
}

/** Increment not_used_count by 1 for an existing row. */
export function bumpNotUsed(db: DatabaseT, scope: string, entryId: string, atMs: number = Date.now()): boolean {
  const info = runStatement(db, SQL_BUMP_NOT_USED, [atMs, scope, entryId]);
  return info.changes > 0;
}

/** Read the feedback row for a single entry. */
export function getFeedback(db: DatabaseT, scope: string, entryId: string): RetrievalFeedbackRow | null {
  const rows = queryAll<RetrievalFeedbackRow>(db, SQL_GET, [scope, entryId], mapFeedbackRow);
  return rows[0] ?? null;
}

/** Bulk-read feedback rows for a list of entry_ids. Used by
 *  `findSimilar` to apply the boost in a single query. */
export function getFeedbackBulk(
  db: DatabaseT,
  scope: string,
  entryIds: ReadonlyArray<string>,
): Map<string, RetrievalFeedbackRow> {
  if (entryIds.length === 0) return new Map();
  const rows = queryAll<RetrievalFeedbackRowRaw>(
    db,
    SQL_GET_BULK,
    [scope, JSON.stringify(entryIds)],
    mapFeedbackRow,
  );
  const out = new Map<string, RetrievalFeedbackRow>();
  for (const r of rows) out.set(r.entry_id, r);
  return out;
}

/** Aggregate stats over all feedback rows. */
export interface FeedbackStats {
  readonly rowsTotal: number;
  readonly usedTotal: number;
  readonly notUsedTotal: number;
}
export function readFeedbackStats(db: DatabaseT): FeedbackStats {
  const row = db.prepare(SQL_STATS).get() as { rows_total: number; used_total: number; not_used_total: number };
  return {
    rowsTotal: Number(row.rows_total ?? 0),
    usedTotal: Number(row.used_total ?? 0),
    notUsedTotal: Number(row.not_used_total ?? 0),
  };
}

function mapFeedbackRow(r: Record<string, unknown>): RetrievalFeedbackRow {
  return {
    id: Number(r['id'] ?? 0),
    scope: String(r['scope'] ?? ''),
    entry_id: String(r['entry_id'] ?? ''),
    used_count: Number(r['used_count'] ?? 0),
    not_used_count: Number(r['not_used_count'] ?? 0),
    last_feedback_at:
      r['last_feedback_at'] === null || r['last_feedback_at'] === undefined
        ? null
        : Number(r['last_feedback_at']),
  };
}
type RetrievalFeedbackRowRaw = RetrievalFeedbackRow;
