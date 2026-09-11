# R91: Live Subagent Progress in TUI

Date: 2026-08-16
Round: R91-D
Status: SHIPPED

## TL;DR

Background subagents are now visible in the TUI in real time.
When the user (or the model) fires `spawn_agent(background=true)`,
the daemon's `SubagentRegistry` fires a `subagent_event` JSON-RPC
notification; the TUI's reducer updates `subagentStatus` and
`runningSubagents`, and the StatusBar renders a compact
`[sag-N] running` / `[sag-N] done 1.4s` indicator. Terminal
events (completed / failed / cancelled) also push a transient
toast so the user can read the result without watching the bar.

## Architecture

```
  AgentTool.runBackgroundJob (daemon thread)
        |
        v
  SubagentRegistry.markCompleted / markFailed / markCancelled
        |
        |  onChange listener (registered in AetherCodeMethods ctor)
        v
  JsonRpcNotification("subagent_event", {jobId, role, status, ...})
        |
        v
  JsonRpcClient (stdio)
        |
        v
  tui.tsx onNotification switch
        |
        v
  reducer dispatch {type: "subagentEvent", ...}
        |
        v
  state.subagentStatus + state.runningSubagents
        |
        v
  StatusBar.tsx renders the indicator + ToastStack pops a transient
```

The registry → notification bridge is a single line of code in
`AetherCodeMethods` constructor:

```java
SubagentRegistry.instance().onChange(ev -> {
    try {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId",     ev.jobId());
        payload.put("role",      ev.role());
        payload.put("status",    ev.status().name());
        payload.put("elapsedMs", ev.elapsedMs());
        payload.put("summary",   ev.summary());
        payload.put("atMs",      ev.atMs());
        notifier.accept(new JsonRpcNotification(
            JsonRpcMessage.VERSION, NOTIFY_SUBAGENT_EVENT, payload));
    } catch (Throwable nfy) {
        LOG.warn("R91-D subagent notify failed: {}", nfy.getMessage());
    }
});
```

The TUI never imports `SubagentRegistry` directly — it just
sees the notification as a JSON-RPC method. This keeps the
transport contract clean and would let a different client
(e.g. the upcoming `aethercode-app` Tauri app) use the same
notification without changes.

## Files changed / added

### Backend (Java)

- **`aethercode-tools/.../task/SubagentRegistry.java`** —
  added `SubagentEvent` record, `onChange(Consumer)` subscribe,
  `fireChange(SubagentEvent)` fan-out. Fires on every state
  transition: `register`, `markCompleted`, `markFailed`,
  `markCancelled`. The listener list is a `CopyOnWriteArrayList`
  (process-scoped, like `BashJobRegistry`). Misbehaving listeners
  are caught and logged so a bad TUI repaint can't kill the
  daemon thread.

- **`aethercode-protocol/.../methods/AetherCodeMethods.java`** —
  added `NOTIFY_SUBAGENT_EVENT = "subagent_event"` constant;
  constructor subscribes to the registry and emits the
  notification. The subscription is best-effort and lives
  for the daemon's lifetime (matching the registry).

- **`aethercode-tools/.../task/SubagentRegistryTest.java`** —
  +8 listener tests (`onChange_*`, `subagentEvent_isTerminal`,
  `onChange_misbehavingListenerDoesNotBreakRegistry`).
  **Total: 24 tests, 0 fail.**

### Frontend (TypeScript)

- **`aethercode-tui/src/state.ts`** —
  +`subagentStatus: string` and `runningSubagents: number`
  on the `State` interface. `INITIAL` defaults to
  `""` and `0`. `Action` gets a new `subagentEvent` variant
  with the daemon's payload shape. The reducer updates the
  status and bumps the running count on `RUNNING`, drops
  it on terminal events. Added `formatSubagentStatus(jobId,
  status, elapsedMs)` helper for compact rendering.

- **`aethercode-tui/src/tui.tsx`** —
  added a `"subagent_event"` case in the `onNotification`
  switch. Dispatches the reducer action and, for terminal
  events, also pushes a transient toast (`COMPLETED` →
  kind `ok`, `FAILED` → `err`, `CANCELLED` → `warn`).
  Running transitions stay in the StatusBar only — toast
  spam on every spawn would bury the chat.

- **`aethercode-tui/src/components/StatusBar.tsx`** —
  added an inline indicator in the "ready" state of the
  bar. Renders the latest `subagentStatus` in cyan
  (`t.accent`). If multiple subagents are running
  (`runningSubagents > 1`), shows "(N running)" after the
  most recent event.

- **`aethercode-tui/scripts/test/r91d-subagent.test.mjs`** —
  new test file. **12 tests, 0 fail.** Covers INITIAL
  defaults, all four state transitions (RUNNING /
  COMPLETED / FAILED / CANCELLED), the `formatSubagentStatus`
  helper, and a full lifecycle (0 → 1 → 2 → 1 → 0 running
  count) to verify the count stays consistent.

## Decisions / trade-offs

- **Per-event, not polled.** The TUI relies on the daemon
  pushing events. A poll loop would have been a fallback,
  but the bridge is cheap (one `notifier.accept` per
  transition) and the events arrive within ~1ms of the
  state change. The fallback `subagent_status` RPC
  remains available for clients that don't subscribe.

- **Count is local.** `runningSubagents` is maintained by
  the TUI's reducer, not carried in the notification.
  Reasoning: the notification is a transition, not a
  snapshot. The reducer increments on `RUNNING`,
  decrements on terminal events. This works for the
  common case (one parent, many subagents) and is O(1).
  A multi-session daemon would need a session filter —
  left for a future round.

- **No AppState bridge.** The first iteration added a
  `subagentEventListeners` field to `AppState` mirroring
  the `sessionIdleListeners` / `messageAppendListeners`
  pattern. Reverted because `AppState` is **session-scoped**
  and `SubagentRegistry` is **process-scoped** — bridging
  would require `aethercode-core` (where `AppState` lives)
  to import `aethercode-tools` (where `SubagentRegistry`
  lives), which would invert the module dependency.
  Subscribing directly from `AetherCodeMethods` (which
  already depends on `aethercode-tools` transitively via
  the SDK) avoids the cycle.

- **Toast for terminal events only.** Spawning a subagent
  doesn't toast — the model already said "I'm running a
  subagent", and the StatusBar indicator is enough.
  Completion / failure is when the user might want to
  read the result, so we toast then.

- **tui-jline left out of scope.** Per user direction
  (R91 feedback): tui-jline is no longer being evolved;
  the future is `aethercode-tui` (TypeScript Ink) and the
  upcoming `aethercode-app` (Tauri). The earlier draft of
  R91-D added wiring to `tui-jline`'s `ReplApp` /
  `StatusBar`; reverted entirely.

## E2E walkthrough (for manual verification)

1. Build: `cd aethercode; .\build.ps1 -SkipTests` then
   `cd ..\aethercode-tui; node scripts/bundle.mjs`
2. Start: `java -jar aethercode\dist\aethercode-0.2.1.jar tui`
3. Run a query that uses `spawn_agent(background=true)`.
4. Watch the StatusBar: it should show
   `[sag-1] running` while the subagent is in flight, then
   `[sag-1] done 1.4s` on completion. A transient toast
   "subagent sag-1 done (1400ms)" appears at the top.
5. Spawn multiple in parallel: status bar shows
   `[sag-3] running (2 running)` for the latest event.

## Known limitations / R92+ candidates

- **R92**: TUI "background subagent panel" — a side panel
  listing all running + recently-finished subagents with
  per-job elapsed time, role, and a click-to-insert result
  shortcut. The data is already on the wire (the notification
  carries everything needed); the panel would just be a
  richer view of `subagentStatus` + a small ring buffer of
  the last N events.
- **R92**: aethercode-app (Tauri) parity — same notification
  → toast + sidebar list. The protocol is already
  transport-agnostic; the desktop just needs the same
  `case "subagent_event"` switch.
- **R92**: `subagent_cancel` RPC + Cancel button. The
  `markCancelled` path is wired but nothing cancels
  in-flight background jobs. A simple "interrupt" hook
  on the engine's `Task` would do it.
- **R93**: per-session subagent filter — for multi-session
  daemons, only show the subagents owned by the active
  session. The current `runningSubagents` is global.

## Cross-project status (2026-08-16)

| Project | Last shipped | Test count |
|---------|--------------|-----------:|
| AetherCode | **R91-D ship** (aethercode-tui + aethercode-protocol + aethercode-tools) | 86 Java + 12 TS new = +12 |
| Fathom (C/C++) | R459-4 partial | 1174 + 12 R459 |
| Plumb (Java) | R568 (sanitizer fix) | 1500+ all |
