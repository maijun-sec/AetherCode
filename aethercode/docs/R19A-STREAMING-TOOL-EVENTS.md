# R19-A — Streaming Text + Tool Event Visibility

**Date**: 2026-08-05
**Status**: DONE — 1176 tests, 0 net regression
**Goal**: Surface per-chunk text deltas and per-tool-call events in the agent's
output stream, instead of swallowing everything inside spring-ai's internal
loop.

---

## Why

R15-R18 used `internalToolExecutionEnabled(true)` on spring-ai's
`OpenAiChatOptions`. That made spring-ai drive the full
`model → tool → model` loop internally and only return the *final* assistant
message. From the consumer's perspective:

- The TUI's `tasks` / `todos` panels only saw the final text. There was no
  signal that the agent was in the middle of running a tool.
- The CLI's `--print` mode only printed the final answer. The user had no
  idea the agent was making 5 tool calls in parallel.
- Streaming text was discarded (one `TextDelta` at the end of the whole loop,
  not per chunk).

The TS original `claude-code` shows each tool call as it happens: the agent
text streams live, then `→ Running tool: bash(command=...)` appears, then the
tool result, then the next text stream. That's the UX the user wanted.

## What changed

### `SpringAiChatClient.runCall` — refactored

| Before (R15-R18) | After (R19-A) |
| --- | --- |
| `chatModel.call(prompt)` (synchronous) | `chatModel.stream(prompt)` (`Flux<ChatResponse>`) |
| `internalToolExecutionEnabled(true)` | `proxyToolCalls(true)` |
| One `TextDelta` at the end | One `TextDelta` per chunk |
| `ToolUseStart` events emitted by the chat client | `ToolUseStart` events emitted by the engine |
| Final `RunEnd` carries stopReason | Final `RunEnd` carries `stopReason="tool_calls"` or `"stop"` |

`proxyToolCalls(true)` tells spring-ai to return the model's tool-call
request in the final response but NOT execute it. We then surface the
tool calls via the engine's normal tool-execution path (with permissions,
hooks, parallelism), and re-call the model with the tool results. This
exposes every step to the consumer.

### `QueryEngine` — populate `pending` from `RunEnd.finalBlocks`

Since the chat client no longer emits `ToolUseStart` per tool call, the
engine's `pending` list (which holds the tool calls for the next batch) was
empty after `RunEnd`. The engine now populates `pending` from
`RunEnd.finalBlocks` (the assistant message's `tool_use` blocks) when
`RunEnd` is consumed.

```java
if (pending.isEmpty() && lastAssistantBlocks != null) {
    for (ContentBlock b : lastAssistantBlocks) {
        if (b instanceof ContentBlock.ToolUseBlock tu) {
            pending.add(tu);
        }
    }
}
```

The engine still emits its own `ToolUseStart` from
`StreamingToolExecutor.Event.Started` when the tool actually starts running,
so the consumer sees exactly one `→ tool(args)` per tool call.

### Dedupe by `(name, args)`

Spring-ai 1.0.0-M6's OpenAI streaming aggregation occasionally produces
duplicate tool-call entries with different ids but identical name+args
(observed in a real `--print` run). We dedupe by `(name, args)` before
adding to `finalBlocks` so the engine runs each unique tool exactly once.

### `Main.runHeadless` (CLI `--print`) — surface tool calls

Added a `StreamEvent.ToolUseStart` handler to the `--print` event loop:

```
  [task u-rrur6yoi started]
  ... streaming text ...
  → bash(command=dir)         ← new in R19-A
  ... tool result fed to model ...
  ... streaming text ...
  Final answer
```

`→ tool(args)` is printed in dim ANSI, one line per tool call. Multi-line
or long args are truncated to 40 chars and stripped of newlines so the line
stays single-line. The tool result itself is NOT printed (the model's
follow-up reply covers it).

## Real test (`--print "Use bash to run: dir"`)

```
  [task u-qe5hq69k started]
  <think>The user wants me to run `dir` command using bash. Let me execute that.</think>
  → bash(command=dir)
  <think>The `dir` command output shows the contents of the current working
  directory... 16 subdirectories, 2 files...
  Let me provide a clean summary to the user.</think>

  Listed contents of `D:\work\workspace\idea\engine\AetherCode\aethercode`:

  **Subdirectories (16):**
  - `aethercode-bridge`, `aethercode-cli`, `aethercode-compact`, `aethercode-core`
  - ... (14 more)
  **Files (2):**
  - `pom.xml` (10,252 bytes)
  - `README.md` (4,180 bytes)
```

## Files

- `aethercode-engine-springai/.../SpringAiChatClient.java` — refactored `runCall`
  (stream + proxyToolCalls + dedupe)
- `aethercode-core/.../engine/QueryEngine.java` — populate `pending` from
  `RunEnd.finalBlocks` so the engine's batch handler still runs the tools
- `aethercode-cli/.../Main.java` — `--print` surfaces `ToolUseStart` events
  as `→ tool(args)` lines; new `summariseArgs` helper for the args display

## Tests

- 1176 tests pass, 0 failures, 0 net regression, 1 known-flaky
  (pre-existing R5 `StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered`).
- New behaviour verified by real `--print` runs.

## Pitfalls (R19-A)

1. **Don't double-emit `ToolUseStart`** — the engine already emits one when
   `StreamingToolExecutor.Event.Started` fires (with the same id/name/input
   as the model-declared tool call). Emitting it from the chat client too
   surfaces "→ bash" twice in `--print` and the TUI. The chat client now
   only adds the `ToolUseBlock` to the assistant message (`finalBlocks`);
   the engine is the sole source of `ToolUseStart` consumer events.
2. **`pending` must be populated or tools never run** — when the chat
   client no longer emits `ToolUseStart`, the engine's `pending` list
   stays empty. Without the fix in `QueryEngine`, the model emits tool
   calls in its final message, the engine sees an empty `pending`, and
   finishes the turn without running any tool. The model then sees no
   tool result in the next turn and the agent stalls.
3. **spring-ai 1.0.0-M6 produces duplicate tool calls** — the
   `(name, args)` dedupe is needed to avoid running the same tool
   twice. The model itself appears to be the source of the duplication
   (two distinct ids for the same logical call), but deduping at our
   layer is the simplest fix and doesn't require model changes.
4. **`Flux.toIterable()` blocks the worker thread** — this is fine
   because the chat-client worker thread is a single-purpose daemon
   thread dedicated to the LLM call. The main consumer thread pulls
   events from the queue at its own pace.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19a\`
(planned)

## Next

R19-B: MemoryRecall → System Prompt assembly. Wire the existing
`aethercode-memory` module into the per-query system prompt so the
agent has the relevant project / agent memory at the start of each
conversation.
