// R378 — the help must be GENERATED from the truth, not hand-written
// three times.
//
// ## What R378 found
//
// The TUI carried three hand-written copies of the same key-binding
// list: the Ctrl-? help overlay, the welcome screen's grid, and the
// `--help` text. Three copies, three truths, and they had already
// contradicted each other and the handler:
//
//   - the welcome screen said "Ctrl-T — theme picker"; the handler
//     binds Ctrl-T to re-open the welcome screen and the theme picker
//     to Ctrl-SHIFT-T;
//   - the overlay said "Ctrl-L — clear scrollback"; the handler's
//     Ctrl-L was a placeholder that logged "(clear is a no-op in this
//     build)" and shadowed the log-viewer toggle behind it;
//   - the overlay listed 13 chords; the app-level handler binds 35.
//     The missing ones included ESC TO STOP A RUNNING TURN — the one
//     binding R363 exists to provide;
//   - eight development notes leaked into user-facing text
//     ("R37: toggle side panel", "对应历史 round: …").
//
// Nothing failed. Every copy was valid TypeScript, and every gate that
// touched them was green.
//
// ## What this gate makes executable
//
// 1. **The registry covers the handler.** Every chord the app-level
//    handler binds is documented, and every chord the registry
//    documents is really bound — a two-way cover, both derived from
//    the source rather than asserted by hand.
// 2. **No development notes reach the user.** The registry's own
//    values (labels, descriptions) are checked, and so is every
//    consumer's CODE.
// 3. **There is no second copy.** Each consumer must import the
//    registry and must not carry a chord table of its own.
// 4. **The help does not promise what this daemon cannot do.** A
//    binding whose RPC is on the refusal table is marked unavailable —
//    the same table the dispatcher consults (R377).
//
// ## What this gate cannot do
//
// It cannot tell whether a DESCRIPTION is a good description. It can
// tell that the description is not a round number, and that the chord
// it names is one the handler really binds.
//
// Lesson 1197 — a second copy of a truth drifts, and it drifts
// silently. The fix is not "be careful when editing the list"; the fix
// is that there is no list to edit twice.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { matchBrace } from "./ts-scan.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const readSrc = (rel) => readFileSync(join(root, rel), "utf-8");
const tuiSrc = readSrc("src/tui.tsx");
const overlaySrc = readSrc("src/components/HelpOverlay.tsx");
const welcomeSrc = readSrc("src/components/Welcome.tsx");
const acTuiSrc = readSrc("src/ac-tui.ts");

// =========================================================================
// The daemon's own truth: the chords the app-level handler binds
// =========================================================================

/** Normalised token for a `key.<flag>` property. */
const FLAGS = { escape: "esc", tab: "tab", upArrow: "up", downArrow: "down", return: "return" };

/**
 * The bodies of every `useInput` handler in a source file.
 *
 * @param {string} src
 * @returns {string[]}
 */
function handlerBodies(src) {
  const out = [];
  for (const m of src.matchAll(/useInput\(\((?:_?input),\s*key\)\s*=>\s*\{/g)) {
    const open = src.indexOf("{", m.index);
    const end = matchBrace(src, open);
    if (end < 0) continue;
    out.push(src.slice(open + 1, end - 1));
  }
  return out;
}

/**
 * The chords a handler body binds, normalised.
 *
 * <p>`ctrl+c`, `ctrl+shift+b`, `ctrl+1-9`, `esc`, `tab`, `up`, `down`,
 * `return`, or a bare letter.
 *
 * @param {string} body
 * @returns {string[]}
 */
function chordsIn(body) {
  const tokens = new Set();
  const ctrlWindows = [];

  for (const m of body.matchAll(/key\.ctrl/g)) {
    let end = m.index;
    while (end < body.length && body[end] !== "{" && body[end] !== ";") end++;
    const win = body.slice(m.index, end);
    ctrlWindows.push([m.index, end]);
    const prefix = /key\.shift/.test(win) ? "ctrl+shift+" : "ctrl+";
    if (/\/\^\[1-9\]\$/.test(win)) {
      tokens.add(prefix + "1-9");
      continue;
    }
    for (const im of win.matchAll(/input\s*===\s*"([^"]+)"/g)) {
      tokens.add(prefix + im[1].toLowerCase());
    }
  }

  const inCtrl = (i) => ctrlWindows.some(([a, b]) => i >= a && i < b);

  for (const m of body.matchAll(/input\s*===\s*"([^"]+)"/g)) {
    if (inCtrl(m.index)) continue;
    tokens.add(m[1].toLowerCase());
  }
  for (const m of body.matchAll(/key\.(escape|tab|upArrow|downArrow|return)\b/g)) {
    if (inCtrl(m.index)) continue;
    tokens.add(FLAGS[m[1]]);
  }

  return [...tokens].sort();
}

/**
 * The app-level handler: the one that exits the app on Ctrl-C.
 *
 * <p>Chosen by BEHAVIOUR, not by position — "the last useInput in the
 * file" is a rule that breaks the day someone adds a hook below it.
 *
 * @returns {string[]} the body; empty when it cannot be found
 */
function appHandlerBodies() {
  return handlerBodies(tuiSrc).filter((b) => /type:\s*"exit"/.test(b));
}

const appBodies = appHandlerBodies();
const appChords = appBodies.length === 1 ? chordsIn(appBodies[0]) : [];

// =========================================================================
// The registry
// =========================================================================

const kbMod = await loadTsModule(root, "src/keybindings.ts");
const KEYBINDINGS = kbMod.KEYBINDINGS;
const registryChordSet = new Set(kbMod.registryChords());

// R377's refusal table, loaded the same way — the answer to "can this
// daemon serve that chord?" comes from the ONE table the dispatcher
// consults, not from a second copy here.
const capsMod = await loadTsModule(root, "src/rpcCapabilities.ts");
const refused = capsMod.BACKEND_MISSING;

// =========================================================================

test("the app-level handler is found, and the chord extraction is not vacuous", () => {
  assert.equal(
    appBodies.length,
    1,
    `expected exactly ONE app-level key handler (the one that exits the app), found ` +
      `${appBodies.length} — re-anchor this test`,
  );
  assert.ok(
    appBodies[0].length > 2000,
    `the app-level handler body is only ${appBodies[0].length} chars — the extraction is ` +
      "probably looking at the wrong block",
  );
  assert.ok(
    appChords.length >= 25,
    `only ${appChords.length} chords extracted from the handler — the patterns probably ` +
      "stopped matching, and every coverage assertion below would be vacuous",
  );
  // A few chords the handler MUST bind. If the extraction silently
  // stopped matching, these are what it would miss first.
  for (const chord of ["ctrl+c", "esc", "tab", "ctrl+z", "ctrl+?"]) {
    assert.ok(
      appChords.includes(chord),
      `the extraction did not find "${chord}", which the handler definitely binds — ` +
        "the patterns drifted",
    );
  }
});

test("every chord the handler binds is DOCUMENTED in the registry", () => {
  const undocumented = appChords.filter((c) => !registryChordSet.has(c));
  assert.deepEqual(
    undocumented,
    [],
    "these chords are bound by the app-level handler but the help does not mention " +
      "them:\n  " +
      undocumented.join("\n  ") +
      "\nThe user cannot discover a binding the help does not list.",
  );
});

test("every chord the registry documents is REALLY bound", () => {
  const phantom = [];
  for (const b of KEYBINDINGS) {
    for (const chord of b.keys ?? []) {
      if (b.boundIn) {
        // Bound by another file: check that file.
        const other = handlerBodies(readSrc(b.boundIn));
        const found = other.some((body) => chordsIn(body).includes(chord));
        if (!found) {
          phantom.push(`${chord}  (claimed bound in ${b.boundIn}, but not bound there)`);
        }
        continue;
      }
      if (!appChords.includes(chord)) {
        phantom.push(`${chord}  (${b.label})`);
      }
    }
  }
  assert.deepEqual(
    phantom,
    [],
    "the help documents chords nothing binds — a binding that does not work is a lie:\n  " +
      phantom.join("\n  "),
  );
});

test("no development note reaches the user", () => {
  const DEV_NOTE = /(\bR\d{2,3}\b)|(对应历史 round)|(\bT-\d{3}\b)|(R\d{2,3}:)/;
  for (const b of KEYBINDINGS) {
    for (const field of ["label", "desc", "short"]) {
      const value = b[field];
      if (!value) continue;
      assert.ok(
        !DEV_NOTE.test(value),
        `the registry's ${field} for "${b.label}" is a development note: "${value}"`,
      );
    }
  }
  // And the consumers' own CODE (comments stripped — prose about a
  // round is fine in a comment, never in a rendered string).
  for (const [label, src] of [
    ["HelpOverlay.tsx", overlaySrc],
    ["Welcome.tsx", welcomeSrc],
    ["ac-tui.ts", acTuiSrc],
  ]) {
    const code = codeOf(src, label);
    const hit = /["'`][^"'`]*(\bR\d{2,3}\b|对应历史 round|\bT-\d{3}\b)[^"'`]*["'`]/.exec(code);
    assert.ok(
      !hit,
      `${label} has a development note in a user-facing string: ${hit ? hit[0] : ""}`,
    );
  }
});

test("there is no second copy: every consumer renders from the registry", () => {
  const consumers = [
    ["HelpOverlay.tsx", overlaySrc],
    ["Welcome.tsx", welcomeSrc],
    ["ac-tui.ts", acTuiSrc],
  ];
  for (const [label, src] of consumers) {
    const code = codeOf(src, label);
    assert.match(
      code,
      /from\s+"[^"]*keybindings\.js"/,
      `${label} must import the key-binding registry — a hand-written list is a second ` +
        "copy of a truth, and a second copy drifts",
    );
    // The old shape was a table of `{ key: "Ctrl-B", label: … }` rows.
    assert.ok(
      !/key:\s*"(?:Ctrl|Esc|Tab|Enter|↑)/.test(code),
      `${label} still carries a hand-written chord table — it must render the registry`,
    );
  }
});

test("every help group is actually RENDERED", () => {
  // Importing the registry is not the same as rendering it: a consumer
  // that imports it and then renders `[]` looks identical to one that
  // works, as far as an import check can tell.
  const code = codeOf(overlaySrc, "HelpOverlay.tsx");
  for (const group of ["session", "display", "editing", "subagent", "permission"]) {
    assert.match(
      code,
      new RegExp(`bindingsIn\\(\\s*"${group}"\\s*\\)`),
      `the overlay never renders the "${group}" section — the import is there but the ` +
        "section is empty",
    );
  }
  // And the welcome screen's dense grid.
  const welcome = codeOf(welcomeSrc, "Welcome.tsx");
  assert.match(
    welcome,
    /welcomeBindings\(/,
    "the welcome screen never renders the registry's grid",
  );
});

test("the overlay's command list is derived, not hand-written", () => {
  const code = codeOf(overlaySrc, "HelpOverlay.tsx");
  assert.match(
    code,
    /ADVERTISED_INTENTS/,
    "the overlay must list the panel commands from the panel registry",
  );
  assert.ok(
    !/name:\s*"\/[a-z]/.test(code),
    "the overlay still hand-writes its slash-command list — it must derive it",
  );
});

test("the help does not promise what this daemon cannot do", () => {
  // R377's rule, applied to a key: the answer comes from the SAME
  // refusal table the dispatcher consults, so the two cannot disagree.
  const marked = KEYBINDINGS.filter((b) => kbMod.bindingUnavailable(b)).map((b) => b.label);
  const expected = KEYBINDINGS.filter((b) => b.method && refused.has(b.method)).map((b) => b.label);
  assert.deepEqual(
    marked.sort(),
    expected.sort(),
    "the set of bindings the help marks as unavailable must be exactly the set whose RPC " +
      "is on the refusal table",
  );
  // R379: `rewind` is SERVED now, so Ctrl-Z is NOT marked unavailable.
  // The mark is derived from the SAME refusal table the dispatcher
  // consults, so the two cannot disagree — which is the point: when
  // the daemon gained the capability, the help stopped apologising
  // for it without anybody editing the help.
  assert.ok(
    !marked.includes("Ctrl+Z"),
    "Ctrl-Z calls `rewind`, which the daemon serves since R379 — marking it " +
      "unavailable tells the user a working key is dead",
  );
  // The derivation must still be wired: the mark comes from the table,
  // it is not a hand-written list.
  assert.match(
    readSrc("src/keybindings.ts"),
    /bindingUnavailable/,
    "the unavailable mark must be derived from the refusal table",
  );
});

test("the gate is not vacuous", () => {
  assert.ok(KEYBINDINGS.length >= 20, `only ${KEYBINDINGS.length} bindings in the registry`);
  assert.ok(registryChordSet.size >= 25, `only ${registryChordSet.size} chords in the registry`);
  // Every group must be non-empty, or a section of the help is empty.
  const groups = new Set(KEYBINDINGS.map((b) => b.group));
  for (const g of ["session", "display", "editing", "subagent", "permission"]) {
    assert.ok(groups.has(g), `the registry has no "${g}" bindings — a help section is empty`);
  }
});
