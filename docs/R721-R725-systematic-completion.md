# R721-R725 — systematic completion (cancel / build / AGENTS / cross-session / integration)

Date: 2026-10-19
Branch: main (post-R720 commit `1556ad8`)
Commits in this round: 1 (`<pending>`)
Files touched: 4 prod + 2 test + 2 doc + 1 build helper

## Why this round exists

R720 closed the three highest-priority issues from the RAG
Day 1-3 / R719 era (L5 compact, memory RPC, file_write guard).
The user's "继续做吧，做完整" directive after R720 was a
mandate to keep going on every other gap the RAG / Mavis /
build-helper loop had surfaced but R720 didn't have the budget
to address. R721-R725 closes:

1. **Cancel RPC was a soft future-flag flip** — settle time
   60-120s, not under the user's control.
2. **Build was 9-13 min stuck on JDK 25 + mockito 5.14.x +
   byte-buddy 1.14.x incompat** — RAG Day 2 hit this for Q19/Q20/Q21.
3. **No project-level memory file** — Mavis / agents reading
   the AetherCode repo on session boot had no AGENTS.md to
   ground project conventions, so the model re-discovered
   "L5 = 0 means look at three layers" every session.
4. **Cross-session memory recall had no regression test** —
   RAG Day 2 Q19's "Mavis 不能修改 RAG 文件" directive
   didn't have a unit-test-style guard that the project
   memory an instance wrote would survive the instance
   going away.
5. **LayeredMemoryStore had an R280 / pre-R280 split** —
   the new R280 `ProjectMemoryStore` (PROJECT_MEMORY.md)
   and the legacy `FileBackedMemory` (MEMORY.md) were
   disjoint: a `appendProjectChange` write hit one, a
   `readProjectMemory` read hit the other, and the
   cross-session contract was silently broken. R724
   discovered this; the fix is in this round.

## What changed

### R721 — hard cancel RPC

- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  - New `runThreads: Map<String, Thread>` and
    `runChildren: Map<String, List<ProcessHandle>>` so the
    cancel RPC can find the worker thread and any forked
    children.
  - `cancel(runId)` now does **four layers** in order:
    1. `done.cancel(true)` — pre-R720 behaviour, kept for
       backwards compat.
    2. `workerThread.interrupt()` — wakes up any
       `tryAdvance` / `stream.read` / blocking I/O on the
       worker thread.
    3. `runChildren.forEach(ProcessHandle::destroyForcibly())`
       — kills any tool-spawned subprocesses (a long-running
       bash tool that leaked would otherwise keep running
       after the LLM stream stopped).
    4. After `CANCEL_FORCE_DEFAULT_MS` (5s, tunable via
       `AETHERCODE_R721_CANCEL_FORCE_MS` env), fall back to
       `Thread.stop()` (deprecated but the documented
       "kill the run" semantics). Logs a warning so an
       operator knows the run didn't honour the polite
       cancel. If `Thread.stop()` throws
       `UnsupportedOperationException` on JDK 25+ virtual
       threads, the watchdog marks the future complete with
       a `RuntimeException("R721 hard-cancel")` so the
       `inFlight.remove(runId)` path can run.
  - Response shape: `{"cancelled": true, "runId": "...", "forceMs": 5000}`.
- `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
  - `tryAdvance` checks `Thread.interrupted()` at the top of
    every iteration; when set, it emits a single
    `StreamEvent.RunEnd("cancelled", List.of())`, sets
    `finished = true`, and returns `false`. The renderer's
    transcript shows the run ended (not errored), and the
    `forEach` consumer exits cleanly.
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/engine/QueryEngineCancelR721Test.java` (new)
  - 2/2 tests: interruptSettlesRunInMilliseconds (proves
    cancel-settle < 2s; pre-R720 was 60-120s) and
    interruptEmitsCancelledRunEnd (proves the renderer's
    last event is a `RunEnd(stopReason="cancelled")`).
  - E2E on a real daemon: `cancel` RPC returns in **5.96 ms**
    (down from 60-120s); `cancelled=true, forceMs=5000`.

### R722 — `aethercode-build-helper.ps1`

- `aethercode-build-helper.ps1` (new at repo root)
  - Reads the JDK major version from `mvn -v`.
  - Picks a (mockito, byte-buddy) pair from a version table:
    - JDK 25 → mockito 5.15.2 + byte-buddy 1.15.11 (RAG Day 3 verified)
    - JDK 24 → mockito 5.14.2 + byte-buddy 1.14.18 (RAG Day 2 baseline)
    - JDK 21-23 → mockito 5.12.0 + byte-buddy 1.14.13
    - JDK 17 → mockito 5.5.0 + byte-buddy 1.14.0
  - Auto-cd into `aethercode/` (the multi-module root) — pre-R722
    the user had to `cd aethercode` first or the script
    saw "no POM in this directory".
  - Splats goals + -D overrides as an array (not a single
    string) — pre-R722 the first cut used `& mvn $fullGoals`
    which PowerShell concatenated into a single
    whitespace-stripped arg, and mvn parsed it as a single
    `Unknown lifecycle phase` name.
  - Retries with exponential backoff (5s, 15s, 45s by default;
    `MaxAttempts=3` by default).
  - Logs to `$env:USERPROFILE\aethercode-build-helper.log`
    so the model can read the trail without a separate `tee`.
  - Exit codes: 0 = BUILD SUCCESS, 1 = BUILD FAILURE after
    all attempts, 2 = prerequisites missing.
  - Usage: `.\aethercode-build-helper.ps1 -Goals 'test'
    -MaxAttempts 2`; dry-run via `-DryRun`.
  - **E2E**: `BUILD SUCCESS in 44.9s on attempt 1` (full
    multi-module package).

### R723 — `AGENTS.md` (project-level memory)

- `AGENTS.md` (new at repo root, 10 KB)
  - Models the agents.md spec: every agent (Mavis, Mavis,
    custom subagents) reads this on session boot to ground
    project conventions.
  - Three layers, three homes (user / project / session)
    with explicit file paths.
  - Sections: Build, Test, Code style, Project layout,
    Daemon deploy cycle, Watch out for, Lessons index.
  - The "Watch out for" section documents the four pre-R720
    silent-no-op bugs (Lesson 1021, 1022, 1023 + the
    `L1-L4 unit test gap`) so a future agent re-discovering
    the same issue finds the root cause + the fix path
    instead of re-debugging.

### R724 — cross-session memory recall

- `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreCrossSessionR724Test.java` (new)
  - 3/3 tests: projectMemoryPersistsAcrossLayeredMemoryStoreInstances,
    userMemoryPersistsAcrossLayeredMemoryStoreInstances,
    differentAgentTypesDoNotShareUserScope.
- `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/LayeredMemoryStore.java`
  - **Bonus fix** discovered by the R724 tests: `readProjectMemory`
    was reading only the post-R280 `ProjectMemoryStore`
    (PROJECT_MEMORY.md), but `appendProjectChange` (the
    pre-R280 entry point used by every test) was writing to
    the legacy `FileBackedMemory` (MEMORY.md). The two
    stores were disjoint — a session that called
    `appendProjectChange` and then `readProjectMemory` saw
    an empty body. The R724 fix merges the two sources
    in `readProjectMemory`, with a "## Legacy change log
    (R127, pre-R280)" section for the FileBackedMemory
    items. Pre-R724, cross-session PROJECT_MEMORY.md
    recall silently dropped every entry the pre-R280
    `appendProjectChange` path wrote.
- Full aethercode-memory module regression: **343 tests,
  0 failures** (including the 3 new R724 tests).

### R725 — integration

- Shaded jar: 56.85 MB, `BUILD SUCCESS in 44.9s` via the
  R722 helper.
- Daemon restart cycle: `taskkill` old PID 25384 →
  `Start-Process java -jar` → fresh PID 18632-style.
- E2E on the running daemon:
  - `getMetrics`: l5Compacts 0 (fresh session, no
    transcript over 80K), turns 0/0, inputTokens 0.
  - `query` accepted with `runId: run-1`.
  - `cancel` RPC: 5.96 ms, response `cancelled: true,
    forceMs: 5000`.
  - `memory/list` works (R720 verified; still 200 OK).
  - `file_write` mode guard (R720 verified; still refuses
    > 5 KB target without explicit `mode`).

## Test counts

| Round | Module                     | New tests | Pass | Fail |
| ----- | -------------------------- | --------- | ---- | ---- |
| R721  | aethercode-core             | 2         | 2    | 0    |
| R722  | aethercode-build-helper    | n/a (script) |  |  |
| R723  | n/a (docs)                 |           |      |      |
| R724  | aethercode-memory          | 3         | 3    | 0    |
| R724  | aethercode-memory regression | 0 (existing) | 343 | 0  |
| R725  | integration                | n/a (E2E) |      |      |

## Lessons

See Mavis memory entries 1024-1027 (appended alongside this
commit).
