# R267 — desktop polish (3 issues, 1 round)

Three independent user-reported desktop issues fixed in a single
round because they share the same test surface (the React store +
MessageList renderer) and the same round-number bucket (R267).

## Issue 1 — step splitting on tool→text boundary

**User complaint (2026-09-14 19:48 screenshot)**: "all thinking on
top, all tools at the bottom". The chat transcript always rendered
as one big thinking block followed by all tool calls, regardless
of how many model-thinks-between-tools cycles actually happened.

**Root cause**: `ChatStep` accumulates ALL `text_delta` + ALL
`tool_use_start` events for the entire run, so
`MessageList.buildBlocks` emits exactly one
`[think][tool][tool]...` group per sub-task. The interleaving
logic in `buildBlocks` is correct; the problem is upstream — a
single step has all the think text + all the tools, so there's
nothing to interleave.

**Fix**: module-private `pendingStepBoundary: boolean` flag in
`store/index.ts`. Set on every `tool_result` event; consumed by
the next `text_delta` (close current step + open new one). Edge
cases handled:

- back-to-back tools (no text between) → still in same step
  (the flag survives tool_use_start, only text_delta consumes)
- first text of a new run → no split (flag cleared on run_start)
- no current step → no split (run_start creates the first one)

After fix, the render is naturally `[think1][tool1][result1]`
`[think2][tool2][result2]` `[final summary]` — one step per
model-think-took-tool cycle.

## Issue 2 — snippet-style diff for file_edit

**User complaint (2026-09-14 19:48 screenshot)**: file_edit's
result was just dumped into a fenced ```diff block, but the
content was actually `"edited /path (1 replacement)"` — the tool's
plain-text success message. The user correctly flagged this as
"not a real diff".

**Root cause**: protocol does not forward `Attachment.DiffPreview`
through `tool_result` (only `content` is sent). So the desktop
never sees the actual before/after text from the Java side.

**Fix**: stash the raw `tool_use_start` input (which DOES include
`old_string` + `new_string` + `file_path`) on every tool event.
Reconstruct the unified diff client-side:

```ts
const oldLines = oldStr.split(/\r?\n/);
const newLines = newStr.split(/\r?\n/);
const head = path ? `--- a/${path}\n+++ b/${path}\n` : '';
const hunk = `@@ -1,${oldLines.length} +1,${newLines.length} @@`;
const body = [
  ...oldLines.map((l) => `-${l}`),
  ...newLines.map((l) => `+${l}`),
].join('\n');
```

Then `summarizeDiff()` collapses >4 consecutive context lines to
a single `··· N unchanged lines ···` segment and caps total to 50
segments (≈ git diff -U2 with a sane fallback for big edits).

The new `DiffSnippetView` component renders each line as a
coloured `<div>` (red del / green add / grey ctx / italic meta /
muted elided) — the CSS lives in `MessageList.css`.

## Issue 3 — queue follow-up prompts (option B + cancel)

**User complaint (2026-09-14 19:48 screenshot)**: typed a new
prompt while the previous run was still streaming (header said
"Streaming 69.7s") — the new prompt just sat there, ignored.

**User picked option (B)**: auto-start after current completes,
with the additional ask that the user can manually cancel the
current run (which should also drop the queue — "我会自己 cancel
前一个, 再提交后一个").

**Fix**: `pendingFollowUp: string | null` field in `AppState` —
single-slot queue. `sendMessage` routes to it when `isStreaming`;
`run_end` auto-promotes it to the next run via
`set({ currentInput: queued })` + `get().sendMessage()`. The
queue has its own cancel button in the UI (`.followup-pill` above
the input box) that calls `cancelPendingFollowUp`. The main
cancel button (`cancelQuery`) ALSO drops the queue so "cancel"
means "stop everything".

`awaiting_user_decision` runs do NOT auto-promote the queue — the
engine is paused waiting for the user's input, and firing an
unrelated queued prompt while it's paused is surprising.

## Tests added (15 new, 0 regression)

| File | Tests | Pins |
|---|---|---|
| `stepBoundaryR267.test.ts` | 4 | flag declaration, tool_result set, text_delta consume, run_start reset |
| `diffSnippetR267.test.ts` | 5 | DiffSegment type, summarizeDiff collapse + cap, buildEditSnippetFromInput, BlockView render, CSS classes |
| `followUpQueueR267.test.ts` | 6 | pendingFollowUp field, cancelPendingFollowUp action, cancelQuery drops queue, sendMessage queueing, run_end promotion, MessageInput pill render |

`npm run test`: 1057/1057 pass (1042 → 1057)
`npm run test:e2e`: 4/4 pass
`npm run typecheck`: clean

## Files touched

- `aethercode-desktop/src/store/index.ts` — pendingStepBoundary flag, pendingFollowUp field + actions, run_end auto-promote, text_delta step split
- `aethercode-desktop/src/components/MessageList.tsx` — DiffSegment type, summarizeDiff, buildEditSnippetFromInput, DiffSnippetView, BlockView file_edit branch
- `aethercode-desktop/src/components/MessageList.css` — diff snippet styling
- `aethercode-desktop/src/components/MessageInput.tsx` — followup-pill UI + Esc handling
- `aethercode-desktop/src/components/MessageInput.css` — followup-pill styling
- 3 new test files (15 tests total)

## Round commit

`R267` — pending