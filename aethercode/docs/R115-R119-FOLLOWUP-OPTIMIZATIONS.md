# R115-R119 — Follow-up Optimizations (2026-08-19)

## Context

R114 shipped the three user-reported 3-fix round
(tools refresh, workflow import, loop UX). R115-R119
are the five follow-up rounds the user requested —
all engineering-debt / DX / self-diagnosis surfaces
that the R114 follow-up candidates list had
queued.

| Round | Title | Lines (renderer) | Lines (Java) | New tests |
|---|---|---|---|---|
| R115 | Loop detector threshold settings | +250 | +90 | +22 |
| R116 | RPC diagnostic panel | +250 (TSX) + 150 (CSS) + 30 (methods.ts) + 30 (store) | 0 | +23 |
| R117 | Workflow picker LRU (recent 5) | +80 | 0 | +14 |
| R118 | refreshEngineState (R114-A pattern补齐) | +60 | 0 | +12 |
| R119 | Persistent subagent toast | +50 | 0 | +10 |
| **Total** | | **~870** | **+90** | **+81** |

**Cumulative vitest**: 89 (R113) → 146 (R114) → 227 (R115-R119)

## R115 — Loop detector threshold settings + RPC

### Why

Pre-R115 the loop detector's window / threshold
pair was fixed at engine build time
(`AetherCodeEngine.Builder.loopDetector(window, threshold)`).
The only way to change them was to re-spawn the JVM,
which is a bad UX for "I want the detector to be
more lenient right now". R115 adds a runtime
RPC + a Settings panel section that lets the user
tweak the thresholds live.

### Java backend (AetherCodeMethods.java)

* `setLoopDetectorThresholds({window, threshold})`
  registered in `dispatcher.registerAll()` (R97 pattern).
* `HttpJsonRpcServer` case added to the WS / HTTP
  dispatch switch (so the desktop's Tauri→WS path
  reaches it).
* Validation: window must be >= threshold when both
  are positive; either axis at 0 / negative disables
  the detector entirely. The validation error
  surfaces in the desktop's "❌" status pill.
* Delegates to `target.queryEngine().setLoopDetector`
  (the same setter the builder uses). The volatile
  fields are read live on every `query()`.

### Renderer

* `lib/methods.ts`: typed `setLoopDetectorThresholds`
  wrapper + `loopWindow` / `loopThreshold` fields on
  `EngineState`.
* `store/index.ts`: `setLoopDetectorThresholds` action
  with optimistic engineState update + post-RPC
  `refreshEngineState()` (R118 invariant).
* `SettingsPanel.tsx`: new "Loop detection" section
  with a master enable checkbox + 2 range sliders
  (window 2-32, threshold 1-window). 250ms debounce
  keeps the WS calm during a drag.
* Bidirectional sync: slider changes push to daemon
  via the RPC; periodic `refreshEngineState()` (R118)
  pulls the canonical values back so the UI never
  drifts from the daemon.

### Files

* MOD: `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  (register + impl, ~90 lines)
* MOD: `aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`
  (dispatch case + /api/methods entry)
* MOD: `aethercode-desktop/src/lib/methods.ts`
  (EngineState fields + RPC wrapper)
* MOD: `aethercode-desktop/src/store/index.ts`
  (setLoopDetectorThresholds action + EngineState
  engineState fields wiring)
* MOD: `aethercode-desktop/src/components/SettingsPanel.tsx`
  (Loop detection section + sliders + debounce)
* MOD: `aethercode-desktop/src/components/SettingsPanel.css`
  (field-group + inline + slider-label styles)
* NEW: `aethercode-desktop/src/components/SettingsPanelR115.test.ts` (22 tests)

## R116 — RPC diagnostic panel (full)

### Why

The R114-A investigation relied on direct
`ws://127.0.0.1:17899/ws` probes (Python script).
R116 ships an in-app panel that exposes the same
diagnostic information so the user can self-diagnose
the next "tools 显示为空" without external help. The
panel was the "full" option (vs "minimal StatusBar
corner") — 50 events, method + status filter, expandable
payload, persistent buffer.

### Architecture

* `AetherCodeRpc.call()` is wrapped to emit an
  `RpcEvent` on every round-trip (success or failure).
  The event carries method, params (JSON-clone),
  durationMs, success, error?, ts. The wrapper is
  centralised — every public RPC method goes through
  `call()`, so we get 100% coverage with one
  instrumentation point.
* `AetherCodeRpc.onRpcEvent(handler)` exposes a
  subscription API. Handlers are stored in a `Set`
  so a disconnected store doesn't pin dead closures.
* The store subscribes once in `initialize()` and
  re-subscribes on reconnect (the previous listener
  is unsubscribed first to avoid double-counting).
* `recentRpcEvents: RpcEvent[]` is capped at 50
  (newest-first). A higher cap would make the panel
  render slower; a lower cap would make 5-minute-old
  failures scroll out of view.

### Component

* `RpcDiagnosticsPanel.tsx`: modal with method
  substring filter + status filter (all / ok / err)
  + row click to expand params / error.
* Esc closes (R114-C / R119 persistent-UI affordance
  pattern).
* Ctrl/Cmd+` opens (e.code === 'Backquote' so the
  chord works on AZERTY layouts where backtick is
  Shift+`).
* "Clear" button → `store.clearRpcEvents()`.

### Files

* MOD: `aethercode-desktop/src/lib/methods.ts`
  (RpcEvent type, onRpcEvent/emitRpcEvent, call wrap, ~80 lines)
* MOD: `aethercode-desktop/src/store/index.ts`
  (recentRpcEvents, recordRpcEvent, clearRpcEvents,
  module-scope subscription handle, ~50 lines)
* NEW: `aethercode-desktop/src/components/RpcDiagnosticsPanel.tsx` (~250 lines)
* NEW: `aethercode-desktop/src/components/RpcDiagnosticsPanel.css` (~150 lines)
* MOD: `aethercode-desktop/src/App.tsx`
  (Ctrl+` hotkey + panel render)
* NEW: `aethercode-desktop/src/components/RpcDiagnosticsR116.test.ts` (23 tests)

## R117 — Workflow picker LRU (recent 5)

### Why

"I run the same workflow every session" — a user
who runs `safe-commit` 20 times a day shouldn't have
to scroll past the rest of the picker each time. R117
adds a "最近使用" section above the full list,
showing the 5 most-recently-selected workflow names.

### Implementation

* `recentWorkflows: string[]` on the store, capped
  at 5, deduped, push-to-front.
* Persistence: localStorage keyed by cwd
  (`aethercode-recent-workflows:<cwd>`). A project
  A LRU doesn't bleed into project B.
* The store handles the LRU semantics in
  `recordRecentWorkflow`; the component is dumb.
* `setActiveWorkflow(wf)` automatically calls
  `recordRecentWorkflow(wf.name)` so the picker's
  onClick path LRU-pushes without explicit wiring.
* The picker intersects the LRU with the current
  `availableWorkflows` so a workflow that was in
  the LRU but has since been deleted doesn't render
  as a ghost button.

### Files

* MOD: `aethercode-desktop/src/store/index.ts`
  (recentWorkflows field, recordRecentWorkflow,
  initialize() LRU hydrate, ~50 lines)
* MOD: `aethercode-desktop/src/components/MessageInput.tsx`
  (LRU section render, ~30 lines)
* MOD: `aethercode-desktop/src/components/MessageInput.css`
  (workflow-recent-section + label + divider, ~30 lines)
* NEW: `aethercode-desktop/src/components/workflowLruR117.test.ts` (14 tests)

## R118 — refreshEngineState (R114-A pattern补齐)

### Why

Pre-R118 the engineState field was a one-shot
snapshot from initialize(). R114-A established the
pattern (refresh action + periodic timer + lazy
trigger on UI open) and applied it to `tools`. R118
applies the same pattern to `engineState`, the field
that drives the model dropdown, permission mode
select, skip-confirmation counter, memory badge, and
StatusBar "X tools" line.

### Implementation

* `refreshEngineState()` action — `getState()`
  wrapped with the R114-A best-effort + preserve-
  on-failure pattern. Caps the response into a
  fresh `engineState` field; stamps
  `engineStateRefreshedAt: Date.now()`.
* `engineStateTimer: number | null` — 15 s periodic
  poll (faster than the 30 s tools timer because
  engine state changes more often: model switches,
  permission mode changes, loop threshold tweaks).
* `setModel` / `setPermissionMode` / `switchProvider`
  kick `refreshEngineState()` after the optimistic
  local update — the canonical state lands within
  ~50 ms rather than waiting for the next tick.
* The same connectionState guard as the other timers.

### Files

* MOD: `aethercode-desktop/src/store/index.ts`
  (refreshEngineState, engineStateTimer, wiring in
  setModel / setPermissionMode / switchProvider, ~60 lines)
* NEW: `aethercode-desktop/src/store/engineStateR118.test.ts` (12 tests)

## R119 — Persistent subagent toast

### Why

R114-C established the lesson: **auto-dismiss is
user-hostile for state-changing notifications**.
The loop banner was the first application. R119
applies the same lesson to the subagent terminal
toast — pre-R119 the toast auto-dismissed after 4s;
a user who kicked off a subagent and switched focus
would return to find the toast gone.

### Implementation

* Removed the 4s auto-dismiss entirely. The toast
  now stays put until the user dismisses it.
* Esc hotkey to dismiss (R114-C persistent-UI
  affordance pattern).
* Promoted the × glyph to a real focusable
  `<button>` with `aria-label="Dismiss notification"`.
  The pre-R119 glyph was a visual hint only —
  keyboard / screen-reader users couldn't reach it.
* Clicking the body still dismisses (same affordance
  as pre-R119) for users who don't realise the new
  button is there.
* The button's `onClick` uses `e.stopPropagation()`
  to prevent the body-click handler from firing
  twice.

### Files

* MOD: `aethercode-desktop/src/components/SubagentToast.tsx`
  (removed timer + Esc hotkey + button, ~50 line changes)
* MOD: `aethercode-desktop/src/components/SubagentToast.css`
  (button styles)
* NEW: `aethercode-desktop/src/components/SubagentToastR119.test.ts` (10 tests)

## Cross-cutting design lessons (R115-R119)

1. **Bidirectional sync is the right pattern for
   live settings sliders.** The Settings panel's
   loop detector sliders (R115) and the R118 engine
   state both need: "user changes UI" → "push to
   daemon" + "daemon state changes" → "pull to UI".
   The store handles both halves (optimistic update
   + post-RPC refresh). Without the pull half, the
   UI silently drifts from the daemon when something
   else (another window, a CLI flag, a future
   automation) updates the same value.

2. **Centralised instrumentation beats per-call
   wrapping.** R116's RPC event recording sits in
   `AetherCodeRpc.call()` — a single point of entry
   captures 100% of public methods. The alternative
   (wrapping each public method) would have meant
   editing 30+ methods and would have been
   regression-prone (any new method forgets to wire
   the event).

3. **Re-init must tear down listeners before
   installing new ones.** The R116 subscription is
   stored at module scope (`rpcEventUnsubscribe`) so
   a reconnect can unsubscribe the old listener
   before installing a new one. Without this guard,
   a reconnect would double-count events (old +
   new listener both push to the same buffer). The
   same lesson applies to the engineStateTimer /
   skipStatsTimer / toolsTimer / pre-warm slots.

4. **Event log panels should be on a hotkey, not a
   menu.** R116's Ctrl+` puts the panel one keystroke
   from any state of the app, including while a run
   is in flight. A hamburger-menu entry would have
   forced the user to (a) find the menu, (b) click
   the entry, (c) lose focus on whatever they were
   doing. The hotkey is the "I'm debugging right
   now" affordance.

5. **LocalStorage quota errors are quiet.** R117's
   `recordRecentWorkflow` and R116's `recordRpcEvent`
   both wrap localStorage in `try / catch`. Private
   mode, quota exhaustion, or disabled storage
   shouldn't break the app — the in-memory state is
   the source of truth; the localStorage write is a
   nice-to-have.

6. **LRU + Map lookup keeps the picker O(N).** R117
   builds a `byName: Map<string, WorkflowDoc>` from
   `availableWorkflows` once, then maps the LRU
   through it. O(N) instead of O(N×M) for the
   common case of N=5 (LRU) and M=20 (workflows).

## Test counts (R115-R119)

| Round | New tests | Cumulative |
|---|---|---|
| R86-R113 (baseline) | — | 89 vitest |
| R114 | +57 | 146 |
| R118 | +12 | 158 |
| R115 | +22 | 180 |
| R119 | +10 | 190 |
| R117 | +14 | 204 |
| R116 | +23 | **227** |

TypeScript typecheck: ✓
Java backend tests: pending rebuild
Engine state RPCs covered: 17 (pre-R115) + 1 (setLoopDetectorThresholds) = 18

## R115-R119+ follow-up candidates

* **R120**: auto-approve "low risk" pending permissions (the
  R87 useEffect already does this once on connect;
  expand to a daemon-side heuristic that auto-resolves
  on emission, with a R86-style per-tool always-allow
  hook).
* **R121**: command palette RPC integration (the
  palette already lists slash commands; expose the
  raw RPC list too so power users can fire any RPC
  from the palette).
* **R122**: persistent engine state across reloads
  (localStorage the model / mode / loop thresholds
  the user picked, restore on next launch).
* **R123**: RPC export — let the user save the
  diagnostic panel's event log to a .jsonl file
  for offline analysis.

## R97-M status

Still awaiting A/B/C decision from user. The
follow-up candidates (R120-R123 above) are
intentionally non-architectural so the team can
ship value while the architecture rework question
remains open.
