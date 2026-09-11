# R92 — Subagent panel, cancel RPC, per-session filter

**Date**: 2026-08-16
**Scope**: aethercode-tools (Java), aethercode-protocol (Java),
aethercode-tui (TypeScript Ink), aethercode-desktop (Tauri)
**Status**: SHIPPED — three sub-rounds (R92-C, R92-D, R92-B)

## TL;DR

The background subagent story gets three upgrades at once,
all in lockstep across the TUI and the desktop app:

- **R92-C** — `subagent_cancel` JSON-RPC. The TUI panel
  and the desktop SubagentPanel now have a real Cancel
  button that interrupts the worker thread (not just
  flips the status). The engine's `SubagentRegistry`
  tracks the worker thread so cancel() can do
  `thread.interrupt()`.
- **R92-D** — per-session filter. A multi-session daemon
  no longer bleeds subagent events from one session into
  another session's UI. The `SubagentEvent` carries a
  `sessionId`; the TUI reducer and the desktop store
  both drop events whose `sessionId` doesn't match the
  current session.
- **R92-B** — `<SubagentPanel>` in both surfaces. The
  TUI adds Ctrl+S toggle + ↑/↓/Enter/c keys. The
  desktop adds a 4th "Subagents" tab in the right panel
  with a per-row Cancel / Insert result button. The
  panel reads the same `subagent` slice that drives the
  StatusBar pill, so the two views are always in sync.
  A live "running" badge on the desktop tab keeps the
  user aware of a background job even when they're on
  another tab.

## Architecture

```
Java daemon — SubagentRegistry (R92-C: tracks Thread per jobId;
                                    R92-D: carries sessionId on every job;
                                    R92-B: publishes result on COMPLETED)
  └─ fireChange(SubagentEvent) per RUNNING / COMPLETED / FAILED / CANCELLED
       │
       │  AetherCodeMethods (R92-C: subscribes to SubagentRegistry;
       │                     R92-D: adds sessionId to payload;
       │                     R92-B: adds result to payload;
       │                     registers subagentCancel RPC)
       ▼
Tauri app.emit("ws-notify", { method: "subagent_event"|"subagentCancel", params: {...} })
       │
       │  AetherCodeRpc + AetherCodeRpc.on(...) handlers
       ▼
Renderer (TUI state.ts / Desktop store/index.ts)
  ├─ R92-D: drop events from other sessions
  ├─ R92-C: state.subagent + state.runningSubagents track lifecycle
  └─ R92-B: state.subagentJobs / subagent.jobs hold the in-memory list
       │
       ├─ StatusBar inline indicator (R91-D, unchanged in shape)
       ├─ SubagentToast (R91-D, unchanged)
       └─ SubagentPanel (R92-B, NEW)
            ├─ TUI: Ctrl+S toggle, ↑/↓ cursor, c cancel, Enter insert
            └─ Desktop: 4th tab in right panel, Cancel / Insert buttons
```

## What ships

### R92-C: subagent_cancel RPC + Cancel button

**Java side**:
- `SubagentRegistry.runningThreads: Map<String, Thread>` — tracks the
  worker for each in-flight job.
- `SubagentRegistry.attachThread(jobId, thread)` — called by
  AgentTool right after `Thread.start()`. Cleared by every
  natural terminal transition.
- `SubagentRegistry.cancel(jobId) → CancelResult` — interrupts
  the worker (security-aware: catches SecurityException
  so a sandboxed JVM still gets the status flip), then
  calls `markCancelled` to fire the notification. Idempotent
  on a finished job (`alreadyFinished=true`).
- `SubagentRegistry.CancelResult` — record carrying
  `cancelled` and `alreadyFinished` booleans.
- `AetherCodeMethods.subagentCancel(params)` — thin
  RPC handler. Returns
  `{ ok: true, jobId, cancelled, alreadyFinished }`.
- `AetherCodeMethods.registerAll` — adds
  `dispatcher.register("subagentCancel", this::subagentCancel)`.
- `AgentTool` — passes the worker thread to the registry
  immediately after `Thread.start()` (and after
  `setDaemon(true)`).

**TUI side**:
- `tui.tsx` — when the SubagentPanel is open, pressing `c`
  on a RUNNING row dispatches `subagentCancel` via
  `client.request("subagentCancel", { jobId })`. Toast
  on success ("cancelled subagent sag-1") or already-finished
  ("already finished").
- `insertSubagentResult(jobId, state, dispatch)` — Enter
  key on a terminal row drops the result text into the
  input box (or a short note for FAILED / CANCELLED) and
  closes the panel.

**Desktop side**:
- `SubagentPanel.tsx` — per-row Cancel button for RUNNING
  jobs. Calls `invoke('rpc_call', { method: 'subagentCancel', params: { jobId } })`
  through Tauri's RPC bridge. The store's `subagent_event`
  subscription re-renders the row as CANCELLED.
- Per-row "Insert result" button for terminal jobs — calls
  `setCurrentInput` with the result text (or a short note).

### R92-D: per-session filter

**Java side**:
- `SubagentRegistry.SubagentJob.sessionId: String` — new
  field, defaults to "" for pre-R92-D callers.
- `SubagentRegistry.SubagentEvent.sessionId: String` —
  carried on every event.
- `SubagentRegistry.register(taskId, prompt, role, sessionId)` —
  new 4-arg overload. The 3-arg `register(...)` is preserved
  for backward compatibility (it defaults sessionId to "").
- `AgentTool.currentSessionId(ctx)` — reads
  `ctx.extra("app_state")` (the engine's AppState) and
  returns its `sessionId()`. Falls back to "" when the
  extras don't have the AppState (defensive: pre-R92-D
  callers).
- `AetherCodeMethods.subagent_event` payload — adds
  `sessionId: <string>` (always present; empty for
  legacy jobs).

**TUI side**:
- `state.ts` — `subagentEvent` action carries the
  optional `sessionId`. The reducer drops events whose
  `sessionId` is non-empty AND doesn't match
  `state.sessionId`. Empty `sessionId` is accepted as
  "ours" (the "single-session" sentinel + the
  pre-R92-D wire shape).
- `r91d-subagent.test.mjs` — 3 new test cases
  (foreign session dropped / matching accepted /
  legacy accepted).

**Desktop side**:
- `subagentReducer.ts` — `SubagentEventAction.sessionId?`
  field; `isOurSession(evSessionId, currentSessionId)`
  helper; LRU-eviction preserves the per-session
  attribution.
- `subagentReducer.test.ts` — 5 new `isOurSession`
  cases.
- `store/index.ts` — `subagent_event` handler calls
  `isOurSession` before the reducer. Events from other
  sessions are dropped on the floor.

### R92-B: side panel + result text on the wire

**TUI side**:
- `state.ts` — `subagentJobs: Record<jobId, SubagentJobView>`
  with `MAX_SUBAGENT_JOBS = 32` (matches the desktop's
  cap so the two surfaces stay in lockstep). Helper
  functions: `mostRecentSubagentId`,
  `subagentJobIdsInOrder`, `formatSubagentPanelRow`.
- `state.ts` — actions: `openSubagentPanel`,
  `closeSubagentPanel`, `setSubagentPanelFocus`,
  `subagentPanelFocusNext`, `subagentPanelFocusPrev`.
- `state.ts` — state fields: `subagentPanelOpen: boolean`,
  `subagentPanelFocus?: string`.
- `state.ts` — `subagentEvent` action now also carries
  `resultText?: string` (the captured assistant text
  on COMPLETED).
- `components/SubagentPanel.tsx` (NEW) — Ink component
  with one row per job. Tone-matched icons (▶ / ✓ / ✗ / ⊘).
  Per-row hints ("[c cancel]" / "[⏎ insert]").
- `tui.tsx` — `Ctrl+S` toggles the panel. `↑/↓` move
  the cursor. `Enter` inserts the focused job's
  result. `c` cancels the focused RUNNING job. `Esc`
  closes the panel. `subagent_event` handler passes
  `resultText` to the dispatched action.

**Desktop side**:
- `components/SubagentPanel.tsx` (NEW) — React component
  in the right panel's 4th tab. Per-row Cancel / Insert
  buttons. Live `1s` tick while any RUNNING job exists
  so the elapsed column updates without a server round-trip.
- `components/SubagentPanel.css` (NEW) — tone-matched
  borders (running / completed / failed), grid layout
  for the per-row fields.
- `components/RightPanel.tsx` — 4th tab "Subagents".
  Live badge on the tab title shows the running count
  so the user sees a background job even when they're
  on another tab. Wired through the same
  `useStore.getState().subagent` selector the StatusBar
  uses.
- `components/RightPanel.css` — `.right-tab-badge` for
  the live count.
- `subagentReducer.ts` — `SubagentJobView.resultText?`
  + `SubagentEventAction.resultText?` fields. The
  reducer preserves the result text across transitions
  (defence in depth — the engine's own guards already
  prevent a stale FAILED from overwriting a real
  COMPLETED).
- `store/index.ts` — `subagent_event` handler reads
  `params.result` and passes it to the reducer.

**Java side** (R92-B result text):
- `SubagentRegistry.SubagentEvent.result: String` —
  new field. Empty for RUNNING / CANCELLED. The
  captured assistant text on COMPLETED. The error
  message on FAILED.
- `SubagentRegistry.MAX_RESULT_CHARS = 4096` — cap on
  the wire payload. The full result remains on
  `SubagentJob.resultText` for any consumer that wants
  the unabridged text (a future "open result" panel).
- `AetherCodeMethods.subagent_event` payload — adds
  `result: <string>` (always present; empty when not
  applicable).

## Implementation notes

### R92-C: cancel mechanics

The original `markCancelled` was a status flip only —
the worker thread kept running. R92-C closes that gap
by tracking the thread and interrupting it. The interrupt
is `Thread.interrupt()` which sets the flag; the chat-client
stream (which is a `Stream.forEach` over a Java 17 stream)
is pull-based, so the next `forEach` iteration will see
the flag and throw `InterruptedException`. The worker's
`catch (Throwable)` in `runBackgroundJob` already handles
this — the markCompleted/markFailed it would otherwise
call is a no-op because the job is no longer in the
`running` map. So the user sees the status flip immediately
and the worker exits cleanly a moment later.

### R92-D: per-session filter contract

The contract is: the TUI/desktop ONLY sees events for the
session it currently owns. Pre-R92-D events (no sessionId)
are accepted when the local sessionId is also empty (a
fresh TUI/desktop before getState returns). After
getState, the local sessionId is set and any non-matching
event is dropped. The empty-string case is the
"single-session daemon" sentinel — a daemon with only
one session never has a sibling to filter against.

The sessionId comes from the engine's `AppState` via
`ctx.extra("app_state")`. This is a thread-local-scope
read; the SubagentRegistry is process-singleton but the
sessionId attribute is set per-call. A future R-round
can index `running` by sessionId for O(1) lookups; today
the renderer filters the snapshot.

### R92-B: panel as a sibling of the StatusBar

The SubagentPanel is a SECONDARY view of the same data
the StatusBar pill shows. The TUI's panel sits to the
right of the sidebar (or the main column when the
sidebar is hidden). The desktop's panel is a 4th tab in
the right panel. Both are presentational — the source
of truth is the `subagent` slice of the store, and the
panel reads it via the same selectors the StatusBar uses.

The "Insert result" action is the new affordance: a
COMPLETED job's result text is captured on the wire
(truncated to 4 KB to keep notifications bounded; the
full result stays on `SubagentJob.resultText` for any
future consumer that wants the unabridged text). The
user presses Enter (TUI) or clicks "Insert result"
(desktop) to drop the result into the input box ready
to be edited and sent.

The Cancel button / key fires `subagentCancel` via the
JSON-RPC. The engine's `SubagentRegistry.cancel` returns
a small `CancelResult` so the UI can distinguish
"cancelled a live job" from "tried to cancel a job
that just finished". The notification path stays
authoritative — the cancel() updates the status, the
status update fires the event, the event re-renders the
row. No optimistic UI flip; the wire is the source of
truth.

## Verification

- `mvn -pl aethercode-tools test` — **39/39** pass
  (24 pre-R92-C + 8 R92-C cancel + 7 R92-D sessionId)
- `mvn -pl aethercode-protocol test` — **47/47** pass
  (incl. dispatcher registration for `subagentCancel`)
- `mvn -pl aethercode-tools test -Dtest=AgentToolTest` —
  **12/12** pass (no regression from the sessionId
  capture path)
- `aethercode-tui/scripts/test/r91d-subagent.test.mjs` —
  **15/15** pass (12 R91-D + 3 R92-D)
- `aethercode-desktop/src/store/subagentReducer.test.ts` —
  **27/27** pass (22 R92-A + 5 R92-D isOurSession)
- `aethercode-tui` bundle: **1577.3 KB** (up from
  1566.7 KB, +10.6 KB for the SubagentPanel + state)
- `aethercode-desktop` vite bundle: **509.53 KB** (up
  from 503.27 KB, +6.26 KB for the SubagentPanel + tab
  badge + state)
- `tsc -b --noEmit` clean on both surfaces

## Decisions

### R92-C

- **Track the thread in the registry**, not in
  AgentTool. AgentTool is per-call, the registry is
  process-singleton — the cancel RPC needs to find
  the thread from the jobId alone, so the registry
  is the right home.
- **Idempotent cancel** — a cancel on a finished
  job returns `alreadyFinished=true`. The UI can
  show a different toast ("nothing to cancel") and
  the user can move on.
- **Status flip first, interrupt second** — calling
  `markCancelled` from `cancel()` is intentional:
  the wire is updated before the worker has a chance
  to react. The worker eventually fires its own
  markCompleted/markFailed, which is a no-op because
  the job is no longer in `running`.
- **No cancel tool** — the model can't call
  `subagentCancel`; cancel is a user-only action. A
  future R-round can add it as a tool the model can
  use to cancel its own runaway subagents (a
  different UX: a model-driven cancel might want to
  emit a "why" string for the audit log).

### R92-D

- **Single attribute on the event** — `sessionId` is
  a plain string. The renderer decides whether to
  filter. This keeps the wire shape stable and the
  filter logic in the renderer (where the local
  sessionId lives).
- **Empty-string as the sentinel** — pre-R92-D
  daemons don't have a sessionId, and a
  single-session daemon has nothing to filter. An
  empty string in both slots (event.sessionId and
  state.sessionId) means "show everything".

### R92-B

- **The panel is a SECONDARY view** of the same data
  the StatusBar pill shows. It's not a separate
  tracker; if you see something on the panel, the
  StatusBar is in sync.
- **TUI: Ctrl+S + ↑/↓/Enter/c**, all handled in
  `useInput` so the panel is keyboard-first. Mouse
  isn't a TUI concern.
- **Desktop: 4th tab with a live badge**. The
  badge (the running count) is a thin extension —
  the user doesn't need to switch tabs to see
  something is running. The TUI's status pill
  serves the same role; the desktop's badge is
  one layer of redundancy.
- **4 KB wire cap on result text** — the panel's
  "Insert into input" use case only needs the first
  few KB (the user edits before sending). The full
  result stays on the engine-side `SubagentJob` for
  any future consumer that wants the unabridged
  text (a "view full result" modal, a copy button
  for long outputs, etc.).
- **Result is preserved across transitions** — a
  COMPLETED result text isn't wiped if a stale
  notification arrives. The engine's own guards
  already prevent this; the reducer's `?? prev?.resultText`
  is defence in depth.

## Files (R92-C / D / B)

```
aethercode/aethercode-tools/src/main/java/.../task/
  SubagentRegistry.java                     (cancel + attachThread + result field + sessionId field)
aethercode/aethercode-tools/src/test/java/.../task/
  SubagentRegistryTest.java                 (+ 8 cancel tests, + 7 sessionId tests)
aethercode/aethercode-protocol/src/main/java/.../methods/
  AetherCodeMethods.java                    (subagentCancel RPC + sessionId + result on payload)
aethercode/aethercode-tools/src/main/java/.../task/
  AgentTool.java                            (currentSessionId(ctx) + attachThread after start)

aethercode-tui/src/
  state.ts                                  (subagentJobs map + 4 panel actions + helpers)
  tui.tsx                                   (Ctrl+S, Esc, ↑/↓, Enter, c handlers; resultText in dispatch)
  components/SubagentPanel.tsx              (NEW — Ink component)
  scripts/test/r91d-subagent.test.mjs       (+ 3 R92-D session filter tests)

aethercode-desktop/src/
  store/subagentReducer.ts                  (sessionId + resultText on action; isOurSession helper)
  store/subagentReducer.test.ts             (+ 5 isOurSession tests)
  store/index.ts                            (subagent_event sessionId filter + resultText pass-through)
  components/SubagentPanel.tsx              (NEW — React component with Cancel/Insert)
  components/SubagentPanel.css              (NEW — tone-matched styling)
  components/RightPanel.tsx                 (4th tab + live running badge)
  components/RightPanel.css                 (.right-tab-badge for the live count)
```
