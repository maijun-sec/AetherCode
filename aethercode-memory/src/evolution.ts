/**
 * R-MEM-2: memory evolution — consolidation + forgetting.
 *
 * Two related operations on the `project_changes` table:
 *
 *  - **Consolidation**: find similar descriptions (by simple
 *    token-Jaccard similarity, optionally upgraded to embedding
 *    similarity via the R-MEM-1 vector store) and merge them
 *    into one canonical row. The source rows are marked
 *    `consolidated_into = <target_id>` so reads skip them. The
 *    target row's description is the longest of the merged set
 *    (a future round can swap in an LLM-generated description
 *    when an LLM client is available — see `mergeStrategy`).
 *
 *  - **Forgetting**: find rows matching the configured
 *    forget-policy (expired / inactive / low-value) and
 *    soft-delete them. Soft delete keeps them queryable for a
 *    configurable retention window so a UI "undo" button can
 *    restore them before the vacuum pass hard-deletes.
 *
 * Both operations are wired into the `MemoryStore` via methods
 * (`consolidate`, `forget`, `readStats`) and the JSON-RPC surface
 * (`memory/consolidate`, `memory/forget`, `memory/stats`).
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement } from './sqlite.js';
import {
  listProjectChanges,
  findForgetCandidates,
  markConsolidatedInto,
  softDeleteProjectChange,
  restoreProjectChange,
  vacuumSoftDeletedChanges,
  readProjectMemoryStats,
  touchChangeAccess,
  type ProjectChangeRow,
  type ProjectMemoryStats,
  type ForgetPolicy,
} from './project-store.js';

/* ----------------------------- consolidation ----------------------------- */

export interface ConsolidateResult {
  readonly scanned: number;
  readonly groups: number;
  readonly merged: number;
  readonly ms: number;
  /** Each group that was merged, in the order they were processed.
   *  The caller can show this in the UI as a "what changed" log. */
  readonly merges: ReadonlyArray<{
    readonly targetId: number;
    readonly sourceIds: ReadonlyArray<number>;
    readonly description: string;
  }>;
}

/** Configuration for one consolidation pass. */
export interface ConsolidateOptions {
  /** Minimum Jaccard similarity (in [0, 1]) for two descriptions
   *  to be considered "similar enough to merge". Default 0.5. */
  readonly jaccardThreshold?: number;
  /** Max descriptions to scan in a single pass. Default 500. */
  readonly maxScanned?: number;
  /** When true, only consider rows not yet consolidated. */
  readonly liveOnly?: boolean;
}

/** A group of similar change rows, ready to be merged. */
interface ConsolidationGroup {
  readonly targetId: number;
  sourceIds: number[];
  readonly description: string;
}

/**
 * Run a consolidation pass on a project's change log.
 *
 * Algorithm:
 *  1. Load up to `maxScanned` live rows.
 *  2. For each row, compare against the already-merged group
 *     targets using a fast Jaccard similarity (token sets).
 *  3. If similarity > threshold, append to that group. Otherwise
 *     start a new group with this row as the target.
 *  4. For each group with > 1 row, mark the smaller-id rows
 *     as `consolidated_into = <target.id>`. The target's
 *     description is the longest one in the group.
 *
 * The function is pure-SQL-safe: every decision is made in JS
 * after reading the rows, so the SQL contract stays simple and
 * testable.
 */
export function consolidateProjectChanges(
  db: DatabaseT,
  projectId: string,
  opts: ConsolidateOptions = {},
): ConsolidateResult {
  const t0 = performance.now();
  const threshold = opts.jaccardThreshold ?? 0.5;
  const maxScanned = opts.maxScanned ?? 500;
  const liveOnly = opts.liveOnly !== false;

  const allRows = listProjectChanges(db, projectId, { uncompressedOnly: true, descending: false });
  const rows = allRows.slice(0, maxScanned);
  // If liveOnly, we already filtered via uncompressedOnly:true
  // (which also requires consolidated_into IS NULL per v6 schema).
  void liveOnly;

  const groups: ConsolidationGroup[] = [];
  for (const row of rows) {
    const tokens = tokenise(row.description);
    if (tokens.size === 0) continue;
    // Find an existing group whose target is similar enough.
    let matched = false;
    for (const g of groups) {
      const gTokens = tokenise(g.description);
      const sim = jaccard(tokens, gTokens);
      if (sim >= threshold) {
        // Append to this group.
        const newSourceIds: number[] = [...g.sourceIds, row.id];
        const newDescription = pickCanonicalDescription(g.description, row.description);
        const idx = groups.indexOf(g);
        groups[idx] = { ...g, sourceIds: newSourceIds, description: newDescription };
        matched = true;
        break;
      }
    }
    if (!matched) {
      groups.push({ targetId: row.id, sourceIds: [], description: row.description });
    }
  }

  // Apply the consolidations: for groups with > 1 source, mark
  // the source rows as consolidated_into = targetId.
  const apply = db.transaction((): number => {
    let merged = 0;
    const merges: Array<{ targetId: number; sourceIds: number[]; description: string }> = [];
    for (const g of groups) {
      if (g.sourceIds.length === 0) continue;
      // Update the target's description to the canonical one.
      runStatement(
        db,
        `UPDATE project_changes SET description = ? WHERE id = ?`,
        [g.description, g.targetId],
      );
      // Mark sources.
      for (const sid of g.sourceIds) {
        if (markConsolidatedInto(db, sid, g.targetId)) merged += 1;
      }
      merges.push({ targetId: g.targetId, sourceIds: g.sourceIds, description: g.description });
    }
    return merged;
  });
  const merged = apply();

  const finalMerges: ConsolidationGroup[] = groups.filter((g) => g.sourceIds.length > 0);

  return {
    scanned: rows.length,
    groups: finalMerges.length,
    merged,
    ms: performance.now() - t0,
    merges: finalMerges,
  };
}

/* ----------------------------- forgetting ----------------------------- */

export interface ForgetResult {
  readonly candidates: number;
  readonly softDeleted: number;
  readonly now: number;
  /** A short summary of each row that was soft-deleted, in
   *  deletion order. The UI can show this in a "soft-deleted N
   *  rows" toast and offer an undo button. */
  readonly deleted: ReadonlyArray<{ id: number; description: string; reason: string }>;
}

/** Run a forgetting pass on a project's change log. */
export function forgetProjectChanges(
  db: DatabaseT,
  projectId: string,
  policy: ForgetPolicy = {},
  now: number = Date.now(),
): ForgetResult {
  const candidates = findForgetCandidates(db, projectId, policy, now);
  const deleted: Array<{ id: number; description: string; reason: string }> = [];
  const apply = db.transaction((): number => {
    let n = 0;
    for (const row of candidates) {
      const reason = classifyReason(row, policy, now);
      if (softDeleteProjectChange(db, row.id, now)) {
        deleted.push({ id: row.id, description: row.description, reason });
        n += 1;
      }
    }
    return n;
  });
  const softDeleted = apply();
  return {
    candidates: candidates.length,
    softDeleted,
    now,
    deleted,
  };
}

/** Hard-delete soft-deleted rows older than the retention window. */
export function vacuumForgottenChanges(
  db: DatabaseT,
  retentionMs: number = 30 * 24 * 60 * 60 * 1000, // 30 days default
  now: number = Date.now(),
): number {
  return vacuumSoftDeletedChanges(db, retentionMs, now);
}

/** Undo a soft-delete (the UI "restore" button). */
export function restoreForgottenChange(
  db: DatabaseT,
  changeId: number,
): boolean {
  return restoreProjectChange(db, changeId);
}

/** Read the project's memory stats (counts + candidate counts). */
export function readMemoryStats(
  db: DatabaseT,
  projectId: string,
  now: number = Date.now(),
): ProjectMemoryStats {
  return readProjectMemoryStats(db, projectId, now);
}

/** Touch the access counter on a change row (called from
 *  `memory/find` and `memory/get` when a row is returned). */
export function touchAccess(db: DatabaseT, changeId: number, now: number = Date.now()): boolean {
  return touchChangeAccess(db, changeId, now);
}

/* ----------------------------- embedding-aware find (R-MEM-1 + R-MEM-2) -- */

/**
 * R-MEM-1's vector store surfaces candidates; this function
 * updates the access counters on the project_changes rows that
 * were actually returned. The wiring is:
 *
 *   memory/find → vec_store.search → returned rows
 *                ↘ this function (best-effort)
 */
export function touchVectorStoreHits(
  db: DatabaseT,
  rows: ReadonlyArray<{ scope: string; entry_id: string }>,
  now: number = Date.now(),
): number {
  let n = 0;
  for (const r of rows) {
    if (r.scope !== 'project') continue;
    const m = r.entry_id.match(/^project-change-(\d+)$/);
    if (m === null) continue;
    const id = Number(m[1]);
    if (touchChangeAccess(db, id, now)) n += 1;
  }
  return n;
}

/* ----------------------------- helpers ----------------------------- */

const TOKEN_SPLIT = /[^\p{L}\p{N}]+/u;

function tokenise(text: string): Set<string> {
  const out = new Set<string>();
  if (!text) return out;
  for (const part of text.toLowerCase().split(TOKEN_SPLIT)) {
    if (part.length > 0) out.add(part);
  }
  return out;
}

function jaccard(a: Set<string>, b: Set<string>): number {
  if (a.size === 0 || b.size === 0) return 0;
  let inter = 0;
  for (const t of a) if (b.has(t)) inter += 1;
  const union = a.size + b.size - inter;
  return union === 0 ? 0 : inter / union;
}

function pickCanonicalDescription(a: string, b: string): string {
  // Canonical: the longer one wins, ties broken by older (a).
  if (b.length > a.length) return b;
  return a;
}

function classifyReason(row: ProjectChangeRow, policy: ForgetPolicy, now: number): string {
  if (policy.expired !== false && row.expires_at !== null && row.expires_at <= now) {
    return 'expired';
  }
  if (policy.lowValue === true && row.value_tag === 'low') {
    return 'low-value';
  }
  if (policy.inactiveSinceMs !== undefined && policy.inactiveSinceMs !== null
      && row.last_accessed_at > 0
      && row.last_accessed_at <= now - policy.inactiveSinceMs) {
    return 'inactive';
  }
  return 'policy';
}
