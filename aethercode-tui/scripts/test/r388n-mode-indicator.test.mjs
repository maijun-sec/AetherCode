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

// R388n — the mode indicator must be a PHRASE, and the phrase must not
// lie about whether the mode still asks.
//
// ## ★ The defect ★
//
// The persistent mode indicator was an ACRONYM: a coloured pill reading
// `ASK`, `TASK`, `EDITS`, `READ`, `PLAN`, `BYPASS`.
//
// An acronym cannot say the two things the user needs:
//
//   1. **Does this mode still interrupt me?** `ASK` says "asks", `TASK`
//      says "task" — a user cannot read `TASK` and know that nothing
//      will ask for the rest of the task.
//   2. **Which mode is this?** `DEFAULT` and `ASK_BEFORE_TOOL` — two
//      DISTINCT daemon modes — BOTH rendered as `ASK`. Two different
//      safety postures looked IDENTICAL.
//
// Claude Code shows the mode as literal text:
// `⏸ manual mode on` / `⏵⏵ accept edits on` / `⏸ plan mode on` /
// `⏵⏵ auto mode on` / `⏵⏵ don't ask on` / `⏵⏵ bypass permissions on`.
//
// ## ★ The glyph is not decoration ★
//
// The leading glyph encodes the ONE safety-relevant fact:
//
//   `⏸`  = this mode STILL ASKS
//   `⏵⏵` = this mode does NOT ask
//
// A user who reads only the first character still gets the half that
// matters. And because the glyph is derived from the catalog, it
// CANNOT drift from the behaviour — the gate pins the glyph against
// the predicate that says whether a mode asks.
//
// ## ★ One copy ★
//
// The phrase lives in `permissionModes.ts` — the ONE catalog R385
// created — because a second copy of the vocabulary is a second truth,
// and R385 exists because six copies had already disagreed.
//
// → **Lesson 1353 — an ACRONYM cannot carry a safety fact.** A mode
// indicator has to say (a) what the mode is and (b) whether it still
// interrupts you. The second is the safety-relevant half, and no
// four-letter badge can carry it.
//
// → **Lesson 1354 — two DISTINCT modes that render IDENTICALLY are one
// mode to the user.** `DEFAULT` and `ASK_BEFORE_TOOL` both said `ASK`.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const read = (rel) => readFileSync(join(root, rel), "utf8");
const readCode = (rel) => codeOf(read(rel));

/* ------------------------------------------------------------------ *
 * Load the REAL catalog.
 *
 * The gate must not re-implement the vocabulary — a second copy is a
 * second truth, and it is exactly what drifts (R380's subject).
 * ------------------------------------------------------------------ */
const loadTs = async (name) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), `r388n-${name}-`));
  const outfile = join(dir, `${name}.mjs`);
  try {
    await build({
      entryPoints: [join(root, "src", `${name}.ts`)],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts" },
    });
    return await import(pathToFileURL(outfile).href);
  } finally {
    try {
      rmSync(outfile, { force: true });
    } catch {
      /* best effort */
    }
  }
};

const modes = await loadTs("permissionModes");

/* ================================================================== *
 * The catalog carries the phrase
 * ================================================================== */

test("R388n: every mode has an indicator PHRASE, not an acronym", () => {
  for (const m of modes.PERMISSION_MODES) {
    assert.ok(
      typeof m.indicator === "string" && m.indicator.length > 0,
      `${m.value} has no indicator`,
    );
    // a phrase has WORDS. An acronym has none.
    assert.match(
      m.indicator,
      /\s/,
      `${m.value}'s indicator "${m.indicator}" is not a phrase — it has no space`,
    );
    // and it must say the mode is ON — an indicator that does not read
    // as a state is not an indicator.
    assert.match(
      m.indicator,
      /\bon\b/,
      `${m.value}'s indicator "${m.indicator}" does not say the mode is on`,
    );
  }
});

test("R388n: the indicator is NOT the badge (the acronym must not be the indicator)", () => {
  for (const m of modes.PERMISSION_MODES) {
    assert.notEqual(
      m.indicator,
      m.badge,
      `${m.value}'s indicator is its badge — that is the defect this round removes`,
    );
  }
});

test("R388n: two modes that share a badge must NOT share an indicator", () => {
  // ★ THE DEFECT ★ `DEFAULT` and `ASK_BEFORE_TOOL` both rendered as
  // "ASK" — two distinct daemon modes that looked IDENTICAL.
  const byBadge = new Map();
  for (const m of modes.PERMISSION_MODES) {
    const list = byBadge.get(m.badge) ?? [];
    list.push(m);
    byBadge.set(m.badge, list);
  }
  for (const [badge, list] of byBadge) {
    if (list.length < 2) continue;
    const indicators = new Set(list.map((m) => m.indicator));
    assert.equal(
      indicators.size,
      list.length,
      `modes sharing the badge "${badge}" (${list.map((m) => m.value).join(", ")}) ` +
        `must have DISTINCT indicators — otherwise they look identical to the user`,
    );
  }
});

/* ================================================================== *
 * The glyph and the predicate must agree
 * ================================================================== */

test("R388n: the glyph encodes whether the mode STILL ASKS", () => {
  // `⏸` = still asks. `⏵⏵` = does not ask.
  for (const m of modes.PERMISSION_MODES) {
    const asks = modes.modeStillAsks(m.value);
    const glyph = m.indicator.startsWith("⏸")
      ? "asks"
      : m.indicator.startsWith("⏵⏵")
        ? "silent"
        : "??";
    assert.notEqual(
      glyph,
      "??",
      `${m.value}'s indicator "${m.indicator}" must start with ⏸ (still asks) or ⏵⏵ (does not ask)`,
    );
    assert.equal(
      asks,
      glyph === "asks",
      `${m.value}'s glyph says "${glyph}" but modeStillAsks says ${asks} — ` +
        `the glyph must not lie about whether the mode interrupts the user`,
    );
  }
});

test("R388n: an UNKNOWN mode is treated as ASKING", () => {
  // A mode this build cannot describe is a mode whose behaviour is
  // unknown, and the safe reading of "unknown" is "it might interrupt
  // you". Claiming it is silent would be the dangerous direction.
  assert.equal(modes.modeStillAsks("SOME_MODE_FROM_THE_FUTURE"), true);
  assert.equal(modes.modeStillAsks(""), true);
});

test("R388n: the modes that DO NOT ask are exactly the ones that say so", () => {
  const silent = modes.PERMISSION_MODES.filter((m) => !modes.modeStillAsks(m.value)).map(
    (m) => m.value,
  );
  // These are the daemon's "no prompt" modes: ACCEPT_TASK, ACCEPT_EDITS,
  // AUTO_READ_ONLY and BYPASS_PERMISSIONS.
  assert.deepEqual(
    [...silent].sort(),
    ["ACCEPT_EDITS", "ACCEPT_TASK", "AUTO_READ_ONLY", "BYPASS_PERMISSIONS"].sort(),
    `the modes that claim not to ask are ${silent.join(", ")} — the gate must track the set`,
  );
});

test("R388n: an UNKNOWN mode's indicator does not invent a phrase", () => {
  const ind = modes.permissionModeIndicator("SOME_MODE_FROM_THE_FUTURE");
  // it may name the mode, but it must not claim a behaviour
  assert.doesNotMatch(
    ind,
    /⏵⏵/,
    "an unknown mode must not render the 'does not ask' glyph — that would be a false safety claim",
  );
  assert.match(ind, /SOME_MODE_FROM_THE_FUTURE/);
});

/* ================================================================== *
 * BOTH renderers show the phrase
 * ================================================================== */

test("R388n: the Ink header renders the indicator", () => {
  const header = readCode("src/components/Header.tsx");
  assert.match(
    header,
    /permissionModeIndicator/,
    "the Ink header must render the indicator from the ONE catalog",
  );
  assert.match(header, /modeIndicator/, "the Ink header must put the phrase on screen");
});

test("R388n: the line renderer renders the SAME indicator", () => {
  const line = readCode("src/line.ts");
  assert.match(
    line,
    /permissionModeIndicator/,
    "the line renderer must render the indicator from the ONE catalog",
  );
  assert.match(line, /modeIndicator\(/, "the line renderer must put the phrase on screen");
});

test("R388n: the phrase is written ONCE (no second copy of the vocabulary)", () => {
  // ★ ONE copy ★ — the R385 rule, applied to the new field. A second
  // copy of a phrase is a second truth, and it drifts.
  const surfaces = [
    "src/components/Header.tsx",
    "src/line.ts",
    "src/components/ModePicker.tsx",
    "src/tui.tsx",
  ];
  for (const rel of surfaces) {
    const code = readCode(rel);
    // no surface may hard-code a literal phrase of the shape
    // "… mode on" — those must come from the catalog.
    const hardCoded = [...code.matchAll(/["'`][^"'`]*\bmode on\b[^"'`]*["'`]/g)];
    assert.equal(
      hardCoded.length,
      0,
      `${rel} hard-codes a mode phrase: ${hardCoded.map((m) => m[0]).join(", ")} — ` +
        `the phrase lives in permissionModes.ts`,
    );
  }
});

test("R388n: the mode picker shows the indicator too (one phrase, three surfaces)", () => {
  const picker = readCode("src/components/ModePicker.tsx");
  assert.match(
    picker,
    /permissionModeIndicator|indicator/,
    "the mode picker must show the same phrase — three surfaces, one phrase",
  );
});
