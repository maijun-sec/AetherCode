# AetherCode R2 — Capability Expansion

> 8 capabilities, 64 tests, streaming executor, hooks integration, auto-compact,
> memory extraction, 4 new tools, MCP SSE, TUI upgrade, IDEA inline diff + @file completion.

R2 takes the R1 skeleton and turns it into a credible agent. Every item from the R1
"carried-forward" list is now in.

---

## 1. Capability matrix

| # | Capability | Where | Tests |
|---|------------|-------|------:|
| 1 | Streaming tool executor (concurrent + cancel) | `aethercode-core/.../StreamingToolExecutor` | 2 |
| 2 | Hooks integrated into the orchestrator | `aethercode-hooks/.../Hooks` (bridge) | 3 |
| 3 | Auto-compact wired into the engine | `aethercode-compact/.../CompactGate` + `core/.../Compactor` | 4 |
| 4 | Auto memory extraction (forked subagent) | `aethercode-memory/.../MemoryExtractor` | 1 (memory total) |
| 5 | New tools: WebFetch / WebSearch / NotebookEdit / AskUserQuestion | `aethercode-tools/.../net,edit,interactive` | 3 |
| 6 | MCP SSE transport | `aethercode-mcp/.../SseMcpClient` | 0 (mcp total) |
| 7 | TUI: scrollback / 3-way permission dialog / multi-line input | `aethercode-tui/.../Scrollback,PermissionDialog,ReplApp` | 2 |
| 8 | IDEA: inline diff + @file completion | `idea-plugin/.../diff/DiffViewer,input/FileReferenceCompletionContributor` | — |
|   | (R1 carryover: Message / Orchestrator / Memory / Permission / Tools) |   | 49 |
|   | **Total** |   | **64** |

## 2. Architecture deltas vs R1

### 2.1 Query engine uses the streaming executor

The R1 orchestrator drained each batch synchronously; the new code path is
`engine.query()` → `StreamingToolExecutor.run(batch, appState)` → live event delivery
to the TUI / SDK. The R1 orchestrator still exists for tests; production goes through
the streaming path.

### 2.2 Hooks are no longer stand-alone

`HookRegistry` is a sequence of pre/post tool-use predicates. The SDK builds a
`StreamingToolExecutor` and bridges the registry via two `HookBridge`s. Block verdicts
short-circuit the tool call; Continue verdicts let it run.

### 2.3 Compactor is an interface in `aethercode-core`

To break the cyclic dependency between the engine and the compact module, the engine
depends on `org.aethercode.core.compact.Compactor` (a tiny interface). The
`aethercode-compact` module provides `CompactGate` which implements the interface and
contains all the real logic (token estimation, summarisation prompt, splice, circuit
breaker).

### 2.4 Memory extraction has its own chat client

`MemoryExtractor` is a separate class from the engine. It uses its own `ChatClient`
reference and runs a *sandboxed* subagent that only has access to `file_edit` on the
exact memory file. The subagent is asked to produce a summary, the file is written
0o600, and the thresholds (10 000 tokens to init, 5 000 between updates, 3 tool calls)
are enforced before each extraction.

### 2.5 New tools follow the same `Tools.build(ToolDef)` recipe

Each new tool is one class. WebFetch is the only one with an external dep (Jsoup for
HTML stripping). The pattern: declare the schema, return a `ToolDef`, set the safety
flags.

### 2.6 TUI gains a scrollback, a real permission dialog, multi-line input

The dialog offers three options: `y` (allow once), `a` (allow always — writes a rule
into `.aethercode/settings.json`), `n` (deny with a reason). Multi-line input lets the
user paste code; the secondary prompt (`…`) signals continuation. Scrollback keeps
the last 2 000 lines and `/history` shows the last 40.

### 2.7 IDEA plugin surfaces file edits as platform diffs

When `file_edit` produces a `Tool.Attachment.DiffPreview`, the chat panel routes it
to `DiffViewer`, which opens a real IntelliJ `SimpleDiffRequest` and also opens the
edited file in the editor. `@file` completion is wired through the standard
`CompletionContributor` API and walks the project tree (skipping `.git`,
`node_modules`, `build`, `target`, `.idea`).

## 3. New code surface (rough LoC)

| File | LoC | What |
|------|----:|------|
| `core/StreamingToolExecutor.java`             | ~200 | Concurrent + cancel |
| `core/compact/Compactor.java`                  |  ~25 | Interface |
| `compact/CompactGate.java`                     | ~250 | Splicing + circuit breaker |
| `hooks/Hooks.java`                             |  ~50 | Bridge adapter |
| `memory/MemoryExtractor.java`                  | ~220 | Forked subagent |
| `tools/net/WebFetchTool.java`                  |  ~85 | OkHttp + Jsoup |
| `tools/net/WebSearchTool.java`                 |  ~55 | Stub |
| `tools/edit/NotebookEditTool.java`            | ~110 | ipynb JSON |
| `tools/interactive/AskUserQuestionTool.java`   | ~110 | Question + answer |
| `tui/Scrollback.java`                          |  ~50 | Bounded buffer |
| `tui/PermissionDialog.java`                    | ~140 | 3-way dialog + persist |
| `tui/ReplApp.java` (rewritten)                 | ~250 | Multi-line + scrollback |
| `mcp/sse/SseMcpClient.java`                    | ~190 | HTTP+SSE transport |
| `idea-plugin/diff/DiffViewer.kt`               |  ~40 | Platform diff |
| `idea-plugin/input/FileReferenceCompletionContributor.kt` | ~85 | @-completion |
| `idea-plugin/AetherCodeChatPanel.kt` (updated) | ~50  | Wire diffs |
| `sdk/AetherCodeEngine.java` (updated)          | ~50  | Compact + hooks |

## 4. Known gaps remaining (carry to R3+)

- **Auto memory relevant recall** (the `findRelevantMemories` step) — R3
- **LSP tool** (would require a JVM LSP client) — R3
- **Hook registration from `settings.json`** — R3
- **Web search backend** (the stub is wired, the actual API is R3)
- **TUI vim mode** — R3
- **Plan mode UI** — R3
- **LSP auto-connect in the IDEA plugin** — R3
- **IDEA editor selection → input box** — R3
- **MCP `prompts/list` and `resources/list`** — R3
- **Streaming tool executor back-pressure** (currently drains the full batch) — R3
- **MCP auth** (OAuth flow) — R4
- **Swarm / Bridge / Remote** — R5

## 5. Migration notes for R1 callers

- The streaming executor is now the default path. If you built an orchestrator-shaped
  abstraction in R1, wire it as a `StreamingToolExecutor` instead — the public API
  is similar but events are emitted sooner.
- `CompactGate.compact(List)` used to return a `Result` object; it now implements
  `Compactor.compact(List)` which returns a new list. For tests that need the failure
  counter, call `compactRaw(List)` instead.
- `McpServers.loadFrom(Path)` now dispatches on `type: "sse"` vs `type: "stdio"`.
  R1 configs that had only `command` are treated as stdio (no migration needed).
- `ReplApp` no longer reads from stdin one-line at a time. Multi-line is the default;
  a single Enter sends, Shift+Enter inserts a newline, two Enters in a row end a
  block. Old single-line workflows still work because the empty-line heuristic is
  the same.
- `PermissionDialog` replaces `ConsolePrompter` in the CLI. The shape of the
  `ToolPermissionPrompter` SPI is unchanged.
