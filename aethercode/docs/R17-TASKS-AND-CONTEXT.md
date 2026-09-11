# R17: Per-Task Context + Task System (R17-A)

## TL;DR

Added a new `aethercode-tasks` module: `Task` (record) + `TaskStatus` + `TaskType` + `TaskRegistry` (process singleton) + `TaskContext` (per-task scope, task-ID-bound, not agent-bound). AetherCodeEngine now creates a task per user query; the task ID is emitted as a `SideNote` in the event stream so the TUI / CLI can correlate events with task lifecycle.

**Real test**: `--print "create hi.py"` now prints `[task u-b50isfs2 started]` at the top. 1264 unit tests pass, 0 net regression, 1 known-flaky.

## What's already there (from R1-R12)

Before R17, the project already had:

- **`aethercode-memory`** module with the full Claude Code memory system: `MemoryScope` (USER / PROJECT / LOCAL), `FileBackedMemory` (R10-3), `MemoryPaths` (mirrors `memdir/paths.ts`), `MemoryEntrypoint` (the `MEMORY.md` index with 200-line / 25KB cap), `MemoryExtractor`, `MemoryRecall`, `MemorySnapshot` + `MemorySnapshotSync`, `MemoryTools`, `TeamMemorySync`. 13 classes, 100+ tests. **No new work needed for the 3-layer memory question — the existing system already supports user / project / local scope with filesystem-backed markdown.**
- **`aethercode-compact`** module with `AutoCompact` + `CompactGate` (R10-4): token-based context budget, default 200K tokens, 4 chars/token heuristic, triggers compaction when context exceeds threshold. **Million-token context already works** by passing a larger `contextWindow` to `AutoCompact(chatClient, 1_000_000, ...)`.

## What R17-A added (this round)

### New module `aethercode-tasks/`

```text
aethercode-tasks/
├── pom.xml
└── src/
    ├── main/java/org/aethercode/tasks/
    │   ├── Task.java            # record: id, type, status, description, parentTaskId, ...
    │   ├── TaskType.java        # USER / AGENT / TOOL_BATCH / WORKFLOW
    │   ├── TaskStatus.java      # PENDING / RUNNING / COMPLETED / FAILED / KILLED
    │   ├── TaskRegistry.java    # process-singleton, listener pattern
    │   └── TaskContext.java     # per-task scope: appState, abort flag, parent, task memory root
    └── test/java/org/aethercode/tasks/
        └── TaskRegistryTest.java  # 9 tests
```

### `Task` — identity + lifecycle

```java
public record Task(
    String id,             // "u-b50isfs2" (user task), "a-c4d2e1f0" (agent), "b-..." (tool batch)
    TaskType type,
    TaskStatus status,
    String description,
    String parentTaskId,   // null for root
    long createdAtMs,
    long endedAtMs         // 0 while running
) { ... }
```

ID format: `<type-prefix>-<8-char-alphanumeric>`. The 8-char alphabet is `[0-9a-z]` (36 chars), giving 36^8 ≈ 2.8 trillion combinations — same as the TS source's defense against symlink brute force. Prefixes: `u-` (user), `a-` (subagent), `b-` (tool batch), `w-` (workflow).

### `TaskRegistry` — process singleton

```java
public final class TaskRegistry {
    public static TaskRegistry instance();
    public Task create(TaskType type, String description, String parentTaskId);
    public Task updateStatus(String taskId, TaskStatus next);  // terminal states are sticky
    public Optional<Task> get(String taskId);
    public List<Task> list();                  // all live, ordered by createdAtMs
    public List<Task> listChildren(String parentId);  // tree navigation
    public Consumer<Task> onChange(Consumer<Task> listener);  // TUI / audit subscription
}
```

Listener exceptions are isolated: a buggy subscriber can't roll back the registry mutation. Modelled after `utils/tasks.ts`'s `createSignal` + `tasksUpdated` pattern.

### `TaskContext` — per-task scope, NOT agent-bound

The user explicitly asked for: "上下文和任务ID绑定，不跟特定 Agent 绑定，Agent 是单例模式". `TaskContext` is the per-task scope that:

- Carries an `AtomicBoolean` abort signal (cancels this task's work, not other tasks').
- References the shared `AppState` (so the agent loop can read/write the session-wide transcript, tools, todo list).
- Has a per-task memory root (`<sessionsDir>/<sessionId>/tasks/<taskId>/memory.md`) — the agent can persist per-task notes that vanish when the task ends.
- Knows its parent task and sibling tasks via `registry.listChildren(task.id())`.

The same Agent can run many tasks in sequence; the context is bound to the task ID.

### `AetherCodeEngine` integration

```java
public Stream<StreamEvent> query(String userInput) {
    // R17: each user query becomes a Task. The Task ID is emitted as a
    // SideNote at the start of the stream so the TUI / CLI can correlate
    // engine events with task lifecycle.
    TaskRegistry registry = TaskRegistry.instance();
    Task task = registry.create(TaskType.USER, userInput, null);
    registry.updateStatus(task.id(), TaskStatus.RUNNING);
    Stream<StreamEvent> inner = queryEngine.query(userInput);
    // Wrap as a Spliterator that emits a task SideNote on entry and
    // transitions the task to COMPLETED/FAILED on RunEnd.
    return StreamSupport.stream(new AbstractSpliterator<>(...) { ... }, false);
}
```

Each user query → fresh task → emits `SideNote("task", "task u-xxxxxxxx started")` as the first event → task transitions to RUNNING → on `RunEnd`, transitions to COMPLETED (or FAILED if `stopReason` starts with "error").

### `Main.java` (--print mode) — display the task ID

```java
} else if (ev instanceof StreamEvent.SideNote sn && "task".equals(sn.kind())) {
    // R17: print task SideNotes so the user can see the task ID
    System.out.println(TerminalPalette.DIM + "  [" + sn.message() + "]" + TerminalPalette.RESET);
}
```

## Real test verification

```
$ java -jar ...shaded.jar --cwd examples --print "create a file hi.py that prints hi"

  [task u-b50isfs2 started]                ← R17 task ID, dim gray
  Done. Created `D:\...\examples\hi.py` containing `print("hi")`. Running it outputs:
  ```
  hi
  ```

Elapsed: 26.7s
```

The task ID `u-b50isfs2` shows the user a stable identifier they can:
- Correlate with log output
- Use in `/tasks list` (R18) to see all live tasks
- Reference in a subagent's `parentTaskId`

## Test counts

- 1159 → 1264 (+105; new TaskRegistryTest 9 tests + previously uncounted memory-module tests now exercised)
- 0 net regression
- 1 known-flaky on full run (R5 timing, passes individually)

## What's still missing (R17-B, R18+)

The user asked for 4 things in this round. Done: task system (this round). **Not done** (deferred):

### R17-B: TUI redesign (MiniMax Code 6-section layout)
- Top-left: New task
- Bottom-left: Project list + task list under each
- Right: TODO + Deliverables
- Center top: Execution info
- Center bottom: Input
- Bottom-left of input: Authorization mode
- Bottom-right: Model selection
- **Status**: data layer (TaskRegistry, AppState.todoList) is ready. The TUI is still JLine-based and line-oriented; full MiniMax-Code-style 6-pane layout needs `lanterna` or escape-based redraw.
- **Mitigation in this round**: ReplApp already has a `todos` panel tab (R16-b). We can add a `tasks` panel tab showing the TaskRegistry contents (`t` to cycle).

### R17-C: AgentTool (subagent spawning)
- `AgentTool` would let the main agent spawn subagents, each with a fresh `Task` ID and isolated `TaskContext`.
- **Status**: `Task` and `TaskContext` are ready. The tool itself needs to be written: a `Tool` implementation that takes `{ agent_type, prompt }`, creates a new `Task` (parent = current), runs the agent, and returns the result.
- The user's original requirement: "Agent 是单例模式" — the singleton Agent definition exists (SpringAiChatClient + StandardTools). The subagent instance is just a `TaskContext` wrapping the same Agent.

### R17-D: Million-token context tuning
- The `AutoCompact` already supports arbitrary context window sizes via constructor.
- What's missing: the engine doesn't auto-detect the model's max context and tune the window. For MiniMax-M3 (1M token), we should set `contextWindow=1_000_000`. R17+ candidate: add CLI flag `--context-window` and wire it through `AetherCodeEngine` → `QueryEngine` → `AutoCompact`.

### R18: Memory recall + auto-compaction
- `aethercode-memory/MemoryRecall` exists but isn't wired into the system-prompt assembly yet.
- The `aethercode-compact/AutoCompact` exists but only triggers on character-count heuristic; needs a token-aware upgrade + a "warn user when context > 80% full" hook.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-tasks/pom.xml` | new | Module definition. |
| `aethercode-tasks/src/main/java/.../Task.java` | new | Record. |
| `aethercode-tasks/src/main/java/.../TaskType.java` | new | USER / AGENT / TOOL_BATCH / WORKFLOW + ID prefix map. |
| `aethercode-tasks/src/main/java/.../TaskStatus.java` | new | Lifecycle enum. |
| `aethercode-tasks/src/main/java/.../TaskRegistry.java` | new | Process singleton + listener pattern. |
| `aethercode-tasks/src/main/java/.../TaskContext.java` | new | Per-task scope. |
| `aethercode-tasks/src/test/java/.../TaskRegistryTest.java` | new | 9 tests covering ID format, parent/child, terminal stickiness, listener isolation. |
| `aethercode/pom.xml` | +5 | Add `aethercode-tasks` to `<modules>` and `<dependencyManagement>`. |
| `aethercode-sdk/pom.xml` | +4 | Add `aethercode-tasks` dependency. |
| `aethercode-sdk/src/main/java/.../AetherCodeEngine.java` | +35 | Wrap `query()` with a Spliterator that creates/updates Task. |
| `aethercode-cli/src/main/java/.../Main.java` | +5 | Print `task` SideNotes in `--print` mode. |

## Backups

- `D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r17\` (planned)
