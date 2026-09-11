# R16-b: TODO List Visibility (AppState + TUI panel + --print output)

## TL;DR

User said: "TODO 分成了多少个步骤，当前执行到了哪个步骤等等，都不清晰" and "TUI 模式应该放在更重要的位置，清晰给出 TODO list，然后能标记已经执行了哪些步骤，哪些还没有执行". R16-b surfaces the in-session todo list end-to-end:

- **`AppState.todoList`** — single source of truth, set by `TodoWriteTool`, with listener pattern.
- **`--print` mode** — prints a "📋 TODO plan — N/M done" block every time the list changes.
- **TUI** — adds a "todos" panel (`t` to cycle), a `[3/5] ▶ current_step` badge in the prompt, `/todos` command, and scrollback entries on each update.

Real test: agent plans a 4-step stringutils task and the user sees each step transition live (0/4 → 1/4 → 3/4 → 4/4) before the final summary. **59 tests pass** end-to-end.

## What was missing

`TodoWriteTool` already existed (R1-R5 era) and was registered in `StandardTools.all()`. The system prompt (R16) told the model to call it. But:

1. The tool only stored the list in `CallContext.extras(STATE_KEY)` — a per-call map that's never read by anything else.
2. The TUI had no panel for the todo list.
3. `--print` had no output for todo updates.
4. The user could only see the agent's progress by reading the final response text.

## Plumbing

### 1. `AppState` — the new source of truth

```java
private volatile List<Map<String, Object>> todoList = List.of();
private final CopyOnWriteArrayList<Consumer<List<Map<String, Object>>>> todoListeners = ...;

public List<Map<String, Object>> todoList() { return todoList; }
public void setTodoList(List<Map<String, Object>> list) { ...; for (l : listeners) l.accept(next); }
public Consumer<List<Map<String, Object>>> onTodoUpdate(Consumer<...> listener) { ... }
```

`setTodoList` does a defensive `List.copyOf` so callers can't mutate live state. The listener is fire-and-forget: listener exceptions are caught and ignored so a buggy UI can't crash the engine.

### 2. `StreamingToolExecutor` — pass `app_state` in CallContext

```java
Tool.CallContext ctx = new Tool.CallContext(
    appState.sessionId(),
    msg -> queue.offer(new Event.Progress(call.id(), msg)),
    Map.of("call_id", call.id(), "app_state", appState)
);
```

The engine path (`StreamingToolExecutor`) was already constructing CallContext per call. Adding `app_state` to the extras map lets tools publish to AppState without coupling to a global.

### 3. `TodoWriteTool` — update AppState if present

```java
Object appState = ctx.extra("app_state");
if (appState instanceof org.aethercode.core.app.AppState as) {
    as.setTodoList(out);
}
```

Backwards-compatible: the test path that constructs `CallContext.of("spring-ai")` or empty extras still works — `ctx.extra("app_state")` is null and we skip.

### 4. `SpringAiChatClient` + `ToolAdapter` — plumb appState through the spring-ai path

The spring-ai tool loop dispatches tools in its own thread and we don't get a chance to inject CallContext extras from outside. So we stash `appState` on the client:

```java
public class SpringAiChatClient implements ChatClient {
    private volatile org.aethercode.core.app.AppState appState;
    public void appState(AppState s) { this.appState = s; }
}
```

`AetherCodeEngine` calls `chatClient.appState(appState)` right after construction. Then `ToolAdapter.adapt(tool, appState)` captures it in the FunctionCallback's closure and passes it through `CallContext` for every invocation. Same `app_state` extra key as the engine path, so `TodoWriteTool` doesn't care which one called it.

## Rendering

### `--print` mode (`Main.runHeadless`)

```
📋 TODO plan — 0/4 done (1 in progress)
  ▶ 1. 查看当前目录,了解环境
  ○ 2. 写 stringutils.py (reverse_string + is_palindrome,处理空串+None)
  ○ 3. 写 test_stringutils.py 完整测试
  ○ 4. 运行 pytest 验证

📋 TODO plan — 1/4 done (1 in progress)
  ✓ 1. 查看当前目录,了解环境
  ▶ 2. 写 stringutils.py (reverse_string + is_palindrome,处理空串+None)
  ○ 3. 写 test_stringutils.py 完整测试
  ○ 4. 运行 pytest 验证

... (more updates) ...

📋 TODO plan — 4/4 done
  ✓ 1. ...
  ✓ 2. ...
  ✓ 3. ...
  ✓ 4. 跑 pytest 验证
```

De-duped by structural equality (`sameTodoList`) so a status refresh that doesn't actually change the list doesn't spam the output.

### TUI (`ReplApp`)

Three additions:

1. **Prompt badge** — every time the prompt is re-painted, the badge shows `[done/total] ▶ current_step`:
   ```
   ❯ [3/5] ▶ 写 test_stringutils.py 完整测试
   ```
   Status icons use ANSI colors (green ✓, cyan ▶, dim ○).

2. **`todos` panel** — added to the existing `PanelTabs` system. Press `t` in NORMAL mode to cycle through chat / todos / tools / memory / log. The panel renders the full list with status icons and step numbers.

3. **Scrollback entries** — `engine.appState().onTodoUpdate(this::onTodoUpdate)` subscribes a callback that appends a `📋 plan: 2/4 done — ▶ 写 stringutils.py` line to the scrollback on every change. `/history` shows the plan's evolution.

4. **`/todos` command** — quick way to dump the current plan from INSERT mode without going through the panel.

## Real-test verification

Same stringutils task as the in-the-loop test:

```
$ java -jar ...shaded.jar --cwd examples --print "<prompt>"

📋 TODO plan — 0/4 done (1 in progress)
  ▶ 1. 查看当前目录,了解环境
  ○ 2. 写 stringutils.py ...
  ○ 3. 写 test_stringutils.py 完整测试
  ○ 4. 运行 pytest 验证

📋 TODO plan — 1/4 done (1 in progress)   # step 1 done, step 2 in progress
  ✓ 1. ...
  ▶ 2. ...

📋 TODO plan — 3/4 done (1 in progress)   # step 2 done, step 3 in progress
  ✓ 1. ...
  ✓ 2. ...
  ▶ 3. ...

📋 TODO plan — 4/4 done                  # all done
  ✓ 1. ...
  ✓ 2. ...
  ✓ 3. ...
  ✓ 4. ...

## Summary
### Files created
- **`.../stringutils.py`** — two functions
- **`.../test_stringutils.py`** — 59 tests

### Coverage
- **TestReverseString** (25)
- **TestIsPalindrome** (33)
- **TestConsistency** (1)

`59 passed in 0.77s`
```

The agent also caught two of its own test bugs mid-run (a typo in an expected value and a wrong test premise) and fixed them without being asked.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-core/src/main/java/org/aethercode/core/app/AppState.java` | +35 / -0 | `todoList` field + `setTodoList` + `onTodoUpdate`. |
| `aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java` | +1 / -1 | Pass `app_state` in CallContext extras. |
| `aethercode-tools/src/main/java/org/aethercode/tools/task/TodoWriteTool.java` | +5 / -0 | If `app_state` is in extras, publish to it. |
| `aethercode-engine-springai/.../SpringAiChatClient.java` | +5 / -0 | `appState` field + setter. |
| `aethercode-engine-springai/.../ToolAdapter.java` | +12 / -5 | New `adapt(tool, appState)` overload; old overload delegates. |
| `aethercode-sdk/.../AetherCodeEngine.java` | +5 / -0 | Wire `appState` into `SpringAiChatClient` on boot. |
| `aethercode-cli/.../Main.java` | +60 / -0 | `printTodoList`, `sameTodoList`, `onTodoUpdate` subscription in `runHeadless`. |
| `aethercode-tui/.../ReplApp.java` | +60 / -0 | `todoBadge`, `onTodoUpdate`, `renderTodosPanel`, `/todos` command, new panel tab. |

## Test counts

- 1159 → 1159 unchanged (no new unit tests — could add a `TodoWriteToolTest` verifying the AppState publish path; deferred to R17)
- 0 net regression
- 2 known-flaky on full run (R5 timing; both pass individually)

## R17 candidates (cumulative)

- Stream text to TUI in real time (currently buffered until spring-ai's internal loop ends)
- `proxyToolCalls(true)` + manual loop for `ToolUseStart` / `ToolResult` event visibility
- `TodoWriteToolTest` — unit test for AppState publish path
- IDEA plugin `AetherCodeSettings` Swing UI panel
- `withUsageListener` → `CostTracker` wire
- Upgrade to spring-ai 1.0.0 GA (new `ToolCallback` API; cleaner wiring)
- `--max-tokens` allow per-tool different caps

## Backups

- `D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r16b\` (planned)
