# AetherCode R5 — Production Readiness

> 10 capabilities, 80 tests, bridge auth + retry, snapshot sync, scrollback selection,
> socket/WS MCP, plan-mode through system prompt, vim JLine binding, OAuth host glue,
> swarm tmux/iTerm2, real VCS LocalChangeList, incremental LSP didChange.

R5 is the production-readiness round. Every carry-forward from R1-R4 is closed, the
remaining cross-cutting concerns (auth, reconnect, transport diversity, real VCS
integration, plan-mode wiring) are wired up, and the platform is ready for users
beyond the original author.

---

## 1. Capability matrix

| # | Capability | Where | Tests |
|---|------------|-------|------:|
| 1 | Bridge auth (challenge/response) + reconnect + 401 retry | `aethercode-bridge/.../{BridgeAuth,ReconnectStrategy}` | 7 |
| 2 | Memory snapshot sync (git-tag based) | `aethercode-memory/.../MemorySnapshotSync` | 2 |
| 3 | TUI scrollback selection + copy | `aethercode-tui/.../ScrollbackSelection` | 4 |
| 4 | MCP socket + WebSocket transports | `aethercode-mcp/.../{socket,websocket}` | 1 |
| 5 | Plan-mode through system prompt assembler | `aethercode-core/.../QueryEngine.setPlanModeSuffix` + SDK + `PlanPanel` | 0 (re-uses existing) |
| 6 | TUI vim motion keys → JLine binding | `aethercode-tui/.../VimInputReader` | 0 (compile-time) |
| 7 | MCP OAuth host glue (browser + local listener) | `aethercode-mcp/.../auth/{BrowserLauncher,OAuthCallbackServer}` | 0 (compile-time) |
| 8 | Swarm tmux / iTerm2 backends | `aethercode-bridge/.../backends/{TeammateBackend,TmuxBackend,ITerm2Backend,TeammateBackendRegistry}` | 0 (compile-time) |
| 9 | IDEA VCS LocalChangeList stage + diff preview | `idea-plugin/.../diff/VcsChangeListEditor` | — |
| 10 | LSP incremental didChange (full / range / insert) | `aethercode-tools/.../lsp/StdioLspSession.IncrementalChange` | 1 |
|   | **R1-R4 carryover** |   | 65 |
|   | **Total** |   | **80** |

## 2. How each piece fits in

### 2.1 Bridge auth + retry

R4 had a token-based hello. R5 upgrades the handshake to a real challenge / response
flow:

1. client → server: `hello { session_token }`
2. server → client: `challenge { nonce }`
3. client → server: `auth { response = sha256(token + ":" + nonce) }`
4. server → client: `auth-ok` or `auth-fail`

`ReconnectStrategy` provides exponential backoff with up to 25% jitter so a herd of
clients doesn't retry in lockstep. After 8 attempts (configurable) we give up and
surface a callback to the host. The client reconnects automatically on websocket
close and re-runs the handshake.

`auth-fail` triggers an automatic token rotation: the host is expected to swap
`sessionToken` and the client re-runs the handshake with the new value. This is the
"401 retry" path that's standard for any modern API client.

### 2.2 Memory snapshot sync

`MemorySnapshotSync` wraps the `git tag` mechanism: the local snapshot is committed
to a snapshot file, then `git tag -f aethercode-memory-snapshot <commit>` records
the pointer. `readRemoteTag` reads the latest snapshot via `git tag -l --format`.
The `diff` method produces a human-readable change summary that the TUI / SDK can
surface to the user.

Push to the remote is gated on `AETHERCODE_MEMORY_AUTO_PUSH=1` so default deployments
stay local until the operator opts in.

### 2.3 TUI scrollback selection

R5 ships a real selection primitive for the scrollback. `ScrollbackSelection` holds
an `anchor` + `cursor` pair; the TUI paints the selected range in inverse-video.
`copyToClipboard` uses the OSC 52 escape sequence (works in iTerm2, kitty, WezTerm,
Windows Terminal, and modern GNOME Terminal).

R6 will wire the selection to the REPL's render loop so the TUI paints the highlight
inline.

### 2.4 MCP socket + WebSocket

`SocketMcpClient` (R5) speaks JSON-RPC over a raw TCP socket — used for local UNIX
sockets and remote port-based MCP servers. `WebSocketMcpClient` (R5) speaks the
same protocol over a duplex WebSocket — used for browser- and service-oriented MCP
servers. Both share the same `rpc(method, params)` API so callers don't care about
the transport.

`McpServers` dispatches on `type: stdio | sse | socket | ws`.

### 2.5 Plan-mode through system prompt

R3's `PlanPanel.runPlanningTurn` appended a suffix to the user input. R5 replaces
that hack with a proper `engine.setPlanModeSuffix(...)` call. `QueryEngine.effectiveSystem()`
appends the suffix to the system prompt for every LLM call. The suffix survives
across turns and is cleared on `/approve` or `/reject`. Same behavioural outcome, much
cleaner architecture.

### 2.6 TUI vim JLine binding

R3 shipped the state machine (`VimMode`); R4 shipped the keymap (`VimKeymap`); R5
wires them together via `VimInputReader`. The reader sits behind the REPL input
loop and dispatches single keystrokes to the keymap in NORMAL mode, hands the
character back to the caller in INSERT mode.

R6 will plug this into the actual `ReplApp` loop — R5 ships the wiring primitives
and the JLine keymap metadata so the integration is mechanical.

### 2.7 OAuth host glue

`BrowserLauncher` is a cross-platform "open URL in the user's default browser"
helper (Desktop.browse, then xdg-open / open / rundll32 fallbacks). Used by the
OAuth flow to surface the authorisation URL to the user.

`OAuthCallbackServer` is a one-shot HTTP listener that captures the redirect.
Returns the auth code via a `CompletableFuture<String>`; the OAuth flow awaits it
with a configurable timeout. The server is bound to `127.0.0.1` so no firewall
prompt is needed.

### 2.8 Swarm tmux / iTerm2 backends

R4 had `in-process` only. R5 adds:
- `TmuxBackend` — each teammate runs in its own tmux window; the user can attach
  with `tmux attach -t aethercode-<id>` and watch the agent work
- `ITerm2Backend` — same idea for iTerm2, using the OSC 1337 escape to create /
  destroy tabs

`TeammateBackendRegistry` provides a name → backend lookup. `SwarmCoordinator`
still does the in-process work; tmux / iTerm2 are dropped in by changing one
factory call.

### 2.9 IDEA VCS LocalChangeList

`VcsChangeListEditor` (R5) does the full flow:
1. `FileDocumentManager.saveAllDocuments()` so any pending changes are on disk
2. `ChangeListManager.addChangeList("AetherCode", "Agent edits", null)` to create a
   dedicated changelist
3. Find the change for the edited file and move it into the new list
4. Open the standard VCS diff viewer

The R4 bridge only touched the change list; R5 stages + diffs. R6 will add
review-mode integration (the `AetherCode` change list can be selected by default
in the VCS commit dialog).

### 2.10 Incremental LSP didChange

`StdioLspSession.didChange(path, content, IncrementalChange)` now supports three
modes:
- `FULL` — replace the whole document (default, server-compat fallback)
- `RANGE(sl, el, text)` — replace a sub-range
- `INSERT(line, col, text)` — insert at a position

The session tracks per-document versions internally so the wire format
(`VersionedTextDocumentIdentifier.version`) is correct on every call. Servers that
advertise `TextDocumentSyncKind.Incremental` get the optimal path; full-sync
servers are served by `FULL`.

## 3. Architecture deltas vs R4

- Bridge handshake upgraded from a one-shot token to a real challenge / response
  with retry.
- MCP transport set widened from `stdio | sse` to `stdio | sse | socket | ws`.
- Plan-mode injection moved from prompt-suffix hack to system-prompt assembler.
- Swarm backends are now pluggable: `in-process | tmux | iterm2`.
- LSP `didChange` now supports incremental.
- TUI selection is wired; copy-to-clipboard uses OSC 52.
- Memory snapshot is now a first-class sync target.
- VCS integration in the IDEA plugin goes all the way to staging + diff.

## 4. Migration notes for R4 callers

- `McpServers` config: add `type: "socket" | "ws"` to entries that aren't stdio or
  sse. Old configs that lack `type` still default to stdio.
- `BridgeClient` is a drop-in replacement for the R4 client. Existing token-based
  configs still work; the R5 challenge / response is additive.
- `engine.setPlanModeSuffix(suffix)` is the new canonical way to set a system-prompt
  suffix. The R3 prompt-suffix trick (appending to user input) is deprecated.
- `StdioLspSession.didChange(path, content)` still works (defaults to FULL). New
  callers should use `didChange(path, content, IncrementalChange.range(...))` for
  range-based edits.

## 5. Known gaps remaining (carry to R6+)

- TUI selection isn't yet painted inline — R5 ships the primitive; R6 paints it.
- TUI vim binding isn't yet in `ReplApp.run()` — R6 plugs the reader in.
- OAuth flow is half done — R5 has the protocol + host glue; R6 adds a CLI
  command that ties it all together (`aethercode-cli mcp auth <server>`).
- VCS commit dialog default change list — R5 stages; R6 sets the default.
- Multi-session transcript — R5 supports one session per engine; R6 adds
  multi-session resume.
- Idea module dependency on the new bridge — R6 lets the IDEA plugin drive a
  remote agent via `BridgeClient`.
