# R92-A — Subagent live progress in the desktop app

**Date**: 2026-08-16
**Scope**: Tauri (aethercode-app) + aethercode-tools
**Status**: SHIPPED

## TL;DR

The desktop app now mirrors the TUI's R91-D behaviour: a
background subagent's lifecycle (RUNNING → COMPLETED /
FAILED / CANCELLED) surfaces live in the StatusBar (inline
indicator) and as a transient bottom-right toast on
terminal events. The wire is the same one the TUI uses —
the Java daemon's `SubagentRegistry` fires a `subagent_event`
JSON-RPC notification, the Rust backend's `ws-notify`
channel forwards it to the WebView, and the renderer's
Zustand store picks it up. The Rust side is unchanged; this
is a frontend-only round.

## What ships

### New files

- `src/store/subagentReducer.ts` — pure reducer + types
  (`SubagentStatus`, `SubagentJobView`, `SubagentState`,
  `SubagentTerminalEvent`). LRU-evicted in-memory job
  list (capped at 32) so long sessions don't grow
  unbounded. Pure functions only — no React, no Tauri,
  vitest-testable in isolation.
- `src/store/subagentReducer.test.ts` — 22 vitest cases
  covering INITIAL defaults, RUNNING/COMPLETED/FAILED/
  CANCELLED transitions, lifecycle (count 0→1→2→1→0),
  duplicate-terminal guard, startedAtMs preservation, LRU
  eviction (and the "never evict in-flight" rule),
  `formatSubagentStatus` helper, `isTerminalStatus`,
  `dismissTerminal`. All 22 pass.
- `src/components/SubagentToast.tsx` + `.css` — transient
  bottom-right toast for terminal events. 4-second
  auto-dismiss, click-to-dismiss, 3 tones (success / error
  / muted), shows engine-supplied elapsed time + summary.
  Self-manages the dismiss timer via `useRef` + a
  `lastKeyRef` so a re-subscribe replay does not restart
  the 4s clock.

### Modified files

- `src/store/index.ts`:
  - `AppState.subagent: SubagentState` field + export of
    the view types.
  - `AppState.dismissSubagentTerminal: () => void` action.
  - `rpc.on('subagent_event', ...)` subscription inside
    the `create()` block, sitting next to the
    `daemon.disconnected` handler. Defensive: unknown
    statuses are silently skipped, missing jobId is
    dropped, numeric fields are coerced via
    `Number.isFinite`.
  - Initial state in the return object defaults to
    `INITIAL_SUBAGENT` (empty status, 0 running, no jobs,
    no pending terminal).
- `src/components/StatusBar.tsx`:
  - Reads `subagent` from the store.
  - Derives a 4-tone colour (running / success / error /
    muted) from the most recent event.
  - Renders `⏵ {subagent.status}` with a `(N running)`
    suffix when more than one subagent is in flight.
- `src/components/StatusBar.css` — five new classes
  (`.subagent`, `.subagent-running`, `.subagent-success`,
  `.subagent-error`, `.subagent-muted`, `.subagent-idle`,
  `.subagent-count`).
- `src/App.tsx` — mounts `<SubagentToast />` once at the
  app root, after `<StepDetailModal />` and before
  `<SettingsPanel />` (z-index 1100 puts it above the
  StatusBar).

## Wire recap (unchanged from R91-D)

```
Java daemon — SubagentRegistry
  └─ fireChange(SubagentEvent) per RUNNING / COMPLETED / FAILED / CANCELLED
       │
       │  (AetherCodeMethods ctor — wired in R91-D)
       ▼
AetherCodeMethods.NOTIFY_SUBAGENT_EVENT JSON-RPC notification
  payload = { jobId, role, status, elapsedMs, summary, atMs }
       │
       │  (aethercode-desktop/src-tauri/src/lib.rs:514-535)
       ▼
Tauri app.emit("ws-notify", { method: "subagent_event", params: {...} })
       │
       │  (AetherCodeRpc.start in src/lib/methods.ts:209-219)
       ▼
AetherCodeRpc.on("subagent_event", handler)
       │
       │  (this round — store/index.ts R92-A subscription)
       ▼
reduceSubagent(state, { jobId, role, status, elapsedMs, atMs, summary })
  → new state with formatted status, running count, jobs map, lastTerminal
       │
       ├─► StatusBar inline indicator
       └─► SubagentToast (terminal events only, 4s auto-dismiss)
```

The Rust side already forwards every `method`/`params`
notification it sees on the WS to the WebView (see
`src-tauri/src/lib.rs:528-535`); the desktop R92-A
subscriber is the new entry point on the renderer side.

## Implementation notes

### Why a separate `subagentReducer.ts`?

Mirroring the TUI's `state.ts subagentEvent` reducer, the
desktop keeps the lifecycle logic in a pure module so it
can be tested in isolation. The store's
`rpc.on('subagent_event', ...)` handler is a one-liner
that feeds events into `reduceSubagent`; no business logic
in the Zustand `create()` body, no React, no Tauri. The
`formatSubagentStatus` helper produces the same labels as
the TUI's helper, so the two surfaces render identically.

### Why a StatusBar indicator AND a toast?

The StatusBar pill is for the "what's running right now"
case (always visible, low-noise). The toast is for the
"the background job you kicked off just finished" moment —
a single-line acknowledgement with the engine's own
elapsed time + summary so the user can tell at a glance
whether to dig in (FAILED with "boom" deserves attention;
COMPLETED with a 0.4s elapsed time is probably noise).

The toast never fires for RUNNING events (the StatusBar
covers that). Only terminal events. RUNNING events also
update `subagent.status` so the StatusBar pill changes
to `[sag-N] running` while the job is in flight, and
the next terminal event mutates it to `[sag-N] done 1.4s`
or similar.

### `lastKeyRef` guard in SubagentToast

`useEffect` with `subagent.lastTerminal` as a dep would
re-fire the timer if any unrelated state change triggers
a re-render that re-evaluates the hook. The `lastKeyRef`
guards against this by keying the timer on
`{jobId}:{status}:{atMs}` — only a *new* event restarts
the 4s window. This is also why a state refresh mid-toast
doesn't extend the lifetime.

### `MAX_SUBAGENT_JOBS = 32`

LRU cap on the in-memory job list. The reducer never
evicts an in-flight (RUNNING) job; if all 32+ jobs are
in-flight, the cap becomes a soft limit. In practice the
user would need 33 simultaneous in-flight subagents to
hit the soft cap, which doesn't happen on a single
desktop session.

### Toast colour tints

Three tones match the engine's terminal states:

- COMPLETED → green (`#5fbf6a`)
- FAILED → red (`var(--error)` / `#f48771`)
- CANCELLED → muted grey

The StatusBar pill uses the same vocabulary so the two
surfaces feel coherent (a green StatusBar pill and a
green toast for the same event).

## Verification

- `npx vitest run` — 22/22 pass.
- `npx tsc -b --noEmit` — clean.
- `npx vite build` — 503.01 kB bundle (was 499.35 kB,
  +3.66 kB for reducer + toast + indicator).
- `tauri build --no-bundle` — runs in background; the Rust
  side is unchanged, so this is just a relink + re-embed
  of the new bundle.

### E2E walkthrough (for the manual check)

1. Launch the Tauri app (or the dev build).
2. From the input bar, ask the model to run a long task
   in the background (e.g. "在后台跑一个 agent 把 ./docs
   总结成 README.md").
3. Watch the StatusBar — `[sag-N] running` should appear
   within ~1s of the model calling `spawn_agent(background=true)`.
4. When the subagent finishes, a toast appears
   bottom-right with the elapsed time + summary, and the
   StatusBar pill mutates to `[sag-N] done 1.4s`.
5. The toast auto-dismisses after 4s; the StatusBar
   indicator stays.

## Decisions

- **Pure reducer module** — keeps the lifecycle logic
  testable in isolation; mirrors the TUI's `state.ts`
  design so future R93+ changes can land in both surfaces
  in lockstep.
- **StatusBar tone derived from `lastTerminal` / running
  count** — single source of truth is the engine's event
  stream; we don't have a separate "is this success or
  error" flag.
- **4s auto-dismiss, click-to-dismiss** — the toast is a
  confirmation, not a modal. The StatusBar indicator
  keeps the same label visible.
- **No new Rust code** — the existing `ws-notify` channel
  already forwards every JSON-RPC notification from the
  daemon to the WebView, so the desktop just needs a new
  handler registration.
- **Mounted at App root** — same pattern as the
  command palette and step detail modal; z-index 1100
  puts the toast above the StatusBar (which is at the
  bottom of the layout grid).

## What's NOT in R92-A (deferred to R92+)

- **Sidebar list of all running subagents** — the data
  is already in `subagent.jobs`. R92-B can add a
  collapsible sidebar in the RightPanel with a row per
  in-flight subagent + click-to-insert result.
- **`subagent_cancel` RPC** — `markCancelled` path is
  wired on the Java side; the desktop just needs a
  Cancel button. R92-C.
- **Per-session subagent filter** — `subagent.jobs` is a
  global Map. When the desktop grows multi-session
  filtering for subagent tasks, the reducer will need a
  `currentSessionId` guard. Not a problem today (the
  desktop is single-session at the engine level).
- **Hardcoded `⏵` icon** — keep it simple; R93+ can
  switch to a proper icon (e.g. lucide-react) when the
  app standardises on an icon set.

## Files added / modified (R92-A)

```
aethercode-desktop/
├── docs/
│   └── R92-A-SUBAGENT-LIVE-PROGRESS.md          (NEW, this file)
├── src/
│   ├── App.tsx                                  (mount SubagentToast)
│   ├── store/
│   │   ├── index.ts                             (state + handler + action)
│   │   ├── subagentReducer.ts                   (NEW, pure reducer)
│   │   └── subagentReducer.test.ts              (NEW, 22 vitest tests)
│   └── components/
│       ├── StatusBar.tsx                        (inline indicator)
│       ├── StatusBar.css                        (4 tone classes)
│       ├── SubagentToast.tsx                    (NEW, toast)
│       └── SubagentToast.css                    (NEW, toast styles)
└── tauri-build-r92a.log                         (NEW, build log)
```
