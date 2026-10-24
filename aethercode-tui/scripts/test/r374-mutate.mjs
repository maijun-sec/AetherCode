// Mutation harness for r374-update-panel.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r374-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// Two families:
//
//   * PRODUCT mutations — the TUI or the daemon is broken. Most of
//     them are behavioural, because `updateInfo.ts` is a pure module
//     the gate COMPILES AND RUNS: "the install verb reuses the check
//     sentence" is a value, not a line of source.
//
//   * GATE mutations — an assertion is neutered and a real defect is
//     introduced. These are only meaningful when the defect has a
//     SECOND independent derivation, so the gate still catches it
//     with the neutered assertion out of the way. A neutered
//     assertion whose defect only IT could see proves nothing (it
//     proves the assertion was the only one) — those are deleted,
//     not shipped.
//
// THIS HARNESS REWRITES PRODUCT SOURCE IN PLACE. It must never run
// concurrently with the test suite (R373 Lesson 1166): a battery that
// edits `src/tui.tsx` while `npm test` reads it produces failures
// that belong to neither.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r374-update-panel.test.mjs"),
  info: join(root, "src", "updateInfo.ts"),
  version: join(root, "src", "version.ts"),
  panel: join(root, "src", "components", "UpdatePanel.tsx"),
  available: join(root, "src", "components", "UpdateAvailable.tsx"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  intents: join(root, "src", "panelIntents.ts"),
  commands: join(root, "src", "commands.ts"),
  actui: join(root, "src", "ac-tui.ts"),
  methods: join(repo, "aethercode", "aethercode-protocol", "src", "main", "java",
    "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java"),
};

// Read the originals ONCE, in memory. `git checkout --` would restore
// to HEAD and swallow every uncommitted fix in the working tree
// (R373: Lesson 1171 has bitten this repo four times).
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", PATHS.gate], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  // `fail === -1` means the gate never got as far as reporting — e.g.
  // the mutation broke the TypeScript compile, and the gate exited
  // non-zero. That is still RED, so it counts as caught.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

const CASES = [
  // --- the verb mapping -----------------------------------------------------
  ["the install sentinel maps back to the check verb",
    [["info", (s) => s.replace(
      '  if (sentinel === "__UPDATE_INSTALL__") return "install";',
      '  if (sentinel === "__UPDATE_INSTALL__") return "check";')]]],

  ["the deps sentinel maps back to the check verb",
    [["info", (s) => s.replace(
      '  if (sentinel === "__UPDATE_DEPS__") return "deps";',
      '  if (sentinel === "__UPDATE_DEPS__") return "check";')]]],

  ["an unknown sentinel falls back to the MUTATING verb",
    [["info", (s) => s.replace(
      '  if (sentinel === "__UPDATE_DEPS__") return "deps";\n  return "check";',
      '  if (sentinel === "__UPDATE_DEPS__") return "deps";\n  return "install";')]]],

  ["the three verbs collapse onto one endpoint",
    [["info", (s) => s.replace(
      '  deps: "update/refreshDeps",',
      '  deps: "update/check",')]]],

  // --- three answers, not one ----------------------------------------------
  ["the install verb reuses the check sentence",
    [["info", (s) => s.replace(
      '    case "install":\n      return "This daemon has no install operation. Nothing was " +\n        "downloaded, nothing was replaced, and nothing was written.";',
      '    case "install":\n      return "This daemon has no update-check operation, so whether a " +\n        "newer release exists cannot be answered here. No release feed " +\n        "was contacted and nothing was changed.";')]]],

  ["the deps verb reuses the check sentence",
    [["info", (s) => s.replace(
      '    case "deps":\n      return "This daemon has no dependency-refresh operation. No index " +\n        "was refreshed and nothing was changed.";',
      '    case "deps":\n      return "This daemon has no update-check operation, so whether a " +\n        "newer release exists cannot be answered here. No release feed " +\n        "was contacted and nothing was changed.";')]]],

  ["the three verbs share one title",
    [["info", (s) => s.replace(
      '    case "install": return "Install an update";',
      '    case "install": return "Check for an update";')]]],

  ["the three verbs share one manual path",
    [["info", (s) => s.replace(
      '    case "deps":\n      return [\n        "To refresh dependencies, do it where the TUI is installed:",\n        "  npm outdated      # what moved",\n        "  npm update        # re-resolve to the newest compatible versions",\n      ];',
      '    case "deps":\n      return [\n        "To see whether a newer release exists, check it where you installed this:",\n        "  npm outdated -g aethercode-tui",\n        "  or read the release notes at the changelog URL below",\n      ];')]]],

  // --- the honest gap -------------------------------------------------------
  ["the install gap no longer says that nothing was written",
    [["info", (s) => s.replace(
      '      return "This daemon has no install operation. Nothing was " +\n        "downloaded, nothing was replaced, and nothing was written.";',
      '      return "This daemon has no install operation.";')]]],

  ["the gap sentence is too short to stand alone",
    [["info", (s) => s.replace(
      '      return "This daemon has no update-check operation, so whether a " +\n        "newer release exists cannot be answered here. No release feed " +\n        "was contacted and nothing was changed.";',
      '      return "No update-check operation.";')]]],

  ["the gap sentence blames the user",
    [["info", (s) => s.replace(
      '      return "This daemon has no update-check operation, so whether a " +\n        "newer release exists cannot be answered here. No release feed " +\n        "was contacted and nothing was changed.";',
      '      return "You need to update the daemon yourself, because you have no " +\n        "update-check operation and you should not expect this panel to " +\n        "do it for you.";')]]],

  ["the panel claims a gap on a daemon that answered",
    [["info", (s) => s.replace(
      "  if (!endpointMissing) {\n    switch (verb) {",
      "  if (false) {\n    switch (verb) {")]]],

  // --- the versions ----------------------------------------------------------
  ["an unreported daemon version becomes 0.0.0",
    [["info", (s) => s.replace(
      "  return typeof v === \"string\" && v.length > 0 ? v : null;",
      "  return typeof v === \"string\" ? v : \"0.0.0\";")]]],

  ["an empty daemon version is treated as a version",
    [["info", (s) => s.replace(
      "  return typeof v === \"string\" && v.length > 0 ? v : null;",
      "  return typeof v === \"string\" ? v : null;")]]],

  ["the panel renders an unreported version as 0.0.0",
    [["info", (s) => s.replace(
      '      facts.daemonVersion ?? "not reported by the daemon",',
      '      facts.daemonVersion ?? "0.0.0",')]]],

  ["the panel drops the TUI version row",
    [["info", (s) => s.replace(
      '    ["tui version", facts.tuiVersion || "not reported"],',
      "")]]],

  ["the panel drops the daemon's own report",
    [["info", (s) => s.replace(
      "  for (const [k, v] of facts.reported) rows.push([k, v]);",
      "")]]],

  ["the panel drops the daemon's own reason",
    [["info", (s) => s.replace(
      '    rows.push(["daemon said", facts.reason]);',
      "")]]],

  ["the flat report lets nested blobs through",
    [["info", (s) => s.replace(
      "    .filter(([, v]) => v === null || typeof v !== \"object\")\n",
      "")]]],

  // --- the changelog ---------------------------------------------------------
  ["the changelog URL reverts to the upstream project",
    [["info", (s) => s.replace(
      'export const CHANGELOG_URL = "https://github.com/maijun-sec/AetherCode/releases";',
      'export const CHANGELOG_URL = "https://github.com/langchain-ai/deepagents/releases";')]]],

  ["UpdateAvailable regains the upstream changelog URL",
    [["available", (s) => s.replace(
      'export const DEFAULT_CHANGELOG_URL =\n  "https://github.com/maijun-sec/AetherCode/releases";',
      'export const DEFAULT_CHANGELOG_URL =\n  "https://github.com/langchain-ai/deepagents/releases";')]]],

  // --- the panel must not offer an action that pretends ---------------------
  ["the panel grows an install action",
    [["panel", (s) => s.replace(
      "  onChangelog: () => void;",
      "  onChangelog: () => void;\n  onInstall?: () => void;")]]],

  // --- the registry ----------------------------------------------------------
  ["the registry row goes back to unavailable on Ink",
    [["intents", (s) => s.replace(
      "    ink: 'mounted',\n    line: 'rendered',\n    gapNote:",
      "    ink: 'unavailable',\n    line: 'rendered',\n    blocker: 'backend-missing',\n    gapNote:")]]],

  ["the registry row keeps a blocker it no longer has",
    [["intents", (s) => s.replace(
      "    component: 'components/UpdatePanel.tsx',",
      "    component: 'components/UpdatePanel.tsx',\n    blocker: 'not-wired',")]]],

  ["the registry row drops the gap note",
    [["intents", (s) => s.replace(
      "    gapNote:\n      'R374:", "    gapNote:\n      'XXX:")]]],

  // --- reachability ----------------------------------------------------------
  ["the Ink panel is not mapped from its intent",
    [["tui", (s) => s.replace(
      '"update-available": "update",', '"update-available": "updateXXX",')]]],

  ["the Ink panel has no render case",
    [["tui", (s) => s.replace(
      '      case "update": {', '      case "updateXXX": {')]]],

  ["the Ink panel hard-codes the endpoint instead of probing the verb's",
    [["tui", (s) => s.replace(
      "      const method = UPDATE_ENDPOINTS[verb];",
      '      const method = "update/check";')]]],

  ["the Ink panel never asks the daemon for its version",
    [["tui", (s) => s.replace(
      '      const ping = client.request("ping", undefined, { timeoutMs: 10_000 }).then(',
      '      const ping = Promise.resolve({ ok: true as const, raw: {} }).then(')]]],

  ["the Ink panel stops detecting a missing method",
    [["tui", (s) => s.replace(
      "          endpointMissing: p.state === \"err\" && isMissingMethod(p.err),",
      "          endpointMissing: p.state === \"err\",")]]],

  ["the missing-method test matches the MESSAGE, not the code",
    [["tui", (s) => s.replace(
      "  return e instanceof RpcCallError && e.code === -32601;",
      "  return e instanceof RpcCallError && String(e.message).includes(\"method not found\");")]]],

  ["the missing-method test uses the wrong JSON-RPC code",
    [["tui", (s) => s.replace(
      "  return e instanceof RpcCallError && e.code === -32601;",
      "  return e instanceof RpcCallError && e.code === -32602;")]]],

  ["line mode collapses the three sentinels onto the check verb",
    [["line", (s) => s.replace(
      '      await panelUpdate(ctx, "install");',
      '      await panelUpdate(ctx, "check");')]]],

  ["line mode gives the deps sentinel the install verb",
    [["line", (s) => s.replace(
      '      await panelUpdate(ctx, "deps");',
      '      await panelUpdate(ctx, "install");')]]],

  ["line mode stops using the shared copy",
    [["line", (s) => s.replace(
      '  const model = buildUpdatePanel(verb, {\n    tuiVersion: TUI_VERSION,\n    daemonVersion: ping.ok ? daemonVersionOf(ping.value) : null,\n    endpointMissing: r.missing,\n    reason: r.ok ? null : r.reason,\n    reported: r.ok ? scalarRows(r.value) : [],\n  });',
      '  const model = { title: verb, endpoint: endpoint, endpointMissing: r.missing, headline: String(r.reason), rows: [] as Array<[string, string]>, manual: [] as string[], changelogUrl: "" };')]]],

  // --- the version constant -------------------------------------------------
  ["the version constant drifts from package.json",
    [["version", (s) => s.replace(
      'export const TUI_VERSION = "0.2.1";',
      'export const TUI_VERSION = "0.2.0";')]]],

  ["the CLI banner regains its own version string",
    [["actui", (s) => s.replace(
      'import { TUI_VERSION as VERSION } from "./version.js";',
      'const VERSION = "0.2.1";')]]],

  // --- the reducer -----------------------------------------------------------
  ["the reducer drops the probe's answer",
    [["state", (s) => s.replace(
      '      return { ...state, updateFacts: action.facts };',
      '      return { ...state, updateFacts: null };')]]],

  ["openPanel ignores the verb it was opened with",
    [["state", (s) => s.replace(
      '        panelUpdateVerb: action.updateVerb ?? "check",',
      '        panelUpdateVerb: "check",')]]],

  ["closePanel leaves the verb behind",
    [["state", (s) => s.replace(
      '        panelUpdateVerb: "check",\n        panelStatus: EMPTY_PANEL_STATUS,',
      '        panelUpdateVerb: state.panelUpdateVerb,\n        panelStatus: EMPTY_PANEL_STATUS,')]]],

  // --- the daemon dependency -------------------------------------------------
  ["the daemon's ping stops answering a version",
    [["methods", (s) => s.replace(
      '        r.put("version",',
      '        r.put("versionRemoved",')]]],

  // --- the catalog -----------------------------------------------------------
  ["the catalog stops advertising the install verb",
    [["commands", (s) => s.replace(
      '{ name: "update", args: "[install]", description: "check for a newer release (add \'install\' to see how to apply one)" },',
      '{ name: "update", description: "check for a newer release" },')]]],

  ["the catalog promises an update the daemon cannot have",
    [["commands", (s) => s.replace(
      '{ name: "update", args: "[install]", description: "check for a newer release (add \'install\' to see how to apply one)" },',
      '{ name: "update", args: "[install]", description: "review an available update" },')]]],

  ["the catalog promises a dependency confirmation",
    [["commands", (s) => s.replace(
      '{ name: "update-deps", description: "check how to refresh dependencies" },',
      '{ name: "update-deps", description: "confirm a dependency refresh" },')]]],

  // --- GATE mutations: does an edit here mask a real defect? --------------
  // Each of these neuters ONE assertion and introduces a defect that a
  // SECOND assertion can still see. If the gate goes green, the
  // property had only one derivation and the neutered assertion was
  // load-bearing after all.
  ["GATE: neuter the verb-mapping test + collapse the install verb",
    [["gate", (s) => s.replace(
      '  assert.equal(updateVerbForSentinel("__UPDATE_INSTALL__"), "install");',
      '  assert.ok(true, "checked elsewhere");'),
      (s) => s.replace(
        '  assert.equal(updateVerbForSentinel("__UPDATE_DEPS__"), "deps");',
        '  assert.ok(true, "checked elsewhere");')],
     ["info", (s) => s.replace(
       '  if (sentinel === "__UPDATE_INSTALL__") return "install";',
       '  if (sentinel === "__UPDATE_INSTALL__") return "check";')]]],

  ["GATE: neuter the unreported-version test + invent 0.0.0",
    [["gate", (s) => s.replace(
      '  assert.equal(daemonVersionOf({ version: null }), null);',
      '  assert.ok(true, "checked elsewhere");')],
     ["info", (s) => s.replace(
       "  return typeof v === \"string\" && v.length > 0 ? v : null;",
       "  return typeof v === \"string\" ? v : \"0.0.0\";")]]],
];

console.log(`r374 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
let noops = 0;
for (const [name, edits] of CASES) {
  const applied = [];
  let broken = null;
  for (const [key, mutate] of edits) {
    const before = original[key];
    const after = mutate(before);
    if (after === before) { broken = key; break; }
    applied.push([key, before, after]);
  }
  if (broken) {
    holes++; noops++;
    console.log(`  ??  ${name}\n      NO-OP on ${broken} — the pattern did not match, so this case tests nothing`);
    continue;
  }

  for (const [key, , after] of applied) writeFileSync(PATHS[key], after, "utf-8");
  const { fail, status, out } = runGate();
  restore(applied);

  const ok = fail > 0 || status !== 0;
  if (!ok) holes++;
  console.log(`  ${ok ? "ok  " : "HOLE"}  ${name}\n      gate fail=${fail} status=${status}  — gate must go red`);
  if (!ok) {
    const lines = out.split(/\r?\n/);
    lines.forEach((l, i) => {
      const m = /^✖ (.+?) \(/.exec(l);
      if (!m) return;
      console.log(`        ✖ ${m[1]}`);
      for (let k = i + 1; k < Math.min(i + 5, lines.length); k++) {
        if (/^\s*(AssertionError|Error):/.test(lines[k])) {
          console.log(`            ${lines[k].trim().slice(0, 220)}`);
          break;
        }
      }
    });
  }
}

function restore(applied) {
  for (const [key, before] of applied) writeFileSync(PATHS[key], before, "utf-8");
}

for (const [k, p] of Object.entries(PATHS)) {
  if (readFileSync(p, "utf-8") !== original[k]) {
    writeFileSync(p, original[k], "utf-8");
    console.log(`  !! restored ${p}`);
  }
}

console.log(`\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}`);
if (noops) console.log(`${noops} case(s) were no-ops and did not test anything`);
process.exit(holes === 0 ? 0 : 1);
