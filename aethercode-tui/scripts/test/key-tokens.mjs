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

// R381 — the ONE extractor for "which keys does this component bind?".
//
// ## Why this file exists
//
// R379's gate had its own copy of this logic, inline in the test file.
// R381 needed the same answer for a wider set of components — and
// needed it to be RIGHT for two shapes R379's copy got wrong:
//
//   1. CONSTANTS. `SessionControl` binds its three action keys through
//      a constant:
//
//          const HOTKEY: Record<ControlAction, string> = {
//            continue: "c", pause: "p", stop: "s",
//          };
//          useInput((input, key) => {
//            if (input === HOTKEY.continue) { … }
//
//      A `input === "x"` scan sees NO literal there, so the extractor
//      reported that the component binds only Tab / ← / → / Enter /
//      Space — and the "the hint matches the handler" check would have
//      compared the wrong two sets. The three hotkeys were invisible.
//
//   2. NORMALISATION. `input === " "` is the Space key, and
//      `input === "Y"` is the `y` key with a shift. Raw string
//      comparison makes " " and "y" look like different keys from
//      "space" and "Y", so a table could never match its handler.
//
// ## The vocabulary
//
// The token names come from the PRODUCT (`src/componentKeys.ts`), not
// from here: `KEY_PROP_TOKEN` maps Ink's `key.*` property names onto
// the short tokens the tables are written in. This module is handed
// that map, so there is one vocabulary.
//
// Lesson 1215 — a checker that answers a slightly different question
// than the one you think is worse than no checker: it agrees with
// itself.

import { blankNonCode, matchBrace, scan } from "./ts-scan.mjs";

/**
 * Decode the escape sequences of a string literal's CONTENTS.
 *
 * <p>`input === "\t"` is the Tab key — Ink hands the handler the tab
 * CHARACTER, and `key.tab` is true for the same keypress. Taking the
 * literal at face value produced a token `\\t` next to the token
 * `tab`, so one key looked like two.
 *
 * @param {string} s
 * @returns {string}
 */
export function decodeEscapes(s) {
  return s.replace(/\\(.)/g, (_, c) => {
    switch (c) {
      case "t":
        return "\t";
      case "n":
        return "\n";
      case "r":
        return "\r";
      case "0":
        return "\0";
      case "\\":
        return "\\";
      case "'":
        return "'";
      case '"':
        return '"';
      default:
        return c;
    }
  });
}

/**
 * Normalise a raw key literal into the registry's token vocabulary.
 *
 * <p>Space becomes "space"; a tab becomes "tab"; a single letter
 * becomes lower-case (a shift is a modifier, not a different key);
 * everything else is taken as it is.
 *
 * @param {string} raw
 * @returns {string}
 */
export function normalizeToken(raw) {
  if (raw === " ") return "space";
  if (raw === "\t") return "tab";
  if (raw === "\n") return "newline";
  if (raw.length === 1 && /[A-Za-z]/.test(raw)) return raw.toLowerCase();
  return raw;
}

/**
 * The module-level constants a file declares.
 *
 * <p>Two shapes matter:
 *   `const KEY = "literal"`        -> a string constant
 *   `const HOTKEY = { k: "v" }`    -> an object of string constants
 *
 * @param {string} src
 * @returns {Map<string, string | Record<string, string>>}
 */
export function moduleConstants(src) {
  const out = new Map();
  const code = blankNonCode(src);

  // `const NAME: TYPE = { … }` — the object form. The body is scanned
  // from the ORIGINAL source so the string values survive.
  const objRe = /const\s+([A-Za-z_$][\w$]*)\s*(?::[^=;]*?)?=\s*\{/g;
  for (const m of code.matchAll(objRe)) {
    const name = m[1];
    const braceAt = m.index + m[0].length - 1;
    const end = matchBrace(code, braceAt);
    if (end < 0) continue;
    const body = src.slice(braceAt + 1, end - 1);
    const members = {};
    for (const pm of body.matchAll(/(?:^|[,\s])([A-Za-z_$][\w$]*)\s*:\s*"([^"]*)"/g)) {
      members[pm[1]] = pm[2];
    }
    if (Object.keys(members).length > 0) out.set(name, members);
  }

  // `const NAME: TYPE = "literal"` — the string form.
  const strRe = /const\s+([A-Za-z_$][\w$]*)\s*(?::[^=;]*?)?=\s*"([^"]*)"/g;
  for (const m of code.matchAll(strRe)) {
    if (!out.has(m[1])) out.set(m[1], m[2]);
  }

  return out;
}

/**
 * Resolve a right-hand side to a string literal, through the constants.
 *
 * <p>Handles `"literal"`, `CONST`, `CONST.member` and `CONST["member"]`.
 *
 * @param {string} expr the raw right-hand side text
 * @param {Map<string, string | Record<string, string>>} constants
 * @returns {string | null}
 */
export function resolveLiteral(expr, constants) {
  const e = expr.trim();
  if (/^"[^"]*"$/.test(e)) return decodeEscapes(e.slice(1, -1));
  if (/^'[^']*'$/.test(e)) return decodeEscapes(e.slice(1, -1));
  const member = e.match(/^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$/);
  if (member) {
    const obj = constants.get(member[1]);
    if (obj && typeof obj === "object" && member[2] in obj) return obj[member[2]];
    return null;
  }
  const indexed = e.match(/^([A-Za-z_$][\w$]*)\["([^"]+)"\]$/);
  if (indexed) {
    const obj = constants.get(indexed[1]);
    if (obj && typeof obj === "object" && indexed[2] in obj) return obj[indexed[2]];
    return null;
  }
  if (/^[A-Za-z_$][\w$]*$/.test(e)) {
    const v = constants.get(e);
    if (typeof v === "string") return decodeEscapes(v);
    return null;
  }
  return null;
}

/** A key word, as it appears in USER-VISIBLE text. */
const KEY_WORD = /\bEsc\b|\bEnter\b|Ctrl-[A-Za-z]|↑\/↓|←\/→/g;

/**
 * Does this text name a key IN PROSE?
 *
 * <p>A bare "Ctrl-S" is not prose — it is an identifier (a lookup key
 * into the app-level registry). "press Ctrl-S to see live jobs" IS
 * prose. The difference is whether anything else is in the sentence,
 * so the key word is removed and the remainder inspected.
 */
function namesAKey(text) {
  if (!KEY_WORD.test(text)) {
    KEY_WORD.lastIndex = 0;
    return false;
  }
  KEY_WORD.lastIndex = 0;
  const rest = text.replace(KEY_WORD, "").trim();
  KEY_WORD.lastIndex = 0;
  return rest.length > 0;
}

/**
 * A hand-written key hint: hard-coded user-visible text that names a
 * key.
 *
 * <p>Two places, because the text can live in either:
 *
 *   - JSX TEXT — the raw text between two tags. Not a string literal,
 *     so a scanner that only looks at literals misses it entirely
 *     (`CommandPalette`'s "Esc: close Enter: select" is JSX text).
 *   - STRING LITERALS — including a template literal inside an
 *     expression (`{`  (${n} — ↑/↓ select, a attach, …)`}`), which a
 *     JSX-shaped detector calls "an interpolation" and misses.
 *
 * <p>Inside a JSX element, the text inside `{…}` expressions is
 * skipped — the expression's own literals are picked up by the second
 * pass instead.
 *
 * @param {string} src the whole file
 * @returns {string[]} the texts, in source order
 */
export function handWrittenHints(src) {
  const hits = [];
  const code = blankNonCode(src);

  // --- pass 1: the static JSX text of every Text element ---
  for (const m of code.matchAll(/<Text\b[^>]*>([\s\S]*?)<\/Text>/g)) {
    const inner = m[1];
    // Drop the expression containers: what is left is the static text.
    let staticText = "";
    let i = 0;
    while (i < inner.length) {
      if (inner[i] === "{") {
        const end = matchBrace(inner, i);
        if (end < 0) {
          staticText += inner.slice(i);
          break;
        }
        i = end;
        continue;
      }
      staticText += inner[i];
      i++;
    }
    if (!namesAKey(staticText)) continue;
    hits.push(staticText.trim().slice(0, 80));
  }

  // --- pass 2: the string literals (and template literals) ---
  for (const s of scan(src).strings) {
    const text = s.value;
    if (text.length < 4) continue;
    if (!namesAKey(text)) continue;
    hits.push(text.trim().slice(0, 80));
  }

  return hits;
}

/**
 * The key primitives a component's `useInput` handler branches on.
 *
 * <p>Scoped to the HANDLER (brace-matched, then sliced out of the
 * ORIGINAL source so the string literals survive). A file-wide scan
 * would pick up keys the component does not bind.
 *
 * <p>★ R388n: THREE MORE SHAPES ★
 *
 * <ol>
 *   <li><b>A RANGE.</b> `/^[1-9]$/.test(input)` binds the digits 1-9.
 *       A `input === "1"` scan sees nothing, so the extractor reported
 *       that the component binds no digits — and a key table that
 *       listed "1-9: pick row" would not match. The digits are now
 *       recognised as a range.</li>
 *   <li><b>A MODIFIER CHORD.</b> `key.ctrl` plus `input.toLowerCase()`
 *       compared against a letter binds `ctrl+<letter>`. The modifier
 *       itself is not a key — `ctrl` alone is not something the user
 *       presses — so it is not emitted as a token; the CHORD is.</li>
 *   <li><b>A SPACE.</b> `input === " "` is Space; `normalizeToken` maps
 *       it to "space". (Already handled, kept here for the record.)</li>
 * </ol>
 *
 * <p>→ **Lesson 1215 — a checker that answers a slightly different
 * question than the one you think is worse than no checker: it agrees
 * with itself.** Each new shape is a shape the REAL handler uses, so
 * the extractor has to know it — otherwise the gate compares two
 * different questions and reports a mismatch that is not there.
 *
 * @param {string} src the whole file
 * @param {Record<string, string>} keyPropToken the product's vocabulary
 * @returns {string[] | null} the sorted tokens, or null when the file
 *   has no `useInput` handler
 */
export function handlerTokens(src, keyPropToken) {
  const code = blankNonCode(src);
  const at = code.indexOf("useInput(");
  if (at < 0) return null;
  const braceAt = code.indexOf("{", at);
  if (braceAt < 0) return null;
  const end = matchBrace(code, braceAt);
  if (end < 0) return null;
  const body = src.slice(braceAt, end);

  const constants = moduleConstants(src);
  const tokens = new Set();

  // ─────────────────────────────────────────────────────────────────
  // PASS 1: the CHORDS.
  //
  // Two shapes bind a chord:
  //
  //   (a) `key.ctrl && input === "s"` — the canonical form. The
  //       modifier and the letter are in ONE expression.
  //   (b) `if (key.ctrl) { const chord = input.toLowerCase();
  //       if (chord === "r") … }` — the modifier opens a branch and
  //       the letter is compared inside it.
  //
  // The bare `ctrl` is not a key the user presses — it is a modifier
  // — so emitting "ctrl" as a token would make the table promise a
  // key that does not exist. The CHORD is the key.
  //
  // ★ The chord scan runs FIRST, because it decides whether a
  // modifier is a key or part of a chord. ★
  // ─────────────────────────────────────────────────────────────────

  // ── shape (a) ──
  const ctrlChordSpans = [];
  for (const m of body.matchAll(/key\.ctrl\s*&&\s*input\s*===\s*"([^"]+)"/g)) {
    ctrlChordSpans.push([m.index, m.index + m[0].length, m[1].toLowerCase()]);
    const letter = m[1].toLowerCase();
    if (letter.length === 1 && /[a-z]/.test(letter)) tokens.add(`ctrl+${letter}`);
  }

  // ── shape (b) ──
  //
  // ★ EVERY `key.ctrl` occurrence is tried, not just the first. ★
  // A handler may have a modifier GUARD before the chord branch
  // (`if (key.ctrl || key.meta) return;` — "a modifier is not text"),
  // and the first occurrence is not the branch that binds a chord.
  //
  // ★ The branch is matched by BRACE, not by a line break. ★ A
  // line-based cut truncates the branch at the first `}` — which is
  // the end of the FIRST `if` inside it — so only the first chord
  // would be seen (Lesson 1322: searching in a big block can find a
  // neighbouring construct's label).
  const ctrlBranchSpans = [];
  for (const ctrlMatch of body.matchAll(/key\.ctrl/g)) {
    const braceAt = body.indexOf("{", ctrlMatch.index);
    let branch = body.slice(ctrlMatch.index);
    if (braceAt >= 0) {
      const branchEnd = matchBrace(body, braceAt);
      if (branchEnd > 0) {
        branch = body.slice(ctrlMatch.index, branchEnd);
        ctrlBranchSpans.push([ctrlMatch.index, branchEnd]);
      }
    }
    const derived = /(\w+)\s*=\s*input\.toLowerCase\(\)/.exec(branch);
    if (!derived) continue;
    const varName = derived[1];
    const re = new RegExp(`${varName}\\s*===\\s*"([^"]+)"`, "g");
    for (const m of branch.matchAll(re)) {
      const letter = m[1].toLowerCase();
      if (letter.length === 1 && /[a-z]/.test(letter)) {
        tokens.add(`ctrl+${letter}`);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // PASS 2: the MODIFIERS.
  //
  // ★ The MODIFIER properties are keys only when they are checked
  // ALONE, and not part of a chord. ★
  //
  // `if (key.shift) { … }` — Shift+Enter is a chord the user presses,
  // and the InputBox's hint names it. The modifier IS the key there.
  //
  // `key.ctrl && input === "s"` — Ctrl+S. The modifier is not a key on
  // its own; the CHORD is. Emitting "ctrl" as a token would make the
  // key table promise a key the user cannot press.
  //
  // `if (key.ctrl || key.meta || key.shift) return;` — a MODIFIER
  // GUARD: "a modifier chord is not text". No key is bound here at
  // all, so the modifier is not a key either.
  //
  // `if (key.ctrl) { …chords… }` — the modifier OPENS A CHORD BRANCH.
  // If that branch really derived a chord (pass 1 found one in it),
  // the modifier is part of the chord, not a key.
  // ─────────────────────────────────────────────────────────────────
  const MODIFIER_PROPS = new Set(["ctrl", "meta", "shift", "alt"]);
  const modifierSpans = [];
  for (const m of body.matchAll(/key\.([A-Za-z]+)\s*&&/g)) {
    if (!MODIFIER_PROPS.has(m[1])) continue;
    modifierSpans.push([m.index, m.index + m[0].length]);
  }
  // A modifier GUARD: `key.a || key.b || …` — every modifier in the
  // chain is part of the guard.
  for (const m of body.matchAll(/key\.([A-Za-z]+)(?:\s*\|\|\s*key\.[A-Za-z]+)+/g)) {
    modifierSpans.push([m.index, m.index + m[0].length]);
  }
  // A modifier that OPENS A CHORD BRANCH that really derived a chord.
  for (const [a, b] of ctrlBranchSpans) {
    const branch = body.slice(a, b);
    if (/input\.toLowerCase\(\)/.test(branch)) modifierSpans.push([a, b]);
  }
  const inChordModifier = (i) => modifierSpans.some(([a, b]) => i >= a && i < b);

  for (const m of body.matchAll(/key\.([A-Za-z]+)/g)) {
    const prop = m[1];
    if (MODIFIER_PROPS.has(prop) && inChordModifier(m.index)) continue;
    tokens.add(keyPropToken[prop] ?? prop.toLowerCase());
  }

  // `input === X` — on EITHER side, so the order does not matter.
  //
  // ★ A comparison that is part of a CTRL CHORD is not a bare key. ★
  // `key.ctrl && input === "s"` binds Ctrl+S — the plain "s" does NOT
  // trigger anything, so emitting "s" as a separate token would make
  // the key table have to list a key the handler does not bind.
  const inCtrlChord = (i) => ctrlChordSpans.some(([a, b]) => i >= a && i < b);

  const cmp =
    /(input)\s*===\s*("[^"]*"|'[^']*'|[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*|\["[^"]+"\])?)|("[^"]*"|'[^']*')\s*===\s*(input)/g;
  for (const m of body.matchAll(cmp)) {
    if (inCtrlChord(m.index)) continue;
    const rhs = m[2] ?? m[4];
    const literal = resolveLiteral(rhs, constants);
    if (literal === null) continue;
    tokens.add(normalizeToken(literal));
  }

  // ★ R388n: a RANGE of keys, tested with a regular expression. ★
  //
  // `/^[1-9]$/.test(input)` binds nine keys. The `input === X` scan
  // above sees a REGEX, not a literal, so the range was invisible and
  // the key table could not match its handler.
  //
  // The range's ENDPOINTS are expanded — "1-9" is nine keys, not two.
  for (const m of body.matchAll(/\/\^?\[([^\]]+)\]\$\/\.test\(/g)) {
    const spec = m[1];
    const range = /^([0-9A-Za-z])-([0-9A-Za-z])$/.exec(spec);
    if (range) {
      const from = range[1].charCodeAt(0);
      const to = range[2].charCodeAt(0);
      if (to >= from && to - from < 64) {
        for (let c = from; c <= to; c++) {
          tokens.add(normalizeToken(String.fromCharCode(c)));
        }
        continue;
      }
    }
    for (const part of spec.split("-")) {
      const ch = part.trim();
      if (ch.length === 1) tokens.add(normalizeToken(ch));
    }
  }

  return [...tokens].sort();
}
