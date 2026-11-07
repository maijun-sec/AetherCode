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

// R388m — the permission DEADLINE, and ONE width rule.
//
// ## Three contracts, three failure modes
//
// (1) THE COUNTDOWN MUST ACTUALLY DENY.
//
// A card that displays "auto-denies in 1:12" and then sits there for
// five more minutes while the daemon's backstop timer runs is a
// DISPLAY, not a contract. The client has to send the deny itself.
//
// (2) THE DEADLINE MUST COME FROM THE DAEMON.
//
// A client that guesses the timeout either (a) counts down to zero
// while the daemon is still willing to wait — and denies a call the
// user was about to approve — or (b) keeps waiting after the daemon
// already denied, so the user watches a prompt that has been resolved.
// The daemon publishes `expiresAtMs`; the client renders THAT.
//
// (3) "NOBODY CAN APPROVE" MUST BE ONE SENTENCE.
//
// Three surfaces deny for this reason (the headless `--print` driver,
// the line renderer when stdout is not a TTY, and the daemon when no
// client is connected). Before R388m each wrote its own sentence, and
// NONE of them told the model the two things it needs: that nobody
// can approve, and that it must not retry. The model's default
// reaction to a denial is to try again — and a retry against a
// permission wall fails identically, forever.
//
// Plus:
//
// (4) ONE WIDTH RULE.
//
// The product had THREE width rules: the line input counted CELLS,
// the panel helpers counted CHARACTERS, and the markdown/prompt
// tables counted CHARACTERS. A CJK label is twice as wide as its
// character count, so a character-counted column is half a cell too
// narrow — the row runs past the frame's right border, which is
// exactly the "ragged / broken dialog box" the user sees.
//
// → **Lesson 1339 — a column width computed from `.length` is a width
// in the wrong unit.** A terminal is a grid of CELLS.
//
// → **Lesson 1340 — a countdown that only DISPLAYS is not a
// contract.**
//
// → **Lesson 1341 — "nobody can approve" is a sentence, and a
// sentence with three copies is three sentences.**

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

/**
 * The block that starts at `open` and ends at its matching close brace.
 *
 * ★ ANCHOR ON THE CONSTRUCT, NOT ON PROXIMITY. ★
 *
 * A regex like `/expiryTimersRef[\s\S]{0,2000}?"permissionResponse"/`
 * binds the two strings by DISTANCE — it goes red when someone
 * reflows the file, and it stays green when someone moves the deny
 * into an unrelated function 1500 characters away. (R388k Lesson 1321:
 * a contract check must be anchored on the CALL, not on a string that
 * also appears in prose.)
 *
 * So: find the construct, take ITS block, and assert on the block.
 */
function blockAfter(src, openMarker, open = "{") {
  const start = src.indexOf(openMarker);
  assert.ok(start > 0, `marker not found: ${openMarker}`);
  const braceStart = src.indexOf(open, start + openMarker.length - 1);
  assert.ok(braceStart > 0, `no opening brace after: ${openMarker}`);
  let depth = 0;
  for (let i = braceStart; i < src.length; i++) {
    const ch = src[i];
    if (ch === open) depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return src.slice(braceStart, i + 1);
    }
  }
  throw new Error(`unbalanced braces after: ${openMarker}`);
}

/* ------------------------------------------------------------------ *
 * Load the REAL modules.
 *
 * The gate must not re-implement the logic — a second implementation
 * is a second truth, and it is exactly what drifts (R380's subject).
 * ------------------------------------------------------------------ */
const loadTs = async (name) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), `r388m-${name}-`));
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

const deadline = await loadTs("permissionDeadline");
const cell = await loadTs("cellWidth");

/* ================================================================== *
 * (4) ONE WIDTH RULE
 * ================================================================== */

test("R388m: cellWidth counts terminal CELLS, not characters", () => {
  // ASCII: one cell per character
  assert.equal(cell.cellWidth("abc"), 3);
  // CJK: two cells per character
  assert.equal(cell.cellWidth("名字"), 4);
  // mixed
  assert.equal(cell.cellWidth("a名b"), 4);
  // emoji (surrogate pair) counts as one wide glyph
  assert.equal(cell.cellWidth("😀"), 2);
  // ANSI is zero-width
  assert.equal(cell.cellWidth("\u001b[31mabc\u001b[0m"), 3);
});

test("R388m: fitCells truncates by CELLS so a CJK row fits the frame", () => {
  // 10 CJK chars = 20 cells. A 10-CELL budget must fit ~5 chars.
  const fitted = cell.fitCells("一二三四五六七八九十", 10);
  assert.ok(
    cell.cellWidth(fitted) <= 10,
    `fitCells must not exceed the budget in cells (got ${cell.cellWidth(fitted)})`,
  );
  // the truncation must be VISIBLE — the ellipsis is somewhere in the
  // row (it is followed by padding, so it is not the LAST character)
  assert.ok(
    fitted.includes("…"),
    `a truncated row must show the ellipsis, got ${JSON.stringify(fitted)}`,
  );
  // and the visible text must be SHORTER than the original — a
  // character-count truncation would have kept 9 of 10 chars (18 cells
  // worth) and overflowed the frame
  assert.ok(fitted.length < "一二三四五六七八九十".length, "the row must actually be truncated");
});

test("R388m: fitCells PADS a short row to the full width (no ragged edge)", () => {
  const fitted = cell.fitCells("名字", 12);
  assert.equal(cell.cellWidth(fitted), 12, "a short row must be padded to the full width");
});

test("R388m: padCells pads by CELLS — the column contract holds for CJK", () => {
  // "名字" is 4 cells. padEnd(10) would give 8 characters = 12 cells
  // (a column 2 cells too wide). padCells must give exactly 10 cells.
  const padded = cell.padCells("名字", 10);
  assert.equal(cell.cellWidth(padded), 10, "padCells must pad to the CELL width");
  // the character count is the thing that differs
  assert.ok(
    padded.length === 8,
    "the character count is smaller than the cell count — that is the point",
  );
});

test("R388m: padCells never TRUNCATES (it would hide data)", () => {
  const padded = cell.padCells("abcdefghij", 4);
  assert.equal(padded, "abcdefghij", "padCells must not truncate");
});

test("R388m: maxCells measures the widest of several strings in CELLS", () => {
  assert.equal(cell.maxCells(["a", "名字", "abc"]), 4);
});

test("R388m: the panel helpers measure in CELLS, not characters", () => {
  // The panel body MUST delegate to the shared rule. A private copy of
  // the width logic is a second truth (R380's subject).
  const panelBody = readCode("src/components/PanelBody.tsx");
  assert.match(panelBody, /from "\.\.\/cellWidth\.js"/, "PanelBody must import the ONE width rule");
  assert.match(panelBody, /fitRow[\s\S]{0,200}?fitCells\(/, "fitRow must delegate to fitCells");
  // and it must NOT have kept a private width computation
  assert.doesNotMatch(
    panelBody,
    /chars\.length\s*<=\s*w/,
    "fitRow must not measure by character count any more",
  );
  assert.doesNotMatch(
    panelBody,
    /chars\.slice\(0,\s*w\s*-\s*1\)/,
    "fitRow must not truncate by character count any more",
  );
});

test("R388m: the line input has ONE width rule, not a private copy", () => {
  const lineInput = readCode("src/lineInput.ts");
  assert.match(
    lineInput,
    /from "\.\/cellWidth\.js"/,
    "lineInput must import the shared width rule",
  );
  // a private `function cellWidth` would be the second truth
  assert.doesNotMatch(
    lineInput,
    /function cellWidth\s*\(/,
    "lineInput must not define its own cellWidth — that is a second rule",
  );
  assert.doesNotMatch(
    lineInput,
    /function isWide\s*\(/,
    "lineInput must not define its own isWide — that is a second rule",
  );
});

test("R388m: the markdown table lays out COLUMNS in cells", () => {
  const md = readCode("src/components/Markdown.tsx");
  assert.match(md, /from "\.\.\/cellWidth\.js"/, "Markdown must import the ONE width rule");
  // the column width must be computed with cellWidth, not .length
  assert.match(md, /cellWidth\(cell\)/, "the column width must be measured in cells");
  assert.match(md, /padCells\(cell,\s*w\)/, "the cell must be padded in cells");
});

test("R388m: the system-prompt table lays out COLUMNS in cells", () => {
  const fmt = readCode("src/prompt-formatter.ts");
  assert.match(fmt, /from "\.\/cellWidth\.js"/, "prompt-formatter must import the ONE width rule");
  assert.match(fmt, /maxCells\(/, "the column width must be measured in cells");
  // the OLD character-count gutter is the defect
  assert.doesNotMatch(
    fmt,
    /label\.length\s*>\s*gutter/,
    "the gutter must not be computed from a character count",
  );
});

test("R388m: NO surface pads a user-controlled string with padEnd any more", () => {
  // The defect class: `x.padEnd(n)` where x is a name the USER typed.
  // The list below is the surfaces R388m converted. A NEW one may be
  // added — but then it must use padCells.
  const surfaces = [
    "src/tui.tsx",
    "src/line.ts",
    "src/commands.ts",
    "src/ac-tui.ts",
    "src/components/HelpOverlay.tsx",
    "src/components/EffortPicker.tsx",
    "src/components/ThemePicker.tsx",
    "src/components/models/ModelSelector.tsx",
    "src/components/MemoryPanel.tsx",
    "src/components/UpdatePanel.tsx",
    "src/components/Welcome.tsx",
    "src/components/WelcomeBanner.tsx",
    "src/components/session/SessionDetailsPanel.tsx",
  ];
  for (const rel of surfaces) {
    const code = readCode(rel);
    const offenders = [...code.matchAll(/\.padEnd\(/g)];
    assert.equal(
      offenders.length,
      0,
      `${rel} still uses .padEnd() — a character-count pad on a user-controlled string ` +
        `drifts the next column. Use padCells(x, n) from src/cellWidth.ts.`,
    );
  }
});

test("R388m: the autocomplete dropdown truncates in CELLS", () => {
  const ac = readCode("src/components/Autocomplete.tsx");
  assert.match(ac, /from "\.\.\/cellWidth\.js"/, "Autocomplete must import the ONE width rule");
  // the OLD character-count slice is the defect: it kept HALF the
  // visible text of a CJK path and still overflowed the row.
  assert.doesNotMatch(
    ac,
    /s\.label\.length\s*>\s*width/,
    "the label must not be truncated by character count",
  );
  assert.match(ac, /fitCells\(s\.label/, "the label must be truncated in cells");
});

test("R388m: the autocomplete dropdown width follows the TERMINAL, not a constant", () => {
  const inputBox = readCode("src/components/InputBox.tsx");
  assert.doesNotMatch(
    inputBox,
    /CompletionDropdown suggestions=\{suggestions\} width=\{48\}/,
    "the dropdown width must not be a hard-coded constant — it must follow the terminal",
  );
  assert.match(
    inputBox,
    /process\.stdout\.columns/,
    "the dropdown width must be derived from the terminal width",
  );
});

/* ================================================================== *
 * (1) (2) THE COUNTDOWN
 * ================================================================== */

test("R388m: the deadline is read from the DAEMON's payload, not guessed", () => {
  const now = 1_700_000_000_000;
  const d = deadline.readDeadline({ timeoutMs: 120_000, expiresAtMs: now + 120_000 });
  assert.ok(d, "a payload with a deadline must yield one");
  assert.equal(d.expiresAtMs, now + 120_000);
  assert.equal(d.timeoutMs, 120_000);
});

test("R388m: NO deadline means NO countdown (never a guessed one)", () => {
  // A guessed deadline can deny a call the user was about to approve.
  assert.equal(deadline.readDeadline(null), null);
  assert.equal(deadline.readDeadline({}), null);
  assert.equal(deadline.readDeadline({ expiresAtMs: 0 }), null);
  assert.equal(deadline.readDeadline({ expiresAtMs: "soon" }), null);
  assert.equal(deadline.readDeadline({ expiresAtMs: -5 }), null);
});

test("R388m: secondsLeft FLOORS (a card must not show time that is not there)", () => {
  const now = 1_000_000;
  const d = { expiresAtMs: now + 1500, timeoutMs: 120_000 };
  assert.equal(deadline.secondsLeft(d, now), 1, "1.5s left must read as 1s, not 2s");
  assert.equal(deadline.secondsLeft(d, now + 1500), 0, "at the deadline it is 0");
  assert.equal(deadline.secondsLeft(d, now + 9999), 0, "past the deadline it is 0, never negative");
});

test("R388m: the countdown line names the CONSEQUENCE", () => {
  const now = 1_000_000;
  const d = { expiresAtMs: now + 125_000, timeoutMs: 120_000 };
  const line = deadline.countdownLine(d, now);
  assert.ok(line, "a live deadline must produce a line");
  assert.match(line, /auto-denies in 2:05/, `unexpected countdown line: ${line}`);
  // the wording must name what happens — a bare number is not a promise
  assert.match(line, /auto-denies/, "the line must say what happens when it reaches zero");
});

test("R388m: an expired deadline says so plainly", () => {
  const now = 1_000_000;
  const d = { expiresAtMs: now - 1, timeoutMs: 120_000 };
  assert.match(deadline.countdownLine(d, now), /no response/);
});

test("R388m: no deadline means no countdown line", () => {
  assert.equal(deadline.countdownLine(null, 1_000_000), null);
});

test("R388m: formatCountdown reads as M:SS", () => {
  assert.equal(deadline.formatCountdown(0), "0:00");
  assert.equal(deadline.formatCountdown(5), "0:05");
  assert.equal(deadline.formatCountdown(65), "1:05");
  assert.equal(deadline.formatCountdown(120), "2:00");
});

test("R388m: the DAEMON publishes the deadline on the permission_request", () => {
  const java = readFileSync(
    join(
      root,
      "..",
      "aethercode",
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "methods",
      "AetherCodeMethods.java",
    ),
    "utf8",
  );
  // the payload must carry BOTH the window and the instant
  assert.match(java, /payload\.put\("timeoutMs"/, "the ask must publish its timeout");
  assert.match(
    java,
    /payload\.put\("expiresAtMs"/,
    "the ask must publish the expiry instant — a window alone cannot be counted down",
  );
  assert.match(
    java,
    /expiresAtMs.*System\.currentTimeMillis\(\)\s*\+\s*timeoutMs/,
    "the expiry must be computed from the SAME window it publishes",
  );
});

test("R388m: the prompter passes its OWN timeout to the ask", () => {
  const java = readFileSync(
    join(
      root,
      "..",
      "aethercode",
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "permissions",
      "JsonRpcPermissionPrompter.java",
    ),
    "utf8",
  );
  // ★ The prompter must call askPermissionWithDeadline, NOT a
  // six-argument askPermission overload. ★
  //
  // The first version of R388m added `askPermission(..., long timeoutMs)`
  // and called it directly. That silently broke every test stub that
  // overrides the FIVE-argument askPermission: the override was never
  // reached, the ask went unanswered, and the test observed a TIMEOUT
  // instead of the contract. (Lesson 1350: an OVERLOAD is a wiring
  // difference too.)
  const call = java.slice(java.indexOf("methods.askPermission"));
  const end = call.indexOf(");");
  const args = call.slice(0, end);
  assert.match(
    args,
    /timeoutMs/,
    "the prompter must publish its timeoutMs — otherwise the client counts down on a different clock",
  );
  assert.match(
    args,
    /askPermissionWithDeadline\(/,
    "the prompter must ask through askPermissionWithDeadline — calling a " +
      "different OVERLOAD of askPermission silently bypasses every stub " +
      "that overrides the five-argument one",
  );
});

test("R388m: the ask is issued by the ONE method every stub overrides", () => {
  const java = readFileSync(
    join(
      root,
      "..",
      "aethercode",
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "methods",
      "AetherCodeMethods.java",
    ),
    "utf8",
  );
  // ★ There must be EXACTLY ONE method that issues the ask. ★
  //
  // The five-argument `askPermission` is the one every stub and every
  // override hooks. If a second overload also issues an ask, a caller
  // that picks the wrong one bypasses all of them.
  const issuers = [
    ...java.matchAll(/public CompletableFuture<PermissionDecision> askPermission\w*\(/g),
  ];
  assert.equal(
    issuers.length,
    2,
    "there must be exactly two askPermission entry points — the issuing " +
      "method and the deadline wrapper. Found: " +
      issuers.map((m) => m[0]).join(", "),
  );
  // the deadline must travel through a field that the ask CONSUMES, so
  // it can never leak into a later ask
  assert.match(
    java,
    /pendingDeadlineMs\.getAndSet\(0L\)/,
    "the ask must READ-AND-CLEAR the deadline — a field that is only set " +
      "would leak into the next ask",
  );
});

test("R388m: the CLIENT sends the deny when the countdown expires", () => {
  const tui = readCode("src/tui.tsx");
  // ★ Anchor on the CONSTRUCT: the `if (deadline) { ... }` block that
  // arms the timer. Not "a string that happens to be nearby".
  const armBlock = blockAfter(tui, "if (deadline)");
  assert.match(armBlock, /setTimeout\(/, "the expiry must be driven by a timer");
  assert.match(armBlock, /"permissionResponse"/, "the expiry timer must send a permissionResponse");
  assert.match(
    armBlock,
    /"deny"/,
    "the expiry timer must deny — an unanswered ask is denied, not left hanging",
  );
  // the timer must be REGISTERED so answering can cancel it
  assert.match(
    armBlock,
    /permissionExpiryTimers\.set\(/,
    "the timer must be registered so answering can cancel it",
  );
});

test("R388m: answering the ask CANCELS its countdown timer", () => {
  const tui = readCode("src/tui.tsx");
  // ★ Anchor on replyPermission's OWN body — not on a nearby
  // clearTimeout somewhere else in the file.
  const reply = blockAfter(tui, "function replyPermission(");
  assert.match(
    reply,
    /cancelPermissionExpiry\(/,
    "answering must cancel the timer — one ask, one timer",
  );
  // and the canceller really clears it
  const canceller = blockAfter(tui, "function cancelPermissionExpiry(");
  assert.match(canceller, /clearTimeout\(/, "the canceller must clear the timeout");
  assert.match(canceller, /\.delete\(/, "the cancelled timer must be dropped");
});

test("R388m: the countdown timer only denies the ask that is STILL pending", () => {
  const tui = readCode("src/tui.tsx");
  const armBlock = blockAfter(tui, "if (deadline)");
  assert.match(
    armBlock,
    /permissionAsk\?\.requestId\s*!==\s*ask\.requestId/,
    "the expiry must be guarded by the ask's identity",
  );
});

test("R388m: the timer registry is MODULE-level, not threaded through every call", () => {
  const tui = readCode("src/tui.tsx");
  // ★ A 7th positional parameter on replyPermission turned every call
  // site into nine lines AND broke the R388l gate's anchor on
  // `replyPermission(client, ask, "allow"`. The registry has exactly one
  // owner (the permission flow), so a module level is the honest scope.
  assert.match(
    tui,
    /const permissionExpiryTimers = new Map/,
    "the timer registry must be module-level",
  );
  assert.doesNotMatch(
    tui,
    /expiryTimersRef/,
    "the timer registry must not be threaded through the call sites as a parameter",
  );
  // and the call sites keep their original shape — a decision call is
  // one line, so the R388l gate's anchor still resolves.
  assert.match(
    tui,
    /replyPermission\(client, ask, "allow", dispatch\);/,
    "the decision call must keep its one-line shape",
  );
});

test("R388m: the permission card renders the countdown", () => {
  const scrollback = readCode("src/components/Scrollback.tsx");
  assert.match(
    scrollback,
    /from "\.\.\/permissionDeadline\.js"/,
    "the card must use the shared countdown helper",
  );
  assert.match(scrollback, /countdownLine\(/, "the card must render the countdown line");
  // and the card must actually TICK — a countdown that only re-renders
  // on some other event is a frozen number
  assert.match(scrollback, /setInterval\([\s\S]{0,200}?setNow/, "the card must tick on a timer");
});

test("R388m: the DEMO's ask carries a deadline (so the feature is demonstrable)", () => {
  const tui = readCode("src/tui.tsx");
  // The demo dispatches a SYNTHETIC ask. Without a deadline the card
  // shows no countdown — honest (never guess a deadline), but the demo
  // then does not demonstrate the feature it exists to show.
  const demo = tui.slice(tui.indexOf('"demo-perm-1"'));
  assert.match(
    demo.slice(0, 800),
    /expiresAtMs/,
    "the demo's ask must carry a deadline, or the demo shows a card with no countdown",
  );
  assert.match(
    demo.slice(0, 800),
    /PERMISSION_COUNTDOWN_MS/,
    "the demo's deadline must come from the ONE countdown constant",
  );
  // and the demo must not cut the countdown short
  assert.match(
    tui,
    /waitForPermissionAnswer\(PERMISSION_COUNTDOWN_MS/,
    "the demo must wait for the countdown window — a shorter wait truncates " +
      "the feature the demo is demonstrating",
  );
});

/* ================================================================== *
 * (3) "NOBODY CAN APPROVE" — ONE SENTENCE
 * ================================================================== */

test("R388m: the deny reason says NOBODY can approve and says DO NOT retry", () => {
  const reason = deadline.nobodyCanApproveReason("file_write", "headless --print mode");
  assert.match(reason, /no one can approve/, "the reason must say nobody can approve");
  assert.match(reason, /DO NOT retry/, "the reason must tell the model not to retry");
  assert.match(reason, /file_write/, "the reason must name the tool");
  // and it must offer a way forward — otherwise the model has nothing
  // to do but retry
  assert.match(reason, /tell the user|different tool/, "the reason must offer a way forward");
});

test("R388m: the headless driver uses the ONE contract wording", () => {
  const ac = readCode("src/ac-tui.ts");
  assert.match(
    ac,
    /from "\.\/permissionDeadline\.js"/,
    "the headless driver must import the contract",
  );
  assert.match(
    ac,
    /reason:\s*nobodyCanApproveReason\(/,
    "the headless deny must use the contract's reason",
  );
  // the OLD reason is the defect: it never said "do not retry"
  assert.doesNotMatch(
    ac,
    /"headless --print mode: stdin not a TTY, defaulting to deny"/,
    "the old headless reason is the defect this round removes",
  );
});

test("R388m: the line renderer uses the ONE contract wording", () => {
  const line = readCode("src/line.ts");
  assert.match(
    line,
    /from "\.\/permissionDeadline\.js"/,
    "the line renderer must import the contract",
  );
  assert.match(
    line,
    /nobodyCanApproveReason\(/,
    "the line renderer's non-TTY deny must use the contract's reason",
  );
  // the OLD shape is the defect: a bare `{ decision: "deny" }` with no
  // reason at all, so the model saw only "line mode picked deny".
  assert.doesNotMatch(
    line,
    /if \(!process\.stdout\.isTTY\) return \{ decision: "deny" \};/,
    "the non-TTY deny must carry a reason — a bare deny tells the model nothing",
  );
});

test("R388m: the DAEMON's no-client deny uses the contract wording", () => {
  const java = readFileSync(
    join(
      root,
      "..",
      "aethercode",
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "permissions",
      "JsonRpcPermissionPrompter.java",
    ),
    "utf8",
  );
  assert.match(java, /no one can approve/, "the daemon's no-client deny must say so");
  assert.match(java, /DO NOT retry/, "the daemon's no-client deny must say do not retry");
  // the OLD reason is the defect
  assert.doesNotMatch(
    java,
    /PermissionResult\.Deny\.of\("no JSON-RPC connection"\)/,
    "the old one-word reason is the defect this round removes",
  );
});

test("R388m: the reply carries the reason through to the wire", () => {
  const comment = readCode("src/permissionComment.ts");
  assert.match(
    comment,
    /reason\?:\s*string/,
    "permissionResponseParams must accept an explicit reason",
  );
  // and the explicit reason must WIN over the default audit trail
  assert.match(
    comment,
    /reason:\s*reason\s*&&\s*reason\.trim\(\)/,
    "an explicit reason must override the default",
  );
  // the default must survive for every other caller
  assert.match(
    comment,
    /\$\{renderer\} picked \$\{decision\}/,
    "the default reason must stay the audit trail",
  );
});

test("R388m: the line renderer PASSES the pick's reason to the wire", () => {
  const line = readCode("src/line.ts");
  // ★ Anchor on the CALL — the params object of the
  // permissionResponseParams invocation — not on a nearby string.
  const callStart = line.indexOf("permissionResponseParams(");
  assert.ok(callStart > 0, "the line renderer must call permissionResponseParams");
  const call = line.slice(callStart, callStart + 500);
  assert.match(
    call,
    /picked\.reason/,
    "the pick's reason must reach permissionResponseParams — otherwise the model " +
      "sees only 'line mode picked deny' and cannot tell whether a retry could work",
  );
});
