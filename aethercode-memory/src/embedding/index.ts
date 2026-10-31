// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
