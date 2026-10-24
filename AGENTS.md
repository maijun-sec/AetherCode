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
- `r###-commit-msg.txt` — a SCRATCH DRAFT of the commit message for
  that round, written when the message is long enough that typing it
  inline is awkward. Use it as `git commit -F <file>` and then
  DELETE it. These are NOT tracked, and must not be.

  This entry used to say "Keep these in git for posterity", which was
  wrong on both counts and is corrected here (2026-10-23). The real
  convention was written into the rule itself on 2026-09-26
  (`2feb4b4`): *"git commit messages are written inline at commit
  time, not stored as files"*. No commit-msg file has ever been
  tracked, in any casing. The bad line arrived 23 days later, used the
  LOWERCASE spelling that the then-uppercase-only rule did not match,
  and so inferred "these are tracked" from a file that merely appeared
  in `git status`. **Lesson: read the intent where it is stated, not
  inferred from a behaviour that may be accidental.** The rule now
  matches both casings (`.gitignore` `*-commit-msg.txt`).

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

- **R750** (TUI slash-command registry has no renderer contract):
  the 2026-10-21 TUI agent-interaction benchmark found that
  `aethercode-tui/src/commands.ts` returns internal `__X__` sentinels
  (`local: "__MEMORY_PANEL__:project"`, `"__TODO_BOARD__"`, …) that each
  renderer is supposed to recognise, but **17 of the 45 sentinels are
  claimed by neither `tui.tsx` nor `line.ts`** — so the user typing
  `/memory` sees the literal text `__MEMORY_PANEL__:project` printed
  back (Ink falls through to `tui.tsx:1186-1188`
  `dispatch({type:"sideNote", message: slash.local})`; line mode falls
  through to `line.ts:1832-1834` `logAbove(colorize(slash.local, …))`).
  Affected commands: `/memory` `/memory-edit` `/todos` `/mcp`
  `/mcp-login` `/mcp-reconnect` `/agent-pick` `/effort-pick` `/cwd-pick`
  `/threads` `/consents` `/notifications` `/workflow` (bare)
  `/update` `/update-deps` **and `/mode` (bare — the Ink path has no
  mode picker; only `line.ts:1816-1831` does)**.
  The matching React components exist and are complete but unreachable:
  **27 components / 5,859 lines** are importable from neither entry point
  (`MemoryPanel` 420, `ModelSelector` 392, `ConsentModal` 379,
  `GrantsManager` 355, `ThreadSelector` 209, `AgentSelector` 207,
  `McpViewer` 201, `EffortPicker` 188, `CwdSwitcher` 176,
  `NotificationCenter` 222, `ContextMeter` 251, `TokenChart` 174, …),
  plus 11 helper modules / 3,393 lines — 38 files / 9,252 lines total.
  `state.ts:452,469` declare `agentPickerOpen` / `effortPickerOpen` and
  `state.ts:1752,1765` implement their reducer cases, but **nothing
  dispatches either action**. `tui.tsx:45` imports exactly one
  panel-style component (`TodoBoard`), and renders it as a passive
  right-column widget at `tui.tsx:2089`.
  **Why:** this is one architectural defect, not 17 bugs — the command
  registry and the renderers share no contract, so every new sentinel can
  silently fall through. `SLASH_HELP` (`commands.ts:17-94`, 88 entries)
  advertises all 17, so the bug is user-visible on first run.
  **How to apply:** when adding a slash command that returns a `local`
  sentinel, you owe a matching `if` branch in **both** renderers, or it
  must be added to an explicit handled-token set. Assert coverage with a
  reachability/BFS check over the static + `await import()` edges —
  a component nobody imports is not "done", it is dead code.
  Also note `SLASH_HELP` (88) and `SLASH_COMMANDS` (76) have drifted
  apart, and `SLASH_HELP` still carries development notes
  (`"对应历史 round: …"`, `commands.ts:29,39`) in user-facing text.
  Full benchmark + 12-dimension comparison:
  `D:\tmp\agent-experience-benchmark\agent-experience-gap-analysis-2026-10-21.md`.

- **R750a** (TUI cannot interrupt a run; "queued" prompt is discarded):
  separate from R750 and worse — it loses user input. The TUI has **no
  cancel/interrupt RPC for the main run** (the only one is
  `subagentCancel` for subagents). `Esc` in `tui.tsx:1619-1623` only
  closes the search bar / help overlay; it does not stop the turn. The
  only exit is `Ctrl+C`, which does `dispatch({type:"exit"})` + `exit()`
  at `tui.tsx:1511-1515` and tears down the daemon with the TUI.
  Worse, `tui.tsx:798-805` pushes a toast reading
  `"model is still working — prompt queued"` and then `return`s — **the
  typed text is thrown away, not queued**. `state.ts:93,803` has only
  `submitting: boolean`; there is no `pendingPrompts` / queue array
  anywhere in `state.ts`. Claude Code's equivalent (`interactive-mode.md`)
  really queues, lists queued entries in the conversation, and binds
  `Ctrl+Enter` to flush them; its `Esc` explicitly "Stop the current
  response or tool call mid-turn so you can redirect".
  **Lesson:** a toast that asserts a state the code does not implement is
  worse than no toast — the user believes their prompt is safe. Any
  "queued / will resume / in progress" string must be backed by an
  actual queue structure, or the message must be changed to describe what
  really happens (e.g. "press Ctrl+C to stop").

- **R750b** (stream-json: `tool_use` lost, duplicated, and `no_run_end`):
  four protocol bugs, three of which were invisible to source-pin tests.
  Full write-up: `docs/R750-stream-json-protocol-fixes.md`. The load-bearing
  lessons:
  - **`tryAdvance`'s contract has TWO halves, and the second is easier to
    miss.** It may not hand the action *more* than one event per call (that
    one loses events to `StreamConsumerGuard`'s probe thread — the original
    `tool_use` loss), AND it may not return `true` after handing the action
    *none* (that one desynchronises `Spliterators.AbstractSpliterator`: the
    iterator caches "has an element", `next()` finds nothing, and the next
    `hasNext()` reports exhaustion → the consumer exits before the terminal
    RunEnd → `no_run_end: engine stream ended without RunEnd`). The
    turn-start branch legitimately produces no consumer-visible event, so it
    ended with a bare `return true;`. Fixed by wrapping the body in
    `engineLoop: while (true)` and routing every such branch through
    `if (deliveredOrContinue(action)) return true; continue engineLoop;`.
    **Any new `tryAdvance` exit must answer: did this iteration actually
    deliver an event? If not, it cannot return true.**
  - **`ToolUseStart` had two emitters for one call** — the model-stream
    pass-through and `StreamingToolExecutor.Event.Started`, same id. On the
    wire that is a duplicate `tool_use`. Deduped via `announcedToolUseIds`.
    **"No orphans" and "no duplicates" are independent assertions** — the
    duplicate passed every pre-existing orphan check.
  - **`RunStart` is run-scoped, not turn-scoped.** It lived in the
    `currentLlmStream == null` branch, which every turn enters → one
    `system/init` per turn. `runStartEmitted` latch; exactly one per run.
  - **A terminal `RunEnd` must NOT go through the `pendingEvents` buffer.**
    Buffering it and then draining an older event before `return false`
    strands it — the exact green `● Idle` silent death R735a/R744 exist to
    prevent. The outer catch clears the buffer and offers the RunEnd
    directly.
  - `emitSubTaskTransitions(action)` wrote straight to the caller's action
    (emitting several `SubTaskStart`/`End` in one `tryAdvance`); it is now
    called as `emitSubTaskTransitions(stEvent -> emit(stEvent))`.
  - **Regression tests must consume with `while (it.hasNext()) out.add(it.next())`,
    not `forEach`.** `forEach` passes a real action to `tryAdvance`, so it
    cannot see over-emission *or* under-delivery; the `hasNext`/`next` loop
    is `StreamConsumerGuard`'s production shape and is the only one that
    discriminates. `QueryEngineEventDeliveryR750Test` uses it deliberately.
  - **Pre-existing red, unchanged:** `QueryErrorNotificationTest.
    query_catchEmitsRunEndAndErrorLog` (see the R741–R743 note above; the
    engine's own `runtime_exception` RunEnd shadows the protocol synthetic
    handler). Verified pre-existing on 2026-10-22 with R750 stashed.

- **R750c** (structural bounds for source-pin gates; the audit tool lied
  three separate ways before it told the truth):
  `aethercode-desktop/src/test-support/slice-structural.ts` replaces the
  fixed-character slices. `sliceBlock(source, anchor)` returns the text from
  an anchor to its matching close brace, so a bound cannot move when someone
  writes a comment. Three findings, each of which cost real time:
  - **A magic window is a latent gate.** `slice(idx, idx + 900)` is a guess
    about a function's length. It is correct once and rots silently the first
    time prose is added above the line being checked. The gate still *looks*
    like it is auditing, so the drift is invisible.
  - **Two implementations of one rule, one of them inert.**
    `matchBrace` computed `const next = skipConstruct(src, i)` and then
    discarded it, doing its own `/*` check — so the comment handling *inside*
    `skipConstruct` was dead code. Comment state now has exactly one owner.
  - **My audit tool reported a broken gate that was fine.** The first version
    of `audit-window-gates.mjs` credited a *global* assertion
    (`assert.match(tuiCode, /x/)`) against a *windowed* slice, and printed
    "586% used" for a healthy test. **Lesson 1115 — an audit tool that has
    never been checked against a known-good case will confidently report
    nonsense; a false positive costs more than no audit, because it sends
    someone to repair a healthy test.**
  - **Lesson 1116 — a test that exercises a trap must place the anchor so the
    trap is actually inside the scanned region.** The `line.ts` trap
    (`"…on memory/* — only append…"`) sits at offset 48 inside a quote span
    of 14..93, so the quote branch consumes it and the comment branch is
    *never reached there*. Three revisions of that test anchored on functions
    that closed before the trap, plus one that asserted
    `not.toContain('on memory/*')` when the string is legitimately data —
    all passing, all measuring nothing.
  - **Two implementations that agree on well-formed input cannot be told
    apart by tests built from well-formed input.** The `/*`-jump and the
    comment-state machine differ only on an *unterminated* comment; that is
    the only discriminating case, and it is now pinned.
  - **Lesson 1117 — a threshold must be measured, not guessed.** A `20 KB`
    region bound failed on correct code because the largest genuine function
    in `line.ts` is 36554 chars. Measured max, then set the bound above it.
  - Audit tool: `aethercode-tui/scripts/test/audit-window-gates.mjs`, run with
    `node scripts/test/audit-window-gates.mjs <repo-root>`. Reports per-gate
    headroom on **comment-stripped** source (via `strip-comments.mjs`), so
    surrounding prose does not count against a window. Exits non-zero only
    when a window is genuinely exhausted. Current state: 3 gates, 0 exhausted.
  - **Non-global regex + `exec()` in a while loop = infinite loop.** The
    first audit run hung for 180 s on exactly this. Every pattern in a scan
    loop must carry `g`, plus an iteration guard.

- **R363** (TUI can interrupt the run in flight): the daemon-side
  cancel was already R721-hard (`done.cancel(true)` +
  `Thread.interrupt()` + `ProcessHandle.destroyForcibly` + a 5s
  `Thread.stop`, ~7ms settle) — **every line of plumbing to reach it
  was missing.** `cancel` is keyed by `runId` and reads
  `params.runId` unconditionally, while `sendPrompt` did
  `client.request<unknown>("query")` and threw away the
  `{runId, accepted}` response. Now: `State.activeRunId` +
  `cancelRequested`, three actions (`setActiveRunId` /
  `requestCancelRun` / `setCancelSettled`), and THREE runId sources
  — the query response, `{ok:false, busyRunId}` (session-lock
  conflict), and the `stream_event` / `permission_request`
  notification params. Same path in line mode via a new optional
  `LineInputCallbacks.onEscape` that fires on a **bare** ESC only
  (CSI sequences are consumed first); `pickFromList` calls
  `lineInput.pause()` so a picker still wins Esc. Full write-up:
  `docs/R363-tui-cancel-interrupt.md`.
  - **An `if (key.escape) … return;` branch earlier in the handler
    makes a run STRUCTURALLY uncancellable, no matter what the
    reducer holds.** The decision-card collapse/expand pair sat
    ahead of every cancel candidate, so Esc could only ever fold a
    card. When adding a new "Esc cancels X" feature, check for
    earlier `return`-on-escape branches FIRST, and resolve the
    whole thing as one ordered ladder (overlays → stop the run →
    drop the queue → the pre-existing toggles).
  - **Both run-boundary transitions must clear the cancel latch,
    not just the successful path.** `submit` and `streamEnd` each
    clear `activeRunId` + `cancelRequested`; otherwise the *first
    Esc of the next turn* is a silent no-op and the run looks
    uncancellable again. A cancel RPC that **fails** must release
    the latch separately (`setCancelSettled`), or the strip says
    "stopping…" forever on a run that is still running.
  - **A daemon REFUSAL (`{ok:false}`) must not enter streaming.**
    No run is coming, so leaving `submitting` true hangs the R362
    queue drain forever.
  - **A test that writes into the directory another test compiles
    is a test-ordering landmine, not a flake.** 15 node:test suites
    wrote `_…` shims into `src/` and `unlinkSync`'d them at module
    end; `node --test` runs files in parallel, so the full-project
    `tsc --noEmit` (`phase-7-t7-10.test.mjs`) hit `TS6053 … not
    found` in the write→unlink window and went red only under
    `npm test`. Fixed at the root: `tsconfig.json` excludes
    `src/**/_*.ts{,x}`, and the three `r196-*` suites put their
    shim in `tmp/` with `--rootDir` at the package root. **When a
    source-pin test fails only in the full suite, suspect
    cross-file filesystem coupling before suspecting the code
    under test.**
  - **A source-pin gate must be watched going red before it is
    believed green.** R363's first version asserted
    `setActiveRunId` appears in `tui.tsx` — satisfied by the two
    notification fallbacks alone, so deleting the query-response
    capture entirely still passed. Its ordering assertion also
    anchored on `setDecisionCardExpanded`, so the "move the block
    below" mutation inserted it *before* the anchor (the correct
    order) and faked a pass. Fix: anchor the assertion inside the
    function under test, and anchor ordering on a landmark
    strictly downstream. `scripts/test/r363-mutate.mjs` (10 cases,
    deliberately NOT in `npm test`) is the harness that caught
    both.

- **R364** (`/cwd-pick` actually switches): R362's panel audit
  marked the cwd panel `ink: 'unavailable', line: 'unavailable'`
  with the note "the backend has no setCwd RPC". **Both halves
  were false.** `setCwd` is the **desktop's Tauri IPC command**
  (`aethercode-desktop/src/store/index.ts` → `rpc.setCwd(path, sid)`,
  handled by the Rust supervisor) — the TUI speaks JSON-RPC to the
  Java daemon and had copied that name across, so every `/cwd-pick`
  died on `-32601`. The JSON-RPC method is `switchProject`
  (`AetherCodeMethods.java:1701` register, `HttpJsonRpcServer.java:837`
  HTTP arm, `:2351-2409` the real implementation) — and `/cwd <path>`
  plus line mode's `panelCwdPick` had been calling it correctly all
  along. Full write-up: `docs/R364-cwd-rpc-name.md`.
  - **Method names are namespaced by transport.** The same concept
    has different names over Tauri IPC vs JSON-RPC. Copying a name
    without its transport is invisible to the compiler and always
    fails at runtime with -32601.
  - **Renaming the RPC would have made the UI lie *louder*.** Fixing
    only the name makes `/cwd-pick` SUCCEED while the panel still
    shows the directory the user just left — three more defects had
    to land with it: no `cwd_changed` case in either renderer (the
    daemon has been emitting it all along), no cwd anywhere in
    `State` (it was an immutable launch prop), and `getState` does
    not return a cwd at all. `State.cwd` + a `setCurrentCwd`
    action + a single derived `const currentCwd = state.cwd || cwd`
    now feed 10 call sites, including **four `sdd.*` RPC payloads**
    that were telling the daemon to build in a directory the session
    had already left. Startup reconciles against `listProjects`
    (the only read that carries the daemon's real binding).
  - **A regex comment stripper silently eats code.** Every source-pin
    gate used `src.replace(/\/\*[\s\S]*?\*\//g, …)`, and `line.ts`
    contains the string `"...no update or delete RPC on memory/* …"`.
    That `/*` reads as a comment opener and blanks the next **916
    lines of real code**. Replaced with a real scanner at
    `scripts/test/strip-comments.mjs` (tracks string + template
    literals, `${}` nesting), shared by all four gates. Only
    `line.ts` was affected, and R363's assertions all sit outside
    the window — but any future assertion inside it would have been
    silently testing whitespace.
  - **A guard built on length is decorative.** The scanner preserves
    length by design (so positional assertions stay valid), which
    made `assertNotOverStripped`'s `(orig.length - stripped.length)`
    ratio permanently 0. It has to count blanked non-newline
    characters. Measured densities: median 26.5%, max 64.1%
    (`src/rpc.ts`) — threshold set at 85%.
  - **Source-pin assertions must not be length-anchored.** Four in
    the R363/R364 gates sliced `idx ± N`; one went red purely because
    an unrelated comment pushed a block 400 chars away, and one only
    ever asserted *presence* in a window while its name claimed
    *ordering*. All are now index arithmetic between block starts or
    function-body slices.
  - **`[IO.File]::WriteAllLines` joins with `Environment.NewLine`,
    which is CRLF on Windows.** Three line-based replacements
    converted the entire `tui.tsx` from LF to CRLF — violating this
    package's convention, and the reason a mutation regex using
    `\n        }` stopped matching. Prefer the Edit tool for TS
    source; if a shell rewrite is unavoidable, verify line endings
    afterwards across `src/` + `scripts/`.
  - **A vacuous assertion looks exactly like a passing one.** The
    "a blank cwd must not wipe an established one" test ran against
    `INITIAL.cwd === ""`, so `"" === ""` held even with the blank
    guard deleted. The mutation battery caught it; the test now sets
    a real cwd first.

- **R365** (the panel registry can no longer lie): R362 shipped
  `panelIntents.ts` as "the single source of truth" with **ten rows at
  `ink/line: 'unavailable'`** — **eight of which were false.** The
  panels had been wired by that same round; the table was never
  updated. R362's six checks on the table all verified STRUCTURE (entry
  exists, gapNote present, component file resolves, ids unique) and
  none verified TRUTH, so ten plausible lies scored a perfect pass.
  Write-up: `docs/R365-panel-registry-truth.md`.
  - **A status field that can be false without failing anything is
    worse than no status field** — the next reader trusts it. The fix
    is to DERIVE the status from the renderer source instead of
    declaring it: `ink = PANEL_BY_INTENT[id] exists && tui.tsx has
    case "<panelId>":`, `line = every sentinel has a case arm in
    line.ts`. `r365-panel-truth.test.mjs` parses both and fails on
    disagreement, so changing a renderer without the registry breaks
    the suite and vice versa.
  - **`unavailable` conflates three opposite situations.** A new
    `PanelBlocker` distinguishes `not-wired` (the backend works, write
    the UI), `backend-missing` (no such RPC — do NOT write it, it would
    be a dead button) and `no-command` (the slash command does not
    exist, so the sentinel has no producer). Required whenever either
    renderer is `unavailable`.
  - **Do not advertise a command that does not exist.**
    `helpByGroup()` iterated every registry row, so `/help` listed
    `/context` — but `handleSlash` has no `case "context"`, so typing
    it answered "unknown slash command: /context. Try /help."
    `advertised?: boolean` + `ADVERTISED_INTENTS` keeps the
    placeholder row without shipping the dead link.
  - When a derived-status gate parses a source anchor (a
    `PANEL_BY_INTENT` literal, a `case` switch), assert the parse came
    back non-trivial FIRST. An empty parse flips every `mounted`
    derivation to `unavailable` and turns the gate green for entirely
    the wrong reason.
  - `aethercode-tui/src/panelIntents.ts` must stay **pure ASCII**. It
    was GBK-double-encoded once already, and that cleanup left `-?`
    where em dashes had been; they are now plain `-`.

- **R366** (cross-transport method names become an executable rule):
  R366 set out to wire an Ink workflow picker and stopped, because
  `workflow/list` turned out not to exist on the daemon the TUI talks
  to. Auditing every call site found **13 method names the TUI sends
  that this daemon does not have** — 3 real bugs plus 10 genuine gaps.
  Write-up: `docs/R366-rpc-name-contract.md`.
  - First establish WHICH server the TUI reaches. `ac-tui.ts` spawns
    `aethercode.jar --daemon`, i.e. `DaemonRunner` ->
    `AetherCodeMethods.registerAll` + `MemoryMethods`. It does **not**
    reach `SupervisorProcess` / `SupervisorRpcServer`
    (`aethercode-tasks`), which only the standalone task CLI starts.
    `workflow/list`, `workflow/run` and `task/pause|resume|kill` all
    live on that other server.
  - The three real bugs: `model/set` (DTOs exist under
    `protocol/rpc/model/` from Phase 1.2 / T-1-19, but **no handler was
    ever registered** — and a T-6-17 comment claimed "the daemon maps
    both", which was false), plus `workflow/list` and `workflow/run`.
    The real names are `setModel` and `runWorkflow`. **The param key
    changes too**: `setModel` reads `model`, not `name`, so a
    rename-only fix still throws.
  - This is the third occurrence of one disease (after R364's
    `setCwd` and R365's lying registry): a name that exists in *some*
    layer, sent to a layer that lacks it. Invisible to the compiler,
    uniformly `-32601` at runtime. **The only detection is to read the
    registration point, never the call site.**
  - `r366-rpc-names.test.mjs` turns "read the registration point" into
    something `npm test` does every run: extract every method name the
    TUI sends, check it against `registerAll` + `HttpJsonRpcServer`'s
    switch, and fail with the offending call sites named.
  - **An allowlist makes a gate only as trustworthy as the allowlist.**
    The ten backend-missing names carry a reason each, and the mutation
    battery deliberately deletes one and blanks another's reason to
    prove the list is not a blanket suppressor. One mutation breaks the
    extraction regex outright — if the main assertion were passing
    because it matched nothing, that must go red.
  - **`asRows()`'s envelope key list was missing two keys**, so
    `panelCwdPick` (`listProjects` -> `{projects:[…]}`) and
    `panelWorkflow` (`listWorkflows` -> `{workflows:[…]}`) rendered a
    **successful, silent, empty** panel. A picker that lists nothing is
    indistinguishable from one with nothing to list. The gate now pins
    `asRows` against what the daemon actually returns.
  - `phase-6-t6-17.test.mjs` had a test titled "`/model <name>` ->
    `model/set` + `/workflow` -> `workflow/run`" — pinning two
    nonexistent names as the contract. A source-pin that freezes a bug
    is worse than no test at all.

## See also

- `docs/R719-session-anchor.md`
- `docs/R720-systematic-repair.md`
- `docs/R741-R742-R743-bug-fixes.md`
- `docs/R748-scroll-latch-and-l4-self-consumption.md`
- `docs/R749-stale-running-after-daemon-restart.md`
- `docs/R363-tui-cancel-interrupt.md`
- `docs/R364-cwd-rpc-name.md`
- `docs/R365-panel-registry-truth.md`
- `docs/R366-rpc-name-contract.md`
- `D:\tmp\agent-experience-benchmark\agent-experience-gap-analysis-2026-10-21.md`
  (TUI agent-interaction benchmark vs OpenCode / Claude Code / Aider /
  Gemini; 12-dimension gap analysis + roadmap)
- `C:\Users\maijun\.minimax\agents\mavis\memory\MEMORY.md`
  (Mavis's cross-project agent memory)
- `~/.aethercode/agent-memory/default/MEMORY.md` (per-agent
  project memory that AetherCode itself maintains via
  the user-level memory subsystem)

- **R750d** (error-outcome taxonomy + `TaskScheduler.awaitIdle` barrier):
  two independent fixes. (1) `StreamEvent.isErrorOutcome()` is now the
  ONLY error classifier; `ClaudeStreamJsonEmitter`'s `OK_KINDS`
  allowlist is deleted and `AetherCodeMethods.eventToMap(RunEnd)` emits
  `isError:true` + `error`. An allowlist fails closed toward "error", so
  every future stopReason looked like a failure until someone remembered
  to update it. Note `cancelled` is a **deterministic hard stop but NOT
  an error** — hard-stop answers "may nothing re-dispatch this?", error
  answers "did this fail?"; merging them turns a user pressing Esc into an
  alert. (2) `TaskScheduler.awaitIdle` was never a barrier: it polled
  `queue` / `running` / `activeCount` one at a time, and a task moves
  through four structures, so any interleaving with a worker could see
  them all empty while a body had not run. Fixed with a single
  `AtomicInteger outstanding` incremented in `schedule()` before any
  collection becomes visible and decremented exactly once per accepted
  task. Guarded by `TaskSchedulerAwaitIdleR750Test` (4 cases).
- **Barriers must read ONE field, not a conjunction (Lesson 1120).**
  `isEmpty()` is atomic; "read four fields and AND them" is not
  observeable-consistent. Adding `pendingById.isEmpty()` to the predicate
  closed two windows and left a third (`pendingById.remove` ->
  `running.add`) — the test still failed 3 runs in 6. Adding a field moves
  the window; it does not close it.
- **Fixing a barrier can turn it into a hang (Lesson 1122).** With three
  decrement sites (cancel / worker-dequeue / runItem-finally), a
  cancelled item that is STILL in the queue gets decremented twice
  unless `cancel()` guards on `pendingById.remove(id) != null` and the
  worker guards on `wasPending`. Symptom flips from "returns too early" to
  "times out forever", and it breaks previously-green cancel tests. Every
  accepted task must be decremented exactly once.
- **Two green variants mean you are testing the wrong thing (Lesson 1119).**
  The first `awaitIdle` fix looked obviously right and its mutation run
  printed `FIXED 5/0` and `REVERTED-BUG 5/0`. If "still green after
  mutation" had been read as "the fix works", an unverified patch would
  have shipped. Complement of Lesson 1117 ("mutation didn't go red"):
  this one is "it went green either way".
- **A test you just wrote will lie to you too (Lesson 1123).** The first
  draft of the R750d watcher failed against CORRECT code because it
  cross-checked `registry.list()`; COMPLETED reaches the registry via the
  bridge listener slightly after `activeCount` drops, so it was measuring
  bridge latency, not scheduler accounting. Validate a new gate in both
  directions — red before the fix, green after.

- **R367** (grant RPC reachability + a second-transport fix): `PermissionMethods`
  and `GrantMethods` were complete, unit-tested, had their Maven deps
  declared in both poms, and **`DaemonRunner` never called either
  `registerAll`**. The TUI's `/consents` panel rendered for months while
  every RPC it called returned `-32601`. Third same-shape defect after
  R720 `memory/*` (Lesson 1022) and R366 `model/set`. Full write-up:
  `docs/R367-grant-rpc-reachability.md`. Load-bearing lessons:
  - **A reachability gate must ask WHO CALLS `registerAll`, not just who
    declares the name.** `r366-rpc-names.test.mjs` asked "is
    `permission/list` in `PermissionMethods.java`?" — yes, green. It never
    asked whether the daemon wired the class. That model gives a free pass
    to every "implemented but never connected" feature. Ask BOTH: which
    class registers it, and which file calls that class's `registerAll`.
  - **A method class reachable only through one transport is a
    one-transport feature.** `GrantMethods` had exactly ONE public member,
    `registerAll` — so `HttpJsonRpcServer`'s hand-rolled switch could not
    route `grants/*` even in principle. Fixed with four passthroughs.
    Adding to the dispatcher does nothing for the HTTP switch; do both.
  - **`routePermission`/`routeGrant` in `HttpJsonRpcServer` THROW when
    unwired; `routeMemory` RETURNS `{ok:false}`.** That asymmetry is
    deliberate. A `{ok:false}` envelope for `permission/list` is a
    LIST-SHAPED lie: `asRows()` sees an object with no array key, returns
    `[]`, and `/consents` renders "0 stored grants" with no error.
  - **`grants/list` returns `{grants:[...]}` (via `GrantHandlers` in
    `aethercode-tasks`); `permission/list` returns a bare array.** Two
    aliases for one feature, two different shapes. `line.ts`'s `asRows()`
    envelope list is now pinned against BOTH sources, not just
    `AetherCodeMethods`.
  - **Wiring an endpoint can make the lie BIGGER (Lesson 1132).** Before
    R367 `/consents` showed an honest "endpoint missing" banner. After
    the daemon was fixed, `asRows` (never called before, therefore
    broken all along) rendered a confident empty list. Re-audit every
    assumption on the SUCCESS path whenever an endpoint flips from
    unavailable to available.
  - **The `javaCode()` comment stripper in the gate had the R364 bug
    again, in Java.** `DaemonRunner.java` contains
    `// ... (the stdio daemon's` — an apostrophe read as a char-literal
    opener swallowed **2.4 KB of real code**, including the
    `http.setPermissionMethods(...)` line the gate reads. R364's TS
    stripper ate 916 lines of `line.ts` for the same reason (a `/*`
    inside a string literal). Same mechanism, different language,
    different file: **literal and comment delimiters leaking across
    states.** Found by a BYPASS mutation that deliberately disabled the
    stripper and got a red it should not have got.
  - **`EXTRA_METHOD_CLASSES` is an allowlist and had to be tested like
    one (Lesson 1120).** `declaredButUnwired` is built BY iterating it —
    deleting a class from the list means that class is no longer checked,
    and the gate stays green. Both deletion mutations passed until
    `the extra-method-class list is itself complete` was added.
  - **`src.contains(".resolve(\".aethercode\")")` was a DEAD GATE.** The
    first R367 mutation (drop that `.resolve` from the root assignment)
    left it GREEN: the same string occurs twice more in `DaemonRunner`
    for unrelated paths. Same trap as `listProjects` matching
    `listProjectsDISABLED`. Substring gates must be scoped to the
    construct they claim to check; the fix is a structural two-hop
    extraction, not a longer substring.
  - **Mutation output must name the failing assertion, not count.**
    `gate fail=1` sent me theorising about the wrong test for three
    rounds; adding "print the failing test name + assertion message"
    found the apostrophe bug in one run (Lesson 1134).
  - `PermissionGrantWiringR367Test` (11 cases) uses `@TempDir`
    everywhere — the first draft used `System.getProperty("user.home")`
    and would have written to the developer's real grant audit log —
    and a comment-stripping source pin, because a plain
    `contains("permRpcs.registerAll(...)")` is satisfied by a
    commented-out line.
  - It also pins `-32604` (not-found) on both revoke paths.
    `GrantHandlers.revoke` builds `{ok:true,id}` unconditionally and
    only reports failure because `JsonRpcProtocolException` is a
    `RuntimeException` propagating through a `Consumer<Map>`.

- **Pre-existing flaky Java test (verified 2026-10-22):**
  `WorkflowExecutorR375Test.parallelism_three_fansOut_andAggregates`
  fails under full-reactor load and passes when run alone
  (`-Dtest=WorkflowExecutorR375Test` → 7/7 green). It is a parallel
  fan-out timing assertion in `aethercode-core`; do not attribute it to
  unrelated changes.

- **Pre-existing HANG, verified by mutation (2026-10-22):**
  `aethercode-cli` `MainCliTest` never completes — it stalls the
  reactor at module 24/24 indefinitely (observed 13 min and 480 s).
  `call_acceptsPrintWithPrompt` deliberately spawns a thread that
  "will fail or hang on a fake endpoint" and only bounds it with
  `t.join(2_000)` on a **daemon** thread, so a non-daemon thread
  somewhere in the booted engine keeps the forked JVM alive.
  **Proven unrelated to R367:** with both `permRpcs.registerAll` and
  `grantRpcs.registerAll` commented out in `DaemonRunner`, the test
  still hangs. Note `Main.java:340` DOES route through
  `DaemonRunner.run()`, so "it's not on my code path" was a wrong
  assumption — the mutation is what settles it. Every other
  `aethercode-cli` test class reports green:
  `DaemonRunnerTest` 10/10, `DaemonRunnerR361V2Test` 4/4,
  `DaemonRunnerR148Test` 5/5, `DaemonRunnerPaperCompatE2ETest` 4/4,
  `DaemonProviderCascadeR343Test` 8/8,
  `MainBuildEnginePaperCompatToolsTest` 3/3.
  To run the CLI module's tests, exclude `MainCliTest`.

- **`mvn -o -pl aethercode-cli test` alone FAILS to compile even when the
  change is correct.** Without `-am`, `aethercode-cli` resolves
  `aethercode-protocol` from the local repo, so a method added this
  session "does not exist" — including `setMemoryMethods`, which has
  shipped since R720. Use `-pl aethercode-protocol,aethercode-cli -am`.
  `mvn install` is also unavailable here: `D:\cache\mvn_repo\...jar` is
  locked by the running daemon.

- **R368** (R362 gate audit — the same defect shape a fourth and fifth
  time): auditing `r362-panel-intents.test.mjs` while writing its
  missing doc found two holes in a gate I wrote. Docs:
  `docs/R362-panel-intents-and-prompt-queue.md`.
  - **`INLINE_OK` was an unchecked regex allowlist.** Sixteen sentinel
    families were excused from the registry requirement and never
    checked against a renderer. Proven by mutation: disabling the
    `__HISTORY__` handler in `tui.tsx` left all 22 assertions GREEN.
    Fixed by making the families a real array (single owner,
    iterable) plus two assertions — every exempt sentinel must be
    claimed by a renderer, and no family may match nothing.
  - **`emittedSentinels()` missed every PARAMETERISED sentinel.** Its
    two regexes both required a closing quote right after `__X__`, so
    `local: "__BUDGET__:0"` was invisible to the whole file. Measured
    before/after: emitted 35 → **44**, INLINE_OK matches 20 → **29**,
    unused families 3 → **0**. The nine invisible ones
    (`__BUDGET__`, `__LAYOUT__`, `__SNIPPET_SAVE__`, `__BOOKMARK__`,
    `__MCP_LOGIN__`, `__MCP_RECONNECT__`, `__BANK_RECALL__`,
    `__THEME__`, `__MEMORY_PANEL__`) are all user-typeable.
  - **Lesson 1120, fourth occurrence, and the general form: a gate that
    models a NARROWER world than the code produces goes green on
    everything in the gap.** An allowlist, a regex, and a capture
    pattern are three shapes of the same mistake.
  - **A source pin cannot see a dead branch.** `claimedBy` first used
    `src.includes(sentinel)`; disabling a handler with
    `false && slash.local === "__X__"` left the literal in the text and
    the gate green. No text-based gate can catch that — it is a
    structural limit. The failure worth pinning is **the token
    disappearing**, which is the one that actually ships.
  - **R362 never had a mutation battery, which is why the hole lived.**
    `r362-mutate.mjs` added: 4 cases, all caught.
  - **Measure thresholds, never guess them.** I wrote a floor of
    `relevant.length >= 25` when the real value was 29 and it went red
    for a reason I had not anticipated (Set vs Array). Same lesson as
    R750c's `WINDOW=4000`.

- **Pre-existing load-sensitive flake (verified 2026-10-23):**
  `aethercode-tui` `rpc-t6-client.test.mjs` `T-6-01: per-request timeout
  rejects with RpcCallError` fails under a loaded full-suite run
  ("timeout took too long: 229ms") and passes 9/9 alone. It is a wall
  clock assertion in a file that `git diff HEAD` shows untouched.
  Same family as `WorkflowExecutorR375Test` above. Baseline is **47**
  failures in 14 files; a 48th is this flake, not a regression.

- **R369** (honest refusals for methods the daemon lacks): `/help`
  advertised five commands that all returned -32601 — `/stop`,
  `/pause`, `/continue`, `/rewind`, `/tokens` — plus
  `/agent-pick-set-default` and `/effort <level>`. Full write-up:
  `docs/R369-honest-commands.md`.
  - **An allowlist inside a gate is not documentation, it is a product
    requirement.** `BACKEND_MISSING` lived only in
    `r366-rpc-names.test.mjs`, so when the UI needed the same knowledge
    it had nowhere to read it from. It now lives in
    `src/rpcCapabilities.ts`; the gate compiles and imports it, so
    there is one copy.
  - **The refusal lives at the dispatcher, not in the five `case`
    bodies.** `handleSlash` consults the table and rewrites the result
    to `{local: "<sentence>"}`; `routeSlash` is the unrouted inner
    function. A command added next month that names a missing method
    is then honest for free — the R364/R366 shape was one call site at
    a time.
  - **Not a silent skip.** The command stays advertised and still runs;
    it answers in a sentence instead of an error code. Hiding it would
    lose discovery; firing the request spends a round trip to learn
    something the table already knows.
  - **Every reason in that table is shown verbatim, so each must stand
    alone.** `task/pause` used to say "same as task/kill"; the user
    saw `task/pause is not available on this daemon — same as task/kill.`
    The gate now requires each reason to be > 25 chars.
  - **The line-mode `/agent-pick` and `/effort-pick` panels call
    `probe()` directly and bypassed `handleSlash`**, so they emitted a
    raw error while the Ink side already refused in words. Two
    renderers at two honesty levels is itself the defect.
  - **`for...of matchAll(...)` is structurally blind to its own
    failure to match** (the R368 hole for the fifth time): if the regex
    drifts the body never runs and nothing fires. Each extraction gets
    its own counted floor.
  - A mutation that only **disables** a guard (`n >= 7` → `n >= 0`)
    survives and should — it breaks nothing, so every other assertion
    still holds. The floor exists for extraction failure, not for a
    disabled check. Point the mutation at the regex instead.
  - `phase-6-t6-17.test.mjs` and `t457-e2e-smoke.test.mjs` had three
    assertions pinning the BROKEN behaviour
    (`assert.equal(r.rpcMethod, "task/resume")`). Both now assert the
    refusal AND use `routeSlash` to pin the mapping exactly — stronger
    than before, not weaker.

- **`aethercode-tui/src/_*.ts` test shims were TRACKED in git.**
  `_t6_17_helpers.ts`, `_t7_06_mock.ts`, `_t7_12_mock.ts` are one-line
  `export { X } from "./y.js"` files that test suites write into `src/`
  at run time; nothing in `src/` imports them. `aethercode-tui/.gitignore`
  already had `src/**/_*.ts`, but **gitignore does not apply to tracked
  files**, so every `npm test` dirtied the working tree. Untracked with
  `git rm --cached` (files left on disk). If you see `src/_*.ts` show up
  in `git status`, it has been re-added.

- **R750e** (TaskRegistry.updateStatus lost update + a lesson about "improvements"):
  `updateStatus` was `get()` then `put()` — a non-atomic read-modify-write.
  Two threads transitioning the SAME task both read the same `prev` and the
  second `put` won, resurrecting an older status. Found by a probe while
  diagnosing the `awaitIdle` barrier: 4-11 transitions lost per 3000
  sequential rounds, leaving tasks stuck at PENDING after the scheduler had
  already run them. Fixed by routing the whole read-decide-write through
  `ConcurrentHashMap.compute`, with `notify()` deliberately OUTSIDE the
  compute block (never hold a bin lock across arbitrary listener code).
  **The first version of the fix broke three existing suites** by gating
  `notify` on "status actually changed" and returning the wrong `Task`:
  `TaskRegistryTest.listener_firesOnEveryChange` (listener fires on EVERY
  call, including one ignored because the task is already terminal),
  `PersistentTaskRegistry` (appends one log line per CALL, so a suppressed
  notify silently dropped persistence records), and
  `restore_terminalStateIsSticky`. **An "improvement" that breaks three
  documented contracts is not an improvement** — read the existing tests for
  the contract before assuming a fix is behaviour-preserving.

- **A gate that cannot go red has not been tested — including when YOU wrote
  it (Lesson 1125).** `TaskSchedulerAwaitIdleR750Test`'s concurrency cases
  pass under the reverted (buggy) predicate; the original
  `awaitIdle` window is a few instructions wide (`pendingById.put` and
  `queue.put` are adjacent) and a test cannot reliably land inside it. Same
  for the registry concurrency case: a `CyclicBarrier` releases threads
  together but does NOT force a get/put interleaving. Both classes now say
  so in their javadoc instead of implying they gate the fix. What the
  registry class DOES gate (verified: reverting the null-guards turns it
  red) is the null-argument contract. When a mutation cannot turn your test
  red, say so in the test — a test named as a gate that is not one is worse
  than no test.

- **R373** (the `/workflow` Ink panel + the two lies the contract was
  hiding): the registry row `workflow-picker` sat at `ink: 'unavailable'`
  for three rounds while line mode had had a real frame since R102. R373
  mounted the Ink half — and in doing so found that the DTO's `name` was
  NOT the addressable id (`runWorkflow` resolves a FILE), and that an
  empty workflow list could not say WHY it was empty. The daemon now
  publishes an addressable `id` (the file's name) and `skipped` (the
  files it could not read); `WorkflowPaths.workflowFile` resolves
  `.yaml` AND `.yml` (it only ever did the former, so a `.yml` workflow
  was listed but could not be started, written, or deleted). See
  "Watch out for (R373 additions)" and `docs/R373-workflow-picker.md`.

- **Reverting the predicate is not the only mutation; pick the mutation your
  fix actually introduced.** Each round's gate must be checked against the
  specific change under test, and a fix that is *only* reachable through a
  specific interleaving should say that plainly.

- **Pre-existing load-sensitive flakes in `aethercode-tasks`** (not caused by
  R750d, verified by stashing the changes and re-running under identical CPU
  load): `AsyncSubAgentMiddlewareTest.start_spawnsChild` and
  `TaskHeartbeatTest.runningTaskIds_reflectsCurrentState` both time out when
  the box is saturated, and pass 8/8 without load. Do not chase these when
  validating an unrelated change. Also pre-existing and unrelated:
  `MainCliTest` blocks indefinitely (300s+, no `Tests run` line) because it
  enters the REPL; the CLI module must be run with `-Dtest=!MainCliTest`.

- **A `mvn package` that reports BUILD SUCCESS can still ship a stale class
  (re-confirmed on R750d).** The R750d null-guards were in the source and in
  `aethercode-tasks/target/classes` but MISSING from the packaged jar —
  incremental compilation had not recompiled `TaskRegistry` because only the
  lambda body had changed. `javap -p -c` on the module's classes showed the
  guards, the jar did not contain the strings, and `mvn clean package` fixed
  it. **Always grep the string constant pool of the FINAL artifact**, not the
  module's `target/classes`. Related to Lesson 1117: a green build is not
  evidence the change is in the artifact.

## Watch out for (R370 additions)

- **A path is not a string.** src/cwdMatch.ts owns `sameCwd` /
  `partitionByCwd` / `cwdBasename`; the session picker and every cwd
  comparison on the /threads and /sessions` paths read it. Raw `===`
  on a cwd is wrong four ways (case folding on Windows, a trailing
  separator, separator style, and the row with no `cwd` key at all), and
  each one empties the list it is filtering. `listSessions` OMITS `cwd`
  for sessions it cannot attribute (`AetherCodeMethods.java:5592`), so
  "unknown" must be its own state, not folded into "different" — the
  picker renders `cwd unrecorded` for it, distinct from `cwd≠`. Note
  that stripping the trailing separator off `C:\` yields `C:`, which
  Windows reads as *the current directory on drive C*; the canonicaliser
  refuses to shorten a bare drive root.
- **A param key must be read off the handler, never remembered.** R370 is
  the third instance of one mistake: `line.ts` sent `loadSession { id }`
  while `AetherCodeMethods.loadSession` reads
  `stringOrThrow(p, "sessionId")`, so every cmd.exe thread switch died
  on `-32602` AFTER printing the table it invited the user to pick from.
  Ink sent `{ sessionId }` all along, which is why only the non-TTY path
  was broken. R364 was `setCwd` (a Tauri IPC name used as a JSON-RPC
  method), R366 was `workflow/list` (a `SupervisorRpcServer` method).
  `r370-session-picker.test.mjs` now DERIVES the key out of the Java
  source instead of restating it.
- **Scope every source extraction to the block you mean.** The first R370
  gate scanned the whole file and took the first `request("loadSession"…)`
  match — which is `viewPrimary`'s at `tui.tsx:2398`, 600 lines before
  the picker — so reverting the PICKER to `{ id }` went green. Same
  failure, sixth occurrence (R362's regex, R368's `matchAll`, R1114's
  unreachable branch): an extraction that silently resolves to a different
  instance of what it is looking for. Every bounded read uses brace
  matching or `matchAll` with an explicit counted floor. The threads
  fetch dispatches `setThreads` TWICE (`[]` on the error path, the real
  rows on success); a bare `.exec` reads the error one.
- **Do not locate things by a guessed character window.** The first R370
  gate draft used `[\s\S]{0,400}?`. Two of the three assertions using
  it were wrong for reasons unrelated to the code under test. A guessed
  threshold fails by going GREEN. Lesson 1138, applied to a gate instead of
  a threshold — use `block()` (brace matching) in the R370 gate.
- **A gate mutation that edits the gate alone must not be expected to go
  red.** "Weaken this assertion, then require the weakened assertion to
  complain" is a category error, and it manufactures phantom holes. The
  R370 battery's `GATE:` cases pair the gate edit with a REAL product
  defect and pass when ANOTHER assertion still catches it. One such case
  was deleted rather than fixed: the renderers' params-key assertion is
  the only thing that asks "do WE send the daemon's key", while the Java
  cross-check asks "what does the daemon want" — demanding redundancy
  there is demanding a second copy of the same question.
- **A panel claim can be false about the CODE and true about the FEATURE.**
  `adoptSession` clears `turns` on a session switch because this
  renderer has NO messages→`Turn` converter (`getTranscript` is
  registered on `HttpJsonRpcServer:1018` but nothing consumes it).
  Keeping the old turns would show session A's history while the model
  answers in session B. The switch toast says so explicitly:
  "history is not replayed here". Hydration is tracked as its own round in
  `docs/R370-cwd-session-picker.md`.
- **Moving a helper out of a gate invalidates that gate's mutations.**
  `javaCode` moved from `r366-rpc-names.test.mjs` to
  `scripts/test/java-strip.mjs` in R370 so the new gate could reuse it
  instead of keeping a second untested copy. The two r366 `javaCode`
  BYPASS mutations went NO-OP on the day it moved — the same lesson as
  `BACKEND_MISSING` moving to `rpcCapabilities.ts`, second occurrence.
  The harness's no-op detector is what caught it; keep reporting NO-OP
  separately from PASS.
- **A blanket invariant about panels is false.** "Every mapped panel must
  have a fetch branch" failed on `todo-board`, which is correctly fed by
  `todo_update` notifications off the event stream rather than by an RPC.
  Scope such claims to the panel under test.

## Watch out for (R371 additions)

- **A method class with a `registerAll` hook is not wired until
  PRODUCTION CODE CALLS IT.** `CompactMethods` has been dead since
  T-193: `CrossCuttingMethodsT500Test` asserts all four `compact/*`
  names are registered — by calling `new CompactMethods()` itself on
  a dispatcher it built. The assertion is green and structurally
  incapable of failing. So `compact/status` / `run` / `reset` /
  `history` answered `-32601` on every daemon, `src/compact-rpc.ts`'s
  remote mode had never worked, and `/context` had nothing to read.
  R371 wires it in `DaemonRunner.runInternal` + `runHttp` and adds
  four `HttpJsonRpcServer` case arms. **Lesson 1156: the check must
  ask "does a production path call X", not "does X exist".** Same
  family as R1121's substring anchor.
- **A method-overload chain makes `indexOf` a trap.** `runHttp`
  has THREE overloads and two are one-line delegates; the real one is
  25 000 characters. `javaMethod(src, "static int runHttp(")`
  returned the 72-char delegate, so the R371 gate reported a defect
  that did not exist — twice. `java-strip.mjs`'s `javaMethod`
  takes an `occurrence` for this. **Lesson 1157: "the extraction
  succeeded" and "it read the right thing" are different claims.**
- **A source pin cannot see a dead branch.** `if (false) { throw … }`
  keeps the token and passes a `/throw/` assertion while the unwired
  daemon answers a value the TUI reads as real. `routeCompact`'s
  gate is POSITIONAL: the `cm == null` guard must still exist and
  the throw must be within reach behind it.
- **Coercing `null` to `0` fabricates a measurement.**
  `ContextInfo.inputTokens` became `number | null`; the meter
  renders `— (no usage reported yet)`. Before the first usage event
  there is no reading, and `0%` against a fallback window looks
  measured. Same rule on the poll: an `ok:true` answer carrying
  `contextWindow: 0` is rejected, not divided by.
- **An assertion naming one command records an EVENT, not a RULE.**
  Three pre-existing gates turned red when `/context` stopped being a
  placeholder. r365's `!cmds.includes("/context")` is now written
  against `blocker === "no-command"` plus a POSITIVE check that
  every advertised row answers. r362's `total < PANEL_INTENTS.length`
  asserted "today's table happens to hide a row", not that the filter
  works — replaced by a synthetic row plus a control. **Lesson 1158.**
- **A mutation aimed at a shape that no longer exists is DELETED, not
  retargeted.** r365-mutate's "re-advertise /context" became
  unexpressible when `/context` was fixed. Pointing it at a
  `not-wired` row survived — hiding a genuinely-unavailable row from
  `/help` is CORRECT, so the retarget silently swapped the rule.
  The four no-ops the harness caught were all real measurement holes.
- **Seven of R371's fixes were to the TEST, not the product.** A gate
  that has just been shown to fail on correct code needs its own
  scrutiny; run its mutation battery before trusting the new version.

## Watch out for (R372 additions)

- **A 「诚实但残缺」的降级会让缺口不可见。** R370 的 `/sessions`
  做完后选会话能真的继续,但滚动区是空的,toast 写着
  `history is not replayed here`。那句话是诚实的 —— 所以它不触发
  任何失败,但它是**症状**不是结论:renderer 里没有
  messages→turns 转换器,而 daemon 一直有数据(`getTranscript`
  早就注册了,只是没人读)。R372 补上后这句话才变成真的。
  **Lesson 1162.** 推论:降级文案要带「待办」信号 ——
  「history is not replayed here」读起来像产品特性,
  「history could not be read」读起来像欠债。
- **`src/transcriptHydrate.ts` 是纯函数,门禁测行为不测源码。**
  21 条断言里大部分是「输入 → 输出」:源码钉看不见「tool_result
  变成了自己的 turn」。**Lesson 1165 — 能跑就跑。**
- **回填的三样东西不许编。** ① 时长 —— 回填的工具调用没有起止,
  `toolStartedAt` 留 undefined,`ToolCard.computeDuration` 本来
  就渲染 `—`;② 没有结果的调用 —— 渲染成
  `(no recorded result — the run was interrupted)`,不是空成功;
  ③ 完整历史 —— 只回放尾部 200 条,**并且报出被丢掉多少**。
- **孤儿 tool_result 有两条规则,第二条才是容易错的。** ① 不产生
  turn;② **不污染前一个调用**。「挂到最后一个 turn」是最自然的
  实现,而它会静默覆盖真实结果。
- **「出现一次」和「每条路径都出现」是两种断言。** 变异删掉**失败
  路径**的 `adoptSession`,门禁全绿,因为成功路径那处还在。
  用 `catchBodies()` 按分支拆。**Lesson 1163.**
- **切换和回填必须是 ONE action。** `adoptSession` 带可选
  `turns`。两个 dispatch(先清后填)会留下一个窗口:滚动区空的,
  头部却已经写着新会话 —— 用户在这个窗口里打字,是在跟一个
  看不见的对话说话。
- **变异用例的目标会随产品演进而腐烂。** R372 改了 reducer 形态和
  toast 文案,r370-mutate 两条变异**目标文本不存在了** → 静默
  no-op。no-op 检出器在这轮抓到 4 个:2 个目标过期、1 个**测 R372
  的行为却跑 R370 的门禁**、1 个是「中和一条断言 + 引入只有另一条
  断言能看见的缺陷」(按构造就是单点故障,证明不了冗余)。
  **Lesson 1164.**

## Watch out for (R373 additions)

- **变异电池会原地改写产品源码,所以绝不能和测试套件并发跑。**
  R373 收尾时后台跑 `npm test`、前台同时跑 9 个电池,全量多出 2 个
  失败(`resp.runId` 那条、INLINE_OK 那条)—— 两条**单跑都是绿的**。
  原因是 `r363-mutate.mjs:41` 正好删掉 `src/tui.tsx` 里
  `const runId = resp.runId ?? …` 那一行,而 r363 的门禁正在读同一个
  文件。**Lesson 1166.** 遇到「多出 N 个失败」,先问「我的验证流程
  是不是自己污染了」—— 判据是那几条**单跑绿不绿**。这次污染是
  「多出失败」,运气好;反过来就是**掩盖**一个真回归。
- **DTO 的字段名不保证它就是可寻址的 id。** `listWorkflows` 回
  `name`(YAML 声明的显示名),`runWorkflow` 按**文件名**解析。文件
  `deploy.yaml` 声明 `name: ship-it` 时,列出来是 "ship-it" 但按
  "ship-it" **启动不了**。R373 让 daemon 公布可寻址的 `id`
  (工作流**文件名**,带扩展名 —— 带扩展名才能让 `foo.yaml` 和
  `foo.yml` 同目录时无歧义)。**Lesson 1167.**
- **同一族文件的「枚举」和「解析」必须认同一批后缀。**
  `WorkflowReader.list` 收 `.yaml` 和 `.yml`,而
  `WorkflowPaths.workflowFile` 只会拼 `.yaml` → `foo.yml` 能被列出
  却**启动/写入/删除全失败**(三个操作共用这一个函数)。
  `workflowFile` 现在按 `.yaml` → `.yml` → 默认 `.yaml` 解析。
  **Lesson 1168.**
- **断言「公布的 id 能解析回文件」要用全列表,不要挑一行。** 我第一版
  挑了 `.yaml` 那一行,全绿;把 `.yml` 放进去才红 —— 一行断言对
  「只有某一行没被修」的缺陷是盲的。
- **一个分支如果只能用某个 OS 的手段触发,它就等于没被测。** 「让文件
  读不出来」的自然做法是**锁文件**,但文件锁在 Linux 上是**建议性**
  的,一个基于锁的 fixture 会在一半平台上自己跳过自己。
  `WorkflowReader.scan(cwd, reader)` 因此注入了 reader,分支到处可测。
  **Lesson 1169.**
- **源码钉要钉「调用」,不是「标识符出现过」。** 断言
  `/workflowEmptyState/` 会被 `import { workflowEmptyState } from …`
  满足 —— 渲染器不再调用该函数,门禁照样全绿。改用
  `/workflowEmptyState\(/`(`name(` 才能区分调用和 import)。
  **Lesson 1170.**
- **提取器的 header 在结构「内部」时,要往回找开口括号。** registry 行
  的 `id:` 在对象字面量**里面**,所以「header 之后的第一个 `{`」读到的是
  **下一行**。用 `enclosingObject()`(回退到最近的 `{`)而不是
  `block()`。
- **R373 的行模式也有同一个寻址 bug。** `panelWorkflow` 原来把
  `nameOf(w)`(显示名)发给 `runWorkflow`;现在发 `idOf(w)`,并保留
  `name` 作为老 daemon 的回退。
- **`runWorkflow` 报的是 "accepted",不是 "finished"。** 它把工作流丢到
  daemon 线程上就返回了,结果走 stream 事件。toast 必须说
  `workflow deploy.yaml accepted (run wf-3)`,说 "started" 就是在
  声称这次调用没有报告过的结果。
## Watch out for (R374 additions)

- **blocker 词汇是「指令」,不是标签。** `not-wired` = 后端能用、
  只是没写 UI(**去写**);`backend-missing` = 后端根本没有这个
  RPC(**别写**,写了就是死按钮)。R374 发现 `update-available` 标了
  三个轮次的 `not-wired`,而 daemon 压根没有任何 update RPC
  (无 `update/check`、无 `update/refreshDeps`、无 `update/install`、
  reactor 里没有 updater 模块)—— 正确分类是 `backend-missing`。
  **blocker 必须从后端重新推导,不能从「缺哪个 UI」倒推。**
- **「别做 UI」本身是一个交付物。** 后端缺失时,诚实的答案是:一个
  说清**什么都没发生**、并指出可行路径的面板,而不是一个带死按钮的
  弹窗。R374 因此**没有**挂 `UpdateAvailable` / `UpdateConfirm` /
  `UpdateProgress` —— 每个都要一个这个 daemon 产不出的事实
  (「有可用更新」/ `latest` 版本 / 下载在途)。
- **按成熟工具的方式拆动词。** Electron `autoUpdater` 拆
  check → download → install(且「已下载的更新会在下次启动时生效」),
  Homebrew 拆 `update`(刷索引)/ `upgrade`(装)/ `outdated`(只报)。
  合成一句话就是 UX bug。R374 落成三个 verb:`/update`(check)、
  `/update install`(install)、`/update-deps`(deps)。
- **「不可用」要靠探测得出,不能写死。** 面板调 `UPDATE_ENDPOINTS[verb]`,
  按 JSON-RPC 错误码 `-32601`(`METHOD_NOT_FOUND`)判断后端缺这个方法。
  硬编码 "unavailable" 的面板在后端落地后**还会继续说谎**。
- **`null` 不是 `0`。** daemon 的 `ping` 从 jar manifest 的
  `Implementation-Version` 读版本,普通 `mvn package` 不写它 ——
  所以「未上报」是**常态**,渲染成 `not reported by the daemon`,
  绝不能是 `0.0.0`。
- **描述文案在面板能纠正你之前就被读到了。** T-430 留下的
  "review an available update" / "confirm a dependency refresh" 两句
  都是谎言(没有可 review 的 update,也没有可 confirm 的刷新)。
  `/help` 的文案是第一手真相,必须和后端能力一致。
- **可达但没登记的动词等于半个功能。** `/update install` 从 T-430
  就存在,但 catalog 只登记了一个形状 → 第三个 verb 一直不可见。
  catalog 的 `args` 字段(`args: "[install]"`)是让可选子动词在
  `/help` 里显形的地方。
- **一个性质只有一条推导时,那条断言可以被悄无声息地废掉。** R374
  变异电池发现:中和掉映射断言后,门禁仍然全绿 —— 因为其它断言
  直接调 `updateHeadline("install", …)`,**不经过映射**。修法是补
  一条**经过映射**的第二推导:
  `updateHeadline(updateVerbForSentinel(s))` 必须逐个不同。
  **推论:GATE 变异只有在缺陷还有第二条独立推导时才有意义。**
- **没有花括号的 `case` arm 不能用花括号配对来提取。** R374 门禁
  的 `block()` 从 `case "__UPDATE_AVAILABLE__":` 往后找 `{`,
  走进了**下一个** arm(读到一个 `sdd.start` case)。用 `caseArm()`:
  切到下一个同缩进的 `case` 为止。
- **断言「引用了某个轮次」会被散文里的旁注满足。** gapNote 正文
  解释「R365 的旧 blocker 是错的」,于是把开头的 `R374:` 删掉,
  `/R\d{3}/` 照样匹配。断言必须钉**开头**:`/^R\d{3}:/`。
- **门禁的 tsc 和项目的 tsc 不是同一个编译器。** 项目
  `tsc --noEmit` 读 `tsconfig.json`(`strict: true`);而
  `r43` / `r77` / `r78` / `r79` / `r94-e` 这几个门禁把文件**直接写在
  命令行上**(`tsc … src/tui.tsx`),此时 **`tsconfig.json` 被完全忽略**,
  走的是编译器默认值(`strictNullChecks: false`)。
  而在非严格模式下,TypeScript **不会**用布尔字面量去收窄联合类型:

      declare const p: { ok: true; raw: number } | { ok: false; err: unknown };
      !p.ok && f(p.err);        // 非严格: TS2339 Property 'err' does not exist
      declare const q: { state: "ok"; raw: number } | { state: "err"; err: unknown };
      q.state === "err" && f(q.err);   // 两种模式都通过

  R374 的 probe 结果原本是 `{ ok: true, raw } | { ok: false, err }` ——
  项目编译绿灯,5 个门禁**根本编译不过**。改用**字符串判别**
  (`state: "ok" | "err"`)后两种模式都过。
  **推论:本仓库新增的 TypeScript 必须同时满足项目和门禁两个编译器。**
  写完 `tsc --noEmit` 不算数,还要跑一次**不带 tsconfig 的**
  `tsc --outDir … --target ES2022 --module ES2022 --moduleResolution bundler
  --jsx react src/<file>.tsx`。
