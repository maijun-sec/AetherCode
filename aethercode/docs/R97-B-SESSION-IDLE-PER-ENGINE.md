# R97-B — SESSION_IDLE Per-Engine + per-RPC sessionId Routing (2026-08-17)

## What ships

R97-B closes the next leg of the multi-session
loop that R95-E opened. Pre-R97-B, factory-built
engines silently missed the SESSION_IDLE
boulder-continuation wiring (the default engine
got it via the constructor; non-default engines
didn't). And the 100+ RPCs all routed to the
default engine — there was no per-RPC `sessionId`
param. R97-B fixes both:

1. **`AetherCodeEngine.setSessionManager()`** — relaxed
   the `sessionManager` field from `final` to
   `volatile` and added a setter. The daemon can
   now install a manager post-construction (the
   default CLI flow: build the engine, then wire
   the manager around it).

2. **`SessionManager.addOnCreateListener(Consumer)`** — a
   copy-on-write listener list that fires every
   time the manager materialises a new engine
   (via `getOrCreate` or `registerExisting`).
   Listeners do NOT fire on `get` for an existing
   handle. The returned `Runnable` unregisters
   the listener. Listener exceptions are
   isolated (logged + swallowed) so one bad
   listener can't block the rest.

3. **Per-engine SESSION_IDLE wiring** — the
   `AetherCodeMethods` 3-arg constructor now
   registers a create-listener that builds a
   fresh `EngineContinuationDispatcher` +
   `TodoContinuationHook` pair per non-default
   engine and installs the SESSION_IDLE listener
   on it. Pre-R97-B, this listener was bound to
   the constructor's engine only, so factory
   builds were effectively silent on the
   continuation path.

4. **per-RPC `sessionId` for `query` + `cancel`** —
   `query` now resolves the target engine via
   `effectiveSessionManager().get(sessionId)`
   when the param is present, falling back to
   `currentEngine()` (which routes to the
   active session in multi-session mode, or the
   default engine in legacy single-engine mode).
   `cancel` accepts the `sessionId` as a
   caller-clarity hint (logged at DEBUG) but
   the actual cancellation is by `runId` (the
   single source of truth).

## Why this is the minimum for multi-session to be useful

Without R97-B, a daemon with two factory-built
engines has two problems:

- The default engine's boulder-continuation
  hook fires on every SESSION_IDLE event. The
  factory-built engines get no hook at all, so
  a `TodoContinuationHook`-style auto-continue
  on those sessions never runs. From the user's
  perspective, the behaviour is invisible (no
  error, no log) but the auto-continue is
  asymmetric across sessions.
- The 100+ RPCs all route to the default engine
  regardless of which session the user thinks
  they're targeting. So `query(prompt)` for
  session "worktree-1" actually runs the
  default engine's model and writes to the
  default engine's transcript. The session
  abstraction is a façade.

R97-B fixes the first via the create-listener
pattern. For the second, R97-B takes the
high-value subset: `query` (the most-used
RPC) and `cancel` (its companion). Other RPCs
(`setModel`, `setPermissionMode`, etc.) can be
added in subsequent rounds — the pattern is
established and the helper
(`currentEngine()`) is in place.

## Wire integration

`AetherCodeMethods.query` shape (R97-B):
```json
// in
{ "prompt": "...", "sessionId": "worktree-1" }
// out (success)
{ "runId": "run-N", "accepted": true }
// out (routing error)
{ "ok": false, "error": "no such sessionId: worktree-1" }
{ "ok": false, "error": "session manager not configured (cannot route query to sessionId)" }
```

`AetherCodeMethods.cancel` shape (R97-B):
```json
// in
{ "runId": "run-N", "sessionId": "worktree-1" }
// out (success)
{ "cancelled": true, "runId": "run-N" }
// out (unknown runId)
{ "cancelled": false, "reason": "no such runId" }
```

The `sessionId` hint in `cancel` is logged at
DEBUG (operator can correlate "I sent cancel
to session X" with the originating session).
The actual cancellation is by `runId` because
the runId uniquely identifies the future in
the global `inFlight` map regardless of which
engine the query targeted.

## What's NOT in R97-B (deferred to R97-C+)

- **Per-RPC sessionId for `setModel`,
  `setPermissionMode`, `getState`, etc.** R97-B
  only adds it for `query` + `cancel` (the
  high-value pair). The other RPCs continue to
  route to the constructor's engine. Adding
  them is a 1-line `currentEngine().xxx` swap
  per RPC; future R-rounds can finish the sweep
  incrementally.

- **AetherCodeEngine.setSessionManager() is
  one-way.** R97-B's setter replaces the
  binding; existing `EngineHandle`s in the
  old manager are NOT migrated. The
  use-case (the daemon wiring the manager
  right after engine build) doesn't need
  migration, so this is acceptable.

- **Per-engine transcript / task push.** R108-1
  wired these to the broadcast notifier, which
  is process-scoped. R97-B doesn't change
  that; a per-engine `setTranscriptPush` is
  possible via the create-listener if a
  per-session broadcast is needed.

- **Per-engine continuation dispatcher's
  cross-session check.** The dispatcher uses
  `engine.appState().sessionId()` to identify
  the session in the continuation flow. With
  per-engine pairs (R97-B), this lookup is
  per-engine so there's no cross-contamination.

## Files touched

| File | Change |
|------|--------|
| `aethercode-sdk/src/main/java/.../sdk/AetherCodeEngine.java` | `sessionManager` field: `final` → `volatile`; new `setSessionManager()` setter |
| `aethercode-sdk/src/main/java/.../sdk/SessionManager.java` | `addOnCreateListener(Consumer)` method; copy-on-write listener list; `fireOnCreate` helper fires on `getOrCreate` / `registerExisting`; `safeSessionId` log helper |
| `aethercode-sdk/src/test/java/.../sdk/SessionManagerTest.java` | +8 R97-B addOnCreateListener tests (fires on getOrCreate / registerExisting, NOT on get; multi-listener fan-out; exception isolation; unregister; concurrent register+create) |
| `aethercode-sdk/src/test/java/.../sdk/AetherCodeEngineSessionManagerTest.java` | NEW, 5 tests for the setter (default null, install, replace, clear, volatile consistency) |
| `aethercode-protocol/src/main/java/.../methods/AetherCodeMethods.java` | `ensureSessionIdleListener` now builds a per-engine dispatcher + hook pair for non-default engines; constructor wires the create-listener; `query` accepts `sessionId` and routes via `effectiveSessionManager().get()`; `cancel` accepts `sessionId` as a hint |
| `aethercode-protocol/src/test/java/.../methods/SessionManagerRpcTest.java` | +6 R97-B tests (query rejects unknown sessionId, query rejects when no session manager, query without sessionId falls back, cancel with hint, cancel without hint, create-listener wired at construction fires for factory builds) |
| `aethercode/docs/r97b-smoke.mjs` | NEW, end-to-end WebSocket smoke test for per-RPC sessionId routing |

## Test counts

| Module | Before R97-B | After R97-B | Delta |
|--------|-------------:|------------:|------:|
| aethercode-sdk | 139 | 152 | +13 (8 SessionManager + 5 setter) |
| aethercode-protocol | 70 | 76 | +6 (per-RPC routing) |
| **Total Java** | **1876** | **1895** | **+19** |

TUI + Desktop test counts are unchanged. E2E
smoke test (`r97b-smoke.mjs`) covers the full
RPC surface against a live daemon (9 checks).

## Build artifacts

- `dist/aethercode-0.2.1.jar` 39.89 MB (R97-B
  added ~2 KB for the new setter, addOnCreateListener,
  and per-RPC routing code paths)
- `dist/ac-tui/ac-tui.js` 1.59 MB (unchanged;
  no TUI surface change in R97-B)
- 16 modules `mvn install` successful

## Lessons

- **Listener list is the right pattern for
  per-instance wiring.** A `Map<EngineHandle,
  List<Listener>>` is more invasive (callers
  need to know about it; the manager exposes
  a richer API). A single global
  `List<Consumer<Engine>>` on the manager with
  copy-on-write semantics is the minimal
  extension: callers add a listener once at
  construction, the manager fires on every new
  engine. The listener closes over the
  caller-side state (the dispatcher / hook /
  notifier) so per-engine wiring is
  encapsulated.

- **The query method is a 175-line monolith.**
  Refactoring it to use a local `target` variable
  (so the engine is resolved once at the top)
  is mechanical but error-prone. Doing it via
  a Python script (find the body, replace
  `engine.` with `target.`) is reliable for
  this kind of refactor; doing it manually
  would risk a missing substitution that only
  surfaces at runtime.

- **`final` is a strong commitment.** R95-E
  made `sessionManager` `final` to enforce
  "set at construction". R96-B's wiring
  pattern (daemon sets the manager after the
  engine is built) requires the field to be
  mutable. The minimum-impact change is
  `final` → `volatile` + a setter; the
  `final` was never load-bearing (the engine
  doesn't snapshot the field, the dispatcher
  reads it via the accessor).

- **Cancel doesn't need to know the sessionId.**
  The `inFlight` map is global (keyed by
  `runId`). The `sessionId` hint in `cancel`
  is caller-clarity (the renderer can say "I
  cancelled session X's query"), but the
  actual cancellation is by `runId` because
  that's the unique key. Trying to add
  per-engine `inFlight` maps would be
  over-engineering for a 1-line RPC.

- **E2E smoke tests catch contract bugs the
  unit tests miss.** The smoke test caught a
  logic bug in the test itself (checking
  `!r.ok || !r.error.includes(...)` when
  `r.ok` was `false` from the routing error
  path), which is itself a signal that the
  error response shape is `ok: false, error: ...`
  not `error: ...` alone. The unit tests pass
  with both shapes; the smoke test asserts the
  wire contract.

## R97+ candidates (continuing)

- **R97-C**: Tauri App 适配 multi-session
  (R95-E / R97-A / R97-B surface parity in
  the Renderer's session picker)
- **R97-D**: `/prompt` 加 diff (compare 当前
  vs saved baseline)
- **R97-E**: Session resume (R106 sessionStore
  + R97-A SessionManager 联合)
- **R97-F**: Per-session `cwd` override (the
  "multi-worktree" use case)
- **R97-G**: Per-RPC sessionId for the
  remaining RPCs (`setModel`,
  `setPermissionMode`, `getState`, ...)
- **R97-H**: Per-engine transcript push
  (R108-1 currently broadcasts to all clients;
  R97-H would scope it to the active session)
