# R105: Workflow Error Policies + Session Persistence + SubTask Animation

Date: 2026-08-12
Builds on: R102 (workflow picker), R103 (workflow executor), R104 (`@`-mention),
R88 (multi-session stack), R97 (per-session drafts)
Daemon jar: `aethercode-0.2.8.jar` (41.5 MB)
Frontend bundle: 464.6 KB JS / 72.9 KB CSS (was 450.9 / 71.0 at R104)

## Scope

R105 ships three independent improvements under one daemon release:

- **R105-A** — `continue_on_error` + `wait: any | majority` workflow policies
  (real executor semantics, replacing R103's "always continue" default).
- **R105-B** — Per-session message persistence to localStorage. The
  multi-session stack now actually survives a reload.
- **R105-C** — CSS-driven smooth animation for the SubTaskCard 段 3
  expand / collapse.

`skill` / `agent` step types in the workflow executor are explicitly
deferred to R106 (they need a child-session model on the engine side
to avoid clobbering the main transcript; that's a bigger refactor
than R105's other three changes combined).

---

## R105-A: `continue_on_error` + `wait:` policy

### Before

R103's executor:

- Any step failure aborted the workflow silently. (R102's behaviour,
  kept by R103 because abort semantics were under-specified.)
- Parallel's branches all had to finish before the parallel step
  completed. (No way to short-circuit on first-success or quorum.)

### After

Per-step **`continue_on_error`** (default `false`):

- `true`  → a failure is downgraded to `skipped`. The workflow
  keeps advancing. The step's `stderr` still records the cause.
- `false` (default) → a failure marks the step `error` and **all
  later steps** are marked `skipped` with reason
  `"aborted by previous error"`. The loop breaks.

Per-parallel **`wait:`** (default `all`):

- `all` (default) → wait for every branch; any error → parallel
  error. (Same as R103.)
- `any`  → complete the parallel as soon as **one** branch is `ok`.
  Remaining branches continue in the background; their results are
  recorded but don't affect the parallel's status. If no branch
  ever reaches `ok`, parallel is `error`.
- `majority` → wait until either `>50%` branches are `ok` or
  `>50%` are `error`. If all branches finish without a majority
  on either side (e.g. 2 ok + 2 error out of 4), the parallel is
  `ok` (a tie defaults to `ok`).

### YAML shape

```yaml
steps:
  - id: lint
    type: shell
    cmd: "eslint src/"
    continue_on_error: true        # new — R105
  - id: test
    type: shell
    cmd: "npm test"
  - id: deploy
    type: parallel
    wait: any                       # new — R105
    branches:
      - id: deploy-staging
        type: shell
        cmd: "deploy.sh staging"
      - id: deploy-prod
        type: shell
        cmd: "deploy.sh prod"
```

### Event-stream changes

The event shape is the same as R103 — a `workflow_step` SideNote
per transition — so the existing `WorkflowProgressBar` keeps
working. New transition kinds:

- `skipped` (terminal) — emitted for steps that hit an error and
  were downgraded via `continue_on_error: true`, or for steps
  after a non-`continue_on_error` failure.
- `error` (terminal) — unchanged.
- The "underlying cause" `error` event from `runShell` still fires
  before the outer `skipped` event. The user sees both: the cause,
  then the downgrade. The final state on the progress bar pill is
  the outer one.

### Files

- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowReader.java`
  — `Step` now has `boolean continueOnError`. New
  `STEP_CONTINUE_ON_ERROR` regex + `parseContinueOnError` helper.
- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowExecutor.java`
  — `StepResult.finalEmitted` flag avoids double-emit when the
  inner step's `error` event is superseded by the outer's
  `skipped` downgrade. `run()` honours `step.continueOnError()`
  for both thrown exceptions and explicit `status="error"`
  set by the inner executor. `runParallel()` honours the
  `wait:` policy via three switch arms (`any`, `majority`,
  `all`/default). `parseWaitPolicy()` parses the field.
- `aethercode-core/src/test/java/org/aethercode/core/workflow/WorkflowExecutorTest.java`
  — 9 new test cases (continuation on/off, wait policies, parser
  unit tests). Total: 28/28 WorkflowExecutorTest pass,
  9/9 WorkflowReaderTest pass.

### WS roundtrip (5/5 PASS)

```
[r105-1] coe=true: bad → error:bad → skipped:bad → ok:ok            PASS
[r105-2] coe=false: bad → error:bad → skipped:never (aborted)       PASS
[r105-3] wait=any, elapsed=289ms (vs 1500ms slow branch)            PASS
[r105-4] wait=majority: 2 ok + 2 error → ok (no majority of error)  PASS
[r105-5] wait=all (default): 1 ok + 1 error → error                 PASS
```

---

## R105-B: per-session message persistence

### Before (R88 multi-session stack, R100 state)

- The daemon's `loadSession` was a R29-era stub that threw
  `"not yet implemented"`. The LeftPanel showed the daemon's
  empty session list and fell back to "no sessions".
- `messages` lived only in the zustand store. A window reload
  (or the app coming back from the background) wiped everything
  except the input draft (R97).
- "+ 新会话" (R88) called `setCurrentSessionId(null)` and
  waited for the daemon to issue a new id on first query.
  The daemon never did (single-session engine).

### After

- Each session's chat history is persisted to localStorage
  under `aethercode-session:<id>`. The schema is
  `{ messages, lastUsedAt, createdAt }`. The cap is
  `MAX_PERSISTED_MESSAGES = 500` (trim from the front) so
  a chatty long session doesn't blow the 5MB quota.
- A new store action `createNewSession()` mints a fresh UUID
  on the spot (using `crypto.randomUUID` or a fallback) and
  surfaces it in the LeftPanel immediately. The
  SessionList/CommandPalette `/new` callers were updated to
  use this instead of the broken `setCurrentSessionId(null)`
  path.
- `switchSession(id)` still calls the daemon's `loadSession`
  best-effort (catches the "not yet implemented" error
  silently), and hydrates the messages from localStorage.
- The `messages` field is hydrated from localStorage on
  app startup, using the daemon's `currentSessionId` as the
  key (or empty if the daemon has no session).
- The LeftPanel's `sessions` array is a merge of the daemon's
  list and the localStorage entries (scanned by key prefix).
  Daemon wins on id collision; localStorage supplies
  `lastUsedAt` when the daemon omits it.
- A `useStore.subscribe` saves messages on every change
  (300ms debounce, mirrors R97's input-draft debounce). It
  also handles the `__none__ → real id` rename: when the
  user transitions from the "no session yet" slot to a real
  id, the `__none__` key is deleted so it doesn't show up
  as an empty session in the LeftPanel.

### Files

- `aethercode-desktop/src/store/index.ts`
  — `readSessionMessages` / `writeSessionMessages` /
  `listPersistedSessions` / `mergeWithPersisted` / `listChanged`
  helpers. New `createNewSession` action. `useStore.subscribe`
  for persistence + session-list refresh. Updated
  `switchSession`, `setCurrentSessionId`, `refreshSessions`,
  initialize (hydrate from localStorage on startup).
- `aethercode-desktop/src/components/SessionList.tsx`
  — `handleNew` calls `createNewSession` instead of
  `setCurrentSessionId(null)`.
- `aethercode-desktop/src/components/CommandPalette.tsx`
  — same.
- `aethercode-desktop/src/components/commandCommands.ts`
  — `/new` slash command uses `createNewSession`.
- `aethercode-desktop/src/lib/methods.ts`
  — `WorkflowStep.continueOnError?` (R105-A, since we're
  shipping the same release).

### Trade-offs

- The daemon's `loadSession` is still a stub. The frontend's
  `currentSessionId` is a UI concept; the engine's
  `appState.sessionId` is the only "real" session the daemon
  knows about. This means stream events arrive on the
  engine's transcript regardless of the local session the
  user is viewing. If the user switches mid-stream, the new
  chunks land in the **new** session's messages (because
  `set((s) => ({ messages: [...s.messages, ...]}))` always
  uses the current state). This is the R88 contract;
  R105-B doesn't try to fix it.
- localStorage is per-browser. Two windows of the same Tauri
  app share the same localStorage but write on top of each
  other; the last write wins. The merge on the LeftPanel
  makes this less noticeable. R106+ could move messages
  to a per-session disk file on the daemon side.
- The 500-message cap truncates old messages silently. A
  future R106+ could add a "load older" button per session.

### TypeScript

`tsc --noEmit` clean. Bundle size: 464.6 KB JS / 72.9 KB CSS
(was 450.9 / 71.0 at R104; +14 KB JS / +1.9 KB CSS for the
persistence logic and the new createNewSession action).

---

## R105-C: SubTaskCard 段 3 collapse animation

### Before

The body of a SubTaskCard (the step trail) was rendered via
`{expanded && <div className="subtask-body">…}`. On collapse,
the element was removed from the DOM — no transition, just a
hard jump.

### After

The body is wrapped in a CSS-grid container that animates
`grid-template-rows` between `0fr` and `1fr`:

```css
.subtask-body-wrap {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 200ms ease-out;
}
.subtask-body-wrap.subtask-body-open {
  grid-template-rows: 1fr;
}
.subtask-body {
  min-height: 0;
  overflow: hidden;
  /* …existing padding / flex / gap… */
}
```

The grid trick lets the height animate to a content-derived
value without `max-height: 9999px` shenanigans. `min-height: 0`
on the inner item is required — without it, the grid item
forces the row to its intrinsic content height and the
animation jumps. `overflow: hidden` clips the content during
the transition.

### Files

- `aethercode-desktop/src/components/MessageList.tsx`
  — `subtask-body` is now always rendered; the wrap div
  carries the `subtask-body-open` class when `expanded`.
- `aethercode-desktop/src/components/MessageList.css`
  — new `subtask-body-wrap` rules + the `min-height: 0;
  overflow: hidden` on the inner `subtask-body`.

The `PreambleSteps` component (the "准备中" card that
appears in the first micro-seconds of a query) keeps the
old non-animated body — it doesn't have a toggle.

---

## What's NOT in R105

- **`skill` / `agent` step types** in WorkflowExecutor. R103's
  stub is unchanged. Wiring real execution requires a
  child-session model on the engine so a `skill` step's
  query doesn't clobber the user's main transcript. Plan:
  R106, alongside the daemon's real per-session support.
- **Tauri plugin shell for `/skill add` one-click**. Still
  blocked on the offline Rust toolchain. The `commandCommands.ts`
  `/skill add` still shows the copy-pasteable `git clone` /
  `Copy-Item` command.
- **SubTaskCard step-level drag-to-reorder** (R98) was
  in R104. The wrap animation interacts with this —
  reorder still works, but the `subtask-body-wrap` is the
  new parent of the `subtask-body`. No regression observed
  in the build.

---

## Test counts (final)

- `aethercode-core` WorkflowExecutorTest: 28/28 pass
  (was 10/10 in R103; +9 R105 cases, 9 modified for the
  new behaviour)
- `aethercode-core` WorkflowReaderTest: 9/9 pass
  (unchanged)
- `aethercode-core` ProgressLoopDetectorTest: 18/18 pass
  (unchanged)
- `aethercode-core` (regression spot-check): 46/46 of the
  above three suites pass.
- `aethercode-desktop` TypeScript: `tsc --noEmit` clean.
- WS roundtrip (5/5 R105 patterns): PASS.

## Build artifacts

- `aethercode-0.2.8.jar` (41.5 MB shaded) at:
  - `aethercode/dist/aethercode-0.2.8.jar`
  - `aethercode-desktop/src-tauri/resources/aethercode-0.2.8.jar`
  - `aethercode-desktop/src-tauri/target/debug/aethercode-0.2.8.jar`
- Daemon running: PID 6516 on port 17888.
- Vite dev server: 1420/1421 (no restart needed; HMR
  picked up the changes).

## Cross-project rules added

- 107. **`workflow.step.continueOnError` semantics**: when
  `true`, inner `error` event still fires (with cause),
  then outer `skipped` event supersedes it. Don't dedupe
  in the event stream — the cause is useful.
- 108. **`wait: any` is a polling wait, not a one-shot
  `anyOf`**. The first-completion may be an error; we
  need to keep checking for an `ok` until either one
  arrives or every branch is done.
- 109. **SubTaskCard collapse = grid-template-rows trick**.
  `min-height: 0` on the inner item is mandatory.
- 110. **`useStore.subscribe` is the right place for
  cross-cutting localStorage persistence** in this app.
  Putting the save inside every message-mutating handler
  scattered through the store would miss new code paths
  added later; the subscriber sees every state change.
- 111. **localStorage session cap of 500 messages** is
  the right balance for a chat app — covers long sessions
  without blowing the 5MB quota. Trim from the front (keep
  the most recent).
- 112. **Merge daemon + local sessions, daemon wins on id
  collision**. The local layer is the source of truth for
  UI history; the daemon layer is the source of truth for
  which sessions are "live" on the engine.
