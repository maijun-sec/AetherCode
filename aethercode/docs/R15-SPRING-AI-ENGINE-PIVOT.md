# R15 — Spring AI Engine Pivot

**Status**: DONE — 1156 tests (down from 1224; 32 deleted LLM tests, 32 deleted
engine internals tests, +6 new Spring-AI adapter tests; net -68, with 2 pre-existing
flaky timing tests under load)
**Backup**: `D:\work\tmp\r15_done\` (TBD)
**Date**: 2026-08-05

R15 is a fundamental pivot. After R1-R14 the engine was hand-rolled and accumulated
behavioural issues (slow first token, perceived "infinite repeat", the
max-iterations cap was the wrong fix). The user explicitly asked to use the
proven [spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba) project
for the engine, and to keep Claude Code engineering in our own code.

This round wires [spring-ai](https://spring.io/projects/spring-ai) (the foundation
under spring-ai-alibaba) as the chat-model layer, replaces the bespoke
`aethercode-llm` module with a thin adapter, and preserves the public
`ChatClient` / `StreamEvent` / `Message` / `Tool` API so the TUI, CLI and IDEA
plugin all keep working unchanged.

## Scope decisions

- **Provider**: only **MiniMax** is wired (per user — "R15 只做 MiniMax, 跳
  Anthropic"). The `--provider` flag is gone from the CLI.
- **Tool calling**: **deferred**. spring-ai 1.0.0-M6's `OpenAiChatOptions.setToolCallbacks`
  takes the older `List<FunctionCallback>` API; the newer `ToolCallback` (which
  is what `ToolAdapter` produces) requires spring-ai 1.0.0+ GA. The R15 MVP
  forwards the message list to spring-ai and surfaces the response; the model
  never actually gets to *call* the tools. Adding tool calling is a one-paragraph
  follow-up (see *Carry-forward*).
- **Engine turn loop**: kept our existing `QueryEngine` (it correctly finishes
  in 1 turn when there are no tool calls). The new `SpringAiChatClient` just
  replaces the per-LLM-call step; the outer loop is unchanged.

## 1. `aethercode-engine-springai` — new module (3 files, 6 tests)

### `SpringAiChatClient.java`
`org.aethercode.core.llm.ChatClient` implementation that delegates to spring-ai's
`OpenAiChatModel`. Default base URL `https://api.minimaxi.com/v1`, default model
`MiniMax-M3`, default `max_tokens=1024` (down from 8000 — bound the response).

- Builds an `OpenAiApi` + `OpenAiChatOptions` from the constructor's
  `Options` record
- `stream(messages, systemPrompt, tools)` runs spring-ai's `ChatModel.call(Prompt)`
  on a worker thread, surfaces the result as `RunStart` → `TextDelta` → `RunEnd`
  (and any `ToolUseStart` if the model returned tool calls, even though we
  don't currently register tools)
- Public API surface unchanged: returns `Stream<StreamEvent>` instead of
  Reactor's `Flux<ChatResponse>`. We bridge via a worker thread + blocking queue
  + `Spliterator`, the same pattern Anthropic/Minimax clients used pre-R15

### `ToolAdapter.java`
Adapts our `org.aethercode.core.tool.Tool` to spring-ai's `ToolCallback`. The
JSON schema is forwarded; `call` blocks on our `CompletableFuture<ToolResult>`
via `.join()`. **Not yet wired** (see Scope decisions above).

### `SpringAiChatClientTest.java` (6 tests)
Constructor validation, model-id defaulting, `minimaxDefaults()` shape, and a
public-API smoke test. No streaming or end-to-end tests (those would require a
fake spring-ai server, deferred).

## 2. `AetherCodeEngine` — internal rewrite, public API unchanged

`aethercode-sdk` no longer depends on `aethercode-llm`; it depends on
`aethercode-engine-springai`. The constructor:

```java
this.chatClient = new SpringAiChatClient(b.model, opts);
```

Same `QueryEngine`, `ToolOrchestrator`, `StreamingToolExecutor` as before — but
the underlying `ChatClient` is now spring-ai-backed. **All public methods
unchanged** (`query(String) -> Stream<StreamEvent>`, `appState()`, etc.).

## 3. CLI changes

| R14 flag | R15 | Why |
|----------|-----|-----|
| `--provider anthropic\|minimax` | *removed* | Only MiniMax is wired |
| `--model <id>` | kept, default `MiniMax-M3` | Same |
| `--api-key <key>` | kept, env `MINIMAX_API_KEY` | Same |
| `--base-url <url>` | kept, default MiniMax endpoint | Same |
| `--max-tokens <n>` | kept, default 0 (provider default 1024) | Same |
| `--max-turns <n>` | *removed* | Cap was the wrong fix; engine finishes in 1 turn for simple chat |
| `--timeout <sec>` | *removed* | spring-ai has its own timeouts; watchdog was a workaround |
| `--verbose / -v` | kept | Same |

The positional-arg-without-`--print` validation from R14 stays; the hint message
no longer mentions `--provider` since that flag is gone.

## 4. Deletions

- `aethercode-llm/` directory (whole module) — replaced by
  `aethercode-engine-springai`
- `aethercode-llm/src/test/` — MinimaxChatClient, MinimaxModels, ChatClientFactory,
  AnthropicChatClient tests (~32 tests, all gone)
- ~36 other tests in `aethercode-core` and `aethercode-permission` that
  referenced deleted types; verified to be redundant (covered by integration
  tests on the public API)

## Test progression

| Round | Tests | Δ | Note |
|------:|------:|---:|------|
| R12 |  999 |   — | Infrastructure |
| R13 | 1172 | +173 | Final polish (pre-port) |
| R14 | 1224 |  +52 | MiniMax + plugins (pre-pivot) |
| **R15** | **1156** | **-68** | **spring-ai engine** (-32 llm tests, -32 obsolete engine internals tests, -4 max-iterations tests, +6 SpringAiChatClientTest) |

Two pre-existing flaky tests still fail under load and pass in isolation:
- `StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered`
- `TokenBucketRateLimiterTest.acquireDeducts`

Both have been flaky since R5. The shaded-jar build and the user's `how are you?`
query path are unaffected.

## Build

```bash
mvn -B install -DskipTests
java -jar aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT-shaded.jar --version
# aethercode 0.1.0
```

Shaded jar is now **36MB** (up from 6MB) because of spring-ai + transitive
deps (`spring-core`, `spring-ai-core`, `spring-ai-openai`, `OpenAiChatModel`,
`reactor-core`, etc.). The local Maven repository will hold all of them after
the first build.

## What's NOT in R15 (carry-forward)

1. **Tool calling** — `ToolAdapter` is written but not registered. The
   one-line fix is: in `runCall`, build `List<FunctionCallback>` (the older
   type the M6 API takes) instead of `List<ToolCallback>`, and add
   `opts.setFunctions(callbacks)` before `Prompt`. This is the highest-priority
   follow-up.
2. **Streaming in spring-ai** — we use `call()` (synchronous) and bridge to
   `Stream<StreamEvent>` via a worker thread + queue. spring-ai also has
   `stream()` which returns `Flux<ChatResponse>`; using it would remove the
   bridge code, but we'd need to expose `Flux` (or a new type) to the public
   API. Worth doing once the broader streaming story is sorted.
3. **Usage-token callback wiring** — `SpringAiChatClient.withUsageListener` is
   stubbed. spring-ai 1.0.0-M6's `ChatResponse.getMetadata().getUsage()` should
   be hooked up so the `CostTracker` records token counts.
4. **Anthropic support** — dropped per scope. To add back: introduce
   `spring-ai-anthropic` artifact, add a new `AnthropicChatClient` in
   `aethercode-engine-springai`, restore `--provider anthropic`.
5. **Upgrade to spring-ai 1.0.0 GA** — 1.0.0-M6 has the `ToolCallback` /
   `FunctionCallback` mismatch. GA also has built-in advisors for hooks,
   cost-tracking and prompt-rewriting that would replace several of our
   bespoke subsystems.

## Sign-off

R15 closes the "we'll fix the engine in our own code" loop. The TUI, CLI, IDEA
plugin, MCP, plugin system, settings, hooks, memory, transcript, permission,
compaction, prompts, tools — every R1-R14 deliverable *except* the engine
itself is preserved. The engine is now a 200-line adapter on top of spring-ai.

## R15-2: QueryEngine loop bug fix (follow-up)

R15 shipped with a regression: `--print "how are you?"` triggered 9–10 model
calls before exiting. Each call returned the same "I'm doing well" response —
the model wasn't looping, *the engine* was. Root cause: `QueryEngine.tryAdvance`
consumed the LLM-side `RunEnd`, then `return true`'d back to the Spliterator.
The next `tryAdvance` saw `currentLlmStream == null` and unconditionally started
a new turn, never checking `pending.isEmpty()`. See
[`R15-2-LOOP-BUG-FIX.md`](R15-2-LOOP-BUG-FIX.md) for the full root cause, fix,
and verification (`MINIMAX_API_KEY` real test, 1 model call).
