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
 * R-MEM-6.1: tests for the image-bytes embedding provider.
 *
 * Pin the bytes-aware embedding semantics: two different image
 * payloads produce different vectors (even with the same
 * description), and identical image bytes produce identical
 * vectors. The text path is intentionally rejected so callers
 * can't accidentally fall back to description-hash behaviour.
 */

import { describe, it, expect } from 'vitest';
import {
  ImageBytesEmbeddingProvider,
} from '../embedding/image-bytes-embedding.js';
import { HashEmbeddingProvider } from '../embedding/hash-embedding.js';
import { cosineSimilarity } from '../vec-store.js';

function bytes(s: string): Buffer {
  return Buffer.from(s, 'binary');
}

describe('R-MEM-6.1: ImageBytesEmbeddingProvider', () => {
  it('different image bytes produce different embeddings (the R-MEM-5.1 fix)', () => {
    const p = new ImageBytesEmbeddingProvider(384);
    // Use realistic-ish sizes: a 256-byte buffer (a tiny PNG
    // header) with the differing bytes scattered throughout, not
    // a single-character difference at offset 0.
    const a = Buffer.alloc(256);
    const b = Buffer.alloc(256);
    for (let i = 0; i < 256; i += 1) {
      a[i] = (i * 7 + 11) & 0xff;
      b[i] = (i * 7 + 13) & 0xff; // same arithmetic, different constant
    }
    const va = p.embedImage({ bytes: a, description: 'screenshot' });
    const vb = p.embedImage({ bytes: b, description: 'screenshot' });
    // Different bytes → cosine should be low, NOT close to 1.
    expect(cosineSimilarity(va, vb)).toBeLessThan(0.95);
  });

  it('identical image bytes produce identical embeddings', () => {
    const p = new ImageBytesEmbeddingProvider(384);
    const buf = bytes('same-image-content');
    const a = p.embedImage({ bytes: buf, description: 'one' });
    const b = p.embedImage({ bytes: buf, description: 'two' });
    // Same bytes → cosine should be 1 (or very close).
    expect(cosineSimilarity(a, b)).toBeGreaterThan(0.99);
  });

  it('embeddings are L2-normalised', () => {
    const p = new ImageBytesEmbeddingProvider(384);
    const v = p.embedImage({ bytes: bytes('anything') });
    let sum = 0;
    for (let i = 0; i < v.length; i += 1) sum += v[i]! * v[i]!;
    const norm = Math.sqrt(sum);
    expect(Math.abs(norm - 1)).toBeLessThan(1e-5);
  });

  it('modelId is distinct from HashEmbeddingProvider', () => {
    const ip = new ImageBytesEmbeddingProvider(384);
    const hp = new HashEmbeddingProvider(384);
    expect(ip.modelId).not.toBe(hp.modelId);
    expect(ip.modelId).toContain('image');
  });

  it('text embed() rejects (forces callers to use embedImage)', () => {
    const p = new ImageBytesEmbeddingProvider(384);
    expect(() => p.embed('hello')).toThrow(/not supported/);
    expect(() => p.embedBatch(['hello'])).toThrow(/not supported/);
  });

  it('embedBatchImage matches single-call embedImage', () => {
    const p = new ImageBytesEmbeddingProvider(384);
    const inputs = [
      { bytes: bytes('a') },
      { bytes: bytes('b') },
      { bytes: bytes('c') },
    ];
    const batch = p.embedBatchImage(inputs);
    expect(batch).toHaveLength(3);
    for (let i = 0; i < inputs.length; i += 1) {
      const single = p.embedImage(inputs[i]!);
      expect(batch[i]!).toEqual(single);
    }
  });
});
