# AetherCode R1 — MVP Skeleton

> 14 modules, 36 tests, one working CLI, one working IDEA plugin ToolWindow.

This is the first delivery of the Java port of Claude Code. It is the smallest useful
end-to-end stack — agent loop, 6 tools, permission system, memory, MCP stdio, TUI, and
an IDEA plugin ToolWindow — so the rest of the system can be built on top.

---

## 1. What's in R1

| Module | Lines | Status |
|--------|------:|--------|
| `aethercode-core`      | ~700 | Tool protocol, Message, QueryEngine, Transcript, AppState, Orchestrator |
| `aethercode-llm`       | ~300 | Anthropic Messages API client with SSE streaming |
| `aethercode-permission`| ~150 | Allow/ask/deny rules + 5 PermissionModes |
| `aethercode-tools`     | ~500 | file_read / file_write / file_edit / glob / grep / bash / todo_write |
| `aethercode-memory`    | ~150 | MEMORY.md + 3 scope resolver + truncation |
| `aethercode-mcp`       | ~200 | JSON-RPC stdio client (`initialize`, `tools/list`, `tools/call`) |
| `aethercode-skills`    | ~70  | Markdown skill loader |
| `aethercode-hooks`     | ~70  | Pre/Post tool-use hook registry |
| `aethercode-compact`   | ~120 | Auto-compact with circuit breaker |
| `aethercode-prompts`   | ~120 | System-prompt assembler |
| `aethercode-tui`       | ~250 | JLine REPL, ANSI renderer, console prompter |
| `aethercode-sdk`       | ~150 | Public entry point — `AetherCodeEngine.builder()...query()` |
| `aethercode-cli`       | ~150 | Picocli entry, fast-path dispatch |
| `idea-plugin/`         | ~250 | IntelliJ ToolWindow + chat panel |

**Total**: ~3 200 lines of Java + Kotlin. 36 unit tests, all green.

## 2. How to use

### CLI

```bash
export ANTHROPIC_API_KEY=sk-...
java -jar aethercode/aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT-shaded.jar
```

Inside the REPL:
- type a prompt and press Enter
- `/tools` to list active tools
- `/state` to show session facts
- `/exit` to quit

Non-interactive single turn:
```bash
java -jar ...-shaded.jar --print "what does this repo do?"
```

### SDK

```java
AetherCodeEngine engine = AetherCodeEngine.builder()
        .cwd(Path.of(".").toAbsolutePath())
        .model("claude-sonnet-4-5")
        .build();
engine.query("explain Main.java").forEach(ev -> {
    if (ev instanceof StreamEvent.TextDelta td) System.out.print(td.text());
    else if (ev instanceof StreamEvent.RunEnd)   System.out.println();
});
```

### IDEA plugin

See `idea-plugin/README.md`. Build with `./gradlew :buildPlugin`, install the zip, and
open the ToolWindow via `View → Tool Windows → AetherCode` (or `Alt+A`).

## 3. Architecture in 5 sentences

The agent loop in `QueryEngine` streams an LLM response, extracts every `tool_use`
block, hands the batch to `ToolOrchestrator.partition()` to group by concurrency safety,
runs each batch through the permission policy, executes the tools, and feeds the
results back into the next LLM turn. Tools implement a sealed `Tool` interface with
schema, safety flags, and a `call()` returning `CompletableFuture<ToolResult>`. The
permission layer (`ProjectPermissionPolicy`) evaluates allow/deny/ask rules plus the
session-wide `PermissionMode`. State lives on `AppState` and is persisted as a JSONL
`Transcript` on disk for resume. MCP stdio tools are injected into the same pool as
the built-ins, with `mcp__<server>__<tool>` names so the model can't tell them apart.

## 4. How it maps to the TypeScript original

| TS source | Java equivalent |
|-----------|-----------------|
| `src/Tool.ts`                              | `aethercode-core` — `Tool` interface + `Tools.build()` |
| `src/services/tools/toolOrchestration.ts`  | `aethercode-core` — `ToolOrchestrator` (partition + dispatch) |
| `src/query.ts`                             | `aethercode-core` — `QueryEngine` (main loop) |
| `src/services/api/claude.ts`              | `aethercode-llm` — `AnthropicChatClient` (SSE) |
| `src/services/mcp/client.ts`              | `aethercode-mcp` — `StdioMcpClient` |
| `src/memdir/*`                             | `aethercode-memory` — `MemoryPaths` + `MemoryEntrypoint` + `MemoryPromptBuilder` |
| `src/services/permissions/*`              | `aethercode-permission` — `ProjectPermissionPolicy` |
| `src/screens/REPL.tsx` + Ink              | `aethercode-tui` — `ReplApp` (JLine + ANSI) |
| `entrypoints/cli.tsx`                      | `aethercode-cli` — `Main` (picocli) |
| `src/utils/QueryEngine.ts`                | `aethercode-sdk` — `AetherCodeEngine` |

## 5. Test coverage

```
aethercode-core:        4 tests  (Message, Orchestrator, ToolDef)
aethercode-permission:  4 tests  (allow/deny/ask/mode)
aethercode-memory:      4 tests  (truncation, sanitisation)
aethercode-tools:       6 tests  (read, edit, replace_all, binary detect)
                       ----
                       36 tests, 0 failures
```

## 6. What's NOT in R1 (carried to R2+)

These were deliberately left out so R1 stays shippable in one round:

- **MCP SSE / WebSocket transports** — stdio only. R2 adds SSE.
- **StreamingToolExecutor** — concurrent + serial batches are honoured, but the
  model-side streaming executor (concurrent tool_use with backpressure) is not. R2.
- **Auto memory extraction** (the forked subagent that summarises past sessions) — R2.
- **AskUserQuestion tool** — the data model supports it, but no TUI surface. R2.
- **Plan mode** — `PermissionMode.PLAN` exists but the plan UI is not wired. R2.
- **LSP tool** — would require a JVM-side LSP client. R3.
- **Web search** — R2 (the API is straightforward).
- **Compact circuit breaker in QueryEngine** — `AutoCompact` exists with circuit breaker,
  but the engine doesn't call it on every turn yet. R2.
- **TUI scrollback / virtual list** — current REPL is line-by-line `println`. R2 swaps
  in a JLine-aware virtual list.
- **TUI permission dialog** — current REPL uses synchronous console prompt. R2 swaps in
  a real TUI modal.
- **IDEA inline diff, file reference completion, LSP auto-connect** — R2 / R3.
- **Remote / Bridge / Swarm** — not in scope until R5+.
- **Voice mode** — not in scope.
- **7+ of the 40+ TS tools** (NotebookEdit, ScheduleCron, Team*, WebSearch, McpAuth, etc.)
  — pick the top 3-4 in R2 (NotebookEdit, WebSearch, TeamCreate, McpAuth).

## 7. How to extend

Adding a new tool is 3 steps:

1. Write a `ToolDef` (or a builder class that returns a `Tool`).
2. Add it to `StandardTools.all()`.
3. Add tests.

Example — a `web_fetch` tool:

```java
public class WebFetchTool {
    public static final String NAME = "web_fetch";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("url", Tools.stringProp("URL to fetch."));
        Map<String, Object> schema = Tools.objectSchema(props, "url");
        return Tools.build(new ToolDef(NAME, "Fetch a URL.", schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input))));
    }
}
```

Add a test, append to `StandardTools.all()`, and the model can call it next session.
