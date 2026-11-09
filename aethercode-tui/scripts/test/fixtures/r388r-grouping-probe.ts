// The GROUPING probe, as a separate source file.
//
// ★ WHAT IT PROVES ★
//
// The card groups CONSECUTIVE file-changing turns, and the app finds
// the card a key means. If the two used different rules, a key would
// act on a card the user is NOT looking at — the worst possible
// outcome, because the user sees a correct card and a wrong action.
//
// The previous version of this gate only checked that the string
// `fileChangeOfTurnParts` APPEARS in the Scrollback. A mutation that
// replaced the Scrollback's rule with `return null` — which is
// exactly the defect this rule exists to prevent — left the string
// in place, so the gate stayed green.
//
// So the probe runs BOTH sides' REAL rules on the SAME turns and
// reports the group ids, and the gate asserts on the VALUES.
//
// ★ THE CARD'S SIDE IS THE REAL `groupTurns` ★ Not a re-implementation
// of it. A probe that re-implements the rule it is testing measures
// the probe, not the product.
//
// The placeholders are substituted at build time:
//   __CHANGES__   — the shared rules module
//   __SCROLLBACK__ — the Scrollback component module

import { fileChangeGroupIdAt, fileChangeOfTurnParts } from __CHANGES__;
import { groupTurns } from __SCROLLBACK__;

// ★ THE TURNS ★ Three kinds, in order:
//   1. a file write          (id 1)
//   2. a file write          (id 2) — continues the group
//   3. a bash command        (id 3) — BREAKS the group
//   4. a file write          (id 4) — starts a new group
//
// ★ THE ARGUMENT KEY IS `file_path` ★ Not `path` — the daemon's tool
// input names the file `file_path`, and `diffStatsFor` reads that key.
// (A first version of this fixture used `path`, and the probe
// reported NO file changes at all — which looked like a broken rule
// and was really a broken fixture.)
const turns = [
  { id: 1, role: "tool", toolName: "file_write", toolArgs: JSON.stringify({ file_path: "a.ts", content: "x\n" }) },
  { id: 2, role: "tool", toolName: "file_write", toolArgs: JSON.stringify({ file_path: "b.ts", content: "y\n" }) },
  { id: 3, role: "tool", toolName: "bash", toolArgs: JSON.stringify({ command: "npm test" }) },
  { id: 4, role: "tool", toolName: "file_write", toolArgs: JSON.stringify({ file_path: "c.ts", content: "z\n" }) },
];

// ★ THE APP'S SIDE ★ The app walks BACKWARDS to the most recent
// file-changing turn and asks the shared rule for that turn's group.
const appGroup: number | undefined = (() => {
  for (let i = turns.length - 1; i >= 0; i--) {
    if (fileChangeOfTurnParts(turns[i].role, turns[i].toolName, turns[i].toolArgs)) {
      return fileChangeGroupIdAt(turns, i);
    }
  }
  return undefined;
})();

// ★ THE CARD'S SIDE ★ The Scrollback's REAL grouping function, run on
// the same turns. Its file-change groups are the cards the user sees.
const cardGroups: number[] = groupTurns(turns)
  .filter((g) => g.changes.length > 0)
  .map((g) => g.turn.id);

process.stderr.write(
  "__GROUPS__" +
    JSON.stringify({
      appGroup: appGroup ?? null,
      cardGroups,
      groupsOfEveryTurn: turns.map((_, i) => fileChangeGroupIdAt(turns, i) ?? null),
    }) +
    "__END__",
);
process.exit(0);
