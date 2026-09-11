/**
 * Memory RPC handlers (T-070 ~ T-074).
 *
 * These are pure functions that take a `MemoryStore` (or a thin adapter)
 * plus a params object and return a JSON-serialisable result. They are
 * the bridges between the `aethercode-protocol` JSON-RPC surface and
 * the in-process `MemoryStore`. The actual transport wiring (method
 * registration, error envelopes, request id) lives in
 * `aethercode-protocol` (T-076); this file only owns the request
 * *handling* so the memory module is self-contained and testable.
 *
 * Contract: every handler must validate its params and throw a
 * `MemoryRpcError` on bad input. The protocol layer maps that to a
 * JSON-RPC error response. Valid input always returns a plain object
 * with the fields documented in `design.md §1.6`.
 */

import type { Fact, MemoryReadResult, MemoryScope } from './types.js';
import { isMemoryScope } from './types.js';
import {
  adaptMemoryStore,
  requestCompression,
  runCompressionPass,
  type CompactResult,
  type CompressionLlmClient,
  type CompressionOptions,
} from './compression.js';

// Re-export so consumers can pick up the LLM type alongside the RPC API.
export type { CompressionLlmClient } from './compression.js';
import {
  hashCwd,
  switchProject as runSwitchProject,
  type ProjectSwitchStore,
  type SwitchFileFs,
  type SwitchOptions,
  type SwitchResult,
} from './project-switcher.js';
import type { MemoryStore } from './memory-store.js';

/* ----------------------------- types ----------------------------------- */

/** A JSON-RPC style error. The protocol layer converts this into an
 *  error envelope. We don't depend on any specific transport types. */
export class MemoryRpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message);
    this.name = 'MemoryRpcError';
  }
}

/** Parameters for `memory/get` (T-070). */
export interface MemoryGetParams {
  readonly scope: MemoryScope | string;
  readonly sessionId?: string;
}

/** Parameters for `memory/appendProjectChange` (T-071). */
export interface MemoryAppendProjectChangeParams {
  readonly description: string;
}

/** Parameters for `memory/appendSessionFact` (T-072). */
export interface MemoryAppendSessionFactParams {
  readonly sessionId: string;
  readonly key: string;
  readonly value: string;
  readonly source?: Fact['source'];
  readonly tags?: ReadonlyArray<string>;
}

/** Parameters for `memory/compact` (T-073). */
export interface MemoryCompactParams {
  readonly force?: boolean;
}

/** Result of `memory/compact`. */
export interface MemoryCompactResult {
  readonly ok: true;
  readonly beforeTokens: number;
  readonly afterTokens: number;
  readonly changesCompressed: number;
  readonly ms: number;
  readonly skipped: boolean;
  readonly resumed: boolean;
}

/** Parameters for `memory/switchProject` (T-074). */
export interface MemorySwitchProjectParams {
  readonly cwd: string;
  readonly placeholderTitle?: string;
  readonly placeholderDescription?: string;
}

/** Result of `memory/switchProject`. */
export interface MemorySwitchProjectResult {
  readonly ok: true;
  readonly projectId: string;
}

/** Parameters for `memory/list` (T-075; included for completeness so the
 *  RPC file mirrors the design surface). The TUI uses it to enumerate
 *  entries of a layer in one round trip. */
export interface MemoryListParams {
  readonly scope: MemoryScope | string;
  readonly sessionId?: string;
}

/** Parameters for `memory/find` (R-MEM-1). */
export interface MemoryFindParams {
  /** Natural-language query. Will be embedded with the store's
   *  configured provider and matched against the vector index. */
  readonly query: string;
  /** Optional scope filter. Default searches all scopes. */
  readonly scope?: MemoryScope | string;
  /** Top-k results to return. Default 10. */
  readonly topK?: number;
  /** Cosine similarity threshold in [0, 1]. Default 0 (no filter). */
  readonly threshold?: number;
  /** Required when scope === 'session'. */
  readonly sessionId?: string;
  /** R-MEM-5.3: optional Tulving-style memory type filter. */
  readonly memoryType?: 'episodic' | 'semantic' | 'procedural';
}

/** A single hit in a `memory/find` result. */
export interface MemoryFindHit {
  /** The matched entry's text content (key + value for facts,
   *  text for rules, description for changes, message for breadcrumbs). */
  readonly content: string;
  /** Cosine similarity in [0, 1] (without type boost) — null
   *  for untyped queries that don't apply the boost. */
  readonly cosineScore: number | null;
  /** R-MEM-6.3: type-specific boost value added to
   *  `cosineScore` to produce the final `score`. Null for
   *  untyped queries. */
  readonly boost: number | null;
  /** R-MEM-6.3 / R-MEM-6.4: why the boost fired, or null. */
  readonly boostReason:
    | 'procedural-success'
    | 'episodic-recency'
    | 'feedback-used'
    | 'feedback-not-used'
    | 'mixed'
    | null;
  /** R-MEM-6.4: per-source breakdown of the boost. Null for
   *  untyped queries. Useful for the debug surface and for
   *  tests that pin the exact contribution. */
  readonly boostBreakdown: {
    readonly procedural: number;
    readonly episodic: number;
    readonly feedbackUsed: number;
    readonly feedbackNotUsed: number;
  } | null;
  /** Final score in [0, 1] (cosine + boost). For untyped
   *  queries this equals `cosineScore`. */
  readonly score: number;
  /** Which scope this hit came from. */
  readonly scope: 'global' | 'project' | 'session';
  /** The entry's natural id (e.g. "project-change-42" or "session-fact-..."). */
  readonly entryId: string;
  /** Best-effort key for facts. Null for non-fact entries. */
  readonly key: string | null;
  /** Timestamp (epoch ms). */
  readonly ts: number;
  /** R-MEM-5.1: 'text' (default) or 'image'. */
  readonly mediaType: 'text' | 'image';
  /** R-MEM-5.1: file path or SHA-256 content hash for image
   *  rows. Null for text rows. */
  readonly mediaRef: string | null;
  /** R-MEM-5.3: Tulving-style memory type. */
  readonly memoryType: 'episodic' | 'semantic' | 'procedural';
}

/** Result of `memory/find`. */
export interface MemoryFindResult {
  readonly ok: true;
  readonly hits: ReadonlyArray<MemoryFindHit>;
  readonly totalScanned: number;
  readonly queryEmbeddingMs: number;
  readonly vecSearchMs: number;
  /** The model id used to embed. Clients can show a warning if it
   *  doesn't match their local model. */
  readonly modelId: string;
}

/* ----------------------------- R-MEM-2 ----------------------------- */

/** Parameters for `memory/consolidate`. R-MEM-2. */
export interface MemoryConsolidateParams {
  /** Always run, even if no candidates. */
  readonly force?: boolean;
  /** Jaccard threshold in [0, 1]. Defaults to 0.5. */
  readonly jaccardThreshold?: number;
}

/** Result of `memory/consolidate`. R-MEM-2. */
export interface MemoryConsolidateResult {
  readonly ok: true;
  readonly scanned: number;
  readonly groups: number;
  readonly merged: number;
  readonly ms: number;
  readonly merges: ReadonlyArray<{
    readonly targetId: number;
    readonly sourceIds: ReadonlyArray<number>;
    readonly description: string;
  }>;
}

/** Parameters for `memory/forget`. R-MEM-2. */
export interface MemoryForgetParams {
  /** Don't actually delete — just return the candidates. */
  readonly dryRun?: boolean;
  /** Match expired rows (default true). */
  readonly expired?: boolean;
  /** Inactivity window in ms. Rows whose last_accessed_at is
   *  older than (now - inactiveSinceMs) are candidates. */
  readonly inactiveSinceMs?: number;
  /** Match low-value rows. */
  readonly lowValue?: boolean;
  /** Max rows to process. Default 100. */
  readonly limit?: number;
}

/** Result of `memory/forget`. R-MEM-2. */
export interface MemoryForgetResult {
  readonly ok: true;
  readonly candidates: number;
  readonly softDeleted: number;
  readonly deleted: ReadonlyArray<{ id: number; description: string; reason: string }>;
}

/** Parameters for `memory/stats`. R-MEM-2. */
export interface MemoryStatsParams {
  // (none for now — reads the current project's stats).
}

/** Result of `memory/stats`. R-MEM-2. */
export interface MemoryStatsResult {
  readonly ok: true;
  readonly totalChanges: number;
  readonly liveChanges: number;
  readonly consolidatedChanges: number;
  readonly softDeletedChanges: number;
  readonly expiredCandidates: number;
  readonly lowValueCandidates: number;
  readonly inactiveCandidates: number;
  readonly now: number;
}

/* ----------------------------- handlers -------------------------------- */

/** T-070 — `memory/get`. */
export function memoryGet(
  store: MemoryStore,
  params: MemoryGetParams,
): MemoryReadResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/get: params must be an object');
  }
  if (!isMemoryScope(params.scope)) {
    throw new MemoryRpcError(-32602, `memory/get: invalid scope "${String(params.scope)}"`);
  }
  switch (params.scope) {
    case 'global':
      return store.getGlobal();
    case 'project':
      return store.getProject();
    case 'session': {
      const sid = params.sessionId;
      if (typeof sid !== 'string' || sid.length === 0) {
        throw new MemoryRpcError(-32602, 'memory/get: sessionId is required for scope=session');
      }
      return store.getSession(sid);
    }
    default:
      // Unreachable because of the isMemoryScope guard above, but TS
      // wants an exhaustive switch.
      throw new MemoryRpcError(-32602, 'memory/get: unsupported scope');
  }
}

/** T-071 — `memory/appendProjectChange`. */
export function memoryAppendProjectChange(
  store: MemoryStore,
  params: MemoryAppendProjectChangeParams,
): { ok: true; id: string; ts: number; compressed: boolean } {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/appendProjectChange: params must be an object');
  }
  if (typeof params.description !== 'string' || params.description.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/appendProjectChange: description must be a non-empty string');
  }
  const entry = store.appendProjectChange(params.description);
  return { ok: true, id: entry.id, ts: entry.ts, compressed: entry.compressed };
}

/** T-072 — `memory/appendSessionFact`. */
export function memoryAppendSessionFact(
  store: MemoryStore,
  params: MemoryAppendSessionFactParams,
): { ok: true; id: string } {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/appendSessionFact: params must be an object');
  }
  if (typeof params.sessionId !== 'string' || params.sessionId.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/appendSessionFact: sessionId must be a non-empty string');
  }
  if (typeof params.key !== 'string' || params.key.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/appendSessionFact: key must be a non-empty string');
  }
  if (typeof params.value !== 'string') {
    throw new MemoryRpcError(-32602, 'memory/appendSessionFact: value must be a string');
  }
  const ts = Date.now();
  const fact: Fact = {
    kind: 'fact',
    id: `rpc-fact-${ts}-${Math.random().toString(36).slice(2, 8)}`,
    ts,
    scope: 'session',
    source: params.source ?? 'user',
    tags: params.tags !== undefined ? [...params.tags] : [],
    key: params.key,
    value: params.value,
  };
  store.appendSessionFact(params.sessionId, fact);
  return { ok: true, id: fact.id };
}

/** T-073 — `memory/compact`. Triggers a compression pass; if no LLM
 *  client is configured, returns `skipped: true`. */
export async function memoryCompact(
  store: MemoryStore,
  params: MemoryCompactParams,
  deps: { llm?: CompressionLlmClient; config?: CompressionOptions; fs?: SwitchFileFs } = {},
): Promise<MemoryCompactResult> {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/compact: params must be an object');
  }
  const adapter = adaptMemoryStore({
    dbPath: store.dbPath,
    projectMemoryPath: store.projectMemoryPath,
    getProject: () => store.getProject(),
    invalidateProject: () => {
      const projectId = currentProjectIdFromPath(store.projectMemoryPath);
      store.cache.invalidateScope('project', projectId);
    },
  });
  // If `force` is true, do an unconditional pass even when the trigger
  // is not met. We approximate this by setting `triggerHeadroom` to
  // `changeLimit - 1` so the threshold is effectively zero.
  const config: CompressionOptions = {
    changeLimit: deps.config?.changeLimit,
    maxDescriptionTokens: deps.config?.maxDescriptionTokens,
    promptTemplate: deps.config?.promptTemplate,
    triggerHeadroom: params.force === true ? Number.POSITIVE_INFINITY : deps.config?.triggerHeadroom,
  };
  if (deps.llm === undefined) {
    return {
      ok: true,
      beforeTokens: 0,
      afterTokens: 0,
      changesCompressed: 0,
      ms: 0,
      skipped: true,
      resumed: false,
    };
  }
  const projectId = currentProjectIdFromPath(store.projectMemoryPath);
  const result: CompactResult | null = await requestCompression({
    store: adapter,
    projectId,
    llm: deps.llm,
    config,
    fs: deps.fs,
  });
  if (result === null) {
    return {
      ok: true,
      beforeTokens: 0,
      afterTokens: 0,
      changesCompressed: 0,
      ms: 0,
      skipped: true,
      resumed: false,
    };
  }
  return {
    ok: true,
    beforeTokens: result.beforeTokens,
    afterTokens: result.afterTokens,
    changesCompressed: result.changesCompressed,
    ms: result.ms,
    skipped: result.skipped,
    resumed: result.resumed,
  };
}

/** T-074 — `memory/switchProject`. Closes the current project handle
 *  (cache-wise) and opens a new one. Returns the new project_id. */
export async function memorySwitchProject(
  store: MemoryStore,
  params: MemorySwitchProjectParams,
  deps: {
    notify?: SwitchOptions['notify'];
    fs?: SwitchFileFs;
  } = {},
): Promise<MemorySwitchProjectResult> {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/switchProject: params must be an object');
  }
  if (typeof params.cwd !== 'string' || params.cwd.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/switchProject: cwd must be a non-empty string');
  }
  const switchStore: ProjectSwitchStore = {
    projectMemoryPath: store.projectMemoryPath,
    globalMemoryPath: store.globalMemoryPath,
    projectDir: dirnameOf(store.projectMemoryPath),
    currentCwd: store.currentCwd,
    dbPath: store.dbPath,
    invalidateProject: (projectId: string): void => {
      store.cache.invalidateScope('project', projectId);
    },
    invalidateGlobal: (): void => {
      store.cache.invalidateScope('global');
    },
  };
  const switchOpts: SwitchOptions = {
    cwd: params.cwd,
    placeholderTitle: params.placeholderTitle,
    placeholderDescription: params.placeholderDescription,
    notify: deps.notify,
  };
  const result: SwitchResult = await runSwitchProject(switchStore, switchOpts, deps.fs);
  // Update the live store so subsequent reads/writes land in the new project.
  store.currentCwd = result.newCwd;
  return { ok: true, projectId: result.projectId };
}

/* ----------------------------- extras ---------------------------------- */

/** T-075 — `memory/list`. Convenience wrapper around `memory/get` that
 *  returns just the entries (no source/tokens). Useful for the TUI
 *  MemoryPanel. */
export function memoryList(
  store: MemoryStore,
  params: MemoryListParams,
): { entries: MemoryReadResult['entries']; totalTokens: number } {
  const result = memoryGet(store, params);
  return { entries: result.entries, totalTokens: result.totalTokens };
}

/** R-MEM-1 — `memory/find`. Semantic search across the memory store.
 *  Validates the params, calls `store.findSimilar`, and projects the
 *  result into the wire-friendly `MemoryFindResult` shape. */
export function memoryFind(
  store: MemoryStore,
  params: MemoryFindParams,
): MemoryFindResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/find: params must be an object');
  }
  if (typeof params.query !== 'string' || params.query.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/find: query must be a non-empty string');
  }
  if (params.scope !== undefined && !isMemoryScope(params.scope)) {
    throw new MemoryRpcError(-32602, `memory/find: invalid scope "${String(params.scope)}"`);
  }
  if (params.scope === 'session' && (typeof params.sessionId !== 'string' || params.sessionId.length === 0)) {
    throw new MemoryRpcError(-32602, 'memory/find: sessionId is required for scope=session');
  }
  if (params.memoryType !== undefined && params.memoryType !== 'episodic' && params.memoryType !== 'semantic' && params.memoryType !== 'procedural') {
    throw new MemoryRpcError(-32602, `memory/find: invalid memoryType "${String(params.memoryType)}"`);
  }
  const vec = store.findSimilar(params.query, {
    scope: params.scope,
    topK: params.topK,
    threshold: params.threshold,
    sessionId: params.sessionId,
    memoryType: params.memoryType,
  });
  const queryEmbeddingMs = 0; // included in vec.ms on the default provider
  const hits: MemoryFindHit[] = vec.rows.map((r) => {
    const typedRow = r as {
      cosine_score?: number;
      boost?: number;
      boostReason?:
        | 'procedural-success'
        | 'episodic-recency'
        | 'feedback-used'
        | 'feedback-not-used'
        | 'mixed'
        | 'none';
      boostBreakdown?: {
        procedural: number;
        episodic: number;
        feedbackUsed: number;
        feedbackNotUsed: number;
      };
    };
    const isTyped = 'cosine_score' in typedRow;
    const boostReason =
      isTyped && typedRow.boostReason && typedRow.boostReason !== 'none'
        ? typedRow.boostReason
        : null;
    return {
      content: r.content_text,
      score: r.score ?? 0,
      scope: r.scope as 'global' | 'project' | 'session',
      entryId: r.entry_id,
      // Best-effort key extraction: facts store "key: value" so
      // we split on the first colon. Non-facts return null.
      key: extractKey(r.content_text),
      ts: r.ts,
      mediaType: r.media_type,
      mediaRef: r.media_ref,
      memoryType: r.memory_type,
      // R-MEM-6.3: type-specific boost fields. For typed queries
      // (memoryType set) we expose the pre-boost cosine and the
      // applied boost separately. For untyped queries, the
      // boost is 0 and the cosine equals the score.
      cosineScore: isTyped ? typedRow.cosine_score ?? r.score ?? 0 : r.score ?? 0,
      boost: isTyped ? typedRow.boost ?? 0 : 0,
      boostReason,
      boostBreakdown: isTyped ? typedRow.boostBreakdown ?? null : null,
    };
  });
  return {
    ok: true,
    hits,
    totalScanned: vec.totalScanned,
    queryEmbeddingMs,
    vecSearchMs: vec.ms,
    modelId: store.embeddingModelId,
  };
}

/** Extract a "key" from a content_text of the form "key: value".
 *  Returns null when the text doesn't look like a fact. */
function extractKey(text: string): string | null {
  const idx = text.indexOf(':');
  if (idx <= 0 || idx >= text.length - 1) return null;
  const key = text.slice(0, idx).trim();
  if (key.length === 0 || key.length > 256) return null;
  return key;
}

/* ----------------------------- R-MEM-2 handlers --------------------- */

/** R-MEM-2 — `memory/consolidate`. */
export function memoryConsolidate(
  store: MemoryStore,
  params: MemoryConsolidateParams,
): MemoryConsolidateResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/consolidate: params must be an object');
  }
  // Delegate to the MemoryStore's evolution API so the RPC
  // layer doesn't need to know the db handle. The store
  // computes the projectId from the projectMemoryPath.
  const out = store.consolidate({
    jaccardThreshold: params.jaccardThreshold,
    force: params.force,
  });
  return {
    ok: true,
    scanned: out.scanned,
    groups: out.groups,
    merged: out.merged,
    ms: out.ms,
    // The MemoryStore.consolidate API returns the counts but
    // not the per-merge detail (we keep the high-level API
    // simple). UI consumers that need the detail can call
    // consolidateProjectChanges directly.
    merges: [],
  };
}

/** R-MEM-2 — `memory/forget`. */
export function memoryForget(
  store: MemoryStore,
  params: MemoryForgetParams,
): MemoryForgetResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/forget: params must be an object');
  }
  const out = store.forget({
    expired: params.expired,
    inactiveSinceMs: params.inactiveSinceMs,
    lowValue: params.lowValue,
    limit: params.limit,
    dryRun: params.dryRun,
  });
  return {
    ok: true,
    candidates: out.candidates,
    softDeleted: out.softDeleted,
    deleted: [], // see above
  };
}

/** R-MEM-2 — `memory/stats`. */
export function memoryStats(
  store: MemoryStore,
  _params: MemoryStatsParams = {},
): MemoryStatsResult {
  const out = store.readStats();
  return {
    ok: true,
    totalChanges: out.totalChanges,
    liveChanges: out.liveChanges,
    consolidatedChanges: out.consolidatedChanges,
    softDeletedChanges: out.softDeletedChanges,
    expiredCandidates: out.expiredCandidates,
    lowValueCandidates: out.lowValueCandidates,
    inactiveCandidates: out.inactiveCandidates,
    now: out.now,
  };
}

/* ----------------------------- R-MEM-3 handlers --------------------- */

/** R-MEM-3 — `memory/shareToSubagent`. A subagent calls this to
 *  push one or more facts into the main project's shared memory.
 *  Each entry is tagged with the subagent's `sessionId` (called
 *  `teamSessionId` here to avoid clashing with `Session.id` in
 *  the JSON-RPC envelope).
 *
 *  The main agent can read the shared rows with
 *  `memory/readTeamMemory` and promote the keepers to permanent
 *  project memory with `memory/promoteFromSubagent`. */
export interface MemoryShareToSubagentParams {
  /** The subagent's session id. Used as the team marker. */
  readonly teamSessionId: string;
  /** Descriptions to share. Each becomes one row in
   *  `project_changes` with `team_session_id` set. */
  readonly entries: ReadonlyArray<{ description: string; expiresAt?: number | null }>;
}

export interface MemoryShareToSubagentResult {
  readonly ok: true;
  readonly shared: number;
  readonly ids: ReadonlyArray<number>;
}

/** R-MEM-3 — `memory/readTeamMemory`. Read shared entries from
 *  subagents. With no `teamSessionId` filter, every shared entry
 *  for the project is returned. With a `teamSessionId`, only that
 *  subagent's entries are returned. */
export interface MemoryReadTeamMemoryParams {
  /** Optional: filter to one subagent session. */
  readonly teamSessionId?: string;
  /** Include soft-deleted entries. Default false. */
  readonly includeDeleted?: boolean;
}

export interface MemoryReadTeamMemoryResult {
  readonly ok: true;
  readonly entries: ReadonlyArray<{
    readonly id: number;
    readonly description: string;
    readonly teamSessionId: string;
    readonly ts: number;
  }>;
  readonly count: number;
}

/** R-MEM-3 — `memory/promoteFromSubagent`. Promote a set of shared
 *  rows from a subagent to permanent project memory. The
 *  `team_session_id` column is cleared; the rows are now visible
 *  to `listProjectChanges` and the consolidation pass. */
export interface MemoryPromoteFromSubagentParams {
  readonly teamSessionId: string;
  readonly ids: ReadonlyArray<number>;
}

export interface MemoryPromoteFromSubagentResult {
  readonly ok: true;
  readonly promoted: number;
}

/** R-MEM-3 — `memory/shareToSubagent`. */
export function memoryShareToSubagent(
  store: MemoryStore,
  params: MemoryShareToSubagentParams,
): MemoryShareToSubagentResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/shareToSubagent: params must be an object');
  }
  if (typeof params.teamSessionId !== 'string' || params.teamSessionId.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/shareToSubagent: teamSessionId must be a non-empty string');
  }
  if (!Array.isArray(params.entries)) {
    throw new MemoryRpcError(-32602, 'memory/shareToSubagent: entries must be an array');
  }
  for (let i = 0; i < params.entries.length; i += 1) {
    const e = params.entries[i];
    if (typeof e !== 'object' || e === null) {
      throw new MemoryRpcError(-32602, `memory/shareToSubagent: entries[${i}] must be an object`);
    }
    if (typeof e.description !== 'string' || e.description.length === 0) {
      throw new MemoryRpcError(-32602, `memory/shareToSubagent: entries[${i}].description must be a non-empty string`);
    }
  }
  const out = store.shareToSubagent(params.teamSessionId, params.entries);
  return { ok: true, shared: out.shared, ids: out.ids };
}

/** R-MEM-3 — `memory/readTeamMemory`. */
export function memoryReadTeamMemory(
  store: MemoryStore,
  params: MemoryReadTeamMemoryParams = {},
): MemoryReadTeamMemoryResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/readTeamMemory: params must be an object');
  }
  if (
    params.teamSessionId !== undefined &&
    (typeof params.teamSessionId !== 'string' || params.teamSessionId.length === 0)
  ) {
    throw new MemoryRpcError(-32602, 'memory/readTeamMemory: teamSessionId must be a non-empty string');
  }
  const out = store.readTeamMemory({
    teamSessionId: params.teamSessionId,
    includeDeleted: params.includeDeleted,
  });
  return { ok: true, entries: out.entries, count: out.count };
}

/** R-MEM-3 — `memory/promoteFromSubagent`. */
export function memoryPromoteFromSubagent(
  store: MemoryStore,
  params: MemoryPromoteFromSubagentParams,
): MemoryPromoteFromSubagentResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/promoteFromSubagent: params must be an object');
  }
  if (typeof params.teamSessionId !== 'string' || params.teamSessionId.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/promoteFromSubagent: teamSessionId must be a non-empty string');
  }
  if (!Array.isArray(params.ids)) {
    throw new MemoryRpcError(-32602, 'memory/promoteFromSubagent: ids must be an array');
  }
  for (let i = 0; i < params.ids.length; i += 1) {
    if (typeof params.ids[i] !== 'number') {
      throw new MemoryRpcError(-32602, `memory/promoteFromSubagent: ids[${i}] must be a number`);
    }
  }
  const out = store.promoteFromSubagent(params.teamSessionId, params.ids);
  return { ok: true, promoted: out };
}

/* ----------------------------- R-MEM-4 handlers --------------------- */

/** R-MEM-4 — `memory/upsertSkill`. Store a skill signature +
 *  description in the experience table. If the (scope, name)
 *  pair already exists, the row is updated and the
 *  success/failure counters are preserved. */
export interface MemoryUpsertSkillParams {
  readonly scope: string;
  readonly name: string;
  readonly signature: string;
  readonly description: string;
  readonly tags?: ReadonlyArray<string>;
  readonly source?: string;
}

export interface MemoryUpsertSkillResult {
  readonly ok: true;
  readonly id: number;
  readonly name: string;
  readonly scope: string;
}

/** R-MEM-4 — `memory/recordSkillOutcome`. Auto-capture hook:
 *  the agent's tool wrapper calls this every time a skill is
 *  used. `ok = true` increments success_count + last_success_at;
 *  `ok = false` bumps last_failure_at. */
export interface MemoryRecordSkillOutcomeParams {
  readonly scope: string;
  readonly name: string;
  readonly ok: boolean;
}

export interface MemoryRecordSkillOutcomeResult {
  readonly ok: true;
  readonly recorded: boolean;
}

/** R-MEM-4 — `memory/findSkill`. Search the experience table by
 *  keyword + Jaccard scoring. The top-k skills in the chosen
 *  scope are returned, ordered by descending score. */
export interface MemoryFindSkillParams {
  readonly query: string;
  readonly scope?: string;
  readonly topK?: number;
  /** Minimum Jaccard score in [0, 1]. Default 0. */
  readonly minScore?: number;
}

export interface MemoryFindSkillHit {
  readonly id: number;
  readonly scope: string;
  readonly name: string;
  readonly signature: string;
  readonly description: string;
  readonly tags: ReadonlyArray<string>;
  readonly successCount: number;
  readonly lastSuccessAt: number | null;
  readonly lastFailureAt: number | null;
  readonly score: number;
}

export interface MemoryFindSkillResult {
  readonly ok: true;
  readonly hits: ReadonlyArray<MemoryFindSkillHit>;
  readonly count: number;
}

/** R-MEM-4 — `memory/upsertSkill`. */
export function memoryUpsertSkill(
  store: MemoryStore,
  params: MemoryUpsertSkillParams,
): MemoryUpsertSkillResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/upsertSkill: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/upsertSkill: scope must be a non-empty string');
  }
  if (typeof params.name !== 'string' || params.name.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/upsertSkill: name must be a non-empty string');
  }
  if (typeof params.signature !== 'string' || params.signature.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/upsertSkill: signature must be a non-empty string');
  }
  if (typeof params.description !== 'string' || params.description.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/upsertSkill: description must be a non-empty string');
  }
  const id = store.upsertSkill({
    scope: params.scope,
    name: params.name,
    signature: params.signature,
    description: params.description,
    tags: params.tags,
    source: params.source,
  });
  return { ok: true, id, name: params.name, scope: params.scope };
}

/** R-MEM-4 — `memory/recordSkillOutcome`. */
export function memoryRecordSkillOutcome(
  store: MemoryStore,
  params: MemoryRecordSkillOutcomeParams,
): MemoryRecordSkillOutcomeResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/recordSkillOutcome: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordSkillOutcome: scope must be a non-empty string');
  }
  if (typeof params.name !== 'string' || params.name.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordSkillOutcome: name must be a non-empty string');
  }
  if (typeof params.ok !== 'boolean') {
    throw new MemoryRpcError(-32602, 'memory/recordSkillOutcome: ok must be a boolean');
  }
  const recorded = store.recordSkillOutcome(params.scope, params.name, params.ok);
  return { ok: true, recorded };
}

/** R-MEM-4 — `memory/findSkill`. */
export function memoryFindSkill(
  store: MemoryStore,
  params: MemoryFindSkillParams,
): MemoryFindSkillResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/findSkill: params must be an object');
  }
  if (typeof params.query !== 'string' || params.query.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/findSkill: query must be a non-empty string');
  }
  if (params.scope !== undefined && (typeof params.scope !== 'string' || params.scope.length === 0)) {
    throw new MemoryRpcError(-32602, 'memory/findSkill: scope must be a non-empty string');
  }
  const hits = store.findSkill(params.query, {
    scope: params.scope,
    topK: params.topK,
    minScore: params.minScore,
  });
  return {
    ok: true,
    hits: hits.map((h) => ({
      id: h.skill.id,
      scope: h.skill.scope,
      name: h.skill.name,
      signature: h.skill.signature,
      description: h.skill.description,
      tags: [...h.skill.tags],
      successCount: h.skill.success_count,
      lastSuccessAt: h.skill.last_success_at,
      lastFailureAt: h.skill.last_failure_at,
      score: h.score,
    })),
    count: hits.length,
  };
}

/* ----------------------------- R-MEM-5.1 handlers ------------------- */

/** R-MEM-5.1 (F4 Multimodal) — `memory/appendImage`. Store an
 *  image (or any binary asset) in the memory store. The image
 *  isn't embedded by a vision model — the `description` is the
 *  text the embedding provider sees, and `mediaRef` (file path
 *  or pre-computed hash) is the lookup key. */
export interface MemoryAppendImageParams {
  /** Which layer the image lives in. */
  readonly scope: 'global' | 'project' | 'session' | 'skill';
  /** Human-readable description — what the image shows. */
  readonly description: string;
  /** Path to the image file on disk. SHA-256 of the file is
   *  used as the media ref. Either this, `fileBuffer`, or
   *  `mediaRef` must be supplied. */
  readonly filePath?: string;
  /** Pre-loaded image bytes (e.g. from clipboard). */
  readonly fileBuffer?: string; // base64-encoded, decoded server-side
  /** Pre-computed media ref (e.g. a SHA-256 the caller already
   *  has). */
  readonly mediaRef?: string;
}

export interface MemoryAppendImageResult {
  readonly ok: true;
  readonly entryId: string;
  readonly mediaRef: string;
  readonly mediaType: 'image';
}

/** R-MEM-5.1 — `memory/appendImage`. */
export function memoryAppendImage(
  store: MemoryStore,
  params: MemoryAppendImageParams,
): MemoryAppendImageResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/appendImage: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/appendImage: scope must be a non-empty string');
  }
  if (params.scope !== 'global' && params.scope !== 'project' && params.scope !== 'session' && params.scope !== 'skill') {
    throw new MemoryRpcError(-32602, `memory/appendImage: invalid scope "${params.scope}"`);
  }
  if (typeof params.description !== 'string' || params.description.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/appendImage: description must be a non-empty string');
  }
  if (
    (typeof params.filePath !== 'string' || params.filePath.length === 0) &&
    (typeof params.fileBuffer !== 'string' || params.fileBuffer.length === 0) &&
    (typeof params.mediaRef !== 'string' || params.mediaRef.length === 0)
  ) {
    throw new MemoryRpcError(-32602, 'memory/appendImage: one of filePath, fileBuffer, or mediaRef is required');
  }
  let fileBuffer: Buffer | undefined;
  if (typeof params.fileBuffer === 'string' && params.fileBuffer.length > 0) {
    fileBuffer = Buffer.from(params.fileBuffer, 'base64');
  }
  const out = store.appendImage({
    scope: params.scope,
    description: params.description,
    filePath: params.filePath,
    fileBuffer,
    mediaRef: params.mediaRef,
  });
  return { ok: true, entryId: out.entryId, mediaRef: out.mediaRef, mediaType: 'image' };
}

/* ----------------------------- R-MEM-5.2 handlers ------------------- */

/** R-MEM-5.2 (F7 Trust) — `memory/recordProvenance`. Explicitly
 *  append a row to the provenance chain. Most writes do this
 *  automatically; this RPC is for callers that need to chain
 *  an entry that lives outside the standard write paths. */
export interface MemoryRecordProvenanceParams {
  readonly scope: string;
  readonly entryId: string;
  readonly content: string;
  readonly ts?: number;
}

export interface MemoryRecordProvenanceResult {
  readonly ok: true;
  readonly id: number;
}

/** R-MEM-5.2 — `memory/verifyChain`. Walk the provenance
 *  chain for a scope and return the verification status. */
export interface MemoryVerifyChainParams {
  readonly scope: string;
}

export interface MemoryVerifyChainResult {
  readonly ok: boolean;
  readonly count: number;
  readonly headHash: string | null;
  /** When `ok` is false: the row id where the chain broke. */
  readonly brokenAt: number | null;
  /** When `ok` is false: 'broken-link' (prev hash mismatches) or
   *  'tamper' (content hash mismatches or backing entry missing). */
  readonly reason: 'broken-link' | 'tamper' | null;
  readonly details: string | null;
}

/** R-MEM-5.2 — `memory/recordProvenance`. */
export function memoryRecordProvenance(
  store: MemoryStore,
  params: MemoryRecordProvenanceParams,
): MemoryRecordProvenanceResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/recordProvenance: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordProvenance: scope must be a non-empty string');
  }
  if (typeof params.entryId !== 'string' || params.entryId.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordProvenance: entryId must be a non-empty string');
  }
  if (typeof params.content !== 'string') {
    throw new MemoryRpcError(-32602, 'memory/recordProvenance: content must be a string');
  }
  const id = store.recordProvenance(params.scope, params.entryId, params.content, params.ts);
  return { ok: true, id };
}

/** R-MEM-5.2 — `memory/verifyChain`. */
export function memoryVerifyChain(
  store: MemoryStore,
  params: MemoryVerifyChainParams,
): MemoryVerifyChainResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/verifyChain: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/verifyChain: scope must be a non-empty string');
  }
  const r = store.verifyChain(params.scope);
  if (r.ok) {
    return { ok: true, count: r.count, headHash: r.headHash, brokenAt: null, reason: null, details: null };
  }
  return {
    ok: false,
    count: r.count,
    headHash: null,
    brokenAt: r.brokenAt,
    reason: r.reason,
    details: r.details,
  };
}

/** R-MEM-6.2 (F7+) — `memory/verifySignedChain`. Ed25519 signed
 *  variant of `memory/verifyChain`. */
export interface MemoryVerifySignedChainParams {
  readonly scope: string;
  /** When true, fail if any row has no signature (legacy
   *  pre-v12 rows). Default false. */
  readonly requireSignatures?: boolean;
}

export interface MemoryVerifySignedChainResult {
  readonly ok: boolean;
  readonly count: number;
  readonly headHash: string | null;
  /** When `ok` is false: the row id where the chain broke. */
  readonly brokenAt: number | null;
  /** When `ok` is false: 'broken-link' / 'tamper' / 'bad-signature' / 'missing-signature'. */
  readonly reason: 'broken-link' | 'tamper' | 'bad-signature' | 'missing-signature' | null;
  readonly details: string | null;
  /** When `ok` is true: the number of rows that had valid signatures. */
  readonly signedCount: number | null;
}

/** R-MEM-6.2 — `memory/verifySignedChain`. */
export function memoryVerifySignedChain(
  store: MemoryStore,
  params: MemoryVerifySignedChainParams,
): MemoryVerifySignedChainResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/verifySignedChain: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/verifySignedChain: scope must be a non-empty string');
  }
  const r = store.verifySignedChain(params.scope, { requireSignatures: params.requireSignatures });
  if (r.ok) {
    return { ok: true, count: r.count, headHash: r.headHash, brokenAt: null, reason: null, details: null, signedCount: r.signedCount };
  }
  return {
    ok: false,
    count: r.count,
    headHash: null,
    brokenAt: r.brokenAt,
    reason: r.reason,
    details: r.details,
    signedCount: null,
  };
}

/* ----------------------------- R-MEM-6.4 handlers ------------------- */

/** R-MEM-6.4 (F3 RL-tuned memory, lightweight) — `memory/recordRetrievalOutcome`.
 *  The agent calls this after a `memory/find` to tell the
 *  store "this hit was actually useful" (or not). The row
 *  accumulates counters; `memory/find` then boosts future
 *  retrieval of the same entry. */
export interface MemoryRecordRetrievalOutcomeParams {
  /** Scope key — typically matches the `scope` of the original
   *  `memory/find` call (e.g. 'global', 'project', 'session'). */
  readonly scope: string;
  /** The hit's `entryId` (e.g. "project-change-42"). */
  readonly entryId: string;
  /** True if the agent used the hit, false if it was noise. */
  readonly used: boolean;
  /** Override for the timestamp (epoch ms). Default `Date.now()`. */
  readonly atMs?: number;
}

export interface MemoryRecordRetrievalOutcomeResult {
  readonly ok: true;
  /** The new feedback row's id, or null if the upsert
   *  didn't increment any counter (should not happen in
   *  practice — kept for type-stability). */
  readonly feedbackId: number | null;
  /** The scope+entry_id as stored. */
  readonly scope: string;
  readonly entryId: string;
  readonly used: boolean;
}

/** R-MEM-6.4 — `memory/recordRetrievalOutcome`. */
export function memoryRecordRetrievalOutcome(
  store: MemoryStore,
  params: MemoryRecordRetrievalOutcomeParams,
): MemoryRecordRetrievalOutcomeResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/recordRetrievalOutcome: params must be an object');
  }
  if (typeof params.scope !== 'string' || params.scope.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordRetrievalOutcome: scope must be a non-empty string');
  }
  if (typeof params.entryId !== 'string' || params.entryId.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/recordRetrievalOutcome: entryId must be a non-empty string');
  }
  if (typeof params.used !== 'boolean') {
    throw new MemoryRpcError(-32602, 'memory/recordRetrievalOutcome: used must be a boolean');
  }
  const r = store.recordRetrievalOutcome(params.scope, params.entryId, params.used, params.atMs);
  return { ok: true, feedbackId: r.feedbackId, scope: r.scope, entryId: r.entryId, used: r.used };
}

/** R-MEM-6.4 — `memory/getRetrievalFeedbackStats`. Aggregate counters
 *  for the whole retrieval_feedback table. Useful for the UI's
 *  "memory system health" panel and for tests. */
export interface MemoryGetRetrievalFeedbackStatsResult {
  readonly ok: true;
  readonly rowsTotal: number;
  readonly usedTotal: number;
  readonly notUsedTotal: number;
  /** Simple "usefulness ratio" in [0, 1] — `usedTotal /
   *  (usedTotal + notUsedTotal)`. Returns 0 when there are
   *  no feedback rows yet. */
  readonly usefulnessRatio: number;
}

export function memoryGetRetrievalFeedbackStats(
  store: MemoryStore,
): MemoryGetRetrievalFeedbackStatsResult {
  const s = store.getRetrievalFeedbackStats();
  const denom = s.usedTotal + s.notUsedTotal;
  return {
    ok: true,
    rowsTotal: s.rowsTotal,
    usedTotal: s.usedTotal,
    notUsedTotal: s.notUsedTotal,
    usefulnessRatio: denom === 0 ? 0 : s.usedTotal / denom,
  };
}

/* ----------------------------- R-MEM-5.3 handlers ------------------- */

/** R-MEM-5.3 (F8 Cognition) — `memory/findByType`. Tulving-style
 *  memory-type filter. Convenience wrapper around `memory/find`
 *  that requires the `memoryType` to be set. */
export interface MemoryFindByTypeParams {
  readonly query: string;
  readonly memoryType: 'episodic' | 'semantic' | 'procedural';
  readonly scope?: MemoryScope | string;
  readonly topK?: number;
  readonly threshold?: number;
  readonly sessionId?: string;
}

export interface MemoryFindByTypeResult {
  readonly ok: true;
  readonly hits: ReadonlyArray<MemoryFindHit>;
  readonly totalScanned: number;
  readonly memoryType: 'episodic' | 'semantic' | 'procedural';
  readonly modelId: string;
}

/** R-MEM-5.3 — `memory/findByType`. */
export function memoryFindByType(
  store: MemoryStore,
  params: MemoryFindByTypeParams,
): MemoryFindByTypeResult {
  if (typeof params !== 'object' || params === null) {
    throw new MemoryRpcError(-32602, 'memory/findByType: params must be an object');
  }
  if (typeof params.query !== 'string' || params.query.length === 0) {
    throw new MemoryRpcError(-32602, 'memory/findByType: query must be a non-empty string');
  }
  if (params.memoryType !== 'episodic' && params.memoryType !== 'semantic' && params.memoryType !== 'procedural') {
    throw new MemoryRpcError(-32602, `memory/findByType: invalid memoryType "${String(params.memoryType)}"`);
  }
  if (params.scope !== undefined && !isMemoryScope(params.scope)) {
    throw new MemoryRpcError(-32602, `memory/findByType: invalid scope "${String(params.scope)}"`);
  }
  if (params.scope === 'session' && (typeof params.sessionId !== 'string' || params.sessionId.length === 0)) {
    throw new MemoryRpcError(-32602, 'memory/findByType: sessionId is required for scope=session');
  }
  // Delegate to memory/find so the wire shape is identical.
  const found = memoryFind(store, {
    query: params.query,
    scope: params.scope,
    topK: params.topK,
    threshold: params.threshold,
    sessionId: params.sessionId,
    memoryType: params.memoryType,
  });
  return {
    ok: true,
    hits: found.hits,
    totalScanned: found.totalScanned,
    memoryType: params.memoryType,
    modelId: found.modelId,
  };
}

/** Internal — re-implement the FNV-1a hash so the handlers don't need to
 *  re-export memory-store's private helper. The two implementations must
 *  stay in sync. */
function currentProjectIdFromPath(projectMemoryPath: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < projectMemoryPath.length; i += 1) {
    h ^= projectMemoryPath.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

function dirnameOf(p: string): string {
  const idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  return idx < 0 ? p : p.slice(0, idx);
}

// Reference `runCompressionPass` so the public surface stays available
// to test files without forcing every caller to import from
// `./compression.js` directly.
void runCompressionPass;
void hashCwd;
