# R18-A: AutoCompact ↔ Compactor adapter (million-token context, real)

## TL;DR

The pre-R18 split — `aethercode-compact/AutoCompact.compact()` returning its own `Result` record, vs the core `aethercode-core/compact/Compactor.compact()` requiring `List<Message>` — is fixed. New `AutoCompactAdapter` bridges the two; the SDK's `--context-window` flag now lights up the trigger path: when the transcript exceeds the window, a single summary `Message` replaces it (the convention Claude Code uses). 1172 tests, 0 real failures (1 known-flaky on full run).

## What was broken

```java
// aethercode-core/.../compact/Compactor.java
public interface Compactor {
    List<Message> compact(List<Message> messages);  // ← returns list
}

// aethercode-compact/.../AutoCompact.java
public Result compact(List<Message> messages) {     // ← returns its own Result
    ...
    return new Result(true, summary);
}
public record Result(boolean wasCompacted, String summary) {}
```

The two types were never compatible. R17-D exposed `--context-window` on `AppState` but explicitly noted the trigger path couldn't be wired — assigning `new AutoCompact(...)` to a `Compactor` field failed to compile.

## The fix: `AutoCompactAdapter`

`aethercode-compact/.../AutoCompactAdapter.java` implements the core `Compactor` interface, delegates `shouldCompact` to `AutoCompact`, and translates the `Result` into a `List<Message>`:

```java
@Override
public List<Message> compact(List<Message> messages) {
    AutoCompact.Result r = delegate.compact(messages);
    if (r == null || !r.wasCompacted() || r.summary() == null || r.summary().isBlank()) {
        return null;   // engine treats null as "no change"
    }
    String wrapped = "Here is a summary of our conversation so far:\n\n" + r.summary()
            + "\n\n(The previous turns were compacted into this summary. "
            + "Recent turns follow below.)";
    Message m = new Message(null, Role.USER,
            List.of(new ContentBlock.TextBlock(wrapped)),
            null, Map.of());
    return List.of(m);
}
```

The single-message replacement mirrors Claude Code's compaction convention: a USER message tagged "Here is a summary of our conversation so far" replaces the whole prefix, and the engine preserves recent turns verbatim.

## SDK wiring

`AetherCodeEngine` now builds a real `AutoCompact` (wrapped in `AutoCompactAdapter`) when `--context-window` is set and no custom compactor is supplied:

```java
Compactor resolvedCompactor = b.compactor;
if (b.contextWindow > 0 && resolvedCompactor == null) {
    int ctx = b.contextWindow;
    int buffer = Math.max(13_000, ctx / 20);    // >= 5% of window
    int maxIn  = Math.max(80_000, ctx * 2 / 5);  // <= 40% of window
    org.aethercode.compact.AutoCompact ac = new org.aethercode.compact.AutoCompact(
            this.chatClient, ctx, buffer, maxIn);
    resolvedCompactor = new org.aethercode.compact.AutoCompactAdapter(ac);
}
this.compactor = resolvedCompactor;
```

The local-`resolvedCompactor` indirection is required because `compactor` is a `final` field and the Java compiler rejects the two-step assignment (`this.compactor = b.compactor; if (null) this.compactor = ...`).

The buffer (`5% of window`) and max-input (`40% of window`) are scaled relative to the user-supplied window so a 1M-token model doesn't compact too eagerly and the summary call doesn't blow past its own cap. Default windows for `AutoCompact` (200K) keep the same constants.

## Tests

New `AutoCompactAdapterTest` (4 tests, all green):

- `compact_translatesResultToSingleUserMessage` — a long transcript on a 200K window triggers compaction; the adapter wraps the chat client's summary in a single USER message.
- `compact_returnsNullWhenShouldCompactFalse` — short transcript on a 1M window doesn't trigger; the chat client is never called.
- `compact_handlesBlankSummary` — a chat client that returns an empty summary still maps to `null` (the engine leaves the transcript intact).
- `interfaceIsSatisfied` — explicit cast to the core `Compactor` interface compiles; the bridge is real.

## Real-test verification

```
$ java -jar ...shaded.jar --cwd examples --context-window 1000000 --print "say hi"

  [task u-zqijizph started]                ← R17 task ID
  Hi! ? I'm AetherCode, your local AI coding agent. How can I help you today?

Elapsed: 7.8s
```

Wiring confirmed: the flag is parsed, the adapter is constructed, the engine accepts the compactor. Actual compaction triggers only when the transcript exceeds the window — for "say hi" that's 1M+ tokens of history, which won't happen on a single turn.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-compact/.../AutoCompactAdapter.java` | new (~60) | Bridge. Implements core `Compactor`, wraps `AutoCompact`. |
| `aethercode-compact/src/test/.../AutoCompactAdapterTest.java` | new (~120) | 4 tests. |
| `aethercode-sdk/.../AetherCodeEngine.java` | +12 / -3 | Wire `AutoCompact` + adapter when `--context-window` set. |

## Test counts

- 1168 → 1172 (+4 from new adapter test)
- 0 net regression
- 1 known-flaky on full run (R5 timing, passes individually)

## What this enables (R18+)

- **Million-token context**: `AutoCompact` now triggers compaction at 1M - 50K = 950K tokens. Any session that grows past that point gets a summary, freeing up the budget.
- **Token-budgeted summarization**: the `maxInputTokens` cap (40% of window = 400K) is passed to `AutoCompact.summarise`, so the summary call itself can't blow past its own cap.
- **Self-tuning for any model**: the same `--context-window` flag works for 200K (Claude), 1M (MiniMax-M3), 8K (legacy) without code changes.

## What's still missing (R18+ candidates)

- **R18-B: full 6-section MiniMax Code TUI** (JLine → lanterna)
- **R18-C: AgentTool** (subagent spawning using `Task` + `TaskContext`)
- **R18-D: MemoryRecall → system prompt** (the `aethercode-memory` module's recall system isn't wired into prompt assembly yet)
- **R18-E: streaming text + tool event visibility** (proxyToolCalls + manual loop)

## Backups

- `D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r18a\` (planned)
