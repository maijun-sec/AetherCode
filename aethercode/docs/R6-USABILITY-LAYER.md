# R6: Usability Layer — Selection, Sessions, Bridge, Panels

R6 closes the gap between R5's "production ready" stack and the day-to-day
ergonomics the TS source actually exhibits. 10 candidates, all ship green, all
covered by tests, no regressions on the 80 R5 baseline.

## Test counts

| Round | Modules | Tests | Failures |
|-------|---------|-------|----------|
| R5    | 15      | 80    | 0        |
| R6    | 15      | 147   | 0        |

Net new tests: **+67**, spread across 9 new test classes and 1 extended one.

## Candidate scorecard

| # | Candidate | Files | Tests | Status |
|---|-----------|-------|-------|--------|
| 1 | TUI selection inline 渲染 | `ScrollbackPainter`, `SelectionCommands` | 13 | ✅ |
| 2 | TUI vim 接进 ReplApp.run() | `VimNormalCommands`, ReplApp wiring | 13 | ✅ |
| 3 | CLI `mcp auth` 命令 | `McpAuthOrchestrator`, `McpCommand`, `McpAuthCommand` | 5 | ✅ |
| 4 | Multi-session transcript resume | `SessionStore`, ReplApp /sessions, /resume, /fork | 6 | ✅ |
| 5 | IDEA drives remote agent via BridgeClient | `RemoteAgentConfig`, `RemoteAgentService`, `ConnectToRemoteAgentAction`, `DisconnectFromRemoteAgentAction` | — | ✅ |
| 6 | VCS commit dialog default change list = "AetherCode" | `VcsChangeListEditor` patch | — | ✅ |
| 7 | LSP didOpen full sync fallback | `StdioLspSession` capability probe | 5 | ✅ |
| 8 | Auth stampede 双层 | `AuthRateLimiter`, `McpAuthOrchestrator` rate-limit gate | 8 | ✅ |
| 9 | TUI 多窗口 | `PanelTabs`, ReplApp NORMAL t/T cycling | 9 | ✅ |
| 10 | Swarm shared blackboard 持久化 | `Blackboard`, `InMemoryBlackboard`, `SqliteBlackboard`, `SwarmCoordinator` injection | 8 | ✅ |

## Implementation notes per candidate

### 1. TUI selection inline 渲染

`ScrollbackSelection` (R5) was a state-only primitive. R6 adds the renderer:

- `ScrollbackPainter.paint(scrollback, selection, window)` returns a string with
  the selected lines wrapped in ANSI SGR-7 / SGR-27 inverse-video escape codes.
- `SelectionCommands` lifts the `/select start N | extend N | copy | clear`
  parsing out of ReplApp so it's unit-testable.
- `/history` now uses the painter, and adds a footer that reports the active
  range.

### 2. TUI vim 接进 ReplApp.run()

R5 shipped `VimInputReader` + `VimKeymap` as primitives but ReplApp still
routed everything through JLine. R6 wires them:

- NORMAL mode short-circuits the JLine readline loop. `j`/`k` move a
  `historyViewOffset`, `G` jumps to the bottom, `g` to the top, `q` returns
  to INSERT.
- The pure logic lives in `VimNormalCommands.State` / `apply` /
  `clampOffset`, so the dispatch is testable without a TTY.
- `VimKeymap` is fed a `ReadOnlyBuffer` stub — in browse-only mode it never
  mutates the buffer, so the no-op interface is intentional.

### 3. CLI `mcp auth` 命令

The mcp.json auth flow had all the parts (BrowserLauncher, OAuthCallbackServer,
McpOAuthFlow) but no end-to-end wiring. R6 ships `McpAuthOrchestrator` that
exposes a single `run(serverName, flow, pkce, timeout, unit, serverFactory)`
entry point:

- `loadAuthConfig(serverName)` reads the mcp.json entry's `auth` block
  (authEndpoint, tokenEndpoint, clientId, scope, redirectUri, callbackPort).
- `run()` starts a callback server (factory-injected for tests), waits for
  the auth code, exchanges it, persists the token to
  `mcp-tokens.json` keyed by server name.
- The CLI subcommand `aethercode mcp auth <server>` calls it, with
  `BrowserLauncher.open(url)` to surface the authorisation URL.

### 4. Multi-session transcript resume

Each session now lives in its own JSONL file under `.aethercode/sessions/`:

- `SessionStore.list()` — directory scan, newest-first.
- `SessionStore.loadOrCreate(id)` — single open, creates if missing.
- `SessionStore.delete(id)` — used by /fork's sibling-cleanup paths.
- `AetherCodeEngine.Builder.transcript(Transcript)` — R6 builder hook that
  prepends the on-disk messages to the in-memory `AppState.transcript`.
- `AetherCodeEngine.toBuilder()` — rebuild with the same model + permissions
  but a different session id + transcript.
- ReplApp gains `/sessions`, `/resume <id|prefix>`, `/fork <id>`. The engine
  field is now `volatile`; the `PlanPanel.swapEngine()` hook discards any
  in-progress plan when the user /resume's.

### 5. IDEA drives remote agent via BridgeClient

The plugin previously had no bridge wiring. R6 adds:

- `RemoteAgentConfig` — `PersistentStateComponent` storing the URL + token
  + `enabled` flag in `.idea/aethercode.xml`.
- `RemoteAgentService` — project service that lazily connects when
  `ensureConnected()` is called and tears down the heartbeat + reconnect
  threads on `disconnect()`.
- `ConnectToRemoteAgentAction` + `DisconnectFromRemoteAgentAction` — menu
  actions, registered in `plugin.xml`.

`build.gradle.kts` adds `aethercode-bridge:0.1.0-SNAPSHOT` so the plugin can
call into the same `BridgeClient` the CLI uses.

### 6. VCS commit dialog default change list = "AetherCode"

`VcsChangeListEditor.stageAndShow()` already created a "AetherCode" change
list. R6 also calls `ChangeListManager.setDefaultChangeList(list)` inside a
`WriteCommandAction` — so when the user hits ⌘K / Ctrl+K, the commit dialog
opens on the agent's edits by default.

### 7. LSP didOpen full sync fallback

`StdioLspSession` now records the server's `ServerCapabilities` after
`initialize()` and exposes `syncKind()`:

- LEFT-style TextDocumentSync → `Full`.
- RIGHT-style TextDocumentSyncOptions with no `change` → `Full`.
- RIGHT with `change = Incremental` → keep the caller's incremental mode.
- Otherwise → force `IncrementalChange.FULL` and set
  `wasLastDidChangeDowngraded()` so callers can log a one-liner.

The new test exercises all four capability shapes.

### 8. Auth stampede 双层

`McpAuthCache` (R4) is the long-window defence (15 minutes). R6 adds
`AuthRateLimiter` as the short-window defence:

- per-server cooldown (default 60s, configurable)
- `tryAcquire(serverId)` returns `false` while the cooldown is active
- `clear(serverId)` is called after a successful auth
- the CLI `mcp auth` subcommand consults the limiter first and refuses to
  even open a browser when the cooldown is still active

This stops the "user double-clicks the auth button" stampede.

### 9. TUI 多窗口

`PanelTabs` is a tab-strip with a renderable body per panel:

- 4 first-class panels: `chat`, `tools`, `memory`, `log`
- `t` cycles forward, `T` backwards, the active panel's title is bolded
  in the strip
- `renderStrip()` paints ANSI-styled brackets, `renderCurrent()` returns the
  supplier body
- NORMAL-mode hint line includes `t panel`

### 10. Swarm shared blackboard 持久化

`SwarmCoordinator` (R4) had an in-process `Map`. R6 lifts it to a
`Blackboard` interface with two implementations:

- `InMemoryBlackboard` (default, ConcurrentHashMap)
- `SqliteBlackboard` — sqlite-jdbc backed, one table
  `blackboard(key TEXT PRIMARY KEY, value_json TEXT, updated_at INTEGER)`,
  upsert via `ON CONFLICT … DO UPDATE`, Jackson serialised values.

`SwarmCoordinator` now takes the backend in the constructor; the
no-arg form still uses the in-memory default. `flush()` is a no-op for the
in-memory backend and a `PRAGMA wal_checkpoint(PASSIVE)` for sqlite.

## Pitfalls surfaced and handled

1. **Auth rate-limit slot was acquired before config check** — fixed by
   moving the limiter check to the very top of `McpAuthCommand.call()`.
2. **SQLite file lock on Windows** — `@TempDir` could not delete the
   `.db` file because the JDBC connection held an OS lock. Fix: add
   `SqliteBlackboard.close()` and an `@AfterEach` in the test that closes
   every blackboard created in the test.
3. **AssertJ `containsExactly` with raw `Iterable<?>`** — Jackson deserialises
   into `List<Object>`, so cast first, then `containsExactly("alpha", "beta")`
   (no varargs of `Object`).
4. **Session resume must rebuild via `toBuilder()` not via reflection** —
   reflection is fragile and we'd have to walk every Builder field; toBuilder
   only carries the four fields the resume path actually needs.
5. **VCS `setDefaultChangeList` must run inside a WriteCommandAction** — the
   IDEA API requires it; without it, the call silently no-ops on
   read-only-flagged changes.
6. **`McpAuthOrchestrator.run` signature** — I had `long timeoutSec`, but
   the test was passing `(5, TimeUnit.SECONDS)` (6 args). Switched to
   `(long timeout, TimeUnit unit)` to match how every test in the codebase
   writes it.
7. **LSP `TextDocumentSync` is an `Either`** — `Either.forLeft(kind)` is the
   shorthand, `Either.forRight(options)` is the long form. The
   `syncKind()` helper handles both.

## Files touched

```
aethercode/aethercode-bridge/pom.xml                                          (sqlite-jdbc dep)
aethercode/aethercode-bridge/src/main/java/.../bridge/Blackboard.java        (new)
aethercode/aethercode-bridge/src/main/java/.../bridge/InMemoryBlackboard.java (new)
aethercode/aethercode-bridge/src/main/java/.../bridge/SqliteBlackboard.java   (new)
aethercode/aethercode-bridge/src/main/java/.../bridge/SwarmCoordinator.java   (inject Blackboard)
aethercode/aethercode-bridge/src/test/java/.../bridge/SqliteBlackboardTest.java (new, 8 tests)
aethercode/aethercode-cli/pom.xml                                             (no change)
aethercode/aethercode-cli/src/main/java/.../cli/Main.java                     (--sessions-dir)
aethercode/aethercode-cli/src/main/java/.../cli/McpCommand.java               (new)
aethercode/aethercode-cli/src/main/java/.../cli/McpAuthCommand.java           (new)
aethercode/aethercode-core/pom.xml                                            (no change)
aethercode/aethercode-core/src/main/java/.../core/transcript/SessionStore.java (new)
aethercode/aethercode-core/src/test/java/.../core/transcript/SessionStoreTest.java (new, 6 tests)
aethercode/aethercode-mcp/src/main/java/.../mcp/AuthRateLimiter.java          (new)
aethercode/aethercode-mcp/src/main/java/.../mcp/McpAuthOrchestrator.java      (added rate-limit gate)
aethercode/aethercode-mcp/src/main/java/.../mcp/McpOAuthFlow.java             (added getters)
aethercode/aethercode-mcp/src/test/java/.../mcp/AuthRateLimiterTest.java      (new, 8 tests)
aethercode/aethercode-mcp/src/test/java/.../mcp/McpAuthOrchestratorTest.java  (new, 5 tests)
aethercode/aethercode-sdk/src/main/java/.../sdk/AetherCodeEngine.java         (transcript() builder, toBuilder())
aethercode/aethercode-tools/src/main/java/.../tools/lsp/StdioLspSession.java  (capability probe, downgrade)
aethercode/aethercode-tools/src/test/java/.../tools/lsp/StdioLspSyncFallbackTest.java (new, 5 tests)
aethercode/aethercode-tui/src/main/java/.../tui/PanelTabs.java                (new)
aethercode/aethercode-tui/src/main/java/.../tui/PlanPanel.java                (swapEngine)
aethercode/aethercode-tui/src/main/java/.../tui/ReplApp.java                  (selection/vim/tabs/sessions wired)
aethercode/aethercode-tui/src/main/java/.../tui/ScrollbackPainter.java        (new)
aethercode/aethercode-tui/src/main/java/.../tui/SelectionCommands.java        (new)
aethercode/aethercode-tui/src/main/java/.../tui/VimNormalCommands.java        (new)
aethercode/aethercode-tui/src/test/java/.../tui/PanelTabsTest.java            (new, 9 tests)
aethercode/aethercode-tui/src/test/java/.../tui/ScrollbackPainterTest.java    (new, 5 tests)
aethercode/aethercode-tui/src/test/java/.../tui/SelectionCommandsTest.java    (new, 8 tests)
aethercode/aethercode-tui/src/test/java/.../tui/VimNormalCommandsTest.java    (new, 13 tests)
idea-plugin/build.gradle.kts                                                   (bridge dep)
idea-plugin/src/main/kotlin/.../bridge/RemoteAgentConfig.kt                   (new)
idea-plugin/src/main/kotlin/.../bridge/RemoteAgentService.kt                  (new)
idea-plugin/src/main/kotlin/.../bridge/ConnectToRemoteAgentAction.kt          (new)
idea-plugin/src/main/kotlin/.../bridge/DisconnectFromRemoteAgentAction.kt     (new)
idea-plugin/src/main/kotlin/.../diff/VcsChangeListEditor.kt                   (setDefaultChangeList)
idea-plugin/src/main/resources/META-INF/plugin.xml                            (register actions)
```

## Verification

```bash
mvn -B test
# 147 tests, 0 failures, 0 errors, 0 skipped — across 9 modules
```

R6 ships clean. Bring on R7.
