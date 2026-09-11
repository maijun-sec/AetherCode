/**
 * R-MEM-6.1 (F4+ real vision encoder): image-bytes embedding.
 *
 * The R-MEM-5.1 `HashEmbeddingProvider` hashes the *description*
 * text. That made image memory effectively a text-memory
 * labelled "image" — two images with the same caption got the
 * same embedding, so search couldn't tell them apart.
 *
 * This provider fixes that by hashing the *raw image bytes*,
 * not the description. The signature is the same (Float32Array
 * of the configured dim, L2-normalised), so it slots into the
 * `EmbeddingProvider` interface unchanged — the difference is
 * the input contract: pass an `ImageEmbeddingInput` whose
 * `bytes` is the raw image payload, and the provider will
 * produce a vector that depends on the actual image content.
 *
 * Hashing details: we don't reuse `HashEmbeddingProvider.embed`
 * because that function strips non-alphanumeric characters via
 * the text tokenizer, which collapses binary bytes into nearly
 * the same vector. Instead we use a dedicated FNV-1a + sign
 * trick over the raw byte stream, with 16-bit windows so that
 * small changes in the byte stream produce large changes in
 * the resulting vector.
 *
 * This is the R-MEM-6.1 stepping stone. The real vision encoder
 * (CLIP / OpenCLIP / ViT) lives one round away — it will
 * implement the same `embed` interface but route to an ONNX
 * runtime and return a real semantic vector. The wire shape
 * doesn't change.
 */

import { DEFAULT_EMBEDDING_DIM } from './hash-embedding.js';

/** Input to the image-bytes provider. */
export interface ImageEmbeddingInput {
  /** Raw image bytes (any format; PNG / JPEG / WebP / etc.).
   *  The provider doesn't decode the image — it just hashes the
   *  raw byte stream. A real CLIP impl would decode + embed. */
  readonly bytes: Buffer;
  /** Optional human description. The R-MEM-5.1 path uses this
   *  as the embedding input directly; the R-MEM-6.1 path
   *  ignores it (the bytes are the source of truth) but still
   *  records it on the vec_index row for human readability. */
  readonly description?: string;
}

/** FNV-1a 32-bit. We use this directly on the byte stream
 *  instead of going through the text tokenizer. */
function fnv1a32(bytes: Buffer, seed: number): number {
  let h = seed;
  for (let i = 0; i < bytes.length; i += 1) {
    h ^= bytes[i]!;
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export class ImageBytesEmbeddingProvider {
  readonly modelId: string;
  readonly dim: number;

  constructor(dim: number = DEFAULT_EMBEDDING_DIM) {
    this.dim = dim;
    this.modelId = `image-bytes-fnv1a-dim${dim}`;
  }

  /** Image-aware embedding. Hashes the image bytes (NOT the
   *  description) so two different images with the same caption
   *  produce different vectors. */
  embedImage(input: ImageEmbeddingInput): Float32Array {
    const v = new Float32Array(this.dim);
    const bytes = input.bytes;
    if (bytes.length === 0) {
      // All zeros is a valid L2-normalised vector only at the
      // origin — but a zero vector has no direction. Pad with
      // a tiny uniform value so the result is still a "nothing
      // to see" point in the embedding space.
      for (let i = 0; i < this.dim; i += 1) v[i] = 1 / this.dim;
    } else {
      // Each output dimension is a sliding window of `WINDOW`
      // bytes over the stream, starting at offset `i * stride`.
      // The stride is chosen so the windows cover the full byte
      // stream and don't all cluster at the start. A per-
      // dimension FNV seed makes each window produce a different
      // number even when the underlying bytes are similar.
      const WINDOW = Math.max(4, Math.min(64, Math.floor(bytes.length / 2)));
      const stride = Math.max(1, Math.floor((bytes.length - WINDOW) / this.dim));
      for (let i = 0; i < this.dim; i += 1) {
        const start = Math.min(i * stride, bytes.length - WINDOW);
        const window = bytes.subarray(start, start + WINDOW);
        // Per-dimension seed: dimensions with the same window
        // still get a different value.
        const seed = 0x811c9dc5 ^ ((i + 1) * 0x9e3779b1);
        v[i] = fnv1a32(window, seed);
      }
    }
    // L2 normalise so cosine similarity = dot product.
    let sum = 0;
    for (let i = 0; i < this.dim; i += 1) sum += v[i]! * v[i]!;
    const norm = Math.sqrt(sum) || 1;
    for (let i = 0; i < this.dim; i += 1) v[i] = v[i]! / norm;
    return v;
  }

  embedBatchImage(inputs: ReadonlyArray<ImageEmbeddingInput>): Float32Array[] {
    return inputs.map((i) => this.embedImage(i));
  }

  /* The text path is intentionally not supported. Callers must
   * use `embedImage` for image content. This avoids accidentally
   * falling back to text-hash behaviour (which is the R-MEM-5.1
   * bug we're fixing here). */
  embed(_text: string): Float32Array {
    throw new Error(
      'ImageBytesEmbeddingProvider: text embed() is not supported. ' +
      'Use embedImage({ bytes }) for image content, or pair this provider ' +
      'with HashEmbeddingProvider for text.',
    );
  }

  embedBatch(_texts: ReadonlyArray<string>): Float32Array[] {
    throw new Error(
      'ImageBytesEmbeddingProvider: text embedBatch() is not supported.',
    );
  }
}
