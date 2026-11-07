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

// Mutation harness for r388l-permission-comment.test.mjs — NOT part of
// npm test. Run:  node scripts/test/r388l-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// The cases come in two families:
//
//   * PRODUCT mutations — the TUI or the daemon is broken.
//
//   * GATE mutations — an assertion is neutered AND a defect that a
//     SECOND assertion can still see is introduced. Those are only
//     meaningful when the defect has two independent derivations, so
//     the gate still catches it with the neutered assertion out of
//     the way. A neutered assertion whose defect only IT could see
//     proves nothing — those are NOT here.
//
// THIS HARNESS REWRITES PRODUCT SOURCE IN PLACE. It must never run
// concurrently with the test suite (R373 Lesson 1166): a battery that
// edits `src/tui.tsx` while `npm test` reads it produces failures
// that belong to neither.

import { readFileSync, writeFileSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
// The gate must be GREEN before anything is mutated — otherwise every
// case reports "caught" for the wrong reason and the battery measures
// nothing. See scripts/test/mutation-baseline.mjs.
import { requireGreenBaseline } from "./mutation-baseline.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r388l-permission-comment.test.mjs"),
  tui: join(root, "src", "tui.tsx"),
  inputBox: join(root, "src", "components", "InputBox.tsx"),
  line: join(root, "src", "line.ts"),
  comment: join(root, "src", "permissionComment.ts"),
  prompter: join(
    repo,
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
  methods: join(
    repo,
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
  executor: join(
    repo,
    "aethercode",
    "aethercode-core",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "core",
    "engine",
    "StreamingToolExecutor.java",
  ),
};

// A battery that is KILLED mid-case must not leave a mutation on disk
// — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r388l-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(
  Object.entries(PATHS).map(([k, p]) => [p, original[k]]),
  SNAPSHOT,
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

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing.
requireGreenBaseline(runGate, "r388l mutation battery");

const CASES = [
  // --- the daemon drops the comment ---------------------------------------
  [
    "the daemon drops the comment on the approval branch (the original defect)",
    [
      [
        "prompter",
        (s) =>
          s.replace(
            "return new PermissionResult.Allow(input, decision.comment());",
            "return new PermissionResult.Allow(input);",
          ),
      ],
    ],
  ],

  [
    "the daemon never reads the comment off the wire",
    [
      [
        "methods",
        (s) =>
          s.replace(
            'String comment = p.get("comment") instanceof String cs ? cs : null;',
            "String comment = null;",
          ),
      ],
    ],
  ],

  [
    "the executor stops appending the note to the tool_result",
    [["executor", (s) => s.replace("appendApprovalNote(output, allowNote)", "output")]],
  ],

  // --- the client never collects a comment ---------------------------------
  [
    "the Ink app lets the decision keys fire while the comment box is open",
    [
      [
        "tui",
        (s) => s.replace("    if (state.commentBox) {", "    if (false && state.commentBox) {"),
      ],
    ],
  ],

  [
    "Tab no longer opens the comment box",
    [
      [
        "tui",
        (s) =>
          s.replace(
            'if (key.tab) {\n        dispatch({ type: "openCommentBox", state: newCommentBox() });',
            'if (false) {\n        dispatch({ type: "openCommentBox", state: newCommentBox() });',
          ),
      ],
    ],
  ],

  [
    'the Tab check grows a literal "\\t" (a phantom chord in the keybinding registry)',
    [
      [
        "tui",
        (s) =>
          s.replace(
            '      if (key.tab) {\n        dispatch({ type: "openCommentBox", state: newCommentBox() });',
            '      if (key.tab || input === "\\t") {\n        dispatch({ type: "openCommentBox", state: newCommentBox() });',
          ),
      ],
    ],
  ],

  [
    "the InputBox swallows the comment (the decisionPending guard wins)",
    [["inputBox", (s) => s.replace("    if (commentBox) {", "    if (false && commentBox) {")]],
  ],

  [
    "the line picker never opens a comment field",
    [
      [
        "line",
        // ★ Anchored on the handler that OPENS the comment field, by
        // what it DOES. A plain `replace('if (c === "\t") {', ...)`
        // hits the FIRST occurrence — and the first occurrence is the
        // no-op Tab INSIDE the comment mode (whose 10-space indent
        // still CONTAINS the 8-space pattern as a substring, so
        // String.replace matches it). The mutation then edited the
        // wrong handler and the gate stayed green — a mutation that
        // changes the wrong occurrence is a no-op in disguise.
        (s) =>
          s.replace(
            /if \(c === "\\t"\) \{\n(\s*)commentMode = true;/,
            "if (false) {\n$1commentMode = true;",
          ),
      ],
    ],
  ],

  // --- the wire shape breaks ----------------------------------------------
  [
    "the wire always sends a comment field, even when empty (breaks backward compat)",
    [["comment", (s) => s.replace("  if (c) params.comment = c;", '  params.comment = c ?? "";')]],
  ],

  [
    "the comment is folded into the reason (boilerplate becomes the user's voice)",
    [
      [
        "comment",
        (s) =>
          s.replace(
            "  const c = comment?.trim();\n  if (c) params.comment = c;",
            "  const c = comment?.trim();\n  params.reason = c ?? params.reason;",
          ),
      ],
    ],
  ],

  // --- the cancel path starts answering ------------------------------------
  [
    "Esc in the comment box also answers the ask",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '      if (key.escape) {\n        dispatch({ type: "closeCommentBox" });\n        return;\n      }',
            '      if (key.escape) {\n        dispatch({ type: "closeCommentBox" });\n' +
              "        if (state.permissionAsk) {\n" +
              "          replyPermission(client, state.permissionAsk, box.armed, dispatch, box.scope, box.text);\n" +
              "        }\n" +
              "        return;\n" +
              "      }",
          ),
      ],
    ],
  ],

  // --- the ONE registry starts being two ----------------------------------
  [
    "the scope moves back into a second list (the drift Lesson 1207 warns about)",
    [
      [
        "comment",
        (s) =>
          s.replace(
            "export const DECISION_KEYS: ReadonlyArray<{",
            "export const DECISION_SCOPE: Readonly<Record<string, RememberScope | undefined>> = {\n" +
              '  a: undefined,\n  t: "session",\n  p: "project",\n  u: "user",\n' +
              '  y: "session",\n  d: undefined,\n  n: "session",\n};\n\n' +
              "export const DECISION_KEYS: ReadonlyArray<{",
          ),
      ],
    ],
  ],

  // --- the modifier chord also types into the comment ----------------------
  [
    "arming the decision also types the letter into the comment",
    [
      [
        "inputBox",
        (s) =>
          s.replace("      if (key.ctrl || key.meta || key.tab) {\n        return;\n      }", ""),
      ],
    ],
  ],
];

function restore() {
  for (const [k, p] of Object.entries(PATHS)) {
    if (k === "gate") continue;
    writeFileSync(p, original[k], "utf-8");
  }
}

let survived = 0;
for (const [label, edits] of CASES) {
  restore();
  let applied = 0;
  for (const [key, fn] of edits) {
    const path = PATHS[key];
    const before = readFileSync(path, "utf-8");
    const after = fn(before);
    if (after === before) {
      console.error(`FAIL [${label}]: the mutation was a NO-OP on ${key}`);
      survived++;
      continue;
    }
    writeFileSync(path, after, "utf-8");
    applied++;
  }
  if (applied === 0) continue;
  const { fail, out } = runGate();
  if (fail > 0) {
    console.log(`OK   caught: ${label}`);
  } else {
    console.error(`FAIL SURVIVED: ${label}`);
    survived++;
  }
  void out;
}

restore();
rmSync(SNAPSHOT, { force: true });

if (survived > 0) {
  console.error(`\n${survived} mutation(s) survived — the gate is not a gate.`);
  process.exit(1);
}
console.log(`\nall ${CASES.length} mutations were caught.`);
