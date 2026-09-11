# R110-5: done step click → step detail modal (2026-08-14)

## TL;DR

R110-2 added a nested activity list under a running step pill —
6 events at a time, FIFO-capped, auto-collapsing when the step
finishes. R110-5 gives the user a way to see the FULL history of
a step (not just the last 6 events) by clicking the pill. Done
(`ok`) and errored (`error`) pills are now buttons; running
pills are too, so the user can open the modal mid-flight to see
"what's happened so far". Pending pills stay non-interactive
(no history yet — the modal would just be empty).

The modal is read-only by design. The user is there to see what
happened, not to edit it; editing the step body mid-workflow is
the workflow editor's job (R110-4) and would race the executor
in dangerous ways.

## What ships in this round

### Frontend (aethercode-desktop)

- New `components/StepDetailModal.tsx`:
  - Opens when the store's `selectedStepDetail` field is set;
    closes when it's null.
  - Header: step id + status badge + type + a × close button
    (Esc also closes).
  - Meta row: workflow name + runId (truncated to 8 chars),
    duration (now − first-event-ts if running, last − first if
    finished), event count + "capped" indicator if the count
    hit the R110-2 cap (6), start wall time.
  - Event timeline: every event in `runningWorkflow.stepEvents[stepId]`,
    sorted oldest first. Each event has a left-border colour
    by kind (matches the WorkflowProgressBar's nested list
    palette so the two views read as the same data), a one-
    line summary (icon + kind label + name + summary + relative
    + wall time), and a collapsible `<details>` element with
    the raw `key=value` payload for debugging.
  - Footer: "Esc 关闭" hint + "Copy raw" button (concatenates
    all event raws with newlines, copies to clipboard, shows
    "✓ 已复制" for 1.5s on success).

- New `components/StepDetailModal.css`: backdrop + centered
  card pattern, identical surface to `WorkflowEditorModal` so
  the two modals feel like one component family. The
  status-badge colours match `WorkflowProgressBar`:
  - `ok` → green
  - `error` → red
  - `running` → blue
  - `pending` → dim

- `store/index.ts`:
  - New `selectedStepDetail: { runId: string; stepId: string } | null`
    field on `AppState`. The `runId` guard keeps a stale
    click from a previous run from re-opening a finished
    run's modal.
  - New action `openStepDetail(runId, stepId)` and
    `closeStepDetail()`.

- `components/WorkflowProgressBar.tsx`:
  - Pills for `ok`, `error`, and `running` steps are now
    buttons. The `pending` pill stays a plain `<li>` (no
    history → empty modal would be confusing).
  - Click + Enter / Space both trigger `openStepDetail`. The
    pill has `role="button"` and `tabIndex={0}` for
    keyboard navigation; the focus ring is the same blue
    accent as the input bar.
  - The nested activity list (R110-2) is still rendered for
    the running step, unchanged. The pill's click handler
    doesn't suppress the nested list — clicking the pill
    area outside the nested list still opens the modal.

- `components/WorkflowProgressBar.css`:
  - `.workflow-progress-pill-clickable` — adds
    `cursor: pointer` + hover / focus rings + active
    scale-down so the pill feels like a button.

- `App.tsx`: mounts `<StepDetailModal />` at the app root,
  alongside `<WorkflowEditorModal />`. Both modals are
  overlays; the user can't have both open at once (opening
  the workflow editor from inside the step detail modal is
  an edge case the user can re-trigger from the main view).

## Design choices

### Why no edits inside the modal?

Two reasons:

1. **The workflow is owned by the daemon** — the daemon
   walked the YAML, advanced the steps, and is in the
   middle of running them. A renderer-side edit would
   race the executor; the next `workflow_step` side note
   could overwrite the user's edit, or the executor
   could read stale state and crash.
2. **Editing belongs in the editor** — if the user wants
   to fix a typo in a step body, they go to `/workflow
   modify`, which uses `WorkflowEditorModal` (R110-4) and
   `rpc.writeWorkflow`. The daemon's path-scope check
   refuses unsafe names; the editor re-parses on save
   and reports errors with line numbers.

The step detail modal is "look at what happened". The
workflow editor is "fix what was wrong". Different
modes, different surfaces.

### Why per-event collapsible raw payloads?

The user investigating "why did the build fail" needs
three levels of detail:

1. **The summary line** — `Bash: mvn -B test` is enough
   to know "oh, mvn was run".
2. **The relative + wall time** — `+12.3s · 14:32:18` is
   enough to know "this happened halfway through the
   step".
3. **The raw payload** — `step=test|kind=tool_use|...|arg=...|argtruncated=0`
   is enough to verify the exact wire format the engine
   sent (for filing an upstream bug, or comparing two
   runs).

Each event has a `<details>` toggle for #3 so the modal
stays scannable (level 1+2) by default and lets the user
dive into #3 on demand. Same pattern as the GitHub
Actions log view.

### Why "Copy raw" instead of "Copy as JSON" or "Copy as table"?

"Copy raw" gives the user the most faithful reproduction
of what the engine sent. If they paste it into a GitHub
issue, the maintainer sees the exact wire format. If
they want a table, they can paste into a Markdown editor
and use a quick table formatter. If they want JSON, they
can parse the `key=value` format with a one-liner
(`Object.fromEntries(raw.split('|').map(kv => kv.split('=')))`).

The "raw" view is the lowest common denominator; it's
the format that survives translation to anything else.

## Trade-offs

### No history beyond the cap

R110-2 caps `stepEvents[stepId]` to `MAX_STEP_EVENTS = 6`
per step. If a step ran 50 events, the user sees the
last 6 in the bar AND the last 6 in the modal. The
"earlier" events are gone from the renderer's memory
(they were discarded FIFO in the store).

The fix is server-side: the daemon should record all
events to its `SessionStore` (or a workflow-event log)
and the renderer should be able to fetch a "full
history" via a new RPC. That's a follow-up that needs
the daemon's event log + a paginated `getStepHistory`
RPC.

Until then, the modal shows what the store has. The
"events" count in the meta row says `6+ (capped)` when
the cap is hit, so the user knows.

### No nested parallel branches

A `type: parallel` step's branches (branch-a, branch-b)
are separate workflow steps, not events of the parent.
The store records them as siblings in `runningWorkflow.steps`,
not as events of the parent step. The modal shows events
of the parent step only; to see branch-a's events, the
user clicks branch-a's pill.

This is the right model (branches are first-class steps,
not sub-events), but it means a "long" parallel step
(with 10 sub-branches) requires the user to click each
branch to see its history. A future "branch tree" view
in the modal would solve this; deferred.

## Tests

- aethercode-core: 851/851 (no backend changes)
- aethercode-desktop: tsc clean, vite build clean
  - CSS: 95.41 KB (+3.2 KB for the modal + timeline styles)
  - JS: 498.42 KB (+0 since R110-2; the StepDetailModal
    reuses the ChildStepEvent type from R110-2's types.ts,
    so the new code is small)

## Future R-round candidates

- **R110-7: branch tree view** — for `type: parallel`
  steps, render the branches as nested rows inside the
  modal so the user can see "branch-a → ✓ Read X, ✗ Bash
  Y" without leaving the parent step's detail view.
- **R110-8: server-side full history** — extend the
  daemon's `SessionStore` to record all workflow events
  (not just the last 6) and add a `getStepHistory` RPC
  the modal calls when the event count hits the cap.
  The modal then shows a "load full history" button.
- **R110-9: re-run step** — add a "Re-run this step" button
  in the modal. The user clicks it, picks a model, and
  the daemon creates a child session for the step's body
  with the same inputs. Useful for "the build failed
  because of a flaky network — try again without
  re-running the whole workflow".

## Files

- `aethercode-desktop/src/components/StepDetailModal.tsx`
  (new, 10582 bytes)
- `aethercode-desktop/src/components/StepDetailModal.css`
  (new, 7835 bytes)
- `aethercode-desktop/src/components/WorkflowProgressBar.tsx`
  (modified — clickable pills)
- `aethercode-desktop/src/components/WorkflowProgressBar.css`
  (modified — clickable pill styles)
- `aethercode-desktop/src/store/index.ts` (modified —
  `selectedStepDetail` field + `openStepDetail` /
  `closeStepDetail` actions)
- `aethercode-desktop/src/App.tsx` (modified — mounts
  `<StepDetailModal />` at app root)
