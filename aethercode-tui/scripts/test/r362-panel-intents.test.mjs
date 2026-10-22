// R362 — panel-intent contract.
//
// The 2026-10-21 benchmark found 15 slash commands whose sentinel was
// printed raw to the user (`__MEMORY_PANEL__:project`), sitting behind
// 15 unmounted components / 5,859 dead lines. `/help` advertised them.
// The root cause is architectural: `handleSlash` returns a magic string
// and each renderer hand-writes an `if (slash.local === "__X__")` chain,
// with nothing checking that a claim exists.
//
// `src/panelIntents.ts` is the registry that closes the loop. These tests
// make the omission impossible to reintroduce silently:
//
//   1. every panel sentinel `commands.ts` emits has a registry entry
//   2. every `unavailable` entry explains itself (gapNote required)
//   3. registry metadata is user-facing-clean (no round refs / dev notes)
//   4. the fallback path renders a sentence, never the raw token
//
// Compiles `panelIntents.ts` with tsc into a temp dir (same pattern as
// r196-commands.test.mjs) because node:test cannot import TS directly.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r362-panel-intents");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") {
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  } else {
    spawnSync("rm", ["-rf", tmp]);
  }
}
mkdirSync(tmp, { recursive: true });

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "panelIntents.ts")}"`],
  { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

// dynamic import needs a file:// URL on Windows — a bare absolute path
// is parsed as scheme "d:" and rejected by the ESM loader.
const mod = await import(pathToFileURL(join(tmp, "out", "panelIntents.js")).href);
const {
  PANEL_INTENTS,
  ALL_DECLARED_SENTINELS,
  intentForSentinel,
  intentForCommand,
  describeSentinel,
  helpByGroup,
} = mod;

const commandsSrc = readFileSync(join(root, "src", "commands.ts"), "utf-8");

/** Sentinel PREFIXES that intentionally render as text, not as a UI surface. */
const INLINE_OK = /__(EXIT|CLEAR|HISTORY|DEMO|THEME|LAYOUT|BUDGET|BOOKMARK|SNIPPET|EXPORT|THEMES_PICK|TUTORIAL|BANK|SDD|MEMORY_AUDIT|PICK_MODE)/;

test("every panel sentinel commands.ts can emit has a registry entry", () => {
  const emitted = new Set();
  for (const m of commandsSrc.matchAll(/local:\s*[`'"](__[A-Z_0-9]+__)[`'"]/g)) emitted.add(m[1]);
  for (const m of commandsSrc.matchAll(/local:\s*`(__[A-Z_0-9]+__)/g)) emitted.add(m[1]);

  assert.ok(emitted.size > 0, "sanity: commands.ts should emit sentinels");

  const missing = [...emitted].filter(
    (s) => !ALL_DECLARED_SENTINELS.includes(s) && !INLINE_OK.test(s),
  );
  assert.deepEqual(missing, [],
    `panel sentinels with no PANEL_INTENTS entry: ${missing.join(", ")}`);
});

test("every unavailable entry carries a gapNote", () => {
  // An `unavailable` row is a deliberate, acknowledged gap. Without a
  // note it is indistinguishable from an oversight — which is the exact
  // failure mode this file exists to prevent.
  //
  // R365 NOTE: this test checks only that the note EXISTS. It never
  // checked that the declared status was TRUE, which is how ten rows
  // sat at `unavailable` while eight of them were fully wired. The
  // real check lives in `r365-panel-truth.test.mjs`, which derives
  // reachability from tui.tsx / line.ts instead of trusting the table.
  const bad = PANEL_INTENTS.filter(
    (p) => (p.ink === "unavailable" || p.line === "unavailable") && !(p.gapNote || "").trim(),
  );
  assert.deepEqual(bad.map((p) => p.id), [],
    "unavailable intents must explain themselves via gapNote");
});

test("sentinels are unique across the registry", () => {
  const seen = new Map();
  const dupes = [];
  for (const p of PANEL_INTENTS) {
    for (const s of p.sentinels) {
      if (seen.has(s)) dupes.push(`${s} claimed by both ${seen.get(s)} and ${p.id}`);
      seen.set(s, p.id);
    }
  }
  assert.deepEqual(dupes, [], "a sentinel must map to exactly one intent");
});

test("intent ids and primary commands are unique", () => {
  const ids = PANEL_INTENTS.map((p) => p.id);
  assert.equal(new Set(ids).size, ids.length, "duplicate intent id");
  const cmds = PANEL_INTENTS.map((p) => p.command);
  assert.equal(new Set(cmds).size, cmds.length, "duplicate primary command");
});

test("every declared component file exists on disk", () => {
  // Guards against the registry drifting from reality — a component path
  // that no longer resolves means the row is stale and the gap note lies.
  const missing = [];
  for (const p of PANEL_INTENTS) {
    if (!p.component) continue;
    const candidates = [
      join(root, "src", p.component),
      join(root, "src", "components", p.component),
    ];
    if (!candidates.some((c) => { try { readFileSync(c); return true; } catch { return false; } })) {
      missing.push(`${p.id} -> ${p.component}`);
    }
  }
  assert.deepEqual(missing, [], "registry points at component files that do not exist");
});

test("registry titles are user-facing copy, not dev notes", () => {
  // P0.3 strips dev notes from /help. The registry feeds /help, so the
  // same standard applies: no round ids, no T-numbers, no "对应历史 round".
  const dirty = PANEL_INTENTS.filter((p) =>
    /\bR\d{3}\b|\bT-\d+|round:|历史/i.test(p.title));
  assert.deepEqual(dirty.map((p) => `${p.id}: ${p.title}`), [],
    "registry titles leak development notes into user-facing copy");
});

test("gapNote may mention rounds (it's internal), titles may not", () => {
  // Guards the other direction: the gapNote is an internal engineering
  // record, so it is ALLOWED (and expected) to cite round numbers.
  const withRoundRef = PANEL_INTENTS.filter((p) => /R\d{3}/.test(p.gapNote || ""));
  assert.ok(withRoundRef.length > 0,
    "gapNotes should cite the round that found the gap — that is the audit trail");
});

test("describeSentinel never leaks the raw token", () => {
  // The whole point: when a renderer cannot render a panel, the user sees
  // a sentence, not `__MEMORY_PANEL__:project`.
  for (const s of ALL_DECLARED_SENTINELS) {
    for (const surface of ["ink", "line"]) {
      const d = describeSentinel(s, surface);
      assert.ok(!d.includes("__"),
        `describeSentinel("${s}", "${surface}") leaked the raw token: ${d}`);
    }
  }
  assert.match(describeSentinel("__MEMORY_PANEL__", "line"), /not available in line mode/);
});

test("lookup helpers resolve by sentinel and by command", () => {
  assert.equal(intentForSentinel("__MEMORY_PANEL__")?.id, "memory-panel");
  assert.equal(intentForSentinel("__MCP_LOGIN__")?.id, "mcp-viewer");
  assert.equal(intentForSentinel("__MCP_RECONNECT__")?.id, "mcp-viewer");
  assert.equal(intentForCommand("/memory")?.id, "memory-panel");
  assert.equal(intentForCommand("memory")?.id, "memory-panel");
  assert.equal(intentForCommand("/nope"), undefined);
  assert.equal(intentForSentinel("__NOT_A_THING__"), undefined);
});

test("helpByGroup returns every ADVERTISED intent exactly once, grouped and sorted", () => {
  // R365: `helpByGroup` is built from ADVERTISED_INTENTS, because
  // `/context` is a registry row with no command behind it and was
  // being advertised by a `/help` that answered "unknown slash
  // command" when it was typed. `r365-panel-truth.test.mjs` pins that
  // contract; this test only covers the grouping itself.
  const ADVERTISED_INTENTS = mod.ADVERTISED_INTENTS;
  assert.ok(ADVERTISED_INTENTS, "panelIntents must export ADVERTISED_INTENTS");
  const groups = helpByGroup();
  const total = groups.reduce((n, g) => n + g.entries.length, 0);
  assert.equal(total, ADVERTISED_INTENTS.length, "helpByGroup dropped or duplicated an intent");
  assert.ok(total < PANEL_INTENTS.length,
    "if every intent is advertised the `advertised: false` escape hatch is dead code");
  for (const g of groups) {
    const cmds = g.entries.map((e) => e.command);
    assert.equal(new Set(cmds).size, cmds.length, `duplicate command in group ${g.group}`);
    assert.deepEqual([...cmds], [...cmds].sort(), `group ${g.group} not sorted`);
  }
});

test("regression: the 15 orphaned sentinels are all accounted for", () => {
  // Hard-coded from the 2026-10-21 reachability sweep. If someone
  // "fixes" one by deleting it from commands.ts, this test tells them
  // the gap count moved rather than silently shrinking.
  const ORPHANS = [
    "__AGENT_PICK__", "__CWD_PICK__", "__EFFORT_PICK__", "__GRANTS_MANAGER__",
    "__MCP_LOGIN__", "__MCP_VIEWER__", "__MEMORY_EDIT__", "__MEMORY_PANEL__",
    "__NOTIFICATION_CENTER__", "__THREAD_SELECTOR__", "__TODO_BOARD__",
    "__UPDATE_AVAILABLE__", "__UPDATE_DEPS__", "__UPDATE_INSTALL__",
    "__WORKFLOW_PICKER__",
  ];
  for (const s of ORPHANS) {
    assert.ok(intentForSentinel(s),
      `${s} was orphaned on 2026-10-21 and must stay in the registry until it is claimed`);
  }
});