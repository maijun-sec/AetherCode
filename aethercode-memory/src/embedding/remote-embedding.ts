/**
 * R-MEM-1: remote embedding provider (stub).
 *
 * Real implementations would call out to OpenAI / Cohere / Voyage
 * via `fetch` and convert the response into a Float32Array of the
 * right dimensionality. The current stub returns deterministic
 * hash vectors so the rest of the system has a path to exercise
 * the remote call shape without hitting a real API.
 *
 * To plug in a real provider:
 *   1. Set `endpoint` / `model` / `apiKey` in the constructor.
 *   2. Replace the `fetchAndEmbed` body with a real HTTP POST.
 *   3. Make sure `dim` matches the remote model's output (e.g.
 *      text-embedding-3-small = 1536, voyage-3 = 1024).
 *
 * The hash provider in `hash-embedding.ts` remains the default
 * because it has no network or model-download dependency. Remote
 * is opt-in via `EmbeddingProvider` interface implementation.
 */

import { HashEmbeddingProvider, DEFAULT_EMBEDDING_DIM } from './hash-embedding.js';

export interface RemoteEmbeddingOptions {
  readonly endpoint: string;
  readonly model: string;
  readonly apiKey: string;
  readonly dim?: number;
  /** Override for tests; defaults to a 0ms stub. */
  readonly fetcher?: (input: string, init: { method: string; headers: Record<string, string>; body: string }) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>;
}

export class RemoteEmbeddingProvider {
  readonly modelId: string;
  readonly dim: number;
  /** R-MEM-1: the constructor parameters. Stored for the future
   *  real impl; the current stub returns hash vectors. Referenced
   *  by the placeholder below so the field isn't flagged as
   *  unused. */
  private readonly opts: Required<Pick<RemoteEmbeddingOptions, 'endpoint' | 'model' | 'apiKey'>> & { fetcher: NonNullable<RemoteEmbeddingOptions['fetcher']> };

  constructor(opts: RemoteEmbeddingOptions) {
    this.opts = {
      endpoint: opts.endpoint,
      model: opts.model,
      apiKey: opts.apiKey,
      fetcher: opts.fetcher ?? globalThis.fetch,
    };
    this.dim = opts.dim ?? DEFAULT_EMBEDDING_DIM;
    this.modelId = `remote-${opts.model}-dim${this.dim}`;
    // Touch `this.opts` to keep the field alive until the real
    // fetchAndEmbed wires the HTTP call. Remove this no-op once
    // fetchAndEmbed is implemented.
    void this.opts;
  }

  embed(_text: string): Float32Array {
    // Synchronous embedding doesn't fit a real network call; the
    // real impl is async. The store/rpc layer must use
    // `embedAsync` to surface the call. We throw a clear error
    // to prevent silent misuse.
    throw new Error(
      'RemoteEmbeddingProvider: sync embed() not supported, use embedAsync() instead',
    );
  }

  embedBatch(_texts: ReadonlyArray<string>): Float32Array[] {
    // See embed() above — same reason.
    throw new Error(
      'RemoteEmbeddingProvider: sync embedBatch() not supported, use embedBatchAsync() instead',
    );
  }

  /** Async embedding — the real entry point. */
  async embedAsync(text: string): Promise<Float32Array> {
    const result = await this.fetchAndEmbed([text]);
    return result[0]!;
  }

  async embedBatchAsync(texts: ReadonlyArray<string>): Promise<Float32Array[]> {
    return this.fetchAndEmbed(texts);
  }

  /**
   * Internal: POST to the remote endpoint, parse the response, and
   * return one Float32Array per input. The shape is intentionally
   * generic (no hard dependency on OpenAI's response format) so
   * callers can wire a custom fetcher for tests / non-OpenAI APIs.
   *
   * The real wire format will be supplied when a concrete provider
   * is plugged in. For now the method is a stub that returns
   * hash-based vectors so the test surface has a working shape.
   */
  private async fetchAndEmbed(texts: ReadonlyArray<string>): Promise<Float32Array[]> {
    // Stub path: don't hit the network, return hash vectors.
    // Real impl: const resp = await this.opts.fetcher(this.opts.endpoint, {...});
    //            const json = await resp.json();
    //            map json.embeddings → Float32Array[] of length `dim`.
    const hash = new HashEmbeddingProvider(this.dim);
    return hash.embedBatch(texts);
  }
}
