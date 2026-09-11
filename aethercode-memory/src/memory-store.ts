/**
 * High-level MemoryStore (T-040 ~ T-045).
 *
 * The MemoryStore composes the markdown parsers, the sqlite stores, the
 * LRU cache, and the jsonl writer into a single class. Reads follow the
 * cascade: cache → sqlite → file. Writes go through all three backends
 * (cache-write-through, see T-048 in a later task).
 *
 * Scope of this file: read methods (getGlobal, getProject, getSession) and
 * write methods (appendProjectChange, appendSessionMessage, appendSessionFact).
 * `compactProject`, `getAll`, and `switchProject` are out of scope for this
 * round — they come in T-046 ~ T-047.
 *
 * The class is constructed with paths and a `Database` handle. The handle
 * is borrowed (not owned) so the same SQLite file can be shared with
 * other modules. The cache is owned.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { mkdirSync, existsSync } from 'node:fs';
import { chmodOwnerReadWriteOnly } from './security/permissions.js';
import type { Database as DatabaseT } from 'better-sqlite3';
import { openAndMigrate, queryAll, withDatabase } from './sqlite.js';
import { readProjectMemoryFile, serializeProjectMemory } from './project-markdown.js';
import { parseGlobalMemory, serializeGlobalMemory } from './markdown.js';
import { writeSessionMessage } from './session-store.js';
import {
  appendProjectChange,
  appendTeamChange,
  listTeamChanges,
  promoteTeamChanges,
  upsertProject,
  listProjectChanges,
  projectChangeToEntry,
  type ProjectRow,
  type ProjectChangeRow,
} from './project-store.js';
import {
  createJsonlWriter,
  type JsonlWriter,
} from './jsonl-writer.js';
import { createLruCache, type LruCache } from './lru-cache.js';
import { VectorStore, type VectorScope, type VectorSearchResult } from './vec-store.js';
import { HashEmbeddingProvider, type EmbeddingProvider } from './embedding/index.js';
import {
  consolidateProjectChanges as runConsolidate,
  forgetProjectChanges as runForget,
  readMemoryStats as runReadStats,
} from './evolution.js';
import { findForgetCandidates } from './project-store.js';
import {
  upsertSkill,
  incrementSkillSuccess,
  recordSkillFailure,
  findSkillsByText,
  type SkillInput,
  type SkillSearchHit,
} from './skill-store.js';
import { sha256File, sha256Buffer, embedTextForImage } from './image-hash.js';
import {
  appendProvenanceSigned,
  verifyChain as runVerifyChain,
  verifySignedChain as runVerifySignedChain,
  type VerifyChainResult,
  type VerifySignedChainResult,
} from './provenance-store.js';
import {
  recordRetrievalOutcome as runRecordRetrievalOutcome,
  getFeedbackBulk,
  readFeedbackStats,
} from './feedback-store.js';
import { rankByType, shouldApplyTypeRanking, type RankedVectorRow } from './ranking.js';
import type {
  ChangeEntry,
  Fact,
  MemoryEntry,
  MemoryReadResult,
  SessionMessageInput,
} from './types.js';

/** Options for `createMemoryStore`. */
export interface MemoryStoreOptions {
  /** Absolute path to the global memory file (e.g. `<Home>/.aethercode/memory.md`). */
  readonly globalMemoryPath: string;
  /** Absolute path to the project memory file (e.g. `<cwd>/.aethercode/memory.md`). */
  readonly projectMemoryPath: string;
  /** Absolute path to the per-session jsonl directory. */
  readonly sessionsDir: string;
  /** Absolute path to the sqlite database. */
  readonly dbPath: string;
  /**
   * The cwd this store is rooted at. If omitted, derived as
   * `dirname(dirname(projectMemoryPath))` (the parent of `.aethercode`).
   * Updated by `switchProject`.
   */
  readonly cwd?: string;
  /** Cache capacity. Default 256. */
  readonly cacheCapacity?: number;
  /** Cache TTL in ms. Default 0 (off). */
  readonly cacheTtlMs?: number;
  /** fsync each jsonl append. Default `true`. */
  readonly jsonlFsync?: boolean;
  /**
   * Skip on-disk writes for the markdown files. Useful for tests that only
   * care about the sqlite / cache behaviour. Default `false`.
   */
  readonly readOnlyFiles?: boolean;
  /**
   * R-MEM-1: optional embedding provider for semantic search. Default is
   * a deterministic hash-based provider (no model download, offline).
   * Pass a custom `EmbeddingProvider` (e.g. all-MiniLM-L6 via ONNX)
   * for production-quality semantic similarity.
   */
  readonly embeddingProvider?: EmbeddingProvider;
  /**
   * R-MEM-1: when `true`, the store auto-embeds new entries on write
   * (project changes, session facts, etc.). Default `true` when an
   * `embeddingProvider` is supplied.
   */
  readonly autoEmbed?: boolean;
}

/** A session handle — owns a jsonl writer for the live session. */
export interface SessionHandle {
  readonly sessionId: string;
  readonly jsonlPath: string;
  append(record: { ts: number; role: string; content: string; metadata?: object; tokenCount?: number }): Promise<void>;
  close(): Promise<void>;
}

/** Factory result: the store + a way to close the owned database handle (if any). */
export interface MemoryStore {
  /* -------- paths (T-060) -------- */
  /** Absolute path to the global memory file. */
  readonly globalMemoryPath: string;
  /** Absolute path to the project memory file. */
  readonly projectMemoryPath: string;
  /** Absolute path to the per-session jsonl directory. */
  readonly sessionsDir: string;
  /** Absolute path to the sqlite database. */
  readonly dbPath: string;
  /** The cwd this store is rooted at. Updated by `switchProject` (T-060). */
  currentCwd: string;
  /* -------- reads (T-040, T-041, T-042) -------- */
  getGlobal(): MemoryReadResult;
  getProject(): MemoryReadResult;
  getSession(sessionId: string): MemoryReadResult;
  /* -------- writes (T-043, T-044, T-045) -------- */
  appendProjectChange(description: string, opts?: { expiresAt?: number | null; valueTag?: 'normal' | 'low' }): ChangeEntry;
  appendSessionMessage(sessionId: string, msg: SessionMessageInput): void;
  appendSessionFact(sessionId: string, fact: Fact): void;
  /* -------- R-MEM-1: semantic search -------- */
  findSimilar(query: string, opts?: { scope?: VectorScope; topK?: number; threshold?: number; sessionId?: string; memoryType?: 'episodic' | 'semantic' | 'procedural' }): VectorSearchResult;
  /** R-MEM-1: the embedding model id this store is bound to. */
  readonly embeddingModelId: string;
  /** R-MEM-1: the embedding vector dimension. */
  readonly embeddingDim: number;
  /* -------- R-MEM-2: evolution -------- */
  consolidate(opts?: { jaccardThreshold?: number; force?: boolean }): { scanned: number; groups: number; merged: number; ms: number };
  forget(opts?: { expired?: boolean; inactiveSinceMs?: number; lowValue?: boolean; limit?: number; dryRun?: boolean }): { candidates: number; softDeleted: number };
  readStats(): { totalChanges: number; liveChanges: number; consolidatedChanges: number; softDeletedChanges: number; expiredCandidates: number; lowValueCandidates: number; inactiveCandidates: number; now: number };
  /* -------- R-MEM-3: subagent shared memory -------- */
  shareToSubagent(teamSessionId: string, entries: ReadonlyArray<{ description: string; expiresAt?: number | null }>): { shared: number; ids: number[] };
  readTeamMemory(opts?: { teamSessionId?: string; includeDeleted?: boolean }): { entries: ReadonlyArray<{ id: number; description: string; teamSessionId: string; ts: number }>; count: number };
  promoteFromSubagent(teamSessionId: string, ids: ReadonlyArray<number>): number;
  /* -------- R-MEM-4: skill memory -------- */
  upsertSkill(input: Omit<SkillInput, 'ts'>): number;
  recordSkillOutcome(scope: string, name: string, ok: boolean): boolean;
  findSkill(query: string, opts?: { scope?: string; topK?: number; minScore?: number }): SkillSearchHit[];
  /* -------- R-MEM-5.1: multimodal image memory -------- */
  appendImage(input: {
    scope: VectorScope;
    description: string;
    filePath?: string;
    fileBuffer?: Buffer;
    mediaRef?: string;
    ts?: number;
  }): { entryId: string; mediaRef: string; mediaType: 'image' };
  /* -------- R-MEM-5.2: provenance chain (F7 trust) -------- */
  recordProvenance(scope: string, entryId: string, content: string, ts?: number): number;
  verifyChain(scope: string): VerifyChainResult;
  /* -------- R-MEM-6.2: Ed25519 signed chain (F7+) -------- */
  verifySignedChain(scope: string, options?: { requireSignatures?: boolean }): VerifySignedChainResult;
  /* -------- R-MEM-6.4: retrieval feedback (F3 RL, lightweight) -------- */
  recordRetrievalOutcome(
    scope: string,
    entryId: string,
    used: boolean,
    atMs?: number,
  ): { feedbackId: number | null; scope: string; entryId: string; used: boolean };
  getRetrievalFeedbackStats(): { rowsTotal: number; usedTotal: number; notUsedTotal: number };
  /* -------- session handle (jsonl streaming) -------- */
  openSession(sessionId: string): Promise<SessionHandle>;
  /* -------- bookkeeping -------- */
  cache: LruCache<MemoryReadResult>;
  close(): void;
}

/**
 * Construct a MemoryStore. The store opens & migrates the sqlite database on
 * construction and closes it on `close()`. To share a database handle across
 * modules, prefer the lower-level `session-store` / `project-store` APIs.
 */
export function createMemoryStore(opts: MemoryStoreOptions): MemoryStore {
  const db: DatabaseT = openAndMigrate(opts.dbPath);
  const cache = createLruCache<MemoryReadResult>({
    capacity: opts.cacheCapacity,
    ttlMs: opts.cacheTtlMs,
  });

  // R-MEM-1: vector store for semantic search. Lazily created on first
  // write so stores that don't opt in never pay the embedding cost.
  // The default provider is a deterministic hash function — see
  // embedding/hash-embedding.ts. Callers can plug in a real model
  // by passing `embeddingProvider`.
  //
  // The default for `autoEmbed` is true: since we always have a
  // provider (the hash fallback), there's no reason to skip
  // auto-embedding unless the caller explicitly opts out. Set
  // `opts.autoEmbed = false` to disable (e.g. in tests that only
  // care about non-vector behaviour).
  const autoEmbed = opts.autoEmbed !== false;
  const provider: EmbeddingProvider = opts.embeddingProvider ?? new HashEmbeddingProvider();
  const vecStore: VectorStore = new VectorStore(db, provider);

  // Track which sessions have a live writer. Map<sessionId, SessionHandleInternal>.
  const liveSessions = new Map<string, SessionHandleInternal>();

  function readGlobal(): MemoryReadResult {
    const cached = cache.peek('global', '_', '_');
    if (cached !== undefined) {
      return { ...cached.value, source: 'cache' };
    }
    const result = readGlobalFromDisk(opts.globalMemoryPath, Date.now());
    cache.set('global', '_', '_', result, estimateTokens(result.entries));
    return result;
  }

  function readProject(): MemoryReadResult {
    const projectId = currentProjectId(opts.projectMemoryPath);
    const cached = cache.peek('project', projectId, '_');
    if (cached !== undefined) {
      return { ...cached.value, source: 'cache' };
    }
    const result = readProjectFromDisk(opts, projectId, Date.now());
    cache.set('project', projectId, '_', result, estimateTokens(result.entries));
    return result;
  }

  function readSession(sessionId: string): MemoryReadResult {
    const cached = cache.peek('session', sessionId, '_');
    if (cached !== undefined) {
      return { ...cached.value, source: 'cache' };
    }
    const rows = queryAll<{ ts: number; role: string; content: string; token_count: unknown }>(
      db,
      'SELECT ts, role, content, token_count FROM session_messages WHERE session_id = ? ORDER BY ts ASC',
      [sessionId],
      (r) => ({
        ts: Number(r['ts'] ?? 0),
        role: String(r['role'] ?? 'system'),
        content: String(r['content'] ?? ''),
        token_count: r['token_count'],
      }),
    );
    let totalTokens = 0;
    const entries: MemoryEntry[] = rows.map((row) => {
      const tc =
        row.token_count === null || row.token_count === undefined ? 0 : Number(row.token_count);
      totalTokens += tc;
      return {
        kind: 'fact',
        id: `session-fact-${sessionId}-${row.ts}`,
        ts: row.ts,
        scope: 'session',
        source: 'tool',
        tags: ['session', row.role],
        key: `msg.${row.ts}`,
        value: row.content,
      } satisfies Fact;
    });
    const result: MemoryReadResult = {
      source: 'sqlite',
      entries,
      totalTokens,
      truncated: false,
    };
    cache.set('session', sessionId, '_', result, totalTokens);
    return result;
  }

  function appendProjectChangeImpl(
    description: string,
    writeOpts: { expiresAt?: number | null; valueTag?: 'normal' | 'low' } = {},
  ): ChangeEntry {
    if (!description) throw new Error('appendProjectChange: description must be non-empty');
    const projectId = currentProjectId(opts.projectMemoryPath);
    const ts = Date.now();
    // Ensure the project row exists before inserting the change so the
    // FOREIGN KEY constraint on project_changes.project_id is satisfied.
    const existing = readProjectRow(db, projectId);
    if (existing === null) {
      upsertProject(db, {
        project_id: projectId,
        cwd: dirname(opts.projectMemoryPath),
        updated_at: ts,
      });
    } else {
      upsertProject(db, {
        project_id: projectId,
        cwd: existing.cwd,
        title: existing.title,
        description: existing.description,
        updated_at: ts,
      });
    }
    const newId = appendProjectChange(db, {
      project_id: projectId,
      description,
      ts,
      expires_at: writeOpts.expiresAt ?? null,
      value_tag: writeOpts.valueTag ?? 'normal',
    });
    // Invalidate the project cache so the next read sees the new change.
    cache.invalidateScope('project', projectId);
    // R-MEM-1: auto-embed the new change. Failures are swallowed
    // so a broken embedding model can never break a write.
    if (autoEmbed) {
      try {
        vecStore.upsert('project', `project-change-${newId}`, description, ts, { memoryType: 'episodic' });
      } catch {
        /* best-effort: search will simply miss this row */
      }
    }
    // R-MEM-5.2: record provenance.
    try { appendProvenanceSigned(db, 'project', `project-change-${newId}`, description, ts); } catch { /* best-effort */ }
    return {
      kind: 'change',
      id: `project-change-${newId}`,
      ts,
      scope: 'project',
      source: 'system',
      tags: ['change'],
      description,
      compressed: false,
    };
  }

  function appendSessionMessageImpl(sessionId: string, msg: SessionMessageInput): void {
    writeSessionMessage(db, sessionId, msg);
    cache.invalidateScope('session', sessionId);
  }

  function appendSessionFactImpl(sessionId: string, fact: Fact): void {
    if (fact.scope !== 'session') {
      // Coerce scope to 'session' so the row is stored in the session layer.
      const sessionFact: Fact = { ...fact, scope: 'session' };
      writeSessionMessage(db, sessionId, {
        ts: sessionFact.ts,
        role: 'fact',
        content: JSON.stringify({ key: sessionFact.key, value: sessionFact.value }),
        metadata: { factId: sessionFact.id, source: sessionFact.source, tags: [...sessionFact.tags] },
        tokenCount: estimateStringTokens(JSON.stringify({ key: sessionFact.key, value: sessionFact.value })),
      });
    } else {
      writeSessionMessage(db, sessionId, {
        ts: fact.ts,
        role: 'fact',
        content: JSON.stringify({ key: fact.key, value: fact.value }),
        metadata: { factId: fact.id, source: fact.source, tags: [...fact.tags] },
        tokenCount: estimateStringTokens(JSON.stringify({ key: fact.key, value: fact.value })),
      });
    }
    // R-MEM-1: auto-embed the new fact. Key + value is the
    // embedding text (the human-readable question + answer pair).
    if (autoEmbed) {
      try {
        const text = `${fact.key}: ${fact.value}`;
        vecStore.upsert('session', `session-fact-${sessionId}-${fact.id}`, text, fact.ts, { memoryType: 'episodic' });
      } catch {
        /* best-effort */
      }
    }
    // R-MEM-5.2: record provenance for the session fact.
    try {
      const text = `${fact.key}: ${fact.value}`;
      appendProvenanceSigned(db, 'session', `session-fact-${sessionId}-${fact.id}`, text, fact.ts);
    } catch { /* best-effort */ }
    cache.invalidateScope('session', sessionId);
  }

  /**
   * R-MEM-1: semantic search. Embeds the query and runs a brute-force
   * cosine search over the in-process index. Returns up to `topK`
   * rows above `threshold`, plus the total scanned count and the
   * wall-clock time.
   *
   * R-MEM-6.3: when `memoryType` is set, the result rows are
   * re-ranked by the type-specific strategy (procedural boost
   * by success_count, episodic recency decay). The original
   * cosine score is preserved on each row as `cosine_score`,
   * with the new `score` and `boostReason` fields added.
   *
   * For the global scope we lazily embed every global fact each
   * call (the global file is small, typically < 100 entries, so
   * the cost is negligible compared to the network round-trip
   * the RPC has already paid for).
   */
  function findSimilarImpl(
    query: string,
    findOpts: { scope?: VectorScope; topK?: number; threshold?: number; sessionId?: string; memoryType?: 'episodic' | 'semantic' | 'procedural' } = {},
  ): VectorSearchResult & { rows: ReadonlyArray<RankedVectorRow> } {
    if (typeof query !== 'string' || query.length === 0) {
      throw new Error('findSimilar: query must be a non-empty string');
    }
    const topK = findOpts.topK ?? 10;
    const threshold = findOpts.threshold ?? 0.0;
    const queryVec = provider.embed(query);

    // Lazily warm the global index if requested.
    if (findOpts.scope === undefined || findOpts.scope === 'global') {
      warmGlobalIndex();
    }

    const result = vecStore.search({
      query: queryVec,
      topK,
      threshold,
      scope: findOpts.scope,
      memoryType: findOpts.memoryType,
    });
    if (!shouldApplyTypeRanking(findOpts.memoryType)) {
      // Untyped query: no boost, no metadata lookup. The
      // returned rows stay plain VectorRow.
      return result as VectorSearchResult & { rows: ReadonlyArray<RankedVectorRow> };
    }
    // For a typed query, look up per-row success_count for
    // procedural rows (the only memory type that has a
    // success counter). Other types' counters are 0.
    const successByEntry = new Map<string, number>();
    for (const r of result.rows) {
      if (r.memory_type === 'procedural' && r.entry_id.startsWith('skill-')) {
        // entry_id format: skill-<scope>-<name>
        const rest = r.entry_id.slice('skill-'.length);
        const dashIdx = rest.indexOf('-');
        if (dashIdx > 0) {
          const skillScope = rest.slice(0, dashIdx);
          const name = rest.slice(dashIdx + 1);
          const row = db.prepare(`SELECT success_count FROM skills WHERE scope = ? AND name = ?`).get(skillScope, name) as { success_count: number } | undefined;
          if (row) successByEntry.set(r.entry_id, row.success_count);
        }
      }
    }
    // R-MEM-6.4 (F3 RL): bulk lookup retrieval feedback for the
    // returned rows. We pick a "primary" scope key for the
    // feedback table: when a sessionId is in scope, we use
    // 'session:<sessionId>' so feedback from one session
    // doesn't leak into another. Otherwise the row's own
    // `scope` is the feedback key.
    const feedbackScope =
      findOpts.scope === 'session' && findOpts.sessionId
        ? `session:${findOpts.sessionId}`
        : 'global';
    const feedbackByEntry = getFeedbackBulk(db, feedbackScope, result.rows.map((r) => r.entry_id));
    const ranked = rankByType(result.rows, {
      getSuccessCount: (r) => successByEntry.get(r.entry_id) ?? 0,
      getFeedback: (r) => {
        const fb = feedbackByEntry.get(r.entry_id);
        if (!fb) return null;
        return { used: fb.used_count, notUsed: fb.not_used_count };
      },
    });
    return {
      ...result,
      rows: ranked,
    };
  }

  /** R-MEM-1: lazily embed every entry in the global memory file.
   *  Idempotent: a row is only inserted when its (scope, entry_id)
   *  pair isn't already present. */
  function warmGlobalIndex(): void {
    if (!existsSync(opts.globalMemoryPath)) return;
    let text: string;
    try {
      text = readFileSync(opts.globalMemoryPath, 'utf-8');
    } catch {
      return;
    }
    const mem = parseGlobalMemory(text, Date.now());
    const items: Array<{ scope: VectorScope; entryId: string; contentText: string; ts: number }> = [];
    for (const f of mem.facts) {
      items.push({ scope: 'global', entryId: f.id, contentText: `${f.key}: ${f.value}`, ts: f.ts });
    }
    for (const r of mem.rules) {
      items.push({ scope: 'global', entryId: r.id, contentText: r.text, ts: r.ts });
    }
    for (const b of mem.breadcrumbs) {
      items.push({ scope: 'global', entryId: b.id, contentText: b.message, ts: b.ts });
    }
    if (items.length === 0) return;
    // Filter to only entries not already in the index. For warm-up
    // a full scan is fine; for hot paths callers should rely on
    // auto-embed at write time.
    const existing = new Set<string>();
    for (const r of vecStore['db'].prepare('SELECT scope, entry_id FROM vec_index WHERE scope = ?').all('global') as Array<{ entry_id: string }>) {
      existing.add(r.entry_id);
    }
    // R-MEM-5.3 (F8 Cognition): global facts and rules are
    // 'semantic' memory (general knowledge), not 'episodic'.
    const fresh = items
      .filter((it) => !existing.has(it.entryId))
      .map((it) => ({ ...it, memoryType: 'semantic' as const }));
    if (fresh.length > 0) {
      vecStore.bulkUpsert(fresh);
    }
  }

  async function openSessionImpl(sessionId: string): Promise<SessionHandle> {
    const existing = liveSessions.get(sessionId);
    if (existing !== undefined) return existing;
    mkdirSync(opts.sessionsDir, { recursive: true });
    const jsonlPath = join(opts.sessionsDir, `${sessionId}.jsonl`);
    const writer: JsonlWriter = await createJsonlWriter(jsonlPath, { fsync: opts.jsonlFsync !== false });
    const handle = new SessionHandleInternal(writer, sessionId, jsonlPath, () => {
      liveSessions.delete(sessionId);
    });
    liveSessions.set(sessionId, handle);
    return handle;
  }

  function close(): void {
    for (const handle of liveSessions.values()) {
      void handle.close();
    }
    liveSessions.clear();
    db.close();
  }

  /* R-MEM-6.4 (F3 RL, lightweight): retrieval feedback loop. */
  function recordRetrievalOutcomeImpl(
    scope: string,
    entryId: string,
    used: boolean,
    atMs?: number,
  ): { feedbackId: number | null; scope: string; entryId: string; used: boolean } {
    // The feedback table has its own scope key. We use the
    // caller's `scope` directly. For session-scoped queries
    // the RPC layer is expected to pass `session:<sessionId>`
    // so feedback from one session doesn't leak into another.
    const at = atMs ?? Date.now();
    runRecordRetrievalOutcome(db, scope, entryId, used, at);
    // Read back the row to surface the new id. Cheaper than
    // touching `lastInsertRowid` because the SQL is
    // idempotent (upsert) and we want a stable type.
    const row = db
      .prepare('SELECT id FROM retrieval_feedback WHERE scope = ? AND entry_id = ?')
      .get(scope, entryId) as { id: number } | undefined;
    return {
      feedbackId: row?.id ?? null,
      scope,
      entryId,
      used,
    };
  }
  function getRetrievalFeedbackStatsImpl(): { rowsTotal: number; usedTotal: number; notUsedTotal: number } {
    return readFeedbackStats(db);
  }

  const currentCwd = opts.cwd ?? dirname(dirname(opts.projectMemoryPath));
  return {
    globalMemoryPath: opts.globalMemoryPath,
    projectMemoryPath: opts.projectMemoryPath,
    sessionsDir: opts.sessionsDir,
    dbPath: opts.dbPath,
    currentCwd,
    getGlobal: readGlobal,
    getProject: readProject,
    getSession: readSession,
    appendProjectChange: appendProjectChangeImpl,
    appendSessionMessage: appendSessionMessageImpl,
    appendSessionFact: appendSessionFactImpl,
    findSimilar: findSimilarImpl,
    embeddingModelId: provider.modelId,
    embeddingDim: provider.dim,
    consolidate: consolidateImpl,
    forget: forgetImpl,
    readStats: readStatsImpl,
    shareToSubagent: shareToSubagentImpl,
    readTeamMemory: readTeamMemoryImpl,
    promoteFromSubagent: promoteFromSubagentImpl,
    upsertSkill: upsertSkillImpl,
    recordSkillOutcome: recordSkillOutcomeImpl,
    findSkill: findSkillImpl,
    appendImage: appendImageImpl,
    recordProvenance: recordProvenanceImpl,
    verifyChain: verifyChainImpl,
    verifySignedChain: verifySignedChainImpl,
    recordRetrievalOutcome: recordRetrievalOutcomeImpl,
    getRetrievalFeedbackStats: getRetrievalFeedbackStatsImpl,
    openSession: openSessionImpl,
    cache,
    close,
  };

  /* R-MEM-2: evolution methods (consolidate / forget / stats). */
  function consolidateImpl(cOpts: { jaccardThreshold?: number; force?: boolean } = {}): { scanned: number; groups: number; merged: number; ms: number } {
    const projectId = currentProjectId(opts.projectMemoryPath);
    const result = runConsolidate(db, projectId, {
      jaccardThreshold: cOpts.jaccardThreshold,
      maxScanned: cOpts.force === true ? 100_000 : undefined,
    });
    return { scanned: result.scanned, groups: result.groups, merged: result.merged, ms: result.ms };
  }
  function forgetImpl(fOpts: { expired?: boolean; inactiveSinceMs?: number; lowValue?: boolean; limit?: number; dryRun?: boolean } = {}): { candidates: number; softDeleted: number } {
    const projectId = currentProjectId(opts.projectMemoryPath);
    if (fOpts.dryRun === true) {
      return { candidates: findForgetCandidates(db, projectId, fOpts).length, softDeleted: 0 };
    }
    const result = runForget(db, projectId, fOpts);
    return { candidates: result.candidates, softDeleted: result.softDeleted };
  }
  function readStatsImpl(): { totalChanges: number; liveChanges: number; consolidatedChanges: number; softDeletedChanges: number; expiredCandidates: number; lowValueCandidates: number; inactiveCandidates: number; now: number } {
    const projectId = currentProjectId(opts.projectMemoryPath);
    return runReadStats(db, projectId);
  }

  /* R-MEM-3: subagent shared memory methods. */
  function shareToSubagentImpl(
    teamSessionId: string,
    entries: ReadonlyArray<{ description: string; expiresAt?: number | null }>,
  ): { shared: number; ids: number[] } {
    if (!teamSessionId) throw new Error('shareToSubagent: teamSessionId must be non-empty');
    if (entries.length === 0) return { shared: 0, ids: [] };
    const projectId = currentProjectId(opts.projectMemoryPath);
    // Ensure the project row exists so the FK holds.
    const existing = readProjectRow(db, projectId);
    if (existing === null) {
      upsertProject(db, {
        project_id: projectId,
        cwd: dirname(opts.projectMemoryPath),
        updated_at: Date.now(),
      });
    }
    const ids: number[] = [];
    for (const e of entries) {
      if (typeof e.description !== 'string' || e.description.length === 0) {
        throw new Error('shareToSubagent: every entry needs a non-empty description');
      }
      const id = appendTeamChange(db, projectId, e.description, teamSessionId, {
        expiresAt: e.expiresAt ?? null,
      });
      ids.push(id);
    }
    return { shared: entries.length, ids };
  }

  function readTeamMemoryImpl(
    rOpts: { teamSessionId?: string; includeDeleted?: boolean } = {},
  ): { entries: ReadonlyArray<{ id: number; description: string; teamSessionId: string; ts: number }>; count: number } {
    const projectId = currentProjectId(opts.projectMemoryPath);
    // We always use listTeamChanges (which skips soft-deleted rows) and
    // surface the unfiltered list when includeDeleted=true.
    if (rOpts.includeDeleted === true) {
      // Inline SQL: include soft-deleted too. Mirrors listTeamChanges
      // but without the deleted_at filter.
      const rows = queryAll<ProjectChangeRow>(
        db,
        `SELECT id, project_id, ts, description, compressed,
              expires_at, consolidated_into, value_tag, last_accessed_at, access_count, deleted_at, team_session_id
         FROM project_changes
         WHERE project_id = ? AND team_session_id IS NOT NULL
           AND (? IS NULL OR team_session_id = ?)
         ORDER BY ts ASC`,
        [projectId, rOpts.teamSessionId ?? null, rOpts.teamSessionId ?? null],
        (r) => ({
          id: Number(r['id'] ?? 0),
          project_id: String(r['project_id'] ?? ''),
          ts: Number(r['ts'] ?? 0),
          description: String(r['description'] ?? ''),
          compressed: Number(r['compressed'] ?? 0) === 1 ? 1 : 0,
          expires_at: r['expires_at'] === null || r['expires_at'] === undefined ? null : Number(r['expires_at']),
          consolidated_into: r['consolidated_into'] === null || r['consolidated_into'] === undefined ? null : Number(r['consolidated_into']),
          value_tag: String(r['value_tag'] ?? 'normal') === 'low' ? 'low' : 'normal',
          last_accessed_at: Number(r['last_accessed_at'] ?? 0),
          access_count: Number(r['access_count'] ?? 0),
          deleted_at: r['deleted_at'] === null || r['deleted_at'] === undefined ? null : Number(r['deleted_at']),
          team_session_id: r['team_session_id'] === null || r['team_session_id'] === undefined ? null : String(r['team_session_id']),
        }),
      );
      const entries = rows
        .filter((r) => r.team_session_id !== null)
        .map((r) => ({
          id: r.id,
          description: r.description,
          teamSessionId: r.team_session_id as string,
          ts: r.ts,
        }));
      return { entries, count: entries.length };
    }
    const rows = listTeamChanges(db, projectId, rOpts.teamSessionId ?? null);
    const entries = rows
      .filter((r) => r.team_session_id !== null)
      .map((r) => ({
        id: r.id,
        description: r.description,
        teamSessionId: r.team_session_id as string,
        ts: r.ts,
      }));
    return { entries, count: entries.length };
  }

  function promoteFromSubagentImpl(teamSessionId: string, ids: ReadonlyArray<number>): number {
    if (!teamSessionId) throw new Error('promoteFromSubagent: teamSessionId must be non-empty');
    if (ids.length === 0) return 0;
    const projectId = currentProjectId(opts.projectMemoryPath);
    return promoteTeamChanges(db, projectId, teamSessionId, ids);
  }

  /* R-MEM-4: skill memory methods. */
  function upsertSkillImpl(input: Omit<SkillInput, 'ts'>): number {
    const ts = Date.now();
    const id = upsertSkill(db, { ...input, ts });
    // Auto-embed the skill into the vector index so semantic
    // search picks it up alongside project changes. The
    // embedding text is the description prefixed by the
    // signature, which gives a richer representation than
    // either alone.
    if (autoEmbed) {
      try {
        const text = `${input.signature}: ${input.description}`;
        vecStore.upsert('skill', `skill-${input.scope}-${input.name}`, text, ts, { memoryType: 'procedural' });
      } catch {
        /* best-effort */
      }
    }
    // R-MEM-5.2: record provenance for the skill.
    try {
      const text = `${input.signature}: ${input.description}`;
      appendProvenanceSigned(db, input.scope, `skill-${input.scope}-${input.name}`, text, ts);
    } catch { /* best-effort */ }
    return id;
  }

  function recordSkillOutcomeImpl(scope: string, name: string, ok: boolean): boolean {
    if (ok) {
      return incrementSkillSuccess(db, scope, name);
    }
    return recordSkillFailure(db, scope, name);
  }

  function findSkillImpl(
    query: string,
    fOpts: { scope?: string; topK?: number; minScore?: number } = {},
  ): SkillSearchHit[] {
    return findSkillsByText(db, query, fOpts);
  }

  /* R-MEM-5.1 (F4 Multimodal): image memory. The image
   * content isn't embedded by a vision model — we just hash
   * the bytes (so identical images collapse to the same key)
   * and use the description + hash as the embedding input. A
   * future round can plug in a real CLIP / vision encoder
   * via the EmbeddingProvider interface. */
  function appendImageImpl(input: {
    scope: VectorScope;
    description: string;
    filePath?: string;
    fileBuffer?: Buffer;
    mediaRef?: string;
    ts?: number;
  }): { entryId: string; mediaRef: string; mediaType: 'image' } {
    if (typeof input.description !== 'string' || input.description.length === 0) {
      throw new Error('appendImage: description must be a non-empty string');
    }
    if (input.scope !== 'global' && input.scope !== 'project' && input.scope !== 'session' && input.scope !== 'skill') {
      throw new Error(`appendImage: invalid scope "${input.scope}"`);
    }
    let mediaRef: string;
    if (typeof input.mediaRef === 'string' && input.mediaRef.length > 0) {
      mediaRef = input.mediaRef;
    } else if (typeof input.filePath === 'string' && input.filePath.length > 0) {
      mediaRef = sha256File(input.filePath);
    } else if (input.fileBuffer !== undefined) {
      mediaRef = sha256Buffer(input.fileBuffer);
    } else {
      throw new Error('appendImage: one of filePath, fileBuffer, or mediaRef is required');
    }
    const entryId = `image-${mediaRef.slice(0, 32)}`;
    const ts = input.ts ?? Date.now();
    const text = embedTextForImage(input.description, mediaRef);
    if (autoEmbed) {
      try {
        vecStore.upsert(input.scope, entryId, text, ts, { mediaType: 'image', mediaRef, memoryType: 'semantic' });
      } catch {
        /* best-effort */
      }
    }
    // R-MEM-5.2: record provenance.
    try { appendProvenanceSigned(db, input.scope, entryId, text, ts); } catch { /* best-effort */ }
    return { entryId, mediaRef, mediaType: 'image' };
  }

  /* R-MEM-5.2 (F7 Trust): provenance chain methods. */
  function recordProvenanceImpl(scope: string, entryId: string, content: string, ts?: number): number {
    return appendProvenanceSigned(db, scope, entryId, content, ts).id;
  }

  /** Build a contentProvider that knows how to look up entry
   *  content for each scope. Falls back to vec_index when the
   *  scope-specific table doesn't have the entry. Used by
   *  both `verifyChain` and `verifySignedChain`. Hoisted as
   *  a function so the verifier functions can be defined
   *  earlier in the closure (function declarations are
   *  hoisted; const arrow functions are not). */
  function buildContentProvider(entryId: string, ts: number): string | null {
    // project_changes: project-change-<id>
    if (entryId.startsWith('project-change-')) {
      const idStr = entryId.slice('project-change-'.length);
      const id = Number(idStr);
      if (!Number.isFinite(id)) return null;
      const row = db.prepare(`SELECT description FROM project_changes WHERE id = ?`).get(id) as { description: string } | undefined;
      return row?.description ?? null;
    }
    // skills: skill-<scope>-<name>
    if (entryId.startsWith('skill-')) {
      const rest = entryId.slice('skill-'.length);
      const dashIdx = rest.indexOf('-');
      if (dashIdx < 0) return null;
      const skillScope = rest.slice(0, dashIdx);
      const name = rest.slice(dashIdx + 1);
      const row = db.prepare(`SELECT signature, description FROM skills WHERE scope = ? AND name = ?`).get(skillScope, name) as { signature: string; description: string } | undefined;
      if (row === undefined) return null;
      return `${row.signature}|${row.description}`;
    }
    // session messages: session-fact-<sid>-<ts>
    if (entryId.startsWith('session-fact-')) {
      const row = db.prepare(`SELECT content FROM session_messages WHERE ts = ?`).get(ts) as { content: string } | undefined;
      return row?.content ?? null;
    }
    // vec_index fallback (image-* etc.)
    const row = db.prepare(`SELECT content_text FROM vec_index WHERE entry_id = ? AND ts = ?`).get(entryId, ts) as { content_text: string } | undefined;
    return row?.content_text ?? null;
  }

  function verifyChainImpl(scope: string): VerifyChainResult {
    return runVerifyChain(db, scope, buildContentProvider);
  }

  function verifySignedChainImpl(
    scope: string,
    options: { requireSignatures?: boolean } = {},
  ): VerifySignedChainResult {
    return runVerifySignedChain(db, scope, buildContentProvider, options);
  }
}

/* ------------------------- internal helpers ----------------------------- */

class SessionHandleInternal implements SessionHandle {
  constructor(
    private readonly writer: JsonlWriter,
    public readonly sessionId: string,
    public readonly jsonlPath: string,
    private readonly onClose: () => void,
  ) {}

  async append(record: {
    ts: number;
    role: string;
    content: string;
    metadata?: Record<string, unknown>;
    tokenCount?: number;
  }): Promise<void> {
    // The writer takes a strongly-typed JsonlValue tree. Our record shape is
    // already a JSON-serialisable object, but TypeScript can't prove the
    // metadata is well-formed; we trust the caller (the higher-level API
    // does the same). The cast narrows to the writer's expected type.
    await this.writer.append(record as unknown as Parameters<JsonlWriter['append']>[0]);
  }

  async close(): Promise<void> {
    await this.writer.close();
    this.onClose();
  }
}

function readGlobalFromDisk(path: string, now: number): MemoryReadResult {
  let text: string;
  try {
    text = readFileSync(path, 'utf-8');
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === 'ENOENT') {
      return { source: 'file', entries: [], totalTokens: 0, truncated: false };
    }
    throw err;
  }
  const mem = parseGlobalMemory(text, now);
  const entries = [...mem.facts, ...mem.rules, ...mem.breadcrumbs];
  return { source: 'file', entries, totalTokens: estimateTokens(entries), truncated: false };
}

function readProjectFromDisk(
  opts: MemoryStoreOptions,
  projectId: string,
  now: number,
): MemoryReadResult {
  const defaultTitle = `project-${projectId.slice(0, 8) || 'untitled'}`;
  const mem = readProjectMemoryFile(
    { readFileSync, existsSync },
    opts.projectMemoryPath,
    defaultTitle,
    now,
  );
  // Also surface the project_db row (cwd / title / description) as facts.
  const row = readProjectRowViaOptions(opts, projectId);
  const changeRows = listProjectChangesViaOptions(opts, projectId);
  const entries: MemoryEntry[] = [
    ...mem.changes,
    ...mem.facts,
    ...(row === null ? [] : projectRowAsFacts(row)),
  ];
  // The actual change rows from sqlite are added (newest first is also
  // useful; for read consistency we mirror the file order, but include the
  // sqlite tail).
  for (const c of changeRows) {
    entries.push(projectChangeToEntry(c));
  }
  // Surface the markdown-file description as a Fact so callers can find it
  // through the unified `entries` API.
  if (mem.description !== '') {
    entries.push({
      kind: 'fact',
      id: `project-fact-${projectId}-file-description`,
      ts: now,
      scope: 'project',
      source: 'system',
      tags: ['project', 'description'],
      key: 'description',
      value: mem.description,
    });
  }
  return { source: 'file', entries, totalTokens: estimateTokens(entries), truncated: false };
}

function projectRowAsFacts(row: ProjectRow): Fact[] {
  const out: Fact[] = [];
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

function readProjectRow(db: DatabaseT, projectId: string): ProjectRow | null {
  const rows = queryAll<ProjectRow>(
    db,
    'SELECT project_id, cwd, title, description, updated_at FROM project_db WHERE project_id = ?',
    [projectId],
    (r) => ({
      project_id: String(r['project_id'] ?? ''),
      cwd: String(r['cwd'] ?? ''),
      title: r['title'] === null || r['title'] === undefined ? null : String(r['title']),
      description:
        r['description'] === null || r['description'] === undefined ? null : String(r['description']),
      updated_at: Number(r['updated_at'] ?? 0),
    }),
  );
  return rows[0] ?? null;
}

function readProjectRowViaOptions(opts: MemoryStoreOptions, projectId: string): ProjectRow | null {
  return withDatabase(opts.dbPath, (db) => readProjectRow(db, projectId));
}

function listProjectChangesViaOptions(
  opts: MemoryStoreOptions,
  projectId: string,
): ProjectChangeRow[] {
  return withDatabase(opts.dbPath, (db) => listProjectChanges(db, projectId, { descending: false }));
}

/**
 * Compute a stable project id from the project memory file path. The default
 * is the hex of a 31-bit FNV-1a hash of the absolute path. Two different
 * cwds that share a memory path would collide; that is acceptable because
 * each cwd has its own `<cwd>/.aethercode/` directory.
 */
function currentProjectId(projectMemoryPath: string): string {
  // FNV-1a 32-bit.
  let h = 0x811c9dc5;
  for (let i = 0; i < projectMemoryPath.length; i += 1) {
    h ^= projectMemoryPath.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/** Crude token estimator: ~4 chars per token, never below 1. */
function estimateTokens(entries: ReadonlyArray<MemoryEntry>): number {
  let total = 0;
  for (const e of entries) {
    total += estimateEntryTokens(e);
  }
  return total;
}

function estimateEntryTokens(e: MemoryEntry): number {
  const text = entryToText(e);
  return Math.max(1, Math.ceil(text.length / 4));
}

function entryToText(e: MemoryEntry): string {
  switch (e.kind) {
    case 'fact':
      return `${e.key}: ${e.value}`;
    case 'rule':
      return e.text;
    case 'change':
      return e.description;
    case 'breadcrumb':
      return e.message;
  }
}

function estimateStringTokens(s: string): number {
  return Math.max(1, Math.ceil(s.length / 4));
}

/** Internal: ensure a project's markdown file exists. */
export function ensureProjectFile(projectMemoryPath: string, title: string, description: string): void {
  if (existsSync(projectMemoryPath)) return;
  mkdirSync(dirname(projectMemoryPath), { recursive: true });
  const mem = { title, description, changes: [], facts: [] };
  const text = serializeProjectMemory(mem);
  writeFileSync(projectMemoryPath, text, 'utf-8');
  // T-507: best-effort 0600 on POSIX. No-op on Windows.
  chmodOwnerReadWriteOnly(projectMemoryPath);
}

/** Internal: ensure the global memory file exists. */
export function ensureGlobalFile(globalMemoryPath: string): void {
  if (existsSync(globalMemoryPath)) return;
  mkdirSync(dirname(globalMemoryPath), { recursive: true });
  const text = serializeGlobalMemory({ facts: [], rules: [], breadcrumbs: [] });
  writeFileSync(globalMemoryPath, text, 'utf-8');
  // T-507: best-effort 0600 on POSIX. No-op on Windows.
  chmodOwnerReadWriteOnly(globalMemoryPath);
}
