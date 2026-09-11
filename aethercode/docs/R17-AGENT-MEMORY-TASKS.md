# R17: Agent Memory + Per-Task Context + Million-Token Context

## TL;DR

Three-layer memory (task / project / tool) + per-task context (task ID, not agent) + million-token context support. Reference: `claude-code-analysis-main/src/`.

## Reference: Claude Code's design

Studied from `claude-code-analysis-main/`:

- **Memory types** (4 scopes): `Auto Memory` (cross-session facts), `Session Memory` (per-conversation), `Agent Memory` (per-agent-type long-term), `Team Memory` (shared across team). All backed by filesystem markdown; `MEMORY.md` is the index (200 lines / 25KB cap), topic files for details.
- **Memory location**: `<memoryBase>/projects/<sanitized-git-root>/memory/` for project-scope; `~/.claude/agent-memory/<agentType>/` for agent-scope; `cwd/.claude/agent-memory/` for project agent.
- **Memory recall**: `findRelevantMemories()` — don't dump all into prompt, pick relevant. Token-budgeted.
- **Task system**: `Task.ts` defines `TaskType` (local_bash / local_agent / remote_agent / teammate / workflow / monitor), `TaskStatus` (pending / running / completed / failed / killed), `TaskContext` (AbortController + getAppState/setAppState).
- **Task ID**: prefixed random ID, e.g. `b<8 chars>` for bash, `a<8 chars>` for agent, `t<8 chars>` for teammate. 36^8 ≈ 2.8T combinations.
- **Agent = singleton**: an `Agent` is a definition (prompt + tools + model). Subagents are instances; `AgentTool` spawns them and gives each a task ID.
- **Context**: token-based compaction (`autoCompact.ts`), not file-count based.

## R17 architecture (this round)

### 1. Three-layer memory (`aethercode-memory` module)

| Layer | Location | Lifetime | Purpose |
|---|---|---|---|
| **Task memory** | `<sessionsDir>/<sessionId>/tasks/<taskId>/memory.md` | Per-task (cleared on completion, archived) | Working notes for the task |
| **Project memory** | `<cwd>/.aethercode/memory/` (with `MEMORY.md` index) | Persistent across tasks | Long-term project facts, conventions |
| **Tool memory** | `~/.aethercode/tool-memory/<tool-name>/` | Global, persistent | Cumulative learnings about a tool's behavior |

API:
```java
interface MemoryLayer {
    String name();            // "task" / "project" / "tool"
    Path root();              // storage root
    String read(String key);  // read entry, return markdown
    void write(String key, String content);
    List<String> list();      // list keys
    String recall(String query, int tokenBudget);  // relevant recall
}
```

`MemoryStore` facade holds all three layers, lets engine query for context assembly.

### 2. Per-task context + Agent singleton (`aethercode-tasks` module)

```java
// Task: identifier + scope for one unit of work
public record Task(
    String id,           // prefixed: "t-" (teammate) / "a-" (agent) / "b-" (bash)
    String type,         // "agent" / "bash" / "workflow"
    String status,       // "pending" / "running" / "completed" / "failed"
    String description,
    String parentTaskId, // null for root
    long createdAtMs,
    long endedAtMs
) {}

// TaskContext: per-task scope. Bound to task ID, NOT to agent.
public class TaskContext {
    final String taskId;
    final AppState appState;        // shared with engine
    final MemoryLayer taskMemory;   // isolated memory for this task
    final AtomicBoolean aborted;
    // ...
}

// TaskRegistry: process-singleton, tracks all tasks
public class TaskRegistry {
    static TaskRegistry instance();
    Task create(String type, String description, String parentTaskId);
    Optional<Task> get(String id);
    List<Task> listChildren(String parentId);
    void updateStatus(String id, String status);
}

// AgentTool: spawn subagent, bound to a new task ID
public class AgentTool {
    // input: { agent_type, prompt }
    // creates new task, runs agent with that task's context
    // returns task ID for parent to query
}
```

Agent itself is a singleton (the main agent). Subagents are spawned with new task IDs. Context flows through task ID, not agent identity.

### 3. Million-token context (`aethercode-context` module)

Token-based, not file-count based:
- `ContextWindow`: tracks total tokens used (system prompt + transcript + memory)
- `TokenBudget`: how much of each section is allowed
- `OffloadStore`: when a message > N tokens, write content to scratch file, replace with `@file:path` reference
- `CompactionTrigger`: when total > threshold, trigger compaction
- `RecallBudget`: memory recall returns at most N tokens

This replaces the current `aethercode-compact` (which exists but only does basic message-count compaction).

### 4. TUI redesign (MiniMax Code style)

6 sections:
- **Top-left**: New task button
- **Bottom-left**: Project list, with task lists under each
- **Right**: TODO list (progress) + Deliverables
- **Center top**: Execution info
- **Center bottom**: Input
- **Bottom-left of input**: Authorization mode (always / smart / ask)
- **Bottom-right**: Model selection

The current TUI is JLine-based, line-oriented. For a real multi-pane TUI, we'd need `lanterna` or escape to a TUI library. R17 ships the **data layer** (panels + state) and keeps the JLine renderer; R18 swaps in a full TUI library.

## Files / modules

New modules:
- `aethercode-memory/` — `MemoryLayer` interface + 3 implementations + `MemoryStore` facade
- `aethercode-tasks/` — `Task` + `TaskContext` + `TaskRegistry` + `AgentTool`
- `aethercode-context/` — `ContextWindow` + `TokenBudget` + `OffloadStore` + `CompactionTrigger`

Modified:
- `aethercode-core/.../QueryEngine.java` — use ContextWindow for system-prompt / transcript assembly
- `aethercode-tui/.../ReplApp.java` — new layout: project list (left) + task list (under) + todos/deliverables (right) + execution (center top) + input (center bottom) + auth/model (footer)

## Out of scope (R18+)

- Full TUI library (lanterna / asciimatics)
- Auto-compaction triggers
- Memory recall (smart injection based on relevance)
- Subagent UI (e.g., nested panes)
- Tool memory auto-extraction (right now, tools write to their own memory; future: auto-extract from tool outputs)

## Reference

- `claude-code-analysis-main/analysis/04-agent-memory.md`
- `claude-code-analysis-main/analysis/04h-multi-agent.md`
- `claude-code-analysis-main/analysis/04f-context-management.md`
- `claude-code-analysis-main/src/memdir/`
- `claude-code-analysis-main/src/services/SessionMemory/`
- `claude-code-analysis-main/src/tools/AgentTool/`
- `claude-code-analysis-main/src/Task.ts` / `tasks.ts`
- `claude-code-analysis-main/src/services/compact/`
