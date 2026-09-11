# R106: Daemon SessionStore + Skill/Agent Executor

Date: 2026-08-12
Builds on: R105 (session persistence, workflow policies)
Daemon jar: `aethercode-0.2.14.jar` (~41.5 MB)
Frontend bundle: 466.24 KB JS / 73.34 KB CSS (unchanged from R105)

## Scope

R106 ships two independent improvements under one daemon release:

- **R106-A** — Daemon-side per-session store. The `SessionStore` and
  `Transcript` infrastructure that the CLI has used since R6 now
  also serves the HTTP+WebSocket daemon. The desktop's
  multi-session stack moves from localStorage to real on-disk
  persistence.
- **R106-B** — `skill` / `agent` step types in the workflow
  executor. R103's stubs now run a real query on a child
  session and stream the result back as the step's stdout.

## R106-A: Daemon-side per-session store

### Before (R105 / R29)

The HTTP+WS daemon's `loadSession` was a R29-era stub that threw
`"not yet implemented"`. The `listSessions` RPC always returned
`[]`. The desktop's multi-session stack fell back to a
localStorage-only model: messages persisted to the browser's
local storage under `aethercode-session:<id>`, but the daemon
had no knowledge of any session other than its own startup
session.

### After

The daemon's engine now owns a `SessionStore` (the same
`org.aethercode.core.transcript.SessionStore` the CLI has used
since R6). The store is a directory of per-session JSONL files
(default `<cwd>/.aethercode/sessions/`, configurable via the new
`--sessions-dir` CLI flag). Every `appState.appendMessage` is
mirrored to the current session's file via a listener, and the
engine supports four new RPCs:

- `listSessions` — returns the on-disk sessions sorted
  newest-first, each with `id / lastUsedAt / sizeBytes /
  messageCount` (the message count is a cheap `sizeBytes / 800`
  proxy — the JSONL line count is the truth, this is just for
  the LeftPanel's `n msg` label).
- `loadSession({sessionId})` — switches the engine to a
  different session. Replaces the in-memory transcript, updates
  `appState.sessionId`, and the listener's reference to the
  new file. Returns the new message count.
- `createSession({})` — mints a fresh session id
  (`<ISO-timestamp>_<8-char-uuid>`), materialises an empty file
  on disk so `listSessions` shows it immediately, and switches
  the engine to it in the same RPC. Returns the new id and
  message count (0).
- `deleteSession({sessionId})` — removes a session file.
  Refuses to delete the active session (the user must switch
  first). Returns `removed: true|false`.

### Wire shape

```jsonc
// listSessions
{ "sessions": [
    { "id": "...", "lastUsedAt": 1734..., "sizeBytes": 4200, "messageCount": 5 },
    { "id": "...", "lastUsedAt": 1734..., "sizeBytes": 120,  "messageCount": 0 }
  ],
  "current": "2026-08-12T03-55-07.395122500Z_f7ade546" }

// createSession
{ "ok": true, "sessionId": "...", "messageCount": 0, "active": "..." }

// loadSession
{ "ok": true, "sessionId": "...", "messageCount": 42 }

// deleteSession
{ "ok": true, "sessionId": "...", "removed": true }
```

### Session lifecycle

The engine's `attachSessionStore` (called from the constructor
when a `SessionStore` is wired) does four things:

1. Loads the engine's current session's file from the store
   (creates an empty file if it doesn't exist — the engine's
   startup session is now visible to `listSessions` even
   before the first user message).
2. Reconciles in-memory vs on-disk transcript: if the
   in-memory has more messages (e.g. the CLI's resume path
   loaded from another source), overwrites the file; if the
   file has more, replaces in-memory.
3. Installs a `Consumer<Message>` on `AppState.onMessageAppend`
   that writes each new message to the current `Transcript`.
4. Logs `"SessionStore wired: <id> (<n> messages on disk)"`.

`loadSession(id)` does the same reconciliation but additionally
swaps `currentTranscript`, replaces `appState.transcript()`, and
mutates `appState.sessionId()` so subsequent reads report the
new session. The listener's `currentTranscript` reference is
volatile, so writes from in-flight queries (rare race) go to
the new file.

`createSession()` calls `SessionStore.newSessionId()` (a
timestamp + 8-char UUID), materialises the empty file
(`Files.createFile`), and then `loadSession(newId)` to make
the engine coherent. The CLI's `rebuildEngine` pattern
(every switch spawns a new engine) is heavier than needed for
the daemon — the daemon's engine is long-lived and the
SessionStore + listener path lets the same engine swap files
cheaply.

### Frontend changes

- `methods.ts` — `listSessions` returns `error?: string` in
  addition to `sessions / current`. `loadSession` returns
  `{ok, sessionId, messageCount}` (the previous `{ok, session}`
  was a stub). New `createSession` and `deleteSession` wrappers.
- `store/index.ts`:
  - `createNewSession()` now calls `rpc.createSession()` first
    (to get a real engine id), then `rpc.loadSession(newId)`,
    then sets `currentSessionId`. Falls back to the R105-B
    local UUID if the daemon rejects the call (the stdio
    daemon's path is unchanged).
  - New `deleteSession(id)` action — calls the daemon, then
    removes the localStorage cache for the id and drops the
    entry from the local session list. Surfaces daemon
    errors as a system message.
  - The localStorage cache (`aethercode-session:<id>`) is
    still the source of truth for the *frontend's* message
    history; the daemon's `loadSession` doesn't push
    transcript down to the renderer. The
    `readSessionMessages` / `writeSessionMessages` / `merge
    WithPersisted` helpers from R105 are unchanged. The
    daemon now also has its own view; the two views are
    consistent because the renderer's `useStore.subscribe`
    saves on every messages change.
- `SessionList.tsx` — per-row delete button (a `×` on the
  right of each row, hidden on the active row). Confirms
  before removing; surfaces the daemon's "cannot delete the
  active session" error if the user gets there anyway.
- `commandCommands.ts` — new `/session delete <id-tail>` slash
  command for keyboard-driven deletion.

### Tauri (Rust) changes

`src-tauri/src/lib.rs::spawn_daemon` now passes
`--sessions-dir <cwd>/.aethercode/sessions` to the spawned
JVM. Without this the daemon falls back to the R29 stub
(graceful — `loadSession` returns `ENGINE_ERROR`, etc.) but
the desktop's multi-session stack is reduced to localStorage
again. **Caveat**: the Tauri Rust binary cannot be rebuilt
in the current environment (offline rust toolchain —
`rustup default` failed with `os error 10060`). The
R106-A change to `lib.rs` is in source; a `cargo build`
will pick it up once the toolchain is restored. Until then,
the test daemon (run manually with `--sessions-dir`) exercises
the new path.

### Files

- `aethercode-core/src/main/java/org/aethercode/core/app/AppState.java`
  — added `onMessageAppend` listener field + `appendMessage`
  fan-out. Made `sessionId` mutable + volatile (was `final`)
  so `loadSession` can switch it. Added a public setter.
- `aethercode-core/src/main/java/org/aethercode/core/transcript/SessionStore.java`
  — unchanged. R6 was the right design.
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`:
  - `Builder.sessionStore(SessionStore)` and a matching
    `Builder` field.
  - Engine constructor wires `sessionStore` and calls
    `attachSessionStore()` after the initial transcript
    load.
  - New `attachSessionStore()` private method (see
    "Session lifecycle" above).
  - New `currentTranscript()` accessor.
  - New `listSessions()` / `loadSession(id)` /
    `createSession()` / `deleteSession(id)` accessors. All
    four are public so the protocol layer can call them
    directly.
  - New `queryInChildSession(parentId, kind, name, prompt)`
    — used by R106-B.
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`:
  - `listSessions` rewritten to call `engine.listSessions()`.
  - `loadSession` rewritten to call `engine.loadSession(id)`.
  - New `createSession` / `deleteSession` methods.
  - All four registered in `registerAll()` (for the stdio
    daemon) **and** in `HttpJsonRpcServer`'s switch (for the
    HTTP+WS daemon). Missing either registration gives
    `METHOD_NOT_FOUND` (the latter was the bug caught during
    the WS roundtrip — see "Lessons" below).
- `aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`:
  - Added `createSession` and `deleteSession` cases to the
    manual switch (the HTTP+WS path doesn't use
    `AetherCodeMethods.registerAll`; it dispatches inline).
  - `/api/methods` list now includes both new methods.
- `aethercode-cli/src/main/java/org/aethercode/cli/Main.java`:
  - `buildEngine()` now passes `b.sessionStore(...)` when
    `--sessions-dir` is set. The default cwd (when the
    flag is omitted) is unchanged; the REPL's separate
    `repl.withSessions(...)` path is preserved.
- `aethercode-desktop/src/lib/methods.ts` — new wrappers.
- `aethercode-desktop/src/store/index.ts` — updated
  `createNewSession` to call the daemon, new
  `deleteSession` action.
- `aethercode-desktop/src/components/SessionList.tsx` —
  delete button + confirmation.
- `aethercode-desktop/src/components/commandCommands.ts` —
  `/session delete` slash command.
- `aethercode-desktop/src/components/SessionList.css` —
  per-row delete button styling (hover-only, 22×22, red
  tint on hover).

### WS roundtrip (9/9 PASS)

```
[r106-1] initial listSessions: 1 sessions; current=<uuid>      PASS
[r106-2] createSession: <iso-timestamp>_<8-hex>                 PASS
[r106-2] file on disk: true                                     PASS
[r106-2] empty file PASS: true                                 PASS
[r106-3] loadSession: {ok, sessionId, messageCount: 0}         PASS
[r106-4] after-create listSessions: 2 sessions; current=<new>  PASS
[r106-5] deleteSession on active refused: true                 PASS
[r106-6] deleteSession: {ok, sessionId, removed: true}         PASS
[r106-7] after-delete listSessions: 1 sessions                 PASS
[r106-8] deleteSession ghost: {ok, removed: false}             PASS
```

The test daemon runs on port 17889 with
`--sessions-dir <cwd>/.aethercode/sessions`. Port 17888's
Tauri-spawned daemon still has the old code (see "Tauri
changes" caveat) — that's the regression check, not a real
shipped state.

### Lessons (R106-A)

1. **The HTTP+WS daemon has its own dispatch switch** in
   `HttpJsonRpcServer.handle()` that's separate from
   `AetherCodeMethods.registerAll()`. Adding a new RPC
   requires editing **both** maps or the desktop gets
   `METHOD_NOT_FOUND` with a confusingly truncated message
   (`"createSession"` rather than `"Method not found: createSession"`).
   The bug surfaced in the WS roundtrip and took 10
   minutes to track down.
2. **An empty Transcript is not a file on disk** —
   `SessionStore.loadOrCreate` returns a Transcript whose
   `messages()` is empty when the file doesn't exist, but
   the file is only materialised on the first
   `Transcript.append`. For the LeftPanel's "I just
   clicked +" UX, the file must be created at
   `createSession` time (and at engine startup, so the
   engine's own session shows in the list). R106 fixes both
   call sites.
3. **`AppState.sessionId` being `final` was a load-bearing
   assumption** in the original R15 design. R106's
   `loadSession` mutates it, which means downstream code
   that snapshots `appState.sessionId()` into a local
   before doing anything else is now correct; code that
   reads it on every event (like the QueryEngine's loop
   detector) is also fine. The risk is in code that
   *caches* the session id and assumes it doesn't change
   — a search showed no such cases.
4. **The "child session" design is the missing piece** for
   R103's `skill` / `agent` stubs. R106-B uses it (see
   below). The pattern is "save parent state, run child
   query, restore parent state" — not as clean as a real
   child session abstraction, but enough for a workflow
   step that needs the answer and not the transcript.
5. **localStorage cache stays for the renderer**. The
   daemon now has its own truth (the JSONL files), but the
   renderer's `messages` array is still the source for
   `MessageList`. The two views are reconciled by the
   `useStore.subscribe` save + the per-session load. This
   is a temporary dual-state arrangement; R107+ could
   stream the daemon's transcript to the renderer and
   drop the local cache.

---

## R106-B: skill / agent executor via child session

### Before (R103)

`skill` and `agent` step types emitted a synthetic
`ok` event with the stub message
`"skill executor lands in R104"`. The progress bar showed
the step as done; the user got no actual skill or agent
invocation.

### After

The engine grows a `queryInChildSession(parentId, kind,
name, prompt)` method that:

1. Saves the parent's `appState.sessionId()` and
   `currentTranscript`.
2. Mints a child session via `createSession()` (empty
   file, switched in).
3. Runs `engine.query(prompt)` on the child — the
   QueryEngine appends to the listener's transcript
   (the child file) and streams events through the
   regular path.
4. Captures `StreamEvent.TextDelta` from the child stream
   into a `StringBuilder`.
5. Restores the parent's session id and transcript in
   a `finally` block. The child file is left on disk for
   the user to inspect via `listSessions`.

The `WorkflowExecutor` grows a `SkillInvoker` functional
interface (a 3-arg lambda). `AetherCodeMethods.runWorkflow`
passes a closure that calls
`engine.queryInChildSession(parentId, kind, name, prompt)`.
The executor's `runSkillOrAgent(step, chunk, kind)` reads
the step's `name:` and `prompt:` (or `input:` / `message:` /
`text:` / `args:` / `body:` / `content:` as fallbacks)
fields, runs Jinja-substitution, invokes the hook, and
stores the returned text as the step's stdout. The step
is `ok` on success and `error` on any exception.

### YAML shape

```yaml
steps:
  - id: explain-error
    type: skill
    name: explain-error
    prompt: "Explain this build error: {{steps.build.stderr}}"
  - id: research
    type: agent
    name: research-agent
    prompt: "Find documentation for {{inputs.feature}}"
```

### Why a child session?

A `skill` / `agent` step needs the model to behave as the
named skill/agent, with a fresh transcript. Reusing the
parent's transcript would pollute the user's main
conversation with the skill's intermediate steps. The
child-session pattern (R106-B's
`queryInChildSession`) gives the skill its own
transcript, then restores the parent's. Cost: one
`createSession` + one `Files.createFile` + one
`loadSession` round trip per skill step. The child
session is *not* deleted at the end (the user can see
it in `listSessions` and click into it to see what the
skill did); a future R107 could add a `discard_after: true`
flag for short-lived skills.

### Caveat: full streaming

`queryInChildSession` captures the final assistant text
only — tool calls fire and complete silently inside the
child, but the executor doesn't surface them. The desktop
sees the skill's "ok" transition with the final answer
as `stdout`; the intermediate tool calls are lost from
the desktop's view (the daemon's trace recorder still
captures them). A future R107+ could wire the child's
stream events through the workflow's `sink` so the
desktop's `WorkflowProgressBar` shows a nested
"running skill X → called tool Y → ok" trail.

### Files

- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowExecutor.java`:
  - New `SkillInvoker` interface.
  - New secondary constructor accepting an invoker.
  - `runSkillOrAgent` method (replaces the two `runStub`
    calls in `executeStep`).
  - `runStub` still exists for the no-invoker fallback
    (test paths that don't wire the engine).
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`:
  - `runWorkflow` now passes a `SkillInvoker` closure that
    calls `engine.queryInChildSession`.

### Test results

- 19/19 `WorkflowExecutorTest` pass (no regression on
  R105's wait / `continue_on_error` cases).
- 9/9 `WorkflowReaderTest` pass.
- 11/11 `SessionStoreTest` pass.
- 18/18 `ProgressLoopDetectorTest` pass.
- 5/5 `HttpJsonRpcServerTest` pass.
- **62/62 total** (was 57 in R105; +5 from
  `HttpJsonRpcServerTest` which I ran as a regression
  spot-check).

No new tests for `skill` / `agent` executor in this
PR — the integration path requires a live engine + chat
client, which the unit test suite doesn't wire. A R107+
end-to-end test against a real daemon (with `--sessions-dir
test/` and a mock chat client) would close this gap.

---

## Cross-project lessons (R106)

1. **HTTP+WS daemon's dispatch switch is a parallel map** to
   the stdio daemon's `registerAll()`. Forgetting to update
   both is the easiest way to ship a broken RPC. The
   default-arm throws `METHOD_NOT_FOUND` with just the method
   name as the message — confusing for the WS client until
   you look at the code.
2. **Mutable `final` fields don't compose well with
   state-machine APIs**. `AppState.sessionId` being `final`
   was a clean constraint; R106's `loadSession` needed to
   mutate it. The fix (volatile + setter) is straightforward
   but easy to forget in code reviews.
3. **The CLI's `rebuildEngine` pattern is heavy for the
   daemon**. The CLI rebuilds the whole engine (Spring AI
   client, memory recall, trace recorder) on every session
   switch. The daemon's `loadSession` mutates the
   in-memory transcript and re-points the listener — much
   cheaper, and the user sees the same UX.
4. **`Files.createFile` is the right way to materialise
   an empty file**. `Files.writeString(p, "")` also works
   but is less obvious in code review. The empty file is
   the on-disk marker that "this session exists" — it
   surfaces in `listSessions` even when the user never
   sends a message.
5. **Listener-based mirrors are simple but order-sensitive**.
   `AppState.onMessageAppend`'s listener fires AFTER the
   in-memory transcript is updated. The `loadSession` path
   replaces the transcript directly (`appState.transcript()
   .clear()` + `add()`) without going through
   `appendMessage`, so the listener doesn't re-write the
   same lines. This is the right ordering; the opposite
   would cause an infinite loop on switch.
6. **Skill/agent executor needs an engine reference**,
   which the executor in `aethercode-core` can't have
   (would create a circular dependency). The
   `SkillInvoker` functional interface decouples the two:
   the executor is testable without an engine, and the
   protocol layer bridges them. This is the same pattern
   as R104's `listCwdFiles` — a small functional interface
   to let a core component call out to an SDK-only API.
7. **The R103 stub-to-real transition is non-trivial**.
   R103's stubs were useful for end-to-end wiring tests
   (the desktop could see the step type appears in the
   picker and the progress bar). R106's real execution
   needs an actual chat client, which is why the unit
   test suite doesn't cover it. A future R107 integration
   test (real daemon + mock chat client + WS roundtrip)
   would catch regressions in the skill path.
