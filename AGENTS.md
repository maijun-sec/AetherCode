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

## See also

- `docs/R719-session-anchor.md`
- `docs/R720-systematic-repair.md`
- `C:\Users\maijun\.minimax\agents\mavis\memory\MEMORY.md`
  (Mavis's cross-project agent memory)
- `~/.aethercode/agent-memory/default/MEMORY.md` (per-agent
  project memory that AetherCode itself maintains via
  the user-level memory subsystem)
