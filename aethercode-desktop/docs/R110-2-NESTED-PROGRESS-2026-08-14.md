# R110-2: Nested progress bar (2026-08-14)

## TL;DR

A workflow `kind: agent` (or `kind: skill`) step's child session now
shows a live activity list — tools, run start/end, side notes — right
under the running step pill. The user no longer has to scroll into the
chat scrollback to see "the agent is running `Bash: ls -la`"; the
progress bar carries the signal itself.

This is a UX-only change for end users. The wire shape and store
schema get richer, but the daemon doesn't need new RPCs and the
existing protocol stays backward-compatible.

## Before / after

**Before (R108-2):**

```
[⚡ pr-review · step 2/5]
[▸ pull] [● unit] [○ lint] [○ review] [○ commit]
```

…and then in the chat scrollback:

```
[child ↳ unit] agent "tester" TextDelta
[child ↳ unit] agent "tester" ToolUseStart
[child ↳ unit] agent "tester" ToolResult
[child ↳ unit] agent "tester" RunEnd
```

The user has to scroll the chat log to see what the agent is doing.
The progress bar carries no per-step signal beyond "running".

**After (R110-2):**

```
[⚡ pr-review · step 2/5]
[▸ pull] [● unit] [○ lint] [○ review] [○ commit]
            ┌─ nested for "unit" (running) ──────┐
            │ ◐ Bash: ls -la                       │
            │ ✓ Read: src/main.ts (1240 chars)     │
            │ ✓ Edit: src/main.ts (380 chars)      │
            └─────────────────────────────────────┘
```

The nested list appears only under the *currently running* step
pill. As soon as the step finishes (status flips to `ok` or
`error`), the nested list collapses back to the bare pill — the
events are still in the store for a future R-round's "step
detail" view, but the progress bar stays compact.

Each row is one line: icon + name + summary. The icon colour
matches the parent pill's legend (blue = in flight, green = ok,
red = error, dim = informational). Hover shows the raw message
payload (the executor's structured `key=value`) so the user can
verify what the engine actually forwarded.

## What ships in this round

### Backend (aethercode-core)

- `WorkflowExecutor.formatChildEventMessage(stepId, kind, name, ev)` —
  a new helper that builds a structured `key=value` payload for
  every `StreamEvent` the child session forwards. The format is:
  ```
  [step-id] kind "name" EventClass |
    step=<id>|kind=<kind>|name=<name>|ev=<EventClass>|
    tool=<name>|arg=<brief-input>|argtruncated=0|1|
    status=ok|err|outlen=<n>|
    model=<id>|stop=<reason>|
    notekind=<kind>|notemsg=<truncated-message>|
    text=<truncated-text>
  ```
  Values are backslash-escaped (`|` and `\` are escaped) so
  the renderer can split on unescaped pipes only.

- The legacy `[step-id]` bracket prefix is preserved at the
  start of the message. R108-2-era clients that match on the
  prefix still work — the new payload follows the legacy tail
  after a `|`. The new renderer ignores everything before the
  first pipe and parses the structured payload.

- The brief input is the first scalar value of the tool input
  (with `command` / `path` / `file_path` / `file` / `url` /
  `prompt` / `input` / `query` preferred as the most
  identifiable arg). Values longer than 80 chars are truncated
  with `…` and the `argtruncated=1` flag is set so the
  renderer can show the suffix.

### Frontend (aethercode-desktop)

- `store/types.ts` (new) — `ChildStepEvent` type and
  `MAX_STEP_EVENTS = 6` cap. The type carries the
  renderer-friendly fields (icon kind, name, summary,
  truncated flag, raw payload for hover).

- `store/index.ts` —
  - `runningWorkflow.stepEvents: Record<string, ChildStepEvent[]>`
    added to the type and initialised to `{}` on the first
    `workflow_step` SideNote.
  - `parseChildEventMessage(raw)` parses the structured
    message into a typed `ChildStepEvent`. Falls back to a
    legacy `unknown` event for unparseable R108-2 messages.
  - `parseKeyValuePairs(payload)` splits the
    `key=value|key=value|...` payload, with backslash
    unescape for `|` and `\`.
  - `formatSystemLine(ev)` builds a one-line description for
    the chat scrollback (preserves the R108-2 behaviour of
    surfacing a system line per child event, but with a
    richer format: `Bash: ls -la` instead of
    `[child ↳ unit] agent "tester" ToolUseStart`).
  - The `child_session_event` SideNote handler now appends
    the parsed event to `runningWorkflow.stepEvents[stepId]`
    (capped to `MAX_STEP_EVENTS`, FIFO drop on overflow)
    AND pushes a system line to the chat scrollback.

- `components/WorkflowProgressBar.tsx` —
  - For the currently running step (status === 'running'),
    renders a nested `<ul>` below the pill with one row per
    event.
  - Done / pending / error steps don't expand (the bar stays
    compact).
  - The nested list uses `position: absolute` to float
    below the running pill without pushing other pills
    around. Anchor = `position: relative` on the running
    pill.

- `components/WorkflowProgressBar.css` — nested row styles:
  - Dark background, blue-tinted border, soft drop shadow
  - "Tab" connector (left edge meets the parent pill)
  - Icon column colour-coded by event kind (blue / green /
    red / dim) to mirror the parent pill's legend
  - In-flight tool rows pulse at 1.6s (same keyframe as
    the parent pill) so the user can tell at a glance
    "this tool is still running"
  - Summary truncated with ellipsis on overflow; the raw
    payload is in the row's `title` attribute for hover

## Wire format (forward-compatible)

The `child_session_event` SideNote message is now a hybrid:

```
[step-id] kind "name" EventClass | step=...|kind=...|name=...|ev=...|...
```

The first part (before the `|`) is the R108-2 legacy format.
The second part is the R110-2 structured payload. New
clients parse the second part; old clients match on the
bracket prefix and ignore the rest. The two parts share the
canonical `step` / `kind` / `name` / `ev` fields so a
debugger can see them in both halves.

## UX rationale

The user's R-round request was "用户体验非常重要". For a
workflow with N skill/agent steps, the user has two natural
questions at any given moment:

1. "Which step is the engine on?" — answered by the parent
   pill row.
2. "What is the engine doing in that step?" — answered by
   the nested activity list (R110-2).

Before R110-2, question 2 was answered by scrolling the
chat. That breaks the user's flow:

- They have to leave the progress bar
- They have to find the right step's events in a sea of
  generic system lines
- They lose track of "which step are we on" while reading
  the chat

The nested list keeps the user anchored on the bar.
Single-glance scan, no scroll, no context switch.

The design is intentionally narrow (4-6 rows, ~240-480px
wide) so it doesn't dominate the screen on a long workflow
with many steps. The list disappears as soon as the step
finishes (the bar returns to its single-row layout) so
the user's visual focus moves to the next step's pill
naturally.

## Files changed

### Backend (aethercode)

- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowExecutor.java`:
  - The `SkillInvoker.invoke(...)` eventSink wrapper now
    builds the message via `formatChildEventMessage(...)`
    instead of `"[" + step.id() + "] " + ev.getClass().getSimpleName()`.
  - New helper: `formatChildEventMessage(stepId, kind, name, ev)`.
  - New helpers: `briefArg(input)`, `briefArgTruncated(input)`,
    `resultLength(content)`, `esc(value)`.

### Tests (aethercode)

- `aethercode-core/src/test/java/org/aethercode/core/workflow/WorkflowExecutorChildSessionTest.java`:
  - New test: `childSessionEvent_messageIsStructuredKeyValue` —
    pins the wire format (legacy prefix + new payload,
    ToolUseStart / ToolResult / RunStart / RunEnd all
    carry the canonical fields).
  - New test: `childSessionEvent_truncatesLongArgs` —
    200-char input gets truncated to 80 + `argtruncated=1`.
  - New test: `childSessionEvent_escapesPipesInArgs` —
    `ls | grep foo` is escaped as `ls \| grep foo`.
  - Existing test `childSessionEvent_wrapsWithParentStepId`
    still passes (the legacy bracket prefix is preserved).

### Frontend (aethercode-desktop)

- `aethercode-desktop/src/store/types.ts` (new, 3147 bytes) —
  `ChildStepEvent` interface + `MAX_STEP_EVENTS = 6`.
- `aethercode-desktop/src/store/index.ts` — `runningWorkflow`
  type extended with `stepEvents: Record<string,
  ChildStepEvent[]>`; `parseChildEventMessage` and
  `parseKeyValuePairs` parsers; `formatSystemLine` for the
  scrollback line; the `child_session_event` SideNote
  handler routes parsed events into `stepEvents`.
- `aethercode-desktop/src/components/WorkflowProgressBar.tsx` —
  nested `<ul>` below the running pill; `EVENT_ICON` map
  for the icon column.
- `aethercode-desktop/src/components/WorkflowProgressBar.css` —
  `.workflow-progress-nested*` styles; "tab" connector;
  per-kind colour scheme matching the parent pill.

## Validation

- aethercode-core: **851/851 tests pass, 0 fail, 0 skip**
  (was 848, +3 new tests in `WorkflowExecutorChildSessionTest`)
- aethercode-protocol: **47/47 tests pass**
- aethercode-sdk: **108/108 tests pass**
- aethercode-desktop: **`tsc --noEmit` clean**; `npm run build`
  succeeds (487.30 KB JS, 86.80 KB CSS, was 478.14 / 80.47)
- TypeScript `noUnusedLocals` / `noUnusedParameters` /
  `strict` clean

## Risks / known limitations

- **Nested list anchors on the running pill**. The
  `position: absolute` makes the list float below the
  pill. If the running pill is the last in the row, the
  list can overflow the right edge of the bar; the
  `max-width: 480px` keeps it bounded, but very narrow
  windows (< 600px) can clip the summary. A future
  R-round can flip to a portal-based popover that
  respects the viewport.
- **The nested list doesn't show for done steps**. A
  multi-step workflow where the user wants to "look
  back" at what step 2 did can only do so by re-running
  the workflow (R110-3 territory — see the WS reconnect
  hydrate transcript which is a related concept).
- **Event types not yet exercised in production**:
  `SubTaskStart` / `SubTaskEnd` (R85) are child-session
  events too, but they bubble through a different
  SideNote path. The R110-2 nested list only handles the
  `child_session_event` SideNote; sub-task transitions
  still appear in the chat scrollback as before. A
  follow-up R-round can extend the parser to also pick
  up sub-task transitions when the executor's wrapper
  starts forwarding them through `child_session_event`.
- **No event coalescing**. A busy child session that
  fires 30 `TextDelta` events in 1 second produces 30
  store updates and 30 re-renders. The `MAX_STEP_EVENTS`
  cap bounds the rendered list but doesn't coalesce
  intermediate updates. R110-3 (WS reconnect
  hydrateTranscript) is a natural follow-up — same
  throttle machinery can power both.

## What doesn't ship in this round

- **Step detail modal** (click a done step to see its
  events). The events are in the store; we just don't
  surface them in the bar. A future R-round can add a
  "view" button to each done pill that opens a modal
  with the full event history.
- **Click-to-jump-to-step** in the chat scrollback.
  R103 already emits `workflow_step` events that include
  the step index, but the renderer doesn't currently
  anchor on them. R110-3's WS reconnect hydrate
  transcript work is a natural place to add the anchor.
- **Drag-to-reorder / cancel a running step**. The
  workflow executor doesn't support this today; it's a
  bigger feature than a single R-round.

## See also

- `R108-2-CHILD-SESSION-EVENTS-2026-08-13.md` — the
  previous round that added the wire path
  (`child_session_event` SideNote + bracket prefix).
  R110-2 extends the wire format and adds the
  renderer.
- `R103-R104-EXECUTOR-AND-MENTION-2026-08-12.md` — the
  `WorkflowExecutor` and `SkillInvoker` lifecycle.
- `R109-3-AGENT-MODEL-LINKAGE-2026-08-13.md` — the
  per-agent `model:` frontmatter that the executor
  resolves to a `ChatClient`. R110-2 is orthogonal;
  it doesn't change model resolution, it just makes
  the user-visible activity richer.
