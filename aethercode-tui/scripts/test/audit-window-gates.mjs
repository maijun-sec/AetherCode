// Audit for fragile fixed-width source-pin windows.
//
// THE FAILURE MODE (Lesson 1107)
//
// A source-pin gate locates an anchor, slices N characters forward, and
// asserts an expected token is inside that slice. The length N is a magic
// number chosen when the gate was written. It is correct ONCE and then
// silently rots: someone adds an explanatory comment above the very line the
// gate is about, the token slides past the window, and the assertion starts
// failing for the wrong reason — or worse, the gate gets "fixed" by widening
// the number and the invariant it was protecting drifts out of scope.
//
// What makes this dangerous is that the gate still LOOKS like it is auditing.
// It finds an anchor, it slices, it runs. The truncation is invisible.
//
// THE FIX IS STRUCTURAL, NOT NUMERIC
//
// A window bounded by the enclosing brace of the function under test never
// rots, because a function's body length is a property of the CODE, not of
// how much prose someone wrote above it. This audit finds the gates that still
// use a number and measures how much headroom each actually has, so marginal
// ones are visible before they silently break.
//
// HOW THE MEASUREMENT WORKS
//
// Distance is computed on the COMMENT-STRIPPED source, not the raw text,
// because the question is "how far is the token from its code anchor,
// ignoring the prose in between". A gate whose stripped distance already
// approaches its window is fragile regardless of surrounding prose; one with
// stripped headroom tolerates a great deal of future commentary.
//
// THE ASSOCIATION IS THE HARD PART
//
// These files commonly contain BOTH `assert.match(guard, /x/)` (windowed)
// and `assert.match(tuiCode, /y/)` (global). Only the first kind constrains
// the window. Crediting a global assertion against a windowed slice reports
// a bogus "window exhausted" for a healthy gate — which is worse than no
// audit at all, because it sends someone to repair a healthy test.
//
// That is not hypothetical: an earlier revision of this script did exactly
// that and reported 586% used on a gate whose assertions were fine. The
// lesson is the same as elsewhere in this repo — an audit tool that has never
// been checked against a known-good case will confidently report nonsense.
//
// Output is worst-headroom first, with a non-zero exit when any gate has no
// headroom left, so this can run in CI.

import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { stripComments } from "./strip-comments.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_ITER = 2000; // hard stop so a bad pattern cannot hang the audit

/** `const <sliceVar> = <src>.slice(<idxVar>, <idxVar> + N)` -> sliceVar info.
 *  Also accepts the TS/test variants:
 *    const v = src.slice(i, i + N)
 *    const v = src.substring(i, Math.min(i + N, src.length))
 */
function findSlices(src) {
  const slices = new Map();
  const shapes = [
    /const\s+(\w+)\s*=\s*(\w+)\.slice\(\s*(\w+)\s*,\s*\3\s*\+\s*(\d{3,})\s*\)/g,
    /const\s+(\w+)\s*=\s*(\w+)\.substring\(\s*(\w+)\s*,\s*Math\.min\(\s*\3\s*\+\s*(\d{3,})/g,
    /const\s+(\w+)\s*=\s*(\w+)\.substring\(\s*(\w+)\s*,\s*\3\s*\+\s*(\d{3,})\s*\)/g,
  ];
  for (const re of shapes) {
    let m;
    while ((m = re.exec(src)) !== null && slices.size < MAX_ITER) {
      const [, sliceVar, , idxVar, window] = m;
      // The anchor literal is whatever `indexOf` assigned to idxVar. The
      // repo mixes single and double quotes for these, so try both.
      const anchorRe = new RegExp(
        `const\\s+${idxVar}\\s*=\\s*(\\w+)\\.indexOf\\(\\s*(?:"([^"]*)"|'([^']*)')`,
      );
      const a = anchorRe.exec(src);
      if (!a) continue;
      slices.set(sliceVar, { anchor: a[2] ?? a[3], window: Number(window) });
    }
  }
  return slices;
}

/** Tokens asserted ON a given slice variable.
 *  Handles both the node:test style (`assert.match(v, /x/)`, `v.includes("x")`)
 *  and the vitest/jest style used by the desktop suite
 *  (`expect(v).toMatch(/x/)`, `expect(v).toContain("x")`).
 */
function findAssertionsOn(src, sliceVars) {
  const res = new Map();
  const add = (v, tok) => {
    if (!sliceVars.has(v)) return;
    if (!res.has(v)) res.set(v, []);
    res.get(v).push(tok);
  };
  const run = (re, handler) => {
    let m;
    let n = 0;
    while ((m = re.exec(src)) !== null && n++ < MAX_ITER) handler(m);
  };

  // assert.match(<v>, /re/)  |  expect(<v>).toMatch(/re/)
  run(/assert\.match\(\s*(\w+)\s*,\s*\/([^/]+)\/[a-z]*\s*\)/g, (m) => add(m[1], m[2]));
  run(/expect\(\s*(\w+)\s*\)\s*\.\s*toMatch\(\s*\/([^/]+)\/[a-z]*\s*\)/g, (m) => add(m[1], m[2]));

  // <v>.includes("x")  |  expect(<v>).toContain("x")  |  assert.ok(<v>.includes("x"))
  run(/(\w+)\.includes\(\s*"([^"]{2,})"/g, (m) => add(m[1], m[2]));
  run(/(\w+)\.includes\(\s*'([^']{2,})'/g, (m) => add(m[1], m[2]));
  run(/expect\(\s*(\w+)\s*\)\s*\.\s*toContain\(\s*"([^"]{2,})"/g, (m) => add(m[1], m[2]));
  run(/expect\(\s*(\w+)\s*\)\s*\.\s*toContain\(\s*'([^']{2,})'/g, (m) => add(m[1], m[2]));
  return res;
}

/**
 * An assertion token is either a plain literal (from `.includes("x")`) or a
 * regex source (from `assert.match(v, /x/)`). For the regex form we can only
 * measure distance when it begins with an identifier — the common case
 * (`/enqueuePrompt/`, `/void cancelActiveRun\(\)/`). Return null otherwise:
 * under-reporting is fine, reporting a fabricated distance is not.
 */
function literalOf(tok) {
  if (/^[A-Za-z_$][\w$]*$/.test(tok)) return tok;
  const m = /^(\^)?([A-Za-z_$][\w$]*)/.exec(tok);
  return m ? m[2] : null;
}

function measure(file, raw) {
  const stripped = stripComments(raw);
  const findings = [];
  const slices = findSlices(raw);
  const perSlice = findAssertionsOn(raw, new Set(slices.keys()));

  for (const [sliceVar, info] of slices) {
    const tokens = perSlice.get(sliceVar);
    if (!tokens || tokens.length === 0) continue;

    const at = stripped.indexOf(info.anchor);
    if (at < 0) continue;

    // The true requirement is the distance to the NEAREST asserted token,
    // measured on comment-stripped source: prose in between does not count,
    // which is exactly why a structural bound is safe.
    let nearest = Infinity;
    let nearestToken = null;
    for (const tok of tokens) {
      const needle = literalOf(tok);
      if (!needle) continue;
      const from = stripped.indexOf(needle, at);
      if (from < 0) continue;
      const d = from - at;
      if (d < nearest) {
        nearest = d;
        nearestToken = needle;
      }
    }
    if (!Number.isFinite(nearest)) continue;

    findings.push({
      file,
      anchor: info.anchor,
      window: info.window,
      nearest,
      nearestToken,
      headroom: info.window - nearest,
      usedPct: Math.round((nearest / info.window) * 100),
    });
  }
  return findings;
}

// Usage: node audit-window-gates.mjs <repo-root>
// Walks both the TUI and desktop test trees — the fragile shape does not
// care which product it is in, and auditing only one of them would leave
// the other silently rotting.
const ROOT = process.argv[2] ?? join(HERE, "..", "..", "..");

const TEST_GLOBS = [
  ["aethercode-tui/scripts/test", ".test.mjs"],
  ["aethercode-desktop/src", ".test.ts"],
  ["aethercode-desktop/src", ".test.tsx"],
];

function walk(dir, filter, out = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (e.name === "node_modules" || e.name.startsWith(".")) continue;
    const full = join(dir, e.name);
    if (e.isDirectory()) walk(full, filter, out);
    else if (e.name.endsWith(filter)) out.push(full);
  }
  return out;
}

const files = [];
for (const [sub, ext] of TEST_GLOBS) {
  for (const f of walk(join(ROOT, sub), ext)) files.push(f);
}

const all = [];
let readErrors = 0;
for (const f of files) {
  try {
    const src = readFileSync(f, "utf8");
    all.push(...measure(f, src));
  } catch {
    readErrors++;
  }
}

if (all.length === 0) {
  console.log(`no windowed source-pin gates recognised across ${files.length} test files`);
  process.exit(0);
}

all.sort((a, b) => b.usedPct - a.usedPct || a.headroom - b.headroom);
const rel = (f) => f.replace(ROOT + "\\", "").replace(ROOT + "/", "");

console.log("fixed-window source-pin gates, worst headroom first\n");
console.log("  used  headroom  window  anchor -> nearest asserted token");
console.log("  ----  --------  ------  ---------------------------------");
for (const r of all) {
  console.log(
    `  ${String(r.usedPct).padStart(3)}%  ${String(r.headroom).padStart(8)}  ${String(r.window).padStart(6)}  ` +
      `${rel(r.file)} :: "${String(r.anchor).slice(0, 26)}" -> "${String(r.nearestToken).slice(0, 22)}"`,
  );
}

const exhausted = all.filter((r) => r.headroom <= 0);
const tight = all.filter((r) => r.headroom > 0 && r.usedPct >= 50);

console.log(`\ntotal gates measured : ${all.length}`);
console.log(`headroom exhausted   : ${exhausted.length}`);
console.log(`over half the window : ${tight.length}`);

if (exhausted.length > 0) {
  console.log("\nEXHAUSTED — must become structural bounds. Widening the number just");
  console.log("re-breaks the next time someone writes a comment above the token:");
  for (const r of exhausted) {
    console.log(`  ${rel(r.file)} :: "${r.anchor}" -> "${r.nearestToken}" (needs ${r.nearest}, has ${r.window})`);
  }
  process.exit(1);
}
if (tight.length > 0) {
  console.log("\nTIGHT — convert before the next edit above these lines:");
  for (const r of tight) {
    console.log(`  ${rel(r.file)} :: "${r.anchor}" (${r.usedPct}% used)`);
  }
}