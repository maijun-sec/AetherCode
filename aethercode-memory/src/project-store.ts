/**
 * Project storage — T-020 (project_db write/read) and T-021 (project_changes
 * write/read).
 *
 * `project_db` is the row that owns a project (one per `cwd`); `project_changes`
 * is the append-only log of changes (one per `appendProjectChange` call). The
 * primary key on `project_db.project_id` is `hash(cwd)` (computed by the
 * caller — the store does not know how).
 *
 * Both tables are created by migrations v2 and v3 (see sqlite.ts).
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement, queryAll } from './sqlite.js';
import type { ChangeEntry } from './types.js';

/** A row in the `project_db` table. */
export interface ProjectRow {
  readonly project_id: string;
  readonly cwd: string;
  readonly title: string | null;
  readonly description: string | null;
  readonly updated_at: number;
}

/** Input shape for `upsertProject` — all fields except `updated_at` are optional. */
export interface ProjectUpsert {
  readonly project_id: string;
  readonly cwd: string;
  readonly title?: string | null;
  readonly description?: string | null;
  /** Defaults to `Date.now()`. */
  readonly updated_at?: number;
}

/** A row in the `project_changes` table. */
export interface ProjectChangeRow {
  readonly id: number;
  readonly project_id: string;
  readonly ts: number;
  readonly description: string;
  /** 0 = not yet compressed into the project 说明; 1 = folded in. */
  readonly compressed: 0 | 1;
  /** R-MEM-2: explicit expiry timestamp (ms epoch). NULL = no expiry. */
  readonly expires_at: number | null;
  /** R-MEM-2: id of the row this change was consolidated into. NULL = live. */
  readonly consolidated_into: number | null;
  /** R-MEM-2: 'normal' or 'low'. Low-tagged rows are candidates
   *  for aggressive forgetting (R-MEM-2.2). */
  readonly value_tag: 'normal' | 'low';
  /** R-MEM-2: last time the row was read by a `memory/get` or
   *  `memory/find` call. 0 = never read (R-MEM-2 inactivity policy). */
  readonly last_accessed_at: number;
  /** R-MEM-2: read count. Combined with last_accessed_at to
   *  drive the forget policy. */
  readonly access_count: number;
  /** R-MEM-2: soft-delete marker. NULL = live, non-NULL = trashed
   *  at this epoch ms. */
  readonly deleted_at: number | null;
  /** R-MEM-3: subagent session id that shared this change. NULL for
   *  normal project changes. When set, the entry is a "team"
   *  entry visible to the main agent but still scoped to the
   *  originating subagent. */
  readonly team_session_id: string | null;
}

/** Input for `appendProjectChange`. */
export interface ProjectChangeInput {
  readonly project_id: string;
  readonly description: string;
  /** Defaults to `Date.now()`. */
  readonly ts?: number;
  /** Defaults to 0. */
  readonly compressed?: 0 | 1;
  /** R-MEM-2: explicit expiry timestamp (ms epoch). */
  readonly expires_at?: number | null;
  /** R-MEM-2: 'normal' or 'low'. Defaults to 'normal'. */
  readonly value_tag?: 'normal' | 'low';
  /** R-MEM-3: subagent session id. NULL = normal project change.
   *  Non-NULL = shared from this subagent session. */
  readonly team_session_id?: string | null;
}

/** SQL: insert or update a project row. */
const SQL_UPSERT_PROJECT = `INSERT INTO project_db
  (project_id, cwd, title, description, updated_at)
  VALUES (?, ?, ?, ?, ?)
  ON CONFLICT(project_id) DO UPDATE SET
    cwd = excluded.cwd,
    title = excluded.title,
    description = excluded.description,
    updated_at = excluded.updated_at`;

/** SQL: read a single project. */
const SQL_SELECT_PROJECT = `SELECT project_id, cwd, title, description, updated_at
  FROM project_db
  WHERE project_id = ?`;

/** SQL: list all projects, most recent first. */
const SQL_LIST_PROJECTS = `SELECT project_id, cwd, title, description, updated_at
  FROM project_db
  ORDER BY updated_at DESC`;

/** SQL: delete a project. (Cascading is left to FK rules; changes are kept by default.) */
const SQL_DELETE_PROJECT = `DELETE FROM project_db WHERE project_id = ?`;

/** SQL: insert a change. */
const SQL_INSERT_CHANGE = `INSERT INTO project_changes
  (project_id, ts, description, compressed, expires_at, value_tag, last_accessed_at, access_count, team_session_id)
  VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)`;

/** SQL: list changes. */
const SQL_LIST_CHANGES = `SELECT id, project_id, ts, description, compressed,
       expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
  FROM project_changes
  WHERE project_id = ?
    AND deleted_at IS NULL
    AND (? IS NULL OR compressed = ?)
  ORDER BY ts ASC`;

/** SQL: list changes, newest first. */
const SQL_LIST_CHANGES_DESC = `SELECT id, project_id, ts, description, compressed,
       expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
  FROM project_changes
  WHERE project_id = ?
    AND deleted_at IS NULL
    AND (? IS NULL OR compressed = ?)
  ORDER BY ts DESC`;

/** SQL: list uncompressed, not-yet-consolidated, not-deleted changes. */
const SQL_LIST_UNCOMPRESSED = `SELECT id, project_id, ts, description, compressed,
       expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
  FROM project_changes
  WHERE project_id = ?
    AND compressed = 0
    AND consolidated_into IS NULL
    AND deleted_at IS NULL
  ORDER BY ts ASC`;

/** SQL: count changes. */
const SQL_COUNT_CHANGES = `SELECT COUNT(*) AS n FROM project_changes
  WHERE project_id = ? AND deleted_at IS NULL`;

/** SQL: mark a set of changes as compressed. */
const SQL_MARK_COMPRESSED = `UPDATE project_changes
  SET compressed = 1
  WHERE project_id = ? AND id IN (SELECT value FROM json_each(?))`;

/** SQL: delete one change by id. */
const SQL_DELETE_CHANGE = `DELETE FROM project_changes WHERE id = ?`;

/** T-020 — write (insert-or-update) a project row. */
export function upsertProject(db: DatabaseT, project: ProjectUpsert): ProjectRow {
  if (!project.project_id) throw new Error('upsertProject: project_id must be non-empty');
  if (!project.cwd) throw new Error('upsertProject: cwd must be non-empty');
  const updatedAt = project.updated_at ?? Date.now();
  const title = project.title ?? null;
  const description = project.description ?? null;
  runStatement(db, SQL_UPSERT_PROJECT, [
    project.project_id,
    project.cwd,
    title,
    description,
    updatedAt,
  ]);
  return {
    project_id: project.project_id,
    cwd: project.cwd,
    title,
    description,
    updated_at: updatedAt,
  };
}

/** T-020 — read one project by id. Returns `null` if not found. */
export function readProject(db: DatabaseT, projectId: string): ProjectRow | null {
  if (!projectId) throw new Error('readProject: project_id must be non-empty');
  const rows = queryAll<ProjectRow>(
    db,
    SQL_SELECT_PROJECT,
    [projectId],
    (r) => mapProjectRow(r),
  );
  return rows[0] ?? null;
}

/** List every project row, most recently updated first. */
export function listProjects(db: DatabaseT): ProjectRow[] {
  return queryAll<ProjectRow>(db, SQL_LIST_PROJECTS, [], (r) => mapProjectRow(r));
}

/** Remove a project row. Cascade behaviour is up to the caller. */
export function deleteProject(db: DatabaseT, projectId: string): boolean {
  if (!projectId) throw new Error('deleteProject: project_id must be non-empty');
  const info = runStatement(db, SQL_DELETE_PROJECT, [projectId]);
  return info.changes > 0;
}

/** T-021 — append one change to a project. Returns the new row id. */
export function appendProjectChange(db: DatabaseT, input: ProjectChangeInput): number {
  if (!input.project_id) throw new Error('appendProjectChange: project_id must be non-empty');
  if (!input.description) throw new Error('appendProjectChange: description must be non-empty');
  const ts = input.ts ?? Date.now();
  const compressed: 0 | 1 = input.compressed ?? 0;
  const valueTag = input.value_tag ?? 'normal';
  const expiresAt = input.expires_at ?? null;
  const lastAccessedAt = ts; // fresh row starts with "accessed at creation time"
  const teamSessionId = input.team_session_id ?? null;
  const info = runStatement(db, SQL_INSERT_CHANGE, [
    input.project_id,
    ts,
    input.description,
    compressed,
    expiresAt,
    valueTag,
    lastAccessedAt,
    teamSessionId,
  ]);
  return typeof info.lastInsertRowid === 'bigint'
    ? Number(info.lastInsertRowid)
    : (info.lastInsertRowid as number);
}

/** Append many changes inside a single transaction. */
export function appendProjectChanges(
  db: DatabaseT,
  inputs: ReadonlyArray<ProjectChangeInput>,
): number[] {
  const apply = db.transaction((rows: ReadonlyArray<ProjectChangeInput>): number[] => {
    const ids: number[] = [];
    for (const input of rows) {
      ids.push(appendProjectChange(db, input));
    }
    return ids;
  });
  return apply(inputs);
}

/**
 * T-021 — list changes for a project, oldest first by default. If
 * `uncompressedOnly` is true, only the still-pending changes are returned.
 */
export function listProjectChanges(
  db: DatabaseT,
  projectId: string,
  options: { uncompressedOnly?: boolean; descending?: boolean } = {},
): ProjectChangeRow[] {
  if (!projectId) throw new Error('listProjectChanges: project_id must be non-empty');
  if (options.uncompressedOnly === true) {
    return queryAll<ProjectChangeRow>(
      db,
      SQL_LIST_UNCOMPRESSED,
      [projectId],
      (r) => mapChangeRow(r),
    );
  }
  const sql = options.descending === true ? SQL_LIST_CHANGES_DESC : SQL_LIST_CHANGES;
  return queryAll<ProjectChangeRow>(db, sql, [projectId, null, null], (r) => mapChangeRow(r));
}

/** Count the number of changes logged for a project. */
export function countProjectChanges(db: DatabaseT, projectId: string): number {
  if (!projectId) throw new Error('countProjectChanges: project_id must be non-empty');
  const rows = queryAll<{ n: number }>(
    db,
    SQL_COUNT_CHANGES,
    [projectId],
    (r) => ({ n: Number(r['n'] ?? 0) }),
  );
  return rows[0]?.n ?? 0;
}

/** Mark a set of change ids as compressed. */
export function markChangesCompressed(
  db: DatabaseT,
  projectId: string,
  changeIds: ReadonlyArray<number>,
): number {
  if (!projectId) throw new Error('markChangesCompressed: project_id must be non-empty');
  if (changeIds.length === 0) return 0;
  const info = runStatement(db, SQL_MARK_COMPRESSED, [projectId, JSON.stringify(changeIds)]);
  return info.changes;
}

/** Remove a single change by id. */
export function deleteProjectChange(db: DatabaseT, changeId: number): boolean {
  const info = runStatement(db, SQL_DELETE_CHANGE, [changeId]);
  return info.changes > 0;
}

/** Convert a `ProjectChangeRow` into the public `ChangeEntry` memory shape. */
export function projectChangeToEntry(row: ProjectChangeRow): ChangeEntry {
  return {
    kind: 'change',
    id: `project-change-${row.id}`,
    ts: row.ts,
    scope: 'project',
    source: 'system',
    tags: ['change'],
    description: row.description,
    compressed: row.compressed === 1,
  };
}

/** Convert a `ProjectRow` into Fact entries (one per non-null field, plus the project_id). */
export function projectRowToFacts(row: ProjectRow): ReadonlyArray<import('./types.js').Fact> {
  const out: import('./types.js').Fact[] = [];
  out.push({
    kind: 'fact',
    id: `project-fact-${row.project_id}-cwd`,
    ts: row.updated_at,
    scope: 'project',
    source: 'system',
    tags: ['project'],
    key: 'cwd',
    value: row.cwd,
  });
  if (row.title !== null) {
    out.push({
      kind: 'fact',
      id: `project-fact-${row.project_id}-title`,
      ts: row.updated_at,
      scope: 'project',
      source: 'system',
      tags: ['project'],
      key: 'title',
      value: row.title,
    });
  }
  if (row.description !== null) {
    out.push({
      kind: 'fact',
      id: `project-fact-${row.project_id}-description`,
      ts: row.updated_at,
      scope: 'project',
      source: 'system',
      tags: ['project', 'description'],
      key: 'description',
      value: row.description,
    });
  }
  return out;
}

function mapProjectRow(r: Record<string, unknown>): ProjectRow {
  return {
    project_id: String(r['project_id'] ?? ''),
    cwd: String(r['cwd'] ?? ''),
    title: r['title'] === null || r['title'] === undefined ? null : String(r['title']),
    description:
      r['description'] === null || r['description'] === undefined ? null : String(r['description']),
    updated_at: Number(r['updated_at'] ?? 0),
  };
}

function mapChangeRow(r: Record<string, unknown>): ProjectChangeRow {
  const valueTag = String(r['value_tag'] ?? 'normal');
  return {
    id: Number(r['id'] ?? 0),
    project_id: String(r['project_id'] ?? ''),
    ts: Number(r['ts'] ?? 0),
    description: String(r['description'] ?? ''),
    compressed: Number(r['compressed'] ?? 0) === 1 ? 1 : 0,
    expires_at: r['expires_at'] === null || r['expires_at'] === undefined ? null : Number(r['expires_at']),
    consolidated_into: r['consolidated_into'] === null || r['consolidated_into'] === undefined ? null : Number(r['consolidated_into']),
    value_tag: valueTag === 'low' ? 'low' : 'normal',
    last_accessed_at: Number(r['last_accessed_at'] ?? 0),
    access_count: Number(r['access_count'] ?? 0),
    deleted_at: r['deleted_at'] === null || r['deleted_at'] === undefined ? null : Number(r['deleted_at']),
    team_session_id: r['team_session_id'] === null || r['team_session_id'] === undefined
      ? null
      : String(r['team_session_id']),
  };
}

/* ============================== R-MEM-2 ==============================
 *  Evolution helpers. These extend project-store with the
 *  primitives consolidation + forgetting need: marking rows as
 *  soft-deleted, pointing at a consolidation target, and updating
 *  access stats. Kept here (rather than a new module) because
 *  the SQL is a thin extension of the existing CRUD.
 * =================================================================== */

/** Mark a single change as soft-deleted (R-MEM-2 forgetting). */
export function softDeleteProjectChange(db: DatabaseT, changeId: number, atMs: number = Date.now()): boolean {
  const info = runStatement(
    db,
    `UPDATE project_changes SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL`,
    [atMs, changeId],
  );
  return info.changes > 0;
}

/** Restore a soft-deleted change (R-MEM-2 undo). */
export function restoreProjectChange(db: DatabaseT, changeId: number): boolean {
  const info = runStatement(
    db,
    `UPDATE project_changes SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL`,
    [changeId],
  );
  return info.changes > 0;
}

/** Hard-delete soft-deleted rows older than `olderThanMs`. Returns
 *  the number of rows hard-deleted. Called by a vacuum pass. */
export function vacuumSoftDeletedChanges(db: DatabaseT, olderThanMs: number, now: number = Date.now()): number {
  const cutoff = now - olderThanMs;
  const info = runStatement(
    db,
    `DELETE FROM project_changes WHERE deleted_at IS NOT NULL AND deleted_at < ?`,
    [cutoff],
  );
  return info.changes;
}

/** Mark a change as consolidated into another (R-MEM-2 consolidation). */
export function markConsolidatedInto(db: DatabaseT, sourceId: number, targetId: number): boolean {
  if (sourceId === targetId) return false;
  const info = runStatement(
    db,
    `UPDATE project_changes SET consolidated_into = ? WHERE id = ? AND consolidated_into IS NULL`,
    [targetId, sourceId],
  );
  return info.changes > 0;
}

/** Increment the access count + last_accessed_at for a change. */
export function touchChangeAccess(db: DatabaseT, changeId: number, atMs: number = Date.now()): boolean {
  const info = runStatement(
    db,
    `UPDATE project_changes
       SET access_count = access_count + 1,
           last_accessed_at = ?
       WHERE id = ? AND deleted_at IS NULL AND consolidated_into IS NULL`,
    [atMs, changeId],
  );
  return info.changes > 0;
}

/** Find candidate changes for forgetting, applying the given
 *  policy filters. Returns the matching rows in ts-ascending order
 *  so the caller can act on the oldest first. */
export interface ForgetPolicy {
  /** Include rows whose `expires_at` is <= now. Opt-in: pass true
   *  explicitly. Default false. */
  readonly expired?: boolean;
  /** Include rows whose `last_accessed_at` is older than
   *  `inactiveSinceMs`. Opt-in: pass a value. Default false. */
  readonly inactiveSinceMs?: number | null;
  /** Include rows with `value_tag = 'low'`. Opt-in: pass true. */
  readonly lowValue?: boolean;
  /** Maximum rows to return. Default 100. */
  readonly limit?: number;
}
export function findForgetCandidates(
  db: DatabaseT,
  projectId: string,
  policy: ForgetPolicy,
  now: number = Date.now(),
): ProjectChangeRow[] {
  const conds: string[] = ['project_id = ?', 'deleted_at IS NULL', 'consolidated_into IS NULL'];
  const params: (string | number | null)[] = [projectId];
  if (policy.expired === true) {
    conds.push('(expires_at IS NOT NULL AND expires_at <= ?)');
    params.push(now);
  }
  if (policy.inactiveSinceMs !== undefined && policy.inactiveSinceMs !== null) {
    conds.push('(last_accessed_at > 0 AND last_accessed_at <= ?)');
    params.push(now - policy.inactiveSinceMs);
  }
  if (policy.lowValue === true) {
    conds.push("value_tag = 'low'");
  }
  const limit = policy.limit ?? 100;
  const sql = `SELECT id, project_id, ts, description, compressed,
       expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
    FROM project_changes
    WHERE ${conds.join(' AND ')}
    ORDER BY ts ASC
    LIMIT ?`;
  params.push(limit);
  return queryAll<ProjectChangeRow>(db, sql, params, (r) => mapChangeRow(r));
}

/** Memory statistics: counts grouped by liveness for the panel. */
export interface ProjectMemoryStats {
  readonly projectId: string;
  readonly totalChanges: number;
  readonly liveChanges: number;
  readonly consolidatedChanges: number;
  readonly softDeletedChanges: number;
  readonly expiredCandidates: number;
  readonly lowValueCandidates: number;
  readonly inactiveCandidates: number;
  readonly now: number;
}
export function readProjectMemoryStats(
  db: DatabaseT,
  projectId: string,
  now: number = Date.now(),
): ProjectMemoryStats {
  const count = (where: string, params: ReadonlyArray<string | number> = []): number => {
    const row = db.prepare(`SELECT COUNT(*) AS n FROM project_changes WHERE project_id = ? AND ${where}`).get(projectId, ...params) as { n: number };
    return Number(row.n);
  };
  const totalChanges = count('1=1');
  const liveChanges = count('deleted_at IS NULL AND consolidated_into IS NULL');
  const consolidatedChanges = count('consolidated_into IS NOT NULL');
  const softDeletedChanges = count('deleted_at IS NOT NULL');
  const expiredCandidates = count('deleted_at IS NULL AND consolidated_into IS NULL AND expires_at IS NOT NULL AND expires_at <= ?', [now]);
  const lowValueCandidates = count("deleted_at IS NULL AND consolidated_into IS NULL AND value_tag = 'low'");
  const inactiveCandidates = count('deleted_at IS NULL AND consolidated_into IS NULL AND last_accessed_at > 0 AND last_accessed_at <= ?', [now - 90 * 24 * 60 * 60 * 1000]);
  return {
    projectId,
    totalChanges,
    liveChanges,
    consolidatedChanges,
    softDeletedChanges,
    expiredCandidates,
    lowValueCandidates,
    inactiveCandidates,
    now,
  };
}

/* ============================== R-MEM-3 ==============================
 *  Subagent shared memory helpers. A subagent calls
 *  `appendTeamChange` to share a fact with the main agent. The main
 *  agent reads them via `listTeamChanges` and decides which to
 *  promote to permanent project memory via `promoteTeamChanges`.
 *
 *  All helpers run on the same `project_changes` table — the
 *  `team_session_id` column is the marker. NULL = normal project
 *  change; non-NULL = shared entry from a subagent session.
 * =================================================================== */

/** SQL: append a team change (R-MEM-3). Same as SQL_INSERT_CHANGE
 *  but always sets team_session_id. */
const SQL_INSERT_TEAM_CHANGE = `INSERT INTO project_changes
  (project_id, ts, description, compressed, expires_at, value_tag, last_accessed_at, access_count, team_session_id)
  VALUES (?, ?, ?, 0, ?, 'normal', ?, 0, ?)`;

/** SQL: list team changes (optionally filtered by team_session_id). */
const SQL_LIST_TEAM_CHANGES = `SELECT id, project_id, ts, description, compressed,
       expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
  FROM project_changes
  WHERE project_id = ?
    AND team_session_id IS NOT NULL
    AND deleted_at IS NULL
    AND (? IS NULL OR team_session_id = ?)
  ORDER BY ts ASC`;

/** SQL: promote a set of team rows to normal project changes
 *  (clears team_session_id and leaves compressed at 0 so the new
 *  entries are still picked up by future compression passes). */
const SQL_PROMOTE_TEAM_CHANGES = `UPDATE project_changes
  SET team_session_id = NULL
  WHERE project_id = ?
    AND team_session_id = ?
    AND id IN (SELECT value FROM json_each(?))`;

/** SQL: list team changes from a specific session (used for
 *  listing + counting). */
const SQL_COUNT_TEAM_CHANGES = `SELECT COUNT(*) AS n
  FROM project_changes
  WHERE project_id = ? AND team_session_id IS NOT NULL AND deleted_at IS NULL`;

/**
 * R-MEM-3 — append a shared team change on behalf of a subagent.
 * Returns the new row id. The row is tagged with the subagent's
 * `teamSessionId`; the main agent can later read or promote it.
 */
export function appendTeamChange(
  db: DatabaseT,
  projectId: string,
  description: string,
  teamSessionId: string,
  opts: { ts?: number; expiresAt?: number | null; valueTag?: 'normal' | 'low' } = {},
): number {
  if (!projectId) throw new Error('appendTeamChange: project_id must be non-empty');
  if (!description) throw new Error('appendTeamChange: description must be non-empty');
  if (!teamSessionId) throw new Error('appendTeamChange: teamSessionId must be non-empty');
  const ts = opts.ts ?? Date.now();
  const info = runStatement(db, SQL_INSERT_TEAM_CHANGE, [
    projectId,
    ts,
    description,
    opts.expiresAt ?? null,
    ts,
    teamSessionId,
  ]);
  return typeof info.lastInsertRowid === 'bigint'
    ? Number(info.lastInsertRowid)
    : (info.lastInsertRowid as number);
}

/**
 * R-MEM-3 — list team changes for a project. If `teamSessionId` is
 * provided, only rows from that subagent session are returned;
 * otherwise every team row in the project is returned. Soft-deleted
 * rows are skipped.
 */
export function listTeamChanges(
  db: DatabaseT,
  projectId: string,
  teamSessionId?: string | null,
): ProjectChangeRow[] {
  if (!projectId) throw new Error('listTeamChanges: project_id must be non-empty');
  const sid = teamSessionId ?? null;
  return queryAll<ProjectChangeRow>(
    db,
    SQL_LIST_TEAM_CHANGES,
    [projectId, sid, sid],
    (r) => mapChangeRow(r),
  );
}

/**
 * R-MEM-3 — count team changes for a project (any subagent).
 * Useful for memory/stats and the TUI badge.
 */
export function countTeamChanges(db: DatabaseT, projectId: string): number {
  if (!projectId) throw new Error('countTeamChanges: project_id must be non-empty');
  const row = db.prepare(SQL_COUNT_TEAM_CHANGES).get(projectId) as { n: number };
  return Number(row.n);
}

/**
 * R-MEM-3 — promote a set of team changes to normal project memory.
 * Clears `team_session_id` on the given rows (so they become regular
 * project entries visible to `listProjectChanges` and the
 * consolidation pass). Returns the number of rows updated. Soft-
 * deleted rows are skipped.
 */
export function promoteTeamChanges(
  db: DatabaseT,
  projectId: string,
  teamSessionId: string,
  changeIds: ReadonlyArray<number>,
): number {
  if (!projectId) throw new Error('promoteTeamChanges: project_id must be non-empty');
  if (!teamSessionId) throw new Error('promoteTeamChanges: teamSessionId must be non-empty');
  if (changeIds.length === 0) return 0;
  const info = runStatement(
    db,
    SQL_PROMOTE_TEAM_CHANGES,
    [projectId, teamSessionId, JSON.stringify(changeIds)],
  );
  return info.changes;
}
