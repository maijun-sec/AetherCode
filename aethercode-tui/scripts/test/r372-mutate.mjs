// Mutation harness for r372-transcript-replay.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r372-mutate.mjs
//
// The converter is a pure function, so most of these mutations are
// BEHAVIOURAL: they change what the replay produces, not what the
// source says. A source pin cannot see "the tool result became its
// own turn" — only running it can.
//
// The three the module explicitly refuses to do are the first group:
// inventing a duration, inventing a success, and hiding the messages
// that were left out.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const PATHS = {
  gate: join(here, "r372-transcript-replay.test.mjs"),
  hyd: join(root, "src", "transcriptHydrate.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
};

const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", PATHS.gate], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

const CASES = [
  // --- the three inventions the module refuses --------------------------
  ["replayed tool calls claim a duration",
    [["hyd", (s) => s.replace(
      "          toolStatus: \"ok\",\n          ts,",
      "          toolStatus: \"ok\",\n          toolStartedAt: ts,\n          toolEndedAt: ts + 1,\n          ts,")]]],

  ["a tool call with no result is reported as a clean success",
    [["hyd", (s) => s.replace(
      '      turn.toolResult = "(no recorded result — the run was interrupted)";',
      '      turn.toolResult = "";')]]],

  ["the count of messages left out is dropped",
    [["hyd", (s) => s.replace(
      "  return { turns, totalMessages, replayedMessages: slice.length };",
      "  return { turns, totalMessages: slice.length, replayedMessages: slice.length };")]]],

  // --- pairing -----------------------------------------------------------
  ["a tool_result becomes its own turn",
    [["hyd", (s) => s.replace(
      '        // Handled in the second pass — the owning tool_use is in an\n        // earlier message.\n        continue;',
      '        turns.push({ id: nextId++, role: "tool", text: toolResultText(b.content), ts, collapsed: true, previewChars: 80 });')]]],

  ["the tool_result is never matched to its tool_use",
    [["hyd", (s) => s.replace(
      "      const turn = owner.get(useId);\n      if (!turn) continue;",
      "      const turn = undefined;\n      if (!turn) continue;")]]],

  // The first draft aimed this at a transcript whose ONLY message is
  // the orphan. There, `turns[turns.length - 1]` is undefined and the
  // behaviour is unchanged — the case passed against broken code. The
  // mutation only differs when the orphan FOLLOWS a real call, which
  // is the case that can contaminate it.
  ["an orphan tool_result overwrites the previous call's result",
    [["hyd", (s) => s.replace(
      "      const turn = owner.get(useId);\n      if (!turn) continue;",
      "      const turn = turns[turns.length - 1];\n      if (!turn) continue;")]]],

  ["the error flag is dropped (every tool reports ok)",
    [["hyd", (s) => s.replace(
      'turn.toolStatus = b.isError === true ? "error" : "ok";',
      'turn.toolStatus = "ok";')]]],

  // --- shapes ------------------------------------------------------------
  ["the wrapped envelope is not unwrapped",
    [["hyd", (s) => s.replace(
      "    if (Array.isArray(r.messages)) return r.messages;",
      "    if (Array.isArray(r.messages)) return [];")]]],

  ["an unrecognised tool_result shape is dropped instead of stringified",
    [["hyd", (s) => s.replace(
      "    try {\n      return JSON.stringify(content);\n    } catch {\n      return \"\";\n    }",
      "    return \"\";")]]],

  // The first draft added a guard on `Array.isArray(part)` — but
  // `part` is an ELEMENT of the array and never an array itself, so
  // the guard could never fire and the case passed against broken
  // code. Short-circuit the array branch where it actually is.
  ["the Anthropic content-block array is not read",
    [["hyd", (s) => s.replace(
      "  if (Array.isArray(content)) {",
      "  if (Array.isArray(content)) { return \"\";")]]],

  ["blank text blocks produce turns",
    [["hyd", (s) => s.replace(
      "        if (text.trim().length === 0) continue;",
      "        if (false) continue;")]]],

  ["a missing timestamp becomes a plausible number",
    [["hyd", (s) => s.replace(
      "      : 0;",
      "      : Date.now();")]]],

  // --- the tail, not the head -------------------------------------------
  ["the replay keeps the OLDEST messages",
    [["hyd", (s) => s.replace(
      "  const from = Math.max(0, totalMessages - Math.max(1, limit));",
      "  const from = 0;")]]],

  ["the replay limit is ignored",
    [["hyd", (s) => s.replace(
      "  const slice = all.slice(from);",
      "  const slice = all;")]]],

  // --- wiring ------------------------------------------------------------
  ["the Ink picker stops reading the transcript",
    [["tui", (s) => s.replace(
      '.request("getTranscript", {} as RpcValue)',
      '.request("getNothing", {} as RpcValue)')]]],

  ["line mode stops reading the transcript",
    [["line", (s) => s.replace(
      'await probe(ctx, "getTranscript")',
      'await probe(ctx, "getNothing")')]]],

  ["the replay is fetched but not adopted (the scrollback is cleared and never refilled)",
    [["tui", (s) => s.replace(
      'dispatch({ type: "adoptSession", sessionId: adopted, turns: replay.turns });',
      'dispatch({ type: "adoptSession", sessionId: adopted });')]]],

  ["the switch is adopted twice (one dispatch leaves an empty scrollback)",
    [["tui", (s) => s.replace(
      'dispatch({ type: "adoptSession", sessionId: adopted, turns: replay.turns });',
      'dispatch({ type: "adoptSession", sessionId: adopted });\n                      dispatch({ type: "adoptSession", sessionId: adopted, turns: replay.turns });')]]],

  ["the failure toast claims the history IS there (the opposite lie)",
    [["tui", (s) => s.replace(
      "its history could not be read", "its history is replayed")]]],

  ["a failed replay is not reported",
    [["tui", (s) => s.replace(
      "text: `now working in ${adopted} — its history could not be read (${errMsg(e)})`,",
      "text: `now working in ${adopted}`,")]]],

  ["the toast goes back to claiming the history is absent",
    [["tui", (s) => s.replace(
      "text: `now working in ${adopted} — its history could not be read (${errMsg(e)})`,",
      "text: `now working in ${adopted} — history is not replayed here`,")]]],

  // --- the reducer -------------------------------------------------------
  ["adoptSession ignores the supplied transcript",
    [["state", (s) => s.replace(
      "      const nextTurns = action.turns ?? [];",
      "      const nextTurns: Turn[] = [];")]]],

  ["adoptSession KEEPS the old turns when no transcript is supplied",
    [["state", (s) => s.replace(
      "      const nextTurns = action.turns ?? [];",
      "      const nextTurns = action.turns ?? state.turns;")]]],

  // --- gate mutations: does an edit here mask a real defect? ------------
  // The first draft neutered the `toolStartedAt` assertion and then
  // introduced a defect that only the `toolEndedAt` assertion could
  // see — a single point of failure BY CONSTRUCTION, so the case
  // proved nothing. Aim it at a rule that has two independent
  // assertions: the orphan must not become a turn, and must not
  // contaminate the call before it.
  ["GATE: neuter the orphan-dropped check + let the orphan render",
    [["gate", (s) => s.replace(
      "  assert.equal(r.turns.length, 0, \"an orphan tool_result produced a turn\");",
      "  assert.ok(true, \"orphan handling checked elsewhere\");")],
     ["hyd", (s) => s.replace(
       "      if (type === \"tool_result\") {",
       "      if (type === \"tool_result\") { turns.push({ id: nextId++, role: \"tool\", text: \"orphan\", ts, collapsed: true, previewChars: 80 }); }\n      if (type === \"tool_result\") {")]]],

  ["GATE: neuter the atomicity check + clear without refilling",
    [["gate", (s) => s.replace(
      "  assert.ok(/turns:\\s*replay\\.turns/.test(adopt[0]),",
      "  assert.ok(true, \"atomicity checked elsewhere\");")],
     ["tui", (s) => s.replace(
       'dispatch({ type: "adoptSession", sessionId: adopted, turns: replay.turns });',
       'dispatch({ type: "adoptSession", sessionId: adopted });')]]],
];

console.log(`r372 mutation battery — ${CASES.length} cases\n`);

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
process.exit(holes === 0 ? 0 : 1);