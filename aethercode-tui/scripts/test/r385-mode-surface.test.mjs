// R385 — the mode surface must speak the daemon's vocabulary, and
// every sentinel must have an owner on BOTH renderers.
//
// ## What this round found
//
// 1. `__PICK_MODE__` — the sentinel bare `/mode` returns — was claimed
//    ONLY by the cmd.exe line mode. On Ink it fell through to the R362
//    fallback and answered "no panel for that command in the TUI yet"
//    for a feature that worked one keystroke away in the other
//    renderer. A FALSE FAILURE is worse than an absent feature.
//
// 2. The permission-mode vocabulary was written in SEVEN files, and the
//    copies had already disagreed:
//      - the list was MISSING `ASK_BEFORE_TOOL` — a mode the daemon
//        has, whose own javadoc says it was named for the TUI;
//      - the Ink header showed "TASK" (ACCEPT_TASK) as the fallback
//        while the daemon's default is `DEFAULT` — a lie about the
//        user's own safety net;
//      - the two renderers showed DIFFERENT badges for the same mode
//        (Ink: the raw name; cmd.exe: "ASK");
//      - the Ink header carried four names the daemon cannot produce
//        (`AUTO`, `ASK`, `DENY`, `BYPASS` — the old `Action` enum).
//
// 3. `/mode default` and `/mode ask-before-tool` both failed: the
//    daemon resolves the mode with `PermissionMode.valueOf(...)`, which
//    is case-sensitive and dash-blind — and the daemon's OWN javadoc
//    documents `/mode ask-before-tool` as the TUI spelling.
//
// ## The three contracts
//
//   §1  the catalog IS the daemon's enum          (read PermissionMode.java)
//   §2  the vocabulary is written in ONE file      (read every src/*.ts)
//   §3  every sentinel has an owner on BOTH sides  (read the renderers)
//
// ## Why the truth is DERIVED
//
// Every assertion below recomputes the fact from the source that
// produces it. Nothing here trusts a status field, a comment or a
// table: the registry, the renderers and the daemon are read, and the
// three are compared. A table that can be false without failing
// anything is worse than no table (R365).
//
// Lesson 1224 — the same defect, written a different way, needs the
// same ruler. The "does this renderer CLAIM the sentinel?" test is
// the one r362 already proved, so this file reuses it verbatim rather
// than inventing a second definition.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { scan } from "./ts-scan.mjs";
import { javaCode } from "./java-strip.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const srcRoot = join(root, "src");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

const commandsSrc = codeOf(read("commands.ts"), "commands.ts");
const tuiCode = codeOf(read("tui.tsx"), "tui.tsx");
const lineCode = codeOf(read("line.ts"), "line.ts");

const modesMod = await loadTsModule(root, "src/permissionModes.ts");
const intentsMod = await loadTsModule(root, "src/panelIntents.ts");
const commandsMod = await loadTsModule(root, "src/commands.ts");

const { MODE, PERMISSION_MODES, normalisePermissionMode, permissionModeEntry } = modesMod;
const { PANEL_INTENTS } = intentsMod;
const { routeSlash } = commandsMod;

/** The daemon's enum, read from the Java source. */
const permissionModeJava = join(
  repo,
  "aethercode",
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
  "permission",
  "PermissionMode.java",
);

/**
 * The enum constants of a Java enum, read from its source.
 *
 * The body is scanned line by line and the scan STOPS at the first
 * line that is not a constant, because a Java enum may carry methods
 * after its constants — and a constant-looking identifier inside a
 * method body is not a mode.
 */
function javaEnumConstants(src, enumName) {
  const at = src.indexOf(`enum ${enumName}`);
  assert.ok(at > -1, `enum ${enumName} not found — re-anchor this test`);
  const open = src.indexOf("{", at);
  assert.ok(open > -1, `enum ${enumName} has no body`);
  const body = src.slice(open + 1);
  const out = [];
  for (const line of body.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === "" || trimmed.startsWith("//")) continue;
    if (trimmed === "}" || trimmed.startsWith("}")) break;
    const m = /^([A-Z][A-Z0-9_]*)\s*[,;]?$/.exec(trimmed);
    if (!m) break;
    out.push(m[1]);
  }
  return out;
}

/**
 * The body of a `{ … }` block, matched with STRING-AWARE brace
 * counting.
 *
 * A naive counter stops early on a `}` inside a string literal — and
 * a truncated extraction is the most expensive kind of green: the
 * block looks fine and every assertion over it passes VACUOUSLY
 * (R384, Lesson 1234).
 */
function blockAfter(src, anchor) {
  const at = src.indexOf(anchor);
  assert.ok(at > -1, `anchor ${anchor} not found — re-anchor this test`);
  const open = src.indexOf("{", at);
  assert.ok(open > -1, `anchor ${anchor} has no block`);
  // Mask the string literals so a `}` inside one cannot close the block.
  const masked = src.split("");
  for (const s of scan(src).strings) {
    for (let i = s.start; i < s.end && i < masked.length; i++) masked[i] = " ";
  }
  let depth = 0;
  for (let i = open; i < masked.length; i++) {
    if (masked[i] === "{") depth++;
    else if (masked[i] === "}") {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  throw new Error(`block after ${anchor} is not balanced — the scanner lost track`);
}

/** Every `case "<label>":` inside a block. */
function caseLabelsIn(block) {
  return new Set([...block.matchAll(/case\s+"([a-zA-Z_-]+)":/g)].map((m) => m[1]));
}

/** intent id -> PanelId, parsed out of tui.tsx's PANEL_BY_INTENT literal. */
function parsePanelByIntent(src) {
  const body = blockAfter(src, "PANEL_BY_INTENT");
  const map = new Map();
  for (const m of body.matchAll(/["']([a-z-]+)["']\s*:\s*["']([a-z-]+)["']/g)) {
    map.set(m[1], m[2]);
  }
  return map;
}

/**
 * Does this renderer CLAIM the sentinel, rather than merely mention
 * it?
 *
 * Verbatim from r362's `claimedBy`: a bare mention in a comment or a
 * help string is not a claim, and a short-circuited branch
 * (`false && local === "__X__"`) is invisible to a source pin by
 * construction. The four forms a renderer actually routes on are
 * `case "__X__":`, `=== "__X__"`, `=== '__X__'` and
 * `startsWith("__X__")`.
 */
function claimedBy(src, sentinel) {
  const esc = sentinel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(
    `(?:case\\s+['"\`]${esc}['"\`]|===\\s*['"\`]${esc}|\\.startsWith\\(\\s*['"\`]${esc})`,
  ).test(src);
}

const PANEL_BY_INTENT = parsePanelByIntent(tuiCode);
const PANEL_CASES = caseLabelsIn(blockAfter(tuiCode, "switch (state.activePanel)"));

/**
 * Every sentinel the command layer can put on a `local:`.
 *
 * The capture is the WHOLE (comment-stripped) file, not a
 * `local:`-anchored pattern. A `local:`-anchored regex misses the
 * emissions that are not a bare literal — `local: target ?
 * \`__MCP_RECONNECT__:${target}\` : "__MCP_RECONNECT__"` is one — and a
 * missed emission is a sentinel nobody is asked to own. The token set
 * in the command layer IS the set of sentinels it can produce.
 */
function emittedSentinels() {
  const emitted = new Set();
  for (const m of commandsSrc.matchAll(/__[A-Z_0-9]+__/g)) emitted.add(m[0]);
  return emitted;
}

/**
 * The import specifier the renderer would use for a component.
 *
 * The registry names the SOURCE file (`components/X.tsx`); the import
 * names the COMPILED one (`./components/X.js`), because the ESM
 * specifier is what the TypeScript source actually writes.
 */
function importSpecifierFor(componentPath) {
  return componentPath.replace(/\.tsx?$/, ".js");
}

// =========================================================================
// §1  the catalog IS the daemon's enum
// =========================================================================

test("the permission-mode catalog is exactly the daemon's enum", () => {
  // The daemon resolves the mode with `PermissionMode.valueOf(...)`,
  // so the enum IS the vocabulary. A mode the TUI invents is rejected
  // by the daemon; a mode the TUI omits is unreachable from the UI.
  const daemon = javaEnumConstants(
    javaCode(readFileSync(permissionModeJava, "utf-8")),
    "PermissionMode",
  );
  assert.ok(daemon.length > 0, "the daemon's enum parsed as empty — re-anchor this test");

  const ours = Object.values(MODE);
  const missing = daemon.filter((d) => !ours.includes(d));
  const extra = ours.filter((o) => !daemon.includes(o));

  assert.deepEqual(
    missing,
    [],
    `the daemon has permission mode(s) the TUI's catalog does not: ${missing.join(", ")} — ` +
      "they are unreachable from /mode",
  );
  assert.deepEqual(
    extra,
    [],
    `the TUI's catalog has permission mode(s) the daemon does not: ${extra.join(", ")} — ` +
      "selecting one fails with 'unknown permission mode'",
  );
});

test("the catalog's rows cover exactly the vocabulary", () => {
  // The rows are what the picker shows. If a row is missing, the mode
  // is unreachable; if there is a spare row, the picker offers a mode
  // the daemon cannot set.
  const vocab = Object.values(MODE).slice().sort();
  const rows = PERMISSION_MODES.map((m) => m.value)
    .slice()
    .sort();
  assert.deepEqual(rows, vocab, "the picker rows and the vocabulary disagree");
});

test("every mode row is complete", () => {
  for (const m of PERMISSION_MODES) {
    assert.ok(m.label && m.label.trim(), `${m.value} has no label`);
    assert.ok(m.badge && m.badge.trim(), `${m.value} has no badge`);
    assert.ok(m.blurb && m.blurb.trim(), `${m.value} has no blurb`);
    assert.ok(m.tone && m.tone.trim(), `${m.value} has no tone`);
  }
});

// =========================================================================
// §2  the vocabulary is written in ONE file
// =========================================================================

test("the permission-mode vocabulary is written in exactly one file", () => {
  // Prose that MENTIONS a mode is fine ("run with --permission-mode=
  // ACCEPT_TASK"). A string literal whose ENTIRE value is a mode NAME
  // is a copy of the vocabulary, and a copy is what drifts.
  //
  // The scan runs on the COMMENT-STRIPPED text, and that is not a
  // detail: `ts-scan` bails at an unterminated quote, and a raw
  // `line.ts` has an apostrophe in a comment ("the daemon's estimate")
  // that the scanner reads as a string opener. It then swallows
  // thousands of lines and every literal after that point is INVISIBLE
  // — so a scan of the raw file would have found nothing and reported
  // "the vocabulary is written once" while the copy sat right there
  // (R381's scanner blind spot, one layer up).
  const names = new Set(Object.values(MODE));
  const owner = "permissionModes.ts";
  const offenders = [];

  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const rel = p.slice(srcRoot.length + 1).replace(/\\/g, "/");
        if (rel === owner) continue;
        const code = codeOf(readFileSync(p, "utf-8"), rel);
        for (const s of scan(code).strings) {
          if (names.has(s.value)) offenders.push(`${rel}: "${s.value}"`);
        }
      }
    }
  };
  walk(srcRoot);

  assert.deepEqual(
    offenders,
    [],
    "the permission-mode vocabulary is written outside src/permissionModes.ts — " +
      `every copy is a place the list can drift: ${offenders.join(", ")}`,
  );
});

test("a file that USES the catalog imports it", () => {
  // The other direction of the same contract. Without it, a renderer
  // could stop reading the catalog and keep using a name it copied —
  // and the import would still be there, so "it imports the catalog"
  // would still be true while the copy was live.
  const exports = [
    "MODE",
    "PERMISSION_MODES",
    "PERMISSION_MODE_VALUES",
    "permissionModeEntry",
    "permissionModeBlurb",
    "normalisePermissionMode",
    "isKnownPermissionMode",
  ];
  const offenders = [];

  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const rel = p.slice(srcRoot.length + 1).replace(/\\/g, "/");
        if (rel === "permissionModes.ts") continue;
        const code = codeOf(readFileSync(p, "utf-8"), rel);
        // The import statements themselves do not count as USE.
        const body = code.replace(/import\s+[^;]*?from\s+["'][^"']*["'];?/g, "");
        const used = exports.filter((n) => new RegExp(`\\b${n}\\b`).test(body));
        if (used.length === 0) continue;
        // The NAMES must be imported, not just the module: a file can
        // import one helper from the catalog and still use a name it
        // never imported — and the import statement then makes the
        // file look connected to the catalog while it is not.
        const stmt = code.match(/import\s*\{([^}]*)\}\s*from\s*["'][^"']*permissionModes\.js["']/);
        const imported = stmt
          ? stmt[1]
              .split(",")
              .map((s) =>
                s
                  .trim()
                  .replace(/^type\s+/, "")
                  .split(/\s+as\s+/)[0]
                  .trim(),
              )
              .filter(Boolean)
          : [];
        const notImported = used.filter((n) => !imported.includes(n));
        if (notImported.length > 0) {
          offenders.push(`${rel} uses ${notImported.join(", ")} but does not import it`);
        }
      }
    }
  };
  walk(srcRoot);

  assert.deepEqual(
    offenders,
    [],
    `these files use the permission-mode catalog without importing it: ${offenders.join("; ")}`,
  );
});

// =========================================================================
// §3  every sentinel has an owner on BOTH renderers
// =========================================================================

test("every sentinel the command layer emits has an owner on BOTH renderers", () => {
  const emitted = [...emittedSentinels()].sort();
  assert.ok(emitted.length > 0, "sanity: commands.ts should emit sentinels");

  const bySentinel = new Map();
  for (const row of PANEL_INTENTS) {
    for (const s of row.sentinels) bySentinel.set(s, row);
  }

  const inkless = [];
  const lineless = [];

  for (const s of emitted) {
    const row = bySentinel.get(s);
    if (row) {
      // Ink owns a panel sentinel through the REGISTRY, not by naming
      // the token: the row maps to a PanelId, the PanelId renders, and
      // the component is really imported.
      const panelId = PANEL_BY_INTENT.get(row.id);
      if (!panelId) {
        inkless.push(`${s} — the registry row "${row.id}" has no PANEL_BY_INTENT entry`);
      } else if (!PANEL_CASES.has(panelId)) {
        inkless.push(`${s} — PANEL_BY_INTENT["${row.id}"] = "${panelId}" but no case renders it`);
      } else if (!row.component || !existsSync(join(srcRoot, row.component))) {
        inkless.push(`${s} — the row names no component file on disk`);
      } else if (
        !new RegExp(
          `from\\s+["'][^"']*${importSpecifierFor(row.component).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}["']`,
        ).test(tuiCode)
      ) {
        inkless.push(`${s} — "${row.component}" exists but tui.tsx never imports it`);
      }
    } else {
      // An "action" sentinel is claimed by NAMING it, on both sides.
      if (!claimedBy(tuiCode, s)) inkless.push(`${s} — no Ink renderer claims it`);
    }
    if (!claimedBy(lineCode, s)) lineless.push(`${s} — no line-mode renderer claims it`);
  }

  assert.deepEqual(inkless, [], `these sentinels have no owner on Ink: ${inkless.join("; ")}`);
  assert.deepEqual(
    lineless,
    [],
    `these sentinels have no owner in line mode: ${lineless.join("; ")}`,
  );
});

test("the registry declares no sentinel the command layer cannot emit", () => {
  // The other direction: a row whose sentinel is never emitted is a
  // panel the user cannot open, advertised in /help.
  const emitted = emittedSentinels();
  const dead = [];
  for (const row of PANEL_INTENTS) {
    for (const s of row.sentinels) {
      if (!emitted.has(s)) dead.push(`${s} (${row.id})`);
    }
  }
  assert.deepEqual(dead, [], `these registry rows have no producer: ${dead.join(", ")}`);
});

test("the /mode picker is a REAL surface on both renderers", () => {
  // The headline defect: bare `/mode` worked in cmd.exe and lied in
  // Ink. Both halves are checked here, and the Ink half is checked by
  // DERIVATION (the registry row -> the PanelId -> the rendered case
  // -> the imported component), not by a status field.
  const row = PANEL_INTENTS.find((r) => r.sentinels.includes("__PICK_MODE__"));
  assert.ok(row, "__PICK_MODE__ has no registry row — the sentinel falls through to the fallback");
  assert.equal(row.ink, "mounted", "the /mode row does not claim an Ink surface");
  assert.equal(row.line, "rendered", "the /mode row does not claim a line-mode surface");
  assert.equal(row.component, "components/ModePicker.tsx", "the /mode row names another component");
  assert.ok(existsSync(join(srcRoot, row.component)), "the ModePicker component does not exist");
  assert.ok(
    /import\s*\{[^}]*ModePicker[^}]*\}\s*from\s*["'][^"']*ModePicker\.js["']/.test(tuiCode),
    "tui.tsx never imports the ModePicker — the row would point at nothing",
  );
  assert.ok(
    claimedBy(lineCode, "__PICK_MODE__"),
    "line mode no longer claims __PICK_MODE__ — bare /mode would print the raw token",
  );
});

test("the picker lists the modes the daemon has, and the line picker agrees", () => {
  // Both pickers must offer the SAME set. Two lists of the modes is
  // exactly the drift this round removed.
  const catalogValues = PERMISSION_MODES.map((m) => m.value)
    .slice()
    .sort();
  const lineValues = [...lineCode.matchAll(/m\.value\.padEnd/g)].length;
  assert.ok(lineValues > 0, "line mode no longer builds its rows from the catalog — re-anchor");
  assert.ok(
    /PERMISSION_MODES\.map\(/.test(lineCode),
    "line mode does not build its picker rows from the shared catalog",
  );
  assert.ok(
    catalogValues.length === Object.values(MODE).length,
    "the picker and the vocabulary disagree about how many modes there are",
  );
});

// =========================================================================
// §4  the argument is normalised, and the daemon's own spelling works
// =========================================================================

test("the normalisation is total over the vocabulary", () => {
  for (const value of Object.values(MODE)) {
    assert.equal(normalisePermissionMode(value), value, `${value} does not survive normalisation`);
    assert.equal(
      normalisePermissionMode(value.toLowerCase()),
      value,
      `/mode ${value.toLowerCase()} does not reach the daemon's spelling`,
    );
    assert.equal(
      normalisePermissionMode(value.replace(/_/g, "-")),
      value,
      `/mode ${value.replace(/_/g, "-")} does not reach the daemon's spelling`,
    );
  }
});

test("/mode <name> puts the daemon's spelling on the wire", () => {
  // Behavioural, not a source pin: the routed result is what the RPC
  // would carry.
  for (const value of Object.values(MODE)) {
    for (const spelling of [value, value.toLowerCase(), value.replace(/_/g, "-")]) {
      const r = routeSlash(`/mode ${spelling}`);
      assert.ok(r, `/mode ${spelling} is not routable`);
      assert.equal(
        r.rpcMethod,
        "setPermissionMode",
        `/mode ${spelling} did not route to setPermissionMode`,
      );
      assert.equal(
        r.rpcParams?.mode,
        value,
        `/mode ${spelling} would put "${r.rpcParams?.mode}" on the wire, not "${value}"`,
      );
    }
  }
});

test("bare /mode opens the picker on both renderers", () => {
  const r = routeSlash("/mode");
  assert.ok(r, "/mode is not routable");
  assert.equal(r.local, "__PICK_MODE__", "bare /mode no longer opens the picker");
});

test("the catalog resolves a mode the way the renderers ask", () => {
  for (const value of Object.values(MODE)) {
    assert.equal(permissionModeEntry(value)?.value, value, `${value} does not resolve`);
    assert.equal(
      permissionModeEntry(value.toLowerCase())?.value,
      value,
      `${value.toLowerCase()} does not resolve`,
    );
  }
  assert.equal(permissionModeEntry("NOT_A_MODE"), undefined, "an unknown mode resolved to a row");
});
