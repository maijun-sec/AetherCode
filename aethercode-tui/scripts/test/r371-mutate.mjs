// Mutation harness for r371-context-meter.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r371-mutate.mjs
//
// `/context` has been broken in three independent ways and each one
// LOOKED finished. So this battery attacks all three layers plus the
// gate's own extractions — the last group matters because five of the
// seven fixes in this round were to the TEST, not the product, and a
// test that was wrong six times needs its own scrutiny.
//
// Every case reports NO-OP separately from PASS. A regex that stops
// matching is a case that tests nothing.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r371-context-meter.test.mjs"),
  commands: join(root, "src", "commands.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  intents: join(root, "src", "panelIntents.ts"),
  sb: join(root, "src", "components", "StatusBar.tsx"),
  meter: join(root, "src", "components", "ContextMeter.tsx"),
  runner: join(repo, "aethercode", "aethercode-cli", "src", "main", "java",
    "org", "aethercode", "cli", "DaemonRunner.java"),
  http: join(repo, "aethercode", "aethercode-protocol", "src", "main", "java",
    "org", "aethercode", "protocol", "http", "HttpJsonRpcServer.java"),
};


// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r371-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(Object.entries(PATHS).map(([k, p]) => [p, original[k]]), SNAPSHOT);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", PATHS.gate], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

const CASES = [
  // --- layer 1: CompactMethods was never constructed ---------------------
  ["drop the stdio registration of CompactMethods",
    [["runner", (s) => s.replace("compactRpcs.registerAll(server.dispatcher());", "")]]],

  ["drop the HTTP setCompactMethods call",
    [["runner", (s) => s.replace("http.setCompactMethods(httpCompactRpcs);", "")]]],

  ["hand CompactMethods a null compactor on the HTTP path",
    [["runner", (s) => s.replace("? sdkHttp.compactor()", "? null")]]],

  ["drop the compact/status case arm from the HTTP switch",
    [["http", (s) => s.replace(/\s*case "compact\/status"\s*->\s*routeCompact\(m -> m\.status\(params\)\);/, "")]]],

  ["drop the compact/run case arm from the HTTP switch",
    [["http", (s) => s.replace(/\s*case "compact\/run"\s*->\s*routeCompact\(m -> m\.run\(params\)\);/, "")]]],

  // Single-line, CRLF-safe on purpose. The first two drafts of this
  // case matched across a newline and went NO-OP — which is how a case
  // that tests nothing gets reported as a hole.
  ["remove routeCompact's unwired guard (a dead branch the old /throw/ pin could not see)",
    [["http", (s) => s.replace("if (cm == null) {", "if (false) {")]]],

  // --- layer 2: the State fields had no writers -------------------------
  ["setContextInfo stops writing contextMaxTokens",
    [["state", (s) => s.replace(/return \{ \.\.\.state, contextInfo: info, contextMaxTokens: info\.contextWindow \};/,
      "return { ...state, contextInfo: info };")]]],

  ["setCompactStatus stops writing autoCompactDisabled",
    [["state", (s) => s.replace(/return \{ \.\.\.state, autoCompactDisabled: action\.autoCompactDisabled, lastCompactTs \};/,
      "return { ...state, lastCompactTs };")]]],

  ["lastCompactTs can rewind",
    [["state", (s) => s.replace("next != null && (state.lastCompactTs == null || next > state.lastCompactTs)",
      "next != null")]]],

  ["the poll adopts a zero contextWindow",
    [["tui", (s) => s.replace("if (window <= 0) return;", "")]]],

  ["the poll stops swallowing getContextInfo failures",
    [["tui", (s) => s.replace(/catch \{ \/\* best-effort \*\/ \}/, "catch (e) { throw e; }")]]],

  // --- layer 3: nothing emitted the sentinel ----------------------------
  ["delete the /context command",
    [["commands", (s) => s.replace(/case "context":\n\s*case "ctx": \{[\s\S]*?\n\s*\}\n/, "")]]],

  ["the registry row stays a no-command placeholder",
    [["intents", (s) => s.replace(/id: 'context-meter',[\s\S]*?\n    ink: 'mounted',/,
      "id: 'context-meter',\n    ink: 'unavailable',")]]],

  ["PANEL_BY_INTENT drops the context-meter entry",
    [["tui", (s) => s.replace('"context-meter": "context",', "")]]],

  ["delete the line-mode __CONTEXT_METER__ arm",
    [["line", (s) => s.replace(/case "__CONTEXT_METER__":[\s\S]*?return true;\n/, "")]]],

  ["delete the `case \"context\":` render branch",
    [["tui", (s) => s.replace(/case "context": \{[\s\S]*?\n      \}\n\n      case "grants": \{/, 'case "grants": {')]]],

  // --- StatusBar reverts to the T-441 half-finished state ---------------
  ["StatusBar stops importing ContextMeter",
    [["sb", (s) => s.replace(/import \{ ContextMeter, type ContextInfo \} from "\.\/ContextMeter\.js";/, "")]]],

  ["StatusBar stops rendering the meter",
    [["sb", (s) => s.replace(/<ContextMeter[\s\S]*?\/>/, "")]]],

  ["the meter goes back to a 200-char default (off an 80-column terminal)",
    [["sb", (s) => s.replace("width={24}", "width={200}")]]],

  ["ContextInfo.inputTokens is coerced to 0 (the invented measurement)",
    [["sb", (s) => s.replace("inputTokens: state.inputTokens,", "inputTokens: state.inputTokens ?? 0,")]]],

  ["the meter loses its no-measurement label",
    [["meter", (s) => s.replace("no usage reported yet", "0%")]]],

  // --- gate mutations: does an edit here mask a real defect? ------------
  // Each pairs a gate edit with a genuine defect and must STILL go red.
  ["GATE: neuter the HTTP case-arm loop + drop the compact/run arm",
    [["gate", (s) => s.replace(/for \(const name of \["compact\/run"[\s\S]*?\n  \}\n/, "")],
     ["http", (s) => s.replace(/\s*case "compact\/run"\s*->\s*routeCompact\(m -> m\.run\(params\)\);/, "")]]],

  ["GATE: neuter the renderers-claim check + delete the line arm",
    [["gate", (s) => s.replace("const arm = /case", "const arm = /x-never-emitted-x/ && /case")],
     ["line", (s) => s.replace(/case "__CONTEXT_METER__":[\s\S]*?return true;\n/, "")]]],

  ["GATE: stop reading the DAO's compactor + null it on stdio",
    [["gate", (s) => s.replace(".test(runInternal) &&", ".test(runInternal || null ||")],
     ["runner", (s) => s.replace("? sdk.compactor()", "? null")]]],

  // Overload blindness: the extraction that read runHttp's DELEGATE.
  // Reinstating a delegating overload in front of the implementation
  // must not make the gate read the wrong method — that is the failure
  // this gate shipped with twice.
  ["GATE: add a delegating runHttp overload in front of the real one",
    [["gate", (s) => s.replace('const runHttp = javaMethod(runnerCode, RUN_HTTP_IMPL, 2);',
      'const runHttp = javaMethod(runnerCode, RUN_HTTP_IMPL, 1);')]],
    // ...and the paired real defect: remove the HTTP wiring entirely.
    ["runner", (s) => s.replace("http.setCompactMethods(httpCompactRpcs);", "")]],
];

console.log(`r371 mutation battery — ${CASES.length} cases\n`);

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
  const { fail, out } = runGate();
  restore(applied);

  const ok = fail > 0;
  if (!ok) holes++;
  console.log(`  ${ok ? "ok  " : "HOLE"}  ${name}\n      gate fail=${fail}  — gate must go red`);
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
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);