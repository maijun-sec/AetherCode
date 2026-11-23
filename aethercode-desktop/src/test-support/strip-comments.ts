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
 * Strip comments from a source file, WITHOUT being fooled by
 * comment-looking text inside string literals.
 *
 * R395 (2026-11-23) — WHY this exists.
 *
 * The "this API must be gone" pins were failing on the very comments
 * that explained WHY it is gone:
 *
 *   // R395: the `PreWarmInfo` interface is GONE. It described the
 *
 * The pin wanted to say "the CODE no longer carries this name". The
 * comment is not code — and it is the most useful thing in the file.
 * So the pin has to read the file with the comments removed, or it
 * has to be written so that the explanation cannot trip it (which
 * means the explanation cannot be written freely).
 *
 * A naive stripper is worse than none:
 *   • `indexOf('//')` would eat `http://127.0.0.1` and delete the
 *     rest of the line — including real code;
 *   • treating every `'` as a string delimiter breaks Rust lifetimes
 *     (`State<'_, AppState>`) and Rust char literals (`'a'`).
 *
 * Hence the language parameter: TypeScript has no lifetimes, Rust has
 * both lifetimes and char literals.
 *
 * Lesson 1212: a scanner with no self-test has its bugs found by
 * whichever gate happens to trip over them. `strip-comments.test.ts`
 * is that self-test — read it before trusting this.
 */

export type SourceLang = 'ts' | 'rust';

/** Copy a `"…"` / `'…'` / `` `…` `` literal verbatim, starting at the
 *  opening quote. Returns the index AFTER the closing quote.
 *
 *  Note: a template literal containing a nested template (`` `${`x`}` ``)
 *  ends early. That is harmless here — the worst case is that a comment
 *  inside the interpolation is not stripped. */
function scanQuoted(src: string, start: number, quote: string): number {
  let i = start + 1;
  while (i < src.length) {
    const c = src[i]!;
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === quote) return i + 1;
    i++;
  }
  return src.length;
}

/** Rust only: decide what a `'` opens.
 *
 *  - `'a'`  → a one-character literal
 *  - `'\n'` → an escaped literal
 *  - `'_`   → a LIFETIME (`State<'_, AppState>`) — not a string
 *
 *  Returns the index AFTER the literal, or `start` when this quote is
 *  a lifetime (the caller then copies just the quote). */
function scanRustSingle(src: string, start: number): number {
  const n = src.length;
  const c1 = src[start + 1];
  if (c1 === undefined) return start;
  if (c1 === '\\') {
    let i = start + 2;
    while (i < n && src[i] !== "'") {
      if (src[i] === '\\') i++;
      i++;
    }
    return i < n ? i + 1 : start;
  }
  if (src[start + 2] === "'") return start + 3;
  return start;
}

/**
 * Remove line comments (`//`) and block comments (`/* … *\/`) from
 * `src`, leaving string literals intact.
 *
 * Block comments are replaced by a single space so the tokens on
 * either side do not glue together (an identifier, a block comment
 * and another identifier must stay three tokens, not two).
 */
export function stripComments(src: string, lang: SourceLang = 'ts'): string {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const ch = src[i]!;
    const next = src[i + 1];
    if (ch === '/' && next === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    if (ch === '/' && next === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i = Math.min(i + 2, n);
      out += ' ';
      continue;
    }
    if (ch === '"' || ch === '`') {
      const end = scanQuoted(src, i, ch);
      out += src.slice(i, end);
      i = end;
      continue;
    }
    if (ch === "'") {
      if (lang === 'rust') {
        const end = scanRustSingle(src, i);
        if (end > i) {
          out += src.slice(i, end);
          i = end;
          continue;
        }
        // a lifetime — copy the quote and carry on
        out += ch;
        i++;
        continue;
      }
      const end = scanQuoted(src, i, "'");
      out += src.slice(i, end);
      i = end;
      continue;
    }
    out += ch;
    i++;
  }
  return out;
}
