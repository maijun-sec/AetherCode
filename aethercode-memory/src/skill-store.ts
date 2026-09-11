/**
 * Skill memory (R-MEM-4).
 *
 * A "skill" is a piece of experience the agent accumulates:
 * a stable `name` + canonical `signature` (the invocation shape),
 * a `description` (the semantics), and `success_count` /
 * `last_success_at` / `last_failure_at` counters. The pair
 * (scope, name) is the natural key — a skill called
 * "git_rebase" can live once per scope.
 *
 * The implementation is small on purpose: it's the store half of
 * the experience model. The retrieval half is `memory/findSkill`
 * (RPC) which does keyword + Jaccard scoring over the `signature`
 * + `description` + `tags` of every live skill in scope.
 *
 * Schema: v8 (see sqlite.ts).
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement, queryAll } from './sqlite.js';

/** A row in the `skills` table. */
export interface SkillRow {
  readonly id: number;
  readonly scope: string;
  readonly name: string;
  readonly signature: string;
  readonly description: string;
  readonly tags: ReadonlyArray<string>;
  readonly source: string;
  readonly success_count: number;
  readonly last_success_at: number | null;
  readonly last_failure_at: number | null;
  readonly ts: number;
}

/** Input for `upsertSkill`. */
export interface SkillInput {
  readonly scope: string;
  readonly name: string;
  readonly signature: string;
  readonly description: string;
  readonly tags?: ReadonlyArray<string>;
  readonly source?: string;
  /** Creation time. Defaults to `Date.now()`. */
  readonly ts?: number;
}

/** SQL: insert a skill, replacing any existing row with the same
 *  (scope, name). */
const SQL_UPSERT_SKILL = `INSERT INTO skills
  (scope, name, signature, description, tags, source, ts)
  VALUES (?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(scope, name) DO UPDATE SET
    signature = excluded.signature,
    description = excluded.description,
    tags = excluded.tags,
    source = excluded.source`;

/** SQL: increment success counter. */
const SQL_INCR_SUCCESS = `UPDATE skills
  SET success_count = success_count + 1,
      last_success_at = ?
  WHERE scope = ? AND name = ?`;

/** SQL: bump last_failure_at. */
const SQL_RECORD_FAILURE = `UPDATE skills
  SET last_failure_at = ?
  WHERE scope = ? AND name = ?`;

/** SQL: read one skill by (scope, name). */
const SQL_GET_SKILL = `SELECT id, scope, name, signature, description, tags,
       source, success_count, last_success_at, last_failure_at, ts
  FROM skills
  WHERE scope = ? AND name = ?`;

/** SQL: list all skills in a scope, ordered by success_count desc
 *  then last_success_at desc. */
const SQL_LIST_SKILLS = `SELECT id, scope, name, signature, description, tags,
       source, success_count, last_success_at, last_failure_at, ts
  FROM skills
  WHERE scope = ?
  ORDER BY success_count DESC, COALESCE(last_success_at, 0) DESC, ts DESC`;

/** SQL: count skills in a scope. */
const SQL_COUNT_SKILLS = `SELECT COUNT(*) AS n FROM skills WHERE scope = ?`;

/** SQL: delete a skill. */
const SQL_DELETE_SKILL = `DELETE FROM skills WHERE scope = ? AND name = ?`;

/** Upsert a skill (R-MEM-4). Replaces the existing row if the
 *  (scope, name) pair already exists; otherwise inserts a fresh
 *  row with success_count = 0. Returns the row id. */
export function upsertSkill(db: DatabaseT, input: SkillInput): number {
  if (!input.scope) throw new Error('upsertSkill: scope must be non-empty');
  if (!input.name) throw new Error('upsertSkill: name must be non-empty');
  if (!input.signature) throw new Error('upsertSkill: signature must be non-empty');
  if (!input.description) throw new Error('upsertSkill: description must be non-empty');
  const ts = input.ts ?? Date.now();
  const tagsJson = JSON.stringify(input.tags ?? []);
  const source = input.source ?? 'system';
  const info = runStatement(db, SQL_UPSERT_SKILL, [
    input.scope,
    input.name,
    input.signature,
    input.description,
    tagsJson,
    source,
    ts,
  ]);
  return typeof info.lastInsertRowid === 'bigint'
    ? Number(info.lastInsertRowid)
    : (info.lastInsertRowid as number);
}

/** Increment success counter (R-MEM-4 auto-capture). Returns the
 *  number of rows updated (0 if the skill doesn't exist). */
export function incrementSkillSuccess(
  db: DatabaseT,
  scope: string,
  name: string,
  atMs: number = Date.now(),
): boolean {
  if (!scope) throw new Error('incrementSkillSuccess: scope must be non-empty');
  if (!name) throw new Error('incrementSkillSuccess: name must be non-empty');
  const info = runStatement(db, SQL_INCR_SUCCESS, [atMs, scope, name]);
  return info.changes > 0;
}

/** Record a failure timestamp on a skill (R-MEM-4 auto-capture).
 *  Returns true if the row was updated. */
export function recordSkillFailure(
  db: DatabaseT,
  scope: string,
  name: string,
  atMs: number = Date.now(),
): boolean {
  if (!scope) throw new Error('recordSkillFailure: scope must be non-empty');
  if (!name) throw new Error('recordSkillFailure: name must be non-empty');
  const info = runStatement(db, SQL_RECORD_FAILURE, [atMs, scope, name]);
  return info.changes > 0;
}

/** Read a single skill by (scope, name). Returns null if not found. */
export function getSkill(db: DatabaseT, scope: string, name: string): SkillRow | null {
  if (!scope) throw new Error('getSkill: scope must be non-empty');
  if (!name) throw new Error('getSkill: name must be non-empty');
  const rows = queryAll<SkillRow>(db, SQL_GET_SKILL, [scope, name], mapSkillRow);
  return rows[0] ?? null;
}

/** List every skill in a scope, most-reliable first. */
export function listSkills(db: DatabaseT, scope: string): SkillRow[] {
  if (!scope) throw new Error('listSkills: scope must be non-empty');
  return queryAll<SkillRow>(db, SQL_LIST_SKILLS, [scope], mapSkillRow);
}

/** Count skills in a scope. */
export function countSkills(db: DatabaseT, scope: string): number {
  if (!scope) throw new Error('countSkills: scope must be non-empty');
  const row = db.prepare(SQL_COUNT_SKILLS).get(scope) as { n: number };
  return Number(row.n);
}

/** Remove a skill by (scope, name). Returns true if a row was deleted. */
export function deleteSkill(db: DatabaseT, scope: string, name: string): boolean {
  if (!scope) throw new Error('deleteSkill: scope must be non-empty');
  if (!name) throw new Error('deleteSkill: name must be non-empty');
  const info = runStatement(db, SQL_DELETE_SKILL, [scope, name]);
  return info.changes > 0;
}

/** Options for `findSkillsByText` (R-MEM-4 keyword + Jaccard search). */
export interface SkillSearchOptions {
  /** When set, restrict the search to this scope. */
  readonly scope?: string;
  /** Top-k results. Default 10. */
  readonly topK?: number;
  /** Minimum Jaccard score in [0, 1]. Default 0. */
  readonly minScore?: number;
}

/** A single hit in `findSkillsByText`. */
export interface SkillSearchHit {
  readonly skill: SkillRow;
  /** Jaccard score in [0, 1] over the union of query tokens
   *  vs the skill's `signature + description + tags` tokens. */
  readonly score: number;
}

/** Find skills by a text query. Tokenises the query + every
 *  skill's signature/description/tags, computes Jaccard
 *  similarity, and returns the top-k matches. */
export function findSkillsByText(
  db: DatabaseT,
  query: string,
  opts: SkillSearchOptions = {},
): SkillSearchHit[] {
  if (typeof query !== 'string' || query.length === 0) {
    throw new Error('findSkillsByText: query must be a non-empty string');
  }
  const queryTokens = tokenize(query);
  if (queryTokens.size === 0) return [];
  // We pull the full list once and score in-process. The skills
  // table is small (hundreds at most) so a brute-force scan
  // beats running SQL LIKE for every term.
  const rows = opts.scope !== undefined ? listSkills(db, opts.scope) : queryAll<SkillRow>(
    db,
    `SELECT id, scope, name, signature, description, tags,
            source, success_count, last_success_at, last_failure_at, ts
     FROM skills`,
    [],
    mapSkillRow,
  );
  const minScore = opts.minScore ?? 0;
  const topK = opts.topK ?? 10;
  const hits: SkillSearchHit[] = [];
  for (const row of rows) {
    const skillTokens = new Set<string>([
      ...tokenize(row.signature),
      ...tokenize(row.description),
      ...row.tags.flatMap((t) => [...tokenize(t)]),
    ]);
    if (skillTokens.size === 0) continue;
    const score = jaccard(queryTokens, skillTokens);
    // Skip zero-score rows. A skill with no shared tokens
    // isn't a meaningful match — including it just dilutes
    // the result and makes "top hit" tests ambiguous.
    if (score <= 0) continue;
    if (score >= minScore) {
      hits.push({ skill: row, score });
    }
  }
  hits.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    return b.skill.success_count - a.skill.success_count;
  });
  return hits.slice(0, topK);
}

/** Tokenise a string into a lowercase set. Splits on
 *  non-letter / non-digit characters. */
function tokenize(s: string): Set<string> {
  const out = new Set<string>();
  const lower = s.toLowerCase();
  let buf = '';
  for (let i = 0; i < lower.length; i += 1) {
    const ch = lower.charCodeAt(i);
    const isAlnum = (ch >= 0x30 && ch <= 0x39) || (ch >= 0x61 && ch <= 0x7a);
    if (isAlnum) {
      buf += lower[i];
    } else if (buf.length > 0) {
      out.add(buf);
      buf = '';
    }
  }
  if (buf.length > 0) out.add(buf);
  return out;
}

function jaccard(a: Set<string>, b: Set<string>): number {
  let inter = 0;
  for (const x of a) {
    if (b.has(x)) inter += 1;
  }
  const union = a.size + b.size - inter;
  return union === 0 ? 0 : inter / union;
}

function mapSkillRow(r: Record<string, unknown>): SkillRow {
  const tagsRaw = r['tags'];
  let tags: string[] = [];
  if (typeof tagsRaw === 'string' && tagsRaw.length > 0) {
    try {
      const parsed = JSON.parse(tagsRaw);
      if (Array.isArray(parsed)) {
        tags = parsed.map((t) => String(t));
      }
    } catch {
      tags = [];
    }
  }
  return {
    id: Number(r['id'] ?? 0),
    scope: String(r['scope'] ?? ''),
    name: String(r['name'] ?? ''),
    signature: String(r['signature'] ?? ''),
    description: String(r['description'] ?? ''),
    tags,
    source: String(r['source'] ?? 'system'),
    success_count: Number(r['success_count'] ?? 0),
    last_success_at:
      r['last_success_at'] === null || r['last_success_at'] === undefined
        ? null
        : Number(r['last_success_at']),
    last_failure_at:
      r['last_failure_at'] === null || r['last_failure_at'] === undefined
        ? null
        : Number(r['last_failure_at']),
    ts: Number(r['ts'] ?? 0),
  };
}
