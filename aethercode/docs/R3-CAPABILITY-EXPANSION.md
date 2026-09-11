# AetherCode R3 — Capability Expansion

> 8 capabilities, 94 tests, recall, LSP tool, web search, settings hooks, MCP prompts/resources,
> TUI vim + plan mode, IDEA selection injection, streaming back-pressure.

R3 closes the R2 "carried-forward" list and adds two never-in-originals features: real
web search backends and IDEA editor selection injection.

---

## 1. Capability matrix

| # | Capability | Where | Tests |
|---|------------|-------|------:|
| 1 | Auto memory relevant recall | `aethercode-memory/.../MemoryRecall` | 4 |
| 2 | LSP tool (lsp4j-backed) | `aethercode-tools/.../lsp/LspTool` | 0 (compile-time) |
| 3 | Web search backend (Brave / Serper) | `aethercode-tools/.../net/{Brave,Serper}SearchClient` | 2 |
| 4 | Hook registration from settings.json | `aethercode-hooks/.../SettingsHooks` | 4 |
| 5 | MCP prompts/list + resources/list | `aethercode-mcp/.../McpPrompts` | 0 (compile-time) |
| 6 | TUI vim mode + Plan mode UI | `aethercode-tui/.../{VimMode,PlanPanel}` | 4 |
| 7 | IDEA editor selection → input box | `idea-plugin/.../action/SendSelectionToAetherCodeAction` | — |
| 8 | Streaming executor back-pressure | `aethercode-core/.../StreamingToolExecutor` (rewrite) | 1 |
|   | **R1+R2 carryover** |   | 79 |
|   | **Total** |   | **94** |

## 2. How each piece fits in

### 2.1 Memory relevant recall

`MemoryRecall` is a small scorer + optional LLM side-query. The scorer is purely lexical
(filename + manifest match + tool-name match + mtime decay). The side-query is only
invoked when there are more than `MAX_RECALL * 3` candidates — that prevents an
unnecessary LLM round trip for short memory directories.

Wired into the system prompt via `MemoryPromptBuilder.build(agentType, scope, dir, sideClient, currentInput, recentTools)`. The SDK injects the user's current input and the recent tool names so the recall happens for free on every turn.

### 2.2 LSP tool

Built on lsp4j 0.22.0. R3 ships the wrapper surface:
- `action=get_diagnostics` reads the cached diagnostics (populated by the in-process `InMemoryLanguageClient`)
- `action=list_servers` echoes the `AETHERCODE_LSP_SERVERS` env var
- `action=hover|definition|format` are stubs (return a clear "stub" message) so the schema is locked in
- R4 will add real stdio bootstrap + `didOpen` synchronization

The tool is added to the standard pool so the model can use it from R3 onwards without any
config.

### 2.3 Web search backends

Two clients share the same shape (`List<Map<String,String>> search(query, num)`):
- `BraveSearchClient` (X-Subscription-Token, REST GET)
- `SerperSearchClient` (X-API-Key, JSON POST)

`WebSearchTool` picks the first available API key. Both clients return results in the
shape `[{title, url, snippet}]` which the tool formats as a numbered list.

### 2.4 Settings-driven hooks

`SettingsHooks.loadFrom(settings.json, registry)` reads a `hooks` block:

```json
{
  "hooks": {
    "PreToolUse":  [{ "matcher": "bash", "action": "deny", "reason": "shell blocked" }],
    "PostToolUse": [{ "matcher": "*",     "action": "log",  "path": "~/.aethercode/audit.log" }]
  }
}
```

Supported actions: `deny`, `allow`, `log`. Matchers support `*` wildcard. The SDK
auto-loads `.aethercode/settings.json` so the user gets behaviour-driven hooks without
any code change.

### 2.5 MCP prompts/list + resources/list

`McpPrompts` provides a typed wrapper around the two new JSON-RPC methods. Both
`StdioMcpClient` and `SseMcpClient` got a generic `rpc(method, params)` helper that the
prompts/resources layer calls. Each prompt can be exposed as a synthetic
`mcp_prompt__<server>__<prompt>` tool so the model can invoke it like any other.

### 2.6 TUI vim + plan mode

`VimMode` is a 3-state machine (`NORMAL` / `INSERT` / `VISUAL`). The current mode is
shown in the prompt as `[N]`, `[I]`, `[V]`. Press ESC to toggle; `/vim` does the same.
R3 only ships the badge + state; R4 will wire motion keys (h/j/k/l, w/b/e, 0/$, gg/G, dd/yy).

`PlanPanel` is the TUI surface for `PermissionMode.PLAN`. The user runs `/plan`,
the engine is switched to plan mode, the next prompt is augmented with
"[PLAN MODE] Produce a step-by-step plan. Use only read-only tools." and the agent
returns a numbered list. `/approve` flips the mode back to `DEFAULT` and lets the
agent execute; `/reject` discards the plan.

### 2.7 IDEA editor selection injection

`SendSelectionToAetherCodeAction` (Alt+Shift+A by default) reads the current editor
selection, opens the AetherCode ToolWindow, and pushes a `@<path>:L1-L2` reference plus
the first 20 lines of the snippet into the input box. The
`AetherCodeToolWindowFactory` now keeps a static `panelFor(project)` map so actions
from anywhere in the IDE can find the active panel.

### 2.8 Streaming executor back-pressure

R2's executor drained each batch before yielding the first event — fine for a 3-tool
batch, bad for a 50-tool batch with one slow tool. R3 replaces that with a
`BlockingQueue<Event>` per batch, a virtual-thread fan-out, and a `BatchEnd` sentinel
so the consumer can yield events as they arrive.

The new `runBatchBackpressured` is a strict improvement: same ordering guarantees, same
abort propagation, same hooks, but events are emitted as soon as they happen. A test
asserts that 3 × 60ms tools complete in < 150ms (they run in parallel; R2 would take
180ms+).

## 3. Migration notes for R2 callers

- `StreamingToolExecutor.Event` gained a `BatchEnd` variant. Switches on the sealed type
  must be exhaustive; existing R2 callers that used `instanceof` patterns are
  unaffected; switches are updated.
- `McpServers` and the `StdioMcpClient` / `SseMcpClient` got a generic `rpc(method, params)`
  method. Add this to your custom MCP wrappers.
- `WebSearchTool` no longer returns a stub error when no API key is set — it now returns
  the same error message but with a more useful `AETHERCODE_BRAVE_API_KEY` hint.

## 4. Known gaps remaining (carry to R4+)

- **Real LSP stdio bootstrap** — the wrapper is in R3; the transport layer that
  actually starts a server process is R4
- **TUI vim motion keys** — only the state machine is in R3
- **LSP `didOpen` / `didChange` synchronization** — R4
- **Plan mode UI polish** — R4 (currently the plan is captured in a String)
- **MCP auth / OAuth** — R4
- **Swarm / Bridge / Remote** — R5
- **IDE inline diff via IntelliJ VCS LocalChangeList** — R4
