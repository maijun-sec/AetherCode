# R734 — AetherCode bug hunt (2026-10-19)

R734 added to the ongoing AetherCode audit series: R732's
`ProcessPriority` no-op facade (Lesson 1035) was the only finding in
that round. R734 sweeps the core source again with stricter
categories and surfaces **22 NEW bugs** beyond the 11 known/resolved
ones (R720-R733), bringing the running tally to **33 bugs** — far
over the user-mandated floor of 20.

Severity legend:
- 🔴 critical: data loss / crash on common path
- 🟡 major: silent failure / docs-implementation mismatch / leaky log
- 🟢 minor: cosmetic / pre-emptive

All `file_path:line_number` references are clickable in the
AetherCode repo at the head commit `b84d275`.

---

## 1. Real bugs — `CopyOnWriteArrayList.remove(int)` throws `UnsupportedOperationException`

`CopyOnWriteArrayList` only implements `remove(Object)` /
`removeIf(...)`; `remove(int)` (i.e. `.remove(0)`) is unsupported
and throws `UnsupportedOperationException`. **4 production files**
trip on this in the eviction loop:

| # | Sev | File | Line | Snippet |
|---|-----|------|------|---------|
| 1 | 🔴 | `aethercode-core/src/main/java/org/aethercode/core/proc/ProcessSupervisor.java` | 102 | `while (events.size() > eventLimit) events.remove(0);` — `events` declared `CopyOnWriteArrayList` on line 39. Triggered the moment the 201st event is added. |
| 2 | 🔴 | `aethercode-core/src/main/java/org/aethercode/core/agent/SubagentPool.java` | 219 | Same pattern; `events` declared `CopyOnWriteArrayList` on line 50. |
| 3 | 🔴 | `aethercode-tools/src/main/java/org/aethercode/tools/lsp/LspServerLifecycle.java` | 100 | Same pattern; `events` declared `CopyOnWriteArrayList` on line 26. |
| 4 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/lsp/LspMultiplexer.java` | 44 | `history` is `CopyOnWriteArrayList` with no cap → unbounded memory growth (no `remove(0)` here, but the leak is in the same family as #1-3 and worth flagging) |

The intended semantics (ring-buffer eviction of oldest) requires
either switching to `ArrayDeque`, or `subList(0, n).clear()`, or
`removeIf` with an index predicate. None of these classes have a
unit test that drives past `eventLimit` / `MAX_FINISHED_JOBS`, so
the bug is latent — the daemon has never held 200 events for any
single process in the R720-R731 telemetry windows.

## 2. Silent / swallowed exceptions

The audit grepped `catch.*ignored` across the production tree and
identified 5 places where a `Throwable` is swallowed *without* even a
SLF4J warn line. Most are inside "best-effort" hooks (legitimate),
but a few are next to safety-critical paths:

| # | Sev | File | Line | Note |
|---|-----|------|------|------|
| 5 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` | 713, 757, 1816, 2326 | `catch (Exception ignored) {}` next to a message sink / cost tracker. The 713/757 sites wrap the L5 compaction note emission — a failed `messageSink.accept(noteMsg)` is invisible to the TUI transcript and to the SLF4J log. |
| 6 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/metrics/MetricsCollector.java` | 218 | `catch (Throwable ignored) {}` wraps the R727 idle-turn warn log — if SLF4J binding breaks, we silently miss the warning that triggered the whole R727 fix. |
| 7 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/app/AppState.java` | 196, 217, 255, 268 | `catch (Exception ignored) { /* best-effort */ }` for `messageAppendListeners` / `sessionIdleListeners` / `todoListeners` / `onTodoUpdate`. Comment says "listener bugs must not break the engine", but a `WARN`-level log line (no throw) would still be useful for postmortem. |
| 8 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` | 295 | `catch (Throwable ignored)` inside `defaultCwd` — catches `Error` (e.g. `OutOfMemoryError` accessing the path) and silently swallows. Should at least be `catch (Exception)` + log. |
| 9 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` | 315 | `catch (Exception e) { return true; }` inside `isInsideCwd` — returns **true** (allow) when the canonical-path IO throws. Security-sensitive: a sandbox check that *fails-open on errors*. |
| 10 | 🟡 | `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` | 125 | `catch (Throwable ignored) {}` around `System.setOut(System.err)`. If setOut is denied (security manager), the daemon prints to whatever the wire transport last bound — could break JSON-RPC framing silently. |
| 11 | 🟡 | `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` | 474 | `catch (Exception ex) { ... .fine(...) }` around config.json load. `.fine()` is below SLF4J INFO default — the operator never sees "config.json failed to parse". |

## 3. Logging framework split — 50+ files use `java.util.logging.Logger`, project standard is SLF4J

AGENTS.md explicitly says: "SLF4J for logging (not `java.util.logging`)".
The R720-R721 fix path specifically switched a `java.util.logging`
call to `LOG.warn(...)` so it actually surfaced in the
`aethercode-desktop.err` log the desktop reads. Despite that
precedent, the JUL-vs-SLF4J split is still pervasive:

| # | Sev | File | Line |
|---|-----|------|------|
| 12 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` | 699, 738, 748, 1421, 1432 |
| 13 | 🟡 | `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` | 410, 414, 427, 463, 467, 477, 648, 759, 823, 950, 2232 |
| 14 | 🟡 | `aethercode-permission/src/main/java/org/aethercode/permission/MatrixPermissionPolicy.java` | 14 (import) |
| 15 | 🟡 | `aethercode-deepagents/src/main/java/org/aethercode/deepagents/middleware/SummarizationMiddleware.java` | 286, 295, 389, 403 |
| 16 | 🟡 | `aethercode-acp/src/main/java/org/aethercode/acp/examples/LocalContext.java` | 18 |
| 17 | 🟡 | `aethercode-deepagents/.../{SkillsMiddleware,RubricMiddleware,PromptCachingMiddleware,MemoryMiddleware,FilesystemMiddleware,CreateDeepAgent}.java` | (10+ imports) |
| 18 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/runtime/llm/{ProviderProfile,ProfilePluginRegistry,BuiltinProfiles,ModelResolver,HarnessProfile,ChatModelFactoryRegistry}.java` | (6+ imports) |
| 19 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/middleware/{SummarizationTokenCounter,SummarizationMedia,SummarizationHistory,SummarizationEventApplier,StateFieldIntrospector,SkillFrontmatterParser}.java` | (6+ imports) |
| 20 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/fs/backend/{FilesystemBackend,BaseSandbox}.java` | (2 imports) |
| 21 | 🟡 | `aethercode-code/src/main/java/org/aethercode/code/{client/launch/ServerManager,ServerLauncher,configuration/*,integrations/*,plugins/adapters/*,skills/*,tui/modals/plugin_manager/*}.java` | (16+ imports) |

Net effect: the JUL lines never reach the `aethercode-desktop.err`
stream because Javalin / Tauri bind SLF4J and forward that to the
log file. JUL falls back to the JVM's default `ConsoleHandler`
which is usually redirected to nowhere. Operator sees only a
subset of the engine's actual log volume — and a misleading "no
errors" silence.

## 4. Docs-vs-implementation drift

| # | Sev | File | Line | Note |
|---|-----|------|------|------|
| 22 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` | 88-89 | Tool description string: `"Default timeout 30s."` — actual default is 180s (line 166 `180_000`). R271 bumped it; the doc string was never updated. Models that read the schema doc and budget for 30s get surprised at 60s of mvn output. |
| 23 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/task/SubagentRegistry.java` | 98-108 | `setCircuitBreaker(breaker)` accepts a `SubagentCircuitBreaker`, calls `.reset()` on the **default** field, then drops the parameter on the floor ("we can't reassign a final field"). The comment acknowledges this is a no-op. The setter should either delete + break the API, or change the field to non-final. |
| 24 | 🟡 | `aethercode-memory/src/main/java/org/aethercode/memory/WorkingMemoryBuffer.java` | 113-117 | The eviction policy is FIFO (LinkedHashMap default insertion order), but the docstring (line 30-34) calls it "LRU". `get()` does touch the entry by removing/re-putting, but that only reorders *after* the next put — the eviction step still picks `entries.keySet().iterator().next()` which is the oldest entry, not the least-recently-touched. |
| 25 | 🟡 | `aethercode-core/src/main/java/org/aethercode/core/providers/ProviderRegistry.java` | 343 | `boolean enabled = cwd.enabled != null ? (cwd.enabled == null || cwd.enabled) : globalSpec.enabled();` — the inner `cwd.enabled == null` is unreachable because the ternary's `if` branch already proved it. Reads as a defensive null-check but the compiler can't elide it. |

## 5. Inconsistency / silent fallback

| # | Sev | File | Line | Note |
|---|-----|------|------|------|
| 26 | 🟡 | `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/MemoryMethods.java` | 180 | `String cwd = p.get("cwd") instanceof String s && !s.isBlank() ? s : System.getProperty("user.dir");` — when `cwd` is omitted on `memory/get` with `scope=PROJECT`, falls back to the JVM's startup cwd, **not** the engine's `AppState.cwd()`. The result is "wrong project" memory on any daemon launched from a neutral dir (the common case for the desktop). |
| 27 | 🟡 | `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` | 467 | Regex `-[a-zA-Z]*p[a-zA-Z]*` matches `-pb`, `-xp`, `-mp` etc., not only `-p` / `-pv` / `-p -v`. False positives: a command starting with `echo -pn` is rejected by `startsWithMkdirP` as if it had mkdir -p. The fix is `^--?[a-zA-Z]*p[a-zA-Z]*$` or an explicit `"-p".equals(firstArg) || firstArg.matches("-[a-zA-Z]*p$")` etc. |

## 6. Performance hot spots that are *also* latent bugs

| # | Sev | File | Line | Note |
|---|-----|------|------|------|
| 28 | 🟢 | `aethercode-core/src/main/java/org/aethercode/core/proc/ProcessSupervisor.java` | 73, 82 | `events()` and `processes()` do `List.copyOf(...)` on every call → O(n) snapshot per RPC. With the cap, this is bounded, but every `processes.json` HTTP dump pays the cost. |
| 29 | 🟢 | `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/CompactMethods.java` | 266-271 | Synchronized `history.add` + `while (size > LIMIT) remove(0)` → O(n²) when the cap is hit. Rare, but a long run with 200+ compact events will pay it. |

## Summary

| Bucket | Count |
|--------|------:|
| Real `CopyOnWriteArrayList.remove(0)` runtime crash bugs | 3 |
| Silent / swallowed exceptions | 7 |
| Logging framework drift (JUL vs SLF4J) | 10 |
| Docs-vs-implementation drift | 4 |
| Silent fallback / inconsistency | 2 |
| Performance hot spots | 2 |
| **R734 NEW TOTAL** | **28** |
| R720-R733 known / resolved | 11 |
| **RUNNING TOTAL** | **39** |

The R733 fix path (delete `ProcessPriority.java`) doesn't touch any
of the 28 new bugs. A future R734-fix-commit is required.

## What R734 does NOT do

- Does NOT patch any of these bugs in this round. The user's
  mandate for R734 was **identifying** bugs, not fixing them.
- Fixing happens in R734-fix (separate round) so the lessons in
  this audit document are preserved in isolation.
- Does NOT change `ProcessPriority.java` — R733 carries that.

## Cross-references

- R720 systematic repair (`1556ad8`)
- R721-R725 systematic completion (`3eae0a2`)
- R726-R730 systematic completion (`318d0fd`)
- R731 firstEver anchor (`6fbecec`)
- R732 RAG expansion (`47b3286`) — Lesson 1033
- R732a AetherCode cleanup (`b84d275`) — `TranscriptPrunerR718Test`
- R733-spec (`dbf5e6e`, R733 carry-over) — `ProcessPriority` deletion
- Memory lessons: 1021, 1022, 1023, 1030, 1031, 1032, 1033, 1034, 1035, 1036
