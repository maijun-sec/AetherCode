# R744 — the fourth silent-death path: an unbounded first-chunk read

**Date:** 2026-10-21
**Incident:** daemon PID 23280, session `default.jsonl`, run-1
**Status:** fixed, deployed, verified in the shipped jar
**Class:** `aethercode-engine-springai` (root cause) + `aethercode-core` (contract documentation)

---

## 1. Summary

The reported symptom — "Spliterator permanently blocked in `queue.take()`, no RunEnd, green
`● Idle`" — was **not** what actually happened. There was no fourth missing
`(RunEnd, POISON)` pair. The run never terminated at all, and it never reached any
terminal branch.

The root cause is a single unguarded blocking socket read:

**`SpringAiChatClient.java:252` (pre-fix), inside the R697 retry loop:**

```java
while (attempt < 3) {
    attempt++;
    try {
        reactor.core.publisher.Flux<ChatResponse> flux = chatModel.stream(prompt);
        it = flux.toIterable().iterator();
        it.hasNext();            // ← the first blocking network read of the entire call
        lastErr = null;
        lastBody = null;
        break;
    } catch (WebClientResponseException wce) { ...sleep, retry... }
      catch (Throwable t)        { ...sleep, retry... }
}
```

On a `toIterable()` iterator, `hasNext()` is not a local buffer check — it is a
**blocking read of the SSE response body**. A provider that accepts the TCP connection
and the HTTP request, and then never finishes the response body, produces **no
exception at all**. The read simply parks on the socket, indefinitely, on a daemon
thread. Nothing throws, nothing returns, nothing is logged.

### Live evidence (not inferred)

The daemon was still in the failed state when the investigation began, so the hang was
confirmed directly rather than guessed at:

```
PID 23280 ALIVE: CPU=56.6s WS=1238MB threads=52 start=10/20/2026 23:46:37
```

`jstack -l 23280` on that live process:

- **No thread anywhere in the JVM had an `org.aethercode` frame inside
  `QueryEngine` or `SpringAiChatClient`.** The only `org.aethercode` frames present
  were the daemon's own infrastructure loops (`ConcurrencyController.monitorLoop`,
  `RulesWatcher.run`, `ConfigWatcher.run`, `RegistryReloadService.watchLoop`).
- **No thread was blocked in `LinkedBlockingQueue.take()`** other than the three
  `AbstractWatchService.take()` filesystem watchers.
- The `springai-runner` thread had already exited.

That last point is the important one, and it is what refuted the initial hypothesis.
If the bug were the R738/R742 shape (RunEnd offered, POISON missing), we would have seen
a `springai-runner` thread alive-but-idle and the consumer parked in `queue.take()` with
a RunEnd sitting unread in the queue. We saw neither. The runner was **gone** and the
consumer **did not exist** — because the engine's Spliterator had never been entered for
this turn.

Combined with the log (which ends at the last pre-call line, `R736: stripOrphanToolResults
repaired transcript of 114 messages -> 96`, and is then silent for 11 minutes), the
picture is unambiguous: the process was blocked *before* the engine could observe
anything, inside the chat client's own call setup.

---

## 2. Why R735 / R735a / R738 / R742 all missed it

Each of those rounds fixed a real bug. None of them could have caught this one, because
this one has a different shape:

| Round | Bug it fixed | Why it is blind to R744 |
|---|---|---|
| **R738** | `catch (Exception e)` around the streaming loop offered a RunEnd but no POISON → Spliterator parked in `queue.take()` with the RunEnd unread. | R738's bug requires the call to *reach* the streaming loop and *fail inside it*. R744 never enters the streaming loop — it parks one statement earlier. |
| **R742** | `if (it == null)` (all 3 R697 retries exhausted) offered a RunEnd but no POISON. | R742's branch is only reachable if the R697 loop **exits**. A parked `it.hasNext()` means the loop never exits, so `it == null` is never evaluated. R742's `(RunEnd, POISON)` pairing is correct and still is — R744 just never gets there. |
| **R735** | `same_error_hard_stop` branch had no `StreamEvent.RunEnd`. | Audits terminal branches the engine *reaches*. R744 reaches none. |
| **R735a** | Wrapped `tryAdvance` in `catch (Throwable t)` → `RunEnd(stopReason="runtime_exception: …")`. | **The decisive miss.** R735a translates a *throw*. R744 produces no throw. The engine is blocked *downstream* inside `currentLlmIt.next()`, so `tryAdvance` is not failing — it is simply still running. The catch is unreachable. |

The generalisable lesson: **R735/R735a/R742 are all `catch`-shaped or
`offer-pairing`-shaped contracts. They cover every way the run can fail *loudly*. None
of them covers a run that fails by never progressing at all.** A blocking read on a
network socket is invisible to every one of them, because from the JVM's point of view
a thread parked in `read()` is a perfectly healthy thread.

This is also why the required "add a catch" instinct would not have helped here: there
is nothing to catch.

---

## 3. The fix

### 3.1 Bound the first-chunk read (`SpringAiChatClient`)

The unguarded `it.hasNext()` is replaced by `awaitFirstChunk(it)`, which runs the
blocking read on a short-lived daemon probe thread and `join()`s it against a deadline:

- `FIRST_CHUNK_TIMEOUT_MS_DEFAULT = 180_000` (180s), overridable per-JVM via
  `AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS`. The override is **validated**: a blank,
  malformed (`NumberFormatException`), or non-positive value falls back to the default,
  so a typo cannot silently reopen the hole or instantly break every call.
- 180s is comfortably longer than the observed cold-start TTFB on a 150K-char
  transcript (~40-90s), so a healthy call is never cut short.
- The probe thread is a daemon, so a genuinely wedged provider cannot keep the JVM
  alive.
- An interrupt (R721 cancel) propagates as `InterruptedException` so the R697 ladder
  restores the interrupt flag and bails — cancel remains a working escape hatch.

On expiry the attempt is **not** silently retried in place: the parked iterator is
dropped (`it = null`), a `TimeoutException` is recorded in `lastErr`, and control falls
through to the R697 retry ladder. When all three attempts stall, we reach the existing
`if (it == null)` branch — which R742 already hardened to offer `(RunEnd, POISON)`
together.

**R744 therefore reuses the existing terminal contract rather than inventing a
parallel one.** That is deliberate: one way for a run to end is easier to keep correct
than several.

### 3.2 Document the reachability gap (`QueryEngine`)

The engine's code was already correct — all four of its terminal branches emit a
RunEnd. What was missing was any statement that the outer catch **cannot** be relied on
alone. `QueryEngine`'s R735a catch now carries an explicit R744 marker saying so, so
the next engineer reading it learns the boundary instead of re-deriving it from an
11-minute hang.

---

## 4. Why this time the fix is actually pinned

Three properties, each verified rather than assumed:

**(a) Behavioural, not just source-pin.** R738's test passed against R742's
still-broken branch because it asserted a token appeared *somewhere* in the file. The
R744 test drives `awaitFirstChunk` with a **real stalling iterator on a real thread** —
one whose `hasNext()` parks forever — and fails if the call does not return. This is the
actual bug reproduced in miniature.

**(b) Mutation-tested.** The guard was deliberately reverted
(`if (Boolean.TRUE) { it.hasNext(); return true; }`, restoring the pre-fix blocking
semantics) and the suite re-run:

```
Tests run: 7, Failures: 2
  stalledFirstChunkReturnsInsteadOfBlockingForever:197
    R744: awaitFirstChunk blocked on a stalling iterator for more than 20000ms.
    This is the silent-death bug: the engine would stay parked inside
    currentLlmIt.next() forever, with no RunEnd, no POISON, and a green ● Idle in the UI.
  interruptPropagatesSoCancelStillWorks
    R744: an interrupt during the first-chunk wait must surface as an exception,
    otherwise R721's cancel RPC cannot interrupt a stalled read and the user has no
    escape hatch
```

The mutation was then reverted; no residue remains (verified by negative-control grep
against the shipped jar).

**(c) Verified in the artifact, not just the build.** `mvn clean package` reported
`BUILD SUCCESS`, and then the compiled constant pools were grepped directly.

---

## 5. A regression this round caused, and fixed

Worth recording because it is the same *class* of mistake as R738's test.

While documenting the fix, the R735a comment quoted the stopReason the catch produces.
`QueryEngineR735Test` audits the **raw source text** for `stopReason = "..."`, so it
matched the prose, found no RunEnd after it, and went red:

```
QueryEngineR735Test.everyTerminalBranch_emitsRunEnd:
  R735: stopReason="runtime_exception: …" terminal branch must emit StreamEvent.RunEnd …
```

The engine was fine; the *comment* was shaped like code. Two lessons, both now enforced
by `QueryEngineSilentDeathR744Test.noCommentShapedStopReasonDecoysBreakTheR735Audit`:

- **Lesson 1057:** never reproduce an assignment's exact shape inside a comment in a
  file that is scanned by a source-pin test. Describe it in prose instead.
- The new guard test polices this in **both** directions — it caught the decoy in
  `QueryEngine.java` and then, correctly, caught a second decoy I had written into the
  guard test's own javadoc.

---

## 6. Verification

### 6.1 Tests

| Suite | Tests | Result |
|---|---|---|
| `QueryEngineSilentDeathR744Test` (new) | 7 | pass |
| `SpringAiChatClientR744Test` (new) | 7 | pass |
| `aethercode-core` (full module, clean) | **1468** | **0 failures** |
| `aethercode-engine-springai` (full module, clean) | 25 | 0 failures |

The four termination paths required by the task are covered in
`QueryEngineSilentDeathR744Test`:

1. `cancelled` (R721 hard cancel) — emits RunEnd
2. `same_error_hard_stop` (R181 death spiral) — emits RunEnd with `lastAssistantBlocks`
3. `awaiting_user_decision` (TodoRunController AwaitUser) — emits RunEnd
4. `runtime_exception` (R735a outer catch) — verified to `action.accept` a RunEnd
   *inside* the catch, not merely to assign `stopReason`

Plus the R735a-style throw test (`outerCatch_translatesThrowIntoRunEnd`) and two
structural pins (the catch carries the R744 marker; no comment-shaped decoys).

### 6.2 Pre-existing failures — explicitly cleared

`AGENTS.md` records `QueryEngineAnchorR726Test` as known-flaky. During this work
`QueryEngineAnchorR726Test`, `WorkflowExecutorTest` and `WorkflowExecutorR375Test`
also went red in full-module runs. **All were proven pre-existing**, not caused by this
change:

- Baseline run with **all R744 changes stashed** (and the R744 test files not on disk):
  **1467 tests, 1 failure** (`WorkflowExecutorR375Test.parallelStepFansOutBranches`, a
  timing/ordering assertion).
- With R744 applied, full clean run: **1468 tests, 0 failures**.
- The three tests that flaked pass deterministically in isolation both with and without
  the R744 changes, and neither references `SpringAiChatClient`, `awaitFirstChunk`, or
  any R744 symbol.

The failing test *class* moved between runs (`WorkflowExecutorR375Test` →
`QueryEngineR735Test`, the latter being the comment-decoy regression of §5) — that
ordering sensitivity is itself the evidence that these are parallel-execution flakes,
not a property of the change.

### 6.3 Jar marker grep (Lesson 1053: BUILD SUCCESS ≠ patch shipped)

`javac` strips comments, so the markers checked are identifiers and string literals that
genuinely reach the class constant pool. An initial check against
`QueryEngine.class` reported MISS for everything — including `R735a` and
`runtime_exception` — which turned out to be **correct behaviour, not a stale jar**: the
`tryAdvance` body lives in the anonymous inner class `QueryEngine$1.class`. Verified by
SHA256 that the jar's copy is byte-identical to the freshly compiled module output.

```
JAR : …\aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT.jar   (56,857,097 bytes)

org/aethercode/core/engine/QueryEngine$1.class
  tryAdvance                                 HIT   (the Spliterator method)
  runtime_exception                          HIT   (R735a catch stopReason)
  same_error_hard_stop                       HIT   (R181 hard stop)
  awaiting_user_decision                     HIT   (TodoRunController AwaitUser)
  cancelled                                  HIT   (R721 cancel)
  currentLlmIt                               HIT   (the downstream iterator R744 was blocked in)

org/aethercode/engine/springai/SpringAiChatClient.class
  awaitFirstChunk                            HIT   (R744 bounded first-chunk read)
  firstChunkTimeoutMs                        HIT   (R744 validated budget)
  AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS     HIT   (R744 env override)
  springai-first-chunk-probe                 HIT   (R744 probe thread)
  R744: Spliterator would block forever      HIT   (R744 stall LOG.warn)
  no first SSE chunk within                  HIT   (R744 TimeoutException message)

--- negative controls (must be absent) ---
  R744-MUTATION                              absent (good)
  inject_decoy                               absent (good)

R744 JAR VERIFICATION: PASS — patch shipped
```

### 6.4 Desktop deploy

```
SRC  SHA256: 82C8999A82353D2A81712166A8610B104FA79FA81E0524BCB21C765D5C1B0821
DST  SHA256: 82C8999A82353D2A81712166A8610B104FA79FA81E0524BCB21C765D5C1B0821
MATCH: True
```

Desktop jar marker grep re-run independently: `awaitFirstChunk`, `firstChunkTimeoutMs`,
`AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS`, `R744: Spliterator would block forever` — all
HIT.

---

## 7. Deployment note

The deployed daemon still runs the old jar until the desktop restarts it (it keys off
the jar's mtime, and the copy above changed it). PID 23280 is still alive in the
stalled state and can be killed; the fix takes effect on the next daemon start.

## 8. Operational guidance

- **To tune:** set `AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS` (milliseconds). Blank,
  malformed, or non-positive values fall back to 180000.
- **To confirm the guard is live** on a future incident, look for in the daemon log:

  ```
  R744: Spliterator would block forever — the provider accepted the request but sent
        no data after <N>ms. Abandoning the probe thread and letting the R697 retry
        ladder decide.
  R697-retry: chat call attempt N stalled waiting for the first stream chunk after <N>ms
  R697: all 3 attempts failed. Final: error: TimeoutException: R744: no first SSE chunk …
  ```

  The first line is the definitive marker. Its presence means the run was saved rather
  than lost, and it will surface as a `RunEnd` on the wire.
- **If a stall is still suspected**, `jstack -l <pid>` and look for
  `springai-first-chunk-probe` (parked, bounded — will exit) versus `springai-runner`
  parked in `awaitFirstChunk`'s caller (should now be impossible).

## 9. Files changed

| File | Change |
|---|---|
| `aethercode-engine-springai/…/SpringAiChatClient.java` | +174/−1 — `awaitFirstChunk`, `firstChunkTimeoutMs`, `FIRST_CHUNK_TIMEOUT_MS_DEFAULT`, bounded read in the R697 loop |
| `aethercode-core/…/QueryEngine.java` | +42/−4 — R735a catch: R744 reachability-gap marker + comment-shape advisory (no behavioural change) |
| `aethercode-core/…/QueryEngineSilentDeathR744Test.java` | new, 7 tests |
| `aethercode-engine-springai/…/SpringAiChatClientR744Test.java` | new, 7 tests |

Total: 210 insertions, 6 deletions across 2 source files, plus 2 new test files.
