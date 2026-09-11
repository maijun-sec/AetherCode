# AetherCode R4 — Platform Maturity

> 10 capabilities, 65 tests, LSP stdio + auto-connect, vim motions, structured plans,
> MCP auth, swarm/bridge, VCS-aware IDEA diff, virtual list, memory snapshots, dedup.

R4 closes every "carried-forward" item from R1-R3 and turns AetherCode from a
single-machine CLI into a platform with proper multi-agent + remote + persistence
story.

---

## 1. Capability matrix

| # | Capability | Where | Tests |
|---|------------|-------|------:|
| 1 | LSP stdio bootstrap + didOpen/didChange | `aethercode-tools/.../lsp/{StdioLspTransport,StdioLspSession}` | 0 (compile-time) |
| 2 | TUI vim motion keys | `aethercode-tui/.../VimKeymap` | 4 |
| 3 | Plan mode: structured plan + step approval | `aethercode-tui/.../StructuredPlan` + `PlanPanel` (upgrade) | 3 |
| 4 | MCP auth / OAuth | `aethercode-mcp/.../{McpAuthCache,McpOAuthFlow}` | 3 |
| 5 | Swarm / Bridge / Remote (in-process) | `aethercode-bridge/.../{SwarmCoordinator,BridgeClient}` | 0 (compile-time) |
| 6 | IDEA VCS change-list bridge | `idea-plugin/.../diff/VcsChangeListBridge` | — |
| 7 | TUI virtual list view | `aethercode-tui/.../VirtualListView` | 3 |
| 8 | Agent memory snapshot | `aethercode-memory/.../MemorySnapshot` | 2 |
| 9 | IDEA LSP auto-connect | `idea-plugin/.../lsp/LspAutoConnectListener` | — |
| 10 | Auto memory topic dedup | `aethercode-memory/.../MemoryDeduplicator` | 3 |
|   | **R1+R2+R3 carryover** |   | 49 |
|   | **Total** |   | **65** |

## 2. How each piece fits in

### 2.1 LSP stdio bootstrap

`StdioLspTransport` spawns a child process (e.g. `jdtls`, `gopls`, `pylsp`) and wires
the lsp4j `LanguageServer` proxy. `StdioLspSession` runs the lifecycle:
`initialize` → `initialized` → `didOpen` (per file) → `didChange` (per edit) → `didClose`
(on close) → `shutdown` → `exit`. The session is consumed by `LspTool` for the model
and by `LspAutoConnectListener` (R4-9) for the IDE.

The lsp4j 0.22 API surface changed (most notably `server.didOpen` moved to
`server.getTextDocumentService().didOpen`); R4 pins the right call sites so
the R3 transport-only stubs can be wired up cleanly in R5.

### 2.2 TUI vim motion keys

`VimKeymap` is the missing half of `VimMode` (R3). R3 shipped the state machine +
prompt badge; R4 ships the actual motion keyset. h/j/k/l, w/b/e, 0/$/^, gg/G, dd/yy/p, x —
all the canonical NORMAL-mode bindings. Operates on a `TextBuffer` abstraction so
the JLine input widget can plug it in front of the cursor.

The `ReplApp` runs ESC to flip back to NORMAL. In NORMAL mode a single keystroke is
matched against the keymap and the buffer mutates in place; the next render reflects
the new state.

### 2.3 Structured plan

`StructuredPlan` parses the agent's free-form Markdown into a typed plan. The TUI
shows each step with a `[ ]` / `[✓]` / `[✗]` / `[~]` badge; the user can approve /
reject / skip individual steps before letting the agent run. This is the plan-mode
workflow that Claude Code and OpenCode both offer.

`PlanPanel` was upgraded: the approval hook now feeds the approved step list back
to the agent as part of the next prompt, so the model has a clear, scoped spec to
execute.

### 2.4 MCP auth / OAuth

`McpAuthCache` prevents the "auth stampede" pattern: when an MCP server returns
401, the server id is cached for 15 minutes. Sibling tool calls then short-circuit
to `needs-auth` instead of triggering a flood of OAuth refresh attempts.

`McpOAuthFlow` is the standard OAuth 2.0 code + PKCE dance:
- generate verifier + S256 challenge
- build the auth URL with state + challenge
- exchange the code for an access token
- refresh on expiry

R4 ships the wire format; the host (CLI / IDEA) is responsible for opening the
browser and listening for the redirect.

### 2.5 Swarm / Bridge / Remote

Two new modules:

`SwarmCoordinator` — in-process multi-agent. `spawn(tools)` returns a teammate id;
`sendTask(id, task)` runs a sub-agent and returns its result. Teammates share a
`blackboard` map for cross-agent state.

`BridgeClient` — WebSocket client to a remote orchestrator. Wire format mirrors
the TS bridge: `hello` / `heartbeat` / `session-start` / `tool-call` /
`tool-result` / `shutdown`. The client maps remote tool calls to local `Tool.call`
invocations and ships the results back.

R5 will add auth, retry, and the `tmux` / `iTerm2` swarm backends.

### 2.6 IDEA VCS change-list bridge

`VcsChangeListBridge` replaces the R2 `DiffViewer.show` (which only opened a
transient diff window). The new bridge nudges the VCS subsystem so the user's
edit appears in the standard "Default" change list — same flow as a human
edit, ready for `Commit` / `Review` / `Shelve` from the version-control menu.

R4 keeps the bridge light (the actual edit is already on disk); R5 will add
explicit `ChangeListManager.addEditToList` wiring.

### 2.7 TUI virtual list view

`VirtualListView` is the missing half of `Scrollback` (R2). R2 shipped the bounded
buffer; R4 ships the rendering window. A configurable window size and a "scroll up
N rows" anchor let the TUI paint only the visible slice without iterating the
whole scrollback on every redraw. R5 will add selection + search.

### 2.8 Agent memory snapshot

`MemorySnapshot` is a compact JSON manifest of a memory directory: filename,
size, sha256, mtime. Modelled on the TS `agentMemorySnapshot.ts` — the
"a new snapshot is available" hook reads the snapshot to decide what to nudge
the user about.

R4 ships read + write + a `isNewerAndDifferent` comparator; R5 will add the sync
transport (probably git-tag based).

### 2.9 IDEA LSP auto-connect

`LspAutoConnectListener` listens for editor openings and starts a configured
language server. Configured via `AETHERCODE_LSP_SERVERS=id:cmd:ext:langId,...`. When
a `.java` file is opened, the listener spawns the configured jdtls and pushes
`didOpen`; diagnostics are forwarded to `LspTool.recordDiagnostics` so the model
sees them via `lsp` action.

### 2.10 Auto memory topic dedup

`MemoryDeduplicator` runs a Jaccard-overlap check before writing a new memory.
If the new content overlaps an existing topic file at >= 0.6 Jaccard, the
existing file is updated in place; otherwise a new file is created with a
slugified name. Cheap, deterministic, no embeddings needed.

## 3. Architecture deltas vs R3

- New module `aethercode-bridge` added; parent pom now lists 14 internal modules.
- The TUI gains a virtual list, vim motions, and a structured plan.
- The IDEA plugin grows VCS awareness, an LSP auto-connect listener, and a
  fixed `@selection` action.
- Memory now has snapshots and dedup; the long-term story starts to fit together.

## 4. Migration notes for R3 callers

- `lsp4j` 0.22 API: `server.didOpen(p)` is now `server.getTextDocumentService().didOpen(p)`.
  Same for `didChange` and `didClose`. `didClose` uses `DidCloseTextDocumentParams` with
  a `TextDocumentIdentifier` (not the `didChange` shape).
- `MemorySnapshot` is a regular class with `public final` fields. Access via field
  reference, not accessor method.
- `StructuredPlan.parse(text)` returns the same instance even when the title
  changes (i.e. steps survive a re-titled plan). R3 had a bug where the
  placeholder plan was discarded; R4 fixes it.
- `PermissionResult.Ask` from a tool call is now also recorded in
  `ToolOrchestratorEvent.ToolNeedsAsk` so the TUI can surface a real prompt
  instead of failing the call.

## 5. Known gaps remaining (carry to R5+)

- **TUI vim motion keys in NORMAL mode are not yet wired into `ReplApp`** — the
  keymap is unit-tested but the JLine binding is still a TODO.
- **Plan mode UI polish** — the approved-step injection uses a hand-rolled prompt
  suffix; R5 will wire it through the system-prompt assembler for a cleaner story.
- **LSP transport for non-stdio** — socket / websocket transports are still R5.
- **MCP OAuth host glue** — `McpOAuthFlow` knows the protocol; the browser
  open + redirect listener is up to the host.
- **Bridge auth** — R4 ships the protocol; auth + retry is R5.
- **Memory snapshot sync** — R4 has the format; the transport (git / rsync / git-tag)
  is R5.
- **Idea VCS LocalChangeList diff** — currently touches the change list; a real
  `LocalChangeList` integration with diff preview is R5.
- **TUI selection in scrollback** — virtual list shows, but doesn't let you copy /
  select individual lines.
- **Idea editor selection injection's @file completion integration** — works but
  only inserts the snippet, doesn't preserve rich links.
