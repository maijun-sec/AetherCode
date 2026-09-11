# R19-B — MemoryRecall → System Prompt

**Date**: 2026-08-05
**Status**: DONE — 1180 tests (+4 AppStateMemoryTest, +3 QueryEngineMemorySectionTest), 0 net regression
**Goal**: Wire the existing `aethercode-memory/MemoryRecall` into the
per-query system prompt so the agent has the right context (project
preferences, build commands, user notes) at the start of each conversation.

---

## Why

R1-R18 built the memory subsystem (`MemoryRecall`, `MemoryScope`,
`MemoryPaths`, `FileBackedMemory`, etc.) but never wired it into the
agent's per-query system prompt. Memory files written to
`~/.aethercode/agent-memory/<model>/` or
`<cwd>/.aethercode/agent-memory/<model>/` sat on disk, untouched. The
TS original `claude-code` always recalled the relevant memory files
at the start of every query and prepended them to the system prompt.

## What changed

### `AppState.surfacedMemories()`

New `Set<Path>` field on `AppState` that tracks which memory files
have been injected into the system prompt this session. The engine
appends to this on every `MemoryRecall` call so subsequent turns in
the same session don't re-inject the same memory file.

```java
public java.util.Set<Path> surfacedMemories() { return surfacedMemories; }
public boolean markMemorySurfaced(Path p) { return surfacedMemories.add(p); }
```

Backed by `ConcurrentHashMap.newKeySet()` so the recall code can
append from any thread without contention.

### `QueryEngine.setMemorySection(String)` + `buildSystemForTurn`

`QueryEngine` gains a `volatile String memorySection` field. The
engine sets it before each `query(String)` call; the QueryEngine
snapshots it into a local for the duration of the query and resets
the field immediately (so the NEXT query on a different thread can't
see the stale section).

`effectiveSystem()` is now `buildSystemForTurn(snapshotMemory,
planModeSuffix)`: identity + environment + tooling + workflow +
**memorySection** + planModeSuffix, joined with blank lines. Memory
goes BEFORE plan mode so the most recent instruction (the plan) is
closest to the model's "now" attention.

### `AetherCodeEngine.buildMemorySection(userInput)`

New private method on the SDK entry point. For each `MemoryScope`
(USER, PROJECT, LOCAL), resolves the memory directory for the current
model id and calls `MemoryRecall.recall(...)`. Concatenates results,
deduping by absolute path (USER wins over PROJECT wins over LOCAL).
Marks every surfaced path via `AppState.markMemorySurfaced(...)`.
The output is `MemoryRecall.render(all)` which produces the
"## filename.md\n\n<content>" block.

Called once per `query(userInput)`, BEFORE the inner `QueryEngine`
call. The result is passed via `setMemorySection(...)`.

### `AetherCodeEngine.recentToolNames()`

Helper that returns the unique tool names from the last 4 messages
of the transcript, used as a recall signal so "what was the last tool
the user used" can boost matching memory files.

### SDK pom adds `aethercode-memory` dependency

`aethercode-sdk/pom.xml` now depends on `aethercode-memory`. The
SDK previously had to reach through `aethercode-llm` or other
modules to talk to memory — now it has a direct edge.

### `Main.runHeadless` prints the memory SideNote

`--print` mode now surfaces `SideNote(kind="memory", ...)` events
in dim ANSI, so the user sees "recalled N memory file(s)" alongside
the existing "[task u-xxx started]" tag.

## Real test (`--print "How do I build this project?"`)

Setup: project memory dir `.aethercode/agent-memory/MiniMax-M3/` with
`MEMORY.md`, `preferences.md` (PowerShell, junit 5, Maven multi-module),
and `build.md` (build / test / CLI commands).

```
  [task u-eajriklz started]   [recalled 2 memory file(s)]
  ...
  To build this Maven multi-module project, use these commands (PowerShell-friendly):

  **Full build (skip tests):**
  mvn -B install -DskipTests
  ...
```

The model correctly cited the memory content (PowerShell, junit 5,
Maven multi-module, the build commands) in its answer.

## Files

- `aethercode-core/.../app/AppState.java` — new `surfacedMemories` field + getter + `markMemorySurfaced`
- `aethercode-core/.../engine/QueryEngine.java` — new `memorySection` field, `setMemorySection`, `buildSystemForTurn`, snapshot-and-reset pattern
- `aethercode-sdk/.../AetherCodeEngine.java` — new `memoryRecall` field, `Builder.memoryRecall`, `buildMemorySection`, `recentToolNames`, memory SideNote in `query(...)`
- `aethercode-sdk/pom.xml` — add `aethercode-memory` dep
- `aethercode-cli/.../Main.java` — surface memory SideNote in `--print`
- New tests:
  - `aethercode-core/src/test/.../app/AppStateMemoryTest.java` (4 tests)
  - `aethercode-core/src/test/.../engine/QueryEngineMemorySectionTest.java` (3 tests)

## Tests

- 1180 tests pass, +7 new tests, 0 failures, 0 net regression, 1
  known-flaky (pre-existing R5 timing test).
- New behaviour verified by real `--print` runs with PROJECT-scope
  memory files.

## Pitfalls (R19-B)

1. **Memory section is per-query, not per-turn** — Claude Code
   recalls at the start of every user query and uses the same
   recall for all turns within that query. We match that: set
   once at `query()` entry, reset before the next call. If you set
   it per-turn, you'd re-inject the same memory on every tool
   result feedback loop.
2. **Snapshot before reset** — `query()` captures the memory
   section into a local and resets the field immediately. Without
   this, a subsequent query on a different thread might see the
   stale section (the volatile field write isn't enough because
   QueryEngine is single-threaded per query but the SDK is
   multi-threaded across queries).
3. **USER > PROJECT > LOCAL dedup** — when the same memory file
   exists in multiple scopes, USER wins because it's iterated
   first and we dedupe by `path.toAbsolutePath()`. Don't sort by
   scope; iterate in priority order.
4. **Recent tool names are limited to last 4 messages** — the
   recall scorer boosts memory files whose manifest mentions
   a recently-used tool. Without a cap, a long session's transcript
   would dilute the signal.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19b\`
(planned)

## Next

R19-C: Plan Mode improvements + plan → TODO integration. Better
plan display, auto-approve simple plans, plan steps become the
agent's TODO list.
