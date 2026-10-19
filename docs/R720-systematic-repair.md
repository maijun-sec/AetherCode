# R720 — systematic repair round

Date: 2026-10-19
Branch: main (post-R719)
Commits in this round: 1 (`<pending>`)
Files touched: 5 (4 prod + 1 test + 1 design + 1 SDK)

## Why this round exists

After R719, two outstanding issues were still on the table:

1. **L5 compaction never fired** — across 17 RAG-Day-2 turns
   (~663M input tokens, 6.6M tool-call transcript chars, 1.95M
   L4 messages dropped) the `l5Compacts` counter stayed at 0.
   The R719 lesson 1018 fix (default `compactAt=0.4` /
   buffer=80K) only updated `AutoCompact.shouldCompact()` and
   `StructuredCompactor8.DEFAULT_BUFFER`; the wire-side
   pre-flight gate in `QueryEngine.runPreFlightCompact()`
   continued to use the absolute `cfg.compactAt()` value
   (default 184K = 92% of 200K) — so the two trigger bands
   were ~2.3x apart and the pre-flight gate returned false
   before the compactor had a chance to fire.

2. **`memory/*` RPC was not registered** — every memory
   call returned `-32601 method not found`, even though
   `MemoryMethods.registerAll(dispatcher)` was on the
   classpath and the SDK engine had a fully-wired
   `LayeredMemoryStore`. The `DaemonRunner` never called
   it, and the HTTP+WS path's hand-rolled switch in
   `HttpJsonRpcServer` had no `memory/*` arms.

3. **`file_write` truncated large existing files silently** —
   the Q19 RAG-Day-2 incident: a model that intended to
   "append a new bullet" via `file_write` instead wiped
   130 lines of `progress.md` history. The
   `WriteExistingFileGuardHook` already blocked "unread →
   overwrite" but it did not block "unread → truncate" on
   files that *had* been read earlier in the session.

R720 closes all three, plus a follow-on bug discovered while
debugging the L5 path (the engine's `compactor` field was
null because the builder's `if (b.contextWindow > 0)` guard
skipped default-compactor construction in the boot path).

## What changed

### R720-A — L5 compaction now actually fires

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
  - `runPreFlightCompact()`: gate now uses a fraction of
    `ctxWindow` (default `0.4` = 80K tokens on a 200K
    window), matching `AutoCompact.DEFAULT_COMPACT_AT` so
    the two backends share one trigger band. Env overrides:
    `AETHERCODE_COMPACT_THRESHOLD` (legacy R283 contract,
    full fraction override), `AETHERCODE_R720_COMPACT_FRACTION`
    (R720-rationalised name), and `AETHERCODE_R720_USE_ABSOLUTE_GATE=1`
    (opt-out back to the legacy `cfg.compactAt()` absolute
    gate for provider.yaml configurations that want a
    per-model off-band `compactAt`).
  - `resolveCompactConfig()`: when `appState.contextWindow()=0`
    and the provider registry hasn't given us a config, fall
    back to `CompactConfig.forContextWindow(200_000)`
    (= `AutoCompact.DEFAULT_EFFECTIVE_CAP_TOKENS`) instead
    of returning `null`. Without this the gate short-circuited
    to a silent no-op.
- `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`
  - Builder now constructs the default
    `StructuredCompactor8 + SlidingWindowCompactor` whenever
    the caller didn't supply a custom compactor — the
    pre-R720 `if (b.contextWindow > 0 && resolvedCompactor == null)`
    guard left the field at `null` for the typical
    `--context-window` default of 0, and the resulting
    `if (compactor == null) return;` in
    `QueryEngine.runPreFlightCompact()` made the whole path
    a no-op.
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/engine/QueryEnginePreFlightCompactR720Test.java`
  - 3 tests pinning the new contract: small transcript
    skips compaction, oversize transcript (360 KB chars
    ≈ 90K tokens) fires the compactor, smoke check that
    `runPreFlightCompact` returns without throwing under
    the absolute-gate env (the env-switch behaviour is
    exercised on a real daemon run; the unit test verifies
    the unconditional entry path). **3/3 pass.**

### R720-B — `file_write` no longer truncates large files silently

- `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/file/FileWriteTool.java`
  - New optional `mode` parameter: `"overwrite"` (default
    for backward compat) or `"append"`. Append opens with
    `CREATE | APPEND` and writes without truncating.
  - Default-mode guard: when the caller omits `mode` AND
    the target already exists with size > 5 KB
    (`LARGE_FILE_THRESHOLD_BYTES`), the tool refuses with
    a structured recovery message (mentions `mode="append"`,
    `mode="overwrite"`, and `file_edit`).
  - `isDestructive` updated: `mode="append"` is
    non-destructive so the permission-prompt layer can
    auto-approve append-only calls.
- `aethercode/aethercode-tools/src/test/java/org/aethercode/tools/file/FileWriteToolTest.java`
  - 4 new tests covering the four R720-B1 cases:
    append preserves content, default-mode on a small
    fresh file behaves like legacy, default-mode on a
    large existing file is refused with the recovery
    hint, explicit `mode="overwrite"` bypasses the
    guard. **6 of 7 pass** (1 pre-existing sandbox test
    is skipped when `AETHERCODE_ALLOW_ANY_PATH` is set;
    not related to R720).

### R720-C — `memory/*` RPC now works

- `aethercode/aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java`
  - stdio path: register `MemoryMethods(memoryStore)`
    on `server.dispatcher()` after the `LayeredMemoryStore`
    is built.
  - HTTP path: pass the same `MemoryMethods` instance to
    `http.setMemoryMethods(...)` so the hand-rolled switch
    in `HttpJsonRpcServer.dispatch()` can route `memory/*`
    calls to the wired store.
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`
  - New `volatile MemoryMethods memoryMethods` field +
    `setMemoryMethods(MemoryMethods)` / `memoryMethods()`
    accessors. Nine new `case "memory/..."` arms in
    `dispatch()` (get, list, appendProjectChange,
    appendSessionFact, appendSessionChange, setProjectInfo,
    readProjectMemory, compact, switchProject) routed
    through a `routeMemory(...)` helper that returns the
    structured `{ok:false, reason:"memory not configured"}`
    envelope when no store is wired (matches the stdio
    daemon's contract).

## E2E verification (R720 daemon, port 22888)

```
$ curl -X POST .../rpc -d '{"method":"memory/list","params":{"scope":"project"}}'
{"ok":true,"scope":"PROJECT","entries":[...],"totalTokens":33}

$ curl -X POST .../rpc -d '{"method":"compact","params":{}}'
{"ok":true,"compactedFrom":5092,"compactedTo":12,
 "messagesCompacted":5080,"didCompact":true,
 "transcriptChars":17212}        # was 1_254_407 (99% reduction)

$ curl -X POST .../rpc -d '{"method":"getMetrics","params":{}}'
{"l5Compacts":1,"lastCompactionTokens":3532,
 "compactFailures":0,
 "l4Strips":0,"l4Dropped":0,
 "turnsStarted":1,"turnsCompleted":1}
```

Daemon log carries the R720 markers:
- `R720: memory/* RPCs registered on http path (9 methods)`
- `R720-A3: no caller-supplied compactor and contextWindow=0 — building default StructuredCompactor8+SlidingWindow`
- `R720-A2: appState.contextWindow()=0 — falling back to AutoCompact.DEFAULT_EFFECTIVE_CAP_TOKENS=200_000`
- `R720-trace: AetherCodeEngine.runPreFlightCompact enter: ...`
- `R720-trace: runPreFlightCompact enter: compactor=SlidingWindowCompactor, appState.contextWindow=0, transcriptSize=5092`

## Test counts

- `QueryEnginePreFlightCompactR720Test`: **3/3** pass
- `FileWriteToolTest`: **6/7** (1 pre-existing sandbox skip)
- Pre-existing tests in the same modules: unchanged
  (verified by mvn -pl aethercode-core,aethercode-tools test)

## Lessons

See Mavis memory entries 1021-1023 (appended alongside this
commit).
