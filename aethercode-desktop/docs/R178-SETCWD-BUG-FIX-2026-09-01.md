# R178: Fix [setCwd failed] no engine for sessionId

**Date**: 2026-09-01
**Version**: v0.2.26
**Files**:
- `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` — added `syncSessionManagerRegistration()` + calls in `createSession` / `loadSession` / `setSessionManager`
- `aethercode/aethercode-sdk/src/test/java/org/aethercode/sdk/AetherCodeEngineR178Test.java` — 5 new tests
- `aethercode/aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` — call `engine.setSessionManager(sessionManager)` in `run()` and `runHttp()` (the **real** wiring gap)
- `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` — default `cwd` now reads `appState.cwd()` instead of `new File("").getAbsoluteFile()` (JVM user.dir)

## Symptom

User opened v0.2.25 desktop, started a new session, then tried to set the cwd.
The daemon returned:

```
[setCwd failed] no engine for sessionId: 2026-09-01T04-49-44.811597300Z_16c023b9
```

The session id format `2026-09-01T..._<short-uuid>` is from
`SessionStore.newSessionId()` — a timestamp + 8-char UUID.

## Three-layered root cause

The bug actually had **three** independent root causes; only fixing
one wasn't enough. Discovered in this order while testing.

### Root cause 1: `appState.sessionId` is re-assigned but the SessionManager doesn't know

`AetherCodeEngine.createSession()` calls
`SessionStore.newSessionId()` and then `appState.sessionId(newId)`.
The engine's internal `appState` now reports the new id, but the
`SessionManager` was set up at daemon startup with two
`registerExisting` calls (under "default" + the engine's startup
UUID). The new id never lands in the manager, so
`sm.get(newId)` returns null. Same gap in `loadSession`.

**Fix**: `AetherCodeEngine.syncSessionManagerRegistration()` — a
private helper that calls `m.registerExisting(appState.sessionId(),
this)` after every `appState.sessionId(newId)`. The manager's
`registerExisting` is idempotent, so calling it on every session
swap is a no-op for the no-change case and a fix for the real one.
Also called from `setSessionManager` to cover the daemon-restart
case where the constructor already adopted a non-default id
(via the `.cwd` sidecar).

### Root cause 2: the daemon **never wired the manager onto the engine**

`DaemonRunner.buildSessionManager` returns a `SessionManager`
that's passed to `AetherCodeMethods` (3-arg constructor) and to
`HttpJsonRpcServer`. But nowhere does the daemon call
`engine.setSessionManager(sessionManager)`. So
`AetherCodeEngine.sessionManager` stays null, and the R178 helper
in root cause 1 is a no-op.

The pre-R178 `AetherCodeMethods.sessionManager` field was set
by the constructor, which masked the issue for routing
(`resolveRpcTarget` falls back to that field), but the engine-side
helper still needs the binding to re-register on session swaps.

**Fix**: `engine.setSessionManager(sessionManager)` after
`buildSessionManager(...)` in both `DaemonRunner.run()` and
`DaemonRunner.runHttp()`. The code comment "install the
SessionManager on the engine" was already there, the actual
call was missing.

### Root cause 3: BashTool's default cwd is the JVM's `user.dir`, not the engine's `appState.cwd()`

After fixing root causes 1+2, `setCwd` succeeds (the engine
correctly reports the new cwd), but `bash` tool calls still run
in the JVM's startup directory because
`BashTool.call()` defaulted to
`new File("").getAbsoluteFile()` (which is `System.getProperty("user.dir")`).
The model sees `dir` return the old directory even after a
successful `bindSessionCwd`, and gets confused.

**Fix**: `BashTool.defaultCwd(ctx)` — pulls
`ctx.extra("app_state")`, casts to `AppState`, reads
`appState.cwd()`. Falls back to `user.dir` for callers that pass
a context without an `AppState` (older tests, standalone bash usage).

## Tests

### `AetherCodeEngineR178Test` (5 tests, all green)
| # | Test | Pins |
|---|------|------|
| 1 | `createSessionRegistersNewIdInManager` | root cause 1 path |
| 2 | `loadSessionRegistersNewIdInManager` | root cause 1, other branch |
| 3 | `setSessionManagerReRegistersUnderCurrentId` | daemon-restart case |
| 4 | `noSessionManagerLeavesCreateSessionIdUntracked` | legacy single-engine path |
| 5 | `multipleCreateSessionCallsEachRegister` | sequential createSession |

### Existing tests (no regression)
- `AetherCodeEngineSessionManagerTest` — 5/5
- `SessionManagerTest` — 35/35
- `aethercode-core` — 1141/1141

Root cause 2 fix has no dedicated test (the `AetherCodeEngineR178Test`
test 1 already covers the case where the manager is wired, and the
daemon's only contract violation is "didn't call setSessionManager" —
a code-inspection test would be more invasive than the fix is).

## Verification (end-to-end)

Fired the v0.2.26 daemon (port 17888, sessions-dir D:\tmp\abc_1):

1. `getState` → `sessionId=098580b1-...` (engine startup UUID)
2. `createSession` → new `sessionId=2026-09-01T07-35-21.291203100Z_9d6e39b8`
3. `switchProject({cwd: "D:\\tmp\\abc_1", sessionId: <newId>})` →
   **`{ok: true, newCwd: "D:\\tmp\\abc_1"}`** — the user-reported
   error is gone.
4. `bash "dir"` (no `cwd` field) — tool result is
   `D:\tmp\abc_1 ��Ŀ¼` (Chinese display garbled, but the path
   is correct) — root cause 3 fixed.

## Build artefacts (2026-09-01)

| Artifact | SHA256 | Size |
|----------|--------|------|
| `release/aethercode-0.2.26/AetherCode.exe` | `6a656acb...` | 3.94 MB |
| `release/aethercode-0.2.26/aethercode-0.2.26.jar` | `56CF4BEF00FD789E915A4B1136940D735B2B89D2776ED30F7035F56F7B1D817B` | 55.3 MB |
| `aethercode/dist/aethercode-0.2.26.jar` | `56CF4BEF...` (same) | 55.3 MB |

The Tauri exe is byte-identical to v0.2.25 (3.94 MB) because R178
is a backend-only change. The daemon jar is the new one with the
fix. The desktop auto-spawns the highest-versioned jar from
`aethercode/dist/`, so launching v0.2.26 exe picks up the new jar.

## Test status

- ✅ `[setCwd failed]` error gone (root cause 1+2)
- ✅ Bash tool defaults to engine cwd (root cause 3)
- ⚠️ Full prompt run was not completed in the headless test —
  the model hit a pre-existing R98 deny matrix issue on
  diagnostic commands (`where mvn`, `java -version`,
  `mvn --version`, `cmd /c "where mvn"` all denied), got stuck
  retrying, and the loop detector (default window 8) kept it
  from completing in 15 minutes. This is **not** an R178
  regression — same deny matrix is in v0.2.25 too. R177's
  successful run used the desktop UI which has the loop detector
  in a more permissive mode; the headless Python test path
  doesn't.
- Recommended next step: set `AETHERCODE_LOOP_DETECTOR=max`
  (or run via the desktop UI) and re-test — the model has all
  the inputs it needs (correct cwd, working BashTool) and the
  R98 denials are recoverable, just slow.

## Lessons

1. **"Active" identity must have a single source of truth that's
   re-registered on every change.** R106 added `appState.sessionId(newId)`
   but didn't tell the manager. The unit test that would have
   caught it: `assertThat(sm.get(newId)).isNotNull()` after
   `engine.createSession()`. Add it once, get it forever.

2. **Idempotent registerExisting is the right API for this kind
   of fix.** No-op when the id is already there, adds the
   mapping when it's new. Calling on every session swap is
   cheap and safe.

3. **Constructor order matters for re-registration.** The
   `setSessionManager` setter now also re-registers, so a daemon
   that boots with `setSessionManager` AFTER the constructor
   has loaded a non-default session (e.g. via the `.cwd` sidecar)
   doesn't have a broken first RPC.

4. **"Install the manager on the engine" — comments lie.** The
   pre-R178 `DaemonRunner.runHttp` had a comment that said
   "install the SessionManager on the engine" but the actual
   call was missing. The `AetherCodeMethods` 3-arg constructor
   set its own `sessionManager` field, which masked the issue
   for routing but not for the engine-side helper. When the
   comment doesn't match the code, **trust the code + tests**,
   not the comment.

5. **`new File("").getAbsoluteFile()` is a trap.** It's
   `user.dir` (the JVM startup directory), not the "current
   directory" in any user-facing sense. The BashTool should
   have always pulled from `appState.cwd()` once that field
   existed. Fixing it required plumbing the `AppState` through
   `CallContext.extras` (it was already there as `"app_state"`).

6. **BashTool 5-min timeout + R98 deny matrix = bad UX.** When
   the model wants to run a diagnostic command, it shouldn't
   take 5 minutes of timeout + retry. Consider lowering the
   default BashTool timeout to 30s for diagnostic patterns or
   adding a "denied" pre-flight response with a clear "use a
   different tool" hint. Pre-R178 issue, not R178.
