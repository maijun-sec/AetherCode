# R229 — Daemon-driven Plan tab via `task_state kind=todo_update` (2026-09-07)

## TL;DR

R228 wired the "Plan" tab to a wire-event fast-path (`tool_use_start` +
`input.todos`). R229 completes the canonical wire: the engine's
`AppState` already publishes the list via `setTodoList` — R229 hooks
that publish path to a JSON-RPC notification so the desktop
sees the daemon's authoritative list, not a one-layer-removed
parse. The Plan tab now reads the daemon list when available and
falls back to the R228 wire list for the sub-second window before
the notification round-trips.

Net result: 1054/1054 vitest (R228 baseline 1033 + R229 new 21),
0 regressions, `desktop.exe` SHA bumped from R228.

## Why this round

R200+ designed a `task/event kind=todo_update` event for the
desktop's `TodoBoard` component, but:

- The `task/event` SSE endpoint was never implemented on the
  daemon. `aethercode-protocol/`'s `session/events` is a
  subagent RPC method, not a main-agent notification.
- The `TodoBoard` component ended up being dead code.

R228 took a quick fix: the desktop `tool_use_start` event
carries the raw `input.todos` array, so the Plan tab parsed
that on the way through. It worked, but the data was one
layer removed from the engine's canonical state — if the
engine's `setTodoList` ever ran via a different path
(e.g. `sub_todo_write` flipping a sub-task status without
a top-level `todo_write`), the desktop would have missed it.

R229 closes that gap. The `AppState.onTodoUpdate` listener
already fires on **every** `setTodoList` call. R229 hooks
it to a JSON-RPC notification and the desktop picks it up
off the existing `ws-notify` IPC channel.

## Wire path

```
daemon AppState.setTodoList(list)
  |
  v
AetherCodeMethods.onTodoUpdate listener (NEW in R229)
  |
  v
notifyCustom(NOTIFY_TASK_STATE, Map.of(
  "kind", "todo_update",
  "sessionId", engine.appState().sessionId(),
  "params", Map.of("todos", todos)
))
  |
  v
tauri lib.rs ws-notify emit  (unchanged)
  |
  v
desktop store/index.ts rpc.on('task_state', ...) handler (NEW in R229)
  |
  v
set({ daemonTodos: normalized TodoItem[] })
  |
  v
AgentTasksPanel: todos = daemonTodos.length > 0 ? daemonTodos : wireTodos
                  (daemon > wire source-priority)
```

## Changes (daemon)

### `aethercode-protocol/.../AetherCodeMethods.java`

- **New constant** (line 86): `TODO_UPDATE_KIND = "todo_update"`.
- **New listener** (line 561): `engine.appState().onTodoUpdate(todos -> ...)`:
  - Reads `engine.appState().sessionId()` for the wire sessionId.
  - Emits `NOTIFY_TASK_STATE` with `kind=todo_update` discriminator.
  - Wraps the inner list under a `params.todos` key (matches the
    multiplexed `task_state` design — see TODO_UPDATE_KIND comment).
  - All `RuntimeException`s are swallowed so a buggy notifier
    can never break the engine's todoList publish path.
- **Existing `Main.runHeadless` listener**: untouched. Both
  writers fire independently and the renderer de-dupes on the
  wire (same data, same instant, same id).
- **Imports**: `Consumer` + `JsonRpcNotification` already present.

## Changes (desktop)

### `src/store/index.ts`

- **New AppState field** (line 1031): `daemonTodos: TodoItem[]`
  - Renamed from `todos` to avoid collision with the dead R200+
    flat-shape `todos` field (R200+ uses `{id, title, status, ...}`
    with no subtasks; R229 uses the nested `TodoItem` shape).
- **New initial state**: `daemonTodos: []`.
- **New `clearCurrentQuery` reset**: adds `daemonTodos: []` next
  to the existing `currentTodos: []` reset.
- **New `rpc.on('task_state', ...)` handler** (line 2729):
  - Defensive type check (`!params || typeof params !== 'object'`).
  - Filters on `params.kind === 'todo_update'` — drops future
    multiplexed kinds (e.g. `kind=session_idle`).
  - Reads `params.params.todos` (the wire's nested shape).
  - Validates `Array.isArray(raw)` before mapping.
  - Normalises Map<String,Object> → camelCase `TodoItem[]`:
    - top-level: `{content, status, active_form?, index, updatedAt,
      subtasks: TodoSubTask[]}`
    - sub-task: `{id, content, status, summary?, index}`
  - Writes via `set({ daemonTodos: next })`.

### `src/components/AgentTasksPanel.tsx`

- **New source-priority selector** (line 108-110):
  ```ts
  const daemonTodos = useStore((s) => s.daemonTodos);
  const wireTodos = useStore((s) => s.currentTodos);
  const todos = daemonTodos.length > 0 ? daemonTodos : wireTodos;
  ```
- The panel now reads the daemon list when it has content and
  falls back to the R228 wire list for the sub-second window
  before the daemon notification arrives.
- Comments updated to document the new source-priority contract.

### `src/components/agentTasksPanelR229.test.ts` (NEW)

21 source-only assertions covering:

- store: `daemonTodos` field exists + initial `[]` + clearCurrentQuery
  resets
- store: `rpc.on('task_state', ...)` handler registered
- store: handler filters `params.kind === 'todo_update'`
- store: handler reads `params.params.todos` (nested shape)
- store: handler validates `Array.isArray(raw)`
- store: handler normalises wire shape (active_form, summary)
- store: handler writes via `set({ daemonTodos: next })`
- store: handler is defensive against missing params
- panel: reads `daemonTodos` from store
- panel: reads `currentTodos` as fallback (R228 quick fix)
- panel: source-priority literal `daemonTodos.length > 0`
- panel: still renders empty state when both empty
- java: `TODO_UPDATE_KIND = "todo_update"` constant
- java: `engine.appState().onTodoUpdate(...)` listener registered
- java: emit uses `NOTIFY_TASK_STATE` method
- java: emit payload includes `kind/sessionId/params`
- java: imports `Consumer` + `JsonRpcNotification`
- regression: R228 `currentTodos` wire-event path still wired
- cross-ref: `TodoWriteTool` produces the matching wire shape

## Test results

- tsc -b: 0 errors, 0 warnings ✓
- vitest R229 new: 21/21 ✓
- vitest full: **1054/1054** (vs R228 1033/1033, +21 R229, 0 regressions) ✓
- vite build: 7.88s ✓
- bundle markers: 5/5 (Plan not started / task_state / daemonTodos /
  currentTodos / RightPanel) ✓
- tauri build: see release artifacts

## Release artifacts

| Round | jar SHA256 | desktop exe SHA256 | zip SHA256 | size | dir |
|-------|------------|--------------------|------------|------|-----|
| R225 | `3FFC5433...` | `B94E58C9...` | `75F2E2FE...` | 94,014,708 | release/aethercode-0.2.1/ |
| R226 | `3FFC5433...` | `9C3D2DFF...` | `C20E2FAB...` | 92,504,190 | release/aethercode-0.2.1/ |
| R227 | `3FFC5433...` | `023DAF26...` | `716981FF...` | 144,112,930 | release/aethercode-0.2.51/ |
| R228 | `3FFC5433...` | `E7BF13CF...` | `E79C8A79...` | 144,117,789 | release/aethercode-0.2.52/ |
| **R229** | **`794F965C15B6817B6364C4B63A4318E8C0EE1D18F4A6E967A0F6F77849CF19A8`** | **`F02CE0E535D3BC9336A73AD8AA358F7364EF8BD9084E86DB2FAAC2F4156EB2AE`** | **`14D45CDF455C3B60AD364B855308B348892ACF45F1149715BC6D44ACC94343C6`** | (zip below) | **release/aethercode-0.2.53/** |

### R229 release artifacts (final)

- `aethercode-0.2.53.jar` — 55,585,378 B — SHA256
  `794F965C15B6817B6364C4B63A4318E8C0EE1D18F4A6E967A0F6F77849CF19A8`
  (mvn build output, copied to dist and src-tauri/resources/)
- `desktop/aethercode-desktop.exe` — 3,982,336 B (+1,536 B vs R228) —
  SHA256 `F02CE0E535D3BC9336A73AD8AA358F7364EF8BD9084E86DB2FAAC2F4156EB2AE`
- `desktop/resources/aethercode.jar` — 55,585,378 B — SHA256
  `794F965C...` (matches dist, embedded in exe by tauri build)
- `aethercode-0.2.53.zip` — 10 files, 217,346,899 B uncompressed —
  SHA256 `14D45CDF455C3B60AD364B855308B348892ACF45F1149715BC6D44ACC94343C6`
- README.md, ac-tui/, ac-tui-standalone.exe, run-tui.bat/sh — copied
  from R228 release (unchanged this round)

R225-R228 all shared the same `3FFC5433...` because the desktop
never actually got the daemon changes (R222-R225 were running
R210's jar; R226.5 fixed the version mismatch; R228 had no daemon
changes). R229's `794F965C...` is the first genuinely new daemon
SHA in 9 rounds.

## Tools / scripts

- `scripts/promote-r229-release.py` (NEW — copy new exe + resources/
  + canonical jar to `release\aethercode-0.2.53\`, mirror tui files
  from R228)
- `scripts/zip-r229.py` (NEW — re-zip the release dir, also prints
  the final zip SHA256)
- `D:\Users\maijun\AppData\Local\Temp\promote-r229-real.py` (one-shot
  manual fix — see "Known issue" below)

## Known issue: `promote-jar.py` is broken for rounds with daemon changes

`scripts/promote-jar.py` (R227) copies from
`aethercode-{cur_v}.jar` (the previous versioned jar) to
`aethercode-{new_v}.jar` (the new versioned jar). It does NOT
copy from `aethercode-0.2.1.jar` (the mvn build output).

This was fine when the previous versioned jar happened to
contain the latest daemon changes (R226 → R228 had no
daemon changes, so all versioned jars were R225 content).
But R229 had daemon changes, and the script silently
propagated R228's (= R225's) content instead of R229's.

The result would have been: desktop spawns a jar that
DOES NOT contain the `task_state` listener, so the
Plan tab would have permanently fallen back to the
R228 wire-event path. R229 would have looked fine in
vitest but broken in production.

**Fix for R230**: rewrite `promote-jar.py` to ALWAYS
check mtime of `aethercode-0.2.1.jar` vs the previous
versioned jar. If 0.2.1 is newer, use 0.2.1; otherwise
warn that mvn wasn't run.

The R229 release was saved by manual verification: I
checked the mvn output's SHA (`794F965C...`) before
running the script, noticed the script would have
copied the wrong jar, and ran a manual override.

## Lessons

1. **Always verify the jar SHA before `promote-jar`**. The
   script's "idempotent" guard means a wrong copy sticks
   for the rest of the R-series. The mvn output SHA is
   the canonical signal — the versioned jar's SHA is
   just a rename of whatever was there before.

2. **Source-priority over duplicate fields**. R229 keeps
   `currentTodos` (R228 wire-event) alive as a fallback
   because the sub-second gap before the daemon
   notification arrives is real (typically ~50ms in
   practice). The panel reads `daemonTodos` first,
   falls back to `currentTodos` only when daemonTodos
   is empty. Two fields, one UI, both work.

3. **Field-naming clash with dead code**. R200+ left a
   `todos: Array<{id, title, ...}>` field on the same
   AppState with a different shape. R229 renamed its
   field to `daemonTodos` rather than reuse the dead
   field — smaller blast radius, clearer name.

4. **R200+ design debt is now closed at the wire level**.
   The R200+ `TodoBoard` component is still dead (its
   `useSessionDetail` query and `subscribeKind` SSE
   subscription both never work), but the Plan tab
   now delivers the live list via a working path.
   R230+ can either: (a) wire the daemon notification
   to `TodoBoard` too (reuses R229's emit, deletes
   the dead SSE path), or (b) delete `TodoBoard`
   entirely if R228's Plan tab is the only consumer.

5. **vitest JSON reporter is more reliable than the
   default**. The PowerShell capture of `npx vitest run`
   mangles the summary block; `--reporter=json` writes
   a clean JSON file with `numTotalTests/numPassedTests/
   numFailedTests/numTotalTestSuites` that PowerShell
   can `ConvertFrom-Json` cleanly.
