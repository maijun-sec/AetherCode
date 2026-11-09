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

// R388p — the REWIND menu: the list must FIT, the SELECTION must be VISIBLE,
// and every key must DO something.
//
// ## The contract
//
// The rewind menu had four defects, all found by MEASUREMENT (see
// `docs/R388p-checkpoint-window.md`):
//
//   1. THE LIST DID NOT FIT. Every checkpoint was rendered: 40 of them
//      made a 53-row frame — taller than a 40-row terminal, so the
//      panel ran off the screen and the user could not see the entry
//      they had selected.
//
//   2. THE DOWN ARROW WAS A DEAD KEY. The list opened with the
//      selection on the NEWEST entry and the clamp kept it there, so
//      the first press of the down arrow did nothing at all. A key
//      that does nothing is worse than a key that is absent: the user
//      concludes the feature is broken.
//
//   3. THE TWO SUMMARIZE ENTRIES READ IDENTICALLY. "Summarize from
//      here" keeps the TAIL and "Summarize up to here" keeps the HEAD
//      — opposite outcomes — and both carried the same blurb, which
//      said "keep what follows" of the action that keeps what came
//      BEFORE.
//
//   4. THE STORE GREW WITHOUT BOUND. Each checkpoint holds a FULL COPY
//      of every file the session has touched, so the daemon's memory
//      grew as turns x files x file-size — quadratic, and fatal.
//
// ## ★ THE PROBE RENDERS THE REAL COMPONENT AND SENDS REAL KEYS ★
//
// The keystroke part is not optional. Defect 2 is INVISIBLE to a
// static render: the list looks right until you press the key.
//
// ★ INK READS ITS INPUT ON THE 'readable' EVENT ★
//
// Ink's App.js does `stdin.on('readable', handleReadable)` and then
// `while ((chunk = stdin.read()) !== null)`. So a fake stdin must
// BUFFER the bytes and hand them back from `read()`. Emitting 'data'
// does nothing at all — Ink never listens for it. (A probe that emits
// 'data' measures a menu whose keys do not work, and then reports
// "the keys are broken" about a menu that is fine.)
//
// ★ AND `read()` MUST BE OVERRIDDEN AFTER CONSTRUCTION ★
//
// `Writable` has its own `read()` on the prototype, so an object
// literal method is shadowed by the prototype's — and Ink would call
// the prototype's one, which returns null and delivers nothing.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const CHECKPOINT_MENU = join(ROOT, "src", "components", "CheckpointMenu.tsx");
const LIST_WINDOW = join(ROOT, "src", "listWindow.ts");

/**
 * The keys the probe can send.
 *
 * <p>Ink's `parseKeypress` reads the raw sequence, so the arrow keys
 * are the terminal's own escape sequences.
 */
const KEY = {
  up: "[A",
  down: "[B",
  tab: "\t",
  return: "\r",
  escape: "",
};

/**
 * Run the REAL CheckpointMenu, optionally pressing keys, and return the
 * LAST frame's lines.
 *
 * @param {number} count   how many checkpoints to render
 * @param {string[]} keys  the keys to press, in order
 * @param {object} opts    {maxRows, evicted, width}
 * @returns {{lines: string[], raw: string}}
 */
function renderMenu(count, keys = [], opts = {}) {
  const dir = mkdtempSync(join(ROOT, ".r388p-probe-"));
  const entry = join(dir, "probe.tsx");
  const bundle = join(dir, "probe.mjs");
  try {
    const maxRows = opts.maxRows ?? 8;
    const evicted = opts.evicted ?? 0;
    const width = opts.width ?? 60;
    writeFileSync(
      entry,
      `
import React from "react";
import { render, Box } from "ink";
import { CheckpointMenu } from ${JSON.stringify(CHECKPOINT_MENU)};

const N = ${count};
const checkpoints = [];
for (let i = 0; i < N; i++) {
  checkpoints.push({
    id: "cp-" + i,
    userMessageIndex: i,
    summary: "turn " + i,
    files: [{ path: "C:/w/f" + i + ".ts", name: "f" + i + ".ts", restorable: true, created: false }],
  });
}

const { Writable } = await import("node:stream");
const chunks = [];
const stdout = new Writable({ write(c, _e, cb) { chunks.push(c.toString("utf8")); cb(); } });
stdout.columns = 80;
stdout.rows = 40;
stdout.isTTY = true;

let buffer = "";
const stdin = new Writable({ write(_c, _e, cb) { cb(); } });
// ★ OVERRIDE read() AFTER CONSTRUCTION ★ Writable has its own read()
// on the prototype, so an object-literal method is shadowed by it.
stdin.read = () => {
  if (buffer.length === 0) return null;
  const chunk = buffer;
  buffer = "";
  return chunk;
};
stdin.isTTY = true;
stdin.setRawMode = () => stdin;
stdin.setEncoding = () => stdin;
stdin.ref = () => stdin;
stdin.unref = () => stdin;
stdin.resume = () => stdin;
stdin.pause = () => stdin;
stdin.readableLength = 0;

const { unmount } = render(
  <Box flexDirection="column">
    <CheckpointMenu
      checkpoints={checkpoints}
      onChoose={() => {}}
      onClose={() => {}}
      width={${width}}
      maxRows={${maxRows}}
      evicted={${evicted}}
    />
  </Box>,
  { stdout, stdin, exitOnCtrlC: false, patchConsole: false },
);

const KEYS = ${JSON.stringify(keys)};
let i = 0;
function send() {
  if (i >= KEYS.length) {
    setTimeout(() => {
      process.stderr.write("__RAW__" + chunks.join("") + "__END__");
      unmount();
      process.exit(0);
    }, 250);
    return;
  }
  buffer += KEYS[i++];
  stdin.emit("readable");
  setTimeout(send, 120);
}
setTimeout(send, 350);
`,
      "utf8",
    );

    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(join(ROOT, "node_modules", "esbuild"))});\n` +
          `build({entryPoints:[${JSON.stringify(entry)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',external:['ink','react'],jsx:'automatic',loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(`r388p: could not build the menu probe: ${build.stderr || build.stdout}`);
    }

    const run = spawnSync(process.execPath, [bundle], { encoding: "utf8", timeout: 60_000 });
    const stderr = run.stderr ?? "";
    const m = stderr.match(/__RAW__([\s\S]*?)__END__/);
    if (!m) {
      throw new Error(
        `r388p: the menu probe produced no frame (stderr: ${stderr.slice(0, 1200)} stdout: ${(run.stdout ?? "").slice(0, 600)})`,
      );
    }
    // ★ THE LAST FRAME ONLY ★ Ink redraws in place, so the stream holds
    // every frame the console ever received. The boundary is the LAST
    // 'ESC[2K' IN THE RAW STREAM — stripping the ANSI first would
    // delete the very marker the boundary is defined by.
    const b = m[1].lastIndexOf("[2K");
    const rawFrame = b >= 0 ? m[1].slice(b) : m[1];
    const plain = rawFrame.replace(/\[[0-9;?]*[A-Za-z]/g, "");
    return { lines: plain.split("\n").filter((l) => l.trim().length > 0), raw: m[1] };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/**
 * The rows that show a checkpoint, and the one the marker is on.
 *
 * @param {string[]} lines the frame's lines
 */
function checkpointRows(lines) {
  return lines.filter((l) => /#\d+/.test(l));
}

/**
 * The checkpoint number the marker is on, or -1.
 *
 * @param {string[]} lines the frame's lines
 */
function markedCheckpoint(lines) {
  const row = checkpointRows(lines).find((l) => l.includes("▶"));
  if (!row) return -1;
  const m = row.match(/#(\d+)/);
  return m ? Number.parseInt(m[1], 10) : -1;
}

// ---------------------------------------------------------------------------
// 1. THE LIST MUST FIT
// ---------------------------------------------------------------------------

test("R388p: the checkpoint list FITS — 40 checkpoints do not make a 53-row frame", () => {
  const { lines } = renderMenu(40);
  const rows = checkpointRows(lines);
  assert.ok(
    rows.length <= 8,
    `R388p: the menu rendered ${rows.length} checkpoint rows for 40 checkpoints — the list must be WINDOWED, not exhaustive.`,
  );
  // The whole frame must fit a 40-row terminal.
  assert.ok(
    lines.length <= 40,
    `R388p: the menu's frame is ${lines.length} rows — taller than the terminal it is drawn in.`,
  );
});

test("R388p: the frame does not grow as the list grows", () => {
  const small = renderMenu(5);
  const large = renderMenu(60);
  // ★ MEASURE THE CHECKPOINT ROWS, NOT THE WHOLE FRAME ★
  //
  // The frame carries two extra lines when the list is windowed: the
  // "N earlier above" and "N later below" markers. Those are CORRECT
  // — they are how the menu says the list continues. So the invariant
  // is on the CHECKPOINT ROWS, which must not grow with the list.
  //
  // A list SHORTER than the window shows all of itself (5 checkpoints
  // = 5 rows), so the comparison is between two lists that are BOTH
  // longer than the window.
  const smallRows = checkpointRows(renderMenu(12).lines).length;
  const largeRows = checkpointRows(large.lines).length;
  assert.ok(
    largeRows <= smallRows,
    `R388p: the menu showed ${smallRows} checkpoint rows for 12 checkpoints and ${largeRows} for 60 — the list is not windowed.`,
  );
  assert.ok(
    largeRows <= 8,
    `R388p: the menu showed ${largeRows} checkpoint rows for 60 checkpoints — the window is not bounding it.`,
  );
  // And the whole frame still fits a 40-row terminal.
  assert.ok(
    large.lines.length <= 40,
    `R388p: the menu's frame is ${large.lines.length} rows — taller than the terminal it is drawn in.`,
  );
  // The short list shows itself in full — the window must not HIDE
  // entries when there is room for them.
  assert.equal(
    checkpointRows(small.lines).length,
    5,
    "R388p: a list of 5 did not show all 5 — the window is hiding entries when there is room.",
  );
});

// ---------------------------------------------------------------------------
// 2. THE SELECTION MUST BE VISIBLE
// ---------------------------------------------------------------------------

test("R388p: the SELECTED checkpoint is always on screen", () => {
  // Walk the whole list with the up arrow and check the marker is
  // never outside the visible rows.
  for (const steps of [0, 1, 5, 12, 25]) {
    const keys = [KEY.tab, ...Array.from({ length: steps }, () => KEY.up)];
    const { lines } = renderMenu(40, keys);
    const marked = markedCheckpoint(lines);
    assert.ok(marked > 0, `R388p: after ${steps} up presses there is no marked checkpoint.`);
    const visible = checkpointRows(lines).map((l) => {
      const m = l.match(/#(\d+)/);
      return m ? Number.parseInt(m[1], 10) : -1;
    });
    assert.ok(
      visible.includes(marked),
      `R388p: the selection is #${marked} but the visible rows are [${visible.join(", ")}] — the selection scrolled out of view.`,
    );
  }
});

test("R388p: the menu says how many points are hidden above and below", () => {
  const { lines } = renderMenu(40);
  const text = lines.join("\n");
  assert.match(
    text,
    /earlier point\(s\) above/,
    "R388p: the menu hides the older checkpoints without saying so — a silent truncation looks like the list ends there.",
  );
});

// ---------------------------------------------------------------------------
// 3. EVERY KEY MUST DO SOMETHING
// ---------------------------------------------------------------------------

test("R388p: the DOWN arrow is not a dead key", () => {
  // The list opens on the NEWEST entry, so the old clamped handler
  // made the first down press a no-op.
  const { lines } = renderMenu(40, [KEY.tab, KEY.down]);
  const marked = markedCheckpoint(lines);
  assert.notEqual(
    marked,
    40,
    `R388p: the down arrow did nothing — the selection is still #${marked} (the newest), which is where the list opened.`,
  );
});

test("R388p: BOTH arrows move the selection, and in OPPOSITE directions", () => {
  const up = markedCheckpoint(renderMenu(40, [KEY.tab, KEY.up, KEY.up]).lines);
  const down = markedCheckpoint(renderMenu(40, [KEY.tab, KEY.down, KEY.down]).lines);
  assert.ok(up > 0 && down > 0, "R388p: one of the arrows produced no marked checkpoint.");
  assert.notEqual(
    up,
    down,
    `R388p: both arrows landed on #${up} — the selection did not move in opposite directions.`,
  );
});

test("R388p: the selection WRAPS — the arrows are never dead at the ends", () => {
  // ★ THE LIST OPENS ON THE NEWEST ENTRY ★
  //
  // So the arithmetic is measured FROM there: the selection starts at
  // index 39 (#40), so ONE down press wraps it to index 0 (#1) — the
  // OLDEST — and a full cycle of 40 presses brings it back to #40.
  //
  // A clamped list would stop dead at the ends, which is exactly the
  // defect: the down arrow did nothing on open, because the list
  // opened at the bottom and the clamp kept it there.
  const N = 40;
  const oneDown = markedCheckpoint(renderMenu(N, [KEY.tab, KEY.down]).lines);
  assert.equal(
    oneDown,
    1,
    `R388p: one down press from the newest (#${N}) did not wrap to the oldest (#1) — got #${oneDown}. The down arrow is dead at the end.`,
  );
  const fullCycle = markedCheckpoint(
    renderMenu(N, [KEY.tab, ...Array.from({ length: N }, () => KEY.up)]).lines,
  );
  assert.equal(
    fullCycle,
    N,
    `R388p: ${N} up presses from #${N} did not come back to #${N} (got #${fullCycle}).`,
  );
});

// ---------------------------------------------------------------------------
// 4. THE TWO SUMMARIZE ENTRIES MUST BE DISTINGUISHABLE
// ---------------------------------------------------------------------------

/**
 * The blurb of ONE action, read out of the action table.
 *
 * <p>★ THE SLICE MUST START AT THE ACTION ITSELF ★ The two summarize
 * entries are ADJACENT, so a slice that starts at
 * `summarize_from_here` contains BOTH blurbs — and a regex that takes
 * the FIRST one returns the same string for both. That is how the two
 * entries could read identically and the check still passed.
 *
 * @param {string} src the component's source
 * @param {string} action the action's key
 */
function actionBlurb(src, action) {
  const i = src.indexOf(`${action}: {`);
  assert.ok(i > 0, `R388p: no entry for ${action} in the action table.`);
  // Stop at the NEXT action's key, so the slice holds one blurb only.
  const rest = src.slice(i + action.length + 3);
  const next = rest.search(/\n  [a-z_]+: \{/);
  const seg = next > 0 ? rest.slice(0, next) : rest;
  const m = seg.match(/blurb:\s*"([^"]*)"/);
  assert.ok(m, `R388p: no blurb for ${action}.`);
  return m[1];
}

test("R388p: the two summarize actions do NOT read identically", () => {
  const src = readFileSync(CHECKPOINT_MENU, "utf8");
  // The actions are "from" (keep the tail) and "up_to" (keep the head)
  // — opposite outcomes.
  const from = actionBlurb(src, "summarize_from_here");
  const upTo = actionBlurb(src, "summarize_up_to_here");
  assert.notEqual(
    from,
    upTo,
    `R388p: both summarize actions carry the blurb "${from}" — but one keeps the tail and the other keeps the head.`,
  );
});

test("R388p: the summarize blurbs describe OPPOSITE outcomes", () => {
  const src = readFileSync(CHECKPOINT_MENU, "utf8");
  const from = actionBlurb(src, "summarize_from_here");
  const upTo = actionBlurb(src, "summarize_up_to_here");
  // ★ BOTH SUMMARISE THE HEAD AND KEEP THE TAIL ★
  //
  // The daemon's boundary is the point's own message: `from` splits
  // BEFORE it, `up_to` splits AFTER it. So the difference is whether
  // the point's own message is folded into the summary.
  //
  // (An earlier version of this gate asserted the two were OPPOSITE
  // — "keep what follows" vs "keep what came before". That was read
  // off the menu's NAMES, not off the daemon, and it was wrong: the
  // engine summarises the head and keeps the tail in both cases. A
  // gate that pins a story instead of the behaviour is a gate that
  // makes the code lie to match it.)
  assert.match(
    from,
    /summarize everything BEFORE/i,
    `R388p: "Summarize from here" must say it summarises what came before (got "${from}").`,
  );
  assert.match(
    upTo,
    /INCLUDING this point/i,
    `R388p: "Summarize up to here" must say it includes the point's own message (got "${upTo}").`,
  );
  assert.match(
    upTo,
    /keep what follows/i,
    `R388p: both summarize actions keep the tail — "up to here" must say so (got "${upTo}").`,
  );
});

test("R388p: the daemon really does keep the TAIL for BOTH summarize actions", () => {
  // ★ THE BLURBS MUST MATCH THE ENGINE ★
  //
  // The menu's descriptions are a CLAIM about the daemon. A claim
  // about another system's behaviour cannot verify itself — so the
  // gate reads the engine's own split and checks that the menu's two
  // entries describe what it actually does.
  const engine = readFileSync(
    join(
      ROOT,
      "..",
      "aethercode",
      "aethercode-sdk",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "sdk",
      "AetherCodeEngine.java",
    ),
    "utf8",
  );
  // ★ ANCHOR ON THE DECLARATION, NOT THE CALL SITE ★
  //
  // There are three mentions of `summarizeAtTranscriptIndex`: two
  // CALLS and one DECLARATION. Anchoring on a call site would read
  // the caller's code and prove nothing about the split rule.
  const decl = engine.indexOf("private java.util.Map<String, Object> summarizeAtTranscriptIndex(");
  assert.ok(
    decl > 0,
    "R388p: could not find summarizeAtTranscriptIndex's DECLARATION in the engine.",
  );
  const seg = engine.slice(decl, decl + 2000);
  // The head is what gets summarised; the tail is what survives.
  assert.match(
    seg,
    /int split = keepAfter \? boundaryIndex : boundaryIndex \+ 1;/,
    "R388p: the engine's split rule changed — the menu's two summarize descriptions may no longer be accurate.",
  );
  assert.match(
    seg,
    /The head is what gets summarised; the tail is what survives\./,
    "R388p: the engine no longer summarises the head — the menu's descriptions would be wrong.",
  );
});

// ---------------------------------------------------------------------------
// 5. THE STORE MUST BE BOUNDED (the daemon side)
// ---------------------------------------------------------------------------

test("R388p: the daemon's checkpoint store is BOUNDED", () => {
  const src = readFileSync(
    join(
      ROOT,
      "..",
      "aethercode",
      "aethercode-core",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "core",
      "transcript",
      "CodeCheckpointStore.java",
    ),
    "utf8",
  );
  assert.match(
    src,
    /MAX_CHECKPOINTS\s*=\s*\d+/,
    "R388p: the checkpoint store has no bound — each checkpoint holds a full copy of every touched file, so the store grows quadratically.",
  );
  assert.match(
    src,
    /evictIfNeeded|evict/,
    "R388p: the store declares a bound but nothing evicts against it.",
  );
});

// ---------------------------------------------------------------------------
// 6. THE WINDOW ARITHMETIC (the pure module)
// ---------------------------------------------------------------------------

test("R388p: listWindow keeps the selection visible", async () => {
  const { listWindow } = await loadListWindow();
  for (const total of [1, 5, 40, 100]) {
    for (let sel = 0; sel < total; sel++) {
      const w = listWindow(total, sel, 8);
      assert.ok(
        sel >= w.start && sel <= w.end,
        `R388p: listWindow(${total}, ${sel}, 8) = [${w.start}, ${w.end}] — the selection is outside the window.`,
      );
      assert.ok(
        w.end - w.start + 1 <= 8,
        `R388p: listWindow(${total}, ${sel}, 8) showed ${w.end - w.start + 1} rows.`,
      );
    }
  }
});

test("R388p: listWindow survives an empty list", async () => {
  const { listWindow } = await loadListWindow();
  const w = listWindow(0, 0, 8);
  assert.equal(w.total, 0);
  assert.ok(
    w.end < w.start,
    "R388p: an empty list must produce an empty window, not a window of one.",
  );
});

/**
 * Load the pure window module.
 *
 * <p>★ ESBUILD, NOT TSC ★ The probe builds its bundle with esbuild,
 * and this module is loaded the same way. A `tsc` invocation on a
 * standalone file has to be told the module format, the target and
 * the resolution rules, and it reports its errors on stderr — so a
 * failure here was a silent "could not compile" with no reason.
 *
 * @returns {Promise<{listWindow: Function, moveSelection: Function}>}
 */
async function loadListWindow() {
  if (!existsSync(LIST_WINDOW)) {
    throw new Error(`r388p: no listWindow module at ${LIST_WINDOW}`);
  }
  const dir = mkdtempSync(join(ROOT, ".r388p-mod-"));
  try {
    const bundle = join(dir, "listWindow.mjs");
    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(join(ROOT, "node_modules", "esbuild"))});\n` +
          `build({entryPoints:[${JSON.stringify(LIST_WINDOW)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',loader:{'.ts':'ts'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: ROOT, encoding: "utf8", timeout: 120_000 },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(
        `r388p: could not build listWindow.ts: ${build.stderr || build.stdout || "(no output)"}`,
      );
    }
    const mod = await import(`file:///${bundle.replace(/\\/g, "/")}`);
    return { listWindow: mod.listWindow, moveSelection: mod.moveSelection };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("R388p: moveSelection WRAPS — it never leaves the user on a dead key", async () => {
  const { moveSelection } = await loadListWindow();
  assert.equal(moveSelection(6, 0, -1), 5, "R388p: up at the top must wrap to the last entry.");
  assert.equal(
    moveSelection(6, 5, 1),
    0,
    "R388p: down at the bottom must wrap to the first entry.",
  );
  assert.equal(moveSelection(6, 3, -1), 2);
  assert.equal(moveSelection(6, 3, 1), 4);
});
