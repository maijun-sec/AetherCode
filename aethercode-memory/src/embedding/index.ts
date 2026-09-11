/**
 * R-MEM-1: embedding provider barrel.
 *
 * The `EmbeddingProvider` interface is the seam between the
 * in-process `MemoryStore` / `vec-store` and the embedding model
 * (hash or remote). Consumers should depend on this interface,
 * not the concrete classes, so they can swap providers without
 * touching the rest of the module.
 *
 * Two providers ship in the box:
 *  - `HashEmbeddingProvider` (default, offline, deterministic)
 *  - `RemoteEmbeddingProvider` (stub, real impl TBD)
 *
 * Sync vs. async contract: the interface is intentionally
 * synchronous (the default hash impl is microsecond-cheap and
 * the store hot path doesn't want to await every entry write).
 * A remote provider must wrap its async work in a sync shim
 * (e.g. block on a promise at construction) or expose a parallel
 * async API. R-MEM-1 keeps the sync shape; async is a future
 * R-MEM-1.x concern.
 */

export { HashEmbeddingProvider, DEFAULT_EMBEDDING_DIM } from './hash-embedding.js';
export { RemoteEmbeddingProvider, type RemoteEmbeddingOptions } from './remote-embedding.js';
export {
  ImageBytesEmbeddingProvider,
  type ImageEmbeddingInput,
} from './image-bytes-embedding.js';

/**
 * The contract every embedding provider must satisfy.
 *
 * `modelId` is persisted alongside every vector so the store
 * can detect when the index was built with a different model
 * (e.g. a model upgrade or downgrade) and trigger a rebuild.
 */
export interface EmbeddingProvider {
  readonly modelId: string;
  readonly dim: number;
  embed(text: string): Float32Array;
  embedBatch(texts: ReadonlyArray<string>): Float32Array[];
}
