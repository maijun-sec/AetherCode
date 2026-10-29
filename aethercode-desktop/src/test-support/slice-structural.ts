// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// Structural slicing for source-pin gates.
//
// WHY NOT A CHARACTER WINDOW
//
// The long-standing shape in these gates is:
//
//     const jumpIdx = source.indexOf('const jumpToBottom');
//     const body = source.slice(jumpIdx, jumpIdx + 900);
//     expect(body).toMatch(/resumeFollowing\(\)/);
//
// The `900` is a guess about how long the function is. It is correct on the
// day it is written and rots silently the first time someone adds an
// explanatory comment above the line the gate is actually about — the token
// slides past the slice, the assertion fails for the wrong reason, and the
// tempting "fix" is to widen the number. Lesson 1107: a gate that truncates
// still LOOKS like it is auditing, so the drift is invisible.
//
// THE STRUCTURAL BOUND
//
// `sliceFunction(source, anchor)` returns the text from the anchor to the
// matching close brace of the block that follows it. That boundary is a
// property of the CODE, so no amount of commentary can move it. A comment
// added above the anchor does not shift it; a comment added inside the
// function does not either.
//
// This is deliberately a real brace matcher rather than a regex: braces
// appear inside strings, template literals, regex literals and comments
// throughout this codebase, and a naive `/\{[^}]*\}/` will either truncate
// early or run past the end. Both failure modes produce a test that still
// runs and still "passes" — which is the whole problem being solved here.
//
// NOTE ON COMPLEXITY: the matcher is not a JS parser. It handles the
// constructs that actually appear in these files (line/block comments,
// single/double quotes, template literals with `${}` nesting, and a
// prev-token heuristic for regex literals). If it ever meets something it
// cannot classify, it returns null rather than guessing — a gate that says
// "could not bound this" is far better than one that silently bounds the
// wrong region.

/**
 * Classify and skip over one construct starting at `i`. Returns the next
 * index.
 *
 * NOTE: this handles strings, template literals and regex literals ONLY.
 * Comment state belongs to {@link matchBrace}, which tests for `/*`
 * BEFORE calling here. An earlier revision also tried to handle comments
 * in this function, and that branch was dead code: the caller computed
 * `next` first and then discarded it, so whatever this function returned
 * for a `/*` never reached the loop position. Two implementations of one
 * rule, disagreeing, one of them inert. One owner, one implementation.
 */
function skipConstruct(src: string, i: number): number {
  const c = src[i];
  const n = src[i + 1];

  if (c === '/' && n === '/') {
    const nl = src.indexOf('\n', i);
    return nl === -1 ? src.length : nl;
  }

  if (c === '"' || c === "'") {
    const quote = c;
    i++;
    while (i < src.length) {
      if (src[i] === '\\') {
        i += 2;
        continue;
      }
      if (src[i] === quote) return i + 1;
      i++;
    }
    return src.length;
  }

  if (c === '`') {
    // Template literal: scan to the matching backtick, honouring ${ } nesting
    // (which may contain further strings, comments or braces).
    i++;
    while (i < src.length) {
      if (src[i] === '\\') {
        i += 2;
        continue;
      }
      if (src[i] === '`') return i + 1;
      if (src[i] === '$' && src[i + 1] === '{') {
        const close = matchBrace(src, i + 1);
        if (close === -1) return src.length;
        i = close + 1;
        continue;
      }
      i++;
    }
    return src.length;
  }

  if (c === '/' && isRegexStart(src, i)) {
    i++;
    let inClass = false;
    while (i < src.length) {
      if (src[i] === '\\') {
        i += 2;
        continue;
      }
      if (src[i] === '[') inClass = true;
      else if (src[i] === ']') inClass = false;
      else if (src[i] === '/' && !inClass) {
        i++;
        while (i < src.length && /[a-z]/.test(src[i])) i++; // flags
        return i;
      }
      i++;
    }
    return src.length;
  }

  return i + 1;
}

/** Heuristic: a `/` begins a regex literal when the previous token cannot end an expression. */
function isRegexStart(src: string, i: number): boolean {
  let j = i - 1;
  while (j >= 0 && /\s/.test(src[j])) j--;
  if (j < 0) return true;
  const prev = src[j];
  // `prev` is a SINGLE character, so this is a membership test — no anchor.
  if (prev === ')' || prev === ']') return false;
  if (/[A-Za-z0-9_$]/.test(prev)) {
    // `return /re/`, `typeof /re/`, `case /re/` are still regex starts.
    const word = /([A-Za-z0-9_$]+)$/.exec(src.slice(0, j + 1));
    const w = word ? word[1] : '';
    return ['return', 'typeof', 'case', 'in', 'of', 'new', 'delete', 'void', 'instanceof', 'yield', 'await', 'do', 'else'].includes(
      w,
    );
  }
  return true;
}

/**
 * Given the index of an opening brace, return the index of its matching close
 * brace, or -1 when the construct cannot be classified reliably.
 */
export function matchBrace(src: string, open: number): number {
  let depth = 0;
  let i = open;
  let inBlockComment = false;
  let guard = 0;
  while (i < src.length && guard++ < src.length * 4) {
    if (inBlockComment) {
      if (src[i] === '*' && src[i + 1] === '/') {
        inBlockComment = false;
        i += 2;
        continue;
      }
      i++;
      continue;
    }
    // R750c: the `/*` check MUST come before skipConstruct is called.
    //
    // The previous order was:
    //     const next = skipConstruct(src, i, false);
    //     if (src[i] === '/' && src[i+1] === '*') { inBlockComment = true; i += 2; continue; }
    // which computes `next` first and then throws it away. Any comment
    // handling inside skipConstruct was therefore DEAD CODE — the value it
    // returned for a `/*` never reached `i`. Two mutually-contradicting
    // implementations of the same rule, one of them silently inert.
    //
    // Now there is exactly one: matchBrace owns comment state, and
    // skipConstruct is only asked about strings / templates / regex.
    // Comment state is owned HERE and only here. skipConstruct handles
    // strings / templates / regex; it is never asked about comments.
    //
    // R750c note: an earlier revision ALSO tried to handle comments inside
    // skipConstruct, via a `*/` jump. That branch was dead — the caller
    // computed `next` and then discarded it. Two implementations of one
    // rule, disagreeing, one inert. Removing the duplicate is the fix; the
    // surviving ordering is the one that actually executes.
    if (src[i] === '/' && src[i + 1] === '*') {
      inBlockComment = true;
      i += 2;
      continue;
    }
    const next = skipConstruct(src, i);
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return i;
    }
    // A skipConstruct that advanced past a brace inside a string / template
    // must not have those braces counted — hence `next` rather than i + 1.
    i = next;
  }
  return -1;
}

/**
 * Slice from `anchor` to the end of the block it introduces.
 *
 * Handles both function-ish shapes present in this repo:
 *   const jumpToBottom = () => { ... }
 *   function renderInline(...) { ... }
 *
 * Returns null when the anchor is absent or no brace follows it, so the
 * caller fails loudly instead of asserting against an empty slice (an empty
 * slice makes `toMatch` fail, but a WRONG slice makes it pass).
 */
export function sliceBlock(source: string, anchor: string): string | null {
  const at = source.indexOf(anchor);
  if (at === -1) return null;
  const open = source.indexOf('{', at);
  if (open === -1) return null;
  const close = matchBrace(source, open);
  if (close === -1) return null;
  return source.slice(at, close + 1);
}

/**
 * Slice from `anchor` up to the NEXT top-level `return false;` / `return true;`
 * in the same method — the engine's Spliterator shape. Used by the Java-side
 * analogue of this problem.
 */
export function sliceToReturn(source: string, anchor: string, terminator: string): string | null {
  const at = source.indexOf(anchor);
  if (at === -1) return null;
  const end = source.indexOf(terminator, at);
  return end === -1 ? null : source.slice(at, end + terminator.length);
}