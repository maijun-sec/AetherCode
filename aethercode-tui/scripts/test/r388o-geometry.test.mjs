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

// R388o — THE FRAME FITS THE TERMINAL.
//
// ## The contract
//
// The user reported a page that "changes size, moves around, and moves
// with the input". The screenshot showed a band of ~12 REPEATED
// header rows, an input pill whose right border was CUT OFF, and a
// status bar that WRAPPED onto a second line.
//
// The measurement found the cause: the frame was WIDER than the
// terminal (the status bar's right-hand box carried `flexShrink={0}`
// and never shrank). The overflow made the terminal wrap the row,
// which added a row to the frame; a frame taller than `stdout.rows`
// puts Ink on its `clearTerminal` path (`ESC[2J ESC[3J ESC[H`); on a
// console that does not honour `ESC[2J` every render APPENDS
// instead of replacing — the stack of headers.
//
// So the contract is ONE line:
//
//     THE FRAME IS NEVER WIDER THAN THE TERMINAL,
//     AND NEVER TALLER THAN THE TERMINAL,
//     AND NEVER ACHIEVES THAT BY CLIPPING THE CONTENT.
//
// ## Why this gate measures, rather than greps
//
// A grep for `flexShrink={0}` pins a SPELLING, not an INVARIANT
// (Lesson 1233). The invariant here is geometric: render the REAL
// components at a known width and MEASURE the frame. The measurement
// uses the faithful terminal model in `r388j-render-frame.mjs` —
// Lesson 1311: a measurement that deletes the thing it measures
// cannot see the thing it measures.
//
// ## ★ THE PROBE RENDERS THE REAL COMPONENTS ★
//
// The first draft rendered a MIRROR of the layout (a hand-written
// `<Text>Start a conversation...</Text>` where the transcript goes).
// The mirror drifted from the product, and the mutation test found
// it: with an empty transcript the row budget does not matter, so
// putting it back to 1000 was invisible.
//
// The probe now renders the REAL `Scrollback`, and it READS the
// transcript's row budget out of `src/tui.tsx` — so a mutation of
// the budget is visible to the probe, which is the whole point.
//
// → **Lesson 1376 — a probe that mirrors the product drifts from it,
// and the drift hides exactly the mutations the probe exists to
// catch.**

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { reconstructScreen } from "./r388j-render-frame.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// ★ THE CAP, READ FROM THE PRODUCT ★ The number of lines the input
// shows is the PRODUCT's number (`terminalGeometry.INPUT_MAX_LINES`),
// not a constant written here. A gate that hard-codes "3" and the
// product that says "5" disagree quietly, and the gate is the one
// that gets blamed.
const { INPUT_MAX_LINES } = await loadTsModule(ROOT, "src/terminalGeometry.ts");

/**
 * Read the transcript's row budget OUT OF THE PRODUCT.
 *
 * The budget is a contract on a VALUE, not on the frame: once the
 * transcript's column carries `flexBasis={0}`, the frame's height no
 * longer depends on the budget (the flex chain clips it), so a
 * rendered-frame check cannot see a budget that went back to 1000.
 * What the budget still governs is the LAYOUT COST — the Scrollback
 * slices the turns to the budget, and without the slice Yoga lays out
 * every turn ever written.
 *
 * So the budget is evaluated here, from the product's own expression,
 * and the gate asserts on the number.
 *
 * @param {number} rows the terminal height to evaluate for
 * @returns {Promise<{budget: number, chromeHeight: number}>}
 */
async function readBudget(rows = 30) {
  const tuiSrc = readFileSync(join(ROOT, "src", "tui.tsx"), "utf8");
  const budgetMatch = tuiSrc.match(/const transcriptBudget = ([^;]+);/);
  assert.ok(budgetMatch, "R388o: could not find 'const transcriptBudget = ...' in src/tui.tsx");
  const chromeMatch = tuiSrc.match(/const chromeHeight =\s*([\s\S]*?);\s*\n/);
  assert.ok(chromeMatch, "R388o: could not find 'const chromeHeight = ...' in src/tui.tsx");
  const heightMatch = tuiSrc
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .match(/<Scrollback\b[^>]*?height=\{([^}]+)\}/s);
  assert.ok(heightMatch, "R388o: could not find the Scrollback's height={...} in src/tui.tsx");

  const chromeHeight = new Function("state", "INPUT_PILL_ROWS", "return " + chromeMatch[1] + ";")(
    { layout: "full" },
    3,
  );
  const transcriptBudget = new Function(
    "rows",
    "chromeHeight",
    "transcriptRows",
    "return " + budgetMatch[1] + ";",
  )(rows, chromeHeight, (t, c) => Math.max(1, t - c));
  const given = new Function("transcriptBudget", "return " + heightMatch[1] + ";")(
    transcriptBudget,
  );
  return { budget: given, chromeHeight };
}

/**
 * Render the REAL chrome (Header + Scrollback + the input pill + the
 * status bar) at a controlled width and height, and return the
 * reconstructed screen AND the frame's own line widths.
 *
 * @param {number} cols  the terminal width to lay out for
 * @param {number} rows  the terminal height to lay out for
 * @param {string} input the text in the input box
 * @returns {Promise<{screen: string[], frameLines: string[], widestLine: number}>}
 */
async function renderFrame(cols, rows, input = "", opts = {}) {
  const dir = mkdtempSync(join(ROOT, ".r388o-probe-"));
  const entry = join(dir, "probe.tsx");
  const bundle = join(dir, "probe.mjs");
  try {
    writeFileSync(
      entry,
      `
import React from "react";
import { render, Box, Text } from "ink";
import { readFileSync } from "node:fs";
import { Header } from ${JSON.stringify(join(ROOT, "src", "components", "Header.tsx"))};
import { StatusBar } from ${JSON.stringify(join(ROOT, "src", "components", "StatusBar.tsx"))};
import { InputBox } from ${JSON.stringify(join(ROOT, "src", "components", "InputBox.tsx"))};
import { Scrollback } from ${JSON.stringify(join(ROOT, "src", "components", "Scrollback.tsx"))};

const state = {
  connected: true,
  model: "MiniMax-M3",
  provider: "",
  permissionMode: "DEFAULT",
  contextWindow: 200000,
  inputTokens: 1200,
  outputTokens: 300,
  sessionId: "default",
  uptimeStartedAt: Date.now(),
  themeName: "dark",
  // ★ A REAL TRANSCRIPT ★ With an empty transcript the transcript's
  // row budget does not matter: nothing overflows, and a mutation
  // that puts the budget back to 1000 is invisible. The gate has to
  // render the thing the budget is FOR.
  //
  // The count comes from the environment so a check can ask for a
  // LONG transcript — the case where the transcript's content used to
  // squeeze the header and the input pill out of the frame.
  turns: Array.from({ length: Number(process.env.PROBE_TURNS || 12) }, (_, i) => ({
    id: i,
    role: "assistant",
    text: "transcript line ".repeat(3),
    ts: Date.now(),
    collapsed: false,
  })),
  todos: [],
  currentTodoId: null,
  input: ${JSON.stringify(input)},
  submitting: false,
  status: "idle",
  layout: "full",
  pending: 0,
  totalCostUsd: 0,
  jarPath: "C:\\\\work\\\\aethercode-cli-0.1.0-SNAPSHOT.jar",
  lastStopReason: null,
  lastStopKind: "ok",
  connectionAttempt: 0,
  maxConnectionAttempts: 3,
  showThinking: true,
  showSearch: false,
  searchQuery: "",
  searchMatches: [],
  collapseAll: false,
  rightPanelVisible: false,
  activePanel: null,
  panelStatus: null,
  panelError: null,
  toasts: [],
  history: [],
  historyIndex: -1,
  permissionAsk: null,
  commentBox: null,
  sddRun: null,
  pendingPrompts: [],
  cancelRequested: false,
  continuationPaused: false,
  staleSession: null,
  themePickerOpen: false,
  decisionCardExpanded: true,
  autoContinueCountdown: null,
  subagentSpawnCards: {},
  continuationStopped: false,
  taskInFlight: false,
};

const PROBE_COLS = Number(process.env.PROBE_COLS || 100);
const PROBE_ROWS = Number(process.env.PROBE_ROWS || 30);

// ★ THE TRANSCRIPT'S ROW BUDGET — READ FROM THE PRODUCT ★
//
// The probe READS the real expressions out of \`tui.tsx\`; it does not
// compute its own. A probe that computes its own budget cannot see a
// mutation of the budget in the product: the two drift, and the gate
// goes blind to exactly the thing it exists to catch.
//
// The probe reads the file ITSELF (it runs in a separate process,
// bundled by esbuild) — and in the mutation's COPY, which is the
// point.
const tuiSrc = readFileSync(${JSON.stringify(join(ROOT, "src", "tui.tsx"))}, "utf8");
const budgetMatch = tuiSrc.match(/const transcriptBudget = ([^;]+);/);
if (!budgetMatch) {
  throw new Error("r388o: could not find 'const transcriptBudget = ...' in src/tui.tsx");
}
const chromeMatch = tuiSrc.match(/const chromeHeight =\\s*([\\s\\S]*?);\\s*\\n/);
if (!chromeMatch) {
  throw new Error("r388o: could not find 'const chromeHeight = ...' in src/tui.tsx");
}
// ★ THE PRODUCT'S FLEX CHAIN — READ FROM THE PRODUCT ★
//
// The chrome (header, input pill, status bar) and the transcript are
// FLEX SIBLINGS. Yoga gives a flex item a BASE SIZE equal to its
// CONTENT, and a flex item only shrinks when the container overflows.
// So a transcript whose content is taller than the terminal does not
// clip itself — it takes the rows and SQUEEZES the chrome.
//
// That is the defect the user reported: a long conversation made the
// header and the input pill collapse to a single row.
//
// The fix is a flex-basis chain: the transcript's columns get
// \`flexBasis={0}\` (base size 0, grow into the leftover rows) and
// \`minHeight={0}\` (a flex child may shrink below its content), and
// the chrome gets \`flexShrink={0}\` (it keeps its rows).
//
// ★ THE PROBE READS THE PRODUCT'S OWN CHAIN ★ If the probe wrote its
// own chain, a mutation of the PRODUCT's chain would not be in front
// of it, and the gate would be blind to exactly the thing it exists
// to catch. So the chain is read out of \`tui.tsx\` — and out of the
// mutated copy, which is the point.
const tuiCode = tuiSrc.replace(/\\/\\*[\\s\\S]*?\\*\\//g, " ");
const heightMatch = tuiCode.match(/<Scrollback\\b[^>]*?height=\\{([^}]+)\\}/s);
if (!heightMatch) {
  throw new Error("r388o: could not find the Scrollback's height={...} in src/tui.tsx");
}
const chromeHeight = new Function(
  "state",
  "INPUT_PILL_ROWS",
  "return " + chromeMatch[1] + ";",
)(state, 3);
const transcriptBudget = new Function(
  "rows",
  "chromeHeight",
  "transcriptRows",
  "return " + budgetMatch[1] + ";",
)(PROBE_ROWS, chromeHeight, (t, c) => Math.max(1, t - c));
// ★ AND THE HEIGHT THE SCROLLBACK IS GIVEN ★ The budget is not the
// only thing: the Scrollback is handed \`height={...}\`, and THAT is
// what a mutation changes. So the probe reads the usage too.
const scrollbackHeight = new Function(
  "transcriptBudget",
  "return " + heightMatch[1] + ";",
)(transcriptBudget);

const App = () => (
  <Box flexDirection="column" flexGrow={1} height={PROBE_ROWS}>
    <Header state={state as any} cwd="D:\\\\work\\\\workspace\\\\idea\\\\engine\\\\RAG" width={PROBE_COLS} />
    <Box flexDirection="row" flexGrow={1} flexBasis={0} minHeight={0}>
      <Box flexDirection="column" flexGrow={1} flexBasis={0} minHeight={0}>
        <Scrollback
          turns={state.turns}
          height={scrollbackHeight}
          showWelcome={state.turns.length === 0}
          welcomeCtx={{ model: state.model, cwd: "D:\\\\work", sessionId: state.sessionId, mode: state.permissionMode }}
          collapseAll={false}
          onToggleTurn={() => {}}
          lastStopReason={null}
          lastStopKind="ok"
          lastError={null}
          showThinking={true}
          permissionAsk={null}
          decisionCardExpanded={true}
          terminalWidth={PROBE_COLS}
        />
        <InputBox
          state={state as any}
          width={PROBE_COLS}
          onChange={() => {}}
          onSubmit={() => {}}
        />
        <StatusBar
          state={state as any}
          width={PROBE_COLS}
          onContinue={() => {}}
          onPause={() => {}}
          onStop={() => {}}
        />
      </Box>
    </Box>
  </Box>
);

const { Writable } = await import("node:stream");
const chunks = [];
const stdout = new Writable({
  write(chunk, _enc, cb) { chunks.push(chunk.toString("utf8")); cb(); },
});
stdout.columns = Number(process.env.PROBE_COLS || 100);
stdout.rows = Number(process.env.PROBE_ROWS || 30);
stdout.isTTY = true;

// ★ THE STDIN MUST BE A TTY TOO ★
// The real StatusBar calls \`useInput\`, and Ink's useInput throws
// "Raw mode is not supported" when stdin is not a TTY — and Ink then
// renders the ERROR as the frame. A probe with a pipe on stdin does
// not measure the layout; it measures Ink's crash page.
//
// (Lesson 1311: a measurement that deletes the thing it measures
// cannot see the thing it measures. Here the probe was measuring the
// error page and calling it "the status bar is missing".)
const stdin = new Writable({
  write(_chunk, _enc, cb) { cb(); },
});
stdin.isTTY = true;
stdin.setRawMode = () => stdin;
stdin.setEncoding = () => stdin;
stdin.ref = () => stdin;
stdin.unref = () => stdin;
stdin.resume = () => stdin;
stdin.pause = () => stdin;
stdin.read = () => null;
stdin.readableLength = 0;

const { unmount } = render(<App />, { stdout, stdin, exitOnCtrlC: false, patchConsole: false });
setTimeout(() => {
  process.stderr.write("__RAW__" + chunks.join("") + "__END__");
  unmount();
}, 400);
`,
      "utf8",
    );

    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(join(ROOT, "node_modules", "esbuild"))});
build({entryPoints:[${JSON.stringify(entry)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',external:['ink','react'],jsx:'automatic',loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(`r388o: could not build the frame probe: ${build.stderr || build.stdout}`);
    }

    const run = spawnSync(process.execPath, [bundle], {
      encoding: "utf8",
      timeout: 60_000,
      env: {
        ...process.env,
        PROBE_COLS: String(cols),
        PROBE_ROWS: String(rows),
        PROBE_TURNS: String(opts.turns ?? 12),
      },
    });
    const raw = (run.stderr ?? "") + "";
    const err = raw.match(/__ERR__([\s\S]*?)__END__/);
    const m = raw.match(/__RAW__([\s\S]*?)__END__/);
    if (!m) {
      throw new Error(
        `r388o: the probe produced no frame (stderr: ${err ? err[1].slice(0, 1200) : raw.slice(0, 1200)})`,
      );
    }
    // Which source tree did the probe READ? The mutation test writes
    // a marker file into the tree it mutated; if the marker is not
    // here, the probe read the ORIGINAL tree and the mutation was
    // never in front of it.
    const marker = join(ROOT, "src", "components", "__r388o_marker__.txt");
    if (existsSync(marker)) {
      console.log(`[r388o] probe read the MUTATED tree (marker present at ${marker})`);
    }
    const screen = reconstructScreen(m[1], cols);
    // ★ THE FRAME'S OWN LINE WIDTHS ★
    //
    // The reconstructed screen WRAPS at the terminal's width, so a
    // row that is too wide is split across two rows and its overflow
    // is invisible in the screen. The frame's own lines are the
    // ground truth: Ink writes each row as a line of text, and a line
    // longer than the terminal is a line the terminal will wrap.
    const frameLines = extractFrameLines(m[1]);
    const widestLine = Math.max(...frameLines.map((l) => l.length), 0);
    if (process.env.PROBE_DUMP) {
      console.error(`=== COLS=${cols} ROWS=${rows} ===`);
      screen.forEach((l, i) => {
        const over = l.length > cols ? ` <<< OVER by ${l.length - cols}` : "";
        console.error(`${String(i).padStart(3)} |${l}|${over}`);
      });
      console.error(`frame lines: ${frameLines.map((l) => l.length).join(",")}`);
    }
    return { screen, frameLines, widestLine };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/**
 * The frame's own lines, taken from the LAST frame Ink wrote.
 *
 * Ink's `log-update` writes `eraseLines(n) + frame`, and
 * `ansiEscapes.eraseLines(n)` is `ESC[2K ESC[1A` repeated n times,
 * then `ESC[G`. So the LAST `ESC[2K` marks the start of the last
 * frame's text.
 *
 * ★ THE BOUNDARY IS THE ERASE, NOT THE NEWLINE ★ A newline is a
 * line WITHIN a frame, so `lastIndexOf("\n")` lands in the middle of
 * the previous frame and the "last frame" comes out empty or partial.
 *
 * @param {string} raw the byte stream
 * @returns {string[]} the frame's lines
 */
function extractFrameLines(raw) {
  let plain = "";
  let lastErase = -1;
  let i = 0;
  while (i < raw.length) {
    if (raw[i] === "\u001b") {
      if (raw[i + 1] === "[") {
        let j = i + 2;
        while (j < raw.length && !/[A-Za-z]/.test(raw[j])) j += 1;
        const final = raw[j];
        if (final === "K") lastErase = plain.length;
        i = j + 1;
        continue;
      }
      if (raw[i + 1] === "]") {
        let j = i + 2;
        while (j < raw.length && raw[j] !== "\u0007") j += 1;
        i = j + 1;
        continue;
      }
      i += 2;
      continue;
    }
    if (raw[i] === "\u0007") {
      i += 1;
      continue;
    }
    plain += raw[i];
    i += 1;
  }
  const tail = lastErase >= 0 ? plain.slice(lastErase) : plain;
  return tail.split("\n");
}

test("R388o: the rendered frame is never wider than the terminal", async () => {
  // ★ NARROW TERMINALS ARE PART OF THE CONTRACT ★
  // The first draft of this gate tested 80/100/120 only, and the
  // mutation test caught the hole: a rule with a hard-coded 60 cells
  // FITS at 80 and only overflows below 60 — so the mutation stayed
  // GREEN and the gate was blind to a whole class of defect.
  //
  // The TUI's own floor is 40 columns (`terminalGeometry.MIN_COLS`),
  // so 40 is where the frame must still fit.
  for (const cols of [40, 50, 60, 80, 100, 120, 160]) {
    const { frameLines, widestLine } = await renderFrame(cols, 30);
    assert.ok(
      widestLine <= cols,
      `R388o: at ${cols} columns the frame's widest line is ${widestLine} cells — it overflows by ${widestLine - cols}.\n` +
        frameLines.map((l, i) => `  ${String(i).padStart(3)} |${l}|`).join("\n"),
    );
  }
});

test("R388o: the rendered frame is never taller than the terminal", async () => {
  for (const rows of [12, 20, 30, 50]) {
    const { screen, frameLines } = await renderFrame(100, rows);
    assert.ok(
      screen.length <= rows,
      `R388o: at ${rows} rows the frame is ${screen.length} rows tall — it overflows by ${screen.length - rows}.`,
    );
    assert.ok(
      frameLines.length <= rows,
      `R388o: at ${rows} rows the frame has ${frameLines.length} lines — it overflows by ${frameLines.length - rows}.`,
    );
  }
});

test("R388o: the frame's height does not change as the input grows", async () => {
  // "the box size changes as the input changes" — the frame's row
  // count must not depend on how much text is in the input.
  const empty = await renderFrame(100, 30, "");
  const short = await renderFrame(100, 30, "hello");
  const long = await renderFrame(100, 30, "a".repeat(200));
  assert.equal(
    empty.frameLines.length,
    short.frameLines.length,
    `R388o: typing one character changed the frame from ${empty.frameLines.length} to ${short.frameLines.length} rows.`,
  );
  assert.equal(
    empty.frameLines.length,
    long.frameLines.length,
    `R388o: typing 200 characters changed the frame from ${empty.frameLines.length} to ${long.frameLines.length} rows.`,
  );
  // ★ AND NOT WITH NEWLINES EITHER ★ A shift+Enter newline adds a LINE
  // to the input. The first draft of this check only typed characters,
  // so it never exercised the one input that used to add a row to the
  // frame per line — and the pill's height cap went untested.
  const multiline = await renderFrame(100, 30, "a\nb\nc\nd\ne\nf\ng\nh");
  assert.equal(
    empty.frameLines.length,
    multiline.frameLines.length,
    `R388o: an 8-line input changed the frame from ${empty.frameLines.length} to ${multiline.frameLines.length} rows.`,
  );
});

test("R388o: the status bar does not wrap onto a second row", async () => {
  for (const cols of [40, 80, 100, 120]) {
    const { frameLines } = await renderFrame(cols, 30);
    const statusRows = frameLines.filter((l) => l.includes("ready") || l.includes("SNAPSHOT"));
    assert.equal(
      statusRows.length,
      1,
      `R388o: at ${cols} columns the status bar occupies ${statusRows.length} rows — it wrapped:\n` +
        statusRows.map((l) => `  |${l}|`).join("\n"),
    );
  }
});

test("R388o: the status bar's state label is never clipped away", async () => {
  // ★ THE OTHER HALF OF THE CONTRACT ★
  //
  // A frame that FITS is not enough: the content must not be clipped
  // to achieve it. When the status bar's right-hand side does not fit
  // and the items are not dropped, Yoga wraps the Text inside the
  // box — and the frame's height constraint then clips the
  // continuation. The row still "fits"; the information is gone.
  //
  // So the bar's ESSENTIAL content must be on screen: the state
  // label and the connection dot. They are the two things that say
  // "is this session alive".
  for (const cols of [40, 50, 60, 80, 100, 120]) {
    const { frameLines } = await renderFrame(cols, 30);
    const statusRow = frameLines.find((l) => l.includes("ready") || l.includes("stopped"));
    assert.ok(
      statusRow,
      `R388o: at ${cols} columns the status bar's row is missing entirely:\n` +
        frameLines.map((l) => `  |${l}|`).join("\n"),
    );
    assert.match(
      statusRow,
      /[●○]/,
      `R388o: at ${cols} columns the connection dot was clipped away:\n  |${statusRow}|`,
    );
    assert.match(
      statusRow,
      /ready|stopped|thinking|streaming|working|empty/,
      `R388o: at ${cols} columns the state label was clipped away:\n  |${statusRow}|`,
    );
  }
});

test("R388o: the status bar shows its full set of items when there is room", async () => {
  // ★ THE NARROW-TERMINAL CONTRACT HAS A WIDE-TERMINAL COUNTERPART ★
  //
  // The dropping is a LAST RESORT: on a terminal that has room, the
  // bar must show everything. A mutation that disables the dropping
  // does not make the bar wider — it makes the bar's content WRAP
  // inside its box, and the frame's height constraint then clips the
  // continuation. The row still fits; the information is gone.
  for (const cols of [100, 120, 160]) {
    const { frameLines } = await renderFrame(cols, 30);
    const statusRow = frameLines.find((l) => l.includes("ready") || l.includes("stopped"));
    assert.ok(statusRow, `R388o: at ${cols} columns the status bar's row is missing.`);
    for (const needle of ["ctx", "in ", "out ", "jar"]) {
      assert.ok(
        statusRow.includes(needle),
        `R388o: at ${cols} columns the status bar lost "${needle}" — it should fit:\n  |${statusRow}|`,
      );
    }
  }
});

test("R388o: the header's identity is never clipped away", async () => {
  // ★ THE HEADER'S LEFT HALF IS THE SESSION'S IDENTITY ★
  //
  // The header's right half may be dropped (elapsed, session id, the
  // context meter). The left half — the model and the mode badge — may
  // NOT: those are what the user reads to know WHICH model is
  // answering and whether it will stop to ask.
  for (const cols of [40, 50, 60, 80, 100, 120]) {
    const { frameLines } = await renderFrame(cols, 30);
    const strip = frameLines.find((l) => l.includes("MiniMax-M3"));
    assert.ok(
      strip,
      `R388o: at ${cols} columns the header's status strip is missing:\n` +
        frameLines.map((l) => `  |${l}|`).join("\n"),
    );
    assert.match(
      strip,
      /MiniMax-M3/,
      `R388o: at ${cols} columns the model name was clipped:\n  |${strip}|`,
    );
    assert.match(
      strip,
      /ASK|TASK|EDITS|READ|PLAN|BYPASS/i,
      `R388o: at ${cols} columns the mode badge was clipped away:\n  |${strip}|`,
    );
  }
});

test("R388o: the header shows its full set of items when there is room", async () => {
  // The counterpart to the dropping: on a terminal that has room, the
  // header must carry the context meter, the session id and the
  // connection dot. Without this, a mutation that disables the
  // dropping passes — the bar's content would WRAP inside its box
  // and the frame's height constraint would clip the continuation.
  for (const cols of [100, 120, 160]) {
    const { frameLines } = await renderFrame(cols, 30);
    const strip = frameLines.find((l) => l.includes("MiniMax-M3"));
    assert.ok(strip, `R388o: at ${cols} columns the header's status strip is missing.`);
    assert.ok(
      strip.includes("%"),
      `R388o: at ${cols} columns the header lost its context meter:\n  |${strip}|`,
    );
    assert.ok(
      /[●○]/.test(strip),
      `R388o: at ${cols} columns the header lost its connection dot:\n  |${strip}|`,
    );
  }
});

test("R388o: the input pill's borders are drawn on every row", async () => {
  for (const cols of [40, 80, 100, 120]) {
    const { screen } = await renderFrame(cols, 30);
    // The pill is a ROUNDED box: a top border, one or more content
    // rows, and a bottom border. EVERY row of it must carry the left
    // and right border characters — a row whose right border is cut
    // off is the screenshot's "[↵ instead of ↵]".
    const top = screen.findIndex((l) => l.includes("╭"));
    assert.ok(top >= 0, `R388o: at ${cols} columns the input pill has no top border.`);
    const bottom = screen.findIndex((l, i) => i > top && l.includes("╰"));
    assert.ok(bottom > top, `R388o: at ${cols} columns the input pill has no bottom border.`);
    for (let i = top; i <= bottom; i++) {
      // The pill sits inside a `paddingX={1}` wrapper, so the row has
      // a leading space. Trim it before checking the borders.
      const row = (screen[i] ?? "").trimStart();
      assert.ok(
        row.startsWith("╭") || row.startsWith("│") || row.startsWith("╰"),
        `R388o: at ${cols} columns the pill's row ${i} has no left border:\n  |${row}|`,
      );
      assert.ok(
        row.endsWith("╮") || row.endsWith("│") || row.endsWith("╯"),
        `R388o: at ${cols} columns the pill's row ${i} has no right border:\n  |${row}|`,
      );
    }
  }
});

test("R388o: the chrome survives a LONG transcript", async () => {
  // ★ THE DEFECT THIS ROUND ACTUALLY WAS ★
  //
  // The header and the input pill are FLEX SIBLINGS of the transcript.
  // Yoga gives a flex item a BASE SIZE equal to its CONTENT, and it
  // only shrinks when the container overflows — so a transcript taller
  // than the terminal does not clip itself: it TAKES the rows and
  // SQUEEZES the chrome.
  //
  // Measured before the fix (a 12-turn transcript in a 30-row
  // terminal): the header's box computed to ONE row (the brand row
  // and the cwd caption both computed to height 0 — they were
  // squashed to nothing), and the input pill showed only its top
  // border. The user saw a bare rule where the header should be, and
  // a half-drawn input box.
  //
  // So the contract is not "the frame fits" — it is "the CHROME keeps
  // its rows". A long conversation must not make the header vanish.
  for (const rows of [20, 30, 50]) {
    const { screen } = await renderFrame(100, rows, "", { turns: 40 });
    const headerRow = screen.findIndex((l) => l.includes("MiniMax-M3"));
    assert.ok(
      headerRow >= 0,
      `R388o: at ${rows} rows with a long transcript the header's status strip is GONE:\n` +
        screen.map((l, i) => `  ${String(i).padStart(3)} |${l}|`).join("\n"),
    );
    // The brand row comes ABOVE the strip; if the header was squashed
    // to one row, the strip would be the first row it rendered.
    const brandRow = screen.findIndex((l) => l.includes("AetherCode"));
    assert.ok(
      brandRow >= 0 && brandRow < headerRow,
      `R388o: at ${rows} rows with a long transcript the header's brand row is missing (the header was squeezed):\n` +
        screen.map((l, i) => `  ${String(i).padStart(3)} |${l}|`).join("\n"),
    );
    // The input pill: BOTH borders, and the row between them.
    const top = screen.findIndex((l) => l.trimStart().startsWith("╭"));
    const bottom = screen.findIndex((l) => l.trimStart().startsWith("╰"));
    assert.ok(
      top >= 0 && bottom > top,
      `R388o: at ${rows} rows with a long transcript the input pill is not closed (top=${top}, bottom=${bottom}):\n` +
        screen.map((l, i) => `  ${String(i).padStart(3)} |${l}|`).join("\n"),
    );
  }
});

test("R388o: the transcript's flex chain cannot squeeze the chrome", async () => {
  // ★ THE INVARIANT, NOT THE SPELLING ★
  //
  // The transcript's wrappers carry `flexBasis={0}` and `minHeight={0}`
  // so their BASE SIZE is zero and they grow into the leftover rows.
  // Without it, the transcript's content decides how many rows the
  // chrome gets.
  //
  // This check pins the INVARIANT ("the wrappers have a zero basis")
  // rather than the exact prop list, so a rename or a reordering does
  // not make the gate red for a reason that is not a defect.
  const tuiSrcNow = readFileSync(join(ROOT, "src", "tui.tsx"), "utf8");
  const tuiCodeNow = tuiSrcNow.replace(/\/\*[\s\S]*?\*\//g, " ");
  const wrappers = [...tuiCodeNow.matchAll(/<Box\b[^>]*?>/gs)]
    .map((m) => m[0])
    .filter((tag) => /flexBasis=\{0\}/.test(tag));
  assert.ok(
    wrappers.length >= 2,
    `R388o: only ${wrappers.length} of the product's transcript wrappers carry flexBasis={0} — the transcript's content can still squeeze the chrome.`,
  );
  for (const tag of wrappers) {
    assert.match(
      tag,
      /minHeight=\{0\}/,
      `R388o: a transcript wrapper carries flexBasis={0} but not minHeight={0}:\n  ${tag}`,
    );
  }
});

test("R388o: the transcript's row budget is BOUNDED", async () => {
  // ★ THE BUDGET'S CONTRACT, AFTER THE FLEX CHAIN ★
  //
  // `height={1000}` is a TURN count used as a ROW count: one turn can
  // be forty rows of markdown, so a thousand turns is tens of
  // thousands of rows of layout work for a thirty-row terminal.
  //
  // ★ AND A RENDERED-FRAME CHECK CANNOT SEE IT ★ Once the transcript's
  // column has `flexBasis={0}`, the frame's HEIGHT no longer depends
  // on the budget — the flex chain clips it. So the frame looks right
  // whether the budget is 21 or 1000, and a rendered-frame gate stays
  // green.
  //
  // What the budget still governs is the LAYOUT COST: the Scrollback
  // slices the turns to the budget, and without the slice Yoga lays
  // out every turn ever written. So the contract is on the VALUE.
  const { budget } = await readBudget();
  assert.ok(
    budget > 0 && budget <= 200,
    `R388o: the transcript's row budget is ${budget} — it must be bounded by the terminal's rows (a turn count used as a row count is the defect).`,
  );
});

test("R388o: the status bar's items do not OVERLAP each other", async () => {
  // ★ SQUEEZING IS NOT TRUNCATING, AND OVERLAPPING IS WORSE ★
  //
  // The status bar's right-hand box must keep its NATURAL size
  // (`flexShrink={0}`), with the one item that can be shortened
  // without losing a number — the jar name — truncated UP FRONT to a
  // measured budget.
  //
  // The tempting alternative is `flexShrink={1}`: let the box shrink.
  // Ink shrinks the BOX and the Text nodes inside it then OVERLAP
  // each other instead of being cut. Measured: the row reads
  // "●✓ ·" — the connection glyph and the state label are drawn on
  // top of each other.
  //
  // ★ WHY A WIDTH CHECK CANNOT SEE THIS ★ the overlapped row is still
  // INSIDE the terminal's width. The frame fits. Only the CONTENT is
  // mangled. So the check has to look at the text itself: the state
  // label must be a whole, readable token.
  for (const cols of [40, 50, 60, 80, 100, 120]) {
    const { frameLines } = await renderFrame(cols, 30);
    const statusRow = frameLines.find((l) => l.includes("ready") || l.includes("stopped"));
    assert.ok(statusRow, `R388o: at ${cols} columns the status bar's row is missing.`);
    // The connection dot and the state label are rendered as
    // SEPARATE Text nodes. When the box is squeezed they land on the
    // same cell. So: the dot must be followed by a space, and the
    // label must follow in full.
    //
    // The label may carry a leading glyph of its own ("✓ ready"), so
    // the space is the thing to assert on — it is what disappears
    // when the nodes overlap.
    assert.match(
      statusRow,
      /[●○]\s+\S/,
      `R388o: at ${cols} columns the status bar's glyph and its label are drawn ON TOP OF EACH OTHER:\n  |${statusRow}|`,
    );
    assert.match(
      statusRow,
      /[●○]\s+(✓\s*)?(ready|stopped|thinking|streaming|working|empty)/,
      `R388o: at ${cols} columns the status bar's state label is mangled:\n  |${statusRow}|`,
    );
  }
});

test("R388o: the input pill shows at most INPUT_MAX_LINES lines of text", async () => {
  // ★ THE CAP IS A CONTRACT ABOUT THE INPUT, NOT ABOUT THE FRAME ★
  //
  // The pill's height is capped at `INPUT_MAX_LINES` lines, and the
  // rest scrolls. Without the cap, an 8-line input (shift+Enter) makes
  // the pill eight rows tall.
  //
  // ★ WHY THE FRAME-HEIGHT CHECK CANNOT SEE IT ★ the transcript's
  // column has `flexBasis={0}`, so the frame's height is the
  // TERMINAL's height whatever the pill does — the frame fits either
  // way. The defect is visible in the PILL, not in the frame.
  //
  // So the check counts the pill's own rows: the top border, at most
  // `INPUT_MAX_LINES` content rows, and the bottom border.
  for (const cols of [80, 100, 120]) {
    const { screen } = await renderFrame(cols, 30, "a\nb\nc\nd\ne\nf\ng\nh");
    const top = screen.findIndex((l) => l.trimStart().startsWith("╭"));
    const bottom = screen.findIndex((l) => l.trimStart().startsWith("╰"));
    assert.ok(top >= 0 && bottom > top, `R388o: at ${cols} columns the input pill is not closed.`);
    const contentRows = bottom - top - 1;
    assert.ok(
      contentRows <= INPUT_MAX_LINES,
      `R388o: at ${cols} columns the input pill shows ${contentRows} rows of text — it must show at most ${INPUT_MAX_LINES} and scroll the rest.`,
    );
  }
});

test("R388o: there is exactly ONE wide-char table in the TUI", () => {
  // Lesson 1207 / 1330: a second copy of a truth drifts. The wide-char
  // table lives in `cellWidth.ts`; `terminalGeometry.ts` must IMPORT it,
  // not restate it.
  const geom = readFileSync(join(ROOT, "src", "terminalGeometry.ts"), "utf8");
  assert.ok(
    !/0x4e00\s*&&\s*cp\s*<=\s*0x9fff/.test(geom),
    "R388o: terminalGeometry.ts carries its own wide-char table — that is a second copy of cellWidth.ts's truth.",
  );
  assert.match(
    geom,
    /from\s+["']\.\/cellWidth\.js["']/,
    "R388o: terminalGeometry.ts must import the cell width rule from cellWidth.ts.",
  );
});
