# R741 / R742 / R743 — latent bug round after R720–R740

**Date:** 2026-10-20
**Scope:** discovery + fix only. R740's `emitSubTaskTransitions` flat-todo branch,
`runPreFlightCompact` (R720) and the cancel-via-thread-interrupt path (R721) are untouched.
**No protocol surface change:** no new RPC method, no new wire event type. R743 only
re-routes classification of *existing* stopReason strings.

---

## Summary

Three latent bugs, each a new instance of a Lesson the previous rounds had
already paid for:

| R | Layer | Bug class | Lesson reused |
| --- | --- | --- | --- |
| R741 | `aethercode-core` — transcript pruning | adjacency invariant broken by a newer pass | Lesson 1054 (adjacency, not id existence) |
| R742 | `aethercode-engine-springai` — chat client | terminal event emitted, consumer never reads it | Lesson 1052 (produced ≠ delivered); R738's own sibling |
| R743 | `aethercode-hooks` + `aethercode-protocol` | exact/prefix string matching across the wire, in 4 places | R735b + R739 |

Total: **6 new test classes, 32 new tests**, plus 1 pre-existing test updated
because R741 inverts the contract it asserted.

---

## R741 — L3 dedupe stub rewrites `TOOL_RESULT` into a `USER` message

**File:** `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/TranscriptPruner.java`
**Function:** `buildDedupeStub` (called from `dedupeToolResults`, the R718-L3 pass)

### Root cause

```java
// pre-R741
return new Message(
        "r718-l3-" + System.currentTimeMillis() + "-" + firstIdx,
        Role.USER,                                    // ← hard-coded
        List.of(new ContentBlock.TextBlock(text)),
        null, meta);
```

The provider's tool-call contract is an **adjacency-preserving batch** (Lesson 1054):
the `tool_use` ids surviving on an assistant must equal the ids of the tool_results in
the *immediately contiguous* tool-result batch that follows, with no non-tool message
between. R736 spent three rounds teaching `stripOrphanToolResults` to honour that.

R718-L3 predates that lesson and violates it. When the same `tool_use_id` appeared
twice, the second result was replaced by a **`Role.USER` side-note sitting in the
middle of the batch**. A USER message TERMINATES the contiguous batch, so the engine
ends up sending `assistant(tool_use × N)` followed by fewer than N tool_results →
`400 invalid params, tool result's tool id (call_X) not found (2013)` from
`api.minimaxi.com` (or the bare `500 unknown error (1000)` from the parallel fallback).

Empirically confirmed pre-fix, via a throwaway probe:

```
R741 L3 stub role = USER | assistant declares 2 tool_use, tool_results after = 1
```

**And R736 cannot repair it.** `stripOrphanToolResults` only ever *drops* messages, so
it can never re-create the tool_result the stub swallowed. The probe confirmed the
broken shape passes straight through the strip untouched. The fix therefore has to live
in L3, not in the repair pass.

### Fix

The stub is now **role-preserving** and carries a `ToolResultBlock` with the *same*
`tool_use_id`, so it still counts as a legitimate answer to the declaration. Only the
body shrinks:

```java
String toolUseId = lookupToolUseId(original);
ContentBlock body = toolUseId != null
        ? new ContentBlock.ToolResultBlock(toolUseId, text, false)
        : new ContentBlock.TextBlock(text);
return new Message(..., original.role(), List.of(body), null, meta);
```

`original.role()` rather than a hard-coded `Role.TOOL_RESULT`, so the helper stays
correct if a future caller reuses it for another role.

### Verification

`TranscriptPrunerDedupeAdjacencyR741Test` — 6 **behavioural** tests (not source-pins):
stub role preserved, stub carries the original `tool_use_id`, declared-count ==
result-count after L3, R736 provably cannot repair the shape, same-reference fast path
preserved, and a source-pin that the helper uses `original.role()` (comments stripped
before the token scan so the rationale prose can't satisfy or defeat it).

`TranscriptPrunerR718Test.l3_dedupe_identicalToolResults_collapsed` was **updated** —
it cast the stub body to `TextBlock`, i.e. it asserted the exact contract R741
inverts. It now accepts either block shape and additionally pins the role and id.

---

## R742 — retry-exhaustion branch offers a `RunEnd` with no `POISON`

**File:** `aethercode/aethercode-engine-springai/src/main/java/org/aethercode/engine/springai/SpringAiChatClient.java`
**Function:** `runCall`, the `if (it == null)` branch

### Root cause

R738 fixed the `catch (Exception e)` mid-iteration branch: offer a terminal `RunEnd`,
then `return;` — which skips the `queue.offer(POISON)` that the `Thread runner` lambda
only reaches on a *normal* return from `runCall`. The Spliterator then blocks forever
on `queue.take()`, the `RunEnd` sits unread in the queue, and the desktop sits in a
misleading green `● Idle` with no error banner and no ↻ retry.

R742 is the **same bug in the branch R738 didn't touch**:

```java
if (it == null) {                                   // all 3 R697 retries exhausted
    queue.offer(new StreamEvent.RunEnd(errMsg, List.of()));
    return;                                         // ← no POISON  (pre-R742)
}
```

This is the *most reachable* variant: `it == null` is exactly what a sustained upstream
outage, a persistently-400 MiniMax prompt, or an auth failure produces — each burns 3
attempts with 1s/2s backoff first. The user's input box stays disabled the whole time.

### Why the R738 test didn't catch it

`SpringAiChatClientR738Test.r697AllAttemptsFailedPairsRunEndWithPoison` asserts only

```java
int nextPoison = src.indexOf("queue.offer(POISON)", runEndIdx);
assertTrue(nextPoison > 0, ...);   // "a POISON exists SOMEWHERE later in the file"
```

The R738 fix inside the `catch` block satisfies that, so the assertion passed against
a branch that was still broken. Classic sibling-detector false negative (the R739
deployment lesson): the test looked at the right *token* but not the right
*control-flow region*.

### Fix

`queue.offer(POISON)` added immediately before the `return`.

### Verification

`SpringAiChatClientR742Test` — 3 tests, deliberately written to **bound** the search to
the `if (it == null) { … }` block so a POISON from a sibling branch can never satisfy
them:

1. the POISON sits between the block's `RunEnd` offer and its closing brace, and *after*
   the `RunEnd` (a POISON before it would discard the `RunEnd`);
2. the `R742` marker comment lives inside the block, immediately above the line whose
   absence caused the bug (Lesson 1052);
3. the success paths (`RunEnd("stop")`, `RunEnd("tool_calls")`) must **not** offer POISON
   inline — an inline POISON there would mask a future missing-POISON bug.

---

## R743 — four independent stopReason classifiers, three of them wrong

**Files:**
- `aethercode/aethercode-core/.../core/stream/StreamEvent.java` (canonical taxonomy)
- `aethercode/aethercode-hooks/.../builtin/TodoContinuationHook.java`
- `aethercode/aethercode-protocol/.../methods/AetherCodeMethods.java`
- `aethercode/aethercode-protocol/.../methods/EngineContinuationDispatcher.java`
- `aethercode-desktop/src/store/index.ts` (defensive, no behaviour change)

### Root cause

Four layers classified the terminal `RunEnd` with hand-rolled string heuristics. Between
them they missed every stopReason the engine raises on its own:

| # | Site | Old predicate | Missed |
| --- | --- | --- | --- |
| (a) | `AetherCodeMethods:3775` | `startsWith("loop")` → `incLoopStop()` | `same_error_hard_stop`, `cancelled` |
| (b) | `EngineContinuationDispatcher:152` | `startsWith("loop")` → `incLoopStop()` | same |
| (c) | `TodoContinuationHook:318` | `startsWith("loop_")` | `loop_detected: …` (starts `loop_d`!), `same_error_hard_stop`, `cancelled` |
| (d) | `AetherCodeMethods:3799` + `EngineContinuationDispatcher:163` | `startsWith("error")` → span status | `runtime_exception: …`, `cancelled`, `same_error_hard_stop` — all recorded **"ok"** |

**(c) is the severe one.** The engine hard-stops; the hook then re-dispatches the same
transcript 2s later; the model re-emits the identical broken call. R739 turned
`same_error` into a deterministic hard-stop precisely because *"more thinking" cannot
recover* — the hook silently undid it, turning a clean stop into an unattended
token-burning loop. For `cancelled` it is worse: it resurrects a run the user
deliberately killed with Ctrl-C. This is the R739 lesson applied one layer up: a
deterministic engine decision must be honoured by a deterministic hook path, not routed
through a name-prefix heuristic that predates the decision.

**(a)/(b)** mean `getMetrics` reported a clean session while the engine was
hard-stopping runs. **(d)** means a run that blew up mid-stream left an `"ok"` span and
the trace recorder reported clean — the same silent-failure class R735/R735a/R735b has
been chasing, in a fourth place.

### Fix

One canonical taxonomy on `StreamEvent` (where the stopReason vocabulary is defined):

- `RunEnd.bareKind()` / `StreamEvent.bareKindOf(s)` — the portion before the first
  `": "` / space / `(`, so a *decorated* reason classifies the same as its bare form
  (R735b's lesson, generalised).
- `RunEnd.isDeterministicHardStop()` / `StreamEvent.isDeterministicHardStopOf(s)` —
  the three loop/terminal kinds (`loop_detected`, `same_error_hard_stop`, `cancelled`).

All four sites delegate. (d) uses `bareKind()` against the *success* set, because a
cancelled run is not an error and an error stop is not a loop — that's a third bucket,
"did the run end badly".

The desktop store's four exact `stopReason === 'awaiting_user_decision'` comparisons
became `isAwaitingUserDecision(stopReason)` (bare-kind match). **No behaviour change
today** — the engine emits that string bare — but it sat right next to the prefix-matching
`failedKind` R735b introduced, one decoration away from silently auto-firing a follow-up
query into a paused engine.

### Verification

- `StreamEventStopReasonR743Test` — 7 behavioural tests, including an **exhaustive sweep**
  over every stopReason that appears in a `stopReason = …` / `new RunEnd(…)` expression
  in the engine's production sources, each annotated with its origin line. If a future
  round adds a hard-stop kind without updating the predicate, this is where it shows up.
- `TodoContinuationHookHardStopR743Test` — 7 behavioural tests driving the hook exactly as
  production does: the three hard-stops dispatch nothing, and `end_turn` /
  `loop_research_mode` still dispatch (no over-correction), `loop_same_fingerprint` still
  skips (R141 preserved).
- `LoopStopMetricR743Test` — 3 source-pin tests over both protocol sinks.

---

## Test results

| Module | Result |
| --- | --- |
| `aethercode-core` | **1461 tests, 0 failures, 0 errors** (2 skipped, pre-existing) |
| `aethercode-hooks` | **131 tests, 0 failures** (incl. all R137/R141/R142/R151 continuation tests) |
| `aethercode-engine-springai` | **BUILD SUCCESS** (R738 + R742 both green) |
| `aethercode-protocol` | 343 tests, **1 failure — pre-existing, not caused by this round** (see below) |
| `aethercode-desktop` | `tsc --noEmit` clean |

### Pre-existing failure (NOT introduced by R741–R743)

`QueryErrorNotificationTest.query_catchEmitsRunEndAndErrorLog` asserts the stream contains
a `run_end` with `isError=true`. Since **R735a**, the engine emits its own
`runtime_exception: …` `RunEnd` (which has no `isError` field) before the protocol-level
synthetic handler is reached, so the assertion never matches.

**Verified pre-existing**: reproduced identically with all R743 changes to
`AetherCodeMethods.java` / `EngineContinuationDispatcher.java` stashed
(`git stash push` → same failure → `git stash pop`).

Left unfixed — reconciling the R735a engine RunEnd with this pre-R735a protocol
assertion means touching the R735a terminal-event path, which this round was scoped out
of. Flagged as a follow-up.

### Also noted, not fixed (out of scope)

`QueryEngineAnchorR726Test` is red on this checkout for unrelated reasons (documented in
AGENTS.md as pre-existing).

---

## Files changed

| File | Change |
| --- | --- |
| `aethercode-core/.../engine/TranscriptPruner.java` | R741: role-preserving dedupe stub |
| `aethercode-core/.../stream/StreamEvent.java` | R743: `bareKind` / `isDeterministicHardStop` taxonomy |
| `aethercode-engine-springai/.../SpringAiChatClient.java` | R742: POISON on retry-exhaustion |
| `aethercode-hooks/.../builtin/TodoContinuationHook.java` | R743: don't auto-continue a hard-stop |
| `aethercode-protocol/.../methods/AetherCodeMethods.java` | R743: metric + span-status classification |
| `aethercode-protocol/.../methods/EngineContinuationDispatcher.java` | R743: same, child-session sink |
| `aethercode-desktop/src/store/index.ts` | R743: bare-kind `awaiting_user_decision` match |
| `aethercode-core/src/test/.../TranscriptPrunerDedupeAdjacencyR741Test.java` | **new**, 6 tests |
| `aethercode-core/src/test/.../stream/StreamEventStopReasonR743Test.java` | **new**, 7 tests |
| `aethercode-engine-springai/src/test/.../SpringAiChatClientR742Test.java` | **new**, 3 tests |
| `aethercode-hooks/src/test/.../TodoContinuationHookHardStopR743Test.java` | **new**, 7 tests |
| `aethercode-protocol/src/test/.../LoopStopMetricR743Test.java` | **new**, 3 tests |
| `aethercode-core/src/test/.../TranscriptPrunerR718Test.java` | updated: asserts the old (broken) `TextBlock` shape |

---

## Build / deploy

```
.\aethercode-build-helper.ps1 -Goals 'clean package -DskipTests -q'   → BUILD SUCCESS (88.73s)
```

### Marker verification in the shipped artefact

Comment markers (`// R741:`) are stripped by javac and are **not** in the constant pool,
so the check targets the *compiled* artefacts of each fix:

```
R741  TranscriptPruner       ContentBlock$ToolResultBlock present : True
R742  SpringAiChatClient     POISON field present                : True
R742  SpringAiChatClient     RunEnd offer present                 : True
R742  SpringAiChatClient     retry-exhaustion log string          : True
R743  StreamEvent            isDeterministicHardStop / bareKindOf / same_error_hard_stop : True
R743  TodoContinuationHook   isDeterministicHardStop + marker string                    : True
R743  AetherCodeMethods      isDeterministicHardStop + bareKind                         : True
R743  EngineContinuationDispatcher  isDeterministicHardStop + bareKind                  : True
```

Definitive bytecode proof for R741 via `javap -p -c`:

```
buildDedupeStub:
  76: new  #435  // class org/aethercode/core/message/ContentBlock$ToolResultBlock
  84: invokespecial #291  // ContentBlock$ToolResultBlock."<init>":(Ljava/lang/String;Ljava/lang/Object;Z)V
```

`buildDedupeStub` now allocates a `ToolResultBlock`; pre-R741 it allocated only a
`TextBlock`.

### Jar sync

```
src  aethercode/aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar
dst  aethercode-desktop/src-tauri/target/release/aethercode.jar
SHA256 (both) : EEC217A4D3E9B82AB977BA05EE443C0E1D9A5BB951BEF3E597C01C9CFADF4AA5
identical     : True
mtime         : 2026-10-20 23:41:54   (fresh → desktop auto-restart will trigger)
```

Note: this build produces `aethercode-cli-0.1.0-SNAPSHOT.jar` (56 MB fat jar), **not**
the `…-shaded.jar` name AGENTS.md's deploy-cycle section still refers to. The desktop
path is unchanged; only the source artifact name differs.