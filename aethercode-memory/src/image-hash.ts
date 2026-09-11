/**
 * R-MEM-5.1 (F4 Multimodal): tiny image hashing helpers.
 *
 * Multimodal memory doesn't have a real vision encoder wired up
 * yet — the agent gets a text description and (optionally) a
 * reference to the original image asset. The hash helpers here
 * let callers:
 *
 *  - `sha256File(path)`: compute the SHA-256 of a file's
 *    contents. Use this as the `media_ref` so identical images
 *    collapse to the same key (and so the original can be
 *    located later by hash).
 *  - `sha256Buffer(buf)`: same, for in-memory buffers
 *    (e.g. clipboard pastes).
 *  - `embedTextForImage(description, mediaRef)`: build the
 *    embedding input the provider sees. The hash is included
 *    as a token so two distinct images with the same
 *    description still embed slightly differently (gives a
 *    weak image fingerprint for the hash fallback provider).
 */

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

/** Compute the SHA-256 of a file's contents. */
export function sha256File(filePath: string): string {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

/** Compute the SHA-256 of an in-memory buffer. */
export function sha256Buffer(buf: Buffer | Uint8Array): string {
  return createHash('sha256').update(buf).digest('hex');
}

/** Build the text we pass to the embedding provider for an
 *  image row. The description is the user-visible label; the
 *  mediaRef (or hash) is appended so the hash provider has
 *  something to differentiate identical descriptions. */
export function embedTextForImage(description: string, mediaRef: string | null): string {
  if (mediaRef === null || mediaRef.length === 0) {
    return description;
  }
  return `${description} [${mediaRef.slice(0, 16)}]`;
}
