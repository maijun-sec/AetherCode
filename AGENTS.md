# AGENTS.md — AetherCode project-level memory

This file is the project-level companion to Mavis's user-level
`MEMORY.md` (at `C:\Users\maijun\.minimax\agents\mavis\memory\MEMORY.md`).
Per the agents.md spec, every agent (Mavis, Mavis, custom agents
spawned by `sub_todo_write`) reads this on session boot to
ground project conventions. Updates flow two ways:

- **Mavis → AGENTS.md**: when Mavis (or a subagent) learns a
  new project-level invariant, append it here. Group by section
  header (e.g. `## Build`); don't rewrite history.
- **AGENTS.md → Mavis**: when this file is updated, Mavis's
  next turn picks up the change because the renderer refreshes
  the project tree on session boot.

Three layers, three homes:

| Layer     | Path                                                | Scope                              |
| --------- | --------------------------------------------------- | ---------------------------------- |
| User      | `~/.aethercode/`                                    | cross-project, user-wide           |
| Project   | `<cwd>/.aethercode/`  *(this file + the dir)*      | single project, current cwd       |
| Session   | `<cwd>/.aethercode/sessions/<id>/`                 | single engine session             |

For Mavis's cross-project model memory, see the Mavis agent
memory at `C:\Users\maijun\.minimax\agents\mavis\memory\MEMORY.md`.

---

## Build

- **JDK**: project root is `D:\work\workspace\idea\engine\AetherCode\`.
  Use the system-wide `java` (Adoptium Temurin 25.0.1).
- **Maven**: `D:\development\apache-maven-3.9.1\bin\mvn`. Do NOT
  invoke `mvn` directly from the repo root — `pom.xml` lives at
  `AetherCode\aethercode\pom.xml`. Either `cd aethercode` first
  or use the `aethercode-build-helper.ps1` script which
  auto-cd's for you.
- **aethercode-build-helper.ps1**: smart build entry point.
  Detects JDK major version from `mvn -v`, picks a safe
  (mockito, byte-buddy) pair (RAG Day 3 verified:
  JDK 25 → mockito 5.15.2 + byte-buddy 1.15.11), runs the
  user's goal string with -D overrides, and retries with
  exponential backoff (5s, 15s, 45s) on BUILD FAILURE.
  Replaces the 9-13 minute "stuck build" pattern that the
  RAG Day 2 Q19/Q20/Q21 sessions hit when the model picked
  mockito 5.14.2 + byte-buddy 1.14.18 against JDK 25.
- **Build commands**:
  - Fast verify: `.\aethercode-build-helper.ps1 -Goals 'compile -DskipTests -q'` (~12s)
  - Full package: `.\aethercode-build-helper.ps1` (default: `package -DskipTests -q`, ~30-60s)
  - With tests: `.\aethercode-build-helper.ps1 -Goals 'test' -MaxAttempts 2` (~5-10 min)
  - Dry-run: add `-DryRun` to see what would execute

## Test

- `QueryEnginePreFlightCompactR720Test` — pins the L5 pre-flight
  gate fraction (0.4 × ctxWindow). Add a new test here when
  changing the L5 trigger logic; pre-R720 the gate was 92% of
  ctxWindow and `l5Compacts` stayed at 0 for 17 RAG-Day-2 turns.
- `QueryEngineCancelR721Test` — pins the cancel-via-thread-interrupt
  contract. Pre-R721 cancel was a soft future-flag flip and could
  take 60-120 seconds to settle.
- `FileWriteToolTest` — covers the four file_write modes
  (default-overwrite refuses > 5 KB target with recovery hint,
  mode="append" preserves original content, mode="overwrite"
  explicit bypasses the guard).
- `MemoryMethods` RPC round-trips are exercised at the daemon
  level — verify with `curl -X POST http://127.0.0.1:22888/rpc
  -d '{"method":"memory/list","params":{"scope":"project"}}'`.

## Code style

- Java 21+ source level (project compiles at release=21 even
  on JDK 25 hosts; the build is JDK 25 / source 21).
- Tabs-as-indent is the historical norm; preserve whatever the
  neighbouring file uses. New files default to 4-space indent.
- SLF4J for logging (not `java.util.logging`). The daemon's
  R720-R721 fix path specifically switched a `java.util.logging`
  call to `LOG.warn(...)` so it actually surfaced in the
  `aethercode-desktop.err` log the desktop reads.
- `// R720-A1: ...` style comments pin the round that introduced
  a non-obvious behaviour. New fixes should follow the same
  pattern: short prefix, then a multi-line comment explaining
  the *why* (not the *what*).

## Project layout

- `aethercode/` — the Maven multi-module root (24 modules).
  - `aethercode-core/` — `QueryEngine`, `TranscriptPruner`, `AppState`,
    `MetricsCollector`, `Message`, `ContentBlock`. The transcript-
    pruning pipeline (R717 L1-L4) and the L5 compact (R718-R720)
    live here.
  - `aethercode-compact/` — `AutoCompact`, `StructuredCompactor8`,
    `StructuredCompactor`, `SlidingWindowCompactor`. The wire-side
    summarisation chain.
  - `aethercode-memory/` — `LayeredMemoryStore`, `ProjectMemoryStore`,
    `MemoryLifecycle`, `MemoryRecall`, `MemoryCriticAgent`,
    `MemoryExtractor`. The 3-layer memory stack (USER / PROJECT /
    SESSION).
  - `aethercode-protocol/` — `AetherCodeMethods`, `MemoryMethods`,
    `HttpJsonRpcServer`, `JsonRpcDispatcher`. The JSON-RPC surface
    over stdio + HTTP+WS.
  - `aethercode-cli/` — `DaemonRunner`, `Main`. The daemon entry point.
  - `aethercode-sdk/` — `AetherCodeEngine`. The high-level API used
    by `aethercode-cli` and downstream projects.
  - `aethercode-tools/` — `FileWriteTool`, `FileEditTool`,
    `FileReadTool`, `BashTool`, `GrepTool`, `GlobTool`, etc.
    Most of the destructive tools (file_write, bash) live here.
  - `aethercode-hooks/` — `WriteExistingFileGuardHook`,
    `PermissionHook`. Safety net.
- `aethercode-desktop/` — Tauri shell. Bundles the daemon
  jar at `src-tauri/target/release/aethercode.jar` and exposes
  the React renderer. **The desktop auto-restarts the daemon
  when the jar's mtime changes** — R720 confirmed this for
  the build-deploy-test cycle.
- `aethercode-memory/` (TS, at the repo root, not the Maven
  module) — the older TS memory stack with a 3-layer model
  (global / project / session) and 4 entry kinds (fact / rule /
  change / breadcrumb). The Java `aethercode-memory` module is
  the production path; the TS variant ships as an npm package
  for the legacy TUI.
- `docs/` — R-number design docs. Each round adds a
  `R###-<short-title>.md` to the root of this folder.
- `r###-commit-msg.txt` — the commit message used for that
  round. Keep these in git for posterity.

## Daemon deploy cycle

- The daemon (PID currently ~20K, restarting on jar-mtime change)
  is launched as a raw `java -jar aethercode.jar --http-port 22888
  --sessions-dir <dir>` by the desktop's startup hook. Mavis
  can launch a fresh one with `Start-Process java ... -ArgumentList ...`
  if needed.
- The desktop's `find_jar_path` looks at (in order): the
  tauri resource dir, the exe sibling, the parent of the exe.
  For the dev loop, the jar is at
  `src-tauri\target\release\aethercode.jar`.
- After a successful `mvn package`, sync with
  `Copy-Item aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT-shaded.jar
  aethercode-desktop\src-tauri\target\release\aethercode.jar -Force`,
  then `taskkill /F /PID <old_pid>` and restart the java process.

## Watch out for

- **`memory/*` RPC gap (Lesson 1022)**: pre-R720 the RPC methods
  existed in `MemoryMethods.registerAll()` but `DaemonRunner`
  never called it AND `HttpJsonRpcServer` had no `memory/*`
  case in its hand-rolled switch. Two-layer fix (cli + protocol).
  Symptom: every memory call returned `-32601 method not found`.
  **Verify with a daemon ping** before assuming memory works.
- **L5 compact `compactor == null` (Lesson 1021)**: the
  `AetherCodeEngine` builder had `if (b.contextWindow > 0 && resolvedCompactor == null)`,
  so a daemon started without `--context-window` got
  `compactor = null` and `runPreFlightCompact` short-circuited
  at the entry guard. Fix lives in three places (AetherCodeEngine
  builder, QueryEngine.runPreFlightCompact gate, resolveCompactConfig
  fallback). When `l5Compacts` is mysteriously 0, debug each
  of the three layers.
- **L1-L4 unit test gap**: pre-R720, `QueryEngine.runPreFlightCompact`
  had no direct unit test. The R720 fix added
  `QueryEnginePreFlightCompactR720Test` with 3 cases (small
  skips, oversize fires, env-var escape hatch smoke check).
  Future L5 changes need to add a case here.
- **`file_write` truncate (Lesson 1023)**: the schema default
  for `file_write` is `TRUNCATE_EXISTING`. The Q19 RAG incident
  destroyed 130 lines of `progress.md` because the model issued
  `file_write` intending "append". R720 fix: optional `mode`
  param, default-mode refuses writes against > 5 KB existing
  files. When the user policy is "Mavis 不能修改 X 文件",
  prefer `mode="append"` or `file_edit`.
- **jar sync timing**: the desktop only restarts the daemon
  when the jar's mtime changes. If `mvn package` produces a
  jar with the SAME mtime as the previous one (rare but
  happens when mtime resolution is coarse), the daemon
  will keep using the old class. Fix: `touch` the jar or
  the build directory after `mvn package`.

## Lessons index

- **R719** (session anchor + L4 preservation): fixes long-task-
  cycle forgetting. The anchor message is a USER-role metadata-
  tagged message that L4 strip-oldest preserves.
- **R720** (systematic repair): L5 compact 0→1, memory RPC
  -32601 → 200, file_write truncate guard. Three layers
  in the L5 fix, all needed.
- **R721** (hard cancel): cancel RPC was a soft future-flag
  flip, took 60-120s to settle. Now: thread.interrupt() +
  ProcessHandle.destroyForcibly() + 5s Thread.stop() fallback
  via `AETHERCODE_R721_CANCEL_FORCE_MS` env. Settle time:
  ~7ms in E2E.
- **R722** (build helper): `aethercode-build-helper.ps1`
  auto-detects JDK and picks a safe (mockito, byte-buddy) pair.
  Replaces 9-13min stuck builds.
- **R735** (terminal-event contract + chat scroll):
  Two P0s from one screenshot. (1) `QueryEngine`'s
  `same_error_hard_stop` branch (R181) omitted
  `StreamEvent.RunEnd`, so the desktop's `case 'run_end':`
  handler never ran and a dead engine looked like a healthy
  green `● Idle`. (2) `MessageList`'s scroll listener flipped
  `pinned=false` on first paint, permanently disabling
  auto-scroll — user could scroll up but not down. Fixes:
  RunEnd emit + `QueryEngineR735Test` (2 source-pin cases);
  `useLayoutEffect` pre-paint snap + `userScrolledRef` +
  `MessageListR735AutoScroll.test.tsx` (7 cases); Header
  `● Failed (reason)` + `● Sub-task stuck` buckets.
- **R735a** (R735 follow-up): wrap the whole `tryAdvance` body
  in `try { ... } catch (Throwable t) { emit RunEnd(stopReason="runtime_exception: ...") }`.
  Without it, any throw escaping `currentLlmIt.next()` silently
  kills the Spliterator. Pin:
  `QueryEngineR735Test.tryAdvance_hasOuterTryCatchRuntimeException`.
- **R735b** (R735 follow-up): the actual root cause of the
  "still dies silently" report was the renderer using
  `failedStopKinds.has(stopReason)` — an exact-match Set lookup
  — while the engine emits decorated stopReasons (~120 chars).
  `Set.has("error")` was false; every upstream 500 was
  classified as success. Fix: prefix-match + store the BARE
  kind for display. Guarded by `failedStopReasonR735b.test.ts`.
- **R736** (transcript repair): `stripOrphanToolResults`
  rev3'd to enforce adjacency-preserving batch on top of the
  R716 id-existence rule. Three rounds, all unit-tested green
  before end-to-end proved each one incomplete — the real RAG
  shape only validates when self-stripped orphans are dropped
  in the same pass as their declarations. 15 source-pin cases
  in `QueryEngineStripOrphanR736Test`.
- **R737** (response language): per-turn `responseLanguageDirective`
  appended LAST in `buildSystemForTurn`, detected from the
  transcript backwards (CJK ≥ 12 && CJK × 4 ≥ Latin).
- **R738** (POISON on the chat-client error path).
- **R739** (`same_error` bypasses the warn ladder and hard-stops
  at `sameErrorThreshold`).
- **R740** (`emitSubTaskTransitions` synthesizes `auto-{idx}`
  sub-tasks for flat todos).
- **R741** (L3 dedupe stub role): `buildDedupeStub` hard-coded
  `Role.USER`, which wedges a non-tool message into the middle
  of a tool-call batch. Now role-preserving with the original
  `tool_use_id`.
- **R742** (POISON on the retry-exhaustion path): R738's own
  sibling — `if (it == null)` offered a terminal `RunEnd` and
  returned without poisoning the queue.
- **R743** (one stopReason taxonomy): four layers had
  hand-rolled classifiers; `StreamEvent` now owns `bareKind()` /
  `isDeterministicHardStop()` and all four delegate.

## Watch out for (R735 additions)

- **Every terminal `stopReason = "…"` in `QueryEngine.tryAdvance`
  must be followed by `action.accept(new StreamEvent.RunEnd(...))`
  before `return false;`.** `QueryEngineR735Test` enforces this;
  if you add a new hard-stop branch, the test's `checked >= 4`
  floor will also make you re-validate the contract.
- **Observer-derived scroll state needs a `useLayoutEffect` snap
  plus a "who scrolled" tag.** The scroll listener registers on
  mount and fires *before* any `useEffect` correction, so
  `pinned = distance < 80` is garbage on the first tick. See
  `MessageList.tsx` `userScrolledRef`.
- **`useLayoutEffect` ≠ "layout is done" (R735-fix1).** It
  guarantees DOM *commit*, not final *layout*. A single
  `scrollTop = scrollHeight` inside it reads a STALE
  `scrollHeight` (markdown blocks, `<details>` folds, and the
  streaming cursor resolve heights in later passes) and lands the
  viewport mid-transcript — the user reported "the newest tool card
  renders above the prompt". Any snap-to-bottom after the content
  *changes shape* must converge over a bounded number of
  `requestAnimationFrame`s, exit when the measured bottom offset
  stops moving, and `cancelAnimationFrame` on cleanup. See
  `MAX_SNAP_FRAMES` in `MessageList.tsx`. Note the asymmetry: the
  auto-scroll effect only needs a single rAF (it re-measures on
  every content change); the mount / session-switch snap needs
  full convergence.
- **`● Idle` is not a health indicator** (pre-R735). The Header now
  has a fourth `● Failed (reason)` bucket; if you touch the
  status pill, keep that bucket or the "dead engine looks
  healthy" regression comes back.
- **`QueryEngineAnchorR726Test` is red on some checkouts** for
  reasons unrelated to current work (it reads `docs/R7*.md` from a
  temp dir). Don't chase it when validating an unrelated change.
- **`currentLlmIt.next()` inside `tryAdvance` needs a top-level
  `catch (Throwable t)` that emits RunEnd (R735a).** Without it,
  any exception escaping the Spliterator (e.g. upstream 500 from
  the chat provider that escaped the retry loop) silently kills the
  stream with no terminal event delivered to the renderer, which
  falls into `● Idle` with no error banner / retry. Pin the
  contract with `tryAdvance_hasOuterTryCatchRuntimeException`.
- **An exact `Set.has()` across the wire is a latent bug (R735b).
  `failedStopKinds` must prefix-match.** The engine emits
  *decorated* stopReasons — `SpringAiChatClient.java:325` produces
  `"error: <class>: <msg>"` (~120 chars, embeds a URL) — so
  `Set.has("error")` returned false and every upstream 500 was
  classified as a success, leaving the renderer in `● Idle`. Use
  `startsWith(\`${k}:\`)` and store the **bare kind** for display.
  Guarded by `failedStopReasonR735b.test.ts`.
- **Verify the patch is inside the jar, not just that the build
  succeeded (R735b).** `mvn package` reports `BUILD SUCCESS` and
  syncs a byte-for-byte correct jar that can still contain a stale
  class — Maven's incremental compiler skips regeneration when the
  diff looks formatting-only. Extract the class from the final
  artifact and grep its constant pool:
  `[System.IO.Compression.ZipFile]::OpenRead($jar)` →
  `QueryEngine$1.class` → `$t.Contains('<your marker>')`.
  When a deploy "doesn't take", run `mvn clean package`.

## Watch out for (R736 additions)

- **The provider's tool-call contract is **adjacency-preserving
  batch**, not id-existence (Lesson 1054).** "Every tool_use has a
  matching tool_result, and every tool_result has a matching
  tool_use" is necessary but NOT sufficient; the surviving
  tool_use ids on any assistant must equal the ids of the tool
  results in the **immediately contiguous** tool-result batch,
  with no non-tool messages between. The validation message is
  `400 invalid params, tool result's tool id (call_X) not found
  (2013)` from `api.minimaxi.com` (and the bare `500 unknown
  error (1000)` from the parallel fallback). Any transcript
  contamination — L4 head-trim, mid-session retries, schema
  migration leftovers — will silently break this. The
  `QueryEngine.stripOrphanToolResults` helper has been rev3'd
  to enforce adjacency. If you add a new transcript-trimming
  step (pruning, compaction, schema migration), make sure the
  survivor of that step keeps assistants' tool_use blocks in
  the SAME positions they were declared, or you'll spawn a new
  shape that breaks the API.
- **When you strip a declaration in one pass, you owe a second
  pass to drop its now-orphaned tool_result (Lesson 1055).** R736
  rev2 only handled cross-batch aliens; rev3 added the
  `survivingUseIds[ownerIdx]` check that drops TRs whose owner
  no longer carries their id post-strip. Look for the marker
  line `no longer carries a tool_use for this id — its own pass
  stripped it as dangling` in
  `$env:TEMP\aethercode-daemon-port22888.log` to confirm the
  self-stripped branch fired for a given transcript pathology.
- **For transcript-repair work the unit tests are necessary but
  not sufficient (Lesson 1056).** The transcripts on disk after
  L4 head-trim and mid-session interruption contain shapes the
  unit tests against idealized pairs don't enumerate. Verify
  per-deploy by emitting a unique log marker in each repair
  branch and grepping the daemon log right after a `query`
  call:
  `Select-String 'R736:.*(no preceding assistant|answered a DIFFERENT|no longer carries)' $env:TEMP\aethercode-daemon-port22888.log`.
  If R736 logs a clean "no changes" line and the API still 400s,
  the live shape is one the unit tests don't model — fall back
  to dump-then-patch.

## Watch out for (R741–R743 additions)

- **A transcript-repair pass may only DROP or REWRITE-IN-PLACE.
  It may not change a message's ROLE.** R741: `TranscriptPruner`'s
  L3 `buildDedupeStub` replaced a collapsed `TOOL_RESULT` with a
  `Role.USER` side-note. That single field is what broke adjacency
  (Lesson 1054) — a USER message terminates the contiguous
  tool_result batch, producing `400 (2013)` even though
  `stripOrphanToolResults` would have found nothing wrong. **R736's
  repair pass cannot fix this class of bug** because it only drops
  messages; it can never re-create the tool_result the stub
  swallowed. If you add a new transcript pass, keep the role.
  Guarded by `TranscriptPrunerDedupeAdjacencyR741Test` (6 cases).
- **A side-note that must live inside a tool batch has to be a
  `ToolResultBlock` carrying the original `tool_use_id`**, not a
  `TextBlock` — otherwise the provider doesn't count it as an
  answer to the declaration.
- **Pair EVERY terminal `RunEnd` with `queue.offer(POISON)`**
  inside `SpringAiChatClient.runCall`. R738 fixed the streaming
  `catch`; R742 fixed the `it == null` retry-exhaustion branch
  (`<runCall(); queue.offer(POISON)>` in the runner lambda is only
  reached on a NORMAL return, so every early `return;` skips it).
  Symptom when missing: the desktop sits in green `● Idle` with no
  error banner and no ↻ retry, while the `RunEnd` sits unread in
  the queue. Guarded by `SpringAiChatClientR742Test` — which bounds
  its search to the `if (it == null) { … }` block precisely because
  R738's own test (`indexOf(POISON) > 0` anywhere later in the
  file) passed against this still-broken branch.
- **`StreamEvent` owns the stopReason taxonomy.** Use
  `re.bareKind()` / `re.isDeterministicHardStop()`; never
  hand-roll a `startsWith` heuristic. R743 found FOUR independent
  classifiers (`AetherCodeMethods` metric, `EngineContinuationDispatcher`
  metric, `TodoContinuationHook` continuation gate, and both span-status
  writers) and three of them were wrong in different directions:
  - `startsWith("loop")` missed `same_error_hard_stop` and `cancelled`
    → `incLoopStop()` under-counted the unattended stops it exists to alert on.
  - `startsWith("loop_")` missed `loop_detected: …` (that starts
    `loop_d`!) **and** `same_error_hard_stop` → the hook
    auto-continued a run the engine had just hard-stopped, silently
    undoing R739; it also resurrected R721 user cancels.
  - `startsWith("error")` missed `runtime_exception: …` /
    `cancelled` / `same_error_hard_stop` → all recorded as span
    status **"ok"**.
- **A deterministic engine decision must be honoured
  deterministically downstream (R739's lesson, one layer up).**
  When the engine hard-stops on a provably-broken pattern, no
  auto-continue path may re-dispatch it.
- **Pre-existing red to ignore:** `QueryErrorNotificationTest.query_catchEmitsRunEndAndErrorLog`
  asserts a `run_end` with `isError=true`, but since R735a the
  engine emits its own `runtime_exception: …` `RunEnd` (no
  `isError` field) first, so the protocol synthetic handler is never
  reached. Verified pre-existing on 2026-10-20 by reproducing with
  all R743 changes stashed. Reconciling the two means touching the
  R735a terminal-event path — tracked as a follow-up, see
  `docs/R741-R742-R743-bug-fixes.md`.
- **Assertj's `assertThat(String).contains(a, b)` is VARARGS** — it
  asserts the source contains BOTH strings. Passing your failure
  message as the second argument makes the test assert that the
  source contains your own message. Use JUnit's
  `assertTrue(s.contains(x))` for source-pin tests.
- **The deploy artifact is now `aethercode-cli-0.1.0-SNAPSHOT.jar`
  (56 MB fat jar), NOT `…-shaded.jar`.** The "Daemon deploy cycle"
  section above still names the shaded jar — update it if the shade
  plugin is ever removed. Desktop destination path is unchanged.
- **Comment markers (`// R741:`) are stripped by javac and are NOT
  in the class constant pool.** Verifying a fix landed in the jar
  must grep the *compiled* artefact — the new method reference
  (`ContentBlock$ToolResultBlock`), the new method name
  (`isDeterministicHardStop`), or a new log string. Verified via
  `javap -p -c` for R741.

- **R748** (scroll permanent latch + L4 self-consumption): two
  independent desktop bugs. (1) `userScrolledRef` in
  `MessageList.tsx` was a ONE-WAY latch cleared only by
  reaching the bottom again — but `distance` is measured
  against a `scrollHeight` that grows with every streamed token,
  so content growth alone armed it and no user action could
  clear it. Fix: arm only when `scrollTop` itself moved
  (`scrollTopMoved`), plus a TTL (`USER_SCROLL_GATE_TTL_MS`
  = 4s) + a wakeup timer so following resumes. `shouldFollowTail`
  must read `!isUserScrollGateActive()`, NOT the raw boolean —
  reading the boolean bypasses the TTL and restores the latch.
  (2) `stripOldestToFit` re-trimmed the FULL on-disk transcript
  every turn, so the drop counter grew ~6 messages PER TURN and
  the working window shrank 139 -> 107 inside ONE user request;
  the model forgot its own work, began re-reading files, and
  17.6% of requests were dropped by the provider (the "卡住然后
  恢复" symptom — 120s/143s/449s stalls are the R697 retry
  ladder's fingerprint). Fix: a shrink-to-fit tier BEFORE
  head-drop that uniformly clips oversized TOOL_RESULTs to a
  descending water mark. Clipping is REQUIRED, not cheaper:
  dropping a tool_result forces R736 to strip its tool_call and
  the whole turn vanishes.

- **R749** (stale `Running` after a daemon restart): the desktop
  showed "Running" for 7 minutes while `jstack` showed zero engine
  threads and the daemon had processed zero queries since boot. The
  renderer inferred "is a run in flight?" from ONE signal — "have I
  seen a chunk lately" — and after a reconnect that inference is
  always wrong: a freshly-established healthy WS connection is
  silent-but-fine, so every liveness signal reads green while
  `isStreaming` stays stuck true from the dead run. The
  `daemon.disconnected` notification that would have cleared it is
  only emitted when the ping fails / read errors / close arrives,
  and a supervisor that reconnects in between means it never fires.
  Fix: stop inferring. `inFlight` in `AetherCodeMethods` is
  authoritative — `query()` registers before any event flows and the
  `finally` removes on EVERY exit path (success / hard-stop /
  cancel / throw) — so `getState().isRunning` answers "is this run
  actually executing" with no heuristics. The renderer reconciles
  against it in TWO places: `initialize()` (the reconnect path,
  immediately) and `refreshEngineState()` (the 15s tick). The guard
  is one-directional (`isRunning === false && isStreaming`) because
  the converse legitimately happens (a run that started after our
  snapshot) and treating it as a desync would kill live runs. Also
  fixed the watchdog's own blind spot: `lastChunkTs <= 0` used to
  bail out, which is EXACTLY the window between "isStreaming = true"
  and the first chunk — a run killed there was unwatchable; it now
  falls back to `currentQuery.startedAt`.

## See also

- `docs/R719-session-anchor.md`
- `docs/R720-systematic-repair.md`
- `docs/R741-R742-R743-bug-fixes.md`
- `docs/R748-scroll-latch-and-l4-self-consumption.md`
- `docs/R749-stale-running-after-daemon-restart.md`
- `C:\Users\maijun\.minimax\agents\mavis\memory\MEMORY.md`
  (Mavis's cross-project agent memory)
- `~/.aethercode/agent-memory/default/MEMORY.md` (per-agent
  project memory that AetherCode itself maintains via
  the user-level memory subsystem)
