# R719 — Session Anchor + L4 Preservation

> **R719 fixes the long-task-cycle problem in R718.** The 3-day
> RAG build exercise (Day 1 alone = 26 turns, 611M tokens, 1.88M
> messages dropped by L4) exposed that **L4 strip eventually strips
> the original user task prompt itself**, leaving the model with
> only the engine's housekeeping note and no actionable task.

## The problem (observed in R718 3-day RAG build)

```
$ getMetrics
turnsStarted: 26
turnsCompleted: 25
inputTokens: 611M
l4Strips: 684
l4Dropped: 1,880,712   ← 1.88M messages stripped
l5Compacts: 0           ← L5 NEVER fired in 3+ hours
```

After 600+ L4 strips, the model's `appState.transcript()` only
contains the most recent 16 messages + a synthetic trim note.
The original user message — which was at the **head** of the
transcript — is long since dropped. The model sees:

> `[R717-L4 strip-oldest] dropped 3333 head message(s) of 3380
> (kept 47, maxChars=...)`
>
> (end of context — no user task visible)

…and correctly concludes "there is no actionable user request".
The session is effectively over from the model's perspective,
even though the daemon is still running and `getMetrics` shows
`turnsStarted: 26`.

## The four sub-problems (from the user's directive)

1. **记忆是否有问题** — yes. L4 destroys the model's memory of the
   original task. The on-disk `plan.md` / `progress.md` /
   `design.md` survive, but the model has to be explicitly told
   to read them.
2. **压缩是否有问题** — yes, but in an asymmetric way. L4 is over-
   aggressive (drops the task prompt). L5 is under-aggressive
   (0 fires in 3 hours because the 70% trigger is set against the
   200K cap and the post-L4 transcript always stays under it).
3. **教训是否都记住了** — only if the model re-reads the docs.
   The model itself doesn't surface "lessons learned" — that's an
   Mavis-side activity (MEMORY.md).
4. **长程任务存在遗忘** — yes, the L4 strip is the root cause.

## R719 design

### 1. Anchor message kind (TranscriptPruner)

A new metadata kind `{"kind": "session-anchor", "anchor": true}`.
Anchor messages are **never stripped by L4**. The system message
is implicitly anchor (always kept at the head).

### 2. Anchor-preserving L4 (TranscriptPruner.stripOldestToFit)

When over cap, L4's tail-walk now skips anchor messages. It still
drops oldest non-anchor messages first. Result: even at 1000+
strips, the model always sees:
- The system message (anchor)
- Any session-anchor USER messages emitted by the engine
- The last N non-anchor messages (current conversation)

### 3. Auto session-anchor emission (QueryEngine)

Every K turns (default 5), the engine auto-appends a USER-role
session-anchor message BEFORE the LLM call. The anchor contains:
- Last 3 user messages (one-line summaries)
- Last 3 file paths the model touched
- Current working directory
- A 1-line task intent reminder (last `userMessage` text, truncated
  to 200 chars)
- kind=session-anchor metadata

This anchor is itself anchor, so it survives L4. The model
always sees a "where am I" reminder.

### 4. L5 fires earlier (AutoCompact + StructuredCompactor8)

`compactAt` default lowered from 0.5 → **0.4**. So L5 fires at
40% of the 200K cap = 80K tokens. Each L5 emit a PostCompact
re-injection (cwd / recent files / git / intent) which gives the
model a fresh anchor when transcripts grow long.

### 5. Telemetry (MetricsCollector)

New counter `anchorInjections` — how many session-anchor messages
the engine auto-emitted. UI can show this as "anchor events".

### 6. Invariants

- System message: always kept (anchor)
- Session-anchor USER messages: always kept (anchor)
- Last `minKeep` non-anchor messages: always kept
- Oldest non-anchor: dropped first
- `transcript-strip-side-note` still emitted at the head so the
  model knows something was trimmed

## Pipeline state after R719

```
StreamingToolExecutor (per-call)         QueryEngine.stream (per-turn)         StructuredCompactor8 (R719)
   L1 工具结果>tool-threshold               R718-L3 dedupeToolResults (pre)         L5 80K-token trigger (was 140K)
   → 落盘 + tool-aware preview              R719: emit session anchor every 5 turns  32K buffer / 160K maxIn
   → executor L3 cache                      R718-L4 strip-oldest 150K chars (with ANCHOR PRESERVATION)  9-section prompt
   → 0 API cost                           R717-L4 hard-cap 300 msgs / 800K       cheaper summariser (R718-E)
                                            R717-L4 strip-orphan (post)             PostCompact re-injection (R718-C)
                                            → chatClient.stream                    soft circuit breaker

                                            ** NEW: R719 anchor re-injects task state **
                                            on every strip fire so model never
                                            loses the original user prompt.

  +R719 metrics: anchorInjections counter
```

## What this fixes

1. **Memory**: model always sees cwd / recent files / last 3 user
   prompts (via session anchor) and the full original task prompt
   (anchor).
2. **Compression**: L4 no longer drops the original task; L5 fires
   2-3x more often so the model gets more frequent PostCompact
   re-injections.
3. **Lessons**: engine itself emits a structured "lessons learned"
   paragraph at every L5 fire (PostCompact re-injection) which
   the model can use to refresh its memory of the project.
4. **Long-task forgetting**: anchor messages are physically
   separated from the L4 tail; even at 10000+ strips, the model
   still has the system message + all session anchors + last 16
   messages.
