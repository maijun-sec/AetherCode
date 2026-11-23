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

import { describe, it, expect } from 'vitest';
import { stripComments } from './strip-comments';

/**
 * The scanner's own self-test.
 *
 * Lesson 1212: "a scanner with no self-test has its bugs found by
 * whichever gate happens to trip over them, which is not a plan."
 * This file exists so that a bug in `stripComments` is reported HERE,
 * by the thing that owns it — not by whichever pin happens to read a
 * file first.
 */
describe('strip-comments', () => {
  it('removes a line comment and keeps the code around it', () => {
    expect(stripComments('const a = 1; // a note\nconst b = 2;')).toBe(
      'const a = 1; \nconst b = 2;',
    );
  });

  it('removes a block comment', () => {
    expect(stripComments('a /* gone */ b')).toBe('a   b');
  });

  it('keeps the tokens on either side of a block comment apart', () => {
    // `a/**/b` must not become `ab` — the two tokens would merge
    // into one identifier.
    expect(stripComments('a/**/b')).toBe('a b');
  });

  it('does NOT eat a URL inside a string', () => {
    // The bug that makes a naive stripper useless: `//` inside a
    // literal is not a comment.
    const src = "const u = 'http://127.0.0.1:22888/healthz'; // note";
    const out = stripComments(src);
    expect(out).toContain('http://127.0.0.1:22888/healthz');
    expect(out).not.toContain('note');
  });

  it('does NOT eat a URL inside a double-quoted string', () => {
    const out = stripComments('const u = "https://example.com/a"; // note');
    expect(out).toContain('https://example.com/a');
  });

  it('does NOT eat a URL inside a template literal', () => {
    const out = stripComments('const u = `ws://127.0.0.1/x`; // note');
    expect(out).toContain('ws://127.0.0.1/x');
  });

  it('keeps a TS single-quoted string intact', () => {
    const out = stripComments("const s = 'a // b'; // note");
    expect(out).toContain("'a // b'");
    expect(out).not.toContain('note');
  });

  it('keeps a Rust LIFETIME intact (and does not swallow the file)', () => {
    const src = "fn f(state: State<'_, AppState>) { let x = 1; } // note";
    const out = stripComments(src, 'rust');
    expect(out).toContain("State<'_, AppState>");
    // the code AFTER the lifetime must still be there — a lifetime
    // mistaken for a string delimiter would eat the rest of the file
    expect(out).toContain('let x = 1;');
    expect(out).not.toContain('note');
  });

  it('keeps a Rust char literal intact', () => {
    const out = stripComments("let c = 'a'; // note", 'rust');
    expect(out).toContain("'a'");
    expect(out).toContain('let c =');
  });

  it('keeps a Rust escaped char literal intact', () => {
    const out = stripComments("let c = '\\''; // note", 'rust');
    expect(out).toContain("'\\''");
  });

  it('keeps a Rust string literal intact', () => {
    const out = stripComments('let s = "http://x"; // note', 'rust');
    expect(out).toContain('"http://x"');
    expect(out).not.toContain('note');
  });

  it('a comment marker inside a string inside a comment is still just a comment', () => {
    const out = stripComments("// a '// b' c\nconst x = 1;");
    expect(out).not.toContain("'// b'");
    expect(out).toContain('const x = 1;');
  });
});
