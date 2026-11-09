
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
- **R376** (the format contract): the TUI gets its own
  `.prettierrc.json` (printWidth 100, double quotes, `semi`,
  `trailingComma: all`, `arrowParens: always`, `endOfLine: lf`,
  2-space) — measured against the TUI's own line-width distribution
  (p90 = 68 before, 67 after; >100-column lines 374 → 75), not
  against prettier's defaults. `format` / `format:check` scripts in
  `aethercode-tui/package.json`; root `format:tui` /
  `format:check:tui` aliases. The enforcement is a GATE
  (`scripts/test/r376-format.test.mjs`, 3 assertions) inside
  `npm test`, so "format before commit" is enforceable rather than
  remembered. Cost: 213 files reformatted, which broke 13 gate
  source-pins and turned **45 mutation needles into no-ops** across
  all 12 batteries — a no-op needle reports "MUTATION SURVIVED", so
  a formatter reflow can silently blind a gate with every dashboard
  green. All pins/needles are now whitespace-, quote- and
  number-spelling-tolerant. Final: 1345/1302/43, 0 new failures vs
  the R375 baseline (45), 12/12 batteries green AFTER the format.
  Doc: `docs/R376-tui-format.md`.
- **R377** (a refusal must never hide a working feature): the TUI's
  refusal table claimed `effort/set` was missing — the daemon calls the
  thing a **variant** and has served it as `switchVariant` on BOTH
  transports since R285 (the desktop's Quality dropdown drives it). The
  TUI's own comment carried the "verification" that produced the lie:
  the method registry has no "effort" in it, it has a **synonym**. The
  entry is gone; `/effort` is real in both renderers via
  `EFFORT_METHOD = "switchVariant"` + `{ variant }`, and both report the
  daemon's **resolved** `activeVariant` — never the request, because
  `Variant.byName` falls back silently. The three genuine gaps now
  ground their reasons in the daemon's own identifiers, and the R377
  gate checks that each quoted identifier exists in the daemon's
  **code** (comments stripped). The refusal also guards EVERY path:
  Ctrl-Z called `rewind` directly, so the slash command answered in
  words while the same key press emitted a raw `-32601`.
  **Lesson 1193 — a refusal is a claim about ANOTHER system's vocabulary
  and cannot verify itself.** Doc: `docs/R377-capability-naming.md`.
- **R378** (the help must be GENERATED from the truth): the TUI carried
  THREE hand-written copies of the key-binding list — the Ctrl-? help
  overlay, the welcome screen's grid, and `--help` — and they had
  already contradicted each other AND the handler. The welcome said
  "Ctrl-T — theme picker" (the handler binds Ctrl-T to re-open the
  welcome; the theme picker is Ctrl-SHIFT-T); the overlay said "Ctrl-L
  — clear scrollback" (that chord was a dead placeholder that shadowed
  the log viewer, so the viewer was unreachable); the overlay listed
  **13** chords where the handler binds **35**, and the missing ones
  included **Esc to stop a running turn** — the one binding R363
  exists to provide. Eight development notes leaked into user-facing
  text. Now ONE registry (`src/keybindings.ts`) serves all three
  consumers, and the R378 gate ties it to the handler in BOTH
  directions: every chord the handler binds is documented, and every
  chord documented is really bound. A chord whose RPC is on R377's
  refusal table is marked unavailable, so the help cannot promise what
  this daemon cannot do.
  **Lesson 1197 — a second copy of a truth drifts, and it drifts
  silently.** Doc: `docs/R378-help-truth.md`.
- **R379** (three REAL gaps closed, the countdown caught, the panels'
  key tables generated): the TUI's refusal table carried three entries
  that were not name mismatches (unlike `effort/set`) — the daemon
  genuinely lacked the capability. `agent/setDefault` now persists the
  default AND installs the agent's body into the live system prompt
  (`QueryEngine.defaultAgentBlock`, appended in BOTH
  `effectiveSystem()` and `buildSystemForTurn()`) — a marker nobody
  reads is a fake success; `rewind` rolls the transcript back in memory
  AND on disk (`target` = 0-based index into the USER messages; the
  picked one is kept); `session/tokens` reads a PER-SESSION bucket
  (`CostTracker.sessionSummary`, unknown sessions land in a real
  `__unknown__` bucket, never dropped and never coerced to 0). All
  three are registered on stdio AND routed on HTTP. The daemon had been
  publishing `todo_continuation_countdown` all along — both renderers
  dropped it, so an automatic turn began with no warning; now the line
  reads `Continuing automatically at 3:45pm · esc to cancel` and the
  "esc" half calls the RPC that actually stops it. The panels' key
  tables live in ONE registry (`src/componentKeys.ts`) and the R379
  gate ties them to each panel's `useInput` handler in BOTH directions.
  Two infrastructure bugs bit this round and are fixed structurally:
  the mutation snapshot could outlive a clean run and silently REVERT
  the next round's work, and a needle that hit a JSDoc comment
  reported a hole for the wrong reason.
  **Lesson 1199 — the same truth twice in ONE file hides bugs just as
  well as two files.** Doc: `docs/R379-gap-closers-and-countdown.md`.
- **R380** (the command surface has ONE list): the slash-command
  surface was described by FOUR lists — a flat `string[]` the Tab
  completion and the command palette read, a structured catalog the
  InputBox dropdown read, a hand-written group table the `/help`
  renderer walked, and the router's own `case` arms — and they had
  already drifted: `/demo` was in the catalog but NOT in the flat
  array (so it was neither Tab-completable nor in the palette),
  `budget` appeared TWICE in the flat array, and the router served ten
  commands the catalog had never heard of — including `/effort`
  (R377's own feature, invisible in `/help`) and the permission
  shortcut whose comment called it "discoverable in one word". The
  comments describing the structure were wrong too ("source of truth
  for autocomplete is SLASH_COMMANDS_DETAILED" — it was
  `SLASH_COMMANDS`; "mirrors the entries in SLASH_COMMANDS" — it did
  not). Now the catalog is the only list: each entry carries its
  `group` (so a command cannot be in the catalog and missing from
  `/help`) and, for an alias, its `aliasOf` (so the alias mention in
  the help text is derived, and the flat name list is the catalog's
  projection). The R380 gate checks BOTH directions — every `case`
  the router can reach is declared in the catalog, and every catalog
  entry is routable. A one-way check would have left half the drift
  standing. `/help` is byte-identical before and after.
  **Lesson 1207 — one "list" can be several lists, and every one of
  them drifts.** Doc: `docs/R380-one-command-list.md`.
- **R381** (reachability is MEASURED, prose is a table too, and a scanner
  that could not see): R379's own "nine reachable components" list was
  wrong — `NotificationSettings` is imported only for its toggle
  REGISTRY and `NotificationDetail` by nobody at all, so only SEVEN of
  the nine can be opened. `scripts/test/reachability.mjs` now walks the
  import graph from the entry points and resolves every JSX tag to the
  file it really comes from (`Welcome.tsx` renders a `<Pill>` but
  defines its OWN `Pill`, so `components/Pill.tsx` is dead). Three real
  bugs fell out of the rest of the round: (1) `ts-scan.mjs` treated an
  apostrophe in JSX text as a string opener and **swallowed 47 lines of
  `src/tui.tsx`** — including a whole panel's render — so every
  assertion over that region was reading whitespace and none of them
  knew it; the rule now matches `strip-comments.mjs` (a quote with no
  partner on its own line is PROSE, and the text around it stays
  visible). (2) `SubagentSpawnCard` advertised "(Enter expand)" while
  its `setOpen` was **never called** — the card could never expand, and
  the description was never shown. (3) The welcome screen taught
  `/spawn` and `/plan` — **neither command exists** — and described
  Claude Code's plan mode, while the daemon's `PermissionMode.PLAN` is
  a permission mode. Twelve components now render their hints from
  `src/componentKeys.ts`, which grew PROSE sentences (a sentence names
  its keys and says who binds each one) and a `via: "global"` kind (for
  the components with no handler of their own); the component
  vocabulary adopted the app vocabulary's word for Enter (`return`), so
  a global chord can be checked against `src/keybindings.ts` directly.
  The R381 gate measures reachability, cross-checks every table against
  its handler in BOTH directions, resolves CONSTANTS (SessionControl
  binds `c`/`p`/`s` through `HOTKEY`, and a literal-only scan saw
  NOTHING), and pins the not-reachable set so wiring a dead panel up
  turns the gate red.
  **Lesson 1212 — a scanner with no self-test has its bugs found by
  whichever gate happens to trip over them, which is not a plan.**
  **Lesson 1214 — "reachable" is a measurement, not a claim.**
  **Lesson 1217 — a needle that is not unique lands on the wrong
  occurrence and pretends to have tested something; a check a LOOKUP can
  satisfy is no check at all.**
  Doc: `docs/R381-panel-keys.md`.
- **R382** (the build was never green): packaging 0.2.72 surfaced three
  defects that had made a **full** release build impossible since R747.
  (1) `--print` without an argv prompt read stdin **to EOF**; surefire's
  fork stdin is an open pipe that never closes, so `MainCliTest` blocked
  on `System.in.readLine()` **forever** and took `mvn install` down with
  it. Every recent "BUILD SUCCESS" in the helper log was a `-pl`
  **subset** — the full reactor had not completed since R747. The plain
  `--print` path now reads only the **already-buffered** bytes
  (`Main.readBufferedPrompt`); the **stream-json** path still reads to
  EOF, because the protocol requires it (the peer keeps the pipe open
  for `control_response` frames).
  (2) `aethercode-evals`' `BenchmarkReportRealLlmTest` issued ~45 **live**
  model calls (~270k input tokens) inside the default test run — its own
  javadoc said "run on demand". It is now opt-in via
  `-Daethercode.evals.realLlm=true`; the two single-call smoke tests
  beside it stay on the env-var gate.
  (3) The `@tauri-apps/*` npm packages had floated to newer minors than
  the pinned Rust crates, and **nothing compared the two** — `tauri
  build` failed with "Found version mismatched Tauri packages". The npm
  side is now pinned to the crates' minors, and
  `aethercode-desktop/src/__tests__/tauriVersionPairingR382.test.ts`
  keeps them paired (rule: **same major.minor** — the two sides have
  different patch cadences, so exact equality is a rule the ecosystem
  cannot satisfy).
  **Lesson 1219 — a build that has not run to completion is not a build;
  "BUILD SUCCESS" must name WHICH build.**
  **Lesson 1220 — a test that needs a secret, the network and 20 minutes
  is a job, not a test; "the key is set" is not "please run".**
  **Lesson 1221 — two floating ranges on two sides of a wire will drift.**
  **Lesson 1222 — read a tool's contract from its SOURCE, not from its
  error message.**
  **Lesson 1223 — one host being unreachable does not mean the artifact
  cannot be fetched.**
  **Lesson 1224 — the numbers in a document are assertions too.**
  Doc: `docs/R382-the-build-was-never-green.md`.
- **R383** (the verdict comes from the daemon): the engine
  **DECORATES** its stop reasons (`"error: <class>: <msg>"`,
  `"loop_detected: empty_tool_input (…)"`), so every client that wants
  to know "did that run fail?" has to re-parse the string — and the TUI
  had **two** such parsers (`state.ts`, `line.ts`) and the desktop a
  third, all three wrong: the TUI matched `"error_"` / `"_error"` with
  an UNDERSCORE while the engine emits `"error:"` with a COLON, so the
  single most common failure (an upstream 4xx/5xx) was classified
  `"ok"` and the StatusBar showed a green **"✓ ready"** (4 of 13 real
  reasons misreported); the desktop's list carried the DISPLAY kinds
  `"loop"` / `"max_turns"` (which the engine never emits) and **missed
  `"loop_detected"`**, so a death spiral showed as **● Idle**. The
  daemon already knew: R750d put `isError` on the wire, and R383 adds
  `stopKind` (the engine's bare kind) beside it — so
  `AetherCodeMethods.eventToMap`'s `run_end` branch now always carries
  `stopKind` and carries `isError` exactly when
  `StreamEvent.isErrorOutcome()` says "failure". The TUI's rules moved
  into ONE module (`src/stopKind.ts`): the verdict comes from the
  daemon, the kind comes from the daemon, and the local failure set is
  only a FALLBACK for a daemon that expresses no verdict — pinned
  kind-for-kind against `StreamEvent.isErrorOutcomeOf` by the new gate.
  `StatusBar`'s second label table (`STOP_LABEL`, keyed by the raw
  reason) was **deleted**: it could never hit a decorated reason, it
  disagreed with the classifier (`same_fingerprint` said "stopped: loop"
  while the classifier said "ok" — the words in the success colour),
  and 4 of its entries were **detector kinds, not stop reasons**.
  The old gate had pinned the two TUI copies to EACH OTHER
  ("state.ts and line.ts classifyStopReason are equivalent") — two
  copies that are wrong together agree perfectly.
  **Lesson 1225 — a renderer never decides on its own whether a run
  failed.**
  **Lesson 1226 — a gate that pins two copies to EACH OTHER is not a
  gate; ask how MANY there are, not whether they agree.**
  **Lesson 1227 — a decorated wire string means every client writes its
  own parser, and every parser is wrong; put the kind on the wire.**
  **Lesson 1228 — a table keyed by the reason and a table keyed by the
  kind can contradict each other, and the contradiction is invisible.**
  **Lesson 1229 — extract a Java switch's case labels ARM by ARM: a
  `case "a", "b" -> true;` names TWO labels, and taking only the first
  silently halves the taxonomy.**
  **Lesson 1230 — "the key is present" is not "the value is used":
  `isError: undefined` satisfies `/isError\s*:/` while throwing the
  verdict away.**
  **Lesson 1231 — a loop detector's kind is not a stop reason:
  `same_fingerprint` / `same_error` / `long_output` / `user_interrupt` /
  `empty_tool_input` are `ProgressLoopDetector.lastLoopKind()` values
  and travel INSIDE the decoration, never as a bare kind.**
  Doc: `docs/R383-the-verdict-comes-from-the-daemon.md`.
- **R384** (ONE run_end frame, ONE contract): the `run_end` frame's
  contract was written down in `AetherCodeMethods.eventToMap`, but
  **three** code paths assembled the frame — `eventToMap` (the
  engine's own `RunEnd`), the R745 synthetic terminal (the consumer
  ran out of events without a `RunEnd`), and the `catch (Throwable)`
  terminal. The two synthetic ones built the frame BY HAND and
  **omitted `stopKind`** — the field R383 had just put on the wire.
  Since R383 made "the verdict comes from the daemon" the RULE, a
  client reading `isError` and expecting `stopKind` beside it got
  `undefined` on exactly those two paths, and the desktop's wire-first
  path collapsed the precise kind (`no_run_end`) into the generic
  `error` — a diagnosis the user cannot act on, on exactly the paths
  where the diagnosis matters most. There is now ONE assembler,
  `AetherCodeMethods.runEndFrame(String, String)`: it ALWAYS carries
  `stopKind`, and carries `isError` + `error` exactly when
  `StreamEvent.isErrorOutcome()` says "failure" — both straight from
  the engine's taxonomy, never a hand-rolled `startsWith`. All three
  emitters go through it, and the stream-json `result` frame carries
  `aethercode_stop_kind` too (the same contract on the other wire).
  The desktop's order became: the wire's kind → the local prefix
  match → the generic `"error"` LAST, so a R750d–R382 daemon (sends
  `isError`, no `stopKind`) no longer loses `no_run_end` /
  `consumer_timeout`.
  **Lesson 1232 — a contract written down in one place and honoured in
  three is not a contract.**
  **Lesson 1233 — a CONTRACT pin must FOLLOW the implementation. A pin
  that names a method goes red on a RENAME (which is not a defect), and
  a pin that names a LOCATION goes red on a REFACTOR (which may make
  the code better). R383's gate went red on R384's improvement for
  exactly this reason. Pin the INVARIANT, not the spelling.**
  **Lesson 1234 — a TRUNCATED extraction is the most expensive kind of
  green: the body is half a method, so every `assert.match` over it
  passes VACUOUSLY. `javaCode` blanks comments but copies string
  literals verbatim, so a `}` inside a literal closed the method body
  early — 978 such literals in `AetherCodeMethods.java`, 5 of 501
  extracted methods truncated. Brace counting must skip literals.**
  **Lesson 1235 — a scanner with no self-test has its bugs found by
  whichever gate happens to trip over them, which is not a plan.**
  **Lesson 1236 — fail loudly rather than lie: `javaMethodContaining`
  reports "no enclosing method" instead of quietly returning a block
  that is not a method.**
  **Lesson 1237 — an INVERTED invariant is often stronger: "this code
  must NOT hand-build a run_end frame" tolerates a rename AND catches
  the real defect, where "this code must call X" only pins the
  spelling.**
  **Lesson 1238 — a verdict WITHOUT a kind must not discard the
  precision the local list already has; the generic value goes LAST.**
  Doc: `docs/R384-one-run-end-frame.md`.
- **R385** (the mode surface must speak the daemon's vocabulary):
  `__PICK_MODE__` — the sentinel bare `/mode` returns — was claimed
  ONLY by the cmd.exe line mode; on Ink it fell through to the R362
  fallback and answered "no panel for that command in the TUI yet" for
  a feature that worked one keystroke away in the other renderer. A
  **FALSE FAILURE** is worse than an absent feature: the user assumes
  the capability does not exist, and then works around it by typing
  `/mode DEFAULT` — which happens to work, so the feature becomes
  "sometimes there". The permission-mode VOCABULARY was written in
  **six files / thirty-nine string literals** and the copies had
  already disagreed: the list was missing `ASK_BEFORE_TOOL` (a mode
  the daemon has, whose own javadoc says it was named for the TUI);
  the Ink header showed "TASK" where the cmd.exe header showed "ASK"
  for the same mode; the Ink fallback claimed `ACCEPT_TASK` while the
  daemon's default is `DEFAULT` (`AppState.permissionMode`); and the
  Ink header carried four names the daemon cannot produce (`AUTO`,
  `ASK`, `DENY`, `BYPASS` — the old `Action` enum). Now ONE
  vocabulary (`src/permissionModes.ts`) holds the names plus their
  label / badge / blurb / tone, checked against the daemon's
  `PermissionMode` enum; `/mode <name>` normalises its argument
  (the daemon's `valueOf` is case-sensitive and dash-blind, so
  `/mode default` and the daemon's own documented
  `/mode ask-before-tool` both used to fail); and a new gate requires
  EVERY sentinel the command layer emits to have an owner on BOTH
  renderers.
  **Lesson 1239 — an allowlist entry may be describing a category that
  does not exist.** `PICK_MODE` sat in r362's `INLINE_FAMILIES`
  ("renders inline, so it needs no owner") while it actually opens a
  PANEL; the excuse is what created the orphan.
  **Lesson 1240 — the payoff of collapsing a vocabulary is not fewer
  lines, it is that two renderers start saying the same thing.**
  **Lesson 1241 — the daemon's enum is the authority; a client's copy
  is checked against the SOURCE.**
  **Lesson 1242 — what a constant is CALLED in the other renderer is
  also a contract.**
  **Lesson 1243 — a fallback value is an assertion, and it is often a
  lie.**
  **Lesson 1244 — scan the COMMENT-STRIPPED text: `ts-scan` bails at
  an unterminated quote, and one apostrophe in a `line.ts` comment
  hides thousands of lines from the scan.**
  **Lesson 1245 — "imported the module" is not "imported the name".**
  **Lesson 1246 — the SHAPE in which a sentinel is claimed must be a
  shape the contract can see.**
  **Lesson 1247 — a false failure is worse than a missing feature.**
  Doc: `docs/R385-one-mode-vocabulary.md`.
- **R386** (the theme the user picks IS the theme they see): `/theme NAME`
  printed "theme → NAME" and **nothing on the screen moved**. The TUI had
  **three theme systems that never met**: a STATIC token table `t` that
  **29 components** import and render with (and which never changed), five
  palettes in `src/themes.ts`, and a React context whose `useTheme()` had
  **ZERO call sites** — so the computed palette was discarded on the
  floor. The theme VOCABULARY was written in **four files** and the
  copies had already disagreed: `themes.ts` shipped FIVE palettes,
  `commands.ts` accepted only THREE (`/theme kanagawa` and
  `/theme opencode` were rejected with "unknown theme" — two shipped
  palettes NOBODY COULD SELECT), `state.ts` typed `themeName` as a
  three-value union, and `tui.tsx` cast to the same three. A **second**
  vocabulary existed too: the `aethercode-themes` package
  (`light` / `dark` / `solarized-light` / …) drove the picker, its
  `ThemeStore.setActive()` wrote to `<UserHome>/.aethercode/theme.json`
  while the screen kept rendering the TUI's own colours, and the picker's
  selection never touched `state.themeName` — the field the renderer
  reads. Now `t`'s 22 palette roles are GETTERS over the active palette
  (the other 20 tokens and the 38 icons stay put — a workbench theme is
  not a syntax-colour theme), ONE registry (`src/themes.ts`) owns the
  names, `/theme` resolves through it (case-insensitively) and derives its
  error message from it, bare `/theme` opens the picker, and the picker
  writes `themeName` on select AND on preview (with Esc restoring what
  was there before). The `aethercode-themes` bridge files are deleted.
  **Lesson 1259 — a command that says it did something must have done
  it.**
  **Lesson 1260 — a computed value nobody reads is not a feature: every
  downstream of a computation must be nameable.**
  **Lesson 1261 — "one frame late" and "never changes" are the same user
  story: publish BEFORE any consumer reads, and remember that JSX
  attributes are evaluated in the PARENT's render body.**
  **Lesson 1262 — a list that is one short tells the user the feature
  does not exist; derive the list and the error message from one place.**
  **Lesson 1263 — two vocabularies are worse than one half-right
  vocabulary: a selection must be written to the field the renderer
  READS.**
  **Lesson 1264 — when mutating the product does not turn the gate red,
  suspect the GATE: the hole was in the shared `claimedBy()` helper
  (its `===` branch accepted a sentinel PREFIX).**
  **Lesson 1265 — one definition written three times is worse than one
  written once; find the second copy and fold it into a shared module
  before writing the third.**
  Doc: `docs/R386-the-theme-you-pick-is-the-theme-you-see.md`.
- **R387** (`/clear` must really clear, and both renderers must say the
  same thing): `/clear` was the only command whose CATALOGUE entry
  ("clear the scrollback"), whose LINE renderer (a real `\x1b[2J` wipe)
  and whose INK renderer (`"(clear is a no-op in this build; restart for
  a fresh scrollback)"`) said three different things. The Ink half was
  HONEST — R378 deferred it as "another honest gap" — and it was still
  broken, because the capability was never missing: the daemon already
  had `createSession` registered on BOTH transports. And clearing the
  VIEW alone would have been a FALSE CLEAR: `state.turns` is a MIRROR of
  the daemon's transcript (R372 replays `getTranscript` into it), so the
  model would still remember the conversation, the user could not see it,
  and the next session switch would bring the whole history back. Now ONE
  module (`src/clearConversation.ts`) owns the method, the params, the
  session-id extraction and the outcome sentence; BOTH renderers call
  `runClear`, the clear is ATOMIC (the daemon confirms a new session
  BEFORE the scrollback is replaced — otherwise a failure leaves an empty
  screen with a model that still remembers everything), and a refused or
  malformed answer is a FAILURE that never invents a session id. The
  daemon was NOT changed: `createSession` is non-destructive, so the old
  conversation is still recoverable from `/sessions` — a new
  `clearTranscript` RPC would have been MORE direct and strictly WORSE.
  `adoptSession` now also drops the PREVIOUS conversation's error card.
  **Lesson 1266 — an honest "I did not do it" is not the same as "it may
  not be done": when the capability already exists, a refusal only
  repackages the defect.**
  **Lesson 1267 — the view is not the session: clearing the mirror brings
  the history back.**
  **Lesson 1268 — a "clear" must be atomic: confirm first, replace
  second.**
  **Lesson 1269 — changing the code is not changing the behaviour: one
  mutation needle hit the wrong line and the gate stayed green by
  luck.**
  **Lesson 1270 — a behaviour description in a document is an assertion
  too: the TUI README claimed "Ink: redraw" for a path that never
  redrew.**
  **Lesson 1271 — naming a daemon method is a contract; naming a LOCAL
  function is not: pin the MODULE, not the symbol (a pin on a name goes
  red on a rename).**
  **Lesson 1272 — do not touch the daemon when an existing RPC already has
  the right semantics: `createSession` is non-destructive, a new
  `clearTranscript` would have destroyed the conversation.**
  Doc: `docs/R387-clear-starts-a-fresh-conversation.md`.
- **R388** (one name, one handler; one list, one description): building
  0.2.74, esbuild warned on **every** build — *"This case clause will
  never be evaluated because it duplicates an earlier case clause"* —
  `/budget` had **TWO `case` arms in the same switch**. The second was
  DEAD CODE, so the daemon's `setPhaseBudget` was **UNREACHABLE** from
  the TUI, and the CATALOGUE described **that dead arm** ("set a phase's
  tool-call / cost cap") while the LIVE arm set the local cost bar. The
  dead arm carried a SECOND lie: an omitted third argument was
  documented as "the USD cap stays", but the daemon's convention is
  **0 = unlimited** and `setBudget` OVERWRITES — so omitting it silently
  removed a cap. And the command PALETTE carried its own hand-written
  description table: **65 of the catalogue's 88** commands rendered an
  EMPTY hint (the palette is the DISCOVERY surface), and the 23 that had
  one could disagree with the catalogue. R380 had made the catalogue the
  only list for `/help` and the tab completion and had **left the palette
  behind** — R378's Lesson 1197. Now the phase cap has its own name
  (`/phase-budget`, with the daemon's three required fields, the cost cap
  now REQUIRED rather than defaulting to "unlimited"), the palette's
  descriptions are DERIVED from the catalogue, and the hand-written table
  is deleted.
  **Lesson 1273 — one name, one handler: a duplicate `case` label is not
  an error, it is dead code.**
  **Lesson 1274 — the catalogue can describe a branch nobody can reach.**
  **Lesson 1275 — if "leave it unchanged" has no encoding, do not write
  it: a required argument is honest, a default that means "unlimited" is
  a trap.**
  **Lesson 1276 — after you fix one consumer, ask how many consumers
  there are.**
  **Lesson 1277 — an empty description is worse than no row at all.**
  Doc: `docs/R388-one-name-one-handler.md`.

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

- **R374** (the `/update` panel, three honest verbs, no dead button): the
  blocker vocabulary was wrong for three rounds — `not-wired` means "the
  backend works, go write the UI", `backend-missing` means "there is no
  backend, do NOT write the UI". The daemon had NO update RPC at all, so
  the honest deliverable was a panel that says so, with no install button
  and no `UpdateAvailable` / `UpdateConfirm` / `UpdateProgress` mounted.
  The gap statement is derived from a real `-32601` probe, so the panel
  self-upgrades when the daemon gains the method. See
  "Watch out for (R374 additions)" and `docs/R374-update-panel.md`.

- **R375** (the run-control surface: three verbs that actually work): the
  status bar's Continue/Pause/Stop buttons and the `/pause` `/stop`
  `/continue` commands all called `task/resume|task/pause|task/kill` —
  methods that exist only on `TaskMethods`, a proxy to a SUPERVISOR
  process production code never constructs (`task/pause` is not even in
  its list). The rejection was swallowed, so the buttons did nothing.
  They now call `cancel {runId}` and `setContinuationStopped {stopped}`
  — the two methods the daemon really has. R375 also found a DEAD UI
  nobody was looking for: `validActions("running")` has no `continue` and
  the status mapping could never return `"paused"`, so Continue was
  permanently greyed out. See "Watch out for (R375 additions)" and
  `docs/R375-run-control.md`.

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

## Watch out for (R375 additions)

- **`task/*` 在这个 daemon 上不存在,而它长得像存在。**
  `TaskMethods`(`aethercode-protocol/.../methods/TaskMethods.java`)真的
  声明了 `task/resume` / `task/kill` 等 12 个方法,但它是**代理到独立
  supervisor 进程**(`SupervisorClient`,自带 TCP loopback socket)的适配器,
  `DaemonRunner` 从不构造它;`task/pause` 连它的列表都没进(只有
  `TaskPauseParams` 这个 DTO)。R375 之前 TUI 的三个按钮 + 三条斜杠命令
  打的就是这些名字,而且**没有 `.catch`**,所以 `-32601` 被吞掉,按钮
  **什么都不做**。**任何"这个方法名在某个类里"的证据都不等于可达** ——
  要看 `DaemonRunner` 有没有接线它。
- **`setContinuationStopped` 的三个默认行为是 TUI 依赖的契约:**
  (1) `sessionId` 空白 → 回落到 `engine.appState().sessionId()`(当前会话);
  (2) **`stopped` 缺失 → 默认 `true`** —— 所以 "continue" 必须**显式**发
  `stopped:false`,什么都不发等于**暂停**;
  (3) `stopped=true` 会**同时取消待触发的倒计时**,所以暂停立刻生效。
  TUI **故意不传 sessionId**:行渲染器的占位符是 `"—"`,daemon 的空白检查
  抓不到它,会老老实实给一个叫 `"—"` 的会话设标志。
- **daemon 在用户下一次提交提示时清掉自己的标志**
  (`AetherCodeMethods.query` → `setContinuationStopped(_targetSid, false)`),
  所以 TUI 的本地镜像**必须在同一个时刻**清掉,否则状态栏会撒谎。
- **死 UI 的成因可能在下游,不在上游。**
  `SessionControl.validActions("running")` = `{pause, stop}`(没有
  `continue`),而 `mapSessionState()` 之前**永远**返回 `"running"` ——
  Continue 按钮是**永久灰的**,而且因为 `/pause` 也打错了方法名,用户根本
  到不了"已暂停"状态。**一个不可达的状态让另一个修复完全无效。**
  门禁要**端到端**跑 `validActions(runControlState(status, paused))`,
  而不是分别断言两个部件。
- **`BACKEND_MISSING` 是"方法清单",不是"按钮健康度清单"。**
  把一个坏按钮登记成"缺方法"等于把缺陷归档成知识。R375 换成一条**结构性
  不变量**:任何能发 RPC 的源文件都**不得再命名**一个 daemon 服务不了的
  方法(`r369` + `r375` 门禁都扫)。注意 `TaskPanel.tsx` /
  `src/rpc/queries.ts` 仍在注释和死代码里提 `task/*` —— 它们没被挂载、
  没被引用、也不在扫描范围内,是已知的后续项。
- **一条"断言这个命令已知是坏的"的断言,锁住的是坏的现状。**
  `phase-6-t6-17` 曾经断言 `/continue` 会拒绝 `task/resume`,理由是"这个
  方法不存在"。**后端其实有能力,只是名字错了/概念错了** —— 正确动作是
  **去把命令接对**,不是把"接不对"写进断言。问的问题是"这个能力到底存在
  吗",不是"这个方法名存在吗"。
- **改名和改断言是同一个动作。** R375 把 `cancelActiveRun` 改名成
  `stopActiveRun`,`r363-cancel` 的 6 条断言当场变红 —— 它们钉的是**名字**
  而不是**契约**。要么改断言去钉契约(本轮钉共享常量 `RUN_STOP_METHOD`,
  方法名本身由 `r375` 门禁钉住),要么别改名。
- **变异用例插注释,门禁看不见,而且**应该**看不见。**
  "DaemonRunner 没有接线 TaskMethods" 读的是**去注释后**的源码;插一行
  `// TaskMethods ...` 会让文本变了而门禁纹丝不动 —— 注释不是接线。
  变异必须插**真代码**。差点被当成门禁有洞,其实是门禁对。
- **失败文案不能顺着失败路径长。** "没有可停的 run"复用失败分支就会变成
  "stop failed: no run is in flight" —— 用户读到"出错了",而其实什么都没
  发生。两个渲染器各写文案时这个偏差立刻出现,共享文案模块
  (`runNothingInFlight()`) 是唯一可靠的解法。
- **一个动作只能有一个实现。** R375 之前斜杠命令、状态栏按钮、Esc 键是
  **两套**实现(而且按钮那套指向不存在的方法)。现在 `src/runControl.ts`
  是唯一的词汇表(动词 → 方法 → 参数 → 文案),两个渲染器各自只有**一份**
  `stopActiveRun` / `pauseAutoContinue`,命令表**只负责说方法名**。
  **推论:同一个问题的第二个答案就是下一个漂移的种子** —— 状态映射曾经
  在 `StatusBar.tsx` 有一份私有的副本,现已并回 `runControl.ts`。
- **`/stop <runId>` 的 run id 由宿主补,不由命令表补。** 命令表没有 state,
  所以 `routeSlash("/stop")` 只说方法名;`tui.tsx` / `line.ts` 收到
  `rpcMethod === "cancel"` 时**提前 return**,自己把 active run id 填上,
  再走同一个 `stopActiveRun`。
- **任何"在原地改源码"的工具,都必须能在被杀之后自愈。** 变异电池
  (`scripts/test/rNNN-mutate.mjs`)把产品源码改在原地;它一旦被杀,变异就
  留在树上,下一次 `npm test` 读到一个**故意被改坏的产品** —— 那些失败
  **属于谁都负责**。R375 踩过:`node scripts/test/r375-mutate.mjs 2>&1 |
  Select-Object -First 2` 会**停掉上游进程**,电池死在第一个用例中间,
  `src/runControl.ts` 留下了 `RUN_STOP_METHOD = "task/kill"`,全量测试多出
  3 条**假失败**。
  `git checkout --` 不能兜底(回到 HEAD,吞掉未提交的修复,Lesson 1171)。
  **修法是结构性的**(`scripts/test/mutation-snapshot.mjs`,12 个电池全接上):
  改动前把原始字节写进 snapshot 文件;电池**启动时**先从 snapshot 恢复
  **再读基线**(顺序反了会把变异固化成基线);跑完清掉。用 snapshot 文件
  而不是 `process.on("exit")`,是因为**被直接杀的进程一个 handler 都不跑**。
  **推论:护栏本身要被真实验证** —— 真的启动一个电池、杀掉它、确认树脏了、
  重跑、确认它自愈;以及"树已经脏了 → 跑电池 → 确认它先自愈而不是把脏内容
  当基线"。两件事都做过,才算这个护栏存在。
  **推论:别用 `| Select-Object -First N` 截断电池的输出** —— 那会杀掉它。

## Watch out for (R376 additions)

- **提交前必须 format —— 现在它是门禁,不是习惯。**
  TUI 的格式契约:`aethercode-tui/.prettierrc.json`(printWidth 100、双引号、
  `semi`、`trailingComma: all`、`arrowParens: always`、`endOfLine: lf`、2 空格)。
  命令 `pnpm format:tui`(根)或 `npm run format`(`aethercode-tui/` 内)。
  强制点在 `scripts/test/r376-format.test.mjs`(`npm test` 的一部分):
  逐文件 `prettier.check`,失败时**列出文件名**而不是只报一个计数。
  **推论:一条要靠人记得的规则,等于没有规则。** 判据是"忘了会不会被抓住",
  不是"记得的人有多认真"。
- **prettier 的 API 不会自己读配置文件。** `prettier.check(src, { filepath })`
  用的是 **prettier 的默认值**(80 列、无尾逗号),不是 `.prettierrc.json` ——
  那是 CLI 做的事。门禁第一次跑就因此把 215 个文件全判成不干净。
  正确写法:`prettier.resolveConfig(file)` 再把结果并进 options。
  **推论:门禁用库的时候,配置解析是调用方的责任,不是库的责任。**
- **一次 `prettier --write` 就能让门禁静默失明。**
  格式化 213 个文件后:13 条源码钉失配(全量 45 → 58 失败),
  **45 个变异针脚变成 no-op**(12 个电池全中)。
  no-op 是最危险的一种 —— 电池报告的是 "MUTATION SURVIVED"(用例通过了),
  而它其实**什么都没测**。**"没匹配到"和"通过了"在输出里长得一模一样。**
  **推论:no-op 探测器是必需的,不是可选的。** 脚本要显式区分
  SKIPPED / NO-OP / GREEN 三种状态。
- **源码钉和变异针脚必须钉语义,不能钉排版。**
  判据:**这条钉能不能在一次 `prettier --write` 之后还成立。**
  做法:空白可选(`\s*` / `[\s\S]*?`)、引号是字符类(`["']`)、
  数字写法不挑(`0\.8(?:0)?`)。R376 改完后 12 个电池在**格式化之后**依然
  12/12 全绿 —— 这才是"针脚与排版无关"的证据,而不是"这次碰巧还能用"。
- **`??` 在正则里是"惰性可选",不是"两个问号"。**
  `/x \?\? "y"/` 匹配的是**空串**。R374 的 `daemonVersion` 针脚因此从未命中,
  电池报 ok 而变异从未发生。要写 `\?\?`。
- **改写门禁断言时,只替换条件表达式,不要替换整条 `assert.ok(...)`。**
  整条替换只匹配到条件那一段时,剩下的 `"<消息>",\n);` 会留在原地 →
  **语法错误**。把 `... .test(runBlock)` 换成 `true`,断言结构不动。
- **一条针脚必须能说出"我指的是哪一个"。**
  `panelIntents.ts` 里每行注册表长得都差不多;`ink: 'mounted',\n line:
  'rendered',` 这种针脚会**跨行命中** —— `[\s\S]*?` 虽然惰性,照样会跳过
  本行结尾去后面找下一个匹配。锚到行自己的身份上:
  `/(component: "components\/UpdatePanel\.tsx",[\s\S]*?ink: )"mounted",/`。
- **格式化脚本和门禁的范围必须由同一定义给出。**
  门禁从 `package.json` 读 `format` / `format:check`,断言两边覆盖同一组
  目录。范围被改动而门禁没跟上时门禁会红 —— **一个枚举器**。
- **根级 `format` 脚本目前仍是一次全仓扫描,不是提交前的动作。**
  其余四个包(desktop / memory / compact / themes)还没 prettier-clean,
  跑一次就是 400+ 文件的无关 diff。等它们清理干净,根级脚本再收窄。
  文档:`docs/R376-tui-format.md`。

## Watch out for (R377 additions)

- **一次拒绝必须引用 daemon 自己的标识符,而且门禁要能核对它。**
  `src/rpcCapabilities.ts` 的每条理由都引用 daemon 的类/方法/注册名;
  `scripts/test/r377-capabilities.test.mjs` 把每个被引用的标识符拿到
  daemon 的 Java **代码**里查(用 `javaCode` 剥掉注释 —— 靠一句 Javadoc
  撑着的断言是关于散文的断言,不是关于行为的断言)。
  **推论:不要相信"我查过了,注册表里没有"。注册表里没有你要找的那个
  `词`,它有的是**同义词**(`effort` → `switchVariant`)。**
  **Lesson 1193 —— 一次拒绝是对另一个系统的词汇表的断言,而这种断言无法自证。**
- **拒绝表要守住每一条会调它的路径,不只是斜杠分发器。**
  Ctrl-Z 直接调 `rewind`,于是斜杠命令用**人话**回答、同一个按键甩一个裸的
  `-32601` 出去。门禁钉的是**顺序**:拒绝检查必须在请求之前。
- **报 daemon 解析出来的结果,不要报你请求的值。**
  `Variant.byName` 对不认识的变体名**静默回退**到 bundled default,
  所以 toast/日志必须用 `activeVariant`。
- **一个动作,一份实现。** `variantLine()` 只在 `src/effort.ts`;
  门禁钉住两个渲染器都不得有自己的一份。
- **变异电池有前置条件:门禁在变异之前必须是绿的。**
  新增 `scripts/test/mutation-baseline.mjs`,13 个电池全接上。
  **一个已经红的门禁在任何变异下都还是红的** —— 所以每个用例都报"抓到了",
  而**测的东西是零**。R377 的 15 个用例就是这样"全绿"的。
  **Lesson 1194 —— 先证明门禁是绿的,再问它能不能抓住。**
- **一个变异改在错误的出现位置,是一个伪装成通过的 no-op。**
  `String.replace` 改的是**第一个**出现。R377 踩了三次:`switchProvider`
  也读 `variant`(参数检查扫整份文件 → 收进 handler 方法体,用大括号配对
  提取);`effort.ts` 的**文档注释**里就有 `"xhigh"`(字符串存在性检查 →
  改比**编译后**的 `EFFORT_LEVELS` 与 daemon 的 `Variant.BUILTIN`,后者
  从 Java **推导**出来)。
  **推论:"某个字符串出现在这个文件里"不是对一个值的检查。**
  **Lesson 1195 —— 文件确实变了、报告确实说"抓到了",而被测的那一处
  根本没被碰过。**
- **按 KEY 定位表项,不按文案定位。** `scripts/test/caps-table.mjs`
  (`findEntry`/`readEntries`/`deleteEntry`/`setReason`/`replaceInReason`/
  `insertEntry`) 是 r366/r369/r375 三个电池共用的编辑器,**自己也有自测**
  (`selfTest()`,由 R377 门禁执行)。R377 重写理由文案时,这 3 个电池的
  4 根文案针脚当场变成 no-op —— **文案会被改写,键不会。**
- **提取要收进目标块,不要用猜的字符窗口。**
  `body.indexOf("\n  }")` 这种猜法在本项目**匹配不到**:`AetherCodeMethods`
  的方法收尾大括号缩进**四个**空格。用 `javaMethod(code, signature)`
  做**大括号配对**。
- **别在电池运行时看工作树。** 变异电池**原地改产品源码**,所以在它运行
  期间 `git diff` / `git status` 读到的是**变异中间态**。R377 差点把
  r364 电池的一个变异(r364 那个 `cwd_changed` 处理器的删除)当成真实回归。
  **判据:电池和测试套件不能并发,电池和"看代码"也不能并发。**
  文档:`docs/R377-capability-naming.md`。

## Watch out for (R378 additions)

- **一份真相只能有一份拷贝。** 第二份拷贝**无法被验证**,而且会漂移 ——
  而且漂移得毫无声响:三份都是合法的 TypeScript,碰过它们的门禁全是绿的。
  R378 之前 TUI 有**三份**手写的按键表(帮助浮层 / 欢迎页 / `--help`),
  它们互相矛盾,而且和 handler 矛盾。
  **推论:修法不是"改的时候小心点",是根本没有第二份可以改。**
  新增 `src/keybindings.ts` 是唯一的注册表,三个消费者全部渲染它。
  **Lesson 1197 —— 一份真相的第二份拷贝会漂移,而且漂移得毫无声响。**
- **注册表和 handler 要双向绑定。** `scripts/test/r378-keybindings.test.mjs`
  从 `tui.tsx` 的 app 级 handler 里**重新推导**和弦集合(用大括号配对提取,
  不是猜的字符窗口),然后断言:handler 绑的每一条都在注册表里,注册表写的
  每一条都真的被绑。**判据:注册表和 handler 谁都不是真相,两者的交集才是。**
- **帮助不承诺这个 daemon 做不到的事。** 注册表条目带 `method`(它调用的 RPC),
  "能不能用"**从 R377 的拒绝表推导**(`bindingUnavailable()`),不手写。
  所以 `Ctrl+Z` 那一行会写 "rewind is not available on this daemon"。
- **import 了不等于渲染了。** 门禁除了查"消费者 import 了注册表",还查
  "每个分区都真的被渲染"(`bindingsIn("display")` 被改成 `[]` 必须变红)。
- **位置锚点要挑一个"活着、且有理由活下去"的东西。**
  R363 的门禁拿 `key.ctrl && input === "l"` 那个**死分支**当"下面还有东西"
  的标记;R378 删掉那段死代码(它遮住了 log viewer),门禁当场失明 ——
  而且**没人知道它一直靠在那儿**。
  **Lesson 1198 —— 一段死代码被当成位置锚点,就是一颗定时炸弹。**
  判据:**这个锚点被删掉的时候,会有人觉得可惜吗?** 不会,就换一个。
- **正则没锚定时,"改短"不等于"改坏"。** R378 的电池用
  `/key\.ctr/g` 去废掉 `/key\.ctrl/g` —— 但 `"key.ctr"` 是 `"key.ctrl"` 的
  **前缀**,正则照样匹配,**变异根本没发生**,用例"通过"了。
  **判据:变异之后,被测的行为真的变了吗?没变,就不是变异。**
- **扫描器必须跳注释。** `scripts/test/ts-scan.mjs` 的括号匹配同时跳注释和
  字符串 —— 否则 `InputBox.tsx` 注释里的一个撇号("the user's …")会被当成
  字符串开头,吞掉后面 2 KB 真实代码。**这正是 R366 记录过的 Java 陷阱在 TS 里
  的翻版。**
- **只有单一推导的性质,不写"阉废门禁"用例。** 阉废唯一的推导只能证明
  "它就是唯一的推导",那是废话,不是证据。R378 删掉了 dev-note 那根针脚,
  并写下了原因。
  文档:`docs/R378-help-truth.md`。

## Watch out for (R379 additions)

- **同一份真相在同一文件里的两份拷贝,同样藏得住 bug。**
  `EngineContinuationDispatcher` 里方法名出现两次(常量声明 + 调用点写
  字面量),于是"删掉发送"不等于"删掉名字" —— 门禁查字面量,字面量还在,
  **一个已经不再倒计时的 daemon 依然是绿的**。
  现在字面量**只留一份**(调用点用常量),门禁断言"这个字面量在整个文件里
  **恰好出现 1 次**"且"常量真的被 `notifyCustom` 发出去"。
  **Lesson 1199 —— 一份真相的第二份拷贝会漂移,而且漂移得毫无声响。**
- **没人读的标记是假成功。** `agent/setDefault` 必须把 agent 的正文装进
  **活着的** system prompt(`QueryEngine.defaultAgentBlock`),否则它只是一个
  写了名字的文件。门禁钉**调用**(`engine.setDefaultAgent(`),不是标识符。
- **一句承诺必须由一个 RPC 兑现。** 倒计时那行写着 "esc to cancel",所以
  Esc 真的调 `pauseAutoContinue(true)`(和 `/pause` 同一条
  `setContinuationStopped` 路径)。**装饰性的承诺比不承诺更糟。**
- **前缀匹配不是匹配。** `code.includes("COUNTDOWN_METHOD")` 也匹配
  `COUNTDOWN_METHOD_DISABLED` —— `_` 是词字符,两者之间**没有词边界**。
  一条变异把 case arm 改名,门禁纹丝不动。
  **Lesson 1202 —— 词边界不是可选的。**
- **改到注释里的变异是伪装成失败的 no-op。** "倒计时不再承诺 esc"那条用例
  的针脚 `s.replace("· esc to cancel", "· continuing")` 命中的是 **JSDoc 里
  引用 Claude Code 的那句话**,不是模板字面量 —— 文件变了,行为没变。
  `scripts/test/code-needle.mjs` 的 `mutateInCode()` **要求针脚打在代码上**:
  注释剥离器把注释涂成空格但**保持长度**,所以偏移一一对应;找不到时分别报
  "只在注释里出现过"(打偏)和"压根没出现"(形状变了)。
  **Lesson 1203 —— 文件变了不等于行为变了。**
- **靠自觉的清理步骤迟早会被漏掉;让漏掉不可能发生。**
  `scripts/test/mutation-snapshot.mjs` 的快照文件会让下一次运行**恢复**工作树
  —— 而"上次被杀"和"上次跑完但忘了删快照"**长得一模一样**。R379 的电池是 14 个
  里唯一没调 `snapshot.clear()` 的,于是它的快照把这一轮的门禁收紧和 daemon
  的 Java 改动一起revert掉了(日志里还写着 "recovered 12 file(s)")。
  现在快照在**退出时按内容自证清白**:每个文件都等于记录的原内容 → 树干净 →
  **删掉自己**;任何文件还不一样 → 电池真死在半路 → 留下,并记下**变异后的
  内容**,于是下一次恢复**只还原仍然是那个变异的文件**,被人改过的不碰。
  **Lesson 1204 —— 一个清理步骤如果只有作者记得做,它迟早会被漏。**
- **数量相同不等于名字相同。** 失败数 43 → 43 不代表"没变化":可能是换了一个
  失败顶替了另一个。**逐名 diff**(`Compare-Object` 两份 `✖` 列表)。
  **Lesson 1205 —— 逐名 diff,不要只比计数。**
- **一条契约在空表上会变空洞。** R379 把拒绝表清空之后,r378 那条"帮助要标出
  不可用的键"**变成了空气**(两边都标不出东西)。修法是**重定向到推导本身**,
  不是删掉用例:`bindingUnavailable` 恒为 true → 每个有 method 的键都被标成
  不可用 → 门禁的 "marked === refused" 比对变红。
  **Lesson 1206 —— 表空了,契约要从表上移到推导上。**
- **回归了要先问"是形状变了还是推导坏了"。** R379 改变了拒绝表的形状,于是
  r366 / r369 / r377 三个电池里写死表项的用例必须**重定向**(改成
  `insertEntry(...)`),而不是删掉 —— 删掉等于少测一条性质。
- **面板的键位表只有一份。** `src/componentKeys.ts` 是唯一注册表,面板渲染
  `hintFor(component)`,门禁从 `useInput` 块(大括号配对,不是全文件扫描)里
  **重新推导**键集合,和表**双向**比对:handler 绑的都要写,写的都要绑。
  本轮覆盖 `panelIntents.ts` 宣告的那 10 个面板;实测还有 9 个**可达**组件
  手写键位表(清单见 `docs/R379-gap-closers-and-countdown.md` 第 4.3 节),
  用 `node scripts/test/probe-component-keys.mjs` 可复现。
  文档:`docs/R379-gap-closers-and-countdown.md`。

## Watch out for (R380 additions)

- **"一个清单"可以有好几份,而且每一份都会漂。** R380 实测:命令面有**四份**
  名单(扁平 `string[]` / 结构化目录 / 手写分组表 / 路由器的 `case` 分支),
  **三份已经漂了**:`/demo` 只在目录里(所以补全不出来、面板里没有)、`budget`
  在扁平数组里**出现两次**、路由器能到的 10 个命令目录里没有。
  **Lesson 1207 —— 一份"清单"可以有好几份,而且每一份都会漂。**
- **注释里的"真相来源是 X"是一句需要被验证的断言。** R380 之前 `commands.ts`
  写着"Source of truth for autocomplete is SLASH_COMMANDS_DETAILED"——而
  `completeSlash()` 读的是 `SLASH_COMMANDS`;还写着"Mirrors the entries in
  `SLASH_COMMANDS`"——而 `demo` 缺失、`budget` 重复。**注释在撒谎,而且没人查。**
- **单向校验留下的正是会漂的那一半。** 分组表方向有门禁、反向没有 → 新增命令
  忘了加分组 → **静默**从 `/help` 消失。而且代码注释**自称**"the test makes that
  an explicit decision"——**那个测试不存在**。
  **Lesson 1208 —— 单向校验留下的正是会漂的那一半;注释里的"有测试保证"要验证。**
- **分组表曾经是 `/help` 的唯一分组来源,现在是条目上的 `group` 字段。**
  好处:命令不可能"在目录里却不在 `/help` 里"。代价:**分组必须在目录里连续**
  (否则 `/help` 会打印两个同名标题)——`r380-command-catalog.test.mjs` 钉了这条。
- **一个别名不得遮住它的主命令。** `/token` 是 `/tokens` 的别名;两个都在补全
  列表里,`/tok` 就失去一键补全。规则:**别名指向的主命令也匹配该前缀时,丢掉
  这个别名**(`/q` 的主命令 `exit` 不匹配 → 两个都留)。
  **Lesson 1209 —— 别名不得遮住主命令。**
- **针脚要钉字段,不是钉整条文本。** prettier 会重排条目(长的描述会被折行),
  钉整条文本的针脚就失配 —— 失配的表现是"用例没测到任何东西",不是"用例失败"。
  R380 里 3 个针脚是这样死的(`r374-mutate.mjs` 的 update/update-deps 那几条)。
  **Lesson 1210 —— 针脚钉字段,不钉整条文本。**
- **`aliases?` 不是 "alias + 可选 es"。** 正则的量词挂在**最后一个字符**上:
  `aliases?` = "aliase" + 可选 "s",所以**单数 "alias" 匹配不上**。
  同一个拼写坑了两次(一次在补丁脚本里剥不掉手写的别名提及,一次在门禁的正则里)。
  **Lesson 1211 —— 量词挂在最后一个字符上。**
- **`/help` 的重构必须用"重构前的文件编译一遍再逐字节 diff"来证明没变**,
  而不是"看起来没变"。R380 的做法:把旧 `commands.ts` 拷到临时目录编译,
  和新版本逐行 diff —— 结果 `IDENTICAL`。

## Watch out for (R382 additions)

- **子集构建的绿色会冒充全量构建的绿色。** `mvn install` 从 R747 起就没跑完过
  —— helper 日志里那些"BUILD SUCCESS"全是 `-pl` 子集,而**子集的日志尾巴和全量
  一模一样**。R382 修了 CLI 挂死之后,全量 reactor 第一次跑完:**576.55s**。
  **Lesson 1219 —— 一次没跑完的构建不算构建;"BUILD SUCCESS"必须指明是哪个构建。**
- **`--print` 的 stdin 读法要按输出形态分家。** plain `--print` 的 prompt 走
  argv(文档化的通道),stdin 只在**已经缓冲了字节**时读;**stream-json 必须读到
  EOF**(对端发完 prompt 会继续持有管道,后面还有 `control_response` 帧)。
  两者共用一条"读到 EOF"的路径 → surefire fork 里**永久阻塞**。
  **Lesson 1220 / 1219。**
- **默认构建里不许有"要密钥 + 要网络 + 要 20 分钟"的测试。**
  `BenchmarkReportRealLlmTest` 的守卫是"`MINIMAX_API_KEY` 没设就跳过"——
  **这恰好在最会付钱的机器上不触发**。现在是
  `-Daethercode.evals.realLlm=true` 显式开启。
  **Lesson 1220 —— "密钥存在"不等于"请运行"。**
- **同一个东西的两份浮动范围,一定会漂。** `Cargo.toml` 的 `tauri = "2"` 和
  `package.json` 的 `"@tauri-apps/api": "^2.0.0"` 各漂各的,没人比较 →
  `tauri build` 直接报 "Found version mismatched Tauri packages"。
  修法:**钉死一边(精确版本)+ 加一条比较门禁**。规则是 **major.minor**,
  不是完全相等(`@tauri-apps/api` 压根没有 2.11.5 —— 两边 patch 节奏不同)。
  **Lesson 1221。**
- **工具的契约要从工具的源码读。** `tauri build` 的报错只说"版本不匹配";
  `crates/tauri-bundler/src/bundle/windows/nsis/mod.rs` 说了**规则、缓存目录
  (`%LOCALAPPDATA%\tauri\NSIS`)、两个 SHA1、需要的文件清单**。走 GitHub
  contents API 就能取到(github.com 超时时 api.github.com 仍通,且资源端点会
  302 到 `objects.githubusercontent.com` 这个可达的 CDN)。
  **Lesson 1222 / 1223。**
- **注意工具的哈希算法。** NSIS 工具链用的是 **SHA1**(40 位十六进制),
  不是 SHA-256。我第一次拿 SHA-256 去比,当然对不上 —— **从二进制字符串里抓到的
  十六进制串,长度不对就说明你认错了算法。**
- **打 zip 别用 .NET Framework 的 `ZipFile.CreateFromDirectory`。** 它在 Windows
  上写**反斜杠**的条目名(违反 zip 规范,某些解压工具会解成一个巨型文件名);
  0.2.71 的 zip 用的是正斜杠。用 `C:\Program Files\7-Zip\7z.exe a -tzip`。
  **Lesson 1224 的邻居:产物本身也有规范。**
- **发布 README 里的数字是断言。** 0.2.72 的 README 写 `54.25 MiB` /
  `95.96 MiB`,实测是 `54.26` / `95.95`(手算的舍入错了);目录树和磁盘结构
  也差了两处。**每个数字都要从文件重新推导一遍** —— 目录树用"解析树 → 重建
  路径 → 和磁盘双向比对",大小用"同单位同小数位重新格式化后逐字比较"。
  **Lesson 1224。**
- **0.2.71 的发布包有两个洞,0.2.72 补上了:** `desktop/bundle/{msi,nsis}` 是
  **空目录**(而 README 声称里面有安装包),以及运行期目录
  `.aethercode/sessions/` 被**打进了包里**。`zipLayoutR268b` 那条 vitest 会在
  最新 release 目录上跑,发布目录没装完它是红的 —— **它红得有道理**。

## Watch out for (R383 additions)

- **渲染器不许自己判断一次运行有没有失败。** 判决来自 daemon
  (`run_end` 帧上的 `isError`),kind 来自 daemon(同一帧上的 `stopKind`),
  渲染器只负责**措辞**。要改 TUI 的失败判定,改 `src/stopKind.ts`,
  **不要在渲染器里再写一条规则**。
  **Lesson 1225。**
- **`src/stopKind.ts` 是分类法的唯一一份。** `state.ts` 和 `line.ts` 都从它
  import;`state.ts` 里的 `export { classifyRunEnd, bareKind }` 是 **re-export**,
  不是第二份定义。门禁 `r383-stop-kind.test.mjs` 会扫整个 `src/`,
  任何别的文件里出现 `function classifyRunEnd` / `const ENGINE_ERROR_KINDS`
  就变红。**Lesson 1226。**
- **兜底集合只在"daemon 没有表达判决"时生效**,判据是
  `sig.isError === undefined`,**不是**"没收到 `stopKind`"。
  曾经写成后者 —— 于是"有 kind 但没有判决"(中间层丢字段、版本错配)
  会把失败报成成功。daemon 只在失败时写 `isError`,所以**缺失 ≠ false**。
  **Lesson 1230 的邻居。**
- **提取 Java `switch` 的 case 标签要按整条 arm 取。**
  `case "no_run_end", "consumer_timeout" -> true;` 是**一条 arm 两个标签**;
  只取第一个会让"引擎集合"少一半,而**比较照常进行**。
  `r383-stop-kind.test.mjs` 的提取用 `/case\s+((?:"[^"]+"\s*,?\s*)+)->/g`。
  **Lesson 1229。**
- **循环检测器的 kind 不是 stop reason。**
  `same_fingerprint` / `same_error` / `long_output` / `user_interrupt` /
  `empty_tool_input` 是 `ProgressLoopDetector.lastLoopKind()` 的值,
  **待在装饰里**(`loop_detected: <tier> (…)`)。任何按这些字符串建的
  标签表 / 失败表,列的都是**永远不会发生的东西**。
  判据:**这个字符串是引擎在 `RunEnd` 里发的,还是检测器报的?**
  **Lesson 1231。**
- **存在性断言会被一个"键在、值被丢掉"的写法骗过。** R383 的变异
  "reducer stops passing the daemon's verdict"(把 `isError: action.isError`
  改成 `isError: undefined`)在门禁里**存活了一轮** —— 当时的断言只查
  `/isError\s*:/`。现在查的是**值**:`/isError\s*:\s*action\.isError/`。
  **Lesson 1230 / 1217。**
- **`StatusBar` 现在只有一张标签表**(`KIND_LABEL`,按显示种类查)。
  往里加"按 stopReason 查"的第二张表之前先问:
  **这个 kind 引擎真的发吗?**(见上面那条检测器 kind 的坑。)
  门禁会数 `const *LABEL*: Record<` 的个数,多一张就红。
  **Lesson 1228。**
- **出包时 `providers.yaml` 有两份,别拷错。**
  - `aethercode/aethercode-models/src/main/resources/providers.yaml`(5,133 B)
    —— **models 注册表**用的那份;
  - `aethercode/aethercode-core/src/main/resources/providers.yaml.sample`(9,844 B)
    —— 构建时被改名成 **jar 根目录的 `providers.yaml`**,是**运维真正要的那份**
    (daemon 首次启动会把它拷到 `<installDir>/providers.yaml`)。
  发布包里的 `providers.yaml` 和 `desktop/providers.yaml` 都是**第二份**。
  拷错会被 README 校验的**大小表**当场抓到 —— 这就是那张表存在的理由。
- **`7z a` 存的是"相对当前目录"的路径。** 在仓库根目录打
  `7z a release/aethercode-0.2.73.zip release/aethercode-0.2.73` 会多出一层
  `release/`,顶层目录就不对了(zip 校验 6 项红)。**先 `cd release/` 再打。**
  另:`.NET Framework` 的 `ZipFile.CreateFromDirectory` 在 Windows 上写**反斜杠**
  条目名(违反 zip 规范),必须用 `C:\Program Files\7-Zip\7z.exe a -tzip`。
- **"构建成功"不等于"补丁在包里"。** 0.2.73 出包时,把 jar 从 **zip 里取出来**
  再打开、grep 它的常量池(`stopKind` / `isError` / `bareKind`),
  确认 R383 的改动真的在用户拿到手的东西里。
  见 `D:\tmp\r383-scratch\verify-zip-artifacts-r383.mjs`(一次性脚本)。
  **Lesson 1224 的邻居:产物本身也有断言。**

## Watch out for (R384 additions)

- **`run_end` 帧只能有一处装配。** 全 daemon 只有
  `AetherCodeMethods.runEndFrame(String, String)` 可以写
  `put("type", "run_end")`;三个终态(引擎自己的 `RunEnd`、R745 合成终态、
  `catch (Throwable)`)全部走它。想加第四个终态?**调用它,别手搭帧** ——
  手搭的帧正是当初漏掉 `stopKind` 的原因。
  门禁 `r384-run-end-frame.test.mjs` 扫全部 main Java 源码,数这个 put 的总数。
  **Lesson 1232。**
- **契约钉子要跟着实现走,别钉名字、别钉位置。**
  `javaMethodContaining(code, marker)` 按**标记**定位包含它的方法;
  `javaMethodInfoContaining` 还能顺带把**方法名**推出来(名字从实现里推导,
  不写死)。R383 的门禁就是把契约钉在 `eventToMap` 的 `run_end` 分支上,
  R384 把装配挪进 builder 之后**那个门禁变红了** —— 红在一次让代码变好的
  重构上。要钉就钉**不变量**:"这里不能手搭帧"比"这里必须调用 X"更强 ——
  前者容忍改名,后者只钉住了写法。
  **Lesson 1233 / 1237。**
- **`javaMethod` 数括号时会跳过字符串字面量 —— 以前不会。**
  `javaCode` 清注释但**原样保留字符串字面量**(门禁要靠它找 `"sessionId"`),
  所以字面量里的 `}` 在剥离后的文本里**仍是一个右括号**。老实现天真地数,
  会**提前关掉方法体**:方法体少一半,上面每条 `assert.match` 都**空洞地通过**
  —— 门禁报绿,而它只读了一半方法。实测 `AetherCodeMethods.java` 有 978 处
  含括号的字面量,全仓库 501 个方法里 5 处提取被截断。
  现在用 `javaLiteralMask` 掩掉字面量区间。**Lesson 1234。**
- **共享的提取原语要配自己的自测。** `scripts/test/java-strip.test.mjs`
  钉三件事:清注释留字面量且**长度不变**、括号计数跳过字面量、
  `javaMethodContaining` 找的是**方法**而不是最内层 block
  (lambda、匿名类、静态初始化块都做不了它的对手)。
  没有自测的 scanner,它的 bug 会被碰巧绊倒它的门禁发现,那不叫计划。
  **Lesson 1235。**
- **宁可报错,不要撒谎。** `javaMethodContaining` 找不到方法时抛
  `no enclosing method`,而不是悄悄返回一个不是方法的块 ——
  名字叫 `javaMethod` 却返回 block,调用方会以为自己读到了方法。
  **Lesson 1236。**
- **桌面的 kind 优先级:线上的 → 前缀匹配 → 通用 `"error"`(最后)。**
  通用值放中间会吞掉精度:R750d–R382 的 daemon 发 `isError` 但不发 `stopKind`,
  那种帧本来能靠前缀匹配答对 `no_run_end` / `consumer_timeout`。
  **Lesson 1238。**

## Watch out for (R385 additions)

- **权限模式的词表只写在 `aethercode-tui/src/permissionModes.ts`。**
  `MODE` 对象是名字的唯一来源,`PERMISSION_MODES` 是带 label / badge /
  blurb / tone 的行。别的文件一律 `MODE.DEFAULT` 这样引用 ——
  **一个字面量都不许再写**。门禁 `r385-mode-surface.test.mjs` 扫
  `src/**` 里所有 `.ts`/`.tsx`,任何"整串内容恰好是模式名"的字符串字面量
  都会红(散文里提到模式名是允许的,比如 `--permission-mode=ACCEPT_TASK`)。
  改之前实测:**39 个字面量、6 个文件**(`line.ts` 16、`Header.tsx` 11、
  `tui.tsx` 7、`Welcome.tsx` 3、`commands.ts` 1、`state.ts` 1)。
  **Lesson 1240。**
- **词表必须对着 daemon 的枚举核,不是对着注释。**
  daemon 的 `org.aethercode.core.permission.PermissionMode` 有 **7** 个常量,
  旧 TS 目录只有 6 个 —— 缺 `ASK_BEFORE_TOOL`,而它自己的 javadoc 写着
  "more discoverable in the TUI's status bar"。门禁直接读那个 `.java` 文件,
  双向比较。**Lesson 1241。**
- **徽章跟词表走,别各写各的。** 改之前 Ink 的 `Header.tsx` 显示
  `DEFAULT`、cmd.exe 显示 `ASK`,而 Ink 那份 switch 还带着 daemon
  根本产生不了的 `AUTO` / `ASK` / `DENY` / `BYPASS`(旧的 `Action` 枚举)。
  两个渲染器都在"正常工作",用户看到的是两个世界。
  **Lesson 1242。**
- **兜底值是对用户安全网的承诺,得对着 daemon 的源码写。**
  daemon 的默认是 `PermissionMode.DEFAULT`(`AppState.permissionMode`),
  而 Ink 的 `getState` 兜底写的是 `?? "ACCEPT_TASK"` —— 于是头栏把
  "本任务内全部自动放行"报给一个其实在逐次询问的用户。
  **Lesson 1243。**
- **扫源码要扫去注释后的文本。** `ts-scan` 遇到没闭合的引号会停,
  `line.ts` 注释里的一个撇号("the daemon's estimate")就够让后面几千行
  从扫描结果里消失 —— **一个看不见的副本和一个不存在的副本,在门禁眼里
  没有区别**。R385 的门禁第一版就栽在这,被自己的变异电池当场抓出来。
  一律 `scan(codeOf(file))`。**Lesson 1244。**
- **"用了就得 import"要查名字,不是查模块。** 只检查"这个文件
  import 过 permissionModes.js"是不够的:把 `MODE` 从 import 列表里删掉、
  留下 `permissionModeBlurb`,模块级检查照样绿。**Lesson 1245。**
- **哨兵被认领的形状,必须是注册表看得见的形状。**
  cmd.exe 原来用 `if (local === "__PICK_MODE__")` 单独认领它,
  而注册表的推导找的是 `case "__X__":` —— **认领得对,但认领成了一种
  没人能核对的形状**,于是等于没被认领。现在它走
  `renderPanelSentinel` 的 switch。**Lesson 1246。**
- **豁免名单的每一项都要能回答"它为什么不需要归属"。**
  r362 的 `INLINE_FAMILIES` 里 `PICK_MODE` 的答案是"恰好没人认领",
  那不是答案。现在更强的契约在 `r385-mode-surface.test.mjs`:
  **每一个哨兵,在两个渲染器上都要有归属。**
  **Lesson 1239。**

## Watch out for (R386 additions)

- **主题词表只写在 `aethercode-tui/src/themes.ts`。**
  `THEME_NAMES` 是名字的唯一来源,`PALETTES` 是调色板,`THEME_LABELS` 是
  label + blurb。别的文件一律 `resolveThemeName()` / `ThemeName` 类型 ——
  **一个字面量都不许再写**。门禁 `r386-theme-reach.test.mjs` 分两半扫:
  四个特征名(`solarized` / `monokai` / `kanagawa` / `opencode`)全局扫,
  `"default"` 只在"import 了 themes.js 的文件"里扫(它同时是个普通英文单词,
  高亮器的兜底语言、提示词格式器的颜色名都在用)。
  改之前实测:**注册表之外 16 个字面量**(`state.ts` 10、`commands.ts` 3、
  `tui.tsx` 3),改之后 **0**。**Lesson 1262。**
- **主题名少一个,用户就以为功能不存在。**
  产品出厂 5 套调色板,命令层只认 3 套,于是 `kanagawa` / `opencode`
  **谁也选不了**,而错误信息还理直气壮地列了三个。
  `/theme` 的错误信息现在由 `THEME_NAMES.join(", ")` **派生** ——
  它不可能列出一个不存在的名字,也不可能漏掉一个存在的名字。
- **发布点必须早于"任何消费者读它"。**
  `t` 的 22 个调色板角色是 `getActivePalette()` 上的 getter,
  发布走 `useActiveTheme(state.themeName)`(`src/tui.tsx:896`),
  **只有这一处**发布。放在 `ThemeProvider` 里**不够**:
  App 自己建的元素(`color={t.warn}` 这种)在 **App 的渲染体**里就求值了,
  而 provider 渲染在 App 之后 —— 那些元素会**晚一帧**才换色。
  **"晚一帧"和"永远不变"是同一个用户故事。Lesson 1261。**
- **门禁要能证明"屏幕变了",不只是"token 变了"。**
  §1 证明 token 表跟着调色板走,§2 用 Ink **真渲染**一棵树,
  切换主题后从**输出的帧**里读 ANSI 转义码。§1 是绿的而 §2 是红的,
  在 R386 修法第一版里真的发生过(晚一帧)。
  渲染的是**真的** provider、**真的** token 表、**真的**发布路径 ——
  门禁验副本,验的就只是副本。
- **哨兵"被认领"的判定要收尾引号。**
  `scripts/test/claimed-by.mjs` 是唯一一份定义(r362 / r385 各有一份逐字节
  相同的内联副本,R386 收成一份)。它的 `===` 分支**必须**要求收尾引号 ——
  少了它,`=== "__X__X"` 会被当成认领了 `__X__`,而这是 R386 变异电池
  实测出来的洞。`startsWith("__X__:")` 是**带参数**的 sentinel,
  名字本来就是字面量的前缀,那一支故意不收尾。**Lesson 1264 / 1265。**
- **`aethercode-themes` 包还在,但 TUI 的 `src/` 里已经没人用它。**
  包本身留在 `package.json` 和磁盘上(`t457-e2e-smoke.test.mjs` 仍断言它
  存在并导出 `ThemeStore`)。**已知后续项**。

## Watch out for (R387 additions)

- **`/clear` 的语义只写在 `aethercode-tui/src/clearConversation.ts`。**
  `CLEAR_METHOD`(`createSession`)、`clearParams(cwd)`、`newSessionIdOf(result)`、
  `clearOutcome(ok, ctx)`、`runClear(request, ctx)` —— 方法名和**措辞**都只在这
  一个文件里。`tui.tsx` 和 `line.ts` 都**从它导入**再调用,不许各自写一份。
  门禁 `r387-clear-conversation.test.mjs` §4 查三件事:两个渲染器都从共享模块
  导入**并调用**它;旧的 no-op 文案在**整棵树**里消失(反向不变量);
  方法名和措辞各自**只有一个写点**。
- **门禁按"模块" pin,不按"函数名" pin。**
  §4 解析出渲染器**导入的那个绑定**再检查它被调用。按名字 pin 会在**改名**时
  变红,而改名不是缺陷(R384, Lesson 1233)。**Lesson 1271。**
- **"清空"必须原子:daemon 确认 → 才换视图。**
  反过来(先清屏再发 RPC)失败了就得到**假清空**:屏幕空的,模型还记得一切,
  而且这次连"清过"都没成功。失败时**什么都不清**,只说原因。
  `runClear` 只在 `newSessionIdOf` 拿到**真的** id 时才返回 `ok:true` ——
  被拒绝的、畸形的结果都算失败,**绝不编一个 id**。**Lesson 1268。**
- **只清视图 = 假清空。**
  `state.turns` 是 daemon transcript 的**镜像**(R372 用 `getTranscript` 重放),
  所以清镜像会让模型还记得、用户看不见、下次切会话整段回来。
  **Lesson 1267。**
- **诚实的"没做"不等于"可以不做"。**
  Ink 侧那句 "(clear is a no-op in this build…)" 是**诚实的**,R378 也正因为它
  诚实才把这条记成"另一个诚实的缺口"推迟了 —— 但能力**一直都在**
  (`createSession` 两条传输上都有)。诚实的降级是给**真没有**的能力用的。
  **Lesson 1266。**
- **能不动 daemon 就不动。**
  `createSession` 语义正好而且**非破坏性**(旧对话还能从 `/sessions` 捡回来);
  自己加一个 `clearTranscript` 会更"直接",但它会**销毁**对话。
  **Lesson 1272。**
- **文档里的"行为描述"也是断言。**
  `aethercode-tui/README.md` 写 "Ink: redraw",而 Ink **从来没有** redraw 过;
  用户手册写 "currently a no-op",现在也不准了(而且"restart" 也不管用 ——
  重启会**重放** transcript)。三份都按真相对齐了。**Lesson 1270。**
- **变异 needle 打错行 = 门禁的假绿。**
  R387 电池第一版有个 HOLE:needle 打在 `id.trim()` 上,可真正拦下畸形 id 的
  是**上一行**的 `typeof id !== "string"` —— 代码变了,**行为没变**。
  改掉 needle(拆掉 id 的类型收窄)才真的被抓。
  **改了代码不等于改了行为,门禁保持绿可能是运气。Lesson 1269。**

## Licence headers (R389 → R391)

The MIT licence header lives in `scripts/` and is applied to every
first-party source file. The header TEXT and the header SHAPE both live in
`scripts/license-header-common.mjs` — the inserter, the remover and the
auditor all import from it, so a shape can never drift between them.
The Python auditor reads the same source through `scripts/license_header.py`.

- **The header carries the FULL MIT TEXT, verbatim.** Not a paraphrase.
  MIT's own condition is "the above copyright notice **and this permission
  notice** shall be included"; "Licensed under the MIT License" carries the
  first and not the second, so a file copied out of this repository on its
  own would carry no grant at all. The wording lives in `MIT_BODY`.

- **Java uses a plain `/* ... */` block comment, not Javadoc.**

      /*
       * Copyright (c) 2026 maijun
       *
       * Permission is hereby granted, free of charge, to any person obtaining a copy
       ...
       */

  google-java-format does NOT reflow a non-Javadoc block comment, so the
  header is no longer a moving target for the formatter — and it stops
  colliding with a file's own Javadoc or `package-info.java` documentation.
  2012 Java files carry this form.

- **Exempt (no header):** `md`, `xml`, `sh`, `bash`, `zsh`, `css`, `cmd`,
  `bat`, `yaml`, `toml`, `ini`, and `json` (JSON has no comment syntax at
  all). Empty files are skipped.

- **Gates:**
  - `node scripts/add-license-headers.mjs --check` — every in-scope file
    carries the header, AND a second run changes nothing (exit 1 otherwise).
  - `node scripts/remove-license-headers.mjs --check` — every header
    round-trips byte-identically. **Never run the remover without
    `--check`/`--dry-run`**: its default writes, and it did once, stripping
    the header from 292 already-migrated files.
  - `python -B scripts/audit_license_headers.py` — proves the change is
    header-only by removing the header and comparing to the blob.
  - `python -B scripts/check_mixed_eol.py` — no file may mix CRLF and LF.
  - `python -B scripts/test_license_headers.py` — the regression tests.
  - `node scripts/check-jsdoc-in-comments.mjs <files>` — no doc comment may
    contain a block-comment terminator.

### The three rules the tools obey (R391)

These are the invariants. Each one was a bug first.

1. **A header's extent is determined by its KNOWN TEXT, never by scanning
   for where the comment run ends.** A licence header is a fixed piece of
   text; the file's own documentation often follows it as more `//` lines,
   and a run-based scanner cannot tell them apart. Scanning swallowed real
   documentation: `ErrorBoundary.tsx` lost its R713 rationale and its R180
   source contract, and a Rust `//!` doc block and a TypeScript
   `/// <reference>` directive were lost the same way (both start with
   `//`). The diff showed only DELETIONS.

2. **One shape set, one path.** The inserter detects ANY existing header
   (current or obsolete) and strips it before writing exactly one canonical
   header. There is no separate "insert" and "migrate" path — two paths are
   two chances to disagree, and they did: an HTML-comment header was
   invisible to both detectors, so `index.html` ended up with two
   copyright notices and two different licence statements.

3. **The tools must be IDEMPOTENT, and the gate must be able to go green.**
   A tool that rewrites every correct file on every run makes `--check`
   permanently red, and a gate that is red on a correct tree is a gate
   nobody trusts.

### Byte-level details that bite (R391)

- **Split on `"\n"` alone, never `/\r?\n/`.** `split(/\r?\n/)` discards the
  carriage return; rejoining with `"\n"` silently rewrote seven CRLF Java
  files with LF endings, leaving them MIXED (a CRLF header on an LF body).
  Split on `"\n"` (each line keeps its `\r`), join the RENDERED header with
  the detected `eol` and the BODY with `"\n"`.

- **The assembly must keep the language DIRECTIVES** — the shebang, the
  Rust inner attributes, the XML declaration, the Python encoding
  declaration, the module docstring. Rewriting the assembly once dropped
  them, and `main.rs` lost
  `#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]`.

- **BOM: detect it from the BYTES, never from the decoded string.**
  `new TextDecoder("utf-8")` silently strips a leading BOM, so
  `raw.charCodeAt(0) === 0xFEFF` is always false. Python's
  `open(..., encoding="utf-8")` does the OPPOSITE (keeps `\ufeff`) — the two
  languages disagree, so normalise explicitly on both sides.

- **Line ending: measure the BODY, not the whole text.**
  `text.includes("\r\n")` asks "does a CRLF appear ANYWHERE", and a stale
  CRLF header makes that answer "yes" forever — so the next run writes
  another CRLF header onto an LF body. 108 files ended up mixed. See
  `detectEol` in `license-header-common.mjs`.

- **Python byte comparisons need `newline=""`.** The default text mode
  applies universal newlines and turns `\r\n` into `\n`, so a CRLF file
  and an LF file become indistinguishable — the audit script read seven
  CRLF Java files as "content changed" when they were byte-identical.

### The regression tests are byte-level, and they are mutation-tested

`scripts/test_license_headers.py` pins all six defects. "The header looks
right" is NOT the invariant — **"everything below the header is unchanged"
is**. So the tests compare bytes, and each defect is proved caught by
re-introducing it (six mutations, six reds).

A test that cannot fail is not a test; a gate that cannot go green is not
a gate. Both of those cost this round real time.


## Watch out for (R388 additions)

- **一个 `case` 标签只能出现一次。**
  重复的标签**不是编译错误,也不是测试失败** —— 第二个分支只是永远匹配不上。
  esbuild 会在构建日志里喊 `duplicate-case`,而没人看构建日志。
  门禁 `r388-command-truth.test.mjs` §1 从两个角度查唯一性
  (router 的 `case` 列表 + 源码里的标签计数),再加一个同名分支就红。
  **Lesson 1273。**
- **目录描述的必须是"能调到的那一个"。**
  `/budget` 的目录写着"阶段的工具调用/成本上限",而真正能调到的是那个
  **死分支**。阶段上限现在有自己的名字 `/phase-budget`。
  **Lesson 1274。**
- **daemon 要求三个字段,就别留一个"省略=保持不变"的默认值。**
  `setPhaseBudget` 缺 `maxCostUsd` 就回 "missing required field",
  而 **0 的含义是"无限"**(`PhaseTracker.Bucket.remainingCostUsd()`:
  `cap <= 0` → `POSITIVE_INFINITY`),`setBudget` 是**覆盖**。
  省略参数会**悄悄拆掉一个成本上限**。现在第三个参数**必填**。
  **Lesson 1275。**
- **命令面板的描述必须从目录派生,不许再长出第二张表。**
  旧的手写表覆盖 23 条命令,目录里 **88 条**中 **65 条**在面板上
  **描述为空** —— 而面板正是用户"记不住命令名"时才打开的发现界面。
  门禁 §2/§3 双向查:每条命令都有非空描述,面板显示的**就是**目录那句,
  且 `CommandPalette.tsx` 里**不能**再定义 `describeCommand`。
  **Lesson 1276 / 1277。**
- **修好一个消费者之后,数一数还有几个消费者。**
  R380 把目录变成了 `/help` 和 Tab 补全的唯一来源,**唯独漏了面板**。


## Watch out for (R388 additions — worktree isolation)

- **worktree 的"成功"不等于"git 隔离"。**
  R388 之前 `addWorktree` 只有两种返回:`branch != null`
  (真 git worktree)或 `branch == null`(空目录 stub),
  **两种都返回 `ok: true`**。客户端无法区分"真隔离"和
  "一个看起来像隔离的空文件夹",唯一的信号是一条没人读的
  WARN 日志。现在每个 worktree 都带 `isolated`(只有真 git
  worktree 才是 `true`)和 `reason`(降级时说明原因),
  新增 `worktreeStatus` RPC 报告 daemon 到底能不能做真隔离。
  **Lesson 1278 — 一个降级路径不能和成功路径长得一样。**
- **文档写了不等于代码读了。**
  `WorktreeManager` 的 javadoc 和 `docs/R148-R150-ARCHITECTURE-COMPLETE.md`
  都写着解析顺序是「setter → `AETHERCODE_WORKTREE_SOURCE_REPO`
  → stub」,但**那个环境变量从来没有被任何代码读过**,
  `setSourceRepo` 也**只在测试里被调用过** —— 于是默认部署
  永远落在 stub 上,而 worktree RPC 看上去一切正常。
  真正的隔离要显式传 `sourceRepo` 参数才会发生。
  **Lesson 1279 — 一段描述行为的文档,和一段实现它的代码,
  是两件事;前者不能作为后者的证据。**
- **参数名遮蔽字段名,会让一条解析链少一环。**
  `addWorktree(String name, String sourceRepo, String baseBranch)`
  的参数 `sourceRepo` **遮蔽**了同名字段。写成
  `sourceRepo != null ? sourceRepo : envSourceRepo` 时,
  **per-manager setter 被整条链丢掉了** —— R149 自己的
  `addWorktree_usesCustomBaseBranch` 用例当场抓到
  (它传 null 参数、依赖 setter)。现在三步链显式写全。
  **Lesson 1280 — 遮蔽是隐式的,调用点读起来完全正常。**
- **`waitFor()` 在读流之前,就是一个管道死锁。**
  `git worktree add` 会为每个文件打印 "Updating files: N%",
  输出远超 64KB 管道缓冲;旧代码先 `waitFor(30s)` 再读流,
  git 卡在写管道上、进程永远不退出,**30 秒后被强杀**,
  留下一个 **半初始化且 locked** 的 worktree(锁文件写着
  "initializing")。现在输出与等待**并发**进行。
  **Lesson 1281 — 先等后读,只有当输出装得下管道缓冲时才安全。**
- **git 继承了 daemon 的 stdin,就是继承了 JSON-RPC 管道。**
  stdio daemon 的 stdin 正是客户端请求的通道;git 若从中读
  stdin(pager、凭据提示),会**吞掉 RPC 帧**、破坏协议。
  现在 `redirectInput(PIPE)` 并立即关闭,git 拿到 EOF。
  **Lesson 1282 — 子进程继承的 stdin 未必是"没有人用的"。**
- **git 报的路径和 JVM 存的路径,分隔符不一样。**
  `git worktree list --porcelain` 报的是 `D:/work/...`
  (正斜杠),JVM 手里是 `D:\work\...`;`toAbsolutePath()`
  **不会**归一化分隔符,所以按路径去重时两个条目仍然不同,
  每个 worktree 在 `listWorktrees` 里出现两次。去重键要
  显式归一化(绝对路径 + 折叠分隔符 + 小写)。
  **Lesson 1283 — 两个来源的同一个东西,先确认它们写的字面量一样。**

## Watch out for (R388b additions — worktree 的生命周期)

- **`git worktree remove --force` 拒绝删除 locked 的 worktree。**
  git 要的是 **`-f -f`**(两个 `-f`)。而 locked 正是**上一次
  `worktree add` 半途失败**留下的状态(锁文件写着 "initializing"),
  所以这**不是边角情况,是重试路径本身**。旧代码只发一个 `--force`,
  于是 R388 那个管道死锁留下的残骸**永远清不掉**。现在失败后
  自动重试 `-f -f`。
  **Lesson 1284 — 一个工具的"强制"常常不止一级。**
- **先删状态再干活,失败了就留下孤儿。**
  `removeWorktree` 原来是 `states.remove(name)` **在前**、git 操作
  **在后**:git 失败时 daemon 已经"忘了"这个 worktree,但 git 还留着,
  分支也还在 —— daemon 和 git 对"存在什么"意见不一致,而且**没法按名字重试**
  (`removeWorktree` 会因为状态没了而返回 false)。现在**只有 git 真的放手了
  才 forget**,失败时保留注册项以便重试。
  **Lesson 1285 — 状态删除要放在副作用成功之后,不是之前。**
- **`git worktree add -b <branch>` 在分支已存在时失败。**
  这正是"部分失败后重试"的样子(分支建好了、checkout 被打断),
  也正是"daemon 重启后重建 worktree"的样子。现在先试 `-b`,
  失败且**确认分支确实存在**时,退化成 `git worktree add <path> <branch>`
  —— **挂到已有分支上**而不是新建。
  **Lesson 1286 — 失败原因决定退路;不要把"分支已存在"当成硬错误。**
- **`createSession` 也要报隔离判定。**
  R388 之后 `createSession({worktree:"x"})` 在没有 source repo 时
  会静默落到空目录,而响应和"真隔离"**长得一模一样**。现在响应带
  `isolated`(以及降级时的 `reason`)。注意区分:普通 cwd 的会话
  报 `isolated: true` —— **没有东西可降级**,客户端不该读成
  "这个会话在 worktree 里"。
  **Lesson 1287 — 判定要一路带到最终的用户可见的那一次调用。**

## Watch out for (R388c additions — skill 与 merge 生命周期)

- **一个 skill 缺失是 WARNING,不是错误 —— 所以它静默。**
  六个 shipped workflow 声明了 `skills: [...]`,**而那些 skill
  一个都不在磁盘上**。`SkillComposer` 把缺失的 skill 当 warning,
  于是 `tdd-feature` —— 一个整个职责就是 red-green-refactor 的
  workflow —— 的 TDD 指导**只活在 yaml 自己的 `prompts:` 块里**,
  而 `skills: [tdd, code-review]` 那张列表是装饰。
  **workflow 的 yaml 和 skill 文件住在两个不同的地方**(jar 资源 vs
  用户主目录),**没有任何东西把它们连起来**,所以也没有东西会发现漂移。
  → 现在补齐了全部 10 个 skill,并加了一个门禁
  (`ShippedWorkflowSkillResolutionTest`):shipped workflow 引用的
  每个 skill 都必须**真的装上了**,而且**必须有 body**。
  **Lesson 1288 — 一个引用列表如果没有门禁,它就是装饰。**
- **skill 的存放格式有两个约定,查清楚再放。**
  AetherCode 的 `SkillComposer` / `SkillRegistry` 找的是
  **扁平的 `<name>.md`**(`~/.aethercode/skills/tdd.md`),
  **不是** `<name>/SKILL.md` 目录形式。放错地方 → 门禁变红,
  而症状是"skill 没装"。
  **Lesson 1289 — 同一个仓库里放同一个东西,可能有两套约定。**
- **一个空 body 的 skill 比缺失的 skill 更安静。**
  它的文件在,`lookup` 成功,于是它出现在 "Injected skills" 列表里 ——
  **prompt 看上去是完整的**,但内容一个字都没有。现在
  `SkillComposer` 把空 body 当成 missing,放进
  "Missing skills (skipped)" 段,让它**可见**。
  **Lesson 1290 — "存在"不等于"起了作用"。**
- **`git worktree add` 的合并要在 source repo 里做。**
  git 拒绝 checkout 一个已经在别的 worktree 里 checkout 的分支,
  所以在 worktree 内部往另一个分支合并是不可能的。R388c 的
  `mergeWorktree` 从 **source repo** 合并(它的 HEAD 才是目标分支),
  而且**拒绝脏的 worktree** —— 合并本身是安全的,但随后删除
  worktree 会丢掉未提交的工作。拒绝时**什么都不改**,用户可以
  commit / stash 之后重试。
  **Lesson 1291 — 拒绝比"顺手做掉"安全。**

## Watch out for (R388d additions — 门禁的 occurrence 编号)

- **★ 加一个 overload 会重排所有兄弟方法的编号。★**
  TUI 的 source-pin 门禁用 **"第 N 个形如 X 的方法"** 来定位 Java
  方法:`javaMethod(runnerCode, "private static int runInternal(")`
  取**第 1 个**匹配,`javaMethod(runnerCode, "EngineFactoryWithSpec
  specFactory) {", 2)` 取**第 2 个**。这些签名是**前缀**,所以:
  - 给 `runInternal` **加第 7 个参数** → 它不再匹配那个前缀 → 编号顺移;
  - 给 `runHttp` **加第 5 个参数** → 同上;
  - **加一个同名 overload** → 编号同样顺移。
  R388d 两个都做了,于是 R371 门禁开始读**一行 delegate**(里面什么
  接线都没有),报出三条**并不存在**的缺陷:`CompactMethods` 没有构造、
  没有注册、HTTP 没拿到同一个实例。
  → 修法:**worktree repo 走一个 static field + setter**
  (`DaemonRunner.setWorktreeSourceRepo`),入口方法的形状一动不动。
  **Lesson 1292 — 一个 overload 不是白加的:它重排了所有共享前缀的
  兄弟,而读这些编号的是没人记得的门禁。**
- **一个"能提取成功但读错了东西"的提取,比提取失败更贵。**
  `javaMethod(code, sig, occurrence)` 的 `occurrence` 参数就是为此存在的:
  `WorktreeManager` 里 `MergeResult.toWireSnapshot()` 声明**在前**,
  `WorktreeState.toWireSnapshot()` 在后 —— 取 occurrence 1 读到的是
  merge 的快照(既没有 `isolated` 也没有 `reason`),门禁报"daemon 从不
  发布隔离判定",而 daemon 一直都在发布。
  **Lesson 1293 — 提取成功但读到的是错的那个,和提取失败一样有害。**
- **门禁的基线数字要"故意地"改,并写清理由。**
  `SLASH_COMMANDS.length` 的基线从 88 → 89:新增的 `/worktree`
  是一个**真正的新能力**(daemon 一直有那些 RPC,只是用户没有名字
  可以敲)。同样的道理,`/work` 从"唯一匹配 `/workflow`"变成
  **歧义**(同时匹配 `/workflow` 和 `/worktree`),补全返回最长公共前缀
  和两个候选 —— **把用户敲的四个字符替他押在一个命令上是撒谎**。
  **Lesson 1294 — 一个新命令会让旧的前缀变歧义;补全必须跟着变。**
- **一个 panel 的键位提示必须来自 registry,不是手写。**
  `WorktreePanel` 手写了 "n new · m merge target · d remove · ↑/↓
  select · Enter merge · Esc close",而它的 `useInput` **一个都没绑**
  —— 提示承诺了三个不存在的键。R381 门禁会抓这个。现在键表在
  `src/componentKeys.ts`,提示用 `hintFor(...)` 渲染,handler 真的
  绑了 `n` / `m` / `d`。
  **Lesson 1295 — 手写的键位提示和它的 handler 是两份会漂移的真相。**
- **降级路径不能和成功路径长得一样。**
  没有 source repo 的 daemon:`worktreeStatus` 报 `available: false` +
  可执行的 `reason`;`addWorktree` 仍然成功,但返回
  `isolated: false` + 同一个 reason。TUI 的 Ink panel 和 line frame
  都**显示** daemon 的 `isolated`,**从不**从"没有 branch"推断它 ——
  detached-HEAD 的 worktree 没有 branch 但**是**隔离的,stub 没有
  branch 但**不是**。
  **Lesson 1296 — 判定归 daemon;渲染器只显示,不推导。**

## Watch out for (R388d additions — 测试不许写进 checked-in 目录)

- **★ 15 个门禁把 tracked 源文件覆盖成了没有 licence header 的生成文件。★**
  有一类门禁需要编译一个"shim"(一行 re-export)才能 import 纯函数
  并**跑**它。因为 shim 的 import 是**相对路径**,它必须坐在被
  re-export 的模块旁边 —— 于是这些门禁都这么写:
  ```js
  const shimPath = join(root, "src", "components", "chat", "_t6_13_helpers.ts");
  writeFileSync(shimPath, shim, "utf-8");   // ← 覆盖了一个 TRACKED 文件
  ```
  `src/` 是 **tracked 目录**。shim 是生成的、没有 MIT header,
  而且**从来不被还原**。症状:**每跑一次 TUI 全量套件,就静默删掉
  7 个 tracked 文件的 licence header**,`git status` 显示它们被改了。
  症状2:`aethercode-compact/tmp-r194-e2e`(13 个 **tracked** 文件)
  是 `r194-end-to-end.test.mjs` 的编译输出目录,每次跑先删后写。
  我两次用 `git checkout -- <paths>` "修好"它,**下一次跑套件又回来**
  —— 因为**原因就是套件本身**。
  → 修法:新增 `aethercode-tui/scripts/test/shim-compile.mjs`。
  它把 `src/` **复制**到 temp 目录当镜像,shim 写在**镜像**里,
  `--rootDir` 指向镜像,相对 import 照样能解析。**checked-in 目录
  一个字节都不动。** 两个 cleanup 门禁从"删掉 shim"改成
  **"断言 checked-in 目录里没有 shim"** —— 变成回归护栏。
  **Lesson 1297 — 测试不许写进 checked-in 目录;生成物一律进 temp。**
- **★ 一个硬编码的绝对路径会让门禁伸手到仓库外面。★**
  `r194-end-to-end.test.mjs` 里写死了
  `"D:\\work\\...\\AetherCode\\aethercode-compact"`。于是**从一个
  隔离副本**(worktree / 基线快照)跑这个门禁时,它编译的、删掉的
  仍然是**真仓库**里的 `tmp-r194-e2e` —— 我做基线对比时,它把我
  真仓库的 13 个 tracked 文件删了,而隔离副本安然无恙。
  → 修法:`join(root, "..", "aethercode-compact")` —— 从**这个文件
  的位置**推导兄弟包。
  **Lesson 1298 — 硬编码路径让门禁越出自己的树;用相对推导。**
- **门禁的失败集合要按"名字"比,不要按"控制台输出"比。**
  这次比对里,同一个测试在两次运行中分别显示成
  `StatusBar: contains the ?? suggested rendering` 和
  `StatusBar: contains the 💡 suggested rendering` —— 那是
  **控制台编码**的差异,不是两条不同的失败。用
  `String.fromCharCode(0x2716)` 读原始字节再比,才不会被
  PowerShell 的重定向编码骗到(它会把文件写成 UTF-16,
  `readFileSync` 读出来全是 `�`)。
  **Lesson 1299 — 比对失败集合要读原始字节,不要信控制台的转述。**

## Watch out for (R388e additions — 渲染器的降级不能是静默的)

- **★ "降级到另一个渲染器" 必须说出来,而且要说清原因。★**
  TUI 自己在 **Ink(全屏)** 和 **line(逐行)** 之间选。判定条件:
  `useLine = wantLine || !haveTty || !inkSupportsRaw`。
  原来的提示语挂在
  ```js
  } else if (!useLine && !inkSupportsRaw && process.stderr.isTTY) {
  ```
  —— 这是**死代码**:`useLine` 在 `!inkSupportsRaw` 时必然为 true,
  所以 `!useLine && !inkSupportsRaw` **永远不成立**。
  结果:最常见的情形(用户**没有**传 `--tui`、终端不支持 raw mode、
  于是拿到 line mode 而不是全屏 UI)**一个字都不打印**。
  用户看到的是一个"能用但少了整个面板/键位/markdown 渲染"的界面,
  没有任何线索说明自己拿到的不是他要的那个。
  → 现在三个分支各自报告:`--line` 是**用户的选择**(不说);
  `!inkSupportsRaw` 说"终端不支持 raw mode,请用 Windows Terminal /
  PowerShell 7 / 支持 raw mode 的 SSH/tmux";`!haveTty` 说
  "stdin/stdout 不是终端"。
  **Lesson 1300 — 静默的降级和静默的失败是同一种坏:用户以为
  自己拿到的是完整的东西。**
- **怎么证明"TUI 走的是 Ink 而不是 line":** 用 `conhost.exe` 造一个
  真的 ConPTY TTY(`conhost --headless -- <cmd>`),子进程里
  `process.stdin.isTTY` / `stdout.isTTY` 都是 true,raw mode 探测
  通过,`useLine=false`。把 conhost 的 stdout 重定向到文件,就能
  抓到**真实的 Ink 帧**(边框、`ASK` 权限药丸、输入框、运行控制、
  状态栏)。
  **Lesson 1301 — 要证明"选了哪个渲染器",就把两个选择都跑一遍,
  看输出里出现的是哪一个。**

## Watch out for (R388g additions — 模块 URL 不是身份,gate 只会 SKIP 等于没有)

- **一个模块的 URL 不是它的身份。bundle 里所有模块共用同一个 bundle URL。**
  `aethercode-memory/src/cli.ts` 结尾有 `if (isDirectInvocation()) {
  main().then(code => process.exit(code)) }`,而 `isDirectInvocation()`
  原来比较 `import.meta.url` 与 `process.argv[1]`。TUI 打成**一个**
  esbuild bundle(`dist/ac-tui.js`),又 import 了 memory 包的**库**接口
  (`index.ts` re-export `./cli.js`)→ esbuild 把 CLI 模块**内联进
  bundle** → bundle 里 `import.meta.url` 就是 bundle 的 URL,而用户
  跑 `node dist/ac-tui.js` 时 `argv[1]` **正是**同一个路径 → guard 返回
  **true** → memory CLI 的 `main()` 拿 TUI 的 argv 跑,认不出子命令,
  **`process.exit(2)`** → TUI 画完第一帧后自己死了。
  症状是"还是进入不了":进程没了,所以 `getState` 的回复永远落不下来,
  `jar` 一直是 `—`。**R388f 的 state replay 让状态栏显示 `✓ ready`,
  但那只是让 TUI 在死掉前一秒"看起来正常"。**
  **判据必须落在"文件"上,不是"模块 URL"上** —— 比 basename:
  `basename(invoked)` 是 `cli.js` / `ac-mem` 才允许自启;bundle 叫
  `ac-tui.js`,不管 argv 是什么都不是 CLI。
  **Lesson 1302 — bundle 里每个模块的 URL 都一样,所以"我的 URL 是不是
  等于 argv[1]"根本区分不了"用户跑了我"和"别的程序 import 了我"。**
- **一个只能 SKIP 的 gate 等于没有 gate。** R388g 的新 gate 第一版有两
  条 skip 路径(esbuild 二进制不在预期位置、bundle 解析不到自己的
  native 模块),都报成 "skipped" —— 那**读起来像有覆盖,其实一次都没跑**。
  最后改用 esbuild 的 **JS API**(和 `scripts/bundle.mjs` 同一个,这样
  bundle 形状和出货的一致)+ `better-sqlite3` 保持 external + cwd 设成
  memory 包目录,native 模块才能按运行时的方式解析。
  **Lesson 1303 — 一个 gate 如果只会 skip,它证明不了任何事;skip 要
  报成"没跑",不能报成"通过"。**
- **从对象上摘下来的方法,bare 调用时没有 `this`。** `InputBox` 的
  `acceptSuggestion()` 原来写 `const pickFn = active.pick; pickFn()`,
  `pick()` 里读 `this.suggestions` → undefined → **TypeError**,而且是
  用户按下 Tab/Enter 接受补全的那一刻 TUI 直接崩。同文件里还有三处
  同形状的 bare 调用(`rangeFn()`、`moveFn(-1)`、`moveFn(1)`)。
  **Lesson 1304 — 这不是风格问题,是崩溃;凡是 `obj.fn` 摘下来调用的,
  都要 `fn.call(obj)`。**
- **`space-between` 不是分隔符,只是"还有地方"的提示。** 把 context
  meter 挪到状态行同一行后,长左边把两半挤到一起,读成了
  `in flightctx[░░░░]`。**Lesson 1305 — 行被填满时两半会贴在一起,
  文字就变成了一个词;要一个挤不掉的分隔符。**
- **只在一种状态下有意义的 UI,就该只在那一种状态下渲染。**
  旧的 `Continue [c] Pause [p] Stop [s]` 行**无条件**渲染,于是空闲
  状态下 TUI 拿整整一行去显示三个**点了什么都不会发生**的按钮。
  Desktop 的 centre panel 只在有 run 的时候显示运行控制。
  → `showControl = !!(onContinue||onPause||onStop) && (state.submitting || state.continuationPaused)`。
  **Lesson 1306 — 三个什么都不会发生的按钮是一行装饰,不是功能。**
- **"参考产品"是一个形状,不是一套样式。** 用户说的是"像 desktop 中间
  那部分",而 desktop 的中间那部分**就是两个组件**
  (`ChatView.tsx` = `<MessageList/>` + `<MessageInput/>`)。
  抄它的**形状**(扁平 transcript + 底部悬浮 pill)比抄它的**像素**
  (820px 上限在这里就是 96 列)更准。
  **Lesson 1307 — 用户拿别家产品做参照时,参照的是布局拓扑,不是配色。**
- **R388h 的门禁:改形状要改"意图",不是删门禁。** 四个门禁钉住了旧形状
  (`borderStyle`、`marginTop={1}`、`width={24}`)。按 **Lesson 1233**,
  钉**位置**的门禁在任何重构上都会红,包括让代码变好的重构 —— 所以
  改成钉**意图**("是个圆角 PILL"、"footer 在 markdown 之后"、"width 是
  显式数字且 ≤ 24"),并把理由写进注释。
  改形状的门禁必须**逐条**留痕:钉住的东西变了,钉它的理由也要跟着变。
  Doc: `docs/R388h-tui-chat-shaped.md`。
- **列表和动作必须对"什么存在"有同一个答案(R388i)。**
  `listWorktrees` 返回的是**两个来源的并集**:manager 自己的 registry
  (`states`)+ git 自己的 `git worktree list --porcelain`。第二个来源是
  有意的 —— 用户在 AetherCode 之外(`git worktree add`)建的 worktree
  必须可见、可挂载。

  但 `mergeWorktree` 和 `removeWorktree` **只查 registry**。于是:
  **列表里显示了一个 worktree,用户点"合并",daemon 回"no such worktree"
  —— 对着一个它刚显示出来的东西。** 这是 R377 的形状向上翻了一层:
  两个界面 disagree 什么存在,**由用户**来发现。

  修法:两个动作都走 `resolveForMerge(name)` —— registry 命中就返回;
  没命中就去 git 的列表里找(按 name 或 path 的 file name),并**补上
  resolved source repo**(git 的记录没有 sourceRepo,因为 git 不知道这个
  worktree 属于哪个 repo —— 它**就是**那个 repo 的 worktree;不补的话
  merge 会换一个理由再失败一次)。

  **Lesson 1308 — 一个"列表"和它的"动作"读不同的数据源时,列表里那一项
  就是一次未来的失败;凡是 list 用了并集,action 就必须能解析到并集的
  每一项。**
  **Lesson 1309 — 从另一个来源补来的记录,缺的那个字段要自己补:
  "记录存在"不等于"记录够用"。**
  回归测试:`WorktreeManagerR388iTest`(6 例;去掉修复后 3 例红)。
- **只通过"被测代码本身"造状态的测试,看不见"状态从别处来"的缺陷。**
  `WorktreeManagerR388Test`(21)+ `R97MTest`(5)全绿,因为它们每一个
  worktree 都是用 `addWorktree` 造的 —— 永远在 registry 里。漏掉的正是
  "worktree 在 **git** 里但不在 registry 里"这一条。
  **Lesson 1310 — 一个只会用被测代码造状态的测试套件,无法发现"这个状态
  是从别处来的"那类缺陷。**
  Doc: `docs/R388i-list-and-action-disagree.md`。
- **R388j** (one frame, one width contract — and a lesson about MEASURING):
  `PanelShell` 画外框,六个面板组件**自己也画了一个**(`borderStyle="double"`),
  于是屏幕上出现两个套在一起的框:边框格子重复(`║║`)、内框比容器宽、
  标题撞进边框、提示行溢出到框外。**规则:外框归 shell,内容归面板。**
  卡片(tool card / permission card / stop banner)仍可以自己画框 —— 那正是
  卡片的意义。新增 `PanelBody.tsx`:无外框 + 宽度契约 + `fitRow`(唯一负责
  补齐到宽度或截断加省略号的地方)。宽度契约**只推导一次**
  (`panelWidth - 4`),由 host 传给每个面板 —— 面板自己猜宽度,只会在终端
  改尺寸那一刻开始错。
  `/sessions` 的作用域必须**诚实且有出路**:空列表要说实话("50 个会话里
  0 个记录在这个目录"),并且给一个**按键**(`a` → `widenPanelScope` 就地
  放宽作用域),而不是让用户去记另一个命令的名字。
  ★ **但这一轮最大的收获是:那个"右边参差不齐"根本不是渲染缺陷。**
  Ink 把行尾的补白写成 `ESC[nX`(擦 n 格)+ `ESC[nC`(光标右移 n 格),
  而不是空格;**把所有 ANSI 序列剥掉的测量工具把那两条也删了**,于是每一行
  都"短了 74 格",边框看起来就在乱跑。按终端的方式重建屏幕(遵守光标移动)
  之后,**每一行的右边框都在第 100 列**。而且 Ink 的 `<Box width={n}>` 会把
  子节点**拉满**宽度,所以右边框由**布局**决定,不由文字决定。
  **Lesson 1311 — 一个把被测对象删掉的测量工具,看不见被测对象。**
  ANSI 剥除器适合读文本;终端不是文本文件。
  **Lesson 1312 — 测量和修复必须是同一个产物。** 每一次修复尝试都用同一个
  坏工具验证,所以工具本身从没被怀疑过;当一个"应该能修好它"的改动修不好时,
  先怀疑仪器。
  **Lesson 1313 — Java 源文件里不能出现字面的反斜杠-u 序列,注释里也不行。**
  Java 的词法分析在 parser 看到文件之前就处理 unicode 转义,所以 javadoc 里的
  `{@code \uXXXX}` 是**编译错误**("非法的 Unicode 逃逸"),不是文档字符串。
  **Lesson 1314 — 会飘的测量不是"系统会飘"的证据。** 先证明测量本身是确定的
  (连跑三次看它是否自洽),再去追缺陷。
  **Lesson 1315 — 一个渲染文本框的组件自带 `useInput`,它也会收到你的按键。**
  "我在自己的 handler 里 return 了"不等于"这个键什么也没发生"。`/sessions` 面板
  里按 `a`:面板 widen 了作用域,同时 filter 框和 prompt 框里也各多了一个 `a`
  ——三处 `useInput` 同时收到同一个键。
  **Lesson 1316 — 一个只差一个 Enter 的命令是个陷阱。** autocomplete 下拉框在
  打开时独占 Enter(接受补全就 return),于是 `/sessions` + Enter 只是把输入框
  填成 `/sessions `,**命令没有跑**;用户得再按一次 Enter。"我记得住的命令"正是
  用户最容易踩到的形状。现在:输入已经是完整命令时,补全是装饰性的,同一个
  Enter 就把命令跑掉;完整性的判断来自**命令目录**(`isCompleteSlashCommand`),
  不是第二份列表。
  **Lesson 1317 — 一个 TYPE-ONLY 的 import 要用编译器的 paths 映射,不是
  package.json 依赖。** desktop 的 `aethercode-themes` 是 type-only import,而
  R209 明确把它从依赖里**移除**了(那个包从未发布,实现改成了轻量版);
  把 `file:../aethercode-themes` 加回 `dependencies` 会直接违反那个决定,并让
  `ThemeSettings.test.tsx` 的门禁变红。正确做法是 tsconfig 的 `paths`:
  没有安装期依赖,`package.json` 不变,R209 的门禁照样绿。
  门禁:`r388j-panel-frame.test.mjs`(11 例,含两条**真渲染**断言:在真 TTY 下
  渲染真的 shell + 真的 panel body,再用终端模拟器重建屏幕);
  三个变异都**证明会红**(`fitRow` 不补齐 / `ThreadSelector` 自画边框 /
  面板打开时 prompt 仍然接收按键)。
  Doc: `docs/R388j-one-frame-one-width.md`。
- **R388k** (业界对标的 UX 审计 + 三个 P0/P1 修复):对着 Claude Code / Cursor /
  OpenCode / Cline / Devin Local / Aider 审计 TUI,发现并修了:
  **(1) 纯命令行下 SDD 全线不可用(P0)** —— daemon 的 `sdd.advance`/`sdd.abort`/
  `sdd.status` 都要求 `slug`,而 line renderer **从不发 slug**(它没有 reducer 状态,
  "记住"一个 slug 是不可能的)。于是 `/sdd <intent>` 之后的每一条 SDD 命令都死在
  "slug and action are required",报错还长得像"这个 daemon 不支持 SDD"。修法:
  **slug 是"发现"的,不是"记住"的** —— daemon 本来就有 `sdd.listRuns {cwd}`,新增
  `src/sddRun.ts`(`pickActiveRun`/`isActiveRun`/`extractRuns`/`sddParams`,纯函数
  无 Ink),line renderer 用它发现当前 run。这也符合业界形状:Devin Local 的 plan
  写在**仓库外的一个 markdown 文件**里、跨会话存活;Claude Code 的 plan 在
  `~/.claude/plans/`。
  **(2) `/help` 只看得到 89 条里的 3 条(P0)** —— help 原来是一个 ~110 行的字符串,
  作为一个 `sideNote` 发出去,被 scrollback 截到只剩 3 行,没有滚动、没有分页、
  没有过滤,命令 4–89 根本够不着。现在 `/help` 返回 `__HELP_PANEL__` 哨兵,
  Ink 打开新的 **CommandHelpPanel**:过滤框 + 滚动窗口 + `↑/↓` + `Enter` 跑命令
  (走**同一个 router**)。行由**命令目录投影**而来(R380 的"唯一清单"规则),
  纯逻辑拆到 `src/commandHelp.ts`(没有 React,门禁可直接 import)。
  **(3) `/sdd-list` 在两个 renderer 里都是坏的** —— 它是个裸
  `rpcMethod: "sdd.listRuns"`,没有 params,而 daemon 要求 `{cwd}`。现在改成
  `__SDD_LIST__` 哨兵,两个 renderer 都拦截(只有 renderer 知道自己的 cwd)。
  另外 line mode 不再拒绝 `/sdd-view|diff|snapshot|clear-cache`:它给出
  **产物路径**,让 shell 自己的 `cat` 去渲染(纯终端不需要富文本查看器,需要的是
  路径)。
  门禁:`r388k-line-sdd.test.mjs`(11 例)、`r388k-help-panel.test.mjs`(9 例)、
  `r388k-one-name-one-surface.test.mjs`(5 例);五个变异**证明会红**。
  重复面用**真跑 `routeSlash` 按"效果"(method+params)归并**来判定,不肉眼看名字:
  真正的重复只有 `/theme` + `/theme-pick`(现已 `aliasOf`);`/continue`+`/pause`
  是同一个 RPC 的**反向**两个动作,`/sdd-approve`+`/sdd-skip` 是**不同 action**,
  都不是重复。
  **Lesson 1318 — 一个"文件里有没有这个模式"的检查,无法钉住逐调用点的契约。**
  第一版 SDD 门禁只断言 `line.ts` 里**某处**有 `slug:` 和 `cwd: ctx.cwd`;
  把 `__SDD_ADVANCE__` 改回无 slug 的形状,门禁**照样绿** —— 因为
  `__SDD_ABORT__`/`__SDD_STATUS__` 仍然带着。**其它正确的实例替错误的那个背书。**
  每条命令必须在自己的块里检查。
  **Lesson 1319 — 找"某个词"的检查会在注释里找到那个词。** 第二版问"这条 arm 提到
  slug 了吗",而变异 `params: { action }`(恰恰是修复前的形状)**活了下来** —— 因为
  arm 自己的注释里有 "slug" 字样,还调了 `resolveActiveSddRun`。契约要在**它真正
  被交付的地方**检查:那个变成 request params 的表达式。
  **Lesson 1320 — 对共享字符串做"邻近正则"只会绑住其中一个出现位置。** 两个
  `sdd.listRuns` 调用点,去掉其中一个的 cwd,门禁依然绿。契约适用于每个调用点时,
  就得**逐个调用点**断言。
  **Lesson 1321 — 契约检查要锚在"调用"上,不是锚在一个也出现在散文里的字符串上。**
  扫 `src/line.ts` 里的 `sdd.listRuns` 找到 5 处,其中 3 处是注释、错误信息和面板标题。
  **Lesson 1322 — 在一大块里搜一个标签,可能搜到**邻近构造**的标签。**
  `paramsExpression(block)` 在整个文件尾巴里找 `params:`,结果抽出了**另一条命令**
  的 params。提取必须拆成"有 `params:` 标签的块"和"裸实参表达式"两种形状。
  **Lesson 1323 — 组件里的纯逻辑,只能通过组件来测。** help 面板的投影和过滤写在
  `.tsx` 里,门禁 import 不了(bundle 会拉进 `react`/`ink`,临时目录里的 bundle
  解析不到)。把数据拆到 `src/commandHelp.ts` 才测得了 —— 而且代码因此更好。
  **Lesson 1324 — 没有花括号的 `case` arm 是另一种形状,假设有花括号的提取器会读到
  错的代码。** `case "__SDD_STATUS__":` 没有块,找"下一个 `{`"会抓到后面某个 arm 的
  花括号,报出一个并不存在的缺陷。
  **Lesson 1325 — 同一个方法 + 不同的 params 不算重复。** `/continue` 和 `/pause`
  都调 `setContinuationStopped`,是一个开关的两面;`/sdd-approve` 和 `/sdd-skip`
  都发 `__SDD_ADVANCE__`,是两个不同 action。只按 METHOD 归并的门禁会要求删掉
  它们 —— 所以门禁按**效果**(method + params)归并,并排除已声明的 alias。
  **Lesson 1326 — 审计对某个子系统的断言,是对另一个系统词汇表的断言,它无法自证。**
  审计初稿说"always allow 只在 session 内生效,没有落盘",列成 P2。读完 daemon
  发现**完全相反**:`P` → `<cwd>/.aethercode/permissions.json`,`U` →
  `~/.aethercode/permissions.json`,而且 `ProjectPermissionPolicy.check` 的顺序就是
  **deny → ask → allow → mode**,和 Claude Code / Cursor / OpenCode 一字不差。
  初稿只读了 **TUI 的按键处理**就推断 daemon 的行为 —— 而**作用域是按键选的,
  持久化是 daemon 做的**,客户端只看到一半。这和 R377 是同一个形状(拒绝表声称
  方法不存在,而 daemon 其实是**用同义词**提供的)。反复出现的缺陷是:
  **相信客户端对服务端的视图**。审计报告里保留**作废记录**(哪一条、为什么错、
  当时读错了哪一半),不要删掉 —— "审计怎么错的"本身就是交付物的一部分。
  **Lesson 1327 — 现有的双向门禁会抓住你新加的东西,别绕过它们。**
  R388k 加 `/help` 面板后,r362 的 "every panel sentinel has a registry entry"
  报 `__HELP_PANEL__` 没有 owner,r381 的 "every registered table's tokens are
  exactly the handler's tokens" 报面板 handler 多绑了一个 `/`。两条都是**真缺陷**
  (前者:哨兵没有归属;后者:handler 绑了键表没写的键),都由既有门禁发现 ——
  它们的"双向"设计正是为了这个。加新表面时**先跑这两个门禁**,而不是等全量。
  **Lesson 1328 — 一个共享的注册表,会让"每个传输层的接线不同"这个缺陷变得不可见。**
  daemon 的五个 `sdd.*` RPC 由 `AetherCodeMethods.registerAll(...)` 在**两个**传输层
  都注册了 —— 所以"方法注册了吗?"在两边都答"是"。但真正干活的 **orchestrator 只在
  `runHttp` 里装**(`http.methods().setSddOrchestrator`),stdio 路径上它是 null,
  于是每个调用都抛 `-32603: Internal error: sdd orchestrator not configured`。
  **TUI 的 line renderer 走的就是 stdio** —— 所以整个 SDD 面在纯终端里不可达,
  而且**只改客户端永远修不好**。
  这是 R720 的形状往下一层(`memory/*` 注册了但 store 没装):这里更糟,方法是
  **已知的**然后运行时才失败,客户端于是相信这个能力存在。
  → **只有一次真实调用才能发现它。** 静态检查看不见:注册表是共享的,接线是
  每传输层不同的,所以"注册了吗"这个问题永远问不到这个缺陷上。
  修法:stdio 路径也装 orchestrator(同样的 best-effort 语义 + 同样的
  `reloadActiveRuns(cwd)` 重挂),门禁
  `SddOrchestratorWiringR388kTest`(5 例)**逐传输层**问"装了吗",并按
  **marker** 而不是签名取方法体(否则会读到 overload 的**一行 delegate** ——
  R388d 踩过同一个坑)。
  Doc: `docs/R388k-agent-cli-ux-audit.md`。
- **R388l** (权限"评论框":注释真的到达模型):照 Claude Code 的权限提示实现
  "Tab 打开评论框、评论作为理由/上下文发给模型" —— 布尔门禁变成沟通通道。
  **先查清楚了现状再动手**:wire 上**本来就有** `reason`,**deny 路径本来就能**
  到达模型(`reason` → `PermissionDecision.reason` → `PermissionResult.Deny.of(reason)`
  → `Event.Completed(call.id(), d.message(), true)` = tool_result)。
  **洞在 approve 分支**:`JsonRpcPermissionPrompter.thenApply` 写的是
  `new PermissionResult.Allow(input)`,reason 被**丢掉了**;而 `Allow` 这个 record
  只有 `Map updatedInput` 一个字段,**根本没有地方放**。
  → 用户写"yes, but write to /tmp first",注释**到达 daemon 然后蒸发** ——
  用户以为自己说了,模型永远没听见。这正是**静默降级**的形状(Lesson 1300)。
  **Lesson 1329 — 只检查客户端的门禁证明的是"注释发出去了",不是"注释到了"。**
  客户端和 daemon 是两个系统,注释可以死在它们的边界上。两端都要有门禁,而且
  **谁都不能替谁作证**。本轮 TUI 门禁同时断言了 daemon 侧(prompter 带上注释、
  executor 把它追加进 tool_result),Java 侧另有一个走**真实往返**的测试。
  **Lesson 1330 — 一个"没有留言槽位"的批准分支就是一个静默丢弃。**
  字段"存在"不等于"被用上"(Lesson 1230 往上一层:approve 分支把**已经收到**的
  reason 丢了)。
  **Lesson 1331 — 机器的理由和用户的话是两件事,不能共用一个字段。**
  `reason` 是审计轨迹("TUI picked deny"),`comment` 是用户原话
  ("no — use the read-only tool")。合成一个字段,executor 就只能把样板话当成
  用户的话呈现给模型,或者逼用户自己敲样板话。
  实现:`PermissionResult.Allow` 与 `PermissionDecision` 各加一个**可选**分量,
  **旧的构造器全部保留**(`Allow(Map)` / `PermissionDecision(decision, reason)`),
  所以 15 个生产点 + ~40 个测试点的 `new Allow(input)` **一字不改**继续编译、
  继续是原来的意思。executor 用**一个** `appendApprovalNote(output, note)` 追加,
  队列路径和 sink 路径**共用**它(只走一条路的注释 = 只sometimes到达模型的注释)。
  客户端:Ink 里评论框是**输入框的一个模式**(卡片是 inline 的),`Tab` 开、
  `Ctrl+<键>` **装填**要发的决定、Enter 发、Esc **只取消不回答**(ask 仍 pending);
  **评论框开着的时候决定键被挂起**(否则打 "d" 会拒绝而不是写字),
  默认装填 **deny**(开评论框的人几乎都在说"不完全这样",而默认 allow 是
  **危险的那一侧**)。line mode 同契约(picker 本来就有 ↑/↓ 列表,更贴
  Claude Code)。两个 renderer 用**同一个** `permissionResponseParams` ——
  两个 renderer 不许手搓两种 wire 形状。
  **空评论是"省略"而不是 `""`** —— 没有评论的回复必须和 R388l 之前**逐字节相同**。
  门禁:`scripts/test/r388l-permission-comment.test.mjs`(19 例)+
  `JsonRpcPermissionPrompterR388lCommentTest`(11 例);
  变异电池 `scripts/test/r388l-mutate.mjs` **10/10 全部变红**(含**原始缺陷**
  本身:daemon 在 approve 分支丢掉注释)。
  **Lesson 1332 — 一个键是隐藏的模式就是用户用不了的模式。** 评论框在自己的
  header 里写明控制键,并且**发送前**就能看到装填的决定。
  **Lesson 1333 — 一个改错了位置的变异是伪装成"门禁弱"的空操作。**
  `String.replace` 用字符串 pattern 只换**第一处**,而 **10 个空格的缩进里
  含有 8 个空格的 pattern 作为子串** —— 所以变异改到了"评论模式里的 Tab
  空操作"而不是"打开评论框的 Tab",真身没被动,门禁当然还是绿的。
  正确做法:变异锚在 handler **做了什么**(`commentMode = true`),而不是它的
  条件文本;门禁的断言也要**有界**(取 comment-mode 块**之后**的那一段)。
  → 这是 Lesson 1322("在一大块里搜一个标签,可能搜到邻近构造的标签")的
  推论:**改错位置的变异会报告一个并不存在的门禁缺陷。**
  **本轮自己的新门禁第一遍是绿的,而既有门禁又抓出 4 个真缺陷** ——
  ① `keybindings.ts` 的和弦是**从 handler 推导**的(它扫 `input === "x"`
  在 Ctrl 窗口里),而 handler 写的是 `armDecision(box, input)`(一个裸
  `input`,扫描器看不见)→ help 会承诺 Ctrl+A/D 这些"注册表看不到绑定"的键;
  修法:handler 把和弦**显式写出来**,注册表再登记(带 `when`:"a permission
  comment box is open",和既有的 `d` 绑定一个形状)。
  ② `key.tab || input === "\t"` 让扫描器把 `"\t"` 当成**第二个**和弦,
  help 于是欠用户一个没人登记过的绑定 → 只用 `key.tab`。
  ③ InputBox 手写了 "Ctrl+A/T/P/U/D/N to change · Enter send · Esc cancel"
  → 组件注册表禁止手写键提示,文字改从 `componentKeys.ts` 来。
  ④ **注释里引用代码 = 代码**:注册表的扫描器读的是**原始源码**(含注释),
  两条解释性注释引用了它们描述的比较式,于是被读成两个多出来的"未绑定和弦"。
  **Lesson 1335 — 读原始源码的扫描器也会读你的注释。** 当扫描器是真相来源,
  代码周围的散文也是输入的一部分。
  **Lesson 1336 — 注册表看不见的和弦,help 就没法承诺。**
  注册表是**从 handler 推导**出来的,所以 handler 必须对它可读:把和弦显式
  写出来,让注册表去登记。
  变异电池 `scripts/test/r388l-mutate.mjs` **13/13 全部变红**(含**原始缺陷**
  本身:daemon 在 approve 分支丢掉注释;以及"Tab 检查长出字面量 `"\t"`"
  这个幽灵和弦)。
  **Lesson 1337 — 重新构建之后大小一模一样的 jar,就是没有重新构建的 jar。**
  shade 插件的"是否过期"判定把**旧构建配置**留下的产物留在了 `target/`,
  连着三次 `mvn package` 每次都只更新了 mtime,内容一动没动。破绽是
  `original-…jar`(shade **之前**的 jar)竟然和"shaded"那个一样大 —— 真正的
  shade 会让 jar 涨 ~57 MB,不可能一样大。**shaded jar 的产物名是
  `aethercode-cli-0.1.0-SNAPSHOT.jar`,不是 `…-shaded.jar`。**
  **Lesson 1338 — "BUILD SUCCESS" 不指名是哪一个产物。** 构建成功了,但被
  检查的文件和被发布的文件不是同一个。要验**内容**(`javap` 出 class,找只有
  新代码才有的字符串,这里是 `"user said"`),不能只看时间戳。
  Doc: `docs/R388l-permission-comment.md`。
- **R388m**(权限截止时间 + 一套宽度规则):三条契约,每条都关掉一个**读代码
  读出来的**缺陷(不是看屏幕看出来的)。**(1) 权限 ask 由 daemon 公布截止
  时间**(`timeoutMs` + `expiresAtMs`)。此前客户端只能**猜**超时,而猜错两个
  方向都致命:猜短了会在用户正要批准时把它拒掉,猜长了用户盯着一个 daemon
  已经解决掉的 prompt。**一个数,不是两个数** —— 客户端数的必须是 daemon 强制
  的那个。**(2) 倒计时必须真的拒绝**:一张写着 "auto-denies in 1:12" 然后又
  静坐五分钟的卡片是**装饰**,不是契约 —— 用户无法分辨这个倒计时有没有意义。
  所以客户端自己发 deny,并且:用 **ask 的身份**做守卫(用户在期间答了,不能
  误拒另一个 ask)、**回答时清掉定时器**(一个 ask 一个定时器)、卡片**自己
  走秒**(只在有截止时间时开 1Hz 定时器)。**(3)「没人能批准」是一句话**,
  而一句话有三个副本就是三句话:headless `--print`、line(非 TTY)、daemon
  (无 client)此前各写各的,而且**没有一个告诉模型"别重试"** —— 模型的默认
  反应就是再试一次,而对着权限墙重试每次都一样失败(症状:跑不完、
  `loopStops=0`、`errorRate=0.89`、`turnsCompleted=0`)。现在统一走
  `src/permissionDeadline.ts` 的 `nobodyCanApproveReason`,并且
  `permissionResponseParams` 新增可选 `reason` **覆盖**默认审计串,否则模型
  只看到 "line mode picked deny"。
  **(4) 一套宽度规则**:产品里曾有**三套** —— line input 按**单元**
  (CJK=2,光标要落对位置),而 `PanelBody.fitRow`、markdown 表格、system-prompt
  表格按**字符**。中文标题在 60 单元的面板里被当成 30 字符 → 实际占 60 单元
  → **冲出右边框**(这就是"对话框参差不齐"的成因)。现在统一到
  `src/cellWidth.ts` 的 `cellWidth`/`fitCells`/`padCells`/`maxCells`;
  所有**用户可控字符串**的列(`padEnd`)都换成 `padCells`。
  **Lesson 1339 — 用 `.length` 算出来的列宽,是**单位错了**的列宽。**
  终端是**单元**的网格,CJK 占 2 格。
  **Lesson 1340 — 只**显示**的倒计时不是契约。**
  **Lesson 1341 —「没人能批准」是一句话;一句话有三个副本就是三句话。**
  **Lesson 1342 — 共享工具函数可以带着它要消灭的那个缺陷。**
  `fitCells` 的**截断**分支是按单元的,**填充**分支却是 `plain.padEnd(w)`
  (按字符)—— 同一个函数、同一次提交里,一个对一个错。**每个分支都要查。**
  **Lesson 1343 —「文件乱码」在证明之前是对**读的人**的指控。**
  conhost 的输出一直是正确 UTF-8(`e2 8c ac` = `⌬`),mojibake 来自
  PowerShell 的 `Get-Content`。**读文件用 node,不要用 `Get-Content`。**
  **Lesson 1344 — 倒计时要**向下取整**。**向上取整会显示并不存在的时间
  ("0:01" 而实际是 0),用户按的键会迟到。
  **Lesson 1345 — 权限墙拒掉一次调用时,模型需要知道**两件事**:
  没人能批准,以及**不要重试**。少了第二条,重试循环就是必然结果。
  **Lesson 1346 — 契约检查要锚在**构造**上,不是锚在一个也出现在散文里的
  字符串上。** `[\s\S]{0,2000}?` 这种**邻近**正则:改个名就变红(重构不
  算缺陷),把 deny 挪到 1500 字符外的另一个函数里照样变绿(那才是缺陷)。
  改成**抽取那个 block** 再断言 block。
  **Lesson 1347 — 产品没错的时候,先怀疑仪器。** 这一轮的终端模拟器错了
  **三次**(没实现换行 / `ESC[nC` 越过末列不换行 / `ESC[nX` 扩展了行而不是
  裁剪到终端宽),每次都是仪器的问题;修好后:可见屏幕 30 行、maxCells=119、
  **0 行超过 120** —— R388j 的宽度契约本来就成立。
  **Lesson 1348 — 一个数,不是两个数。** 客户端数的截止时间必须**就是**
  daemon 强制的那一个;公布一个窗口、强制另一个,是客户端赢不了的赛跑。
  **Lesson 1349 — 一个要穿过每个调用点的参数,就是放错了位置的参数。**
  定时器表作为 `replyPermission` 的第 7 个位置参数,把每个调用点撑成
  9 行(prettier 展开),**并且**打破了 R388l 门禁锚在
  `replyPermission(client, ask, "allow"` 上的字符串 —— 一次**无害**的重构
  把门禁弄红了。这张表只有一个主人(权限流程),所以**模块级**才是诚实的
  作用域;React ref 反而要穿过一个模块级函数,毫无收益。
  **而且:当一次 reflow 把门禁弄红时,先问门禁钉的是"拼写"(Lesson 1233)
  还是"不变量"。** 本次门禁钉的确实是拼写,所以它对"更干净的重构"变红
  是**假警报** —— 该改的是**参数的位置**,不是删门禁。
  **Lesson 1350 — 重载(overload)也是一种"接线差异"。** 一个 override
  了**五参** `askPermission` 的 stub,不会被**六参**重载的调用命中。第一版
  R388m 加了 `askPermission(..., long timeoutMs)` 并让 prompter 直接调它,
  结果**每一个** stub 都被绕过:ask 无人应答,future 超时,测试看到的是
  **一条超时消息**而不是它要验的契约。这是 Lesson 1328 的形状 —— 共享的
  注册点让接线差异不可见;只不过这里的差异是**重载**,而**唯一的症状
  是一条超时消息**。修法:ask 仍然由**大家都会 override 的那一个方法**
  发出,截止时间通过一个**读后即清**的字段传进去(所以不会漏进下一次 ask)。
  **Lesson 1351 — 测试里的"超时消息"是**症状**,不是判决。**
  "ask 超时了"读起来像"被测代码太慢";实际上它意味着"这次调用去了 stub
  看不见的地方"。**读消息,然后问:到底调用的是哪一个方法。**
  **Lesson 1352 —"诚实"和"可演示"都是要求。** 一个只渲染出"这个功能
  诚实地不存在"的 demo,什么也没演示。demo 的合成 ask 现在带上了来自
  **那一个常量**的截止时间;而且它的等待窗口被**加长**了 —— 60 秒的上限
  比 120 秒的倒计时**短**,会把它正在展示的那个功能自己截断。
  Doc: `docs/R388m-permission-deadline-and-cell-width.md`。
- **R388n**(Claude Code 对标的四项:resume 选择器 / checkpoint 菜单 / 字面模式指示 /
  「Yes, and switch to auto mode」):四项里有**一项是真的后端** ——
  checkpoint 菜单。Claude Code 的六个 rewind 操作里,daemon 此前只实现了
  **一个**(`rewind`,只回滚对话);「Restore code」和「Summarize from here」
  **根本没有**。R388n 加了 `CodeCheckpointStore`(按**工具调用**推出的
  「本次会话写过哪些文件」,快照这些文件的**改前**内容)、`Compactor.
  summarizeNow`(**不受上下文窗口门槛约束** —— 用户显式要求必须总结)、
  `restoreConversationTo` / `summarizeAtCheckpoint`,以及三个 RPC
  (`checkpoints/list|restore|summarize`,stdio **和** HTTP 都接)。
  **(1) 序号和位置是两种地址。** 引擎会写好几种**用户没打**的
  `Role.USER` 消息(`session-anchor` / `compaction-side-note` /
  `transcript-trim-side-note` / `todo-ask-llm` / `user-decision-resume`),
  而既有的 `rewind(target)` 数的是**所有** `Role.USER`。所以压缩提示之后
  「回到第 3 轮」会落在提示上而不是用户的第三条消息 —— **功能看起来能用,
  实际上在破坏对话**。修法是**一条计数规则、一个地方**:
  `CodeCheckpointStore.userTurnIndices` / `isRealUserTurn`,生产者和
  消费者都走它;而**以 checkpoint 为锚**的两个操作**根本不重新推导**边界
  —— 菜单说的是「回到这一点」,这一点是**位置**,不是序号。
  **(2)「两者都恢复」是标志位,不是第三套实现。** 六个动作各自带
  `touchesCode` / `touchesConversation`,派发读**标志**。若为「都恢复」
  单独写一条分支,两半就会漂移:一边修了另一边不修,用户在同一个菜单的
  两个条目里得到不同的结果。scope 上线也是**一次请求**
  (`scope: code|conversation|both`)—— 两次请求意味着代码恢复成功、
  对话恢复失败,而用户被告知「done」。
  **(3) 快照必须在**轮次边界**取。** 工具跑完再取,取到的是「改后」,
  恢复等于没恢复;提示入账之前取,又不知道属于哪一轮。所以
  `QueryEngine` 加了 `setOnTurnBoundary` 钩子(预检压缩之后、提示入账
  之前触发)。它是**第二个 setter**,不是 `setOnUserPrompt` 的第二个参数 ——
  一个 setter 装两个东西,后装的会**静默顶掉**先装的。
  **(4) 恢复代码的三种结果各有一种。** 原来存在 → 写回内容;**本次会话
  建的** → **删掉**(写回空文件会在用户的树里留下一个 0 字节文件);
  **二进制** → **跳过并报告**(写回坏字节比不恢复更糟)。
  **(5) Esc Esc 是**第二次**。** Esc 已经是「关掉浮层 / 停掉运行 / 清掉
  队列」,抢走**第一次**会让每个面板都要按两次 Esc 才关得掉。规则:
  600ms 内的**第二次** Esc 开菜单,第一次照常走梯子。
  **(6) 字面模式指示。** `ACCEPT_TASK` 对没读过源码的人毫无意义,于是每个
  模式配一句人话(`⏸ manual mode on` / `⏵⏵ auto mode on` / …),
  **字形承载唯一要紧的事实**:`⏸` = 还会问,`⏵⏵` = 不会。
  **未知模式一律当「还会问」** —— 必须往安全的一边倒。
  **(7) 权限卡的「Yes, and switch to auto mode」。** 此前六���答案
  (A/T/P/U/D/N)**没有一个**改会话的权限模式;陷阱是 `P`/`U` **看起来**
  像:它们装的是**工具/目标**的规则,不是 `PermissionMode`。用户读
  「allow for this project」按 `P`,以为会话不再问了 —— 没有。
  现在是第五个编号答案 `[M]`,`mode = ACCEPT_TASK`。**模式走线上自己的
  字段**,并且 `setPermissionMode` 在 `permissionResponse` 之后**独立**发
  (不 chain):**审批不能依赖装饰性后续** —— 模式变更失败不能拖垮/搞挂
  用户已经批准的调用。`armedLabel` 现在按 decision+scope+**mode** 三者
  匹配:两个答案产生同一 decision 时,按 decision 查会找错。
  **编号是显式数据**(1..5),不是从数组顺序派生的 —— `t`/`y` 是同义词,
  派生会造出 6/7/8 这些不存在和弦。
  **(8) 决策键 handler 改成注册表驱动。** 键住在 `DECISION_KEYS`,但
  Ink 的 handler 是**手写 if 链** —— 同一批键的**第二份列表**。新的 `M`
  会住在注册表里(卡片能宣传它)而 if 链不知道它(按了没反应):
  **键看起来是活的,其实是死的**。
  **(9) 分支是**读文件**得到的,不是 spawn 子进程。** 五十行列表 × 一次
  `git` 子进程就是五十个进程;`GitBranchReader` 直接读 `.git/HEAD`,
  跟随 `.git` **文件**(worktree/submodule),按 git 目录缓存 2 秒。
  **detached HEAD / remote ref / tag 一律返回 null** —— **错的标签比没标签
  更糟**。
  **(10) 重命名不能**搬走** session。** `SessionStore.touch()` 会一并写
  `cwd`,而 session 列表已经在用它;拿它做重命名会把 session **改挂到
  另一个目录**,session 从它本来属于的项目里消失。所以 `setTitle` 只写
  **一列**。
  **Lesson 1355 — 两个答案产生**同一 decision** 就是两个答案。** 按
  outcome 查标签会找错(显示「allow once」而实际发的是「allow 并切模式」)。
  **Lesson 1356 — 宣传了却没接线的键,比不存在的键更糟。** 吃掉用户一次
  按键,并且教会 UI 会撒谎。
  **Lesson 1357 — 审批不得依赖装饰性后续。** chain 住 `setPermissionMode`
  会让模式变更失败拖垮/搞挂已批准的调用。
  **Lesson 1358 — test runner 的子进程若不清 `NODE_TEST_CONTEXT`,失败
  不会反映在退出码**(会永远「存活」);变异测试必须
  `delete env.NODE_TEST_CONTEXT`。
  **Lesson 1359 — 菜单项要写**操作**,写「标签」就检查不了。** 六个动作
  各自命名它**执行**什么,同一个字符串就不能被复用于两个操作。
  **Lesson 1360 —「两者都」是标志位,不是第三套实现。**
  **Lesson 1361 — 序号和位置是两种地址;重新推导自己边界的恢复,是可能
  落在别处的恢复。**
  **Lesson 1362 — 快捷键不能抢走它共享的那个键的含义。**
  **Lesson 1363 — 「必须包含这段调用」的检查若读**原始**源码,被注释掉
  的调用也能满足它。** 读**剥掉注释**的源码。本轮变异测试正是靠这条
  抓到门禁自己的缺陷:注释掉 `onTurnBoundary.accept(...)` 之后门禁仍然绿。
  Doc: `docs/R388n-claude-code-parity.md`。
- **R388o**(TUI 整体渲染:框会变、位置会动、输入一变整页都在动):用户报的
  三个症状 —— 顶部**十几个重复的 header**、输入框**右边框被切掉**、状态栏
  **折行** —— **没有一个**是它看起来的原因,而且有**两个**独立的成因。
  **(A) 宽度溢出 → 十几个 header。** 状态栏右侧那一格带着 `flexShrink={0}`
  (**永远不缩**),在 80 列的终端里整行是 **93** 格(超 13 格)。**超宽的行会被
  终端折行** → 帧多出一行 → 帧比 `stdout.rows` 高 → Ink 切到
  **`clearTerminal` 路径**(`ESC[2J ESC[3J ESC[H`) → **经典 Windows 控制台
  不认 `ESC[2J`** → 每次重绘都是**追加**而不是替换 → **十几个 header**。
  **`ESC[2J` 就是全部的故事**:认它的终端只画一帧,不认的终端把帧叠起来。
  **(B) flex basis 挤压 → header 整个不见(这才是真正难的那个)。**
  **把 root 钉在终端行数上是必要的,但**不充分**。Yoga 给 flex item 的
  **base size = 它的内容高度**,而 flex item **只在容器溢出时才缩**。于是
  比终端高的 transcript **不会自己裁剪** —— 它**抢走**兄弟的行,把 chrome
  **挤掉**。实测(Yoga 树 dump,100×30,12 轮 transcript):header 的外框算出来
  **只有 1 行**,品牌行和 cwd 目录行都算成 **height 0**(被挤没了),输入框只剩
  **上边框**。用户看到的就是「本该是 header 的位置只剩一条横线」+
  「画了一半的输入框」。
  **修法是一条 flex-basis 链**:transcript 的列 `flexBasis={0}` +
  `minHeight={0}`(base size 归零,长进 chrome 让出来的行),
  chrome(header / 输入框 / 状态栏)`flexShrink={0}`(不管 transcript 多高都
  保住自己的行)。修完同样的 dump:header 3 行(top=0/1/3),transcript 26 行
  (top=4)。
  **(B2) 八个改动里**只有**一个**是**承重**的。** 逐个删掉再渲染一帧来量:
  删掉 **Scrollback 自己那一列的 `flexBasis={0}`** → **BROKEN**(输入框没了
  下边框);删 `minHeight={0}`、删两处 `overflow="hidden"`、删宽度表达式、
  删行预算、删 header 的 `flexShrink={0}` → **全都无影响**。其余七个是
  **便宜的保险**,留着;但**门禁的变异必须锚在承重的那一个上**。
  **(B3) 第一轮二分是**错的**:结论说「`flexBasis={0}` 删掉也没事」。**
  原因是那个正则命中了**文件自己写的注释**里的 `flexBasis={0}` ——
  **每一次「删除」都是空操作**,量的一直是修好的代码。还有两处同源的错:
  一个变体的锚点写的是 `paddingRight={rightPad}` 而文件里是
  `paddingRight={leftPad}`(于是「HEAD + 修复」那几轮全都等于「HEAD 原样」);
  另一个变体在**测 header** 时 **Scrollback 还停在上一轮的已变异状态**,
  量到的其实是 Scrollback。
  → **Lesson 1380 — 没有生效的变异,和「这个改动不重要」是无法区分的。**
  每次「删除」都必须**回读文件**确认,每个变体都必须**从恢复后的基线开始**。
  **(C) 窄终端要**丢项**,不要**挤**。** 40 列时 header 的状态条是 64 格,
  模型名被切成两半(`⌬MiniM · AS⏸ manual`)。现在 header 和状态栏都按
  **优先级**丢右侧的项(header: elapsed → session → ctx;状态栏: jar →
  cost → out → in → ctx),**永不丢**的是模型、模式徽章、状态标签、连接点。
  被**丢**的项是不存在,被**挤**的项是**错的**。
  **(D) 挤压不等于截断。** 第一版给状态栏右侧加了 `flexShrink={1}` ——
  Ink 缩的是**盒子**,里面的 Text 节点**互相重叠**:
  `jaaethercode-cli-0.1.0-SNAP`。右侧保持**自然宽度**,唯一能安全缩短的
  是 jar 名,而且是**提前**按**量出来的**预算截断的。**估出来的**预算错了
  8 格,行照样溢出,而且**一声不吭**。
  **(D2) 但真正让状态栏溢出的**不是** `flexShrink`。** 逐个关掉再量:
  关掉「jar 名截断」→ **溢出**(40 列里 71 格);关掉「右侧永不缩」→ **正常**;
  两个都关 → **溢出**。**溢出的是 jar 名的**长度**,修法是**提前**按量出来
  的预算把它截短。名字截短之后,盒子的自然宽度**总是**放得下,
  「能不能缩」根本无关。**原来归咎于 `flexShrink` 是错的。**
  → **Lesson 1375** 的另一个用法:**当一个变异「没让门禁变红」时,先量它
  到底是不是缺陷;是缺陷就修门禁,不是缺陷就**把变异删掉**,而不是把门禁
  拧到同意自己。**
  **(E) flex 子节点上的固定 `height` 会**抢走兄弟的行**。** 第一版给
  transcript 的内容列加了 `height={budget}`:实测 30 行的终端里输入框和
  状态栏**整行消失**。所以内容列**不设高度**,裁剪来自
  `overflow="hidden"` + flex 已经算好的列宽。
  **(F) 终端尺寸只有一个出处。** 新模块 `src/terminalGeometry.ts`:
  `terminalSize()` / `transcriptRows()` / `fitCells()`。旧代码在**四个**
  地方问终端,给了**四个**不同的兜底(120、80、裸的
  `process.stdout.columns`、以及「调用方给什么就用什么」)—— 而 **120 比
  多数终端都宽**,于是那个「安全」的兜底**正是**溢出的来源。兜底改成
  **100**。
  **(G) 组件问 `process.stdout` 拿宽度,在**任何**不是 `process.stdout` 的
  渲染目标里都是错的。** `InputBox` 自己去问终端,于是测试渲染器(宽度 40)
  里画出了 **99 格**宽的输入框。宽度现在是一个 **prop**
  (`width={cols}`),兜底才问终端。
  **(H) 宽字符表只有一份。** `terminalGeometry.ts` 的第一版**自己抄**了
  一份 East-Asian-Wide 表(而且已经和 `cellWidth.ts` 的不一致)—— 这正是
  R380 的主题。现在它 **import** `cellWidth.ts`,门禁 pin 住「只有一份」。
  **(I) 根节点现在钉在终端的行数上**(`height={rows}`),帧永远是终端那么大,
  Ink 永远走「原地重绘」路径。
  门禁 `scripts/test/r388o-geometry.test.mjs` **15/15**:宽度 40–160、
  高度 12–50、输入变长(含**换行**)帧高不变、状态栏不折行、状态标签不被裁、宽终端状态栏
  全显、header 身份不被裁、宽终端 header 全显、输入框每行都有左右边框、
  **长 transcript 下 chrome 仍然存活**、**transcript 的 flex 链不能挤掉
  chrome**、**transcript 的行预算有界**、**状态栏各项不重叠**、
  **输入框最多显示 `INPUT_MAX_LINES` 行**、宽字符表只有一份。
  它**测量**真实组件(Header/StatusBar/InputBox/
  **Scrollback**)渲染出来的帧,用 `r388j-render-frame.mjs` 那套**忠实终端
  模型**重建屏幕;并且**从 `src/tui.tsx` 里读出** `transcriptBudget` 表达式和
  `<Scrollback height={…}>` 的用法(`new Function` 求值),所以对产品里预算
  的变异是**看得见的**。
  变异测试 `scripts/test/r388o-geometry-mutation.test.mjs` **8/8**:八种
  变异(状态栏右侧开始缩、状态栏不丢项、header 不丢项、**jar 名不截断**、
  transcript 预算回到 1000、输入框高度上限被去掉、**Scrollback 的 flex
  basis 丢掉零**、**输入框不再用传进来的宽度**)全部让门禁**变红**。
  ★ 八个锚点里有**两个**曾经命中**注释**而不是代码(`flexBasis={0}` 在
  `Scrollback.tsx` 的注释里也写着),于是「变异生效了但门禁是绿的」——
  **没有生效的变异,和「这个改动不重要」是无法区分的**(Lesson 1380)。
  另有门禁 `scripts/test/r388o-console.test.mjs` **2/2**:在**真实
  conhost** 里跑**发布的那份 bundle**,断言 `ESC[2J` 计数 ≤ 1、header 在、
  输入框**右边框没被切**、状态栏在。
  真实 bundle(发布的那一份)在真实 ConPTY 下:**`ESC[2J` 计数 1**、输入框
  **四角都在**(`↵]`)、状态栏**一行**、header**完整**。
  **Lesson 1364 — 症状的形状不是它的成因。** 用户说「页面在动」,帧的
  **高度**从来不是问题;是**宽度**,然后是**flex 链**。先量,再推。
  **Lesson 1365 — 门禁的**覆盖范围**是它契约的一部分。** 只测 80/100/120
  列的门禁,看不见 40 列下的溢出,而产品确实在那里溢出。
  **Lesson 1366 — 「必要」的修复不因此就「充分」。** 把 root 钉在终端行数上
  是对的,但它**没有**修好消失的 header。「帧放得下」和「chrome 活得下来」是
  **两个**契约,需要**两个**检查。
  **Lesson 1367 — flex 子节点上的固定 `height` 会抢走兄弟的行。**
  **Lesson 1368 — 挤压不是截断。** `flexShrink` 让 Ink 把盒子里的 Text
  节点**重叠**;要缩短**文本**,预算还得是**量出来的**。
  **Lesson 1369 — 估出来的预算是一次静默的溢出。**
  **Lesson 1370 — 窄终端丢项,不挤项。**
  **Lesson 1371 — 一个渲染了崩溃页的探针,量的是崩溃页。** 探针的 stdin
  是管道,`useInput` 抛异常,Ink 把**错误页**当帧渲染;门禁于是把
  「状态栏不见了」读成了缺陷。
  **Lesson 1372 — 探针不能复现它要测的缺陷。** 探针没有传宽度,组件
  于是自己去问终端 —— 正是本轮要修的那个 bug。
  **Lesson 1373 — 穿过 junction 的路径会解析回原处。** 变异测试的副本放在
  OS 临时目录里:门禁用 `dirname(import.meta.url)/../..` 算自己的根,放在
  `<pkg>/node_modules/` 下的副本那条路径**穿过 junction**,**解析回原来的
  树** —— 探针导入的是**原始的** `StatusBar.tsx`,变异**根本没在门禁面前**。
  于是六个变异全部「门禁是绿的」,而那个绿**什么也不证明**。副本现在放在
  OS 临时目录里,并且门禁会**报告**它读的是哪棵树。
  **Lesson 1374 — 测量模型必须知道它所测的那个量。** `reconstructScreen`
  原本没有宽度概念,超宽的行被**默默吞掉**,于是一个 60 格的规则在 40 列
  的终端里看起来**完全正常** —— 而真实终端会把它**折行**,多出一行。
  模型现在会**折行**,门禁也直接量**帧自己的行宽**(折行之前的)。
  **Lesson 1375 — 复现不了缺陷的变异不是变异。** 第一版的变异测试断言
  「把规则改成固定 60 格会让门禁变红」—— 它**没有**变红,而**本来就不该**:
  规则是 Yoga 已经定好宽度的 Box 里的 `<Text>`,而 `<Text>` **会折行**,
  60 格的规则在 80 列的盒子里只占**一行**的 60 格,从来不溢出。真正的
  溢出一直是那个 `flexShrink={0}` 的右侧盒子。断言一个**从来没坏过**的
  改动会让门禁变红,是**噪音**,它盖住的是真信号。
  **Lesson 1376 — 探针不能**镜像**产品。** 探针现在渲染**真实组件**、
  按**真实的树**布局,并且从 `src/tui.tsx` **读出**产品自己的表达式。
  **Lesson 1377 — Yoga 的 base size 就是内容的高度。** flex item 的 base
  size 是它内容的高度,所以一个高的 transcript **不会自己裁剪** —— 它从兄弟
  那里**抢行**。让内容让位的链是 `flexBasis={0}` + `minHeight={0}`。
  **Lesson 1378 — 一个问 `process.stdout` 拿尺寸的组件,在任何不是
  `process.stdout` 的渲染目标里都是错的。** 宽度是调用方**已经知道**的事实,
  把它当 prop 传下去。
  **Lesson 1379 — 把布局树 dump 出来,不要靠推理。** 十来轮「高度是好的 /
  宽度是坏的」的推理**完全漏掉**了 flex-basis 那个成因;三十行 Yoga dump
  一次就找到了。
  **Lesson 1380 — 没有生效的变异,和「这个改动不重要」是无法区分的。**
  `flexBasis={0}` 的删除正则命中了**注释**里的那一份,每一轮「删除」都是
  空操作,量的一直是修好的代码 —— 于是得出了「这个承重改动无所谓」的错误
  结论。**每次「删除」都要回读文件确认;每个变体都要从恢复后的基线开始。**
  **Lesson 1381 — 不承重的防御性 prop 可以留,但**门禁**必须锚在承重的那
  一个上。** 锚在不承重的 prop 上,门禁会永远绿,并且什么也不证明。
  Doc: `docs/R388o-terminal-geometry.md`。

- **R388p**(rewind 菜单:列表装不下、↓ 是死键、两个 summarize 读起来一样、
  后端的 checkpoint store **无界增长**):四个缺陷,全部**先量再改**。
  **(A) 列表没有窗口。** 实测 40 个 checkpoint 渲染出 **53 帧行**(40 行终端装不下),
  而且**选中项也可能被挤出屏幕**。现在列表有窗口(新模块
  `src/listWindow.ts`:`listWindow()` + `moveSelection()`),
  并且菜单**说出**「上面还有 N 个 / 下面还有 N 个」——**静默的截断看起来像列表到头了**。
  修完:40 个 checkpoint → **22 帧行 / 8 行可见**。
  **(B) ↓ 键是死键。** 菜单**开在最新的那一条**(index = length-1),而旧代码
  `Math.min(checkpoints.length - 1, i + 1)` **夹住**它 —— 所以**打开后第一次按 ↓
  什么都不发生**。实测:连按 3 次 ↓,标记仍在 `▶ #40`。
  → **Lesson 1383 — 一个什么都不做的键,比一个不存在的键更糟。** 用户分不清
  「这条不适用」和「这里坏了」,于是不再试这个功能。列表选择必须**回绕**:
  顶部按 ↑ 到最新,底部按 ↓ 到最旧,两个方向**永远有意义**。
  **(C) 两个 summarize 读起来一模一样** —— 而我**第一次改错了**。
  两个条目原本共用同一句 blurb。我第一版按菜单的**名字**推断它们是**相反**的
  操作("from" 留尾巴 / "up_to" 留头),并把它写进代码和门禁。
  **读引擎的源码才发现是反的**:`summarizeAtTranscriptIndex` 里
  `int split = keepAfter ? boundaryIndex : boundaryIndex + 1;`,
  注释明说 **「the head is what gets summarised; the tail is what survives」**
  —— **两个动作都总结头部、保留尾部**,唯一的差别是**分界线落在那条消息之前还是之后**
  (`up_to` 把那条消息本身也折进总结)。于是 blurb 改成
  「summarize everything BEFORE this point, keep what follows」/
  「summarize up to AND INCLUDING this point, keep what follows」。
  → **Lesson 1382 — 对另一个系统行为的描述是**主张**,而主张不能自证。**
  门禁现在**读引擎自己的分界规则**和**菜单自己的 blurb**,检查两者一致。
  **钉住一个「故事」而不是「行为」的门禁,是让代码去说谎来迎合门禁。**
  **(D) 后端的 store 无界 —— 这条不是 UI 问题。** `CodeCheckpointStore` 用一个
  裸的 `LinkedHashMap` 存**全部** checkpoint,而**每个 checkpoint 存的是会话
  **至今碰过的每个文件的完整内容副本**。于是内存是
  **turns × 碰过的文件数 × 文件大小** —— **随会话长度平方增长**,每一轮都更糟。
  长会话会把堆撑爆,daemon 最后死于 `OutOfMemoryError`,而用户**看不到任何
  可以行动的现象**。现在 `MAX_CHECKPOINTS = 64`,**FIFO 淘汰**(旧的先走 —— 用户
  要回退的是最近的),淘汰数**上到线上**(`checkpoints/list` → `evicted`),菜单
  **说出来** —— 一份从「point 40」开始的列表,看起来像 daemon 丢了历史。
  门禁 `scripts/test/r388p-checkpoint-menu.test.mjs` **14/14**:列表装得下、
  帧不随列表增长、**选中项永远在屏幕上**、菜单说出藏了多少、**↓ 不是死键**、
  两个方向相反地移动、**回绕**、两个 summarize 读起来不同、blurb **与引擎一致**、
  **引擎真的保留尾部**、store **有界**、`listWindow` 保证选中可见、
  **空列表**、`moveSelection` 回绕。
  ★ 探针**渲染真实组件并发送真实按键** —— 缺陷 2 在静态渲染里**根本看不见**。
  ★ **Ink 在 'readable' 事件上读输入**:它的 `App.js` 做
  `stdin.on('readable', handleReadable)` 然后
  `while ((chunk = stdin.read()) !== null)`。所以假 stdin 必须**缓冲**字节并从
  `read()` **交还**;发 `'data'` **完全没用** —— Ink 根本不听它。一个发
  `'data'` 的探针量的是「按键不工作的组件」,然后把**组件的按键**报成坏的。
  ★ 而且 `read()` 必须在**构造之后**覆盖 —— `Writable` 原型上有自己的
  `read()`,对象字面量里的方法会被**原型遮蔽**,Ink 拿到的是原型那个
  (返回 `null`,什么都收不到)。
  变异测试 `scripts/test/r388p-checkpoint-menu-mutation.test.mjs` **7/7**:
  列表不设窗口、选中项可以跑出窗口、↓ 回到夹住、↑ 回到夹住、菜单不说藏了多少、
  两个 summarize 读起来一样、store 的上限被去掉 —— 全部让门禁**变红**。
  每个变异**改完回读文件**,断言目标文本**已经不在**(否则量的是没被改过的代码);
  副本放 **OS 临时目录**(junction 会解析回原树)。
  **Lesson 1384 — 假 stdin 要实现**读**的那一侧,不是写的那一侧。**
  **Lesson 1385 — 「列表有界」是关于**行**的主张,不是关于**帧**的主张。**
  加了窗口之后,短列表的帧反而**多两行**(「上面还有 N 个 / 下面还有 N 个」两行标记) ——
  第一版门禁量的是帧,于是对着**正确的代码**报红。
  **Lesson 1386 — 无界的快照 store 是平方级泄漏,而且它不是 UI 缺陷。**
  上限属于 **store**,不属于渲染器;被丢掉的**数量**必须**到达用户**。
  **Lesson 1387 — 固定延时的探针测的是「机器够不够快」,不是产品。**
  R388o 的 console 门禁(真实 bundle 跑在真实 conhost 里)在这一轮**红了**,
  断言是「the bundle produced no output at all」—— 而 bundle **一个字节没改**,
  **直接跑**时渲染完全正常。根因:shim 用 `setTimeout(exit, 4000)` 决定何时退出,
  而 `runTui` 是 **`await client.request("ping")` 之后才 `render()`** ——
  **Ink 在 daemon 回答之前不画第一帧**,冷启动的 JVM 比 4000ms 慢,于是进程在
  **第一帧之前**就退出了,门禁量到的是**空屏**。实测:4000ms → **0 字节**;
  20000ms → **55738 字节**。差别**不是**修复,是 **daemon 起来没有**。
  现在 shim **监视 stdout,看见第一帧就退出**(用**字节数**当信号,conhost 自己的
  启动清屏只有几个字节),45s 兜底。
  ★ 改完之后**重新证明门禁还能变红**:把 bundle 换成**什么都不渲染**的 stub,
  门禁必须**变红** —— 它确实红了。「等久一点」很容易把门禁改成**空洞通过**。
  **Lesson 1388 — 钉住一个「故事」而不是「行为」的门禁,会让代码去说谎来迎合它。**
  (Lesson 1382 的重述,因为它让本轮**先改错了一次**。) 两个 summarize 的第一版修法
  断言它们是**相反**的操作 —— 那是按菜单的**名字**推的,不是读 daemon 推的。
  引擎在**两个**动作里都是**总结头部、保留尾部**。
  Doc: `docs/R388p-checkpoint-window.md`。


- **R388q** (the file-changes card — the field name was the bug):
  用户给了桌面端 end-of-turn 卡的截图,要求「TUI 和这个框展示类似,大差不差,
  不要随便几个线条就给糊弄过去了」。目标形态:
  `╭────────────────────────────────────────────────────────────────╮`
  `│ ⊕  已编辑 19 个文件  +2235 -36   │  查看文件        [撤销] [审查]   │`
  `╰────────────────────────────────────────────────────────────────╯`
  —— 七个组成部分**每一个都带信息**:独立格的图标 / **粗体标题(文件数是头条)** /
  **绿 `+N` 红 `-N`**(卡里唯一承载语义的两种颜色) / 分隔线 / 暗色链接 / 两个带框按钮。
  **根因不是样式,是字段名。** daemon 的 `eventToMap` 发的是
  `tool_use_start` → `{type,id,name,input}`、`tool_result` → `{type,id,content,isError}`;
  而 **Ink 渲染器读的是 `ev.args` / `ev.result`** —— daemon **从不发这两个字段**。
  所以 **Ink 的每一张工具卡都是空盒子**:没有路径、没有参数、没有结果、没有 diff 计数。
  (line 渲染器 `line.ts` 读的是 `ev.input`,所以只有 Ink 这条路坏了。)
  实测:把 daemon 的真实事件喂给真实 `ToolCard`,渲染出来是
  `│ - ✎ file_write  13ms  ·  Tab: collapse  ·  d: details │` —— 一个近乎空的盒子。
  现在:新增 `src/fileChanges.ts`(纯 TS,门禁可 import)——
  `countLines` / `diffStatsFor` / `fileChangeFor` / `summarizeFileChanges`;
  新增 `src/components/FileChangesCard.tsx`(`borderStyle="round"` 圆角框);
  `Scrollback.tsx` 把**连续**的文件变更 turn 合成**一张卡**;
  `tui.tsx` 改成 `ev.input ?? ev.args` / `ev.content ?? ev.result`(两种拼写都收)。
  ★ **diff 计数在客户端从 tool 的 input map 算**,不改 daemon:daemon 的
  `ToolWidgets.DiffStats` 服务的是 **line 渲染器的审批 widget**;Ink 已经收得到
  input 字段(`file_write.content` = 整个新文件,`file_edit.old_string/new_string`
  = 删/增)。计数**来自工具当时的输入**,不是重新读磁盘上的当前文件。
  ★ **19 个文件 = 1 张卡**,不是 19 张卡 —— 19 张卡既回答不了「这回合改了什么」,
  又是一片噪音;per-file 明细放展开态。
  ★ **连续的**文件变更 turn 合并一组;中间的 bash 等**非文件 turn 切断**分组
  (否则会静默合并两边的写入)。
  门禁 `scripts/test/r388q-file-changes.test.mjs` **13/13** ——
  ★ **门禁渲染真实组件并量它的形状**(不是 grep):grep `borderStyle="round"`
  只证明**源码里有这个字符串**,不证明**终端里画出来是什么**。
  量的七件事:七个组成部分都在 / 边框是圆角 / **8 个宽度下卡片都是 3 行** /
  **9 个宽度下每一行的 cell 数都等于边框的**(不溢出) /
  **9 个宽度下计数从不被截断** / 0 个文件时**什么都不渲染** /
  **1、3、19 个文件都只出 1 张卡** / 展开态列出每个文件 /
  渲染器读的是 daemon 的**真字段名**(门禁**两侧都钉**:daemon 的
  `r.put("input", …)` 链**和**渲染器的读法 —— 任一侧改名门禁就红,而不是卡片变空) /
  计数来自工具自己的 input / 空文件是 0 行 / Scrollback 的分组被非文件 turn 切断。
  ★ **门禁的测量也是按 cell 的**,不是按字符 —— 一个按字符计的门禁**看不见溢出**。
  变异测试 `scripts/test/r388q-file-changes-mutation.test.mjs` **12/12**:
  方角边框、删掉 `-N`、删掉 `+N`、删掉链接、删掉撤销、删掉审查、删掉分隔线、
  删掉标题、预算改回按**字符**算、渲染器改回读 `ev.args`、空文件又算 1 行、
  分组不再被非文件 turn 切断 —— 全部让门禁**变红**;并且断言**红的原因是对的**
  (输出里必须点名那条断言,而不是探针崩了)。
  ★ 三个实测出来的缺陷:
  **(A) 终端无法内联带边框的 Box** —— 带 `borderStyle` 的 Box 占**上下两行**,
  所以桌面端那种「两个带框按钮并排」在终端里**做不到**:卡片被撑成 4 行。
  → 动作改成**单行括号标签** `[撤销 u]`(本 app 已有的写法:权限卡写 `1 [A] allow…`),
  卡片回到 **3 行**(边框 / 内容 / 边框)。
  **(B) 宽度预算按**字符**算 —— 标题 `已编辑 19 个文件` 是 **10 个字符但 15 个 cell**,
  5 个 cell 的误差让行溢出、Ink 换行、**计数被截成 `-1`**。
  → 预算全部改用 `cellWidth`(R388m 的**唯一**宽度规则),并按**优先级**丢项:
  先丢动作、再丢链接、最后才动标题尾巴 —— **计数永远不截**(它就是答案)。
  **(C) 窄宽度下链接丢得太晚**,导致行换行、卡片变 4 行 —— 同 (B) 的根因。
  实测 11 个宽度(96/80/60/50/45/40/35/30/24):**每个宽度都是 3 行,
  每行的 cell 数都等于边框的(mismatched=0),`-14` 全程存活**。
  ★ 探针的 bundle 必须**自带 ink**(`nodePaths` 指向真 `node_modules`),
  因为 Ink 需要 ESM(打成 CJS 会在它自己的 `require("assert")` 上死);
  但**全打包的 ESM 产物在运行时**又会挂在「Dynamic require of assert is not supported」
  —— esbuild 把 `require` 改成了桩。解法是官方那行 interop shim:
  `banner: { js: "import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);" }`。
  ★ **源码里的 ESC 转义字节是隐形的** —— 帧边界是原始流里最后一个 `ESC[2K`,
  而第一版门禁把它写成**字面字节**,被编辑器/格式化器/复制粘贴吃掉,文件里只剩
  `[2K` 三个字符 → 边界永远匹配不上 → 每次测量量的都是**累积流**。
  现在写成 `\u001b`。
  **Lesson 1390 — 客户端自己解析 wire 字段,就等于自己写一个解析器,而它一定是错的。**
  Ink 渲染器**自己发明**了 `args`/`result` 两个字段名,daemon 发的是
  `input`/`content`。**没有任何东西大声失败**:卡片就是空着。
  **一个服务器从不发的字段名,是一次无声的、彻底的信息丢失。**
  **Lesson 1391 — 终端无法内联带边框的 Box。** 带边框的 Ink Box 占两行,
  所以「行内一个带框按钮」不是样式选择,是**做不到**。
  **把桌面端的部件搬进终端之前,先问终端的原语到底能做什么。**
  **Lesson 1392 — 按**字符**量的宽度预算,就是**错的**宽度预算。**
  CJK 文本会打穿每一个 `String.length` 预算。app 只有一个宽度规则(`cellWidth`),
  预算用它 —— **门禁也得用**,否则门禁同样看不见这个缺陷。
  **Lesson 1393 — 计数就是答案,所以计数是**活下来**的那个。**
  装不下时按**优先级**丢:动作 → 链接 → 标题尾巴;**截断标题的布局比丢掉装饰更糟**。
  **Lesson 1394 — 格式化器的 reflow 会静默打断门禁的锚点。**
  本轮 prettier 重排了卡片和两个门禁;变异锚点是**精确空白**字符串,
  reflow 打断一个 → 变异**不生效** → 量的是没被改过的代码 → 报「门禁没有牙齿」
  —— 而那是关于**变异**的陈述,不是关于门禁的。格式化后**重新验证**了 12 个锚点
  (全部仍然唯一),而且变异助手现在**自己**断言唯一性。(R376 的教训第二次落地。)
  **Lesson 1395 — 挂在构建错误上的门禁,是**因为错误的原因**红。**
  沙箱副本必须带上**整个 `src/`**(卡片 import `../theme.js` 和 `../cellWidth.js`)
  和**同级的 Java 树**(门禁要读 daemon 自己的 map),否则探针死在缺文件上 ——
  而「红」会意味着「脚手架坏了」,不是「断言抓到了缺陷」。
  最后一条变异测试因此断言:红的输出里**必须点名**那条失败的断言。
  ★ **变异门禁抓到了一个**空洞断言** —— 这正是它存在的理由。**
  第一次跑变异门禁是 **11/12**:删掉**分隔线**的那个变异让整个门禁**保持绿**。
  原因:「卡片有分隔线」写的是 `assert.match(row, /│/)`,而 `row` 是**连着边框列**的
  内容行 —— 卡片自己的框在每一行的**两端**都画了 `│`。于是这条断言是被**边框**满足的,
  分隔线删掉它照样绿。★ **一个 LOOKUP 能满足的检查,根本不是检查**(R381 的 Lesson 1217
  换了新衣服):这里的「lookup」就是**边框本身**。
  修法:量**内部**(去掉边框列),并且**按位置**断言分隔线 —— 它必须在**计数之后**、
  **链接之前**,这才是「计数和动作之间的分隔线」的意思。边框那两根在行两端的 `│`
  **满足不了一个位置断言**。
  ★ **绿的门禁不是证据。被证明会红的门禁才是。**
  **Lesson 1396 — 边框可以满足一条关于它自己**内容**的断言。**
  Doc: `docs/R388q-file-changes-card.md`.

- **R388r** (the card's advertised keys must DO something — and the gate had NO teeth):
  R388q 的文件变更卡广告了三个动作 `查看文件 f` / `[撤销 u]` / `[审查 r]`,
  **三个都不做任何事**。用户拍板语义:**撤销 = 打开回溯菜单**、**审查 = 展开文件清单**。
  两个独立缺陷:`Scrollback` **没传任何回调**(卡片的 `onUndo`/`onReview` 是 undefined);
  卡片的 `focused` 是**内部 `useState(false)`**,**从来没有任何东西把它设为 true** → key handler 是死代码。
  ★ **实测:Ink 的 `useInput` 把按键送给**每一个** mounted hook** —— 探针输出
  `["text:d","cmd:d"]`。所以**裸字母命令在用户打字时也会触发**(app 现有的 `d` 键
  就是这样)。→ 卡片键必须是**命令模式键**:**仅当 prompt 为空时生效**。
  (app 的 `d` 键碰撞是**既有**问题,本轮**不复制**它,也**不修**它。)
  落盘:新增 `src/fileCardActions.ts`(纯 TS)—— `FILE_CARD_ACTIONS` /
  `FILE_CARD_ACTION_INFO`(卡片的标签、app 的绑定、门禁**读同一张表**)/ `fileCardActionFor` /
  ★ **`fileCardEffect` / `fileCardEffectFor`**;卡片改成**纯展示**(删掉自己的 stdin 监听
  和内部 focus state,`focused`/`actionsLive` 变成 prop,不能触发的动作**变暗**);
  `Scrollback.tsx` 接线;`tui.tsx` 加 `focusedFileCardTurnId`(用共享规则) + 三个动作函数;
  `keybindings.ts` 加三条 registry 条目。
  ★ **语义是一个**值**,不是一次 grep** —— app 的 `useInput` handler **没法单独 mount**,
  所以第一版门禁只能 grep 源码,而 grep 不是检查(见下)。于是语义被提到纯模块里:
  `FileCardEffect = {kind:"toggleFiles"|"openRewind"|"none"}`,app 变成**翻译器**
  (`applyFileCardEffect` 一个地方把 effect 变成 dispatch),**键盘和卡片自己的回调都走它**
  → 卡的键和卡的按钮不可能含义不同。
  ★ **撤销只开菜单,不猜回退到哪个点** —— daemon 的 restore 是 **checkpoint 粒度**
  (一批文件 + 一段对话),卡片是 **turn 粒度**;所以让用户自己选(和 `Esc Esc` 同一条路)。
  ★★ **本轮真正的发现:变异门禁是**空的**,而且它「证明」自己有牙齿。**
  第一版变异门禁把源码复制到临时沙箱再改,但**沙箱没有 `node_modules`**,
  而门禁的探针要 bundle Ink 和 React。探针构建器**硬编码**了
  `nodePaths: [join(ROOT, "node_modules")]` —— 那是**沙箱**的,不存在 →
  探针死在 `Could not resolve "ink"` → **每个变异都红了,红在构建错误上**。
  ★ **挂在构建错误上的门禁,会对**任何**改动变红,包括正确的改动。它不是有牙齿,
  它是有一根绊线。** R388q 的门禁早就解决了这件事(读 `process.env.R388Q_NODE_PATHS`,
  由调用方把**真**树的路径交过来);R388r **抄了机制,没抄修法**。
  ★★ **修好探针之后,9 个变异里有 5 个让门禁**保持绿** —— 构建失败**掩盖**了断言的真实状态。**
  绿的那 5 个:Scrollback 不传 undo 回调 / 撤销不再开回溯菜单 / 审查不再切换清单 /
  卡片自己留一份标签 / Scrollback 自己留一份分组规则 —— **全都是同一个形状:
  一条「字符串在文件里」的**存在性**检查,而那个字符串**同时满足别的检查**
  (比如 grep `openPanel", id: "checkpoints"` **也被 `Esc Esc` 的 handler 满足**)。
  ★ **一个 LOOKUP 能满足的检查,根本不是检查**(Lesson 1217 又一次落地)。
  修法:**断言值**,并且**调用**被测代码 —— 语义变成值(`fileCardEffect`)、
  app 的翻译被**双向**钉住、Scrollback 的接线按**接线**查(不是按 prop 名查)、
  卡片和 app 的分组**跑同一批 turn 然后对比**(为此 `groupTurns` / `fileChangeOf`
  必须**导出**,让探针**调用**它们而不是**重新实现**它们)。
  ★ **探针自己的 fixture 也是测量的一部分** —— 分组探针一开始报告「**一个文件变更都没有**」:
  fixture 用了 `path`,而 daemon 的 tool input 字段叫 **`file_path`** → `diffStatsFor`
  对每个 turn 都返回 `null`。这个失败**长得和「共享规则坏了」一模一样**。
  **Lesson 1402 — 探针报告「没有数据」时,先怀疑探针,再怀疑产品。**
  门禁 `scripts/test/r388r-file-card-actions.test.mjs` **13/13**;
  变异门禁 **11 个变异全红**,且每个都断言**红的原因是对的那条断言**
  (输出里必须点名它),并额外断言**输出里不能出现构建失败**。
  **Lesson 1397 — 死键比没有键更糟。** 用户被告知功能存在;缺键是**缺席**,
  死键是**谎言**。
  **Lesson 1398 — Ink 的 `useInput` 把按键送给每个 mounted hook。** 裸字母命令会在
  用户打字时触发;裸字母必须**限定在空 prompt**。
  **Lesson 1399 — 挂在构建错误上的门禁,是**因为错误的原因**红。**
  **Lesson 1400 — 一个 LOOKUP 能满足的检查不是检查。** 断言值,或者**调用**代码。
  **Lesson 1401 — 重新实现了被测规则的探针,量的是探针,不是产品。**
  导出那个函数,然后调用它。
  **Lesson 1403 — 一个绿色的变异门禁,在你亲眼看它**为正确的理由**变红之前,
  什么都不能证明。** 而它第一次「为错误的理由」变红时,要先问的是「它到底红了没有」
  —— 构建失败是一种**不带任何信息**的红。
  Doc: `docs/R388r-file-card-actions.md`.

- **R388s** (回溯菜单的预览 —— 选中**之前**就知道回退会做什么):
  R388n 的回溯菜单把文件清单放在面板**底部**、只显示**当前选中**的那个点,
  而且只列**文件名**。所以:预览只在用户**已经移动了选择之后**才可见 ——
  而预览正是用来**帮用户选**的;而且它说的是「这回合碰过哪些文件」,
  不是「回退这个点会**做什么**」。
  ★ **daemon 本来就有两边**:`CodeCheckpointStore` 存着每个 checkpoint 的
  **完整文件内容**(`FileState.content`,即「那时」的状态),而活文件在磁盘上。
  渲染器**只有一半**(名字),所以它**算不出来** —— 由 daemon 算,再上 wire:
  `CodeCheckpointStore.preview(Checkpoint)` + `FilePreview(name, linesNow,
  linesDelta, created, note)`;引擎加 `previewCodeCheckpoint(id)`;
  `checkpoints/list` 带上 `preview` / `changedFiles` / `changedLines` /
  `previewComplete`。
  ★ **「不知道」不是 0** —— 二进制文件、读不了的路径、现在是目录的文件,
  restore 会**跳过**它们,所以诚实的答案是「不知道」,`FilePreview.note` 说明原因。
  报 0 等于给用户一个 daemon 根本没有的**数字**,而用户可能照着它行动。
  ★ **足迹写在每一行上**(不是选中之后):预览是用来**帮选择**的,所以它不能
  住在一个「选完之后才出现」的面板里。行形态是:
  `▶ #12  refactor the checkpoint store` / `      3 files · ~12 lines`。
  足迹**单独一行** —— summary 是用户自己的话,可能很长,挤一行会截掉其中一个。
  细节面板说的是**会做什么**:`a.ts — ~12 lines differ (now 40)` /
  `b.ts — unchanged` / `logo.png — unknown (not restorable)` /
  `new.ts — would be deleted (30 lines)`。
  ★★ **没有实现 diff,这是刻意的** —— diff 需要行匹配算法;daemon 有
  (`DiffUtils`,LCS),但 TUI 是 TypeScript 引不到,移植一份就是**两个语言里的
  两份算法**,那是最会漂的形状(Lesson 1207 的形状再往外一步)。所以预览报的是
  **可算的事实:多少行不同**,而且措辞是「lines」不是「diff」。
  将来真要 diff,**必须由 daemon 拥有**并把 hunk 发上 wire,不要在渲染器里
  长出第二个 diff 引擎。
  ★ **数行的规则在两侧都被钉住** —— daemon 算数字、渲染器显示数字,
  所以「怎么算行数」必须是**同一条规则**:空串是 **0 行**,结尾换行**不**另起一行,
  所以 `"a\n"` 是 **1 行**不是 2 行;两侧都实现它,门禁**两侧都钉**
  (还钉了 daemon 的 CRLF 归一化,免得 Windows 文件和 TUI 数得不一样)。
  门禁 `scripts/test/r388s-checkpoint-preview.test.mjs` **9/9** ——
  ★ **门禁渲染真实菜单并量它的帧**:grep `linesDelta` 只证明**源码里有这个字符串**,
  不证明**终端里画出来是什么**。量的九件事:每个点都带**自己的**足迹
  (且**别的点的数字不能顶替它**)/ 没有预览的点**不显示 0** /
  什么都不会变的点用**词**说「no change」/ **不完整**的预览必须说出来
  (「no change」不能掩盖「我们不知道」)/ 细节面板说每个文件**会怎样** /
  读不了的文件说 **unknown** 绝不写「unchanged」/ session **新建**的文件说
  **会被删除** / 渲染器与 daemon **数行数的方式相同**(两侧都钉) /
  门禁**不空洞**(没有预览时足迹确实不出现)。
  变异门禁 **7 个变异**:足迹从行上消失、缺失预览变成 0、unknown 变成
  unchanged、「no change」掩盖不完整、daemon 侧丢结尾换行修正、TUI 侧丢
  结尾换行修正、created 不再报删除 —— 全部让门禁**变红**,且断言**红的原因是对的**。
  ★★ **变异门禁又踩了 R388r 那个坑** —— 沙箱没有 `node_modules`,
  而门禁把 **esbuild 的位置硬编码成了沙箱的** → 探针**构建不了**
  (`Cannot find module '...\node_modules\esbuild'`) → **每个**变异都红在
  **构建错误**上。挂在构建错误上的门禁会对**任何**改动变红,包括正确的改动 ——
  它是一根绊线,不是牙齿。修法与 R388r 相同:**构建器的位置来自环境变量**,
  由调用方把**真**树的路径交过来。
  ★ **沙箱里的同级 Java 树必须放在门禁真正会读的那个位置** ——
  门禁按 `join(ROOT, "..", "aethercode", …)` 找 daemon 的源码,而沙箱的 ROOT
  是临时目录,所以 `ROOT/..` 是**临时目录**而不是工作区:副本必须落在
  `dir/../aethercode/…`,否则断言死在「文件不存在」上(又是为错误的原因红)。
  而且那个同级目录**按沙箱命名**,不能让两个沙箱共用一个 `Temp/aethercode`
  —— 一个崩掉的沙箱的残留会变成下一个沙箱的输入。
  既有 checkpoint 门禁 `r388p` + `r388n` **37/37 绿**(菜单的渲染变了)。
  **Lesson 1404 — 沙箱没有 `node_modules`,就会把每个变异变成一次构建失败,
  而构建失败是一种**不带任何信息**的红。构建器的位置必须来自环境变量。**
  **Lesson 1405 — 只在选中之后才出现的预览,没法帮用户做选择。**
  **Lesson 1406 — 「不知道」不是 0。** daemon 读不了的数字不能编。
  **Lesson 1407 — 两个语言里的 diff 是两个真相。** 要 diff 就让 daemon 拥有它。
  **Lesson 1408 — 两个语言都实现的规则,必须**两侧都**钉住。**
  Doc: `docs/R388s-checkpoint-preview.md`.
