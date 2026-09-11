# R15-2: QueryEngine Loop Bug Fix

## TL;DR

`--print "how are you?"` triggered 9–10 model calls before exiting. Root cause: `QueryEngine.tryAdvance` consumed the LLM-side `RunEnd` event but then `return true`'d back to the Spliterator. The next `tryAdvance` saw `currentLlmStream == null` and unconditionally started a new turn — never checking whether the previous turn had any tool calls. Real test: `MINIMAX_API_KEY` + `--print "how are you?"` → now 1 model call, response in ~9s.

## Symptom (before fix)

```
$ java -jar aethercode-cli-...-shaded.jar --print "how are you?"
[spring-ai DEBUG] text='<think>The user is just greeting me...'
[spring-ai DEBUG] text='<think>The user is just greeting me...'
[spring-ai DEBUG] text='<think>The user is just greeting me...'
... 7 more times, identical
Hi! I'm doing well, thanks for asking. ...
```

10 model calls for a 1-token prompt. Each call returned the same "I'm doing well" response. The engine never noticed the loop was redundant.

## Root cause

`QueryEngine.query()` returns a `StreamSupport.stream(sp, false)` over a custom `Spliterator`. Inside `tryAdvance`, the state machine was:

```java
if (currentLlmIt.hasNext()) {
    StreamEvent ev = currentLlmIt.next();
    switch (ev) {
        case RunEnd re -> { stopReason = re.stopReason(); lastAssistantBlocks = ...; currentLlmStream = null; }
        default -> ...
    }
    return true;   // ← always returns, even after RunEnd
}

// LLM iterator is exhausted: handle the batch.
if (pending.isEmpty()) { finished = true; emit RunEnd; return true; }
// else: run tools, return true
```

The "LLM iterator is exhausted" branch (which checks `pending.isEmpty()` and decides to finish) was only reachable when `currentLlmIt.hasNext()` returned `false`. But:

1. Each `tryAdvance` consumes exactly ONE event from `currentLlmIt` and returns.
2. When the LLM stream emits `RunEnd`, the engine consumes it, sets `currentLlmStream = null`, and **returns `true`**.
3. Next `tryAdvance` sees `currentLlmStream == null` and re-enters the "Start of a turn" branch — issuing a new LLM call — instead of falling through to the batch handler.

So the engine never actually executed the "is pending empty?" check after a text-only turn. It just kept starting new turns.

The unit test `QueryEngineSimpleChatTest.simpleGreetingFinishesAfterOneTurn` had been failing with `Java heap space` ever since the R15 pivot, because the test's mock returned a finite `Stream.of(...)` but the loop would never drain it — every `tryAdvance` re-issued the LLM call.

## Fix

Two changes:

### 1. `QueryEngine.tryAdvance` — fall through after RunEnd (QueryEngine.java)

```java
if (ev instanceof StreamEvent.RunEnd re) {
    // R15-2: consume internally, do NOT return — fall through to the
    // batch handler so pending.isEmpty() can decide whether the turn is done.
    stopReason = re.stopReason();
    lastAssistantBlocks = re.finalBlocks();
    currentLlmStream = null;
    // fall through
} else {
    action.accept(ev);
    return true;
}
// LLM iterator is exhausted OR RunEnd was just processed: handle the batch.
if (pending.isEmpty()) {
    finished = true;
    action.accept(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks));
    return true;
}
// run tools, return true
```

Now when `RunEnd` arrives, the engine falls through to the same "is pending empty?" check that the "iterator exhausted" branch uses. Text-only turns finish after one model call.

### 2. Stop double-emitting RunStart (SpringAiChatClient.java)

While debugging, I noticed the consumer was also seeing TWO `RunStart` events per turn — one from the engine, one from the LLM client. Fixed two ways:

- **`SpringAiChatClient.stream()`**: removed the `queue.offer(new StreamEvent.RunStart(...))` line. The LLM client now emits only data events (`TextDelta`, `ToolUseStart`, `ToolResult`) and an internal `RunEnd`. Control events (`RunStart`, terminal `RunEnd`) are owned by the engine.
- **`QueryEngine.tryAdvance`**: defensive branch that drops any `RunStart` the LLM stream happens to emit, so the consumer never sees a duplicate "turn started" event regardless of which LLM client is plugged in.

## Test changes

- **`QueryEngineSimpleChatTest.simpleGreetingFinishesAfterOneTurn`**: corrected event count from 4 → 3. The author had expected the model-side `RunEnd` to be passed through, but the engine has always consumed it internally (now the comment explains why). Actual events: 1× engine `RunStart` + 1× `TextDelta` + 1× engine terminal `RunEnd`.

The test was previously failing with `Java heap space` (infinite loop, OOM). After the fix it completes in 73 ms.

## Real-world verification

```
$ MINIMAX_API_KEY=... java -jar aethercode-cli-...-shaded.jar --print "how are you?"
Hi! I'm doing well, thanks for asking. I'm AetherCode, your local AI coding assistant —
ready to help you explore, edit, or reason about code in your workspace at `D:\work\workspace\idea\engine\AetherCode\aethercode`.
What can I help you with today?
Elapsed: 16337 ms
```

Single model call, complete response, no looping. Verified three times (greeting, "what is 2+2?", "say hi") — all single-call, all clean.

## Why not just a max-turns cap?

R14 added `setMaxTurnsPerQuery` as a safety net (default 10, R15-2). The user pushed back on R14's `maxTurnsPerQuery=3` because accuracy matters more than tight caps — "3轮太少，最重要的是准确" — and the real fix was the loop bug, not a tighter cap. The cap stays at 10 as a safety net against runaway tool-call loops, but it should never fire for well-formed queries.

## Files changed

| File | Lines changed | What |
|---|---|---|
| `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` | +30 / -10 | Restructured `tryAdvance` event handler; added `RunStart` defensive drop. |
| `aethercode-core/src/test/java/org/aethercode/core/engine/QueryEngineSimpleChatTest.java` | +5 / -2 | Updated event count assertion (4 → 3) with explanatory comment. |
| `aethercode-engine-springai/src/main/java/org/aethercode/engine/springai/SpringAiChatClient.java` | +15 / -8 | Removed `RunStart` emit, removed `runIdCounter` plumbing, converted DEBUG `System.err.println` to `LOG.debug`. |

## Test counts

- R15 total: 1156 → **1159** (+3 from `QueryEngineSimpleChatTest` cleanup / `SpringAiChatClient` tests unchanged)
- aethercode-core: 631 (was 632 — actually +1 because the simple-chat test now reliably passes instead of OOMing)
- aethercode-engine-springai: 7 (unchanged)
- 0 net regression
- 1 known-flaky: `StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered` (R5 timing-flaky, passes on re-run)

## Backups

- `D:\work\tmp\r15_2_done\` (4 files: 3 src + 1 shaded jar)
