// The R389 CHAT-COLUMN probe.
//
// ★ WHAT IT PROVES ★
//
// The chat column is the desktop's flat document: a `##` heading per
// sub-task, a folded block per think, a folded row per tool call, and
// a `结果` block per finished sub-task.
//
// The rules that decide THAT are pure functions in
// `src/chat/blocks.ts`. A grep for `buildBlocks` proves the string is
// in the file — it proves nothing about what the transcript actually
// looks like. So the probe runs the REAL module on a REAL transcript
// and reports what it produced; the gate asserts on the VALUES.
//
// The placeholders are substituted at build time:
//   __BLOCKS__ — the real `src/chat/blocks.ts` module

import {
  buildEditSnippetFromInput,
  buildTimeline,
  finalThinkIndex,
  resolveOpen,
  summarizeDiff,
  thinkPreview,
  toolPreview,
} from __BLOCKS__;

/**
 * ★ THE TRANSCRIPT ★
 *
 * Two sub-tasks, one preamble-free flow:
 *
 *   1  user      "do the thing"
 *   2  thinking  "plan: step one"          (in sub-task 0:a)
 *   3  tool      file_read src/App.tsx     (in sub-task 0:a)
 *   4  thinking  "final reasoning"         (in sub-task 0:a) ← the FINAL think
 *   5  assistant "here is the answer"      (in sub-task 0:a)
 *   6  tool      bash npm test             (in sub-task 0:b)
 *
 * Sub-task 0:a is CLOSED (it has a summary → a 结果 block); 0:b is
 * still open (no summary, no 结果 block).
 *
 * ★ TWO THINKS, NOT ONE ★ The desktop's rule distinguishes the
 * FINAL think (always open) from the earlier ones (folded). A
 * fixture with a single think cannot tell the two rules apart — every
 * think would be the final one.
 */
const turns = [
  { id: 1, role: "user", text: "do the thing", ts: 1000, collapsed: false, previewChars: 240 },
  {
    id: 2,
    role: "thinking",
    text: "**plan**: step one\nstep two",
    ts: 1100,
    collapsed: true,
    previewChars: 80,
    subTaskId: "0:a",
  },
  {
    id: 3,
    role: "tool",
    text: "file_read",
    toolName: "file_read",
    toolArgs: JSON.stringify({ file_path: "src/App.tsx" }),
    toolStatus: "ok",
    ts: 1200,
    collapsed: true,
    previewChars: 80,
    subTaskId: "0:a",
  },
  {
    id: 4,
    role: "thinking",
    text: "final reasoning: the answer is X",
    ts: 1250,
    collapsed: true,
    previewChars: 80,
    subTaskId: "0:a",
  },
  {
    id: 5,
    role: "assistant",
    text: "here is the answer",
    ts: 1300,
    collapsed: false,
    previewChars: 240,
    subTaskId: "0:a",
  },
  {
    id: 6,
    role: "tool",
    text: "bash",
    toolName: "bash",
    toolArgs: JSON.stringify({ command: "npm test" }),
    toolStatus: "ok",
    ts: 1400,
    collapsed: true,
    previewChars: 80,
    subTaskId: "0:b",
  },
];

const subTasks = [
  {
    id: "0:a",
    taskId: 0,
    subTaskId: "a",
    content: "read the source",
    status: "completed",
    summary: "read it and understood it",
    startedAt: 1050,
  },
  {
    id: "0:b",
    taskId: 0,
    subTaskId: "b",
    content: "run the tests",
    status: "in_progress",
    startedAt: 1350,
  },
];

const timeline = buildTimeline(turns, subTasks);

// The fold decisions, per group — both while the run is idle and
// while it is live.
const groups = timeline
  .filter((ev) => ev.kind === "agent")
  .map((ev) => {
    const group = ev.group;
    const finalIdx = finalThinkIndex(group.blocks);
    return {
      id: group.id,
      kind: group.kind,
      title: group.title,
      status: group.status,
      blocks: group.blocks.map((b, i) => ({
        kind: b.kind,
        id: b.id,
        turnId: "turnId" in b ? b.turnId : null,
        title: "title" in b ? b.title : null,
        status: "status" in b ? b.status : null,
        isLast: i === group.blocks.length - 1,
        isFinalThink: i === finalIdx,
        idleOpen: resolveOpen(b, {
          turn: "turnId" in b ? turns.find((t) => t.id === b.turnId) : undefined,
          isLast: i === group.blocks.length - 1,
          isFinalThink: i === finalIdx,
          isLive: false,
          collapseAll: false,
        }),
        liveOpen: resolveOpen(b, {
          turn: "turnId" in b ? turns.find((t) => t.id === b.turnId) : undefined,
          isLast: i === group.blocks.length - 1,
          isFinalThink: i === finalIdx,
          isLive: true,
          collapseAll: false,
        }),
      })),
    };
  });

// The diff a `file_edit` produces from its OWN input.
const diff = buildEditSnippetFromInput({
  file_path: "src/App.tsx",
  old_string: "const a = 1;\nconst b = 2;",
  new_string: "const a = 1;\nconst b = 3;",
});

process.stderr.write(
  "__R389__" +
    JSON.stringify({
      timeline: timeline.map((ev) =>
        ev.kind === "agent"
          ? { kind: "agent", id: ev.group.id }
          : { kind: ev.kind, turnId: ev.turn.id },
      ),
      groups,
      thinkPreview: thinkPreview("**plan**: step one\nstep two"),
      toolPreview: toolPreview("file_read", JSON.stringify({ file_path: "src/App.tsx" })),
      bashPreview: toolPreview("bash", JSON.stringify({ command: "npm test" })),
      diffSegments: summarizeDiff(diff ?? "").map((s) => s.kind),
      diffHasHeader: (diff ?? "").includes("--- a/src/App.tsx"),
    }) +
    "__END__",
);
process.exit(0);
