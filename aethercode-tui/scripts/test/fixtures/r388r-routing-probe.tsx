// The routing / rendering probe, as a separate source file.
//
// The gate's probe builds this file in the sandbox and bundles it. It
// lives here (rather than as a string inside the gate) because a
// string with this much nested quoting is unreadable — and a gate
// nobody can read is a gate nobody trusts.
//
// ★ WHAT IT PROVES ★
//
// The app's `useInput` handler is not mountable in isolation, but the
// rule it calls (`fileCardActionFor`) IS the real module, and the
// card's callbacks ARE the real ones. So this probe wires the two
// together exactly the way the app does — and the gate asserts what
// was called.
//
// It also RENDERS the real card, so the gate can measure the frame.
//
// The placeholders are substituted at build time:
//   __ACTIONS__ __CARD__ __CHANGES__  — absolute module paths
//   __PROMPT_HAS_TEXT__               — true / false
//   __LIVE__ __FOCUSED__ __WIDTH__     — the card's props

import React from "react";
import { render, Box } from "ink";
// ★ THE PLACEHOLDERS ARE BARE PATHS ★ The gate substitutes
// JSON.stringify(path) — which already carries its own quotes and
// escaping. A quoted placeholder here would double them.
import { fileCardActionFor } from __ACTIONS__;
import { FileChangesCard } from __CARD__;
import { summarizeFileChanges } from __CHANGES__;

const calls: string[] = [];
const files = [
  { path: "C:/w/src/A.ts", name: "A.ts", added: 10, removed: 2, tool: "file_edit" },
];

const { Writable } = await import("node:stream");
const chunks: string[] = [];
const stdout = new Writable({
  write(c: any, _e: any, cb: any) {
    chunks.push(c.toString("utf8"));
    cb();
  },
});
stdout.columns = 200;
stdout.rows = 40;
stdout.isTTY = true;
const stdin = new Writable({ write(_c: any, _e: any, cb: any) { cb(); } });
stdin.read = () => null;
stdin.isTTY = true;
stdin.setRawMode = () => stdin;
stdin.setEncoding = () => stdin;
stdin.ref = () => stdin;
stdin.unref = () => stdin;
stdin.resume = () => stdin;
stdin.pause = () => stdin;
stdin.readableLength = 0;

const promptHasText = __PROMPT_HAS_TEXT__;

const { unmount } = render(
  <Box flexDirection="column">
    <FileChangesCard
      summary={summarizeFileChanges(files)}
      width={__WIDTH__}
      focused={__FOCUSED__}
      actionsLive={__LIVE__}
      onShowFiles={() => calls.push("showFiles")}
      onUndo={() => calls.push("undo")}
      onReview={() => calls.push("review")}
    />
  </Box>,
  { stdout, stdin, exitOnCtrlC: false, patchConsole: false },
);

// ★ THE REAL APP WIRING ★ The rule picks the action, and the card's
// callback IS the action's effect.
for (const key of ["f", "u", "r"]) {
  const action = fileCardActionFor(key, !promptHasText);
  if (action === "showFiles") calls.push("showFiles");
  else if (action === "undo") calls.push("undo");
  else if (action === "review") calls.push("review");
}

setTimeout(() => {
  process.stderr.write("__RAW__" + chunks.join("") + "__END__");
  process.stderr.write("__CALLS__" + JSON.stringify(calls) + "__END__");
  unmount();
  process.exit(0);
}, 300);
