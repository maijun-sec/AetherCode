/**
 * R-MEM-1: deterministic hash-based embedding provider.
 *
 * The default embedding provider is a pure-JS, dependency-free
 * implementation that turns text into a 384-dimensional float vector
 * via a token-bag hash + L2 normalization. It is:
 *
 *  - **Deterministic** (same text → same vector, across processes
 *    and across runs), so the BLOB-persisted index is stable.
 *  - **Dependency-free** (no ONNX runtime, no model download, no
 *    network). It works offline and ships as part of the build.
 *  - **Cheap** (microseconds per call), so a `memory/find` round
 *    trip dominated by SQLite BLOB reads is fast.
 *
 * The quality is obviously lower than a trained model — tokens that
 * don't share a hash bucket get zero similarity, even if they share
 * semantic meaning. But it gives the rest of the system a real
 * vector index to develop against. R-MEM-1.x is the place to swap
 * in a real model (e.g. all-MiniLM-L6 via transformers.js) once
 * the rest of the pipeline is stable.
 *
 * Tokenisation: lowercase, split on `/[^\p{L}\p{N}]+/u` so Chinese /
 * Japanese / Korean characters stay as single tokens (the
 * 384-dimensional budget is shared across all Unicode word
 * boundaries, not per-script).
 *
 * Hashing: FNV-1a 32-bit hash of each token, then a sign bit
 * derived from a second FNV-1a hash (hashed-sign trick from
 * RandomIndexing / SimHash) to give each dimension a +1 / -1
 * contribution. The 384 dimensions are accumulated with the
 * square-root scaling `1 / sqrt(count)` so high-frequency tokens
 * don't dominate.
 */

/** Standard embedding dimension used across the memory module. */
export const DEFAULT_EMBEDDING_DIM = 384;

/** Hash a single token to a 32-bit unsigned int. */
function fnv1a(token: string, seed: number): number {
  let h = seed >>> 0;
  for (let i = 0; i < token.length; i += 1) {
    h ^= token.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** Split text into a bag of normalised tokens. The pattern is
 *  Unicode-aware so non-ASCII scripts (CJK, Cyrillic, etc.) don't
 *  collapse into single characters. */
function tokenise(text: string): string[] {
  if (!text) return [];
  const lower = text.toLowerCase();
  const out: string[] = [];
  // /u flag: treat surrogate pairs as single code points
  // /[^\p{L}\p{N}]+/u: split on anything that isn't a letter or number
  const parts = lower.split(/[^\p{L}\p{N}]+/u);
  for (const p of parts) {
    if (p.length > 0) out.push(p);
  }
  return out;
}

export class HashEmbeddingProvider {
  readonly modelId: string;
  readonly dim: number;

  constructor(dim: number = DEFAULT_EMBEDDING_DIM) {
    if (dim <= 0 || !Number.isFinite(dim) || !Number.isInteger(dim)) {
      throw new Error(`HashEmbeddingProvider: dim must be a positive integer, got ${dim}`);
    }
    this.dim = dim;
    this.modelId = `hash-v1-dim${dim}`;
  }

  /** Embed a single string. */
  embed(text: string): Float32Array {
    const tokens = tokenise(text);
    const v = new Float32Array(this.dim);
    if (tokens.length === 0) return v;
    // Count tokens so we can apply IDF-style sub-linear scaling.
    const counts = new Map<string, number>();
    for (const t of tokens) {
      counts.set(t, (counts.get(t) ?? 0) + 1);
    }
    const scale = 1 / Math.sqrt(counts.size);
    for (const [token, count] of counts) {
      const dimHash = fnv1a(token, 0x811c9dc5);
      const signHash = fnv1a(token, 0x9e3779b1);
      const idx = dimHash % this.dim;
      const sign = (signHash & 1) === 0 ? 1 : -1;
      // Sub-linear TF: 1 / sqrt(count) so repeated tokens don't dominate.
      v[idx] += sign * scale / Math.sqrt(count);
    }
    // L2-normalise so cosine similarity is a single dot product.
    let norm = 0;
    for (let i = 0; i < this.dim; i += 1) norm += v[i] * v[i];
    norm = Math.sqrt(norm);
    if (norm > 0) {
      for (let i = 0; i < this.dim; i += 1) v[i] /= norm;
    }
    return v;
  }

  /** Embed a batch of strings. One-pass, same algorithm as embed. */
  embedBatch(texts: ReadonlyArray<string>): Float32Array[] {
    return texts.map((t) => this.embed(t));
  }
}
