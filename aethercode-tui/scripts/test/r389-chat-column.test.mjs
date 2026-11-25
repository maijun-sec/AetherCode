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

// R389 — the chat column must LOOK like the desktop's centre column.
//
// ## The defect
//
// The TUI rendered something the desktop does not: a dim "Assistant"
// label above every reply, a dim "Thinking" label above every think,
// and a BORDERED BOX around every tool call. Three pieces of chrome
// per turn. A forty-turn session read like a stack of crates.
//
// And the STRUCTURE was missing: the daemon's `sub_task_start` /
// `sub_task_end` events were dropped on the floor — the TUI had zero
// occurrences of `sub_task` in its whole source tree — so the model
// declaring "this is the task I am working on" never reached the
// screen. The desktop renders it as a `##` heading with a `结果`
// footer.
//
// ## ★ THE GATE MEASURES THE VALUES ★
//
// A grep for `buildBlocks` proves the string is in the file. It
// proves nothing about what the transcript looks like. So the probe
// runs the REAL module on a REAL transcript and the gate asserts on
// what it produced.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const BLOCKS = join(ROOT, "src", "chat", "blocks.ts");
const STATE = join(ROOT, "src", "state.ts");
const TUI = join(ROOT, "src", "tui.tsx");
const SCROLLBACK = join(ROOT, "src", "components", "Scrollback.tsx");
const CHATBLOCKS = join(ROOT, "src", "components", "ChatBlocks.tsx");
const CHANGES = join(ROOT, "src", "fileChanges.ts");
const PROBE_FIXTURE = join(ROOT, "scripts", "test", "fixtures", "r389-blocks-probe.ts");

/**
 * Build and run the probe from its fixture, and return the JSON it
 * reported.
 *
 * @returns {object} the probe's report
 */
function probe() {
  const dir = mkdtempSync(join(ROOT, ".r389-probe-"));
  const entry = join(dir, "probe.ts");
  const bundle = join(dir, "probe.mjs");
  try {
    let src = readFileSync(PROBE_FIXTURE, "utf8");
    src = src.split("__BLOCKS__").join(JSON.stringify(BLOCKS));
    writeFileSync(entry, src, "utf8");

    const esbuildDir = process.env.R388Q_ESBUILD_DIR || join(ROOT, "node_modules", "esbuild");
    const nodePaths = process.env.R388Q_NODE_PATHS || join(ROOT, "node_modules");
    const build = spawnSync(
      process.execPath,
      [
        "-e",
        "const {build}=require(" +
          JSON.stringify(esbuildDir) +
          ");\n" +
          "build({entryPoints:[" +
          JSON.stringify(entry) +
          "],bundle:true,outfile:" +
          JSON.stringify(bundle) +
          ",platform:'node',format:'esm',nodePaths:[" +
          JSON.stringify(nodePaths) +
          "],banner:{js:\"import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);\"},loader:{'.ts':'ts','.tsx':'tsx'}})" +
          ".catch(e=>{console.error(e.message);process.exit(1)});",
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error("r389: could not build the probe: " + (build.stderr || build.stdout));
    }
    const run = spawnSync(process.execPath, [bundle], { encoding: "utf8", timeout: 60_000 });
    const stderr = run.stderr ?? "";
    const m = stderr.match(/__R389__([\s\S]*?)__END__/);
    if (!m) {
      throw new Error("r389: the probe produced no result: " + stderr.slice(0, 800));
    }
    return JSON.parse(m[1]);
  } finally {
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
  }
}

const report = probe();

// ═══════════════════════════════════════════════════════════════════
// THE STRUCTURE
// ═══════════════════════════════════════════════════════════════════

test("R389: the transcript is a TIMELINE — the user's words first, then the agent's blocks", () => {
  assert.deepEqual(
    report.timeline,
    [
      { kind: "user", turnId: 1 },
      { kind: "agent", id: "0:a" },
      { kind: "agent", id: "0:b" },
    ],
    "r389: the transcript is not a chronological timeline — got " + JSON.stringify(report.timeline),
  );
});

test("R389: a sub-task becomes a `##` heading, and its steps become the blocks under it", () => {
  const a = report.groups.find((g) => g.id === "0:a");
  assert.ok(a, "r389: sub-task 0:a produced no group.");
  assert.equal(a.kind, "subtask", "r389: a group with a sub-task is not a subtask.");
  assert.equal(a.title, "read the source", "r389: the heading is not the sub-task's content.");
  assert.deepEqual(
    a.blocks.map((b) => b.kind),
    ["header", "think", "tool", "think", "answer", "result"],
    "r389: sub-task 0:a's blocks are wrong — got " + JSON.stringify(a.blocks.map((b) => b.kind)),
  );
});

test("R389: an OPEN sub-task has no 结果 block", () => {
  const b = report.groups.find((g) => g.id === "0:b");
  assert.ok(b, "r389: sub-task 0:b produced no group.");
  assert.deepEqual(
    b.blocks.map((x) => x.kind),
    ["header", "tool"],
    "r389: an unclosed sub-task must not carry a result block — got " +
      JSON.stringify(b.blocks.map((x) => x.kind)),
  );
  const result = a_result(report, "0:a");
  assert.equal(result.status, "completed", "r389: the result block lost the sub-task's status.");
  assert.equal(result.title, "结果", "r389: the result block is not labelled 结果.");
});

// ═══════════════════════════════════════════════════════════════════
// THE FOLD POLICY
// ═══════════════════════════════════════════════════════════════════

test("R389: the fold policy is the DESKTOP'S — history folded, the answer open", () => {
  const a = report.groups.find((g) => g.id === "0:a");
  const byKind = a.blocks.filter((b) => b.kind === "think");
  assert.equal(a.blocks[0].idleOpen, true, "r389: the heading must always be open.");
  assert.equal(byKind[0].idleOpen, false, "r389: an EARLIER think must be folded.");
  assert.equal(byKind[1].idleOpen, true, "r389: the FINAL think must be open.");
  const tool = a.blocks.find((b) => b.kind === "tool");
  assert.equal(tool.idleOpen, false, "r389: a tool call must be folded by default.");
  const answer = a.blocks.find((b) => b.kind === "answer");
  assert.equal(answer.idleOpen, true, "r389: the answer must be open.");
  const result = a.blocks.find((b) => b.kind === "result");
  assert.equal(result.idleOpen, true, "r389: the result must always be open.");
});

test("R389: while the run is live, the LAST block is open", () => {
  const b = report.groups.find((g) => g.id === "0:b");
  const last = b.blocks[b.blocks.length - 1];
  assert.equal(last.kind, "tool", "r389: the fixture's last block is not the tool call.");
  assert.equal(last.liveOpen, true, "r389: the live tail must be open.");
});

test("R389: the FINAL think is always open", () => {
  // The desktop's rule (R276): the last think is the model's
  // concluding reasoning, and the user always wants to see it.
  const a = report.groups.find((g) => g.id === "0:a");
  const thinks = a.blocks.filter((b) => b.kind === "think");
  assert.equal(thinks.length, 2, "r389: the fixture must have an earlier AND a final think.");
  assert.equal(
    thinks[thinks.length - 1].idleOpen,
    true,
    "r389: the final think is not open — the desktop's rule was lost.",
  );
});

test("R389: the collapsed state is the TURN's own — so Tab can open a block", () => {
  const a = report.groups.find((g) => g.id === "0:a");
  const tool = a.blocks.find((b) => b.kind === "tool");
  // The turn's `collapsed` is what the Tab key flips, so a folded
  // block must be foldable BY THE USER, not by a hard-coded rule.
  assert.equal(tool.idleOpen, false, "r389: the tool call should start folded.");
  const state = readFileSync(STATE, "utf8");
  assert.match(state, /case "toggleTurn"/, "r389: the turn's fold state is not togglable.");
});

// ═══════════════════════════════════════════════════════════════════
// THE PREVIEWS
// ═══════════════════════════════════════════════════════════════════

test("R389: a folded row shows the INTERESTING field, not the raw JSON", () => {
  assert.equal(
    report.toolPreview,
    "src/App.tsx",
    "r389: the tool row does not show the file path — got " + JSON.stringify(report.toolPreview),
  );
  assert.equal(
    report.bashPreview,
    "npm test",
    "r389: the tool row does not show the command — got " + JSON.stringify(report.bashPreview),
  );
  assert.equal(
    report.thinkPreview,
    "plan: step one step two",
    "r389: the think preview does not strip the markdown — got " +
      JSON.stringify(report.thinkPreview),
  );
});

test("R389: a file_edit shows a REAL diff, built from the tool's own input", () => {
  assert.ok(report.diffHasHeader, "r389: the diff has no file header.");
  assert.deepEqual(
    report.diffSegments,
    ["meta", "meta", "del", "del", "add", "add"],
    "r389: the diff is not a real +/- diff — got " + JSON.stringify(report.diffSegments),
  );
});

// ═══════════════════════════════════════════════════════════════════
// THE WIRING — source pins for the things a probe cannot measure
// ═══════════════════════════════════════════════════════════════════

test("R389: the daemon's sub-task events reach the reducer", () => {
  const tui = readFileSync(TUI, "utf8");
  assert.match(
    tui,
    /case "sub_task_start"/,
    "r389: the TUI drops the daemon's sub_task_start event.",
  );
  assert.match(tui, /case "sub_task_end"/, "r389: the TUI drops the daemon's sub_task_end event.");
  const state = readFileSync(STATE, "utf8");
  assert.match(state, /case "subTaskStart"/, "r389: the reducer has no subTaskStart case.");
  assert.match(state, /case "subTaskEnd"/, "r389: the reducer has no subTaskEnd case.");
  assert.match(
    state,
    /subTaskId: state\.currentSubTaskId \?\? undefined/,
    "r389: the turns are not stamped with the open sub-task — the `##` heading would have nothing to nest under.",
  );
});

test("R389: the old chrome is GONE — no per-turn labels, no bordered tool boxes", () => {
  const scroll = readFileSync(SCROLLBACK, "utf8");
  assert.doesNotMatch(
    scroll,
    />\s*Assistant\s*</,
    "r389: the chat column still prefixes every reply with an 'Assistant' label.",
  );
  assert.doesNotMatch(
    scroll,
    />\s*Thinking\s*</,
    "r389: the chat column still prefixes every think with a 'Thinking' label.",
  );
  assert.doesNotMatch(
    scroll,
    /from "\.\/ToolCard\.js"/,
    "r389: the chat column still renders the boxed tool card.",
  );
  const chatBlocks = readFileSync(CHATBLOCKS, "utf8");
  assert.doesNotMatch(
    chatBlocks,
    /borderStyle=/,
    "r389: the chat column still draws a border around a tool call.",
  );
});

test("R389: the file-change grouping is ONE algorithm, with three callers", () => {
  const changes = readFileSync(CHANGES, "utf8");
  assert.match(
    changes,
    /export function fileChangeRuns/,
    "r389: the shared grouping rule is gone.",
  );
  assert.match(
    changes,
    /export function fileChangeGroupIdAt/,
    "r389: the app's targeting rule is gone.",
  );
  const blocks = readFileSync(BLOCKS, "utf8");
  assert.match(
    blocks,
    /from "\.\.\/fileChanges\.js"/,
    "r389: the chat column re-implements the file-change rule instead of reading the shared one.",
  );
  const scroll = readFileSync(SCROLLBACK, "utf8");
  assert.match(
    scroll,
    /fileChangeOfTurnParts/,
    "r389: the Scrollback keeps its own copy of the rule.",
  );
});

/**
 * The result block of a group, or null.
 *
 * @param {object} report the probe's report
 * @param {string} id the group's id
 * @returns {object|null} the result block
 */
function a_result(report, id) {
  const g = report.groups.find((x) => x.id === id);
  if (!g) return null;
  return g.blocks.find((b) => b.kind === "result") ?? null;
}
