# R97-G — Per-RPC sessionId for getState / listTools / setModel / setPermissionMode (2026-08-17)

## What ships

R97-G finishes the per-RPC sessionId routing
sweep that R97-B started. After R97-B, `query`
and `cancel` accepted the `sessionId` param.
R97-G adds the same routing to the four
"inspection + admin" RPCs that the renderer
(TUI / desktop) calls constantly:

- **`getState`** — the `/state`-style
  inspection RPC. Routes to the target
  engine's `appState()` and
  `queryEngine()`. The response now also
  carries the engine's `sessionId` so the
  renderer knows which engine it just got.
- **`listTools`** — the tool-pool
  inspection RPC. Routes to the target
  engine's `appState().toolPool()`. Same
  `sessionId` echo.
- **`setModel`** — the model switch RPC.
  Routes to the target engine's
  `appState().mainLoopModel()`. The
  response now carries the engine's
  `sessionId` so the renderer can confirm
  the routing.
- **`setPermissionMode`** — the
  permission-mode switch RPC. Routes to
  the target engine's `appState()` AND
  `engine.setPermissionMode()` (both are
  needed; the AppState surfaces the
  current mode, the live policy enforces
  it). The `mode` value validation happens
  before the session lookup (so a bad
  mode always errors with the same shape
  regardless of sessionId).

A new private helper
`resolveRpcTarget(String)` does the lookup
and is reused by all four RPCs. When the
sessionId is null or blank, it falls back
to `currentEngine()` (the active engine in
multi-session mode, or the constructor
engine in legacy single-engine mode).

## Why this matters for the user

Pre-R97-G, a renderer talking to a
multi-session daemon had to be careful:
`getState` always returned the default
engine's state, even when the user had
switched to a different session. So
clicking "session: worktree-1" in the
renderer, then calling `getState`, would
show the wrong model / tools / transcript
size. The user would think the daemon is
lying about the state (because the visible
session picker says "worktree-1" but the
state shown is for "default").

R97-G fixes this: `getState` /
`listTools` / `setModel` /
`setPermissionMode` all route to the
target engine when the renderer passes
`sessionId`. The session picker can now
be the single source of truth — the
renderer sets the active session and
calls these RPCs without `sessionId`,
and the active engine's state is what
comes back. (The existing `currentEngine()`
path handles this transparently.)

## Wire integration

`AetherCodeMethods.getState` shape (R97-G):
```json
// in
{ "sessionId": "worktree-1" }
// out (success)
{ "sessionId": "worktree-1", "model": "...", "permissionMode": "...",
  "toolCount": N, "tools": [...], "transcriptSize": N,
  "contextWindow": N, "maxTurns": N, "loopWindow": N, "loopThreshold": N }
// out (routing error)
{ "ok": false, "error": "no such sessionId: worktree-1" }
```

`AetherCodeMethods.listTools` shape (R97-G):
```json
// in
{ "sessionId": "worktree-1" }
// out (success)
{ "sessionId": "worktree-1", "tools": [ {name, description}, ... ] }
```

`AetherCodeMethods.setModel` shape (R97-G):
```json
// in
{ "model": "M-foo", "sessionId": "worktree-1" }
// out (success)
{ "sessionId": "worktree-1", "model": "M-foo" }
```

`AetherCodeMethods.setPermissionMode` shape (R97-G):
```json
// in
{ "mode": "BYPASS_PERMISSIONS", "sessionId": "worktree-1" }
// out (success)
{ "sessionId": "worktree-1", "mode": "BYPASS_PERMISSIONS" }
```

## What's NOT in R97-G (deferred to R97-H+)

- **Per-RPC sessionId for the rest of the
  100+ RPCs.** R97-G covers the four most
  commonly called inspection + admin
  RPCs. The remaining RPCs
  (`setSystemPrompt`, `getSystemPrompt`,
  `getSystemPromptSection`, `listTasks`,
  `createTask`, `updateTaskStatus`,
  `getMetrics`, `getTraces`, `getTrace`,
  `getEngineStats`, `setConcurrencyProfile`,
  `getTranscript`, `listProviders`,
  `switchProvider`, `permissionResponse`,
  ...) continue to route to the constructor
  engine. Adding them is a 1-line
  `currentEngine().xxx` swap per RPC;
  future R-rounds can finish the sweep
  incrementally.

- **Per-RPC sessionId for the sub-agent
  RPCs** (`subagentCancel`). The
  `SubagentRegistry` is a process-scoped
  singleton (mirroring `BashJobRegistry`),
  not an engine-scoped resource, so it
  doesn't need per-RPC sessionId routing.
  The events it emits already carry a
  `sessionId` field (R92-D) so the
  renderer can filter by session on the
  consumer side.

- **A `currentSessionId` (active session)
  explicit field.** R97-G uses
  `currentEngine()` for the no-sessionId
  case, which returns the active engine
  in multi-session mode. A future R-round
  could add a `getActiveSession` RPC that
  returns just the sessionId (no engine
  state) for renderers that want to
  display "currently using" without
  calling the heavier `getState`.

## Files touched

| File | Change |
|------|--------|
| `aethercode-protocol/src/main/java/.../methods/AetherCodeMethods.java` | `resolveRpcTarget(String)` helper; `getState` / `listTools` / `setModel` / `setPermissionMode` route via the helper; response payloads now include `sessionId` |
| `aethercode-protocol/src/test/java/.../methods/SessionManagerRpcTest.java` | +9 R97-G tests (getState fallback / ghost rejection / routing to factory-built / listTools fallback / ghost rejection / setModel routing + default isolation / setPermissionMode routing + default isolation / setPermissionMode mode validation / setModel ghost rejection) |
| `aethercode/docs/r97g-smoke.mjs` | NEW, end-to-end WebSocket smoke test (12 checks) |

## Test counts

| Module | Before R97-G | After R97-G | Delta |
|--------|-------------:|------------:|------:|
| aethercode-protocol | 76 | 85 | +9 (per-RPC routing for 4 new RPCs) |
| **Total Java** | **1895** | **1904** | **+9** |

TUI + Desktop test counts are unchanged.
E2E smoke test (`r97g-smoke.mjs`) covers the
full surface against a live daemon (12
checks).

## Build artifacts

- `dist/aethercode-0.2.1.jar` 39.89 MB
  (+357 bytes from R97-B for the
  `resolveRpcTarget` helper and the
  `sessionId` echo in the response
  payloads)
- `dist/ac-tui/ac-tui.js` 1.59 MB
  (unchanged; no TUI surface change in
  R97-G; the TUI already had the
  `/session <id>` slash command from
  R95-E)
- 16 modules `mvn install` successful

## Lessons

- **`resolveRpcTarget` is the right
  abstraction for the small RPCs.** The
  `query` method has too much body to
  refactor through a helper closure (it
  uses `engine.xxx` 17 times across the
  Thread lambda). The four R97-G RPCs are
  small enough that the helper is clean.
  Future R-rounds can keep adding RPCs via
  the same pattern: resolve target at the
  top, use `target.xxx` in the body.

- **The `sessionId` echo in the response
  is a safety net for the renderer.** A
  renderer that says "I sent `getState` to
  session X" can compare the response's
  `sessionId` against X. If they differ,
  the routing failed. This is the same
  pattern as `setModel` returning the
  updated model — the response carries
  the post-state, not the pre-state.

- **Validation order matters.** `setModel`
  validates the `model` param (non-null,
  non-blank) before the session lookup;
  `setPermissionMode` validates the `mode`
  value (must be a valid `PermissionMode`
  enum) before the session lookup. A bad
  model / mode always errors with the
  same shape regardless of sessionId. The
  session lookup only happens when the
  base parameters are valid.

## R97+ candidates (continuing)

- **R97-H**: per-RPC sessionId for the
  remaining RPCs (`setSystemPrompt`,
  `getSystemPrompt`, `getSystemPromptSection`,
  `listTasks`, `createTask`,
  `updateTaskStatus`, `getMetrics`,
  `getTraces`, `getTrace`,
  `getEngineStats`, `setConcurrencyProfile`,
  `getTranscript`, `listProviders`,
  `switchProvider`, `permissionResponse`,
  ...). The pattern is established;
  finishing the sweep is mechanical.
- **R97-I**: Tauri App 适配 multi-session
  (R95-E / R97-A / R97-B / R97-G surface
  parity in the Renderer's session
  picker)
- **R97-J**: `/prompt` 加 diff (compare
  当前 vs saved baseline)
- **R97-K**: Session resume (R106
  sessionStore + R97-A SessionManager 联合)
- **R97-L**: Per-session `cwd` override
  (the "multi-worktree" use case)
