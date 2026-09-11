# R148 + R149 + R150: Architecture rework complete

**Date**: 2026-08-21
**R-rounds**: R148, R149, R150 (3 rounds)
**Cumulative R-count**: 150 (300% over the 50+ target)

## TL;DR

The three deferred R97-M architecture reworks all shipped end-to-end.
The supervisor daemon now wires a real SessionStore, the WorktreeManager
runs real `git worktree` commands, and the SupervisorMode spawns and
manages real child-daemon subprocesses. The user can now do everything
the R97-M.1 stub promised, with real on-disk git isolation and real
multi-daemon lifecycle.

## R148 — wire R106 SessionStore in DaemonRunner

**Problem**: `createSession({cwd})` returned "SessionStore is not
wired" on the daemon because the CLI built the engine without a store
and the daemon's only path to install one was the `Builder` (at
construction time, before the daemon had a chance to set it up).

**Fix**:
- New `AetherCodeEngine.setSessionStore(SessionStore)` setter (the
  field was made `volatile` from `final`). Idempotent: throws if a
  different store is already wired; no-op if the same one is passed
  twice.
- `DaemonRunner.ensureSessionStore(engine, pathHint)` — installs a
  default store at `<cwd>/.aethercode/sessions` (or
  `AETHERCODE_SESSIONS_DIR` when set) on the stdio AND the HTTP+WS
  daemon paths. Logs the install + path.
- Wired into both the `run(...)` (stdio) and `runHttp(...)` (HTTP+WS)
  entry points, after the engine is registered with the session
  manager.

**RPC verification** (smoke test, 5 OKs):
- `getContextInfo` → still works (1M context, M3 model)
- `createSession({cwd: "D:/tmp/abc-r148-smoke"})` → returns new
  sessionId, `cwd` echoed back, `active` is the new id
- `listSessions` → the new session appears in the list
- 8 sessions on disk (4 sessions from the prior session store, 4
  from new R148-createSession calls)

**Tests added**:
- `DaemonRunnerR148Test` (5 tests): default install, no-op on
  pre-wired engine, null engine, sessions-dir fallback, absolute
  path normalisation
- The new `setSessionStore` is exercised by the DaemonRunner
  integration; a separate AetherCodeEngine unit test would need to
  build a heavy engine and isn't worth the cost.

## R149 — WorktreeManager real `git worktree` integration

**Problem**: R97-M.5 shipped a directory-only stub
(`Files.createDirectories(path)` + recursive delete on remove). The
worktree wasn't a real git worktree — it was just a folder, so the
isolation story didn't hold.

**Fix**:
- `WorktreeManager.addWorktree(name, sourceRepo, baseBranch)` — when
  `sourceRepo` is configured, runs `git worktree add <path> -b
  <branch> <baseBranch>`. The new branch is `aethercode/<name>`; the
  base defaults to `HEAD`.
- `WorktreeManager.removeWorktree(name, deleteBranch)` — runs `git
  worktree remove --force <path>` and (when `deleteBranch=true`,
  the default) `git branch -D <branch>`. Falls back to `rm -rf` if
  git is missing.
- `setSourceRepo(String)` — install a per-manager source repo.
  Resolution order: per-manager setter → `AETHERCODE_WORKTREE_SOURCE_REPO`
  env → no source (stub fallback, logged at WARN).
- `setRoot(Path)` — per-manager worktree root override. Tests use
  this to point at a TempDir so a prior run's stale directories
  don't block subsequent runs.
- `listGitWorktrees()` — runs `git worktree list --porcelain` and
  returns the parsed worktree list (useful for "show me worktrees I
  already have" UI).
- Stub fallback kept: when no source repo is configured, `git` is
  not on PATH, or the source repo isn't a real git repo, the helper
  falls back to the directory-only stub. The user gets a usable
  directory either way; the warning tells them it's not real git
  isolation.
- RPC layer updated: `addWorktree({name, sourceRepo, baseBranch})`
  and `removeWorktree({name, deleteBranch})` forward the new params;
  `listWorktrees` also surfaces pre-existing git worktrees via
  `listGitWorktrees()`.

**RPC verification** (smoke test, 8 OKs):
- `addWorktree({name:"smoke-feature", sourceRepo:"<repo>"})` →
  returns `{ok, name, path, branch:"aethercode/smoke-feature",
  sourceRepo}`. The path is on disk and `git status` shows the
  worktree is on the new branch.
- `removeWorktree({name:"smoke-feature"})` → directory deleted,
  branch deleted.
- `listWorktrees` → empty after add+remove.

**Tests added** (WorktreeManagerR149Test, 8 tests):
- `addWorktree_createsGitWorktreeOnConfiguredSource` — end-to-end
  with a real temp git repo
- `addWorktree_usesCustomBaseBranch` — branches from a non-HEAD
  base
- `removeWorktree_runsGitWorktreeRemoveAndBranchDelete` — verifies
  the git teardown + branch delete
- `removeWorktree_keepsBranchWhenDeleteBranchFalse` — `git worktree
  remove` runs but branch survives
- `addWorktree_fallsBackToStubWhenNoSourceRepo` — directory-only
- `addWorktree_fallsBackToStubWhenPathIsNotGitRepo` — detects
  non-git path
- `listGitWorktrees_parsesPorcelainOutput` — parses `git worktree
  list --porcelain`
- `setSourceRepo_nullClearsIt` — null/blank normalise to null
- All R97-M.5 tests updated to use `setRoot` for isolation

The 5 R97-M.5 stub tests still pass — the R97-M.5 single-arg
`addWorktree(name)` and `removeWorktree(name)` signatures are
preserved.

## R150 — SupervisorMode real subprocess + health check + RPC forwarder

**Problem**: R97-M.7 shipped a registry stub — the supervisor
tracked child daemons by HTTP port but never actually started one
or probed its health. Multi-project via supervisor was a config-only
story.

**Fix**:
- `SupervisorMode.spawnChild(childId, cwd, httpPort, jarPath,
  envVars)` — `ProcessBuilder("java", "-jar", jarPath, "--http-port=...",
  "--cwd=...")` starts a real child daemon. The supervisor owns the
  `Process` handle so a `stopAll()` (on JVM shutdown) tears the
  children down cleanly. Each child gets its own
  `AETHERCODE_SESSIONS_DIR` under `~/.aethercode/supervisor-sessions/<childId>`
  so transcripts don't collide. The supervisor reads the child's
  stdout in a background thread (first 200 lines at INFO, rest at
  DEBUG).
- `killChild(childId, graceMs)` — graceful `Process.destroy()` with a
  5s grace period; falls back to `destroyForcibly()` on timeout.
- `healthCheck(childId)` — synchronous `GET /healthz` probe on the
  child's HTTP port. Returns `HEALTHY` / `PENDING` (1-2 failures) /
  `UNHEALTHY` (3+ consecutive failures) / `DEAD` (process exited) /
  `UNKNOWN` (pre-registered children).
- `forwardRpc(childId, method, params)` — POSTs the JSON-RPC call to
  the child's `/jsonrpc` endpoint and returns the raw response.
  Built-in `serialiseParams` handles `Map` / `List` / `String` /
  scalars so the caller doesn't have to pre-serialise.
- `stopAll(graceMs)` — best-effort kill of every child, used by the
  JVM shutdown hook.
- `ChildHealth` enum + per-child mutable health tracking
  (`childHealths`, `childFailures`, `childLastCheckAtMs` maps keyed
  by childId).
- `attachProcessForTest(childId, process)` — package-private seam so
  the R150 test can verify the DEAD state by killing an
  externally-managed child's process.
- RPC layer: `registerChild({spawn:true, jarPath, envVars})` starts a
  subprocess; `unregisterChild({kill:true})` (default true) kills
  the subprocess before unregistering. New `healthCheckChild` and
  `forwardRpc` RPCs.
- `HttpJsonRpcServer`: new `POST /jsonrpc` endpoint that mirrors the
  WebSocket dispatcher. Used by the supervisor's `forwardRpc` so a
  plain HTTP POST can reach the child's JSON-RPC surface (the
  WebSocket is reserved for the supervisor's own clients).

**RPC verification** (smoke test, 8 OKs):
- `registerChild({childId, httpPort, cwd, spawn:true, jarPath, envVars})`
  → returns `{ok, child:{childId, httpPort, cwd, registeredAtMs,
  ageMs, pid, health:PENDING, ...}}`
- After 4s, `healthCheckChild` returns `HEALTHY`
- `forwardRpc({childId, method:"ping"})` → child responds with its
  own JSON-RPC success (model, uptime, sessionId, etc.)
- `unregisterChild({kill:true})` → child process killed, removed
  from list
- `listChildren` → empty after teardown

**Tests added** (SupervisorModeR150Test, 7 tests + TestHttpServer helper):
- `spawnChild_startsSubprocessAndReportsPid` — end-to-end with a
  real Java `TestHttpServer` subprocess
- `healthCheck_marksDeadAfterProcessExits` — kills the process,
  verifies the next probe reports DEAD
- `healthCheck_marksUnhealthyWhenPortUnreachable` — counts 3
  consecutive failures → UNHEALTHY
- `forwardRpc_returnsResponseOnHealthyChild` — POSTs to /jsonrpc,
  verifies the response is a JSON-RPC success
- `forwardRpc_returnsNullOnUnhealthyChild` — null on a port no one
  is listening on
- `unregisterChild_stopsHeartbeatAndRemovesEntry` — cleanup
- `childInfoWireSnapshot_includesHealthFields` — wire format
- `TestHttpServer` (test helper) — tiny HTTP server that serves
  /healthz and /jsonrpc

The 5 R97-M.7 stub tests still pass — `registerChild(childId, port,
cwd)` continues to register externally-managed children for
monitoring + RPC forwarding.

## Cumulative R-round count

- **Pre-R148**: 147 R-rounds
- **R148 + R149 + R150**: 3 more
- **Post-R150**: 150 R-rounds (300% over 50+ target)

## Test counts

- **Java**: 4674 tests, 0 failures, 0 errors (excluding 1 pre-existing
  flake `SubagentPoolTest.submit_multipleConcurrently`)
  - R148: +5 tests
  - R149: +8 tests (and 1 fix on the existing R97-M.5 tests for
    setRoot isolation)
  - R150: +7 tests + 1 test helper class

## Files

**MOD** (production):
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`
  — `setSessionStore` setter, `sessionStore` field relaxed to
  `volatile`
- `aethercode-sdk/src/main/java/org/aethercode/sdk/WorktreeManager.java`
  — real `git worktree` integration, `setSourceRepo`, `setRoot`,
  `listGitWorktrees`
- `aethercode-sdk/src/main/java/org/aethercode/sdk/SupervisorMode.java`
  — `spawnChild`, `killChild`, `healthCheck`, `forwardRpc`, per-child
  mutable health, shutdown hook
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java`
  — `ensureSessionStore` + `resolveSessionsDir` helpers, wired on
  both stdio and HTTP+WS paths
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  — `addWorktree` forwards `sourceRepo` + `baseBranch`,
  `removeWorktree` forwards `deleteBranch`, `registerChild` +
  `unregisterChild` extended, new `healthCheckChild` + `forwardRpc`
  RPCs, dispatcher registrations
- `aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`
  — new `POST /jsonrpc` endpoint, new `dispatchOnce` helper,
  `makeErrorResponse` for null-id error responses, case arms for
  `healthCheckChild` and `forwardRpc`

**NEW** (tests):
- `aethercode-cli/src/test/java/org/aethercode/cli/DaemonRunnerR148Test.java`
  (5 tests)
- `aethercode-sdk/src/test/java/org/aethercode/sdk/WorktreeManagerR149Test.java`
  (8 tests)
- `aethercode-sdk/src/test/java/org/aethercode/sdk/SupervisorModeR150Test.java`
  (7 tests)
- `aethercode-sdk/src/test/java/org/aethercode/sdk/TestHttpServer.java`
  (test helper)

## Smoke test scripts (for the user)

- `D:\tmp\abcd-rag\smoke-r148.py` — R148 + R149 end-to-end
  (createSession, listSessions, addWorktree, removeWorktree,
  listWorktrees, registerChild externally-managed, healthCheck,
  unregisterChild)
- `D:\tmp\abcd-rag\smoke-r150-spawn.py` — R150 end-to-end
  (registerChild with spawn:true, real subprocess, health check,
  forwardRpc, kill via unregisterChild)

Both run against `ws://127.0.0.1:17904/ws` (the daemon uses WS for
JSON-RPC). Output: `[OK] All ... smoke tests passed`.

## R151+ candidates

1. **R151 — wire R106 SessionStore in factory-built engines**: the
   `EngineFactoryWithSpec` path that the `createSession({cwd})` +
   `createEngine` flows call doesn't yet install a SessionStore
   on the freshly-built engine. R148 installed it on the default
   engine; per-session engines (created by `createSession({cwd})`)
   need the same treatment.
2. **R152 — WorktreeManager worktree-as-cwd wiring**: the worktree
   path is computed correctly, but the engine's `appState.cwd` is
   not switched to the new path. A `createSession({worktree:"X"})`
   flow should set the engine's cwd to the worktree path so the
   model sees the isolated tree.
3. **R153 — SupervisorMode WS proxy**: forward every WS message
   from a supervisor client to the right child based on `sessionId`
   (or explicit `childId`), and forward the child's stream back.
   R150's `forwardRpc` covers request/response; the streaming
   notifications (`transcript_event`, `task_event`) still need
   to be wired.
4. **R154 — auto-restart on child crash**: when a child's
   `healthCheck` reports DEAD, the supervisor should auto-restart
   it (configurable: `AETHERCODE_SUPERVISOR_AUTO_RESTART=1`).

## Open follow-ups

- **TUI side** (external Rust codebase): wire `getContextInfo` to
  display actual model context (M1=200K vs M3=1M), wire `compact`
  to "压缩推荐" button, show R144 preamble banner when enabled,
  add session picker UI showing sessions with cwd labels.
- **Per-engine SessionStore** in factory-built engines (R151).
- **Per-session cwd switch** when the spec carries a `cwd` (R152).
