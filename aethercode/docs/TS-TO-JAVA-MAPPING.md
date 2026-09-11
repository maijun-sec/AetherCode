# TypeScript → Java mapping reference

> Quick lookup table for the Java port. Each row is a TS file → Java counterpart.

| TS file | Java class | Module |
|---------|-----------|--------|
| `src/Tool.ts`                                     | `Tool`, `ToolDef`, `Tools`            | `aethercode-core` |
| `src/tools.ts`                                    | `StandardTools`                       | `aethercode-tools` |
| `src/tools/BashTool/BashTool.ts`                  | `tools.shell.BashTool`                | `aethercode-tools` |
| `src/tools/FileReadTool/FileReadTool.ts`          | `tools.file.FileReadTool`             | `aethercode-tools` |
| `src/tools/FileEditTool/FileEditTool.ts`          | `tools.file.FileEditTool`             | `aethercode-tools` |
| `src/tools/FileWriteTool/FileWriteTool.ts`        | `tools.file.FileWriteTool`            | `aethercode-tools` |
| `src/tools/GlobTool/GlobTool.ts`                  | `tools.file.GlobTool`                 | `aethercode-tools` |
| `src/tools/GrepTool/GrepTool.ts`                  | `tools.file.GrepTool`                 | `aethercode-tools` |
| `src/tools/TodoWriteTool/TodoWriteTool.tsx`        | `tools.task.TodoWriteTool`            | `aethercode-tools` |
| `src/services/tools/toolOrchestration.ts`         | `ToolOrchestrator`                    | `aethercode-core` |
| `src/services/tools/toolExecution.ts`             | (merged into orchestrator + Tool)     | `aethercode-core` |
| `src/services/tools/toolHooks.ts`                 | (R2)                                 | `aethercode-hooks` |
| `src/query.ts`                                    | `QueryEngine`                         | `aethercode-core` |
| `src/QueryEngine.ts`                              | `AetherCodeEngine`                    | `aethercode-sdk` |
| `src/services/api/claude.ts`                      | `AnthropicChatClient`                 | `aethercode-llm` |
| `src/services/mcp/client.ts`                      | `StdioMcpClient` (R2: SSE / WS)       | `aethercode-mcp` |
| `src/services/mcp/mcpStringUtils.ts`              | `McpServers` (naming inline)          | `aethercode-mcp` |
| `src/memdir/memdir.ts`                            | `MemoryPromptBuilder`                 | `aethercode-memory` |
| `src/memdir/paths.ts`                             | `MemoryPaths`                         | `aethercode-memory` |
| `src/memdir/findRelevantMemories.ts`              | (R2)                                 | `aethercode-memory` |
| `src/services/SessionMemory/*`                    | (R2)                                 | `aethercode-memory` |
| `src/services/extractMemories/*`                  | (R2)                                 | `aethercode-memory` |
| `src/services/compact/autoCompact.ts`             | `AutoCompact`                         | `aethercode-compact` |
| `src/services/compact/compact.ts`                 | (R2 — partial in compact)             | `aethercode-compact` |
| `src/services/PromptSuggestion/*`                 | `SystemPrompt`                        | `aethercode-prompts` |
| `src/services/permissions/*`                      | `ProjectPermissionPolicy`             | `aethercode-permission` |
| `src/skills/*`                                    | `SkillRegistry` + `Skill`             | `aethercode-skills` |
| `src/utils/sessionStorage.ts`                     | `Transcript`                          | `aethercode-core` |
| `src/state/AppState.ts`                           | `AppState`                            | `aethercode-core` |
| `src/utils/swarm/backends/registry.ts`            | (R5+)                                | — |
| `src/entrypoints/cli.tsx`                         | `Main`                                | `aethercode-cli` |
| `src/main.tsx`                                    | `Main` + `AetherCodeEngine.builder()` | `aethercode-cli` / `aethercode-sdk` |
| `src/replLauncher.tsx`                            | `ReplApp.run()`                       | `aethercode-tui` |
| `src/screens/REPL.tsx`                            | `ReplApp` + `MessageRenderer`         | `aethercode-tui` |
| `src/components/Message.tsx`                      | `MessageRenderer`                     | `aethercode-tui` |
| `src/components/PromptInput/*`                    | `ReplApp` (JLine `LineReader`)        | `aethercode-tui` |
| `src/components/memory/MemoryFileSelector.tsx`    | (R2)                                 | `aethercode-memory` |
| `src/commands.ts`                                 | (R2 — slash commands in ReplApp)      | `aethercode-tui` |

## Notes on deliberate differences

- **No JSX / no React.** The TUI uses JLine + raw ANSI sequences. The TUI is a
  single-threaded read-print loop; the REPL.tsx virtual list / vim mode / hint chips
  are R2.
- **Records & sealed types over `type` aliases.** The TS code uses TypeScript
  discriminated unions (`type ToolResult = Allow | Deny | Ask`); the Java side uses
  sealed interfaces and record subtypes. Same shape, compile-time enforced.
- **CompletableFuture over `Promise`.** `Tool.call()` returns
  `CompletableFuture<ToolResult>`. R2 will introduce `StructuredTaskScope` once
  Orchestrator needs cancel-aware fan-out.
- **Jackson over `JSON.parse` / `JSON.stringify`.** Where the TS code uses JSON-RPC
  envelopes (MCP, settings), the Java side uses Jackson `ObjectMapper`.
- **Virtual threads in the orchestrator.** `CompletableFuture.runAsync` defaults to
  ForkJoinPool in JDK 21, which is backed by virtual threads. No explicit pool config
  needed for R1.
