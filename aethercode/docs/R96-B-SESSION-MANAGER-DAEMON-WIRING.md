# R96-B — SessionManager Daemon Wiring (2026-08-17)

## What ships

R96-B wires the multi-session surface (R95-E)
into the daemon process so the JSON-RPC
multi-session RPCs (`listEngines` /
`createEngine` / `deleteEngine` /
`setActiveEngine` / `getActiveEngine`) actually
work end-to-end. Pre-R96-B, the RPCs returned
`{ok: false, error: "session manager not
configured"}` because the engine was built without
a SessionManager and the daemon never installed one.

Three pieces land in R96-B:

1. **`SessionManager.registerExisting(String, AetherCodeEngine)`**
   — pre-register an already-built engine for a
   given session id, bypassing the factory. The
   factory stays in place for non-default
   sessions; this is the "I have an engine, please
   use it for X" seam that the daemon needs to
   install the CLI-built engine as the "default"
   session.

2. **`AetherCodeMethods.effectiveSessionManager()`**
   — a private helper that prefers the
   SessionManager passed to the 3-arg constructor
   (the new wiring path) over the engine's own
   accessor (the legacy path). The five R95-E RPCs
   now consult this helper instead of
   `engine.sessionManager()` directly, so the
   3-arg constructor is the single source of truth
   when wired.

3. **`DaemonRunner.buildSessionManager(AetherCodeEngine)`**
   — package-private helper that builds a
   SessionManager with a "default" pre-registration
   and a factory that throws for non-default ids
   (R97+ will plumb the full engine-build path
   through). Both `run()` (stdio) and `runHttp()`
   (HTTP+WebSocket) now call this helper and pass
   the result to the 3-arg `AetherCodeMethods` /
   `HttpJsonRpcServer` constructors.

## Why this is the minimum

The R95-E design assumed the engine was built
with `Builder.sessionManager(...)`. That requires
the SessionManager to exist at engine-build time,
which is awkward in the CLI flow — the
`Main.buildEngine()` chain doesn't know whether
the user wants multi-session mode. R96-B adds a
second wiring path: the daemon constructs the
SessionManager AFTER the engine is built and
threads it through the RPC layer.

The two paths co-exist:
- Legacy path: `Builder.sessionManager(...)` →
  `engine.sessionManager()` returns the manager
- New path: 3-arg `AetherCodeMethods` ctor →
  `effectiveSessionManager()` returns the manager
  (methods-level field)

The methods-level field wins when both are
present, but the legacy path is preserved for
backward compat with existing tests and any
caller that wires the SessionManager at the
Builder level.

## What's NOT in R96-B (deferred to R97+)

- **Multi-engine factory plumbing through
  `Main.buildEngine()`.** R96-B's factory throws
  for non-default ids. R97+ will mirror the
  build-process closure (provider / model / cwd
  / skills / etc.) so `createEngine` for
  non-default ids actually materialises a fresh
  engine. Without this, a fresh engine would be
  a half-baked stub and the daemon would crash
  on the first query through it.

- **Per-RPC sessionId routing.** R96-B does NOT
  change the 100+ method bodies that read
  `this.engine.xxx` directly. Only the five
  R95-E RPCs (and the constructor path) consult
  the new helper. The other RPCs (query, cancel,
  listTools, setModel, etc.) still route to the
  default engine. R97+ will add a `sessionId`
  param to the RPCs that need per-session
  routing.

- **`AetherCodeEngine.setSessionManager()` setter.**
  R96-B does NOT add a setter on the engine
  (the field is `final`). The 3-arg ctor on
  `AetherCodeMethods` is sufficient for the
  daemon's needs. If a future caller needs the
  engine accessor to reflect a post-construction
  SessionManager, the field can be relaxed to
  `volatile` and a setter added.

- **SESSION_IDLE listener wiring for non-default
  engines.** The boulder-continuation hook is
  bound to the constructor's engine, so a
  non-default engine (created lazily by the
  factory) does not get its own continuation
  hook. Users can still transition to the
  default session to keep the auto-continue
  working; the non-default path is a known
  caveat in the code comment.

## Files touched

| File | Change |
|------|--------|
| `aethercode-sdk/src/main/java/.../sdk/SessionManager.java` | `registerExisting` helper, `safeModel` log helper |
| `aethercode-sdk/src/test/java/.../sdk/SessionManagerTest.java` | +7 R96-B tests |
| `aethercode-protocol/src/main/java/.../methods/AetherCodeMethods.java` | `effectiveSessionManager` helper, 5 R95-E RPCs consult it |
| `aethercode-protocol/src/test/java/.../methods/SessionManagerRpcTest.java` | NEW, 9 tests for 2-arg vs 3-arg ctor routing |
| `aethercode-protocol/src/main/java/.../http/HttpJsonRpcServer.java` | 3-arg ctor that accepts a SessionManager; 2-arg delegates to 3-arg with null |
| `aethercode-cli/src/main/java/.../cli/DaemonRunner.java` | `buildSessionManager` helper, both `run` and `runHttp` wire it |
| `aethercode-cli/src/test/java/.../cli/DaemonRunnerTest.java` | NEW, 3 tests for the helper |

## Test counts

| Module | Before R96-B | After R96-B | Delta |
|--------|-------------:|------------:|------:|
| aethercode-sdk | 132 | 139 | +7 |
| aethercode-protocol | 56 | 65 | +9 |
| aethercode-cli | 4 | 7 | +3 |
| **Total Java** | **1847** | **1866** | **+19** |

TUI + Desktop test counts are unchanged.

## E2E verification

Once a fresh `aethercode-0.2.1.jar` is in
`dist/`, the user can verify the wiring by
spawning the daemon and running `listEngines`:

```sh
java -jar aethercode-0.2.1.jar --daemon &
echo '{"jsonrpc":"2.0","id":1,"method":"listEngines","params":{}}' | nc -U /tmp/sock
# → {"jsonrpc":"2.0","id":1,"result":{"ok":true,"activeSessionId":"default","count":1,"sessions":[{"sessionId":"default",...}]}}
```

Pre-R96-B the same call returned
`{"ok":false,"error":"session manager not
configured"}`. Post-R96-B it returns the default
session in the snapshot.

`createEngine` for a non-default id returns
`{ok: false, error: "engine factory failed: R96-B: multi-session factory not yet wired in DaemonRunner; non-default session 'foo' cannot be created."}` — a clear signal
that the user needs to wait for R97+ for that
path.

## Lessons

- **Two wiring paths are fine when one is
  legacy.** Removing the `Builder.sessionManager`
  path would have broken every existing test
  that builds an engine with a wired manager
  (R95-E's own 20 tests, plus the
  SessionManagerRpcTest's negative case). Adding
  a second path is a 3-arg ctor + a helper
  method; both call sites converge on the
  methods-level field.
- **Package-private helpers are testable.** Making
  `buildSessionManager` package-private (rather
  than private) lets the unit test verify the
  wiring without spinning up the JSON-RPC
  transport. Same pattern as the engine
  accessor tests.
- **`final` fields bite.** AetherCodeEngine's
  `sessionManager` is `final`, so post-construction
  wiring is impossible without relaxing the
  modifier. R96-B routes around the limitation
  via the methods-level field. Future R97+
  setter additions are a one-line change.
