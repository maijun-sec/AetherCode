// R365 — the panel registry must agree with the renderers.
//
// R362 introduced `src/panelIntents.ts` as "the single source of truth"
// for what each slash command opens and on which renderer. Its own
// tests checked that every emitted sentinel had an entry, that entries
// were unique, that component files existed, and that any
// `unavailable` row carried a `gapNote`.
//
// **None of those tests asked whether the declared status was TRUE.**
// So ten rows sat at `ink: 'unavailable', line: 'unavailable'` and
// the suite stayed green — while eight of them had in fact been wired
// by the very round that wrote the table. A status field that can be
// false without failing anything is worse than no status field: the
// next reader trusts it.
//
// R364 found one of those eight the hard way (`/cwd-pick` was marked
// unavailable, and the real story was a JSON-RPC/Tauri name mix-up).
// R365 removes the class.
//
// ## The fix: DERIVE, do not declare
//
// These tests recompute both columns from the renderer sources:
//
//   ink  = `PANEL_BY_INTENT[id]` exists in tui.tsx AND there is a
//          matching `case "<panelId>":` in its panel switch
//   line = every one of the intent's sentinels has a
//          `case "__SENTINEL__":` arm in line.ts
//
// and fail if the declared status disagrees. The table can no longer
// drift: changing a renderer without updating the registry breaks
// this gate, and vice versa.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r365-panel-truth");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const r = spawnSync(`"${tscBin}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "panelIntents.ts")}"`,
], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "out", "panelIntents.js")).href);
const { PANEL_INTENTS, ADVERTISED_INTENTS, helpByGroup, intentForCommand } = mod;

const tuiCode = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineCode = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const commandsCode = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");

// --- derive the truth from the renderers -----------------------------------

/** intent id -> PanelId, parsed out of tui.tsx's PANEL_BY_INTENT literal. */
function parsePanelByIntent(src) {
  const start = src.indexOf("PANEL_BY_INTENT");
  assert.ok(start > -1, "PANEL_BY_INTENT not found in tui.tsx — re-anchor this test");
  const open = src.indexOf("{", start);
  const close = src.indexOf("};", open);
  const body = src.slice(open, close);
  const map = new Map();
  for (const m of body.matchAll(/["']([a-z-]+)["']\s*:\s*["']([a-z-]+)["']/g)) {
    map.set(m[1], m[2]);
  }
  return map;
}

/** Every `case "<label>":` label in a source file. */
function caseLabels(src) {
  return new Set([...src.matchAll(/case\s+"([a-zA-Z_-]+)":/g)].map((m) => m[1]));
}

/** Every `case "__SENTINEL__":` arm in a source file. */
function sentinelCases(src) {
  return new Set([...src.matchAll(/case\s+"(__(?:[A-Z_0-9]+)__)"\s*:/g)].map((m) => m[1]));
}

const PANEL_BY_INTENT = parsePanelByIntent(tuiCode);
const INK_CASES = caseLabels(tuiCode);
const LINE_SENTINELS = sentinelCases(lineCode);

/** ink: the sentinel resolves to a PanelId AND that PanelId renders. */
function deriveInk(intent) {
  const panelId = PANEL_BY_INTENT.get(intent.id);
  if (!panelId) return { mounted: false, why: `no PANEL_BY_INTENT entry for "${intent.id}"` };
  if (!INK_CASES.has(panelId)) {
    return { mounted: false, why: `PANEL_BY_INTENT maps to "${panelId}" but tui.tsx has no case "${panelId}":` };
  }
  return { mounted: true, why: `PANEL_BY_INTENT["${intent.id}"] = "${panelId}" + case "${panelId}":` };
}

/** line: EVERY sentinel of the intent has an arm. */
function deriveLine(intent) {
  const missing = intent.sentinels.filter((s) => !LINE_SENTINELS.has(s));
  if (missing.length) {
    return { rendered: false, why: `line.ts has no case for ${missing.join(", ")}` };
  }
  return { rendered: true, why: `line.ts has case arms for ${intent.sentinels.join(", ")}` };
}

test("PANEL_BY_INTENT parses and is non-trivial", () => {
  // If this ever silently degrades to an empty map, every "mounted"
  // derivation below would flip to unavailable and the gate would go
  // green for the wrong reason.
  assert.ok(PANEL_BY_INTENT.size >= 8,
    `PANEL_BY_INTENT parsed only ${PANEL_BY_INTENT.size} entries — the anchor probably moved`);
});

// --- the whole point -------------------------------------------------------

test("DECLARED ink status matches what tui.tsx actually renders", () => {
  const wrong = [];
  for (const p of PANEL_INTENTS) {
    const actual = deriveInk(p);
    const declared = p.ink === "mounted";
    if (declared !== actual.mounted) {
      wrong.push(`${p.id}: registry says ink:'${p.ink}' — ${actual.why}`);
    }
  }
  assert.deepEqual(wrong, [],
    "the registry's ink column has drifted from tui.tsx.\n  " + wrong.join("\n  "));
});

test("DECLARED line status matches what line.ts actually renders", () => {
  const wrong = [];
  for (const p of PANEL_INTENTS) {
    const actual = deriveLine(p);
    const declared = p.line === "rendered";
    if (declared !== actual.rendered) {
      wrong.push(`${p.id}: registry says line:'${p.line}' — ${actual.why}`);
    }
  }
  assert.deepEqual(wrong, [],
    "the registry's line column has drifted from line.ts.\n  " + wrong.join("\n  "));
});

// --- the blocker vocabulary ------------------------------------------------

test("every unavailable row declares WHICH kind of gap it is", () => {
  // R365's actual addition. `unavailable` alone cannot distinguish
  // "nobody wrote the UI" from "the daemon has no such RPC" — and
  // those call for opposite responses (write code vs. do not write
  // code). The R362 table conflated them for a full round.
  const bad = PANEL_INTENTS
    .filter((p) => p.ink === "unavailable" || p.line === "unavailable")
    .filter((p) => !["not-wired", "backend-missing", "no-command"].includes(p.blocker));
  assert.deepEqual(bad.map((p) => `${p.id} (blocker=${p.blocker})`), [],
    "every unavailable row needs a structured blocker, not just a prose note");
});

test("every blocked row carries a gapNote explaining itself", () => {
  const bad = PANEL_INTENTS
    .filter((p) => p.ink === "unavailable" || p.line === "unavailable")
    .filter((p) => !(p.gapNote || "").trim());
  assert.deepEqual(bad.map((p) => p.id), [],
    "a blocker without a note is just an assertion");
});

test("a mounted-on-one-renderer row does not claim to be blocked there", () => {
  // workflow-picker and update-available are real on line mode and
  // absent on Ink. If someone later wires Ink and forgets to clear
  // `blocker`, the row would claim a gap that no longer exists.
  const inconsistent = PANEL_INTENTS.filter((p) =>
    p.ink !== "unavailable" && p.line !== "unavailable" && p.blocker);
  assert.deepEqual(inconsistent.map((p) => p.id), [],
    "a fully-mounted intent must not carry a blocker");
});

// --- /help must not advertise a dead command -------------------------------

test("/help never lists a command that does not exist", () => {
  // The live bug this round found: `/context` was advertised by
  // helpByGroup() because it is a registry row, but `handleSlash` has
  // no `case "context"`, so typing it answered
  // "unknown slash command: /context. Try /help."
  const dead = ADVERTISED_INTENTS.filter((p) => {
    const cmd = p.command.replace(/^\//, "");
    // handleSlash is a switch on the bare command name.
    return !new RegExp(`case\\s+"${cmd}"\\s*:`).test(commandsCode);
  });
  assert.deepEqual(dead.map((p) => p.command), [],
    "these commands are advertised by /help but handleSlash has no case for them");
});

test("an advertised command must actually emit one of its sentinels", () => {
  // Second half of the same contract: even with a `case`, the command
  // has to return a sentinel the registry claims, or the panel is
  // unreachable by a different route.
  const mismatched = ADVERTISED_INTENTS.filter((p) => {
    const cmd = p.command.replace(/^\//, "");
    const m = new RegExp(`case\\s+"${cmd}"\\s*:[\\s\\S]{0,600}?return\\s*\\{`).exec(commandsCode);
    if (!m) return false; // no return found in range — covered by the test above
    const returnsSentinel = /local:\s*[`'"]?(__[A-Z_0-9]+__)/.test(m[0]);
    return returnsSentinel && !p.sentinels.some((s) => m[0].includes(s));
  });
  assert.deepEqual(mismatched.map((p) => `${p.command} -> ${p.sentinels.join(",")}`), [],
    "a command returns a sentinel its own registry row does not claim");
});

test("helpByGroup excludes non-advertised rows and keeps the rest", () => {
  const total = helpByGroup().reduce((n, g) => n + g.entries.length, 0);
  assert.equal(total, ADVERTISED_INTENTS.length,
    "helpByGroup must list exactly the advertised rows");
  const cmds = helpByGroup().flatMap((g) => g.entries.map((e) => e.command));
  assert.ok(!cmds.includes("/context"),
    "/context has no command behind it and must not be advertised");
  // ...and the advertised ones are still there.
  for (const p of ADVERTISED_INTENTS) {
    assert.ok(cmds.includes(p.command), `${p.command} went missing from /help`);
  }
});

test("intentForCommand still resolves every row (advertised or not)", () => {
  // Hiding a row from /help must not break the sentinel -> intent path,
  // or the renderer fallback loses its label.
  for (const p of PANEL_INTENTS) {
    assert.equal(intentForCommand(p.command)?.id, p.id);
  }
});