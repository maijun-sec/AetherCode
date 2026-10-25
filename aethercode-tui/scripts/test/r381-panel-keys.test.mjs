// R381 — the panels' key tables, part 2: REACHABILITY, PROSE, and a
// scanner that can see.
//
// ## What R379 left behind
//
// R379 made ten panels render their key hint from the registry
// (`src/componentKeys.ts`) and cross-checked the table against the
// handler in both directions. Two things were still open:
//
//   1. The SCOPE was a hand-written list of "the panels a user can
//      open" — and R379's own note said nine MORE components still
//      hand-wrote a hint. That list was WRONG: `NotificationSettings`
//      and `NotificationDetail` are not openable at all.
//
//   2. The gate's scanner (`ts-scan.mjs`) could not see past an
//      apostrophe in JSX text. `src/tui.tsx` says "the daemon's
//      estimate", and the scanner read on until the next apostrophe —
//      47 lines, including the render of a whole panel. Every
//      assertion over that region was reading whitespace, and none of
//      them knew it.
//
// ## What this gate pins
//
//   1. The scanner is not blind: no source file hides code behind an
//      unterminated quote.
//   2. Reachability is MEASURED (`scripts/test/reachability.mjs`), and
//      every REACHABLE component that hand-writes a key hint is
//      registered.
//   3. Every registered table matches its handler, in BOTH directions.
//   4. A PROSE sentence only names keys that are really bound — by
//      the component, or by the application (the app-level registry).
//   5. Every registered component really RENDERS the registry's hint.
//   6. The components that carry a key hint but cannot be opened are
//      PINNED — so wiring one up turns this gate red instead of
//      silently shipping a hand-written hint.
//
// Lesson 1214 — "reachable" is a measurement, not a claim.
// Lesson 1215 — a checker that answers a slightly different question
// than the one you think is worse than no checker.
// Lesson 1200 — a marker nobody reads is a fake success.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadTsModule } from "./load-ts-module.mjs";
import { scan } from "./ts-scan.mjs";
import { handlerTokens, handWrittenHints } from "./key-tokens.mjs";
import { analyzeReachability } from "./reachability.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const srcRoot = join(root, "src");

const keysMod = await loadTsModule(root, "src/componentKeys.ts");
const kbMod = await loadTsModule(root, "src/keybindings.ts");
const cmdMod = await loadTsModule(root, "src/commands.ts");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

/** The registry's tables, by component path. */
const tables = keysMod.COMPONENT_KEYS;
const registered = new Set(tables.map((t) => t.component));

// =========================================================================
// 1. the scanner can see
// =========================================================================

test("no source file hides code behind an unterminated quote", () => {
  // R381: `src/tui.tsx` says "the daemon's estimate". The scanner used
  // to read on until the NEXT apostrophe and blank 47 lines — the
  // render of the grants panel among them. A gate reading that region
  // was reading whitespace and did not know it.
  //
  // A single- or double-quoted literal CANNOT span lines in
  // TypeScript, so any literal that does is the scanner losing track.
  const offenders = [];
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name)) {
        const src = readFileSync(p, "utf-8");
        const line = (off) => src.slice(0, off).split("\n").length;
        for (const s of scan(src).strings) {
          if (s.quote === "`") continue; // template literals MAY span lines
          if (line(s.end) > line(s.start)) {
            offenders.push(`${p.replace(srcRoot, "src")} lines ${line(s.start)}..${line(s.end)}`);
          }
        }
      }
    }
  };
  walk(srcRoot);
  assert.deepEqual(
    offenders,
    [],
    `these files hide real code behind an unterminated quote: ${offenders.join(", ")} — ` +
      "the scanner is blind there, so every assertion over it is reading whitespace",
  );
});

// =========================================================================
// 2. reachability, measured
// =========================================================================

const analysis = analyzeReachability(root);

test("the reachability analysis really walked the tree", () => {
  // A measurement that found nothing is not a measurement.
  assert.ok(
    analysis.reachableComponents.length >= 30,
    `only ${analysis.reachableComponents.length} reachable components — the walk found nothing`,
  );
  const rendered = new Set(analysis.reachableComponents.map((c) => c.rel));
  assert.ok(
    rendered.has("components/StatusBar.tsx"),
    "StatusBar is reachable — re-anchor this test",
  );
  assert.ok(rendered.has("components/InputBox.tsx"), "InputBox is reachable — re-anchor this test");
});

test("every REACHABLE component that hand-writes a key hint is REGISTERED", () => {
  const offenders = [];
  for (const c of analysis.reachableComponents) {
    const hints = handWrittenHints(read(c.rel));
    if (hints.length === 0) continue;
    if (!registered.has(c.rel)) {
      offenders.push(`${c.rel} (${JSON.stringify(hints)})`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `these reachable components hand-write a key hint instead of rendering the registry's: ` +
      `${offenders.join(", ")} — that is a second copy of the truth`,
  );
});

test("no REGISTERED component hand-writes a key hint", () => {
  for (const table of tables) {
    const hints = handWrittenHints(read(table.component));
    assert.deepEqual(
      hints,
      [],
      `${table.component} still hand-writes a key hint: ${JSON.stringify(hints)} — the hint must ` +
        "come from the key registry",
    );
  }
});

test("every component that CALLS the registry HAS a table", () => {
  // The other direction, and the one that catches a DELETED table: a
  // component that calls `hintFor("components/X.tsx")` for a table
  // that no longer exists renders an EMPTY hint — and the "every
  // registered component renders its hint" test would never notice,
  // because the component is no longer registered.
  const callers = [];
  for (const c of analysis.components) {
    const src = read(c.rel);
    for (const m of src.matchAll(/(?:hintFor|proseFor|emptyNoteFor)\s*\(\s*"([^"]+)"/g)) {
      callers.push({ component: c.rel, table: m[1] });
    }
  }
  const missing = callers.filter((x) => !registered.has(x.table));
  assert.deepEqual(
    missing.map((x) => `${x.component} → ${x.table}`),
    [],
    `these components call the key registry for a table that does not exist: ${missing
      .map((x) => `${x.component} → ${x.table}`)
      .join(", ")} — the hint would render EMPTY`,
  );
});

test("every REGISTERED component really RENDERS the registry's hint", () => {
  // A registry entry nobody renders is a marker nobody reads.
  for (const table of tables) {
    const src = read(table.component);
    const escaped = table.component.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const call = new RegExp(`(hintFor|proseFor|emptyNoteFor)\\s*\\(\\s*"${escaped}"`);
    assert.match(
      src,
      call,
      `${table.component} is in the key registry but never renders it — a table nobody renders is ` +
        "a lie about what the user sees",
    );
  }
});

// =========================================================================
// 3. the table matches the handler, in BOTH directions
// =========================================================================

test("every registered table's tokens are exactly the handler's tokens", () => {
  for (const table of tables) {
    const src = read(table.component);
    const actual = handlerTokens(src, keysMod.KEY_PROP_TOKEN);
    const declared = keysMod.handlerTokens(table.component);

    if (actual === null) {
      // The component has NO handler of its own (the command palette,
      // the search bar, the sub-agent panel, the welcome screen). Then
      // it may only show chords the APPLICATION binds — anything else
      // would be a promise with nobody behind it.
      assert.deepEqual(
        declared,
        [],
        `${table.component} has no useInput handler, but its key table declares handler-bound ` +
          `keys ${JSON.stringify(declared)} — nothing binds them`,
      );
      continue;
    }

    if (table.keys.length > 0) {
      // A key LIST is a complete statement about the component: every
      // key it binds is named, and every key it names is bound.
      assert.deepEqual(
        actual,
        declared,
        `${table.component}'s handler binds ${JSON.stringify(actual)} but its key table declares ` +
          `${JSON.stringify(declared)} — the hint would promise keys the panel does not bind, ` +
          "or hide keys it does",
      );
    } else {
      // A PROSE sentence is a SUMMARY, so it may name fewer keys than
      // the handler binds — but it must not name one that is not
      // bound. A sentence that names a key nobody handles is a promise
      // the user cannot keep.
      const prose = keysMod.proseTokens(table.component);
      for (const token of prose.handler) {
        assert.ok(
          actual.includes(token),
          `${table.component}'s prose names "${token}", but its handler binds only ` +
            `${JSON.stringify(actual)}`,
        );
      }
    }
  }
});

test("a constant-bound key is visible to the checker", () => {
  // R381: `SessionControl` binds its three hotkeys through a
  // constant:
  //
  //     const HOTKEY: Record<ControlAction, string> = {
  //       continue: "c", pause: "p", stop: "s",
  //     };
  //     if (input === HOTKEY.continue) { … }
  //
  // A `input === "x"` scan sees no literal there, so the three hotkeys
  // were INVISIBLE to the checker — and a table that forgot them would
  // have compared equal.
  const src = read("components/session/SessionControl.tsx");
  const tokens = handlerTokens(src, keysMod.KEY_PROP_TOKEN);
  for (const t of ["c", "p", "s"]) {
    assert.ok(
      tokens.includes(t),
      `the constant-bound hotkey "${t}" is invisible to the checker (saw ${JSON.stringify(tokens)}) — ` +
        "the extractor must resolve constants, or a table can drop a key without failing",
    );
  }
});

// =========================================================================
// 4. a prose sentence may only name keys that exist
// =========================================================================

test("every key a prose sentence names is really offered", () => {
  const chords = new Set(kbMod.registryChords());
  const texts = new Set(kbMod.KEYBINDINGS.map((b) => b.text).filter((t) => typeof t === "string"));
  for (const table of tables) {
    const { global } = keysMod.proseTokens(table.component);
    for (const token of global) {
      assert.ok(
        chords.has(token) || texts.has(token),
        `${table.component}'s prose names "${token}", which the app-level registry does not ` +
          `offer — the sentence promises a key the application does not handle (chords: ` +
          `${[...chords].join(", ")})`,
      );
    }
  }
});

test("every GLOBAL chord a component shows is really offered by the app", () => {
  // The components with no handler of their own (the command palette,
  // the search bar, the sub-agent panel, the welcome screen) show
  // chords the APPLICATION binds. Those chords must exist in the
  // app-level registry — the one list of app-level keys.
  //
  // A TEXT trigger ("/" and "@" — what the user TYPES, not a key)
  // counts too: the registry carries those as `text`.
  const chords = new Set(kbMod.registryChords());
  const texts = new Set(kbMod.KEYBINDINGS.map((b) => b.text).filter((t) => typeof t === "string"));
  for (const table of tables) {
    for (const token of keysMod.globalTokens(table.component)) {
      assert.ok(
        chords.has(token) || texts.has(token),
        `${table.component} shows the chord "${token}", which the app-level registry does not ` +
          `offer — the hint promises a key the application does not handle`,
      );
    }
  }
});

test("every command a welcome tip names REALLY EXISTS", () => {
  // R381: the sub-agent tip said "or /spawn <intent> to launch one
  // inline" — and there is no /spawn command. The user typed it and
  // got "unknown slash command". A tip is a promise too.
  const src = read("components/Welcome.tsx");
  const at = src.indexOf("const TIPS");
  assert.ok(at > 0, "the TIPS array is gone — re-anchor this test");
  const end = src.indexOf("];", at);
  const tips = src.slice(at, end);
  const texts = [...tips.matchAll(/text:\s*[`"]([^`"]*)[`"]/g)].map((m) => m[1]);
  assert.ok(texts.length >= 5, `only ${texts.length} tips found — re-anchor this test`);

  const known = new Set(cmdMod.SLASH_COMMANDS);
  const named = new Set();
  for (const t of texts) {
    for (const m of t.matchAll(/\/([a-z][a-z-]*)/g)) named.add(m[1]);
  }
  const missing = [...named].filter((n) => !known.has(n)).sort();
  assert.deepEqual(
    missing,
    [],
    `the welcome tips name commands that do not exist: ${missing.join(", ")} — the user would ` +
      `type them and get "unknown slash command"`,
  );
});

// =========================================================================
// 5. the NOT-reachable components are pinned
// =========================================================================

/**
 * The components that are NOT reachable, but that this gate still
 * cares about.
 *
 * <p>Two kinds:
 *
 *   - a component that carries a hand-written key hint. A key hint on
 *     a panel no user can open is not user-visible drift — it is DEAD
 *     CODE, and the next round should either wire the panel up or
 *     delete it.
 *   - a component that is in the key registry but is not reachable
 *     (`StaleWarning`: the host imports it and mounts it "in T-7").
 *     Removing its render would silently take it out of the reachable
 *     set, and this list is what makes that visible.
 *
 * <p>Measured on 2026-10-25 (R381) by `analyzeReachability`. The list
 * is compared against the MEASURED set, so it cannot drift silently:
 * wire one of these up and this gate goes red, and the panel then has
 * to be registered like any other.
 */
const NOT_REACHABLE_BUT_IN_SCOPE = [
  "components/ConsentPrompt.tsx",
  "components/McpLogin.tsx",
  "components/McpReconnect.tsx",
  "components/NotificationDetail.tsx",
  "components/NotificationSettings.tsx",
  "components/StaleWarning.tsx",
  "components/TaskPanel.tsx",
  "components/UpdateAvailable.tsx",
  "components/UpdateConfirm.tsx",
  "components/UpdateProgress.tsx",
  "components/consent/ConsentModal.tsx",
  "components/models/ModelSelector.tsx",
];

test("the NOT-reachable components are exactly the measured ones", () => {
  const reachable = new Set(analysis.reachableComponents.map((c) => c.rel));
  const measured = analysis.components
    .filter((c) => !reachable.has(c.rel))
    .filter((c) => handWrittenHints(read(c.rel)).length > 0 || registered.has(c.rel))
    .map((c) => c.rel)
    .sort();
  assert.deepEqual(
    measured,
    NOT_REACHABLE_BUT_IN_SCOPE,
    "the set of components that are not reachable (but carry a key hint, or are in the key " +
      "registry) has changed — if one of them was wired up, register it in the key registry; if " +
      "a new one appeared, remove its hint or register it too",
  );
});

test("every NOT-reachable component really is unreachable", () => {
  const reachable = new Set(analysis.reachableComponents.map((c) => c.rel));
  for (const rel of NOT_REACHABLE_BUT_IN_SCOPE) {
    assert.ok(
      !reachable.has(rel),
      `${rel} is in the NOT-reachable list but the reachability walk says it IS reachable — it ` +
        "must be registered in the key registry like any other panel",
    );
    assert.ok(existsSync(join(srcRoot, rel)), `${rel} does not exist — re-anchor this test`);
  }
});
