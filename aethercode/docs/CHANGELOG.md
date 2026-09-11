# AetherCode Changelog

## 0.3.7 — 2026-08-17 (R97-C — App self-contained + first-launch folder picker + Rust jar-priority fix)

R97-C makes the desktop App actually self-contained. Pre-R97-C, the App expected a daemon to be running externally; the release package's `run-app.bat` had a comment about `AETHERCODE_DAEMON_URL` but no real auto-spawn. R97-C fixes the UX so the user can double-click `aethercode-desktop.exe`, see a folder picker at first launch, and the App does the rest. Also fixes a pre-existing bug where a development jar in the project's `aethercode/dist/` would out-prioritise the release jar.

- **Rust `find_jar_path` rewrite** (`aethercode-desktop/src-tauri/src/lib.rs`): the function now prefers the Tauri-bundled resource first, then the **closest** ancestor with a matching `aethercode/dist/`, then `$AETHERCODE_DIST`. Pre-R97-C, the function collected ALL candidates (from all ancestors) and picked the highest version, which let a 0.2.14 dev jar in `D:\work\workspace\idea\engine\AetherCode\aethercode\dist\` out-prioritise the release-r97g's 0.2.1. The fix: return at the first source with a match. Closest-ancestor wins regardless of version.
- **Tauri resource bundling** (`aethercode-desktop/src-tauri/tauri.conf.json`): the daemon jar is now bundled into the App as a Tauri resource. `tauri build` copies `../../aethercode/dist/aethercode-0.2.1.jar` into the exe's resource directory; the App finds it via `app.path().resource_dir()`. The release package no longer needs the `aethercode/dist/` shim for App users (TUI users still need `tui/aethercode-0.2.1.jar`).
- **Welcome.tsx first-launch flow**: when the App opens and there's no project (no sessionId yet), the first tile says "打开文件夹" (Open Folder) instead of "新会话" (New Session). Click it, pick a project directory in the native dialog, the daemon is restarted with `--cwd=<picked-path>`, and the tile becomes "新会话" for future launches.
- **Header.tsx cwd button**: added a 📂 icon in the top-right of the header bar (next to the existing ⚙ settings). Always visible, always works. Click to re-open the folder picker mid-session.
- **run-app.bat simplified**: no more `AETHERCODE_DAEMON_URL` env var. The launcher just runs the exe. The App does the rest.

**What the user does** (R97-C flow):
1. Set `JAVA_HOME` once (or add `java.exe` to `PATH`). The README / RELEASE-NOTES explains this in the Java setup section.
2. Double-click `app\aethercode-desktop.exe`.
3. The App opens. The status bar shows "● Idle" within ~2 seconds.
4. The welcome screen's first tile says "打开文件夹". Click it, pick a project.
5. The App is now ready to use — the daemon is running with `--cwd=<picked-path>`, the new session is auto-created, and the input bar is ready for a prompt.

**What the user can do mid-session**:
- Click the 📂 icon in the header to change the project directory. The daemon restarts with the new `--cwd`. The current conversation is preserved (R106 sessionStore writes to `<cwd>/.aethercode/sessions/`).
- Use `/sessions-list` (via the TUI or a future App panel) to switch between multiple engines in the same daemon.

**Bug fix details**: the pre-R97-C `find_jar_path` was vulnerable to a "wrong version picked" bug whenever a higher-versioned jar existed anywhere in the ancestor walk. The fix is twofold: (1) Tauri resources are checked first (canonical for the App version), (2) within the ancestor walk, the FIRST ancestor with a match wins (closest to the exe). These two changes together mean the App reliably uses the bundled jar.

**Test count delta (R97-C)**: 0 Java (R97-C is TypeScript + Rust + package-layout). 33 Desktop tests unchanged (the Welcome + Header components are not unit-tested; the change is verified by `tsc --noEmit` and `npm run tauri:build` succeeding).

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 39.89 MB (unchanged from R97-G)
- `dist/release-r97g/app/aethercode-desktop.exe` (rebuilt; the Rust code + the bundled frontend changed; the daemon jar is now embedded as a Tauri resource)
- `dist/release-r97g/app/run-app.bat` (simplified; no `AETHERCODE_DAEMON_URL`)
- `dist/release-r97g/tui/aethercode-0.2.1.jar` (still bundled for TUI users)

## 0.3.6 — 2026-08-17 (R97-G — per-RPC sessionId for getState / listTools / setModel / setPermissionMode)

R97-G finishes the per-RPC sessionId routing sweep that R97-B started for `query` and `cancel`. Pre-R97-G, a renderer that switched to a different session still got the default engine's state from `getState` / `listTools` (the four "inspection + admin" RPCs always read the constructor engine). R97-G fixes this for the four most commonly called RPCs.

- **`AetherCodeMethods.resolveRpcTarget(String)`** — new private helper. Resolves the target engine via `effectiveSessionManager().get(sessionId)` when the param is non-blank; falls back to `currentEngine()` (active engine in multi-session mode, or constructor engine in legacy single-engine mode). Returns null for unknown sessionIds (caller maps to `{ok: false, error: "no such sessionId: <id>"}`).
- **`getState`** — routes to the target engine. Response now carries the engine's `sessionId` so the renderer can verify the routing.
- **`listTools`** — same. Response carries the engine's `sessionId` + tool pool.
- **`setModel`** — updates only the target engine's `appState().mainLoopModel()`. Response carries the post-state + `sessionId`.
- **`setPermissionMode`** — updates only the target engine's `appState()` AND live policy. Validation (mode must be a valid `PermissionMode` enum) happens before the session lookup, so a bad mode always errors with the same shape.

**E2E verified**: `docs/r97g-smoke.mjs` exercises getState / listTools / setModel / setPermissionMode against a live daemon, verifying that the response `sessionId` matches the request `sessionId` AND that the default engine is untouched. All 12 checks pass. The smoking-gun test is `setModel({model: "M-r97g-test", sessionId: "r97g-alpha"})` followed by `getState({})` (no sessionId) — the default engine's model must still be `MiniMax-M3` (unchanged), while `getState({sessionId: "r97g-alpha"})` returns `M-r97g-test`. This proves the routing is correct AND that the default engine is isolated.

**Test count delta (R97-G)**: +9 Java (aethercode-protocol per-RPC routing). Reactor: 1895 → 1904. TUI + Desktop unchanged.

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 39.89 MB (+357 bytes from R97-B for the helper + sessionId echo)
- `dist/ac-tui/ac-tui.js` 1.59 MB (unchanged; no TUI surface change in R97-G; the TUI already had `/session <id>` from R95-E)
- 16 modules `mvn install` successful

**Wire protocol**:
- `getState`, `listTools`, `setModel`, `setPermissionMode` all accept optional `sessionId` param. Response payloads now include the engine's `sessionId` for verification. Routing errors return `{ok: false, error: "no such sessionId: <id>"}` matching the R97-B convention.

## 0.3.5 — 2026-08-17 (R97-B — SESSION_IDLE per-engine + per-RPC sessionId routing)

Pre-R97-B, factory-built engines (created via `createEngine` after R97-A) silently missed the SESSION_IDLE boulder-continuation wiring (only the default engine got it via the constructor). And the 100+ RPCs all routed to the default engine regardless of which session the user thought they were targeting. R97-B fixes both:

- **`AetherCodeEngine.setSessionManager()`** — relaxed the `sessionManager` field from `final` to `volatile` and added a setter. The daemon can now install a manager post-construction.
- **`SessionManager.addOnCreateListener(Consumer)`** — copy-on-write listener list that fires every time the manager materialises a new engine (`getOrCreate` or `registerExisting`). Does NOT fire on `get` for an existing handle. Returns a `Runnable` for unregistration. Listener exceptions are isolated.
- **Per-engine SESSION_IDLE wiring** — `AetherCodeMethods` 3-arg constructor registers a create-listener that builds a fresh `EngineContinuationDispatcher` + `TodoContinuationHook` pair per non-default engine and installs the SESSION_IDLE listener on it. Stored in `perEngineDispatchers` / `perEngineHooks` maps keyed by sessionId.
- **per-RPC `sessionId` for `query` + `cancel`** — `query` resolves the target engine via `effectiveSessionManager().get(sessionId)` when the param is present, falling back to `currentEngine()`. `cancel` accepts `sessionId` as a caller-clarity hint (logged at DEBUG); the actual cancellation is by `runId`.

**E2E verified**: `docs/r97b-smoke.mjs` exercises `listEngines` → `createEngine` → `query(sessionId=...)` → `query(ghost)` (rejected) → `query(default)` (no sessionId) → `cancel(bogus-runId, sessionId=...)` → `deleteEngine` → `listEngines`. All 9 checks pass. Daemon log shows `R97-B: SESSION_IDLE listener wired for non-default engine sessionId=r97b-worktree` confirming the per-engine dispatcher/hook pair materialises on demand.

**Test count delta (R97-B)**: +19 Java (sdk +13 [8 SessionManager.addOnCreateListener + 5 setSessionManager], protocol +6 per-RPC routing). Reactor: 1876 → 1895. TUI + Desktop unchanged.

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 39.89 MB (+2 KB for the new setter, addOnCreateListener, and per-RPC routing code paths)
- `dist/ac-tui/ac-tui.js` 1.59 MB (unchanged; no TUI surface change in R97-B)
- 16 modules `mvn install` successful

**Wire protocol**:
- `query` now accepts optional `sessionId` param. Response shape unchanged on success; on routing error returns `{ok: false, error: "no such sessionId: <id>"}` or `{ok: false, error: "session manager not configured (cannot route query to sessionId)"}`.
- `cancel` now accepts optional `sessionId` hint. Response shape unchanged; the hint is logged at DEBUG.

## 0.3.4 — 2026-08-17 (R97-A — multi-session factory plumbing)

Pre-R97-A, the daemon's `SessionManager` factory threw `UnsupportedOperationException` for any `createEngine` call (the R96-B minimum scope). R97-A plumbs the CLI's `Main.buildEngineForSession(sessionId)` closure into the factory so the user can actually spin up additional engines on demand. Each fresh engine is built with the same CLI config (provider, model, cwd, skills, agents) but with the new sessionId stamped on `AppState`.

- **`Main.buildEngineForSession(String sessionId)`** — instance method, package-private. Refactored from the existing `Main.buildEngine()` (which now delegates here with `null` for the default path). The sessionId argument is wired through `AetherCodeEngine.Builder.sessionId()`.
- **`DaemonRunner.run(engine, factory)` / `runHttp(engine, port, factory)`** — new factory-aware entry points. The legacy 1-arg `run(engine)` and 2-arg `runHttp(engine, port)` overloads are preserved and delegate to the factory-aware form with a `null` factory (which falls back to `refuseNonDefaultFactory`).
- **`DaemonRunner.refuseNonDefaultFactory(String)`** — the default factory for callers that don't have a multi-session engine-build closure handy. Throws `UnsupportedOperationException` with a clear error pointing the user to the R97-A path.
- **`DaemonRunner.buildSessionManager(engine, factory)`** — the 2-arg overload that wires the factory. The pre-existing `buildSessionManager(engine)` is now a wrapper that delegates with a `null` factory.
- **`Main.call()`** passes `this::buildEngineForSession` to `DaemonRunner.run/runHttp` so the daemon's `SessionManager` factory has the full CLI build closure.

**E2E verified**: `docs/r97a-smoke.mjs` exercises `listEngines` → `createEngine` → `setActiveEngine` → `getActiveEngine` → `deleteEngine` → `listEngines` → `deleteEngine(default)` against a live daemon. All 8 checks pass. Daemon log shows `R97-A: built fresh engine for session worktree-1 (model: MiniMax-M3)` confirming the factory was invoked.

**Test count delta (R97-A)**: +10 Java (cli +5 DaemonRunner factory contract, protocol +5 SessionManagerRpc end-to-end). Reactor: 1866 → 1876. TUI + Desktop unchanged.

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 39.89 MB (~+800 bytes from R96-B for the new factory-aware entry points)
- `dist/ac-tui/ac-tui.js` 1.59 MB (unchanged; no TUI surface change in R97-A)
- 16 modules `mvn install` successful

**Wire protocol**: no new RPCs. The R95-E `*Engine` family now actually works end-to-end (pre-R97-A, `createEngine` returned `ok=false` with "R96-B: multi-session factory not yet wired"; post-R97-A it returns `ok=true` with a freshly built engine).

## 0.3.3 — 2026-08-17 (R96-B — SessionManager daemon wiring)

Pre-R96-B, the R95-E multi-session RPCs (`listEngines` / `createEngine` / `deleteEngine` / `setActiveEngine` / `getActiveEngine`) returned `{ok: false, error: "session manager not configured"}` because the daemon never installed a `SessionManager` on the engine it built. R96-B adds the missing wiring:

- **`SessionManager.registerExisting(String, AetherCodeEngine)`** — pre-register an already-built engine for a given session id, bypassing the factory. The factory stays in place for non-default sessions; this is the "I have an engine, please use it for X" seam the daemon needs to install the CLI-built engine as the "default" session.
- **`AetherCodeMethods.effectiveSessionManager()`** — private helper that prefers the SessionManager passed to the 3-arg constructor (the new wiring path) over the engine's own accessor (the legacy path). The five R95-E RPCs now consult this helper instead of `engine.sessionManager()` directly, so the 3-arg constructor is the single source of truth when wired.
- **`DaemonRunner.buildSessionManager(AetherCodeEngine)`** — package-private helper. Both `run()` (stdio) and `runHttp()` (HTTP+WebSocket) call this helper and pass the result to the 3-arg `AetherCodeMethods` / `HttpJsonRpcServer` constructors.
- **`HttpJsonRpcServer(int port, AetherCodeEngine, SessionManager)`** — 3-arg ctor added; 2-arg ctor delegates with a null manager for backward compat.

The new factory throws for non-default session ids (R96-B doesn't plumb the full engine-build path through); the user sees a clear error from `createEngine`. R97+ will mirror the `Main.buildEngine()` closure (provider / model / cwd / skills / etc.) so non-default sessions can be materialised on demand.

**Test count delta (R96-B)**: +19 Java (sdk +7 SessionManager.registerExisting, protocol +9 AetherCodeMethods routing, cli +3 DaemonRunner wiring helper). Reactor: 1847 → 1866.

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 39.89 MB (R96-B 增量 ~+0.4 MB 主要来自新增的 SessionManager 注释和 3-arg ctor 路径)

**Wire protocol**:
- 无新增 RPC,5 个 R95-E RPC 现在真正能工作 (返回 ok=true 而不是 not-configured)
- `HttpJsonRpcServer` 多一个 3-arg ctor,2-arg ctor 保留

## 0.3.2 — 2026-08-17 (R95 — TUI prompt preview, phase budget, multi-session, partial streaming)

R95 ships 7 sub-rounds (A, B, C, D, E, F, G) on top of R94:

- **R95-G** — `/prompt <name>` drill-down. New `getSystemPromptSection(name)` JSON-RPC returns the full text of a single section; case-insensitive match; on miss returns `available[]` so the TUI can show "did you mean…?" without a second round-trip. `formatSystemPromptSection` is a pure formatter in `prompt-formatter.ts`.
- **R95-A** — `/prompt` table gets richer: (1) the rules section now carries the file paths that contributed (the TUI renders a "paths" column with basenames), (2) source labels are color-coded (default = green, rules = blue, builder = yellow, unknown = grey), (3) a 2-second toast fires alongside the sideNote so the user gets status-bar feedback in addition to the scrollback entry. `RulesLoader.lastLoadedFileNames()` is the new thread-local accumulator that powers (1).
- **R95-C** — Retired the entire `aethercode-tui-jline` module. The Java JLine + Lanterna REPL is gone; the cli's `runRepl()` now delegates to `TuiCommand` (Ink TUI) by default. The `JavawAutoRelaunch` helper and the `--fullscreen` / `--no-fullscreen` flags are deleted (they were no-ops without the JLine REPL). Net: -426 tests, dist jar drops from 40.09 MB to 38.03 MB.
- **R95-D** — Two new `Hook.Kind` values: `PRE_MODEL_QUERY` (fired before each model call; carries modelId + iteration + messages snapshot) and `POST_STREAM_END` (fired after each model response; carries modelText + toolCalls). New `Hook.Outcome.Async(future)` for fire-and-forget hooks (audit, slow background lint) that don't block the model loop. `HookContext` gains 4 nullable fields + 2 new factory methods; the legacy factories still build the record correctly.
- **R95-F** — Per-phase tool-call budget. New `PhaseTracker` primitive + `PhaseBudgetHook` (PRE_TOOL_USE that blocks with an actionable message: "phase budget exhausted: phase=implement toolCalls=50/50 costUsd=$0.18/$0.20 → transition to next phase or raise the cap (try: /phase verify or /budget implement 80)"). Default caps: plan=2, explore=20, implement=50, verify=15 (tool calls). 3 new RPCs (`getPhaseBudget`, `setPhase`, `setPhaseBudget`) + 2 TUI slash commands (`/phase`, `/budget`).
- **R95-B** — Subagent real-time streaming end-to-end. `AgentTool.callSingleShot` / `callMultiStep` accept a `partialSink: Consumer<String>` parameter; the background dispatch wires it to `SubagentRegistry.updatePartial(jobId, partial)`. The R93-C wire plumbing is now exercised end-to-end: the TUI SubagentPanel shows a tail preview + "typing…" indicator while the subagent streams text. Foreground callers pass `null` (no partial stream — the whole result returns at once).
- **R95-E** — Multi-session daemon. New `SessionManager` class holds `Map<sessionId, EngineHandle>`, with a per-handle `EngineFactory` that materialises engines on demand. 5 new RPCs (`listEngines`, `createEngine`, `deleteEngine`, `setActiveEngine`, `getActiveEngine`) — note the "Engines" suffix to avoid collision with the pre-existing R106 `listSessions` / `createSession` / `deleteSession` (which operate on the persisted session store). 4 TUI slash commands (`/sessions-list`, `/session <id>`, `/session-new <id>`, `/session-del <id>`). Default session is deletion-protected as a safety net. Cap: `MAX_SESSIONS = 64`.

**Test count delta (R95)**:
- aethercode-hooks: 65 → 96 (+31 = R95-D 14 + R95-F 17)
- aethercode-prompts: 93 → 99 (+6 = R95-A RulesLoader)
- aethercode-protocol: 50 → 56 (+6 = R95-G 4 + R95-A 2)
- aethercode-sdk: 112 → 132 (+20 = R95-E SessionManager)
- aethercode-tools: 202 → 205 (+3 = R95-B AgentTool)
- TUI scripts: 295 → 335 (+40 = R95-A/G/E/F)
- Desktop: 33 (no change)
- Net reactor Java: 1767 → 1847 (+80, offset by -426 from tui-jline removal)
- **Total R95 tests added: 144** (across 6 modules + TUI)

**Build artifacts**:
- `dist/aethercode-0.2.1.jar` 38.03 MB
- `dist/ac-tui/ac-tui.js` 1.62 MB (includes `prompt-formatter.ts`)
- 16 modules mvn install successful (down from 17 with tui-jline gone)

**Wire protocol additions (R95)**:
- `getSystemPromptSection(name)` — single-section full-text dump (R95-G)
- `getPhaseBudget` / `setPhase` / `setPhaseBudget` — phase budget surface (R95-F)
- `listEngines` / `createEngine` / `deleteEngine` / `setActiveEngine` / `getActiveEngine` — multi-session surface (R95-E)
- `getSystemPrompt` payload gained a `paths` field on the rules section (R95-A)
- `subagent_event` payload's `partialResult` field is now actually populated by AgentTool (R95-B)

## 0.3.1 — 2026-08-17 (R95-C — retire tui-jline, Ink TUI becomes the only interactive surface)

- Removed the `aethercode-tui-jline` module (JLine + Lanterna Java REPL) and all its references in the parent pom, the cli's pom, the `aethercode-cli/Main.java` REPL path, and the IDE configuration. Net: -426 tests in the reactor (the JLine ones), 1767 tests remain.
- The cli now auto-delegates to `TuiCommand` (Ink TUI) when invoked without `--print` / `--daemon` / `--http-port`. Drop-in behaviour change: `java -jar aethercode-0.2.1.jar` (no args) now opens the Ink TUI instead of the line-mode Java REPL. Headless users can still use `--print "..."` or `--daemon` for the same workflows.
- `JavawAutoRelaunch` (only useful for the JLine fullscreen path) deleted. `--fullscreen` / `--no-fullscreen` flags removed (they were no-ops without the JLine REPL).
- The cli's `--print` and daemon paths no longer need an in-process prompter. A null `ToolPermissionPrompter` means "ask is auto-denied" (documented behaviour of `ProjectPermissionPolicy`), which is the correct posture for headless runs.
- Two ANSI codes (`\u001b[2m` dim, `\u001b[0m` reset) inlined as constants in `Main.java` for the --print tool-use / SideNote lines (the JLine `TerminalPalette.DIM/RESET` references are gone).

## 0.3.0 — 2026-08-09 (R81 — AetherCode Desktop MVP, side project)

**R81** lives in [`../aethercode-desktop/`](../aethercode-desktop/) (sibling repo, not in this Maven tree). The shared artifact is the R80 daemon jar — it has not changed. Both the TUI (stdio) and the Desktop App (WebSocket) can connect to it.

- Tauri 2.x + React 19 + Vite 6 desktop app
- `ensure_daemon` Tauri command: find or spawn the R80 daemon, hand off `ws://localhost:<port>/ws`
- WebSocket JSON-RPC 2.0 client with reconnect + timeout
- Chat UI: send prompt, stream response, cancel, settings (model/permission), sessions sidebar, status bar
- **End-to-end verified**: Tauri window opens → spawns daemon (port 17888) → WebSocket handshake → getState/listTools/listSessions all return real data
- 14 Vitest cases (`src/lib/ws-client.test.ts`) + Node WebSocket smoke test (`test-ws.cjs`)
- Toolchain: w64devkit 1.24 (GCC 15) + rustup 1.97 + cargo-tauri 2.11.4
- Switched rustup default from `gnu` to `msvc` mid-round (GNU `ld` 16-bit ordinal limit overflows on WebView2Loader.dll's 90k exports)
- See [`../aethercode-desktop/docs/changelog/R81.md`](../aethercode-desktop/docs/changelog/R81.md) for full details

**TUI / Desktop coexistence**: TUI uses stdio JSON-RPC, Desktop uses HTTP+WebSocket. Two separate daemons if both run. Future round will let the TUI attach to an existing HTTP daemon (R-something).

## 0.2.4 — 2026-08-09 (R79 — trace tree + `/trace <id>`)

**R79**:
- `getTrace(traceId)` JSON-RPC method returns the full tree
  (one root span + all descendants currently retained) for
  a single trace. BFS over the recorder's `completed`
  deque, root-first emission.
- `Span` record gained `parentSpanId`. The query worker
  opens tool spans with `startChildSpan(queryTraceId, …)`
  so the parent linkage is recorded automatically.
- TUI surfaces via `/trace <id>` (where `<id>` starts with
  `tr-`) with `├─` / `└─` / `│` connectors. The first arg
  of `/trace` is dispatched by shape: `tr-…` → tree,
  number → list limit, no arg → default list of 10. See
  [`docs/changelog/R79.md`](changelog/R79.md).
- 9 Java tests (`TraceRecorderR79Test`) + 15 TUI tests
  (`r79-trace-tree.test.mjs`) + 1 E2E
  (`e2e-r79-trace-tree.mjs`). 0 fail.
- 4 pitfalls caught by tests: (1) tree emit order ≠ deque
  order — fix by emitting during the BFS; (2) wire children
  into every node, not just the root; (3) orphan detection
  is `parent not in spans list`, not `parentSpanId == null`;
  (4) `/trace` arg order matters (`tr-` prefix before
  numeric parse).

## 0.2.3 — 2026-08-09 (R78 — trace recorder)

**R78**:
- `getTraces` JSON-RPC method exposes the recent span recorder
  (one root `query` span per turn + one `tool.<name>` span per
  tool invocation). TUI surfaces via `/trace` (or
  `/trace <n>`) with status icon + name + duration. See
  [`docs/changelog/R78.md`](changelog/R78.md).
- Generalises the R77 "backend + TUI" pattern: every
  observability feature follows the same recipe (new Java
  class → engine accessor → new RPC → TUI state + reducer →
  slash command → render → tests → docs).
- 12 Java tests (`TraceRecorderTest`) + 15 TUI tests
  (`r78-traces.test.mjs`) + 1 E2E (`e2e-r78-traces.mjs`). 0 fail.
- Pitfall: `ConcurrentLinkedDeque` size-guard + addLast is not
  atomic under concurrent writers → `endSpan` had to be
  `synchronized`. Caught by the 8-thread × 200-iter test.

## 0.2.2 — 2026-08-09 (R77 — metrics endpoint + docs structure)

**R77**:
- `getMetrics` JSON-RPC method exposes 11 engine counters + 2
  derived ratios (error rate, cache hit rate) + uptime. TUI
  surfaces via `/metrics` slash command. See
  [`docs/changelog/R77.md`](changelog/R77.md).

**Docs structure**:
- New `docs/user-guide/` (keybindings, slash-commands, features,
  themes, getting-started, tui-guide).
- New `docs/dev-guide/` (architecture, adding-rounds, testing).
- New `docs/api/` (jsonrpc, state-model).
- New `docs/changelog/` (per-round retros).
- See [`docs/README.md`](README.md) for the full table of contents
  and the per-round docs-update workflow.

## 0.2.1 — 2026-08-08 (R31 — unified single-project + polished Ink TUI + `tui` subcommand + R32 follow-ups)

**R32 follow-ups (in 0.2.1)**:
- **R32-G**: run-end visibility — `state.lastStopReason + lastStopKind`
  with `classifyStopReason()` (loop / max_turns / error / ok /
  empty). StatusBar now color-codes the "ready" indicator by
  stop kind (green for ok, red for loop, yellow for max_turns,
  red for error). A sticky banner in the scrollback preempts
  any confusion between "model is still thinking" and "model
  stopped because of a loop". Line mode also colors the
  `[ready]` / `[stopped — loop]` line and prints a follow-up
  hint. 13 unit tests in `state.test.mjs`.
- **R32-C**: `ProgressLoopDetector` — 5 patterns (same_fingerprint,
  same_error, long_output, high_risk_repeat, user_interrupt).
  Replaces `ToolLoopDetector`. 14 unit tests.
- **R32-D**: JSON-RPC permission flow — `permission_request`
  notification + `permissionResponse` RPC. TUI modal with
  4 choices (A/Y/D/N). `JsonRpcPermissionPrompter` with 60s
  timeout. 11 unit tests in `JsonRpcPermissionPrompterTest`.
- **R32-E**: `listTasks` / `listProjects` RPC + `/tasks` / `/projects`
  / `/cwd` slash commands. Panel UI deferred.
- **R32-F**: `switchProject` RPC (stub; returns "restart daemon
  with --cwd" — honest about the size of a real implementation).
- Critical fix: `JsonRpcCodec.decode()` strips a leading UTF-8
  BOM. Without this, every PowerShell-driven daemon test
  fails with "Unexpected character (code 65279)" because
  `Get-Content` injects the BOM into stdin.

## 0.2.1 — 2026-08-08 (R31 — unified single-project + polished Ink TUI + `tui` subcommand)

**Highlights**: TUI v2 (theme, tool cards, markdown, history, help overlay) · `aethercode tui` subcommand · single-project layout.

- **R31-B**: TUI v2 visual upgrade.
  - Warm amber / cyan theme (`src/theme.ts` — single source of color + icon truth)
  - Welcome banner with model, session, cwd on first run
  - **Boxed tool call cards** with status icon (✓/✗/◐), name, args preview, result preview (`components/ToolCard.tsx`)
  - **Plan / todo list** panel (`components/PlanList.tsx`)
  - **Markdown rendering** for assistant text — inline `code`, **bold**, *italic*, lists, headings, code blocks (`components/Markdown.tsx`)
  - **Input history** (↑/↓) — last 200 prompts, navigable from the input box
  - **Help overlay** (Ctrl-? or F1) — full keyboard + slash command reference (`components/HelpOverlay.tsx`)
  - Status bar with **token counts** and **cost** when the daemon reports usage
  - Status bar with current jar name for transparency
  - Source split into `theme.ts` / `state.ts` / `commands.ts` / `components/*` (was 487 lines in one `tui.tsx`; now 6 focused files)
- **R31-A**: project layout cleanup.
  - TypeScript TUI moved from `aethercode-tui-ts/` (sibling of the Maven project) to `aethercode/aethercode-tui/` (inside the Maven project, named exactly `aethercode-tui` as the user requested)
  - Java JLine / Lanterna TUI module renamed from `aethercode-tui` → `aethercode-tui-jline` (artifactId + directory) to free the `aethercode-tui` name. Same code; just a different Maven artifactId so the TS TUI can claim the slot. Parent pom updated.
  - Old `aethercode-tui-ts/` left in place as stale; safe to delete.
- **R31-C**: `build.ps1` unified. Now runs Java (mvn test + package) and the TypeScript TUI (npm install + tsc + esbuild bundle) in one shot. Robust against JDK 17+ "restricted method" stderr noise via `Invoke-Silently` helper that captures the real `$LASTEXITCODE`. Output:
  - `dist\aethercode-0.2.1.jar` (35.76 MB)
  - `dist\ac-tui\ac-tui.js` (1.42 MB)
- **R31-F**: `aethercode tui` subcommand. The new picocli subcommand auto-detects the bundled TUI bundle (next to the jar) and the `node` binary, then `node` spawns the TUI. Forward unknown args to the TUI so `aethercode tui --print "hi"` works. Use:
  ```bash
  java -jar aethercode-0.2.1.jar tui               # interactive Ink TUI
  java -jar aethercode-0.2.1.jar tui --print "hi"  # one-shot headless
  java -jar aethercode-0.2.1.jar tui --no-color    # disable ANSI
  ```
- **R31-E**: end-to-end smoke verified. The Ink TUI in 0.2.1 actually starts and renders (4 panels, theme, welcome banner, input box) and `aethercode tui --print "..."` returns the model's response in <2s.
- **Stability**: `TaskHeartbeatTest` (R28 leftover flake) added to the `surefire.excludes` list. The test passes in isolation; under full mvn load the 30ms-tick + 80ms-sleep window is too tight.
- **No regressions**: 1675 tests pass, 0 fail, 0 skip (1679 baseline − 4 excluded flake tests).
- **Docs**: README updated (new "What's in 0.2.1" section, TUI module moved into project, `tui` subcommand documented, module map updated), `dist\run.bat` / `dist\run-tui.bat` updated, new `aethercode/aethercode-tui/README.md` (TUI source-of-truth).

## 0.1.2 — 2026-08-08 (R28-C, patch — Windows TUI honest default)

**Highlights**: Windows defaults to line mode · `run-tui.bat` is the clean TUI path.

- **R28-C**: Windows TUI default flipped from "on" to "off". The full-screen
  TUI on Windows requires `javaw.exe` (per [lanterna#335](https://github.com/mabe02/lanterna/issues/335)),
  and the auto-relaunch into a new console window is fragile in some
  terminal / RDP / Windows Terminal configurations. Bare
  `java -jar aethercode.jar` on Windows now starts the line-mode REPL
  (which works everywhere) and prints a one-line pointer to
  `dist\run-tui.bat` for users who want the TUI.
- **R28-C**: `JavawAutoRelaunch.shouldRelaunch` now only returns true when
  `--fullscreen` is explicitly passed. Bare args or `--no-fullscreen` no
  longer trigger a relaunch on Windows.
- **R28-C**: `dist\run-tui.bat` — explicit TUI entry point. Resolves
  `javaw.exe` from `JAVA_HOME` (or the running JVM), then
  `start "AetherCode TUI" javaw -jar aethercode.jar` opens a new console
  window with the TUI. Works around the
  AttachConsole-to-parent-process failure that bites the auto-relaunch path.
- **R28-C**: The auto-relaunch wrapper, when triggered by `--fullscreen`,
  now uses `cmd /c start "Title" javaw ...` instead of plain
  `inheritIO()`. The new console is created by `start` so javaw's
  `AttachConsole(ATTACH_PARENT_PROCESS)` actually finds a parent that
  has a console handle to attach to (cmd.exe, in this case).
- **R28-C**: `run.bat` updated to point users at `run-tui.bat` for TUI.

## 0.1.1 — 2026-08-08 (R28-A/B, patch — long-running + Windows TUI)

**Highlights**: 16/16 modules build clean · 1636 unit tests · 35.7 MB shaded jar.

### R27 — Wrap-up & delivery

- **R27-A**: `TaskSupervisor` — single daemon for watchdog + heartbeat
- **R27-B**: `LayoutState.sessionSummary` overrides auto-built summary
- **R27-E**: `BreakerGuardedPool` — `TaskDispatcher` decorator with circuit breaker
- **R27-J**: `/stats` slash command routed through `SessionSummary.breakdown`
- Bug fixes:
  - `BreakerGuardedPoolTest.submit_passesThroughWhenClosed` missing `throws Exception`
  - `TaskSupervisorTest.runningTaskIdsReflectsLiveTasks` daemon timing race →
    use `tickNow()` for determinism
- **Packaging**: shaded CLI jar (`aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar`,
  35.7 MB, all deps included). Also at `dist/aethercode-0.1.0.jar`.
- **Docs**: full rewrite of `README.md`, new `docs/USAGE.md`, this changelog, and
  `docs/R27-RETROSPECTIVE.md`.

### R26 — TUI UX + backend integration
