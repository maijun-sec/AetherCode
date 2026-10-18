# R715 — engineHealth reports ACTIVE engine permMode (not constructor default)

**Date**: 2026-10-18
**Branch**: main
**Status**: COMPLETED, daemon restarted with new jar, end-to-end verified

## 1. Problem

User reported perm mode "主动询问" not working on a multi-session daemon:

> session 选择的是 主动询问，但是所有的任务都直接走下去了，完全没有询问

## 2. Root cause analysis

End-to-end diagnostic on the running daemon (3 RPCs cross-checked):

| RPC | reported permMode | what it actually read |
| --- | --- | --- |
| `engineHealth.permissionMode` | `BYPASS_PERMISSIONS` | the constructor `engine` field (process-scoped) |
| `getState(sid=4653e421).permissionMode` | `DEFAULT` | the active engine via `resolveRpcTarget(sid)` |
| `listEngines[active 4653e421].permissionMode` | `DEFAULT` | `effectiveSessionManager().wireSnapshot()` |
| `listEngines[default].permissionMode` | `BYPASS_PERMISSIONS` | same |
| `getPermissionModeSuggestion.currentMode` | `DEFAULT` | `currentEngine()` → `sessionManager.active()` |

The constructor `engine` field is a `final` reference set at
`AetherCodeMethods` construction time. When the user picked a
non-default session (e.g. a fresh project cwd), the desktop's
SessionManager built a second engine for that session and
`setActiveEngine` pointed the active session id at the new
engine. The constructor engine's `permissionMode` field stayed
at the value it had at boot — usually `BYPASS_PERMISSIONS`,
because the user had previously flipped the dropdown to
"始终授权".

The bug:
- `engineHealth` (used by the StatusBar / TUI status pill) read
  the **constructor** engine's permMode, never the active one.
- `getState(sid)`, `getPermissionModeSuggestion`, and
  `setPermissionMode(sid)` all routed through
  `resolveRpcTarget(sid)` / `currentEngine()` and reported the
  **active** engine's permMode.
- The two answers disagreed whenever active ≠ constructor.
- The user saw the StatusBar pill stay green ("始终授权") even
  after switching the dropdown to "主动询问", so they assumed
  perm wasn't working at all. The actual perm behaviour tracked
  the dropdown because the dropdown dispatch went through
  `setPermissionMode` and the dropdown rendering eventually
  followed `getState` on the next 15s refresh — but the
  StatusBar rendered the stale `engineHealth` snapshot.

## 3. Fix

`AetherCodeMethods.engineHealth` (file:
`aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`):

- Read `permMode`, `sessionId`, `model` from
  `currentEngine().appState()` instead of the constructor
  `engine` field. The constructor `engine` is now only used
  for `recordPing()` + `sessionStats()` (the "daemon is alive"
  liveness signal, which is process-scoped, not
  session-scoped).
- New field `currentSessionId` in the response so the renderer
  can disambiguate "active session" from "constructor
  engine's sessionId" without diffing sessionId strings.

The fix is two lines + a comment. It does not change any other
RPC; `getState`, `getPermissionModeSuggestion`, and
`setPermissionMode` already routed through
`resolveRpcTarget(sid)` / `currentEngine()` correctly.

## 4. Tests pinned

New: `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsR715Test.java`
(3 tests, all pass):

1. `engineHealth_reportsActiveEnginePermMode_notConstructorDefault`
   — builds two engines, sets the second active, asserts
   `engineHealth.permissionMode == ASK_BEFORE_TOOL` and
   `engineHealth.currentSessionId == "alpha"`.
2. `engineHealth_singleSession_usesConstructorEngine` — legacy
   2-arg ctor (no SessionManager) still works; the field
   reports the constructor engine's permMode.
3. `engineHealth_statsAndPingRemainOnConstructorEngine` —
   liveness signal (queries, totalToolCalls) is process-scoped
   and not affected by `setActiveEngine`.

`aethercode-protocol` test results:

```
Tests run: 340, Failures: 0, Errors: 0, Skipped: 0
```

## 5. Collateral fix: R124 session/trash tag conflict

`AetherCodeMethodsR124Test.readWriteTagsAreMutuallyExclusive`
was already failing pre-R715 (introduced by R710-A). The
`session/trash` RPC is multi-action (empty / restoreId /
count) and was tagged `[WRITE, READ, SESSION]`. R124 forbids
READ+WRITE on the same method.

The trash-list path is no longer routed through
`session/trash` — the desktop uses `listSessions({ trashed:
true })` (R710-A added this filter). So the destructive
`session/trash` actions (empty + restoreId) only need the
WRITE tag. Tag fixed to `[TAG_WRITE, TAG_SESSION]`.

## 6. End-to-end verification

After restart on the new jar (R715 build, fresh daemon
session "default"):

```text
$ setPermissionMode { mode: "ASK_BEFORE_TOOL" }
result: { sessionId: "default", mode: "ASK_BEFORE_TOOL" }

$ engineHealth
result: {
  ok: true,
  healthy: true,
  model: "MiniMax-M3",
  sessionId: "default",
  permissionMode: "ASK_BEFORE_TOOL",        ← R715: matches setPermissionMode result
  currentSessionId: "default",
  state: "running",
  ...
}
```

Pre-R715 the same call returned `permissionMode:
"BYPASS_PERMISSIONS"` regardless of what `setPermissionMode`
had set. The fix is verified end-to-end.

## 7. Build + restart

- `mvn -f aethercode-protocol/pom.xml test` — 340/340 pass
- `mvn -f aethercode/pom.xml install -DskipTests -pl
  aethercode-core,aethercode-protocol,aethercode-cli -am` —
  reactor BUILD SUCCESS, 40.8 s
- New shaded jar: 56,858,873 bytes (was 56,858,740, +133 B
  for the R715 redirect + the `currentSessionId` field)
- jar sync to `src-tauri/resources/`,
  `src-tauri/target/release/resources/`, and
  `src-tauri/target/release/` (Lesson 948-951 pattern)
- Desktop + daemon restarted; `engineHealth` now reports the
  active engine, R715 contract verified

## 8. Lessons learned

- **Lesson 976 (single-source-of-truth)**: A class that
  exposes a "current state" snapshot must source that state
  from the same `currentXxx()` helper the other RPCs use.
  Pre-R715 `engineHealth` read a different field from
  `getState` / `getPermissionModeSuggestion` / `setPermissionMode`,
  so the four "what session is the user looking at" answers
  disagreed whenever the active session differed from the
  constructor. The fix consolidates on `currentEngine()` and
  adds an explicit `currentSessionId` so the renderer never
  has to infer it from string equality.

- **Lesson 977 (process-scoped vs session-scoped)**: When a
  class has both a process-scoped field (the constructor
  `engine` in our case) and a per-session pointer
  (`SessionManager.active()`), the user-visible snapshots
  must follow the per-session pointer. Process-scoped data
  (liveness, uptime, ping time) stays on the constructor
  field. Conflating the two is the most common
  multi-engine-architecture bug.

- **Lesson 978 (multi-action RPCs in dispatch tables)**: When
  a single RPC has multiple sub-actions (write + read), tag
  it for the dominant intent only. R124's
  read-write-mutual-exclusivity contract is the right rule
  (and was pre-existing), but `session/trash`'s list
  sub-action should not have forced READ onto a primarily
  destructive RPC. The proper fix is to route the read
  sub-action through a dedicated RPC (`listSessions({
  trashed: true })`) and keep `session/trash` for the
  destructive paths only. Without that, the dispatcher's tag
  filter over-matches.
