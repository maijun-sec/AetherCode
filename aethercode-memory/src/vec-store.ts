/**
 * R-MEM-1: vector store — SQLite BLOB persistence + brute-force
 * cosine search.
 *
 * Scope: a single project's worth of vectors. The `scope` field
 * on each row is a hint for callers (`'global'`, `'project'`,
 * `'session'`, `'skill'`) but the search just iterates the whole
 * in-memory index, so all scopes are addressable from one store
 * instance. When the project set grows beyond ~10K entries, swap
 * the brute-force loop for HNSW (or sqlite-vec if/when we can
 * ship a native extension).
 *
 * Storage:
 *  - `vec_index` table: (id, scope, entry_id, content_text,
 *    embedding BLOB, model_id, ts)
 *  - The BLOB is the raw Float32Array bytes (little-endian on
 *    all platforms we care about — better-sqlite3 is built with
 *    a stable binary layout).
 *
 * Consistency:
 *  - `upsert` is idempotent on `(scope, entry_id)`. The model_id
 *    check ensures we never mix vectors from two different
 *    embedding models in the same query result.
 *  - `delete` is `(scope, entry_id)`.
 *  - `rebuild` is `delete all` + `upsert` loop. Used when the
 *    embedding model changes or when the caller wants to
 *    re-embed with a fresh provider.
 */

import type { Database as DatabaseT } from 'better-sqlite3';
import { runStatement } from './sqlite.js';
import type { EmbeddingProvider } from './embedding/index.js';

export type VectorScope = 'global' | 'project' | 'session' | 'skill';

/** R-MEM-5.3 (F8 Cognition): Tulving-style memory types. */
export type MemoryType = 'episodic' | 'semantic' | 'procedural';

/** A persisted vector row. `score` is filled in by `search` only. */
export interface VectorRow {
  readonly id: number;
  readonly scope: VectorScope;
  readonly entry_id: string;
  readonly content_text: string;
  readonly embedding: Float32Array;
  readonly model_id: string;
  readonly ts: number;
  /** R-MEM-5.1: 'text' (default) or 'image'. Drives the
   *  multimodal retrieval in `memory/find` and the wire shape
   *  in the RPC layer. */
  readonly media_type: 'text' | 'image';
  /** R-MEM-5.1: for image rows, the file path or SHA-256
   *  content hash that callers can use to look up the
   *  original asset. NULL for text rows. */
  readonly media_ref: string | null;
  /** R-MEM-5.3: Tulving-style memory type. Drives the
   *  type-based retrieval in `memory/findByType`. */
  readonly memory_type: 'episodic' | 'semantic' | 'procedural';
  /** Cosine similarity to the query, in [0, 1] (clamped). Only set by `search`. */
  readonly score?: number;
}

/** Search options. */
export interface VectorSearchParams {
  readonly query: Float32Array;
  readonly topK?: number;
  /** Cosine threshold in [0, 1]. Rows with score < threshold are dropped. */
  readonly threshold?: number;
  /** Optional scope filter — only rows matching this scope are considered. */
  readonly scope?: VectorScope;
  /** Optional entry_id filter — useful for deduping a query against an existing row. */
  readonly entryId?: string;
  /** R-MEM-5.3: optional memory type filter — only rows
   *  matching this Tulving type are considered. */
  readonly memoryType?: 'episodic' | 'semantic' | 'procedural';
}

/** Result of a `search` call. */
export interface VectorSearchResult {
  readonly rows: ReadonlyArray<VectorRow>;
  readonly totalScanned: number;
  readonly ms: number;
}

const SQL_CREATE_TABLE = `CREATE TABLE IF NOT EXISTS vec_index (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope TEXT NOT NULL,
  entry_id TEXT NOT NULL,
  content_text TEXT NOT NULL,
  embedding BLOB NOT NULL,
  model_id TEXT NOT NULL,
  ts INTEGER NOT NULL,
  media_type TEXT NOT NULL DEFAULT 'text',
  media_ref TEXT,
  memory_type TEXT NOT NULL DEFAULT 'episodic',
  UNIQUE(scope, entry_id)
)`;

const SQL_CREATE_TS_INDEX = `CREATE INDEX IF NOT EXISTS idx_vec_index_ts ON vec_index(ts DESC)`;
const SQL_CREATE_SCOPE_INDEX = `CREATE INDEX IF NOT EXISTS idx_vec_index_scope ON vec_index(scope)`;
const SQL_CREATE_MEDIA_INDEX = `CREATE INDEX IF NOT EXISTS idx_vec_index_media_type ON vec_index(media_type, scope)`;
const SQL_CREATE_MEMORY_TYPE_INDEX = `CREATE INDEX IF NOT EXISTS idx_vec_index_memory_type ON vec_index(memory_type, scope)`;

const SQL_UPSERT = `INSERT INTO vec_index (scope, entry_id, content_text, embedding, model_id, ts, media_type, media_ref, memory_type)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(scope, entry_id) DO UPDATE SET
    content_text = excluded.content_text,
    embedding = excluded.embedding,
    model_id = excluded.model_id,
    ts = excluded.ts,
    media_type = excluded.media_type,
    media_ref = excluded.media_ref,
    memory_type = excluded.memory_type`;

const SQL_DELETE_BY_ENTRY = `DELETE FROM vec_index WHERE scope = ? AND entry_id = ?`;

const SQL_DELETE_ALL_BY_SCOPE = `DELETE FROM vec_index WHERE scope = ?`;

const SQL_DELETE_ALL = `DELETE FROM vec_index`;

const SQL_COUNT = `SELECT COUNT(*) AS n FROM vec_index`;

const SQL_LIST_BY_SCOPE = `SELECT id, scope, entry_id, content_text, embedding, model_id, ts, media_type, media_ref, memory_type
  FROM vec_index
  WHERE (? IS NULL OR scope = ?)
  ORDER BY ts DESC`;

interface Row {
  id: number;
  scope: string;
  entry_id: string;
  content_text: string;
  embedding: Buffer;
  model_id: string;
  ts: number;
  media_type: string;
  media_ref: string | null;
  memory_type: string;
}

function rowToVectorRow(r: Row): VectorRow {
  return {
    id: r.id,
    scope: r.scope as VectorScope,
    entry_id: r.entry_id,
    content_text: r.content_text,
    embedding: new Float32Array(r.embedding.buffer, r.embedding.byteOffset, r.embedding.byteLength / 4),
    model_id: r.model_id,
    ts: r.ts,
    media_type: r.media_type === 'image' ? 'image' : 'text',
    media_ref: r.media_ref,
    memory_type: r.memory_type === 'semantic' || r.memory_type === 'procedural' ? r.memory_type : 'episodic',
  };
}

export class VectorStore {
  private readonly db: DatabaseT;
  private readonly provider: EmbeddingProvider;

  constructor(db: DatabaseT, provider: EmbeddingProvider) {
    this.db = db;
    this.provider = provider;
    // Apply the schema lazily (we don't own migrations; callers
    // can also include these statements in their own migration
    // list if they prefer).
    this.db.exec(SQL_CREATE_TABLE);
    this.db.exec(SQL_CREATE_TS_INDEX);
    this.db.exec(SQL_CREATE_SCOPE_INDEX);
    this.db.exec(SQL_CREATE_MEDIA_INDEX);
    this.db.exec(SQL_CREATE_MEMORY_TYPE_INDEX);
  }

  /** The embedding provider this store is bound to. */
  get modelId(): string { return this.provider.modelId; }
  get dim(): number { return this.provider.dim; }

  /** Total number of indexed rows. */
  count(scope?: VectorScope): number {
    if (scope === undefined) {
      const row = this.db.prepare(SQL_COUNT).get() as { n: number };
      return row.n;
    }
    const row = this.db.prepare(`SELECT COUNT(*) AS n FROM vec_index WHERE scope = ?`).get(scope) as { n: number };
    return row.n;
  }

  /** Embed + upsert. The provider's modelId is stored alongside so
   *  later searches can detect a mismatch. The optional
   *  `mediaType` (default 'text') and `mediaRef` (file path or
   *  SHA-256 for image rows) are stored for multimodal
   *  retrieval. The optional `memoryType` (Tulving: episodic /
   *  semantic / procedural, default 'episodic') drives the
   *  type-based filter in `search`. */
  upsert(
    scope: VectorScope,
    entryId: string,
    contentText: string,
    ts: number = Date.now(),
    media: {
      mediaType?: 'text' | 'image';
      mediaRef?: string | null;
      memoryType?: 'episodic' | 'semantic' | 'procedural';
    } = {},
  ): void {
    const embedding = this.provider.embed(contentText);
    this.upsertEmbedding(scope, entryId, contentText, embedding, ts, media);
  }

  /** Upsert with a pre-computed embedding (used by batch embed paths). */
  upsertEmbedding(
    scope: VectorScope,
    entryId: string,
    contentText: string,
    embedding: Float32Array,
    ts: number = Date.now(),
    media: {
      mediaType?: 'text' | 'image';
      mediaRef?: string | null;
      memoryType?: 'episodic' | 'semantic' | 'procedural';
    } = {},
  ): void {
    if (embedding.length !== this.dim) {
      throw new Error(
        `VectorStore.upsertEmbedding: embedding length ${embedding.length} does not match provider dim ${this.dim}`,
      );
    }
    const buf = Buffer.from(embedding.buffer, embedding.byteOffset, embedding.byteLength);
    runStatement(this.db, SQL_UPSERT, [
      scope, entryId, contentText, buf, this.provider.modelId, ts,
      media.mediaType ?? 'text',
      media.mediaRef ?? null,
      media.memoryType ?? 'episodic',
    ]);
  }

  /** Delete one row by (scope, entry_id). */
  delete(scope: VectorScope, entryId: string): { changes: number } {
    const info = runStatement(this.db, SQL_DELETE_BY_ENTRY, [scope, entryId]);
    return { changes: info.changes };
  }

  /** Delete every row in a scope. */
  clearScope(scope: VectorScope): { changes: number } {
    const info = runStatement(this.db, SQL_DELETE_ALL_BY_SCOPE, [scope]);
    return { changes: info.changes };
  }

  /** Drop the entire index. Used by `rebuild` and by tests. */
  clearAll(): { changes: number } {
    const info = runStatement(this.db, SQL_DELETE_ALL);
    return { changes: info.changes };
  }

  /** Brute-force cosine search. Iterates every matching row,
   *  computes cosine similarity, returns topK above threshold.
   *  Honours the optional `memoryType` filter (R-MEM-5.3). */
  search(params: VectorSearchParams): VectorSearchResult {
    const topK = params.topK ?? 10;
    const threshold = params.threshold ?? 0.0;
    if (params.query.length !== this.dim) {
      throw new Error(
        `VectorStore.search: query length ${params.query.length} does not match provider dim ${this.dim}`,
      );
    }
    const t0 = performance.now();
    const raw = this.db.prepare(SQL_LIST_BY_SCOPE).all(
      params.scope ?? null,
      params.scope ?? null,
    ) as Row[];
    const hits: VectorRow[] = [];
    for (const r of raw) {
      if (params.entryId !== undefined && r.entry_id === params.entryId) continue;
      if (r.model_id !== this.provider.modelId) continue; // skip stale-model rows
      if (params.memoryType !== undefined && r.memory_type !== params.memoryType) continue;
      const row = rowToVectorRow(r);
      const score = cosineSimilarity(params.query, row.embedding);
      if (score < threshold) continue;
      hits.push({ ...row, score });
    }
    hits.sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
    return {
      rows: hits.slice(0, topK),
      totalScanned: raw.length,
      ms: performance.now() - t0,
    };
  }

  /** Embed a batch of (scope, entryId, contentText) tuples and
   *  upsert them. Useful for warming the index from a fresh
   *  memory dump. The optional `mediaType` / `mediaRef` /
   *  `memoryType` are passed through to `upsertEmbedding`. */
  bulkUpsert(
    items: ReadonlyArray<{
      scope: VectorScope;
      entryId: string;
      contentText: string;
      ts?: number;
      mediaType?: 'text' | 'image';
      mediaRef?: string | null;
      memoryType?: 'episodic' | 'semantic' | 'procedural';
    }>,
  ): void {
    if (items.length === 0) return;
    const texts = items.map((it) => it.contentText);
    const embeddings = this.provider.embedBatch(texts);
    const tx = this.db.transaction((): void => {
      for (let i = 0; i < items.length; i += 1) {
        const it = items[i]!;
        const emb = embeddings[i]!;
        this.upsertEmbedding(it.scope, it.entryId, it.contentText, emb, it.ts ?? Date.now(), {
          mediaType: it.mediaType,
          mediaRef: it.mediaRef,
          memoryType: it.memoryType,
        });
      }
    });
    tx();
  }
}

/** Cosine similarity between two L2-normalised vectors (just a dot
 *  product). Falls back to a normalised dot product if either
 *  vector isn't unit-length (shouldn't happen with our provider,
 *  but the function is robust to bad input). */
export function cosineSimilarity(a: Float32Array, b: Float32Array): number {
  if (a.length !== b.length) {
    throw new Error(`cosineSimilarity: length mismatch ${a.length} vs ${b.length}`);
  }
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let i = 0; i < a.length; i += 1) {
    const ai = a[i]!;
    const bi = b[i]!;
    dot += ai * bi;
    na += ai * ai;
    nb += bi * bi;
  }
  const denom = Math.sqrt(na) * Math.sqrt(nb);
  if (denom === 0) return 0;
  // Clamp to [0, 1] for callers that treat score as similarity.
  const v = dot / denom;
  if (v < 0) return 0;
  if (v > 1) return 1;
  return v;
}
