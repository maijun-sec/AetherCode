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

// R388k — `/help` must be REACHABLE, not truncated.
//
// ## The bug this gate exists to hold
//
// `/help` returned `SLASH_HELP` — one ~110-line string — which the Ink
// renderer emitted as a single `sideNote`. The scrollback truncated it
// to the available height: on a real TTY, **3 lines were visible** and
// commands 4–89 were unreachable. There was no scroll, no pagination
// and no filter.
//
// → The help surface could not answer the question it exists to answer.
//
// ## The benchmark
//
//   * Claude Code — `/` merges built-ins, skills, plugin and MCP
//     commands into ONE list; `/commands` gives the full list.
//   * Cursor — a `/` menu plus `/help [command]`.
//   * OpenCode — commands carry a `description` "shown in command lists
//     and discovery".
//   * Aider — `/help` renders the command table GENERATED FROM SOURCE.
//
// ## What this gate pins
//
//   * `/help` opens a PANEL, not a wall of text;
//   * the panel is REACHABLE — the id is registered in the meta table
//     and the state type, and the renderer mounts it;
//   * the panel's rows are PROJECTED from the one catalog, so the help
//     surface cannot describe a command that does not exist;
//   * the panel can FILTER, so 89 rows are reachable;
//   * the panel's key table matches its handler in BOTH directions
//     (R381's rule);
//   * the line renderer still prints the reference (a terminal has no
//     panels — but it has a scrollback).

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
 * Load the real modules.
 * ------------------------------------------------------------------ */
const loadTs = async (entry, outName) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "r388k-help-"));
  const outfile = join(dir, outName);
  try {
    await build({
      entryPoints: [join(root, entry)],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts", ".tsx": "tsx" },
      // The panel renders Ink components; the gate only needs its two
      // pure functions, so the rendering deps stay external.
      external: ["ink", "react", "ink-text-input"],
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

const loadCommands = () => loadTs("src/commands.ts", "commands.mjs");
// The panel's DATA lives in `src/commandHelp.ts` — pure functions, no
// React. Bundling the `.tsx` would pull in `react`/`ink`, and a bundle
// written to a temp directory cannot resolve them.
const loadHelp = () => loadTs("src/commandHelp.ts", "help.mjs");

/* ------------------------------------------------------------------ *
 * 1. `/help` opens a panel, not a wall of text.
 * ------------------------------------------------------------------ */
test("R388k: /help routes to a sentinel, not to the help TEXT", async () => {
  const cmd = await loadCommands();
  const route = cmd.routeSlash("/help");
  assert.ok(route, "/help does not route at all (R388k)");
  // The sentinel, not the ~110-line string. A route that carries the
  // text is the pre-R388k shape: the renderer emits it as one
  // sideNote and the scrollback truncates it.
  assert.equal(
    route.local,
    "__HELP_PANEL__",
    "/help returns the help TEXT instead of a panel sentinel — the scrollback truncates it (R388k)",
  );
  // And the text still exists for the line renderer.
  assert.ok(
    typeof cmd.SLASH_HELP === "string" && cmd.SLASH_HELP.length > 0,
    "SLASH_HELP is gone — the line renderer has nothing to print (R388k)",
  );
});

/* ------------------------------------------------------------------ *
 * 2. The panel is REACHABLE.
 *
 * R381's lesson: "reachable" is a MEASUREMENT, not a claim. A panel
 * that is written but never mounted is 300 lines of dead code — which
 * is exactly what R362 found behind twelve sentinels.
 * ------------------------------------------------------------------ */
test("R388k: the help panel is mounted by the renderer", () => {
  const code = readCode("src/tui.tsx");
  assert.match(
    code,
    /case\s+"help":/,
    "the renderer has no case for the help panel — the panel is never mounted (R388k)",
  );
  assert.match(code, /<CommandHelpPanel\b/, "the renderer does not mount CommandHelpPanel (R388k)");
  assert.match(
    code,
    /from\s+"\.\/components\/CommandHelpPanel\.js"/,
    "the renderer does not import CommandHelpPanel (R388k)",
  );
});

test("R388k: the help panel id is registered in the state and the meta table", () => {
  const state = readCode("src/state.ts");
  assert.match(
    state,
    /export type PanelId\s*=[\s\S]{0,900}?\|\s*"help"/,
    'the PanelId type does not include "help" — the panel cannot be opened (R388k)',
  );
  const tui = readCode("src/tui.tsx");
  assert.match(
    tui,
    /PANEL_META[\s\S]{0,4000}?help:\s*\{/,
    "PANEL_META has no entry for the help panel — the shell would render without a title (R388k)",
  );
  assert.match(
    tui,
    /PANEL_BY_INTENT[\s\S]{0,3000}?"command-help":\s*"help"/,
    "the help panel id is not in PANEL_BY_INTENT — the generic panel claim cannot open it (R388k)",
  );
  assert.match(
    tui,
    /case\s+"help":/,
    "the renderer has no case for the help panel — the panel is never mounted (R388k)",
  );
});

/* ------------------------------------------------------------------ *
 * 3. The rows are PROJECTED from the one catalog.
 *
 * A hand-written list of 89 command names in the panel would be a
 * SECOND copy — and R380's whole subject is that a second copy drifts
 * silently.
 * ------------------------------------------------------------------ */
test("R388k: the panel's rows are projected from the catalog", async () => {
  const cmd = await loadCommands();
  const panel = await loadHelp();

  const rows = panel.buildHelpRows(cmd.SLASH_COMMANDS_DETAILED);
  assert.equal(
    rows.length,
    cmd.SLASH_COMMANDS_DETAILED.length,
    "the panel shows a different number of commands than the catalog holds (R388k)",
  );

  // Every catalog command is reachable by name.
  for (const c of cmd.SLASH_COMMANDS_DETAILED) {
    assert.ok(
      rows.some((r) => r.name === c.name),
      `/${c.name} is in the catalog but not in the help panel (R388k)`,
    );
  }

  // The panel does NOT hard-code the list.
  const code = readCode("src/commandHelp.ts");
  assert.match(
    code,
    /from\s+"\.\/commands\.js"/,
    "the help data does not import the catalog — the row list would be a second copy (R388k)",
  );
  assert.doesNotMatch(
    code,
    /name:\s*"sdd-approve"/,
    "the help data hard-codes a command name — the list must be projected (R388k)",
  );
});

/* ------------------------------------------------------------------ *
 * 4. The panel can FILTER, so 89 rows are reachable.
 *
 * Before R388k the reference was one truncated blob. A list that
 * cannot be filtered is a list that can only be scrolled — and a
 * scroll window the user has to hold an arrow key against.
 * ------------------------------------------------------------------ */
test("R388k: the panel filters by name AND by description", async () => {
  const cmd = await loadCommands();
  const panel = await loadHelp();
  const rows = panel.buildHelpRows(cmd.SLASH_COMMANDS_DETAILED);

  // An empty filter matches everything.
  assert.equal(panel.filterHelpRows(rows, "").length, rows.length);

  // By name.
  const byName = panel.filterHelpRows(rows, "sdd-approve");
  assert.ok(byName.length >= 1, "filtering by name found nothing (R388k)");
  assert.ok(byName.every((r) => r.name.includes("sdd-approve")));

  // By description — the word a user remembers, not the command name.
  const byDesc = panel.filterHelpRows(rows, "theme");
  assert.ok(byDesc.length >= 1, "filtering by description found nothing (R388k)");

  // A filter that matches nothing yields nothing (and the panel says
  // so) rather than everything.
  assert.equal(panel.filterHelpRows(rows, "zzzzznotacommand").length, 0);
});

test("R388k: the scroll window follows the highlight and never lies", async () => {
  const panel = await loadHelp();

  // Empty list.
  assert.deepEqual(panel.helpWindow(0, 0, 10), { start: 0, end: 0 });

  // A short list shows all of it.
  assert.deepEqual(panel.helpWindow(3, 0, 10), { start: 0, end: 3 });

  // The selected row is ALWAYS visible — this is the property that
  // makes a scroll window usable.
  for (const total of [5, 20, 89]) {
    for (const h of [0, 1, 7, total - 1, total]) {
      const { start, end } = panel.helpWindow(total, h, 10);
      assert.ok(start >= 0, `start went negative for total=${total} h=${h}`);
      assert.ok(end <= total, `end ran past the list for total=${total} h=${h}`);
      const clamped = Math.max(0, Math.min(h, total - 1));
      assert.ok(
        clamped >= start && clamped < end,
        `the highlight (${clamped}) is outside the window ${start}..${end} for total=${total} h=${h}`,
      );
    }
  }
});

/* ------------------------------------------------------------------ *
 * 5. The key table matches the handler (R381's rule, both directions).
 * ------------------------------------------------------------------ */
test("R388k: the help panel's key table matches its handler", () => {
  const code = readCode("src/components/CommandHelpPanel.tsx");
  const keys = readCode("src/componentKeys.ts");

  // The panel is in the registry.
  assert.match(
    keys,
    /component:\s*"components\/CommandHelpPanel\.tsx"/,
    "the help panel is not in the key registry (R388k)",
  );

  // The handler binds what the table promises.
  const handler = code.slice(code.indexOf("useInput("), code.indexOf("useInput(") + 1200);
  assert.match(handler, /key\.escape/, "the handler does not bind Esc (R388k)");
  assert.match(handler, /key\.return/, "the handler does not bind Enter (R388k)");
  assert.match(handler, /key\.upArrow/, "the handler does not bind ↑ (R388k)");
  assert.match(handler, /key\.downArrow/, "the handler does not bind ↓ (R388k)");

  // And it does NOT bind j/k — the filter box owns the letters, and a
  // key that means two things means neither. The table says so too.
  assert.doesNotMatch(
    handler,
    /input\s*===\s*"j"/,
    "the handler binds 'j' — the filter box owns the letters (R388k)",
  );
  assert.doesNotMatch(
    handler,
    /input\s*===\s*"k"/,
    "the handler binds 'k' — the filter box owns the letters (R388k)",
  );
  assert.doesNotMatch(
    keys,
    /components\/CommandHelpPanel\.tsx[\s\S]{0,600}?tokens:\s*\[[^\]]*"j"/,
    "the key table promises j/k but the handler does not bind them (R388k)",
  );
});

/* ------------------------------------------------------------------ *
 * 6. The line renderer still prints the reference.
 *
 * A terminal has no panels — but it HAS a scrollback, which is the
 * terminal's own scrolling. The line renderer must not lose the
 * reference when the Ink renderer gains a panel.
 * ------------------------------------------------------------------ */
test("R388k: the line renderer still prints the command reference", () => {
  const code = readCode("src/line.ts");
  assert.match(
    code,
    /__HELP_PANEL__/,
    "the line renderer does not handle the /help sentinel — the reference is lost (R388k)",
  );
  assert.match(code, /SLASH_HELP/, "the line renderer no longer prints SLASH_HELP (R388k)");
});

/* ------------------------------------------------------------------ *
 * 7. The panel's rows FIT the frame.
 *
 * R388j's width contract: a row that is one cell too wide shoves the
 * frame's right border off the edge. The panel's rows go through
 * `fitRow`, and the gate checks the panel uses it.
 * ------------------------------------------------------------------ */
test("R388k: the help panel's rows are fitted to the content width", () => {
  const code = readCode("src/components/CommandHelpPanel.tsx");
  assert.match(
    code,
    /from\s+"\.\/PanelBody\.js"/,
    "the panel does not use PanelBody — the width contract would be lost (R388k)",
  );
  assert.match(
    code,
    /\bfitRow\(/,
    "the panel does not fit its rows — a long description would push the border off (R388k)",
  );
});
