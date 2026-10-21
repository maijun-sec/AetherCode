# R745 — consumer-side terminal-event contract

**Date:** 2026-10-21
**Layer:** consumption side of an engine stream (`aethercode-protocol`)
**Predecessors:** R735 (terminal-event contract), R735a (outer try/catch),
R742 (RunEnd+POISON pairing), R743 (one stopReason taxonomy), R744
(producer-side first-chunk timeout)

---

## 1. Scope

Pure fix round. Root cause was located and confirmed by the reporter
before implementation; this round does no re-investigation. The layer
is **consumption**: the code that drains `engine.query(...)` on the
protocol side. `QueryEngine.tryAdvance` (R735a) and
`SpringAiChatClient.runCall` (R744) are untouched.

The contract every consumer must satisfy, in three parts:

| # | Requirement | Consequence of violation |
|---|---|---|
| 1 | **close** the stream | Spliterator + chat-client resources leak for JVM lifetime |
| 2 | **bounded `hasNext()`** | consumer thread parks forever → `finally` never runs → session run lock held forever |
| 3 | **a terminal `RunEnd`** | renderer's `isStreaming` never flips false → dead run reads as a healthy green "Idle" (R735) |

---

## 2. Fix A — `EngineContinuationDispatcher.java`

Confirmed pre-fix state: zero `.close()` in 299 lines, `while
(it.hasNext())` at line 134 with no deadline, and no RunEnd contract.

| Change | Location |
|---|---|
| stream bound in **try-with-resources** | `EngineContinuationDispatcher.java:133` |
| bounded probe gate replacing `while (it.hasNext())` | `:148` → `awaitNextEvent(it)` at `:164` |
| `consumer_timeout` RunEnd emitted **before** break | `:164-189` |
| `sawRunEnd[0] = true` in the `RunEnd` branch | `:210` |
| post-loop **no-RunEnd guard** | after the loop, emits `no_run_end: …` |
| `awaitNextEvent(Iterator)` helper | new private method |
| `consumerTimeoutMs()` + `CONSUMER_TIMEOUT_MS_DEFAULT` | new package-private, `180_000L` |
| `CONSUMER_POLL_MS = 250L` | new private constant |

### Timeout budget reuse

The consumer budget reads `AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS` and
defaults to **180s — the same value R744 uses**. No new knob was minted,
deliberately:

- R744 measured 40-90s cold-start TTFB on a 150K-char transcript.
  A continuation that stalls *longer* than a first chunk may
  legitimately take is dead by the same evidence.
- A separate env var means an operator who tuned R744 does not also
  tune this, and the two budgets can silently diverge into "producer
  healthy, consumer kills it".
- A malformed override falls through to the default, so a typo can never
  disable the guard.

### The subtle case in the probe

`awaitNextEvent` returns `true` for **clean exhaustion**, not just
"has an element". A healthy stream that has delivered its last element
makes `hasNext()` return `false` forever. Treating that as a stall would
inject a bogus `consumer_timeout` RunEnd into *every* healthy run. The
caller then calls `next()`, which throws `NoSuchElementException` and the
loop exits normally. This is pinned behaviourally.

---

## 3. Fix B — `AetherCodeMethods.query`

Audited against the same three questions. Result:

| Question | Pre-fix state | Action |
|---|---|---|
| closes? | **NO** — `target.query(prompt).forEach(...)`, never closed | bound in try-with-resources |
| "exited but no RunEnd" path? | **YES, and unhandled** | post-`forEach` guard added |
| session lock not acquired? | **already correct** — 800ms bounded retry, then explicit `{ok:false, error:"session is busy with run …", busyRunId}` | pinned, not rewritten |
| R735a outer catch → synthetic error RunEnd | already present | pinned, not rewritten |
| `finally` releases session lock | already present | pinned, not rewritten |

The interesting find is the middle row. `forEach` returns normally on
clean exhaustion, so **a stream ending without a RunEnd is
indistinguishable from a healthy one at the point of return**. That is
the same silent-death class as R735, on the main path, and it was
unhandled. The guard emits a `run_end` with
`stopReason="no_run_end: consumer exited without terminal event"` and
`isError=true`, so R735b's failed-pill bucket still classifies it.

The busy-lock path is pinned specifically because it is easy to break:
per the project's standing principle that determinable logic takes the
determinate path, a lock conflict must never be reported as
`accepted: true` for a run that will never dispatch.

---

## 4. Fix C — full-repo consumption-point audit

Every `engine.query(` / `.iterator()` / `while (it.hasNext())` /
`.spliterator()` consumption point in `aethercode/` main sources:

| Module | Site | close | bounded hasNext | RunEnd contract | Action |
|---|---|---|---|---|---|
| `aethercode-protocol` | `EngineContinuationDispatcher:116` | ✗ | ✗ | ✗ | **FIXED (A)** |
| `aethercode-protocol` | `AetherCodeMethods:3736` | ✗ | ✗ | ✗ | **FIXED (B)** |
| `aethercode-core` | `SubagentOrchestrator:84` `child.query().forEach` | ✗ | ✗ | n/a | **out of scope, reported** |
| `aethercode-bridge` | `SwarmCoordinator:61` `engine.query().forEach` | ✗ | ✗ | n/a | out of scope, reported |
| `aethercode-sdk` | `PlanExecutor:355` `engine.query().forEach` | ✗ | ✗ | n/a | out of scope, reported |
| `aethercode-tools` | `AgentTool:551` `engine.query().forEach` | ✗ | ✗ | n/a | out of scope, reported |
| `aethercode-cli` | `Main:332` `engine.query().forEach` | ✗ | ✗ | n/a | out of scope, reported |
| `aethercode-compact` | `StructuredCompactor8:293`, `StructuredCompactor:173`, `CompactGate:162` | ✗ | ✗ | breaks on RunEnd | out of scope, reported |
| `aethercode-memory` | `MemoryExtractor:186`, `MemoryLifecycle:637` | ✗ | ✗ | breaks on RunEnd | out of scope, reported |
| `aethercode-memory` | `MemoryRecall:183` | ✓ | ✗ | breaks on RunEnd | out of scope, reported |
| `aethercode-deepagents` | `ChatClientReflector:63` | ✓ | ✗ | breaks on RunEnd | out of scope, reported |
| `aethercode-sdd` | `SddOrchestrator:602` | ✗ | ✗ | ✗ | out of scope, reported |

**Not fixed in R745, and why.** The brief scoped this round to the two
JSON-RPC consumption points (A and B) and said explicitly: *do not
refactor*. The remaining sites are `forEach`-based, synchronous, and
in-process — a stall in them surfaces as a stack trace rather than the
silent terminal-event loss the desktop path produces, and none of them
acquires the session run lock, so a park there cannot produce the
permanent "session is busy" wedge this round is about.

Two are still worth a follow-up round and are called out here so they are
not lost:

- **`SubagentOrchestrator:84`** is the highest-value remaining site: it
  runs an unattended **child** engine, so it has exactly the same
  "nobody is watching" property as the continuation dispatcher that made
  this round necessary.
- **`AgentTool:551`** and **`SddOrchestrator:602`** likewise consume
  engine streams with no terminal-event check.

The compact / memory / sdd sites additionally leak their stream (no
`close()`), which is a real resource defect but a slow leak rather than
a hang.

---

## 5. Tests

`EngineContinuationDispatcherR745Test` — **9 tests**

Behavioural (the algorithmic part, driven without an engine):

| Test | Asserts |
|---|---|
| `stalledIteratorIsDistinguishable` | a `hasNext()` still parked at the deadline reports **not**-ready, and only after actually waiting its budget (2s) — guards both "no bound" and "returns immediately" |
| `availableIteratorIsReady` | a stream with an element pending reports ready, and **promptly** (<5s) — every healthy event must not pay the full timeout |
| `exhaustedIteratorIsNotAStall` | clean exhaustion is **not** a stall; detected immediately |
| `timeoutDefaultsToR744Budget` | consumer default is exactly R744's 180s |

Source pins (structural — what actually regressed):

`streamIsClosed`, `hasNextIsBounded` (asserts `while (it.hasNext())` is
gone), `exhaustedWithoutRunEndIsTerminal`,
`sessionLockAlwaysReleased` (asserts `releaseSessionLock` sits inside
the `finally`, **after** the catch — placing it before the finally
reintroduces the permanent wedge), `repairBranchesAreInstrumented`.

`AetherCodeMethodsConsumerR745Test` — **7 tests**

`queryStreamIsClosed`, `exhaustedWithoutRunEndIsTerminal`,
`runEndBranchRecordsTerminal` (no false positive on healthy runs),
`outerCatchContractPreserved`, `busyLockRejectsDeterminately`,
`finallyReleasesLock`, `repairBranchIsInstrumented`.

All assertions use JUnit's `assertTrue(s.contains(x))`, **not** AssertJ's
`assertThat(String).contains(a, b)` — that overload is varargs and
asserts both strings (see AGENTS.md).

### Results

```
EngineContinuationDispatcherR745Test:  Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
AetherCodeMethodsConsumerR745Test:     Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
                                                         ─────────  ─────────
                                          total:          16          0
```

### Mutation test

Both files reverted to pre-fix semantics (bare stream assignment,
`while (it.hasNext())`, both guards neutered to `if (false)`):

```
EngineContinuationDispatcherR745Test:  Tests run: 9, Failures: 3, Errors: 0
AetherCodeMethodsConsumerR745Test:     Tests run: 7, Failures: 2, Errors: 0
                                                         ─────────  ─────────
                                          total:          16          5
```

The five red tests are exactly the five fixes:

- `EngineContinuationDispatcherR745Test.streamIsClosed`
- `EngineContinuationDispatcherR745Test.hasNextIsBounded`
- `EngineContinuationDispatcherR745Test.exhaustedWithoutRunEndIsTerminal`
- `AetherCodeMethodsConsumerR745Test.queryStreamIsClosed`
- `AetherCodeMethodsConsumerR745Test.exhaustedWithoutRunEndIsTerminal`

---

## 6. Build verification

| Step | Result |
|---|---|
| `mvn test -pl :aethercode-protocol` | 1 pre-existing failure (below) |
| `mvn test -pl :aethercode-core` | **BUILD SUCCESS** |
| `mvn clean package -DskipTests -q` | **BUILD SUCCESS** (91.9s) |

### Pre-existing failure — not from this round

`QueryErrorNotificationTest.query_catchEmitsRunEndAndErrorLog`

```
no synthetic run_end with isError=true found in stream_events; got:
  [..., {type=run_end, stopReason=runtime_exception: RuntimeException: upstream 503: gateway timeout}]
```

Verified pre-existing by stashing both changed main-source files and
re-running: **fails identically** with all R745 changes removed. This is
the red already documented in AGENTS.md — since R735a the engine emits
its own `runtime_exception: …` RunEnd before the protocol synthetic
handler is reached. Tracked in `docs/R741-R742-R743-bug-fixes.md`.

Note it is now *closer* to correct than before: the R745 guard means
both the engine's own RunEnd **and** the protocol's synthetic one are
delivered on this path.

### Lesson 1053 — the patch is verified inside the jar

`BUILD SUCCESS` is not evidence the patch shipped (Maven's incremental
compiler skips regeneration when the diff looks formatting-only).
Comment markers are stripped by javac, so the **string constants** were
grepped out of the compiled `.class` inside the fat jar:

```
=== EngineContinuationDispatcher.class (len=15542) ===
  'R745: continuation consumer exited without RunEnd' -> True
  'R745: continuation consumer stalled'                -> True
  'no_run_end'                                         -> True
  'consumer_timeout'                                   -> True
  'awaitNextEvent'                                     -> True
  'consumerTimeoutMs'                                  -> True
  'R745: query consumer exited without RunEnd'        -> False   (correct — other class)

=== AetherCodeMethods.class (len=199566) ===
  'no_run_end'                                         -> True
  'R745: query consumer exited without RunEnd'        -> True
  'awaitNextEvent'                                     -> False   (correct — other class)
  'consumer_timeout'                                   -> False   (correct — other class)
```

Each class carries its own markers and **not** the other's, confirming
the two classes were independently recompiled.

### Jar sync — NOT completed (blocked)

| | |
|---|---|
| new jar | `aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT.jar`, 56 859 522 B, 01:25:33 |
| new SHA256 | `CBFE893B8D84A57C078F2A57D5685013A91D0FE27E1EC69E741ADF3BEC84774C` |
| desktop jar | `aethercode-desktop\src-tauri\target\release\aethercode.jar`, 56 857 097 B, 00:36:10 |
| desktop SHA256 | `82C8999A82353D2A81712166A8610B104FA79FA81E0524BCB21C765D5C1B0821` |
| identical | **false** |

**The desktop jar still holds the pre-R745 build.** It could not be
overwritten: the running daemon holds it open.

```
PID 24456  java -Xms2g -Xmx8g ... -jar ...\target\release\aethercode.jar
```

Confirmed by an exclusive-write probe:

```
[System.IO.File]::Open($dst,'Open','Write','None') -> MethodInvocationException
```

`Copy-Item`, `robocopy` (incl. `/IS /IT`) all silently no-op against the
locked handle. PID 24456 is the same daemon the reporter jstacked, so it
was **not killed** — restarting someone's running daemon is the user's
call, not this round's.

**To finish the deploy**, once the daemon is stopped (or the desktop is
closed):

```powershell
Copy-Item aethercode\aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT.jar `
          aethercode-desktop\src-tauri\target\release\aethercode.jar -Force
(Get-FileHash aethercode\aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT.jar).Hash
# expect CBFE893B8D84A57C078F2A57D5685013A91D0FE27E1EC69E741ADF3BEC84774C
```

Per AGENTS.md the desktop auto-restarts the daemon on jar-mtime change,
so no manual restart is needed after the copy.

---

## 7. Changed files

| File | Lines touched |
|---|---|
| `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/EngineContinuationDispatcher.java` | +~190 (helpers + guards) |
| `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` | +~60 |
| `aethercode/aethercode-protocol/src/test/java/org/aethercode/protocol/methods/EngineContinuationDispatcherR745Test.java` | new, 9 tests |
| `aethercode/aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsConsumerR745Test.java` | new, 7 tests |

Scratch scripts (`apply-r745.py`, `mutate-r745.py`) were left in
`aethercode/` for reproducibility of the mutation test; they are
throwaway and should be deleted before commit.

`QueryEngine.java` and `SpringAiChatClient.java` are **untouched** by
this round.

---

## 8. Follow-ups

1. **Finish the jar deploy** (above) — the desktop is still on the
   pre-R745 build.
2. **`SubagentOrchestrator:84`** is the next highest-value consumption
   site: an unattended child engine, same "nobody is watching"
   property that made this round necessary.
3. **`AgentTool:551` / `SddOrchestrator:602`** consume engine streams
   with no terminal-event check.
4. The compact / memory consumers leak their stream (no `close()`) — a
   slow resource leak, not a hang.
5. `QueryErrorNotificationTest` remains red (pre-existing, tracked in
   `docs/R741-R742-R743-bug-fixes.md`).