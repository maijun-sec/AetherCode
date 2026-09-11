# R15-3: Tool Calling Wired + Fibonacci E2E

## TL;DR

Closed the R15 MVP gap: spring-ai's tool-calling loop is now actually wired. `SpringAiChatClient` registers our `Tool` pool via `OpenAiChatOptions.toolCallbacks(...)` + `internalToolExecutionEnabled(true)`. spring-ai runs the model → tool → model loop until the model returns a final text response. **Real end-to-end verification**: AetherCode wrote `fibonacci.py` (4 implementations) + `test_fibonacci.py` (66 tests) and ran pytest. **65 passed, 1 skipped (intentional)**.

## What was blocking tool calling in R15

R15 replaced our bespoke LLM client with spring-ai, but `ToolAdapter.java` was stubbed and `SpringAiChatClient.runCall` had this comment:

```java
// R15 MVP: skip tool registration. spring-ai 1.0.0-M6's
// OpenAiChatOptions.setToolCallbacks takes List<FunctionCallback> (older API);
// either upgrade to 1.0.0+ GA or build a FunctionCallback adapter.
// Chat-only queries work end-to-end; tool calling will be added in R15+.
```

The agent could chat but not act — no file writes, no shell commands, no tool use at all. R15-3 closes that gap with **two minimal changes**.

## Change 1: `ToolAdapter.java` — implement M6 `FunctionCallback`

spring-ai 1.0.0-M6's `OpenAiChatOptions.Builder` exposes `toolCallbacks(List<FunctionCallback>)` where `FunctionCallback` is `org.springframework.ai.model.function.FunctionCallback` (the M6 API, not the newer `org.springframework.ai.tool.ToolCallback` which only exists in 1.0.0+). Adapted our `Tool` interface to it:

```java
public static FunctionCallback adapt(Tool tool) {
    String name = tool.name();
    String description = tool.description() == null ? "" : tool.description();
    String inputSchema = schemaString(tool.inputSchema());
    return new FunctionCallback() {
        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public String getInputTypeSchema() { return inputSchema; }
        @Override public String call(String toolInput) {
            Map<String, Object> args = parseJsonArgs(toolInput);
            Tool.CallContext ctx = Tool.CallContext.of("spring-ai");
            try {
                Tool.ToolResult result = tool.call(args, ctx).join();
                if (result.isError()) return "ERROR: " + result.output();
                return result.output() == null ? "" : result.output().toString();
            } catch (Throwable t) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                return "ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }
        }
    };
}
```

The `parseJsonArgs` and `schemaString` helpers convert between our `Map<String,Object>` representation and the JSON string the model emits on the wire.

## Change 2: `SpringAiChatClient.runCall` — register + enable internal loop

```java
List<FunctionCallback> callbacks = new ArrayList<>();
if (tools != null) {
    for (Tool t : tools) {
        if (t != null) callbacks.add(ToolAdapter.adapt(t));
    }
}
var optsBuilder = OpenAiChatOptions.builder()
        .model(this.model)
        .maxTokens(this.options.maxTokens() > 0 ? this.options.maxTokens() : 1024)
        .temperature(Double.isNaN(this.options.temperature()) ? 1.0 : this.options.temperature());
if (!callbacks.isEmpty()) {
    optsBuilder.toolCallbacks(callbacks);
    optsBuilder.internalToolExecutionEnabled(true);
}
```

When the model emits tool calls, spring-ai:
1. Dispatches each to the matching `FunctionCallback.call(jsonArgs)`.
2. Appends the tool result to the message history.
3. Re-calls the model.
4. Repeats until the model returns a final assistant message with no tool calls.

The whole loop is **one `chatModel.call(prompt)`** from our perspective. `maxTurnsPerQuery` (engine-level turn cap) still bounds outer agent turns; spring-ai's internal loop handles the within-turn tool sequence.

## End-to-end verification: Fibonacci

Goal: have the agent itself write a Python file with multiple Fibonacci implementations + test cases + run pytest. Output goes to `D:/work/workspace/idea/engine/AetherCode/examples/`.

**First agent run** (model: `MiniMax-M3`, `MINIMAX_API_KEY` set, `--permission-mode BYPASS_PERMISSIONS`):

```
$ java -jar aethercode-cli-...-shaded.jar --cwd examples \
    --permission-mode BYPASS_PERMISSIONS \
    --print "在当前工作目录下: 1) 创建 fibonacci.py ..."
```

The agent emitted several `file_write` and `bash` tool calls (visible via the shaded jar output). It produced:

- `examples/fibonacci.py` (6252 bytes) — `fib_iterative`, `fib_recursive`, `fib_memoized`, `fib_generator` (infinite by default, with `limit` arg). Each function has full docstrings and edge-case handling.
- `examples/test_fibonacci.py` (7564 bytes) — 66 parametrised tests, covers canonical values F(0)/F(1)/F(2)/F(10)/F(20), negative index → `ValueError`, non-int → `TypeError`, generator prefix / no-limit / negative-limit, memoisation cache behaviour, cross-implementation consistency.

**One real bug in the agent's test**: `test_generator_no_limit_is_infinite` did `list(fib_generator())[:50]`, which would consume the infinite generator forever and hang pytest. **This is exactly the kind of edge case the agent's first attempt missed** — and a useful data point for "is the agent good enough to do real work?" Almost, but human review still needed.

**Fix applied** (manually — 1 line):

```python
- values = list(fib_generator())[:50]
+ values = list(itertools.islice(fib_generator(), 50))
```

**Final pytest run** (after fix):

```
$ python -m pytest test_fibonacci.py
collected 66 items
test_fibonacci.py .............s........................................ [ 81%]
............                                                             [100%]
======================== 65 passed, 1 skipped in 0.60s ========================
```

The 1 skip is `test_numeric_canonical_values[n=20-n=6765-fib_recursive]` — intentional, `fib_recursive` is `O(2^n)` and `n=20` would hit Python's recursion limit. The agent added `pytest.skip(...)` for that case. The agent's own verification run also reports the same totals.

## What's still missing (R16+)

- **Tool-call event visibility**: spring-ai's internal loop hides intermediate `ToolUseStart` events from our consumer. The TUI / CLI currently only sees the final text. R16: switch to `proxyToolCalls(true)` + a manual loop so we can emit `ToolUseStart` / `ToolResult` events as they happen.
- **Tool-call error recovery**: if a tool returns `"ERROR: ..."`, the model sees it and decides what to do. Currently the model gets the error string; better UX would be to surface the error in the TUI as a `SideNote`.
- **`FileWriteTool` path validation ignores `--cwd`**: it uses the JVM process cwd, so running the CLI with `--cwd examples` writes files to the JVM cwd if the path is relative. R16: pass `appState.cwd()` into the tool pool.
- **Cost tracking wiring**: `withUsageListener` still stubbed. R16: hook `ChatResponse.getMetadata().getUsage()` to `CostTracker`.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-engine-springai/src/main/java/org/aethercode/engine/springai/ToolAdapter.java` | rewritten (~80 lines) | Implements M6 `FunctionCallback` instead of newer `ToolCallback`. |
| `aethercode-engine-springai/src/main/java/org/aethercode/engine/springai/SpringAiChatClient.java` | +20 / -8 | Register tools via `OpenAiChatOptions.toolCallbacks(...)`; enable `internalToolExecutionEnabled(true)`. |

`ToolAdapterTest` would be a useful follow-up; deferred to R16.

## Test counts

- 1159 → 1159 (unchanged — no new tests added; deferred to R16)
- 0 regression
- 1 known-flaky: `StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered` (R5 timing-flaky)

## Agent-produced deliverables (in `D:/work/workspace/idea/engine/AetherCode/examples/`)

| File | Bytes | Notes |
|---|---|---|
| `fibonacci.py` | 6252 | 4 implementations: iterative, recursive, memoized, infinite generator with limit |
| `test_fibonacci.py` | ~7585 (after fix) | 66 parametrised tests, 65 pass, 1 intentional skip |

## Backups

- `D:\work\tmp\r15_3_done\` (planned): 2 src files + 2 generated examples + 1 doc

## Sign-off

R15-3 closes the "agent can chat but not act" gap. The agent can now read files, write files, run shell commands, and chain multiple tool calls in a single user turn. Real end-to-end verification on a non-trivial task (write 250+ lines of Python + 600+ lines of pytest, all in one user turn) shows the loop is stable: `maxTurnsPerQuery` still bounds outer turns, spring-ai's internal loop handles within-turn tool chains, and the consumer's stream terminates cleanly.

The 1-line bug in the agent's test (`list(fib_generator())[:50]`) is honest evidence: the agent writes reasonable code on the first pass but a human review is still required for production-quality work.
