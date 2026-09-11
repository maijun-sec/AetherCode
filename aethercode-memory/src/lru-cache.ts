/**
 * LRU cache for the memory layer (T-030 ~ T-035).
 *
 * - Backed by `Map`, which preserves insertion order. The Map's iteration
 *   order is our recency order: the first key is the oldest, the last key
 *   is the newest. On a hit we delete + re-set to move the key to the
 *   tail. On overflow we delete the head.
 * - Configurable capacity (T-031). Default 256.
 * - Optional per-entry TTL in milliseconds (T-032). 0 / undefined = no TTL.
 * - Composite key (T-033): the public API takes `(scope, scopeId, key)`.
 *   Internally we hash it into a single string with a separator that cannot
 *   appear in any of the three components.
 * - Invalidation (T-034): `invalidate(scope, scopeId, key)` removes one
 *   entry; `invalidateScope(scope, scopeId?)` drops all entries for a
 *   scope (or one scopeId inside a scope).
 * - Token tracking (T-035): each entry carries an `insertedAt` (ms),
 *   a `tokenCount` (default 0), and an `expiresAt` (if TTL is set). The
 *   cache keeps a running `totalTokens` so callers can ask "is the cache
 *   over budget?" without iterating.
 *
 * The cache is intentionally a plain object with no async I/O. It's a
 * pure in-process data structure that the higher-level `MemoryStore`
 * composes with sqlite and the jsonl writer.
 */

import type { MemoryScope } from './types.js';

/** A single cached value with metadata. */
export interface LruEntry<V> {
  readonly value: V;
  readonly tokenCount: number;
  /** Epoch ms when the entry was created / last refreshed. */
  readonly insertedAt: number;
  /** Epoch ms when the entry expires. `undefined` if no TTL is in effect. */
  readonly expiresAt?: number;
}

/** Configuration for the cache. */
export interface LruCacheOptions {
  /** Maximum number of entries. Default 256. */
  readonly capacity?: number;
  /** Per-entry TTL in milliseconds. 0 / undefined = no expiry. */
  readonly ttlMs?: number;
  /**
   * Optional clock for tests. Defaults to `Date.now`. The clock is called
   * on every `get` (for TTL checks) so do not pass a slow function.
   */
  readonly now?: () => number;
}

/** Composite key shape accepted by the public API. */
export type CacheKey = readonly [scope: MemoryScope, scopeId: string, key: string];

/** The cache handle. */
export interface LruCache<V> {
  readonly capacity: number;
  readonly ttlMs: number;
  /** Number of entries currently stored. */
  readonly size: number;
  /** Sum of `tokenCount` across all entries. */
  readonly totalTokens: number;
  /** Look up an entry; returns `undefined` on miss or expiry. Updates recency. */
  get(scope: MemoryScope, scopeId: string, key: string): LruEntry<V> | undefined;
  /** Look up without updating recency. */
  peek(scope: MemoryScope, scopeId: string, key: string): LruEntry<V> | undefined;
  /** Store an entry. Evicts the oldest if at capacity. Updates token totals. */
  set(
    scope: MemoryScope,
    scopeId: string,
    key: string,
    value: V,
    tokenCount?: number,
  ): void;
  /** Drop one entry. Returns true if it was present. */
  invalidate(scope: MemoryScope, scopeId: string, key: string): boolean;
  /** Drop every entry for a scope; optionally scoped to one scopeId. */
  invalidateScope(scope: MemoryScope, scopeId?: string): number;
  /** Remove all entries (does not change capacity). */
  clear(): void;
  /** Snapshot the keys in recency order (oldest first). */
  keys(): ReadonlyArray<CacheKey>;
  /** Return true if the cache is at or above `ratio * capacity`. */
  isFull(ratio?: number): boolean;
}

const DEFAULT_CAPACITY = 256;
const SEP = '\u0000'; // separator that cannot appear in user-supplied strings

/**
 * Create a new LRU cache.
 *
 * Capacity is clamped to a minimum of 1. Setting capacity to 0 throws (the
 * cache would be unusable). ttlMs is clamped to a non-negative integer.
 */
export function createLruCache<V>(options: LruCacheOptions = {}): LruCache<V> {
  const capacity = clampCapacity(options.capacity ?? DEFAULT_CAPACITY);
  const ttlMs = clampTtl(options.ttlMs ?? 0);
  const now = options.now ?? ((): number => Date.now());

  // Map preserves insertion order. The first key is the oldest (LRU).
  const store = new Map<string, LruEntry<V>>();
  let totalTokens = 0;

  const composite = (scope: MemoryScope, scopeId: string, key: string): string =>
    `${scope}${SEP}${scopeId}${SEP}${key}`;

  const isExpired = (entry: LruEntry<V>): boolean =>
    entry.expiresAt !== undefined && entry.expiresAt <= now();

  return {
    capacity,
    ttlMs,
    get size(): number {
      return store.size;
    },
    get totalTokens(): number {
      return totalTokens;
    },
    get(scope, scopeId, key) {
      const k = composite(scope, scopeId, key);
      const entry = store.get(k);
      if (entry === undefined) return undefined;
      if (isExpired(entry)) {
        store.delete(k);
        totalTokens -= entry.tokenCount;
        return undefined;
      }
      // Move to the tail (most recent).
      store.delete(k);
      store.set(k, entry);
      return entry;
    },
    peek(scope, scopeId, key) {
      const entry = store.get(composite(scope, scopeId, key));
      if (entry === undefined) return undefined;
      if (isExpired(entry)) {
        store.delete(composite(scope, scopeId, key));
        totalTokens -= entry.tokenCount;
        return undefined;
      }
      return entry;
    },
    set(scope, scopeId, key, value, tokenCount = 0) {
      const k = composite(scope, scopeId, key);
      const prev = store.get(k);
      if (prev !== undefined) {
        totalTokens -= prev.tokenCount;
        store.delete(k);
      }
      const insertedAt = now();
      const expiresAt = ttlMs > 0 ? insertedAt + ttlMs : undefined;
      const entry: LruEntry<V> = { value, tokenCount, insertedAt, ...(expiresAt !== undefined ? { expiresAt } : {}) };
      store.set(k, entry);
      totalTokens += tokenCount;
      while (store.size > capacity) {
        const oldest = store.keys().next();
        if (oldest.done) break;
        const oldestKey = oldest.value;
        const oldestEntry = store.get(oldestKey);
        store.delete(oldestKey);
        if (oldestEntry !== undefined) totalTokens -= oldestEntry.tokenCount;
      }
    },
    invalidate(scope, scopeId, key) {
      const k = composite(scope, scopeId, key);
      const entry = store.get(k);
      if (entry === undefined) return false;
      store.delete(k);
      totalTokens -= entry.tokenCount;
      return true;
    },
    invalidateScope(scope, scopeId) {
      const prefix = scopeId === undefined ? `${scope}${SEP}` : `${scope}${SEP}${scopeId}${SEP}`;
      let removed = 0;
      for (const k of Array.from(store.keys())) {
        if (k.startsWith(prefix)) {
          const entry = store.get(k);
          store.delete(k);
          if (entry !== undefined) {
            totalTokens -= entry.tokenCount;
            removed += 1;
          }
        }
      }
      return removed;
    },
    clear() {
      store.clear();
      totalTokens = 0;
    },
    keys() {
      const out: CacheKey[] = [];
      for (const k of store.keys()) {
        const idx1 = k.indexOf(SEP);
        const idx2 = k.indexOf(SEP, idx1 + 1);
        if (idx1 < 0 || idx2 < 0) continue;
        const scope = k.slice(0, idx1) as MemoryScope;
        const scopeId = k.slice(idx1 + 1, idx2);
        const key = k.slice(idx2 + 1);
        out.push([scope, scopeId, key] as const);
      }
      return out;
    },
    isFull(ratio = 1) {
      if (ratio < 0) return true;
      if (ratio > 1) ratio = 1;
      return store.size >= Math.ceil(capacity * ratio);
    },
  };
}

function clampCapacity(n: number): number {
  if (!Number.isFinite(n)) throw new Error('createLruCache: capacity must be a finite number');
  if (n < 1) throw new Error('createLruCache: capacity must be >= 1');
  return Math.floor(n);
}

function clampTtl(n: number): number {
  if (!Number.isFinite(n)) throw new Error('createLruCache: ttlMs must be a finite number');
  if (n < 0) throw new Error('createLruCache: ttlMs must be >= 0');
  return Math.floor(n);
}
