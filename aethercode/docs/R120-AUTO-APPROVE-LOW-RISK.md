# R120 — Daemon-Side Auto-Approve Low-Risk Tool Calls (2026-08-19)

## Context

R87 introduced the renderer-side "auto-approve low-risk
prompts" defence-in-depth branch: when the engine emits
a `permission_request` for a read-only tool (file_read /
glob / grep), the desktop auto-responds Allow without
showing a dialog. R120 moves that decision to the daemon,
so the round-trip — emit → renderer → Allow — disappears
entirely. The cost is the same (one Allow per call), the
latency drops from ~10-30ms to ~0.

R120 also exposes the flag as a first-class toggle (default
`true` — same as R87's behaviour, so existing users see no
change). A status-bar badge shows the cumulative count and
the recent-10 list, so the user always knows what was
auto-allowed.

| Metric | Value |
|---|---|
| Java lines | +90 (`AetherCodeMethods.java` flag/counter/notification + `JsonRpcPermissionPrompter.java` short-circuit) |
| TS / TSX lines | +60 (StatusBar badge + tooltip + CSS) + 80 (store action + listener + 3 fields) + 20 (`methods.ts` wrapper) |
| New RPCs | `setAutoApproveLowRisk({enabled})` |
| New notification | `permission_auto_approved` |
| New tests | 19 Java + 16 TS = **+35** |
| Cumulative vitest | 227 (R115-R119) → **252** |
| Cumulative Java | 4216 (R115) → **4235** |

## R120 — Java backend

### `AetherCodeMethods.java`

```java
private volatile boolean autoApproveLowRisk = true;
private final AtomicLong autoApprovedCount = new AtomicLong(0);
```

* `isAutoApproveLowRisk()` / `getAutoApprovedCount()` —
  read by the prompter on every ask (volatile + atomic so a
  flip is visible without reconnect).
* `setAutoApproveLowRisk({enabled})` — registered in
  `dispatcher.register()` next to `setLoopDetectorThresholds`
  (R115's neighbour) and dispatched in `HttpJsonRpcServer`.
  Validation: `enabled` must be boolean (or 0/1 number, since
  the Tauri→WS bridge sometimes serialises as int).
  Returns `{ok, enabled, autoApprovedCount}` so the store's
  optimistic update doesn't need a follow-up `getState`.
* `recordAutoApproved(tool, input, reason)` — increments the
  counter and emits `permission_auto_approved` with payload
  `{requestId: "auto-<uuid>", tool, input, reason, atMs, autoApprovedCount}`.
  The synthetic `auto-` prefix on `requestId` lets the
  renderer distinguish from real `permission_request` ids.

### `JsonRpcPermissionPrompter.java`

```java
if ("low".equals(riskLevel) && methods.isAutoApproveLowRisk()) {
    methods.recordAutoApproved(tool.name(), input, question);
    return CompletableFuture.completedFuture(
            new PermissionResult.Allow(input));
}
```

The short-circuit sits **before** the `askPermission` call,
so low-risk tool calls never leave the daemon. Medium /
high / critical risk still go through the asker — the user
still sees a dialog for `bash`, `file_write`, etc.

## R120 — Renderer

### `StatusBar.tsx`

A new always-visible badge:

```tsx
<button
  className={`status-item status-auto-approve ${autoApproveLowRisk ? '' : 'is-disabled'}`}
  title={...}                         // shows recent tool list when > 0
  onClick={() => void setAutoApproveLowRisk(!autoApproveLowRisk)}
>
  {autoApproveLowRisk ? '✓' : '✗'} auto-allow
  {autoApprovedCount > 0 && <span className="status-auto-approve-count"> {autoApprovedCount}</span>}
</button>
```

* ✓ (green tint) when the daemon will auto-allow low-risk.
* ✗ (muted) when the user flipped it off via the badge.
* The count chip is hidden when 0 (R114-C lesson: a "0"
  badge looks like a failure).
* Tooltip carries the recent-5 tool names so a power user
  can hover to see "what was just auto-allowed in the last
  minute" without opening the diagnostic panel.

### `StatusBar.css`

* `.status-auto-approve` base style (green tint, focus ring,
  hover highlight).
* `.status-auto-approve.is-disabled` muted grey variant.
* `.status-auto-approve-count` count chip with rounded
  background.

### `store/index.ts`

* Three new `AppState` fields: `autoApproveLowRisk: boolean`
  (default `true`), `autoApprovedCount: number` (default 0),
  `recentAutoApproved: {tool, atMs}[]` (capped 10).
* `setAutoApproveLowRisk(enabled)` action: calls the RPC,
  mirrors the daemon's returned `enabled` and
  `autoApprovedCount` on success, `console.warn` on failure
  (the next `refreshEngineState` tick will re-sync).
* `permission_auto_approved` notification listener:
  increments the counter (preferring the daemon-supplied
  value when present) and pushes a new entry to
  `recentAutoApproved` (head-insert + `.slice(0, 10)` cap).

### `lib/methods.ts`

```ts
setAutoApproveLowRisk(opts: { enabled: boolean }): Promise<{
  ok: boolean;
  enabled: boolean;
  autoApprovedCount: number;
}> {
  return this.call('setAutoApproveLowRisk', opts);
}
```

The typed return shape matches the Java method 1:1 so the
store's optimistic update is typesafe.

## R120 — Tests

### Java (19 new)

* `JsonRpcPermissionPrompterR120Test` (9): short-circuit on
  low-risk + autoApprove=true; fall-through on
  autoApprove=false; high / critical risk bypass; runtime
  flag flip takes effect on next ask; low-risk classification
  covers read / list / glob / grep / search / stat / get;
  null-methods null-guard; recordAutoApproved called once per
  ask; counter increments monotonically.
* `AetherCodeMethodsR120Test` (10): default flag = true
  (R87 backward compat); missing/non-boolean `enabled` throws
  `JsonRpcProtocolException` with the right `error().message()`;
  boolean and 0/1 number coercion; toggle; counter
  increments; notification payload shape (synthetic
  `auto-<uuid>` requestId, tool, reason, atMs, count);
  notification stream carries post-increment count;
  input map is forwarded in the payload.

The prompter test uses a `RecordingMethods` fake
(`AetherCodeMethods` subclass) instead of Mockito — Java 25
+ Mockito 5.12's inline mockmaker can't attach the bytecode
transformer on this JVM, and the project doesn't use
Mockito anywhere else. The fake overrides the four methods
the prompter touches and counts interactions.

### TS (16 new)

* `StatusBarR120.test.ts` (8): pulls the four R120 fields
  from `useStore()`; renders the focusable `<button>` with
  `status-auto-approve` className; shows ✓/✗ based on flag;
  hides count when 0; click calls `setAutoApproveLowRisk(!current)`;
  tooltip branches on recent list presence; CSS has the
  base / `.is-disabled` / count chip styles; `methods.ts`
  wrapper has the typed return shape.
* `autoApproveR120.test.ts` (8): the three AppState fields
  are declared + seeded with default values; the action
  declares its signature, calls the RPC, mirrors the daemon
  value, wraps in try/catch; the listener registers on
  `permission_auto_approved`, prefers daemon-supplied count,
  drops notifications without a tool, caps at 10, inserts
  at head, records `tool` + `atMs`.

### Pre-existing test touched

`LoopGuardBannerR114.test.ts` — its regex for the
`loopWarn` destructure anchor (`loopWarn }`) was anchored
too tight: R120 added 4 more fields + 8 lines of comment
below `loopWarn`, so `}` was no longer on the next line.
Relaxed to `loopWarn,[\s\S]*?\}` (cross-line, no `/m`
needed). No production-code change.

## Design notes

* **Why daemon-side not renderer-side**: The user picked
  daemon-side because it's strictly faster (no WS round-
  trip per call) and survives a renderer crash (a renderer
  restart sees the counter, but doesn't re-emit 50 phantom
  prompts for already-completed tool calls). The
  notification is still emitted so the UI badge stays in
  sync.
* **Default = true**: matches R87's renderer-side behaviour
  1:1. Users who never opened Settings get the new code
  path with no behaviour change. Users who flipped
  `ACCEPT_TASK` off in R87 get the same effect (the
  renderer-side fallback in `permission_request` listener
  is preserved as defence-in-depth for old daemons).
* **No silent changes**: the badge is always visible, the
  notification is always emitted, the tooltip always
  carries the recent list. A user who wants to know
  "what just happened" can find out in two clicks (StatusBar
  hover or RpcDiagnosticsPanel filter).
* **Synthetic `auto-` requestId**: lets the renderer route
  the notification through the same `permission_auto_approved`
  path that real `permission_request` ids would use, but
  with a clear prefix so logs / debugging can tell them
  apart.
* **Counter is the daemon's, not the renderer's**: multi-
  client scenarios (TUI + desktop) stay in sync because
  the daemon is the source of truth. The notification
  payload carries the post-increment count so the renderer
  doesn't need a follow-up `getState` RPC to keep its
  badge accurate.

## Cumulative state

* Java: **4235** tests (R115 4216 + 19 R120)
* TS: **252** vitest (R115-R119 227 + 25 R120)
* Total wire RPCs: 19 (R115 added 1, R120 added 1)
* Total wire notifications: 13 (R120 added
  `permission_auto_approved`)

## R121-R123 follow-up candidates

* **R121**: command palette raw RPC mode (list 19 RPCs +
  JSON input + execute). Pair with R116's diagnostic panel
  for the equivalent read-side.
* **R122**: persistent engine state across reloads
  (model / permissionMode / loopWindow / loopThreshold /
  autoApproveLowRisk) via localStorage.
* **R123**: RpcDiagnosticsPanel "Export to .jsonl" button
  via Tauri save dialog — for offline analysis of slow /
  failed RPC calls.
