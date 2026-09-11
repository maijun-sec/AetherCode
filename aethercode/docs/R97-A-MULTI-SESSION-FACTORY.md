# R97-A — Multi-Session Factory Plumbing (2026-08-17)

## What ships

R97-A closes the loop that R95-E opened and R96-B
wired. The daemon's `SessionManager` factory now
**actually builds fresh engines** when the user
calls `createEngine` for a non-default session id.
Pre-R97-A, the factory threw
`UnsupportedOperationException` (the R96-B
minimum scope). Post-R97-A, the factory invokes
`Main.buildEngineForSession(sessionId)` and the
new engine is materialised with the full CLI
configuration: same cwd, same provider, same
model, same skills, same agents — but with the
new sessionId stamped on the engine's `AppState`.

Four pieces land in R97-A:

1. **`Main.buildEngineForSession(String sessionId)`**
   — instance method, package-private. Refactored
   from the existing `Main.buildEngine()` (which
   now delegates here with `null` for the default
   path). The sessionId argument is wired through
   `AetherCodeEngine.Builder.sessionId()` so the
   fresh engine carries the new id from the moment
   it's constructed.

2. **`DaemonRunner.run(AetherCodeEngine, Function<String, AetherCodeEngine>)`**
   and **`DaemonRunner.runHttp(AetherCodeEngine, int, Function<String, AetherCodeEngine>)`**
   — new factory-aware entry points. The
   `sessionFactory` closure is the seam the
   `SessionManager` calls when `createEngine` is
   invoked. The 1-arg `run(engine)` and 2-arg
   `runHttp(engine, port)` overloads are preserved
   for backward compat — they default to
   `refuseNonDefaultFactory` (the R96-B minimum
   scope).

3. **`DaemonRunner.refuseNonDefaultFactory(String)`**
   — the default factory for callers that don't
   have a multi-session engine-build closure handy.
   Throws `UnsupportedOperationException` with a
   clear error so a misuse (calling `createEngine`
   through the legacy entry point) is caught
   early. The error message points the user to the
   R97-A path: "use `DaemonRunner.run(engine,
   factory)` / `runHttp(engine, port, factory)`
   with a closure that builds fresh engines (R97-A:
   `Main.buildEngineForSession`)".

4. **`DaemonRunner.buildSessionManager(AetherCodeEngine, Function<String, AetherCodeEngine>)`**
   — the 2-arg `buildSessionManager` overload that
   wires the factory. The pre-existing
   `buildSessionManager(engine)` is now a wrapper
   that delegates with a `null` factory (the
   legacy path). A `null` factory falls back to
   `refuseNonDefaultFactory` so the daemon
   refuses non-default sessions by default
   (matching R96-B's minimum scope).

## Why this completes the picture

R95-E added the `SessionManager` class and the
five `*Engine` RPCs. R96-B wired the manager into
the daemon and the `AetherCodeMethods` 3-arg
constructor — the RPCs started returning
`{ok: true, ...}` for the default session but
`{ok: false, error: "multi-session factory not
yet wired"}` for any `createEngine` call. R97-A
plumbs the CLI's `buildEngineForSession` closure
into the factory so the user can:

- `createEngine({sessionId: "worktree-1"})` →
  the factory builds a fresh engine with
  sessionId=`worktree-1`, mirrors the CLI's
  config (provider, model, cwd, skills, agents),
  and the manager registers it
- `listEngines` → returns both the default
  session and the factory-built sessions
- `setActiveEngine({sessionId: "worktree-1"})`
  → switches the active id; subsequent
  `query` / `cancel` / `listEngines` calls
  route to that engine
- `deleteEngine({sessionId: "worktree-1"})` →
  the factory-built session is removed; the
  default session is protected (matches the
  pre-existing `SessionManager.delete` contract)

## E2E verification

The smoke test in `docs/r97a-smoke.mjs` exercises
the full surface against a real daemon:

```sh
java -jar dist/aethercode-0.2.1.jar --http-port 18445 \
    --cwd /tmp/worktree-1 --no-skills --no-agents \
    --model MiniMax-M3 --provider minmax --no-color &
node docs/r97a-smoke.mjs 18445
# ALL CHECKS PASSED
```

Daemon log confirms the factory was invoked:

```
R96-B: SessionManager wired (default session pre-registered; factory=live (R97-A))
R97-A: built fresh engine for session worktree-1 (model: MiniMax-M3)
R95-E: created session worktree-1 (active engine: MiniMax-M3)
R95-E: deleted session worktree-1
R95-E: refusing to delete the default session
```

The `R97-A: built fresh engine for session
worktree-1` line is the smoking gun — the
factory was called, a fresh engine was built,
and the sessionId is correct.

## What's NOT in R97-A (deferred to R97-B+)

- **Per-RPC sessionId routing.** The 100+ RPC
  method bodies still use `this.engine.xxx`
  directly. The R96-B `currentEngine()` helper is
  in place but not consulted by the other RPCs.
  R97-B will add a `sessionId` param to
  `query` / `cancel` / `setModel` /
  `setPermissionMode` and route through
  `currentEngine()`.

- **AetherCodeEngine.setSessionManager() setter.**
  R97-A does NOT add a setter (the field is
  `final`). The 3-arg `AetherCodeMethods` ctor is
  sufficient for the daemon's needs. R97-B can
  relax the field to `volatile` and add a setter
  in one line if a future caller needs the engine
  accessor to reflect a post-construction
  SessionManager.

- **Per-engine SESSION_IDLE listener wiring.**
  R97-A does NOT wire the boulder-continuation
  hook for factory-built engines. The default
  engine still gets the listener; factory-built
  engines do not. R97-B will add a
  per-engine SESSION_IDLE listener factory
  closure (mirroring the buildEngineForSession
  pattern) so each fresh engine gets its own
  continuation hook.

- **Per-session `cwd` override.** All factory-built
  engines share the CLI's `--cwd`. A future
  R-round can add a `cwd` param to `createEngine`
  to spin up a worktree-specific engine (the
  "multi-worktree" use case).

## Files touched

| File | Change |
|------|--------|
| `aethercode-cli/src/main/java/.../cli/Main.java` | `buildEngine` now delegates to `buildEngineForSession(null)`; new instance method captures the full CLI config + sessionId override; `call()` passes `this::buildEngineForSession` to `DaemonRunner.run/runHttp` |
| `aethercode-cli/src/main/java/.../cli/DaemonRunner.java` | 2-arg `buildSessionManager(engine, factory)` overload; 1-arg delegates with `null` factory; new `refuseNonDefaultFactory` helper; `run/runHttp` accept a `Function<String, AetherCodeEngine>` |
| `aethercode-cli/src/test/java/.../cli/DaemonRunnerTest.java` | +5 R97-A tests (factory builds, factory receives sessionId, factory reused across sessions, default engine immutable, refuseNonDefaultFactory) |
| `aethercode-protocol/src/test/java/.../methods/SessionManagerRpcTest.java` | +5 R97-A end-to-end tests (createEngine invokes factory + stamps sessionId, listEngines shows both, setActive+getActive, deleteEngine, dedup) |
| `aethercode/docs/r97a-smoke.mjs` | NEW, end-to-end WebSocket smoke test |

## Test counts

| Module | Before R97-A | After R97-A | Delta |
|--------|-------------:|------------:|------:|
| aethercode-cli | 7 | 12 | +5 |
| aethercode-protocol | 65 | 70 | +5 |
| **Total Java** | **1866** | **1876** | **+10** |

TUI + Desktop test counts are unchanged. E2E
smoke test (`r97a-smoke.mjs`) covers the full
RPC surface against a live daemon (8 checks).

## Build artifacts

- `dist/aethercode-0.2.1.jar` 39.89 MB (R97-A
  added ~800 bytes for the new factory-aware
  entry points)
- `dist/ac-tui/ac-tui.js` 1.59 MB (unchanged; no
  TUI surface change in R97-A)
- 16 modules `mvn install` successful

## Lessons

- **Backward-compat overloads are cheap when the
  old path is just a "default to refuse"
  wrapper.** The 1-arg `run(engine)` / 2-arg
  `runHttp(engine, port)` overloads survive
  because they delegate to the 2-arg / 3-arg
  forms with a `null` factory, which the manager
  translates to `refuseNonDefaultFactory`. Every
  existing test that called the old entry point
  (R96-B's `DaemonRunnerTest`) still passes
  after the signature change.

- **The factory closure captures the CLI's
  build-process for free.** `Main.buildEngineForSession`
  is an instance method, so `this::buildEngineForSession`
  is a one-liner method reference. The picocli
  fields (`this.cwd`, `this.model`, `this.apiKey`,
  etc.) are captured by the closure — no need
  to extract a `BuildSpec` record or refactor
  the build logic into a static method.

- **Factory invocation is observable via the
  daemon log.** The `R97-A: built fresh engine
  for session X (model: Y)` log line is the
  smoking gun the user can grep for. Without it,
  debugging "why is `createEngine` slow" would
  require attaching a debugger.

- **E2E smoke test is mandatory for RPC surface
  changes.** The unit tests cover the
  `AetherCodeMethods` + `SessionManager` glue,
  but the WebSocket path is a different beast
  (JSON parsing, dispatcher routing, notifier
  fan-out). The `r97a-smoke.mjs` script catches
  issues the unit tests miss — e.g. "the
  WebSocket welcome handshake happened but the
  dispatcher is using a stale methods instance"
  type bugs.

## R97+ candidates (continuing)

- **R97-B**: AetherCodeEngine.setSessionManager()
  setter (relax `final` → `volatile`) +
  per-engine SESSION_IDLE listener wiring +
  per-RPC sessionId routing (`query` / `cancel`
  / `setModel` / `setPermissionMode`)
- **R97-C**: Tauri App 适配 multi-session
  (R95-E / R97-A surface parity in the
  Renderer's session picker)
- **R97-D**: `/prompt` 加 diff (compare 当前 vs
  saved baseline)
- **R97-E**: Session resume (R106 sessionStore +
  R97-A SessionManager 联合)
- **R97-F**: Per-session `cwd` override (the
  "multi-worktree" use case)
