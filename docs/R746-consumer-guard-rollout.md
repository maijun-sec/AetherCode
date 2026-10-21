# R746 — consumer-guard rollout: 3 high-value sites + 8 stream closes

**Date:** 2026-10-21
**Predecessor:** R745 (`docs/R745-consumer-side-terminal-contract.md` §4 audit table),
R744 (`docs/R744-silent-death-4th-path.md`)

## What this round is

R745 fixed the two JSON-RPC consumption points (`AetherCodeMethods.query`,
`EngineContinuationDispatcher`). Its §4 audit table listed the rest of the
repo's stream consumers. This round does the remaining high-value three
(**R746**) and the eight pure-leak closes (**R746b**).

The common defect in all eleven sites: `hasNext()` on a Spliterator-backed
stream is **itself a blocking read**. A bare `forEach` / `while (it.hasNext())`
therefore has no upper bound — a producer whose backing queue never receives
another element parks the consumer thread forever, and nothing throws.

## 1. The shared helper — `StreamConsumerGuard`

`aethercode-core/src/main/java/org/aethercode/core/stream/StreamConsumerGuard.java`

R745's probe + terminal-event logic lived as two private static methods inside
`EngineContinuationDispatcher`. R746 extracts them so five sites share one
implementation rather than copying the probe-thread dance five times.

```java
public final class StreamConsumerGuard {

    public static final String TIMEOUT_ENV = "AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS";
    public static final long   TIMEOUT_MS_DEFAULT = 180_000L;

    /** production callers pass timeoutMs(); tests pass 2s */
    public static Result consume(String site,
                                 Iterator<StreamEvent> it,
                                 Consumer<StreamEvent> onEvent,
                                 String runContext,
                                 long timeoutMs);

    /** R745-compatible two-valued view used by the dispatcher */
    public static boolean awaitNextEvent(Iterator<?> it, long budgetMs);

    public record Result(Outcome outcome, int eventCount, boolean sawRunEnd,
                         String stopReason, StreamEvent.RunEnd syntheticRunEnd) {
        boolean stalled();
        boolean needsSyntheticRunEnd();
    }

    public enum Outcome {
        COMPLETED_WITH_RUN_END,   // happy path; syntheticRunEnd == null
        EXHAUSTED_WITHOUT_RUN_END,// drained w/o RunEnd; synthetic minted
        STALLED,                  // wedged producer; consumer_timeout minted
    }
}
```

### Design decisions worth defending

**One knob, not four.** `TIMEOUT_ENV` is R744's
`AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS` with R744's 180s default. A new
per-consumer knob would mean an operator who tuned R744 silently fails to
tune this. A malformed override falls through to the default, for the same
reason R744 does it: a typo in an env var must never be able to disable the
guard and reintroduce the wedge.

**`Outcome` is three-valued, not boolean.** The distinction between *stalled*
and *drained cleanly without a terminal event* is the whole subtlety. Folding
them together (R745's `awaitNextEvent` shape) means either a healthy stream
gets a fabricated `consumer_timeout`, or a wedged stream is reported as a
clean drain. `consume` therefore loops on the three-valued private
`probeHasNext`, not on the two-valued `awaitNextEvent`.

**The count rides in the stopReason.** `consumer_timeout: no stream event
within 2000ms (3 event(s) received)` — not just in the LOG line. The callers
that surface a stall to a downstream waiter (the subagent orchestrator's error
`Result`, the CLI's stderr line) need to say how far the run got; a stopReason
that omits it forces them to log-and-scrape.

**`EngineContinuationDispatcher` was NOT rewritten.** The task explicitly
forbids touching it (R745 is done; rewriting invites regression), but also
asks all five sites to share the helper. Reconciled by replacing only the two
helper BODIES with one-line delegations to `StreamConsumerGuard`, leaving the
thread body, span teardown, TUI notification, and the `sawRunEnd` /
`consumer_timeout` / `no_run_end` branches exactly as R745 wrote them. All
four R745 source pins still pass unmodified.

## 2. The three high-value sites (R746)

| Site | Pre-fix | Post-fix |
|---|---|---|
| `SubagentOrchestrator:84` | `child.query(t).forEach(...)` | guard + try-with-resources; **stall ⇒ `Subagent.Result.error(...)`** |
| `AgentTool:551` | `engine.query(...).forEach(...)` | guard + try-with-resources; **stall ⇒ thrown, so the existing `RetryHelper` retries then errors** |
| `SddOrchestrator:602` | `for (var ev : (Iterable<StreamEvent>) stream::iterator)` | guard + try-with-resources; **stall ⇒ `SddException(PHASE_TIMEOUT)`** |

### Why `SubagentOrchestrator` is the sharpest one

It runs an **unattended child engine**. Nothing else drives it, and a wedged
child does **not** throw back to the parent thread — the parent's
`delegate(...)` simply never returns, and the parent agent waits on
`subagent_status` with no error path and no timeout. Here the bounded wait is
not optional insurance; it is the *only* mechanism by which the parent learns
the child died. Hence the stall is surfaced as an error `Result` (carrying the
stopReason and the event count), never as "empty output".

`AgentTool` and `SddOrchestrator` both already had an outer failure path
(`RetryHelper`'s 3 attempts; `future.get(10min)` → `PHASE_TIMEOUT`), so a
stall is converted into that existing path rather than inventing a new one.

### LOG markers

```
R746: subagent-orchestrator consumer exited without RunEnd (…)
R746: subagent-orchestrator consumer stalled            (… )
R746: agent-tool consumer exited without RunEnd (…)
R746: agent-tool consumer stalled            (… )
R746: sdd-orchestrator consumer exited without RunEnd (…)
R746: sdd-orchestrator consumer stalled            (… )
R746: cli-headless consumer exited without RunEnd (…)
R746: cli-headless consumer stalled            (… )
```
(the `stalled` and `exited without RunEnd` forms are emitted by the shared
helper with the site label as its first token; the `subagent-orchestrator`
stall line is additionally logged by the site itself with the agent name.)

## 3. The eight close-only sites (R746b)

try-with-resources only. **No timeout, no RunEnd guard.** These are in-process
synchronous calls where a wedged provider throws a stack rather than parking
silently, so a bounded wait would be cost without benefit.

| File | Line (pre-fix) | Consumer |
|---|---|---|
| `StructuredCompactor8.java` | 293 | 8-section summarisation |
| `StructuredCompactor.java` | 173 | 7-section summarisation |
| `CompactGate.java` | 162 | gate summary |
| `MemoryExtractor.java` | 186 | memory extract subagent |
| `MemoryLifecycle.java` | 637 | strategy chat |
| `PlanExecutor.java` | 355 | plan step executor |
| `SwarmCoordinator.java` | 61 | teammate task |
| `Main.java` | 332 | CLI `--print` — **promoted, see §4** |

## 4. `Main:332` — promoted out of the close-only batch

**Decision: yes, bounded wait added.**

`--print` is the unattended automation entry point — the caller is a script or
CI job. Pre-fix `engine.query(input).forEach(...)` parked in `hasNext()`
forever if the producer wedged, and nothing rescued it:

- no session lock to release (unlike the dispatcher),
- no desktop to paint a red banner (unlike the daemon),
- `System.exit(code)` at `Main.java:231` is only reached **after**
  `runHeadless` returns — so the process never exits.

The judgement call: Ctrl-C interrupts the main thread, but a thread parked in a
blocking queue read does not reliably observe the interrupt, so the documented
"Ctrl-C and the process exits" contract degrades to "Ctrl-C and the process
hangs". A stall now returns exit code **1** and prints the stopReason to
**stderr** (`--print`'s stdout is machine-parsed, so the marker must not be
injected into it).

Unlike the other three, this site is *inbound* — the user is present and the
whole process is the consumer — so there is no downstream waiter to leave a
terminal event for. The LOG marker + exit code are the signal.

Note `AgentTool.callSingleShot:418` also has an unbounded `forEach`, and was
**not** in the audit table; it is left alone as out of scope for this round.

## 5. Verification

### Tests

`StreamConsumerGuardR746Test` (7 cases) — the guard itself:

- a stream ending in `RunEnd` → `COMPLETED_WITH_RUN_END`, no synthetic event,
  and it must **not** pay the timeout;
- clean exhaustion without `RunEnd` → synthetic `no_run_end` terminal event;
- `hasNext()` parked forever → `STALLED` + `consumer_timeout` stopReason;
- **clean exhaustion is NOT a stall** (the zero-element iterator case — the
  dangerous confusion, since treating it as a stall fabricates a timeout for
  every healthy empty stream);
- stall *after* real progress preserves the event count;
- default timeout == R744's 180s and the env var name is shared;
- `awaitNextEvent` keeps R745's two-valued shape.

`SubagentOrchestratorR746Test` (5 cases) — including the key one,
`wedgedChildSurfacesAsError`: a child that streams one event then wedges must
come back as an error `Result` within the budget, so the parent is not left
waiting forever.

```
Tests run: 5,  Failures: 0, Errors: 0  -- SubagentOrchestratorR746Test
Tests run: 11, Failures: 0, Errors: 0  -- SubagentOrchestratorTest   (pre-existing)
Tests run: 7,  Failures: 0, Errors: 0  -- StreamConsumerGuardR746Test
Tests run: 23, Failures: 0, Errors: 0
```

### Mutation testing

| Mutation | Pre-fix semantics restored | Result |
|---|---|---|
| **A** | guard reports a stall as a clean drain (what an unbounded `while (it.hasNext())` effectively sees) | `Tests run: 12, Failures: 4` |
| **B** | no synthetic terminal event on drain-without-RunEnd | `Tests run: 12, Failures: 1` |
| **C** | `SubagentOrchestrator` back to bare `child.query(t).forEach(...)` | **suite hung — killed at the 600s tool timeout** |

Mutation A's four reds:
`StreamConsumerGuardR746Test.wedgedIteratorStalls`,
`StreamConsumerGuardR746Test.stallAfterProgressPreservesCount`,
`SubagentOrchestratorR746Test.wedgedChildSurfacesAsError`,
`SubagentOrchestratorR746Test.immediatelyWedgedChildSurfacesAsError`.

Mutation C is the strongest evidence of the whole round: reverting the
orchestrator does not produce a failing assertion, it produces a **hung test
suite** — which is precisely the user-visible symptom R746 fixes.

### Two defects the tests caught during the round

1. `awaitNextEvent` initially returned `true` for a drained iterator
   (`!= STALLED`). That contradicts its own contract and would have made the
   R745 dispatcher call `it.next()` on an exhausted iterator, throwing
   `NoSuchElementException` out of its loop body. Fixed to
   `== HAVE_MORE` **before** shipping; the dispatcher's behaviour is
   unchanged (it only ever called `next()` when `true`, and a wedged stream
   still answers `false`).
2. The stall `stopReason` originally omitted the event count. Added
   `(N event(s) received)` so the callers that surface a stall can say how far
   the run got.

### Jar verification

See §6 for the SHA256 + marker grep.

## 6. Deploy

*(filled in at package time — see the round summary)*