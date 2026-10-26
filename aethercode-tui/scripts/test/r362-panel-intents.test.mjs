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
import { codeOf } from "./strip-comments.mjs";

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
  "--outDir",
  join(tmp, "out"),
  "--target",
  "ES2022",
  "--module",
  "ES2022",
  "--moduleResolution",
  "bundler",
  "--jsx",
  "react",
  "--esModuleInterop",
  "true",
  "--skipLibCheck",
  "true",
  "--rootDir",
  join(root, "src"),
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "panelIntents.ts")}"`], {
  encoding: "utf-8",
  shell: true,
});
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

const commandsSrc = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");

/**
 * Sentinel FAMILIES that intentionally render inline — a toast, a
 * toggle, an export path — rather than as a panel surface.
 *
 * <p>This is an ALLOWLIST, and allowlists rot into suppress-all unless
 * something checks them. The first version of this file had it as a
 * bare regex, and a mutation proved the consequence: disabling the
 * `__HISTORY__` handler in `tui.tsx` left all 22 assertions green. A
 * name matching any family was excused forever, whether or not a
 * renderer had ever heard of it.
 *
 * <p>So the families are a real array (one owner, iterable by the test
 * below) rather than a pattern embedded in one assertion. New families
 * are now visible to the coverage test instead of invisible inside a
 * regex. — Lesson 1120, fourth occurrence.
 *
 * <p>R385: "PICK_MODE" was here until this round, and it was a false
 * description. `__PICK_MODE__` is not an inline action — it opens the
 * permission-mode picker, and the cmd.exe line mode has had that
 * picker since R352. The excuse kept the sentinel out of
 * `panelIntents.ts`, so on Ink nothing claimed it and bare `/mode`
 * answered "no panel for that command in the TUI yet" — a FALSE
 * FAILURE for a working feature. It is now a registry row
 * (`mode-picker`), and the stronger contract lives in
 * `r385-mode-surface.test.mjs`: EVERY emitted sentinel must have an
 * owner on BOTH renderers.
 */
const INLINE_FAMILIES = [
  "EXIT",
  "CLEAR",
  "HISTORY",
  "DEMO",
  "THEME",
  "LAYOUT",
  "BUDGET",
  "BOOKMARK",
  "SNIPPET",
  "EXPORT",
  "THEMES_PICK",
  "TUTORIAL",
  "BANK",
  "SDD",
  "MEMORY_AUDIT",
];
const INLINE_OK = new RegExp(`__(${INLINE_FAMILIES.join("|")})`);

/**
 * Every sentinel token commands.ts can put on a `local:`.
 *
 * <p>One regex, and it deliberately ignores whatever follows the
 * token. The first version used two patterns, one requiring a closing
 * quote right after `__X__` and one for backticks only — so every
 * PARAMETERISED sentinel was invisible to this whole file:
 * `__BUDGET__:0`, `__LAYOUT__:name`, `__SNIPPET_SAVE__:path`,
 * `__BOOKMARK__:n`, `__MCP_LOGIN__:name`, `__BANK_RECALL__:kind:n`,
 * `__THEME__:name`, `__MCP_RECONNECT__:target`. Nine sentinels the user
 * can trigger by typing a command, none of them covered.
 *
 * <p>Same defect shape as the INLINE_OK regex: the gate modelled a
 * narrower world than the code produces, and everything in the gap was
 * silently fine. Capture the token, not the whole literal.
 */
function emittedSentinels() {
  const emitted = new Set();
  for (const m of commandsSrc.matchAll(/local:\s*[`'"](__[A-Z_0-9]+__)/g)) emitted.add(m[1]);
  return emitted;
}

/**
 * Does this renderer actually CLAIM the token, rather than merely
 * mention it?
 *
 * <p>First attempt was `src.includes(sentinel)`, and a mutation showed
 * why that is worthless: short-circuiting the branch with
 * `false && slash.local === "__HISTORY__"` leaves the literal in the
 * text and the gate green. (No text-based gate can catch that — a dead
 * branch is invisible to source pins by construction. What a source
 * pin CAN catch is the token disappearing, which is the failure that
 * actually ships: the handler is deleted during a refactor and the
 * sentinel starts printing itself at the user.)
 *
 * <p>So the match is tightened to the four forms renderers actually
 * use to route a sentinel: `case "__X__":`, `=== "__X__"`,
 * `=== '__X__'`, and `startsWith("__X__:")` for the parameterised ones.
 * A bare mention in a comment or a help string no longer counts.
 */
function claimedBy(src, sentinel) {
  const esc = sentinel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(
    `(?:case\\s+['"\`]${esc}['"\`]|===\\s*['"\`]${esc}|\\.startsWith\\(\\s*['"\`]${esc})`,
  ).test(src);
}

test("every panel sentinel commands.ts can emit has a registry entry", () => {
  const emitted = emittedSentinels();

  assert.ok(emitted.size > 0, "sanity: commands.ts should emit sentinels");

  const missing = [...emitted].filter(
    (s) => !ALL_DECLARED_SENTINELS.includes(s) && !INLINE_OK.test(s),
  );
  assert.deepEqual(
    missing,
    [],
    `panel sentinels with no PANEL_INTENTS entry: ${missing.join(", ")}`,
  );
});

test("every INLINE_OK sentinel is actually claimed by a renderer", () => {
  // The check the regex never got. A sentinel that commands.ts can emit
  // and that no renderer names is exactly the R750 bug: the user types
  // `/history`, and the token comes back printed at them.
  const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
  const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");

  // Families that produced at least one emitted sentinel, so an unused
  // family cannot pass by matching nothing. The floor is MEASURED, not
  // guessed: 29 of 44 emitted sentinels matched INLINE_OK as of
  // 2026-10-23, after the capture was widened to include the
  // parameterised ones. (Guessing 25 here is exactly how a threshold
  // becomes a lie — Lesson 1117.)
  //
  // R385: the floor is 28, not 29. `__PICK_MODE__` left this
  // allowlist because it is a PANEL, not an inline action — it now has
  // a registry row, and `r385-mode-surface.test.mjs` holds it to the
  // STRONGER rule (an owner on BOTH renderers, not merely one).
  // Measured on 2026-10-26: 28 of 47 emitted sentinels match
  // INLINE_OK.
  const relevant = [...emittedSentinels()].filter((s) => INLINE_OK.test(s));
  assert.ok(
    relevant.length >= 28,
    `only ${relevant.length} sentinels matched INLINE_OK — the family list probably ` +
      "stopped matching, which would make every assertion below vacuous",
  );

  const unclaimed = relevant.filter((s) => !claimedBy(lineSrc, s) && !claimedBy(tuiSrc, s));
  assert.deepEqual(
    unclaimed,
    [],
    "these sentinels are excused by INLINE_OK but NO renderer names them, so " +
      `they leak the raw token to the user: ${unclaimed.join(", ")}`,
  );

  // And a family nobody emits is dead weight in an allowlist — it is
  // pure future suppression.
  const unused = INLINE_FAMILIES.filter((f) => !relevant.some((s) => s.includes(f)));
  assert.deepEqual(
    unused,
    [],
    `INLINE_FAMILIES entries that match no emitted sentinel: ${unused.join(", ")}. ` +
      "An allowlist entry for a sentinel nobody can emit is suppression waiting to happen.",
  );
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
  assert.deepEqual(
    bad.map((p) => p.id),
    [],
    "unavailable intents must explain themselves via gapNote",
  );
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
    if (
      !candidates.some((c) => {
        try {
          readFileSync(c);
          return true;
        } catch {
          return false;
        }
      })
    ) {
      missing.push(`${p.id} -> ${p.component}`);
    }
  }
  assert.deepEqual(missing, [], "registry points at component files that do not exist");
});

test("registry titles are user-facing copy, not dev notes", () => {
  // P0.3 strips dev notes from /help. The registry feeds /help, so the
  // same standard applies: no round ids, no T-numbers, no "对应历史 round".
  const dirty = PANEL_INTENTS.filter((p) => /\bR\d{3}\b|\bT-\d+|round:|历史/i.test(p.title));
  assert.deepEqual(
    dirty.map((p) => `${p.id}: ${p.title}`),
    [],
    "registry titles leak development notes into user-facing copy",
  );
});

test("gapNote may mention rounds (it's internal), titles may not", () => {
  // Guards the other direction: the gapNote is an internal engineering
  // record, so it is ALLOWED (and expected) to cite round numbers.
  const withRoundRef = PANEL_INTENTS.filter((p) => /R\d{3}/.test(p.gapNote || ""));
  assert.ok(
    withRoundRef.length > 0,
    "gapNotes should cite the round that found the gap — that is the audit trail",
  );
});

test("describeSentinel never leaks the raw token", () => {
  // The whole point: when a renderer cannot render a panel, the user sees
  // a sentence, not `__MEMORY_PANEL__:project`.
  for (const s of ALL_DECLARED_SENTINELS) {
    for (const surface of ["ink", "line"]) {
      const d = describeSentinel(s, surface);
      assert.ok(
        !d.includes("__"),
        `describeSentinel("${s}", "${surface}") leaked the raw token: ${d}`,
      );
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
  for (const g of groups) {
    const cmds = g.entries.map((e) => e.command);
    assert.equal(new Set(cmds).size, cmds.length, `duplicate command in group ${g.group}`);
    assert.deepEqual([...cmds], [...cmds].sort(), `group ${g.group} not sorted`);
  }
});

test("the advertised:false escape hatch actually excludes a row", () => {
  // This used to be `assert.ok(total < PANEL_INTENTS.length, "if
  // every intent is advertised the escape hatch is dead code")` —
  // i.e. it proved the mechanism was exercised only by the COINCIDENCE
  // that some row happened to be hidden. R371 gave `/context` a real
  // command, that row stopped being hidden, and the assertion went
  // red with the product in a perfectly good state.
  //
  // That assertion was backwards. "At least one row is hidden" is a
  // property of TODAY'S TABLE, not of the filter; a table with nothing
  // to hide is not evidence of broken code. What must hold is that the
  // filter WORKS, which is only checkable against a synthetic row.
  //
  // Same shape as R370's "panel has a fetch branch": an invariant
  // aimed at the wrong object, failing on correct code.
  const { PANEL_INTENTS: rows } = mod;
  const synthetic = {
    id: "synthetic-no-command",
    sentinels: ["__SYNTHETIC__"],
    command: "/synthetic",
    title: "synthetic row used to prove the advertised filter works",
    group: "system",
    ink: "unavailable",
    line: "unavailable",
    blocker: "no-command",
    gapNote: "synthetic",
    advertised: false,
  };
  const withSynthetic = rows.concat([synthetic]);
  const filtered = withSynthetic.filter((p) => p.advertised !== false);
  assert.equal(
    filtered.length,
    rows.length,
    "advertised:false did NOT exclude the synthetic row — the escape hatch is broken",
  );
  assert.ok(!filtered.some((p) => p.id === synthetic.id));
  // ...and the SAME row without the flag would be advertised, so the
  // assertion above is measuring the filter rather than the input.
  //
  // (The first draft compared "rows with no `advertised` key" against
  // "rows passing the filter", which is a statement about TODAY'S
  // TABLE — it would go red the first time somebody wrote
  // `advertised: true`, and that would be fine. Measuring the filter
  // against its own input is the only version that stays true.)
  const withoutFlag = { ...synthetic };
  delete withoutFlag.advertised;
  const control = withSynthetic.concat([withoutFlag]);
  assert.ok(
    control.filter((p) => p.advertised !== false).some((p) => p.id === synthetic.id),
    "an un-flagged row is ALSO excluded — the probe row was not a fair test of the flag",
  );
});

test("regression: the 15 orphaned sentinels are all accounted for", () => {
  // Hard-coded from the 2026-10-21 reachability sweep. If someone
  // "fixes" one by deleting it from commands.ts, this test tells them
  // the gap count moved rather than silently shrinking.
  const ORPHANS = [
    "__AGENT_PICK__",
    "__CWD_PICK__",
    "__EFFORT_PICK__",
    "__GRANTS_MANAGER__",
    "__MCP_LOGIN__",
    "__MCP_VIEWER__",
    "__MEMORY_EDIT__",
    "__MEMORY_PANEL__",
    "__NOTIFICATION_CENTER__",
    "__THREAD_SELECTOR__",
    "__TODO_BOARD__",
    "__UPDATE_AVAILABLE__",
    "__UPDATE_DEPS__",
    "__UPDATE_INSTALL__",
    "__WORKFLOW_PICKER__",
  ];
  for (const s of ORPHANS) {
    assert.ok(
      intentForSentinel(s),
      `${s} was orphaned on 2026-10-21 and must stay in the registry until it is claimed`,
    );
  }
});
