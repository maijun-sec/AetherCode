# R280 — project-tier memory (PROJECT_MEMORY.md) + auto-session-change log

## Trigger

> "我在 `abc_1` 里面,执行了多个任务之后,并没有 项目级 的 memory,我希望 项目级的 agent 包含的内容:
> 1) 项目的基本信息(比如本身具备的一些能力);
> 2) 历次的 session 对本项目修改的内容,记录 <session id> <time> <完成功能>
> 然后如果历次的 session 修改超过了一定阈值,比如,超过了 20次,就将 project memory 送给 llm 压缩,然后保留最近的 5次 session 修改。
> 最后,在 session 执行时,project memory 需要考虑按需放到最前面(可以考虑刨除本 session 的内容,本 session 的内容在 session 对话中已经有了)"

The user runs Mavis inside `D:\tmp\abc_1\` — a Java/Maven playground.
After R266i → R279 the agent had session-level memory and 3-tier
recall, but no project-level file that persisted across sessions.
The project-info block ("what the project is + what the agent can do
for it") was missing. The session-change log ("what each session did")
was missing. Both had to be brought back as a single Markdown file
that the engine reads into the system prompt at the top.

## Design

One plain-text Markdown file: `PROJECT_MEMORY.md`, sibling of the
existing `MEMORY.md` (which is JSON, owned by `FileBackedMemory`).
Two sections bounded by HTML-comment markers so rewriting one
section never disturbs the other:

```
<!-- PROJECT-INFO:START -->
# (hand-curated description of project + agent capabilities)
<!-- PROJECT-INFO:END -->

<!-- SESSION-CHANGES:START -->
[<sessionId> <iso8601>] <description>
[<sessionId> <iso8601>] <description>
...
<!-- SESSION-CHANGES:END -->
```

* **Project-info block**: hand-curated, persistent, never auto-evicted.
  Written by `memory/setProjectInfo` RPC.
* **Session-changes block**: auto-appended by `MemoryLifecycle.onQueryEnd`
  on every successful query (heuristic — last assistant turn →
  one-line summary, max 140 chars). When the count exceeds
  `projectCompressThreshold = 20`, the oldest
  `(count - keepRecent) = (count - 5)` entries are summarised by an
  LLM into one paragraph each, kept as a single line with a
  synthetic `summary` sessionId so the same parser round-trips.

On every query, the engine injects PROJECT_MEMORY.md **at the top**
of the system prompt (before the existing memory-recall / experience
/ session-k/v sections), with all entries from the **current
session** filtered out — the session already has its own transcript.

## Production defaults

`DaemonRunner.buildMemoryStore` now passes `20, 5, true` (was `50, 10, true`).
The user-facing brief is verbatim: threshold 20 → LLM compression →
keep 5.

## RPC surface (R280 additions to MemoryMethods)

* `memory/appendSessionChange({cwd, sessionId, description})`
  — append one entry; returns `countBefore / countAfter /
  compressedTriggered`.
* `memory/setProjectInfo({cwd, info})`
  — write/replace the project-info block (idempotent).
* `memory/readProjectMemory({cwd, excludeSessionId?})`
  — read PROJECT_MEMORY.md as a string. Pass `excludeSessionId`
  to drop this session's own entries (mirrors what the engine does
  in `buildProjectMemorySection`).

## Storage class

New `org.aethercode.memory.ProjectMemoryStore`:
* Plain-text append/read on `<cwd>/.aethercode/agent-memory/<agentType>/PROJECT_MEMORY.md`.
* Self-contained compressor: reads the SESSION-CHANGES block, runs
  `ProjectMemoryCompressor.chat().complete(prompt)` on the oldest
  `(count - keepRecent)` entries, replaces them with one summary
  line, keeps the recent window verbatim.
* Per-file `ReentrantLock` for write serialisation (matches the
  existing `ProjectMemoryCompressor` style).
* `LayeredMemoryStore` exposes facades: `writeProjectInfo`,
  `readProjectMemory`, `readProjectMemoryExcluding`,
  `appendSessionChange`, `countProjectChanges`.

## Engine injection

`AetherCodeEngine.query()` previously did:
```
top    = memorySection[]
middle = combineThreeSections(memorySection, experienceSection, sessionKvSection)
```

Now:
```
top    = projectMemorySection                          // NEW
middle = combineThreeSections(memorySection, experienceSection, sessionKvSection)
```

`buildProjectMemorySection()`:
* `null` lifecycle / store / cwd → empty (graceful).
* Reads `PROJECT_MEMORY.md` minus the current session's own entries.
* Prefixes a one-line header explaining the filter so the model
  understands what it's reading.
* Empty file → empty section → no overhead.

## Auto-append hook

`MemoryLifecycle.onQueryEnd(success=true, transcript, sink)`:
* When `success == true` AND `currentProjectCwd != null` AND the
  transcript is non-empty AND lifecycle is enabled:
  derive one-line summary via `R280DeriveSessionSummary` and
  `store.appendSessionChange(currentProjectCwd, sessionId, summary)`.
* Best-effort: failures are logged at WARN, never thrown — the
  agent loop must not break because the memory layer hiccupped.

`R280DeriveSessionSummary` heuristic:
1. Last assistant turn's text content (whitespace collapsed,
   truncated to 140 chars + `…`).
2. Fall back to first user turn's text content.
3. Fall back to `"session completed (N messages, M tool calls)"`.

## Tests (26 new, +0 regression)

* `ProjectMemoryStoreR280Test` (9 tests): bootstrap skeleton,
  project-info round-trip / replace, append format on disk,
  blank-description skip, exclude-session filter, compression
  fires above threshold and keeps `keepRecent` verbatim,
  no-op compression under threshold, full-read shape.
* `LayeredMemoryStoreR280Test` (6 tests): facade round-trips
  (`writeProjectInfo` / `readProjectMemory` /
  `appendSessionChange` / `readProjectMemoryExcluding`), blank-skip,
  `invalidateProject` round-trip, null-cwd safety.
* `R280DeriveSessionSummaryTest` (7 tests): last-assistant-wins,
  whitespace collapse, MAX_LEN truncation + ellipsis,
  first-user fallback, empty-transcript fallback,
  null/empty input handling, blank-tool-use blocks skipped.
* `MemoryLifecycleR280Test` (4 tests): success → append on cwd,
  failure → no append, no-cwd → no append,
  disabled-lifecycle → no append.

Existing `CrossCuttingMethodsT500Test` updated to include the 3 new
RPC method names in the registration / null-store structural
checks.

`mvn test -pl aethercode-memory,aethercode-protocol`: 281/281 pass
(unchanged) + 26/26 R280 tests. 0 regressions.

## Build / deploy (R279 lesson applied)

The R279 lesson (maven-shade-plugin incremental-cache) is still
real, so:
* `mvn clean package -DskipTests -pl aethercode-protocol,aethercode-memory,aethercode-cli,aethercode-sdk -am`
* `scripts/verify_jar_r277_fix.py` extended to 11 checks (3 R277 +
  8 R280 markers; the R280 markers are the bytecode fingerprints
  of the new public surfaces — see `verify_jar_r277_fix.py` for
  the full list).

R280 jar SHA256 = `233D1638444E40BC9A6D5265F32988D24A9AA38454E1DDCA0B5AD71972A3F669`
(56,599,840 B; +13,152 B vs R279's `BDA4DAA1…` 56,586,688 B — the
delta reflects the new ProjectMemoryStore + R280DeriveSessionSummary
+ 3 MemoryMethods handlers + LayeredMemoryStore helpers +
AetherCodeEngine buildProjectMemorySection).

Desktop exe unchanged from R278 (`77CCE4A3…`, 5,179,904 B). TUI
bundle unchanged from R279 (no TS changes in this round).

R280 zip SHA256 = `60E6ECB92E959D05150D2311850CF2A832F8C2ECA70C06FF6D50B5788A0F3926`
(108,441,908 B; +26,670 B vs R279's `FB876F10…` 108,415,238 B —
the delta is the +13,152 B jar × 2 [top-level + desktop/] minus
the previous top-level jar's stale 56,586,688 B → 56,599,840 B).

## Files

New (3 main + 4 tests + 1 script):
* `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/ProjectMemoryStore.java` (20 KB; the new plain-text store)
* `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/R280DeriveSessionSummary.java` (3.4 KB; heuristic)
* `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/ProjectMemoryStoreR280Test.java` (9 tests)
* `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreR280Test.java` (6 tests)
* `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/R280DeriveSessionSummaryTest.java` (7 tests)
* `aethercode/aethercode-memory/src/test/java/org/aethercode/memory/MemoryLifecycleR280Test.java` (4 tests)
* `scripts/zip-r280.py` (zip pack script, R279 pattern)

Modified (8):
* `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/LayeredMemoryStore.java`
  — facade methods + per-cwd ProjectMemoryStore cache.
* `aethercode/aethercode-memory/src/main/java/org/aethercode/memory/MemoryLifecycle.java`
  — auto-append on `onQueryEnd`; expose `store()` accessor for the
  engine.
* `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/MemoryMethods.java`
  — 3 new RPC handlers + registration + tag-table entries.
* `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  — `m.put(...)` tag entries for the 3 new RPC names.
* `aethercode/aethercode-protocol/src/test/java/org/aethercode/protocol/methods/CrossCuttingMethodsT500Test.java`
  — registration / null-store structural checks updated.
* `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`
  — `buildProjectMemorySection` + prepend to memorySection; bind
  `currentProjectCwd` on the lifecycle at query-start.
* `aethercode/aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java`
  — production default `20, 5, true` (was `50, 10, true`).
* `scripts/verify_jar_r277_fix.py`
  — extended to 11 bytecode checks (3 R277 + 8 R280).

## Commit chain

```
cc2a8b4 R278: split preamble timeline by queryId
   ↓
194f012 R279: maven-shade-plugin incremental-cache bug fix + verify_jar script
   ↓
TBD    R280: project-tier memory (PROJECT_MEMORY.md + auto session-change log)
```

## Lessons (R280 specific)

* **Heuristic summary is good enough for a change log** — full LLM
  summarisation per session is too expensive and the model already
  has the assistant's last turn in the project-memory scope. The
  heuristic just needs to be ≤140 chars and one-line.
* **Bounded blocks via HTML-comment markers** are a clean way to
  have multiple sections in one Markdown file: each block's writer
  reads the whole file, replaces the block's marked region, writes
  back. No need for JSON schema or markdown-front-matter parsers.
* **The same `ChangeLine` regex round-trips session entries AND
  summary lines** if summary lines use a synthetic `summary`
  sessionId. Format `[summary <iso8601>] <text>` parses with the
  same regex, distinguishes itself via the sessionId sentinel.
* **Inject at the TOP of the system prompt**, not interleaved with
  the existing memory recall — the user's brief is explicit:
  "放到最前面". The model reads it first; if any of the other
  recall sections add irrelevant context it can still fall back.
* **Filter the current session's own entries** — the model already
  has the conversation transcript; including the change line
  duplicates context and wastes tokens. Filter at inject time, not
  on disk (the on-disk log keeps the full history).
* **The R279 maven-shade-plugin bug is still biteable**. Even with
  the verify script, R280 forced a `mvn clean package` to be safe.
  The script caught nothing wrong with R279's jar in the end — but
  the script is what made us CONFIDENT in shipping R279. Same
  applies to R280: `clean package` + `verify_jar_r277_fix.py` is
  the chain.

## Status

- jar: 56,599,840 B, SHA256 `233D1638444E40BC9A6D5265F32988D24A9AA38454E1DDCA0B5AD71972A3F669` ✓
- jar bytecode markers: 11/11 (R277+R280) verified
- desktop exe: unchanged from R278 ✓
- TUI bundle: unchanged from R279 ✓
- zip: 108,441,908 B, SHA256 `60E6ECB92E959D05150D2311850CF2A832F8C2ECA70C06FF6D50B5788A0F3926` ✓
- vitest / mvn protocol tests: 26 R280 new + 281 protocol total, 0 regressions ✓
- push: pending