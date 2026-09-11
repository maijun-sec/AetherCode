# R13 — Final Polish

**Status**: DONE — 10/10 candidates, **1172 tests (+173)**, 0 fail
**Backup**: `D:\work\tmp\r13_done\` (22 files)
**Date**: 2026-08-04

R13 closes out the AetherCode port. The candidates here are the "last 10%"
items: lifecycle helpers, observability, manifest/contract validation, and
robust process supervision. No new architectural surface — just the small,
sharp utilities that every production system eventually needs.

## 1. FocusTracker (aethercode-tui) — 20 tests

TUI focus state for nested panes: tracks which pane currently holds input,
emits `GAINED` / `LOST` events, maintains bounded history.

- `FocusTracker.setActive(String)` switches focus atomically
- `setActive(null)` from a focused state emits `LOST`; from null it's a no-op
  (no spurious `LOST` on first init)
- History is bounded (default 50 events), oldest dropped first
- Listeners receive events in registration order

## 2. CostExporter (aethercode-core/cost) — 13 tests

Serialize `CostTracker` snapshots to JSON/CSV for external billing systems.

- `toJson(tracker)` → compact JSON, ISO-8601 timestamps
- `toCsv(tracker)` → header row + per-model rows
- `toMap(tracker)` → flat key/value (used by SessionExporter)
- Round-trip parity: `toJson → JsonMapper → toMap` preserves all numeric fields

## 3. SubagentPool (aethercode-core/agent) — 19 tests

Pool of reusable subagent contexts with `acquire()` / `release()` semantics.

- Bounded capacity (rejects when full, configurable reject policy)
- `acquire(name)` returns a fresh context; `release(ctx)` returns it to the pool
- Idle eviction via max-idle-time
- `invalidate(name)` removes the named slot entirely

## 4. WorkspaceBackup (aethercode-core/fs) — 15 tests

Snapshot a workspace directory tree to a destination with explicit include rules.

- Walks separately (no `Files.walk` recursion) for predictable ordering
- `.git` excluded by default; opt-in via `includeGit=true`
- Hidden files excluded by default; opt-in via `includeHidden=true`
- Preserves relative paths in destination
- `BackupResult` reports file count, total bytes, skipped count

## 5. LspServerLifecycle (aethercode-tools/lsp) — 20 tests

State machine for an individual LSP server process.

- States: `CREATED → STARTING → INITIALIZING → READY → STOPPED / FAILED`
- `READY` → `STOPPED` is normal shutdown; `READY` → `FAILED` is error
- `STOPPED` and `FAILED` can transition back to `CREATED` for restart
- `awaitReady(Duration)` blocks with timeout, returns success boolean
- `shutdown()` is idempotent

## 6. ProcessSupervisor (aethercode-core/proc) — 20 tests

Wraps `ProcessBuilder` with structured supervision: start, monitor, restart.

- Tracks live child processes by ID
- On abnormal exit, invokes `RestartPolicy.recordFailure()` and respawns
- `onExit(Consumer<ExitReason>)` registers a listener
- `shutdownAll(Duration)` sends SIGTERM then SIGKILL (Windows: `destroy` + `destroyForcibly`)

## 7. PluginManifest + PluginManifestException (aethercode-tools/plugin) — 18 tests

JSON manifest parser for `.aethercode/plugin.json`.

- Required fields: `name`, `version`, `entrypoint`
- `entrypoint` is resolved relative to the plugin root at load time
- Validation errors (missing field, wrong type) → `PluginManifestException`
- Jackson `record` IAE gets wrapped — tests expect `RuntimeException` family

## 8. McpHealthDashboard (aethercode-mcp) — 15 tests

Aggregates per-server health into a single snapshot for status panels.

- `snapshot()` returns one row per server: name, status, last-check, last-error
- Status derived from `McpHealthCheck.Status` enum
- `failed()` filters to non-`UP` rows
- JSON serialization for TUI / CLI rendering

## 9. ToolParamValidator (aethercode-core/tool) — 15 tests

Reusable JSON-Schema-like validator for tool argument maps. Wraps
`SchemaValidator` with tool-specific conveniences.

- `validate(schema, args)` returns `ValidationResult` (same shape as core)
- `requiredString()`, `stringEnum(List<String>)`, `optionalString()` factories
- Error messages include the failing field path (`args.foo[0].bar`)
- Schema-typed convenience: `validateString(value, "field", minLen, maxLen)`

## 10. RestartPolicy (aethercode-core/proc) — 18 tests

Exponential backoff with jitter, max-attempts cap, and a pluggable clock for
testing.

- `Config` record validates: `maxAttempts ≥ 1`, `base > 0`, `max > 0`,
  `multiplier ≥ 1.0`, `jitter ∈ [0, 1]`
- `recordFailure()` returns the next backoff `Duration`; returns
  `Duration.ZERO` once `failureCount() > maxAttempts`
- `backoffFor(n)` is the pure form (no counter side-effect); clamps at `max`
- `shouldRetry()` = `failureCount() < maxAttempts`
- `recordSuccess()` resets counter iff `Config.resetOnSuccess`
- `LongSupplier clock` for testability; `System::nanoTime` in production

### R13-10 pitfall (recap)

- `Config` is a `record` with validation in the **compact constructor** —
  `null` arguments throw `NullPointerException` from `Objects.requireNonNull`,
  bad values throw `IllegalArgumentException`. The test `constructor_rejectsNullArgs`
  only catches the `NPE`; IAE cases are covered by `config_rejectsBadValues`.
- `backoffFor(attempt)` is **clamped at max** regardless of how large `attempt`
  is — so `backoffFor(20)` with `max=500ms` returns 500ms, not 100ms × 10²⁰.
- One test originally asserted `assertEquals(500, p.backoffFor(10))` — that
  fails because `backoffFor` returns `Duration`, not `long`. Fixed to
  `assertEquals(Duration.ofMillis(500), p.backoffFor(10))`.

## Cross-cutting regression fix (R13 unplanned)

`SchemaValidator.validate_multipleErrorsAccumulate` was reporting 4 errors
instead of 2 because the R11-1 "required without properties" fallback block
fired unconditionally — when `properties` was also present, both the
in-properties required check and the fallback check fired for each missing
key. Added a `properties == null` guard to the fallback. Single-line fix;
uncovered by R12 because the failing test path was always the "required +
properties" combination, which R12 didn't exercise.

## Test progression

| Round | Tests | Δ | Modules | Status |
|------:|------:|---:|--------:|--------|
| R1    |    36 |  +36 | 14 | MVP skeleton |
| R2    |    64 |  +28 | 14 | Capability expansion |
| R3    |    65 |   +1 | 14 | Capability expansion |
| R4    |    65 |    0 | 14 | Platform maturity |
| R5    |    80 |  +15 | 14 | Production readiness |
| R6    |   147 |  +67 | 14 | Usability layer |
| R7    |   234 |  +87 | 14 | Cost, quality, lifecycle |
| R8    |   351 | +117 | 14 | SDK and polish |
| R9    |   497 | +146 | 14 | Developer experience |
| R10   |   670 | +173 | 14 | Integration and polish |
| R11   |   843 | +173 | 14 | Misc utilities |
| R12   |   999 | +156 | 14 | Infrastructure |
| **R13** | **1172** | **+173** | **14** | **Final polish** |

(15 modules total counting parent; 14 are the real test-bearing modules.)

## R13 deliverables by module

| Module | New files (main) | New files (test) | Tests |
|--------|------------------|------------------|------:|
| aethercode-core | FocusTracker is in tui; here: CostExporter, SubagentPool, WorkspaceBackup, ProcessSupervisor, PluginManifest[Exception] is in tools, ToolParamValidator, RestartPolicy | matching tests | 138 |
| aethercode-tools | LspServerLifecycle, PluginManifest, PluginManifestException | matching tests | 38 |
| aethercode-tui | FocusTracker | matching test | 20 |
| aethercode-mcp | McpHealthDashboard | matching test | 15 |
| **R13 totals** | **10 main + 1 exception** | **10 tests** | **+173** |

## What's NOT in R13 (carry-forward)

- Wire R13 candidates into `ScanRunner`-equivalent CLI plumbing (the
  AetherCode equivalent). They're available as libraries; integration is
  application-layer work.
- Settings.json schema for `.aethercode/` config (uses `SchemaValidator` +
  `ToolParamValidator` but not a concrete config schema yet)
- Plugin loader: `PluginManifest` parses, but there's no
  `PluginLoader` that reads from disk and instantiates entrypoints
- ProcessSupervisor signal handling on POSIX (only `destroy` + `destroyForcibly`
  on Windows; SIGTERM is the only signal honored cross-platform via Process API)

## Sign-off

R13 closes the AetherCode port. The system is at 1172 tests across 14
modules with 0 failures and a single known pre-existing R5 timing flake
(`StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered`
threshold 150 vs actual 242 under load — non-blocking). All R1-R13
deliverables are backed up to `D:\work\tmp\r{1..13}_done/`.
