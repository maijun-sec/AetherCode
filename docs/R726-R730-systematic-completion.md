# R726-R730 — systematic completion (4 rounds, no new major fixes)

Date: 2026-10-19
Branch: main (post-R720 / R721-R725)
Commits in this round: 1 (`<pending>`)
Files touched: 7 prod + 5 test + 1 design + 1 commit-msg

## Why this round exists

After R720 (L5 / memory RPC / file_write) and R721-R725 (cancel /
build-helper / AGENTS.md / cross-session memory), the engine is
working but several latent issues were still on the table:

1. **Session anchor hint stops at "read on-disk docs"** — the
   pre-R726 anchor body said "if you cannot see [the original
   prompt], read on-disk plan/progress docs" without telling
   the model which files actually exist. The model has to
   guess. R726 fixes this by enumerating cwd + docs/ +
   .aethercode/ for known long-task doc files (plan.md /
   progress.md / design.md / R###-*.md / q##-*.json /
   AGENTS.md / etc.) and listing the discovered paths in the
   anchor body.
2. **Auto-continue no-op is invisible** — the RAG Day 2 build
   surfaced a turn-12 silent failure: a turn that ended with
   no tool calls and no assistant text, with stopReason
   "end_turn". The user had to type another message to nudge
   anything forward. R727 adds a `recordIdleTurnIfApplicable`
   gate on the protocol layer that fires a counter
   (`idleTurnsDetected`) + a SLF4J WARN log carrying the
   runId + stopReason + finalBlocks count.
3. **R280 migration was half-done** — Lesson 1027 already
   documented that pre-R728 `LayeredMemoryStore.appendProjectChange`
   wrote to the legacy `FileBackedMemory` (MEMORY.md) while
   `readProjectMemory` read from the R280 `ProjectMemoryStore`
   (PROJECT_MEMORY.md). R724 added a "## Legacy change log
   (R127, pre-R280)" merge section in `readProjectMemory` to
   recover content from the legacy side, but the underlying
   write target was still wrong. R728 closes the loop by
   routing `appendProjectChange` to `ProjectMemoryStore`; the
   R724 merge code stays in `readProjectMemory` only so any
   pre-R728 content on disk remains visible.
4. **Permission auto-approve is invisible** — the engine's
   `BYPASS_PERMISSIONS` / `ACCEPT_TASK` / `ACCEPT_EDITS`
   modes auto-allow tools without a user click, but no
   metric or log line tracked those decisions. R729 wires
   `MetricsCollector.incPermissionAutoApprove()` into the
   `StreamingToolExecutor` Allow branch (both the queue-
   based and sink-based observation paths) and adds a
   structured SLF4J INFO log line per Allow carrying the
   tool name + run id + active permission mode.

R730 ties the four together: full multi-module package via
the R722 build-helper, jar sync, daemon restart, E2E
verification, commit + push, and lessons appended to
MEMORY.md.

## What changed

### R726 — Session Anchor on-disk context hint

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
  - New package-private static helper
    `scanCwdContextDocs(Path cwd, int cap)` that scans
    cwd + `docs/` + `.aethercode/` for known long-task
    doc files. Pattern coverage: top-level names
    (`plan.md` / `progress.md` / `design.md` / `tasks.md` /
    `TODO.md` / `README.md` / `AGENTS.md`), R###-*.md and
    q##-*.{md,markdown,json,txt} regexes, plus a depth-1
    subdir scan over `docs/`, `.aethercode/`,
    `.aethercode/memory/`, `.aethercode/sessions/`,
    `.aethercode/sdd/`. Cap default 10 (env-overridable via
    `AETHERCODE_R726_CWD_DOC_CAP`).
  - `maybeEmitSessionAnchor()` now injects a
    "On-disk context files (read these to recover state):"
    section listing the discovered paths. The follow-up
    "Current intent" hint is updated to say "read one of
    the on-disk context files listed above" instead of the
    vague "read on-disk plan/progress docs".
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/engine/QueryEngineAnchorR726Test.java`
  - 12 unit tests pinning the helper: null cwd returns
    empty, missing cwd returns empty, known top-level
    names found (case-insensitive), R### and q## patterns
    matched, depth-1 subdirs scanned, cap respected, sort
    order descending, IOException swallowed, mixed real
    layout surfaces every source. **12/12 pass.**

### R727 — Idle-turn detection (auto-continue no-op)

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/metrics/MetricsCollector.java`
  - New `idleTurnsDetected` AtomicLong field + snapshot
    entry. New `incIdleTurnsDetected()` + `idleTurnsDetected()`
    accessors. New package-decision method
    `recordIdleTurnIfApplicable(String runId, boolean
    sawToolUse, String stopReason, List<ContentBlock>
    finalBlocks)` that centralises the stop-reason
    carve-outs (cancelled / loop / error /
    awaiting_user_decision are real terminations, NOT idle)
    and increments the counter on detection with a SLF4J
    WARN log carrying runId + stopReason + finalBlocks
    count.
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  - In the `query()` forEach loop, the existing
    `sawToolUse` boolean tracks whether at least one
    `ToolUseStart` fired during the run. On `RunEnd`,
    `recordIdleTurnIfApplicable(...)` is called with the
    runId, the `sawToolUse` flag, the stopReason, and the
    finalBlocks. The R721 tryAdvance cancel / R719 anchor
    paths are untouched.
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/EngineContinuationDispatcher.java`
  - Same wire-up in the auto-continuation path. The
    RAG Day 2 build's hardest symptom was an
    auto-continuation that fired but the model emitted
    nothing; the R727 detector is the postmortem trace
    that surfaces the count in the next session's
    `getMetrics` snapshot.
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/metrics/MetricsCollectorR727Test.java`
  - 13 unit tests covering: empty finalBlocks / null
    finalBlocks / whitespace-only text / non-blank text /
    sawToolUse=true / cancelled / loop / error /
    awaiting_user_decision / tool_use block present /
    mixed empty + meaningful blocks / multi-turn
    accumulation / fresh-snapshot-zero. **13/13 pass.**

### R728 — Complete R280 migration

- `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/LayeredMemoryStore.java`
  - `appendProjectChange(cwd, content)` now writes to
    `projectMemoryStore(cwd).appendSessionChange("legacy",
    content)` instead of the legacy
    `FileBackedMemory.add(...)`. Returns a synthetic
    `FileBackedMemory.MemoryItem` (UUID id, timestamped
    line content, scope="project-change", now timestamps)
    so the existing `memory/appendProjectChange` RPC
    contract is preserved.
  - `projectMemoryStore(cwd)` now propagates the
    `autoCompress` flag down to the inner
    `ProjectMemoryStore`: when `autoCompress=false`, the
    inner compressor is `null` so the
    `if (compressor != null && countChanges() >
    threshold)` gate is skipped. This preserves the
    R127 autoCompress=false contract.
- `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreR728Test.java`
  - 9 unit tests covering: write target is
    PROJECT_MEMORY.md (NOT MEMORY.md), MEMORY.md is
    unchanged after a write, MemoryItem RPC contract
    (id / content / scope / createdAt), read sees new
    entries, read merges pre-R728 legacy + new R728
    entries, null cwd / null content returns null, multi
    appends persist, countProjectChanges reflects
    appends. **9/9 pass.**
- `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreR127Test.java`
  - 4 pre-R728 tests that asserted `listProject(cwd)` size
    (=1 after one append) updated to use
    `countProjectChanges(cwd)` (=1 after one append into
    PROJECT_MEMORY.md). The cwd-isolation / cache-
    invalidation / autoCompress-disabled / autoCompress-
    enabled semantics are preserved; only the read
    surface changed.

### R729 — Permission auto-approve audit

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/metrics/MetricsCollector.java`
  - New `permissionAutoApprovals` AtomicLong field +
    `incPermissionAutoApprove()` + `permissionAutoApprovals()`
    accessors + snapshot entry.
- `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java`
  - Both observation paths (queue-based around line 555,
    sink-based around line 826) bump the new counter on
    every `PermissionResult.Allow` and emit a structured
    SLF4J INFO log carrying the tool name, call id, and
    the active `appState.permissionMode()`. The streaming
    executor never receives an Allow that came from a
    user click — user-driven allow/deny answers happen
    inside the UI layer before the tool call is
    re-dispatched. So the bump is exhaustive for "tools
    that ran without a confirmation prompt".
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/metrics/MetricsCollectorTest.java`
  - 2 new tests: fresh-snapshot-zero for both R727 +
    R729 fields, and a counter-accumulation test for
    `incPermissionAutoApprove`. All previous
    MetricsCollectorTest tests still pass.

## E2E verification (R726-R730 daemon, port 22888)

```
$ curl -X POST .../rpc -d '{"method":"getMetrics","params":{}}'
{
    "uptimeMs": 21825,
    "turnsStarted": 0,
    "turnsCompleted": 0,
    "l5Compacts": 0,
    "anchorInjections": 0,
    "idleTurnsDetected": 0,        # R727 — new in this round
    "permissionAutoApprovals": 0    # R729 — new in this round
    ...
}

$ curl -X POST .../rpc -d '{"method":"cancel","params":{"runId":"nonexistent-run","forceMs":5000}}'
{ "reason":"no such runId", "cancelled":false }    # 77ms (R721 verified)

$ curl -X POST .../rpc -d '{"method":"memory/list","params":{"scope":"project"}}'
{ "ok":true, "scope":"PROJECT", "entries":[], "totalTokens":0 }    # R720-C verified
```

Daemon log carries the R720 + R721 markers (cancel path +
memory/* RPCs + compactor construction) and the new R726 +
R727 + R729 markers will fire on real user activity.

## Test counts

- `QueryEngineAnchorR726Test`: **12/12** pass
- `MetricsCollectorR727Test`: **13/13** pass
- `LayeredMemoryStoreR728Test`: **9/9** pass
- `LayeredMemoryStoreR127Test`: **19/19** (4 updated to
  use `countProjectChanges` for the post-R728 read surface)
- `LayeredMemoryStoreR724Test`: **3/3** (cross-session
  R724 contract still pinned, no regression)
- `MetricsCollectorTest`: **10/10** (+2 R729 tests)
- `aethercode-core` total: **~350 tests, 0 failures**
- `aethercode-memory` total: **352 tests, 0 failures**
- `aethercode-protocol` total: **unchanged, 0 failures**

Full multi-module `mvn package -DskipTests` via
`aethercode-build-helper.ps1`: **BUILD SUCCESS in 67.4s on
attempt 1**. Jar sync'd to
`aethercode-desktop\src-tauri\target\release\aethercode.jar`
(56.85 MB).

## Lessons

See Mavis memory entries 1028-1031 (appended alongside this
commit). Each lesson captures the round-specific learning
that a future agent would otherwise re-discover from scratch.
