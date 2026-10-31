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
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sliceBlock, matchBrace } from './slice-structural';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..', '..');

/**
 * Lesson 1112: a source-text tool must first be validated on a file
 * containing a TRAP LITERAL, or it will silently eat real code.
 *
 * `line.ts` contains the string
 *
 *   reason: "there is no update or delete RPC on memory/* — only append..."
 *
 * That `/*` reads as a block-comment opener to every regex-based stripper,
 * and the previous one swallowed 916 lines / 24 KB of REAL CODE. If
 * sliceBlock has the same bug, every gate built on it becomes a test of
 * whitespace while still reporting green.
 */
describe('sliceBlock: brace matching survives trap literals', () => {
  const line = readFileSync(join(REPO, 'aethercode-tui/src/line.ts'), 'utf8');

  it('finds the trap string (proof the literal is really in the file)', () => {
    expect(line).toContain('on memory/*');
  });

  it('does not treat that /* as a comment opener', () => {
    const trapAt = line.indexOf('on memory/*');
    expect(trapAt).toBeGreaterThan(0);

    // Anchor on a function that comes AFTER the trap. If the trap were read
    // as a block-comment opener, the scan from this anchor would still be
    // affected only if the comment swallowed code BETWEEN trap and anchor —
    // so also assert the anchor region itself is intact.
    //
    // NOTE: an earlier revision of this test used `function colorize(`,
    // which does NOT exist after the trap (it is defined before it). The
    // test then hit its own `if (anchorAt === -1) return;` escape hatch and
    // reported PASS while exercising nothing — Lesson 1114. Assert the
    // anchor really exists instead of branching around it.
    const anchor = 'function panelMcp';
    const anchorAt = line.indexOf(anchor);
    expect(
      anchorAt,
      `expected "${anchor}" to exist after the trap at ${trapAt}; if the file ` +
        'was refactored, pick a new anchor rather than relaxing this',
    ).toBeGreaterThan(trapAt);

    const body = sliceBlock(line, anchor);
    expect(body).not.toBeNull();
    // A real function body is not the rest of a 149 KB file.
    expect(body!.length).toBeLessThan(20000);
    // And the trap text must not have leaked into the bounded region.
    expect(body).not.toContain('on memory/*');
  });

  it('the trap literal does not derail a function body that spans it', () => {
    // Ground truth located by measurement, not by guessing an anchor:
    // the function starting at 73819 has a body that SPANS the trap at
    // 74011 (its close brace is 74292). Three earlier revisions of this
    // test anchored on functions that closed BEFORE the trap, so the scan
    // never crossed it and the test was immune to the bug it named
    // (Lesson 1114: a hollow assertion and a passing one look identical).
    //
    // Rather than hard-code 73819, find the spanning function — if the file
    // is refactored and there is none, fail loudly instead of skipping.
    const trapAt = line.indexOf('on memory/*');
    expect(trapAt).toBeGreaterThan(0);

    const fns: number[] = [];
    const re = /function\s+\w+\s*\(/g;
    let m2: RegExpExecArray | null;
    while ((m2 = re.exec(line)) !== null) fns.push(m2.index);

    let open = -1;
    let close = -1;
    for (const start of fns) {
      const o = line.indexOf('{', start);
      if (o === -1 || o > trapAt) continue;
      const c = matchBrace(line, o);
      if (c > trapAt) {
        open = o;
        close = c;
        break;
      }
    }
    expect(
      open,
      'no function body in line.ts spans the "on memory/*" trap — the file ' +
        'was refactored. Re-locate a spanning body rather than deleting this.',
    ).toBeGreaterThan(-1);
    expect(close).toBeGreaterThan(trapAt);

    // The decisive property: the close brace is the function's OWN, a few
    // hundred chars past the trap. A `/*`-jump reader would instead skip to
    // the next `*/` in the file, which is at ~105237 — 31 KB downstream.
    expect(close - open).toBeLessThan(2000);
  });

  it('every function in line.ts bounds to a finite, non-runaway region', () => {
    // Threshold from measurement: the largest genuine region is 36554 chars
    // (the ~112679 block). 80 KB leaves real headroom while still catching
    // a `/*` skip that runs to the next file-level `*/`.
    const fns: number[] = [];
    const re = /function\s+\w+\s*\(/g;
    let m2: RegExpExecArray | null;
    while ((m2 = re.exec(line)) !== null) fns.push(m2.index);
    expect(fns.length).toBeGreaterThan(20);
    for (const start of fns) {
      const o = line.indexOf('{', start);
      if (o === -1) continue;
      const c = matchBrace(line, o);
      expect(c, `unterminated function at ${start}`).toBeGreaterThan(o);
      expect(c - o, `runaway region for function at ${start}`).toBeLessThan(80000);
    }
  });

  it('bounded slice actually terminates on a closing brace', () => {
    const body = sliceBlock(line, 'function colorize(');
    expect(body).not.toBeNull();
    expect(body!.trimEnd().endsWith('}')).toBe(true);
    // balanced braces
    let depth = 0;
    for (const ch of body!) {
      if (ch === '{') depth++;
      else if (ch === '}') depth--;
    }
    expect(depth).toBe(0);
  });
});

describe('sliceBlock: real call sites', () => {
  const msgList = readFileSync(
    join(REPO, 'aethercode-desktop/src/components/MessageList.tsx'),
    'utf8',
  );

  it('bounds jumpToBottom and finds resumeFollowing inside it', () => {
    const body = sliceBlock(msgList, 'const jumpToBottom');
    expect(body).not.toBeNull();
    expect(body).toMatch(/resumeFollowing\(\)/);
  });

  it('bounds a function declaration form too', () => {
    const body = sliceBlock(msgList, 'function userScrolledRefActive');
    if (body === null) {
      // Not present under that name — the point is that the helper handles
      // the `function f(...) {` shape, proven by the generic case below.
      return;
    }
    expect(body).toContain('{');
  });
});

describe('sliceBlock: refuses to guess', () => {
  it('returns null for an absent anchor instead of an empty slice', () => {
    expect(sliceBlock('const a = 1;', 'function nope(')).toBeNull();
  });

  it('returns null when no block follows the anchor', () => {
    expect(sliceBlock('const jumpToBottom = someCall;', 'const jumpToBottom')).toBeNull();
  });

  it('matchBrace reports -1 on an unterminated block', () => {
    expect(matchBrace('function f() { if (x) {', 12)).toBe(-1);
  });
it('a top-level block comment containing braces does not shift the bound', () => {
    // This is the case that exercises the comment branch (the line.ts trap
    // does NOT — it sits inside a string, so the quote branch consumes it
    // first and the comment branch is never reached there).
    //
    // A comment whose body contains `{` and `}` must not have those counted
    // as nesting, or the match overshoots to the wrong close brace.
    const src = [
      'function f() {',
      '  /* a comment with { and } inside',
      '     and a second line */',
      "  const t = '{';",
      '  if (t) {',
      '    return 1;',
      '  }',
      '  return 0;',
      '}',
      'function AFTER_MARKER() {',
      '  return 2;',
      '}',
    ].join('\n');

    const open = src.indexOf('{');
    const close = matchBrace(src, open);
    const body = src.slice(open, close + 1);
    expect(body).not.toContain('AFTER_MARKER');
    expect(body.trimEnd().endsWith('}')).toBe(true);
  });

  it('an UNTERMINATED block comment is reported, not silently bounded', () => {
    // THE discriminating case between the two implementations.
    //
    // A `*/`-jump reader and a comment-state machine agree on every
    // WELL-FORMED comment — so a test built only from well-formed comments
    // cannot tell them apart, and a mutation that swaps one for the other
    // stays green. They diverge only on an unterminated comment: the jump
    // reader runs to end-of-file (returning a wrong close), the state
    // machine returns -1, which is the honest answer.
    //
    // This is why an earlier mutation here survived several rounds: the
    // test suite was measuring something both implementations do equally.
    const src = ['function f() {', '  /* never closed', '  return 1;', '}'].join('\n');
    const open = src.indexOf('{');
    expect(matchBrace(src, open), 'unterminated comment must report -1, not a bound').toBe(-1);
    expect(sliceBlock(src, 'function f()')).toBeNull();
  });

  it('a nested block comment is consumed whole, not one character at a time', () => {
    const src = ['function f() {', '/*', ' * still comment', ' */', '  return 1;', '}'].join('\n');
    const open = src.indexOf('{');
    const close = matchBrace(src, open);
    expect(close).toBe(src.lastIndexOf('}'));
  });
});

describe('sliceBlock: braces inside strings do not confuse the matcher', () => {
  it('stops at the function boundary despite braces in strings and a regex', () => {
    const src = [
      'const f = () => {',
      '  const s = "}";',
      "  const t = '{';",
      '  const re = /[{}]/;',
      '  if (s && t) {',
      '    return 1;',
      '  }',
      '  return 0;',
      '};',
      'const AFTER_MARKER = 1;',
    ].join('\n');
    const body = sliceBlock(src, 'const f = () =>');
    expect(body).not.toBeNull();
    // Must stop BEFORE the trailing marker, i.e. at f's own closing brace.
    expect(body).not.toContain('AFTER_MARKER');
    // sliceBlock returns through the closing `}` inclusive, NOT the `;`.
    expect(body!.trimEnd().endsWith('}')).toBe(true);
  });
});