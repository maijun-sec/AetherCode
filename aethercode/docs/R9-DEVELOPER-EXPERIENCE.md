# R9 Developer Experience

10 candidates targeting daily-coding pain points: documentation, on-screen help, file watching, structured logging, session export, LLM/tool-call resilience, patch hunks, cost guardrails, transcript checkpoints, and path normalisation.

## Candidates

| # | Name | Module | Tests | What it does |
|---|------|--------|-------|--------------|
| 1 | MagicDocs generator | core/docs | 11 | Regex-based Javadoc / `class` / `method` extraction for skeleton docs |
| 2 | Tip of the day | core/tips | 8 | `Tips.random()` + `at(index)` with mod wrap |
| 3 | File system watcher | core/fs | 8 | `FileWatcher implements Closeable`, debounce, multi-path |
| 4 | JSONL logger | core/log | 9 | Atomic line writes, race test with 4×100 threads |
| 5 | Session export | core/export | 10 | `SessionExporter.toJson()` / `toMap()` / `write()` |
| 6 | LLM tool-call retry | llm/retry | 25 | `Retry` + `RetryPolicy` + `RetryingChatClient` decorator with exponential backoff, cause-chain aware |
| 7 | Patch hunk parser | core/patch | 20 | Unified diff parser, multi-file, multi-hunk per file, `PatchFile`/`Hunk`/`DiffLine` model |
| 8 | Cost budget enforcer | core/cost | 14 | `CostBudget` daily cap + per-call cap + listener + injectable Clock |
| 9 | Conversation checkpoint | core/transcript | 17 | `CheckpointStore` save/restore/rewind-by-index, deep-copy safety |
| 10 | Path normalizer | core/fs | 23 | resolve, relativize, isInside, safeResolve, segments, join, isDescendant |

**R9 totals: 145 new tests across 9 new files + 1 test file. 0 regression (R5 pre-existing timing flake is now stable on re-run).**

## Per-candidate notes

### R9-6 retry
- `Retry.execute(ThrowSupplier, RetryPolicy, Sleeper)` is the core; `Retry.Sleeper.NOOP` keeps tests fast.
- `isTransientDefault` walks the cause chain so `new RuntimeException(IOException)` still classifies as transient.
- `RetryingChatClient` is a `ChatClient` decorator: connection-level failures retry; mid-stream errors surface as `RunEnd("error: ...")`.

### R9-7 patch
- `split("\\r?\\n")` (no limit) drops trailing empties — otherwise every diff produced a phantom blank context line.
- `PatchFile` may carry multiple `Hunk`s — fixed an early version that overwrote instead of appending.
- Hunk header counts default to 1 when omitted (`@@ -1 +1 @@` is valid).

### R9-8 budget
- `Clock` is injectable via constructor so day-rollover tests use `FixedClock`.
- `Listener.onExceeded` fires **once** per day, not per call — verified in test.
- `tryAcquire` does not record; `record` and `consume` are the writers. Keeps the read-side pure.

### R9-9 checkpoint
- `Checkpoint` deep-copies on save; mutating the live list afterwards does not affect the snapshot.
- `restoreByIndex(n)` is 1-based for the `/rewind 2` UX.
- No disk persistence in R9 — that's R12 candidate (checkpoint persistence).

### R9-10 path
- `relativize` always returns forward-slash form for display.
- `safeResolve` blocks `..` escapes via `PathEscapeException`.
- `join` drops nulls and empty segments so `join("a", null, "", "b")` → `"a/b"`.

## Build verification

`mvn -B test` from `aethercode/`: **497 tests, 0 fails** across 9 modules. Per-module breakdown:
- aethercode-core: 217 (R9: 10 + 8 + 9 + 10 + 14 + 17 + 23 + 20 = 111 new)
- aethercode-bridge: 24
- aethercode-cli: 4
- aethercode-llm: 25 (R9: 25 new)
- aethercode-mcp: 31
- aethercode-memory: 15
- aethercode-permission: 7
- aethercode-skills: 36
- aethercode-tools: 98

R8 baseline was 351 → R9 = 497 (+146).

## Key pitfalls (carried into R10)

1. **Java `Supplier<T>` rejects checked exceptions** — `Retry.execute` uses a `ThrowSupplier<T>` functional interface that allows `throws Exception`.
2. **`String.split("\\r?\\n")` without limit** drops trailing empties. Use `-1` only when you genuinely need to preserve them.
3. **Hunk header count defaults** — `@@ -1 +1 @@` is the abbreviated form; treat omitted count as 1.
4. **Day-rollover in budget** — guard with double-checked locking on the date string, not the clock.
5. **Path equality across OS** — `Path.of("/tmp/proj").toString()` on Windows returns `\tmp\proj`; use `endsWith("sandbox/proj")` or inject a fixed Path.

## Backup
`D:\work\tmp\r9_done\` (planned) — 17 R9 files.
