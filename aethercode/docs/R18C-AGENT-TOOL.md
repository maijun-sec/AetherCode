# R18-C: AgentTool (subagent spawning)

## TL;DR

The model can now call `spawn_agent` to delegate work to a subagent. The subagent gets a fresh `Task` ID, runs a single LLM call (no tool use), and returns its text response to the parent. **Real test**: agent spawned `a-hd7f5xd6` (child of `u-ajciz89a`), subagent ran 3.2s, produced 1482 chars of draft code, parent agent wrote files + ran pytest → **3 passed in 0.14s**. 1176 unit tests, 0 failures.

## What was added

### `AgentTool` (single-shot subagent)

`aethercode-tools/.../task/AgentTool.java`:

- Tool name: `spawn_agent`
- Input schema: `{ prompt: string, context?: string }`
- The model calls it with a self-contained prompt; the subagent runs a single `chatClient.stream()` call (no tools) and returns the text.
- Lifecycle: creates an `AGENT` task in the `TaskRegistry` (parent = the most recent RUNNING `USER` task), transitions it to RUNNING, then COMPLETED on success or FAILED on exception. The user can `/tasks` and see the parent → child relationship in the TUI.

### Wiring: chat_client in CallContext extras

The agent uses the same `ChatClient` (singleton — the user's "Agent 是单例模式" requirement). For `AgentTool` to call it, the chat client must be reachable from the tool's `CallContext`. Two paths:

- **Engine path** (`StreamingToolExecutor`): added `withChatClient(ChatClient)` builder method + `buildExtras(callId, appState)` that always includes `app_state` and adds `chat_client` if registered. `AetherCodeEngine` calls `exec.withChatClient(this.chatClient)` on boot.

- **spring-ai path** (`ToolAdapter`): added a new `adapt(Tool, AppState, ChatClient)` overload that adds `chat_client` to the `CallContext` extras. `SpringAiChatClient.runCall()` calls `ToolAdapter.adapt(t, this.appState, this)` — passing the client itself.

The first real run hit "AgentTool requires chat_client in CallContext extras" because the spring-ai overload wasn't yet wired; once `ToolAdapter` was updated, the second run spawned a subagent cleanly.

### Dependency cycle resolved

Adding `AgentTool` to `StandardTools.all()` made `aethercode-tools` depend on `aethercode-tasks`. `aethercode-tasks` already depended on `aethercode-memory`, and `aethercode-memory` depended on `aethercode-tools` — a 3-module cycle. Fix: removed the unused `aethercode-memory` dependency from `aethercode-tasks/pom.xml`. The tasks module never actually imported anything from `org.aethercode.memory`; the dependency was a dead import carried over from initial scaffolding.

## Real-test verification

```
$ java -jar ...shaded.jar --cwd examples --print \
  "Use the spawn_agent tool to delegate: ask it to create a file sub_demo.py
   with a function greet(name) that returns a greeting string. Then create
   test_sub_demo.py with tests and run pytest."

  [task u-ajciz89a started]                ← R17 task ID, USER type
  21:11:46 INFO  AgentTool - subagent a-hd7f5xd6 started, parent=u-ajciz89a
  21:11:49 INFO  AgentTool - subagent a-hd7f5xd6 completed (1482 chars)
  [task a-hd7f5xd6 completed]              ← AGENT type, child of u-ajciz89a

  test_sub_demo.py::test_greet_typical_case PASSED
  test_sub_demo.py::test_greet_returns_string PASSED
  test_sub_demo.py::test_greet_empty_string PASSED
  ============================== 3 passed in 0.14s ==============================

Elapsed: 42.7s
```

**The TUI would now show this task tree** (after R17-B):

```
Tasks (2)
  ▶ u-ajciz89a  user    "Use the spawn_agent tool to delegate..."
  ✓ a-hd7f5xd6  agent   "ask it to create a file sub_demo.py..."  (parent u-ajciz89a)
```

The parent task ran for 42.7s, the child task ran for 3.2s — a real subagent delegation in action.

## Design rationale

The user asked for "Agent 是单例模式 / 上下文和任务ID绑定". The `AgentTool` honors both:

- **Singleton Agent**: `AgentTool` uses `ctx.extra("chat_client")`, which is the same `SpringAiChatClient` instance the main query loop uses. There's no per-subagent agent definition — the agent's behavior is consistent because the *agent* (the chat client + system prompt + tool pool) is one instance, the *task* (the per-invocation scope) is what differs.

- **Task-bound context**: Each subagent invocation creates a new `Task` in the `TaskRegistry` with a unique ID (`a-xxxxxxxx`). The parent → child relationship is recorded via `parentTaskId`. The TUI's `tasks` panel shows this tree in real time. If a future round introduces multi-turn subagents, each subagent run would still be a fresh `Task` — context is always scoped to the task, not the agent.

## Limitations (deliberate, R19 territory)

- **Single-shot only**: the subagent cannot itself call tools. This is the explicit design — "single LLM call, no tool use" is what `AgentTool` does. Multi-step subagents (use a subagent to do a multi-step task with tools) require the recursive variant, which is R19.

- **No parent task ID through CallContext**: `AgentTool` discovers the parent by scanning the registry for the most recent RUNNING USER task. This is fragile if multiple USER tasks are running concurrently. R19 fix: pass `parentTaskId` through the `CallContext` extras map directly.

- **Subagent doesn't share the parent's transcript**: the subagent's prompt is the only context it sees. For complex delegation, the parent should pre-summarize. R19: a `context` parameter that pulls from `appState.transcript()` automatically.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-tools/.../task/AgentTool.java` | new (~180) | The tool. |
| `aethercode-tools/src/test/.../AgentToolTest.java` | new (~120) | 4 tests. |
| `aethercode-tools/.../StandardTools.java` | +2 / -1 | Add `AgentTool` to the default pool. |
| `aethercode-tools/pom.xml` | +4 | Add `aethercode-tasks` dependency. |
| `aethercode-tasks/pom.xml` | -4 | Remove unused `aethercode-memory` dependency (cycle). |
| `aethercode-core/.../engine/StreamingToolExecutor.java` | +20 | `withChatClient` + `buildExtras` exposes chat client to engine-path tools. |
| `aethercode-sdk/.../AetherCodeEngine.java` | +3 | Wire `exec.withChatClient(this.chatClient)`. |
| `aethercode-engine-springai/.../ToolAdapter.java` | +35 | New `adapt(Tool, AppState, ChatClient)` overload. |
| `aethercode-engine-springai/.../SpringAiChatClient.java` | +4 / -1 | Pass `this` as the chat client in `ToolAdapter.adapt`. |

## Test counts

- 1172 → 1176 (+4 from new `AgentToolTest`)
- 0 net regression
- 0 known-flaky in this run (R5 timing-flaky passed)

## What's still missing (R19+)

- **Multi-step subagents** — let the subagent call tools (recursive `AgentTool`)
- **Parent task ID propagation** — pass via CallContext extras instead of scanning the registry
- **Shared transcript** — subagent sees parent's recent turns automatically
- **Recursion guard** — subagent depth limit so a runaway doesn't loop forever
- **Full 6-section TUI** (R18-B)
