# R174: Empty-Input Hard-Stop + Actionable BashTool Errors

**Date:** 2026-08-31
**Status:** Released as **v0.2.20**
**Trigger:** User ran the v0.2.19 desktop on the same Maven sort project
prompt and observed a 2-hour hang. `getTranscript` showed the model emitting
`bash {}` 50+ times in a row, getting `command is required` back, and falling
into a "fix the tool call format" loop. `loop_warn_2/2` fired but never
hard-stopped.

**Goal:** Catch the empty-input loop at the engine layer (instead of waiting
for a fingerprint match that never comes), give the model an error message
it can act on, and teach it the right behaviour in the system prompt.

---

## TL;DR

| What changed                                                | Where                                                                                          | Why                                                                                  |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| **BashTool empty-command error** is now an actionable list   | `aethercode-tools/.../BashTool.java`                                                           | The model gets a list of every accepted parameter, not just "command is required".   |
| **`empty_tool_input` loop pattern** in `ProgressLoopDetector` | `aethercode-core/.../ProgressLoopDetector.java`                                                | 3 consecutive empty-input batches (past 2-turn grace) → hard stop. 50-turn storm now ends at turn 5. |
| **AetherCodeAgent default prompt** has a "stop retrying" section | `aethercode-core/.../AetherCodeAgent.java`                                                     | A confused model now has explicit "STOP, re-read the schema" instructions + a transcript example. |
| **`v0.2.20` release**                                      | `release/aethercode-0.2.20/`                                                                  | Sums A + B + C.                                                                         |

---

## Root cause analysis

`getTranscript` on the hung session (`dd86e6e5`) showed this loop:

```
[user] 在当前目录下生成一个 java maven 项目，实现至少5种排序算法...
[assistant] <think>... Plan: ... [TOOL: bash {}]
[tool_result] command is required
[assistant] [TOOL: bash {}]
[tool_result] command is required
... ×50 ...
```

The model **was** getting an error back. The error wasn't *wrong* — it was
*too short*. "command is required" tells the model what field is missing
but not what to do about it. The model interpreted the error as a parsing
problem ("the system is receiving 'timeout: null' as the command — must
be a format issue") and tried to "fix" its own tool-call format. Each
fix attempt was another `bash {}` with the same empty input.

Pre-R174 the `ProgressLoopDetector` only matched **fingerprints** (same
tool + same args) and **same errors** (same tool result text). An empty
bash call has a unique fingerprint (no args = `bash|`) and a unique
error ("command is required" vs "command refused by R130 denylist"
etc.), so neither pattern caught it. The detector eventually fired
`loop_warn_2/2` on fingerprint repetition of `bash|;` but by then the
session was already 2 hours deep.

---

## Fix A: Actionable BashTool error

**Before** (BashTool.java:97-99):

```java
if (command == null || command.isBlank()) {
    return Tool.ToolResult.error("command is required");
}
```

**After**:

```java
if (command == null || command.isBlank()) {
    return Tool.ToolResult.error(
            "command is required (string, e.g. \"ls\" or \"pwd -L\").\n" +
            "accepted parameters:\n" +
            "  - command (string, required): the shell command to run\n" +
            "  - cwd (string, optional): working directory, default = engine cwd\n" +
            "  - timeout_ms (integer, optional): max runtime in ms, default 180000\n" +
            "  - stream (boolean, optional): stream stdout chunks live, default true\n" +
            "  - background (boolean, optional): run in background, default false\n" +
            "fix: re-read the tool schema in the system prompt and re-emit the call with the `command` parameter set.\n" +
            "if you cannot determine the right command, STOP retrying and ask the user for guidance (or use a different tool).");
}
```

Three pinned test cases (`BashToolTest.r174_*`) verify:
1. The error names the missing field
2. The error lists every accepted parameter
3. The error explicitly tells the model to STOP retrying
4. A blank string (`Map.of("command", "   ")`) gets the same error
5. 100 empty calls return in < 200ms (no process is actually spawned — a
   test that the early-return path is fast)

---

## Fix B: `empty_tool_input` loop pattern

**New pattern in `ProgressLoopDetector`**:

| Field                    | Value   | Where                                                                                          |
| ------------------------ | ------- | ---------------------------------------------------------------------------------------------- |
| `emptyInputStreak`       | counter | Per-instance counter, reset on any non-empty batch.                                            |
| `emptyInputStreakThreshold` | 3 (default) | Builder / setter configurable. Default 3 = fire after 3 consecutive empty batches.            |
| Turn guard               | `turnCount > 2` | Same grace period as R138 / R136.3 (the first 2 turns are "priming" — the engine doesn't have enough context to call it a loop yet). |
| Verdict                  | `loop_detected` (hard stop, NOT tiered) | A confused model can't self-correct; a soft warning just delays the inevitable. |
| Last-loop-kind tag       | `empty_tool_input` | Surfaces in the `LoopGuardBanner` so the user sees "model stuck in empty-input loop" not "model stuck in fingerprint loop". |
| Reset paths              | `acknowledge()` (user "继续") + `notifyProgress()` (successful file_write) + any non-empty batch | All three of the existing reset paths clear the new streak alongside the others. |

**New tests in `ProgressLoopDetectorR174Test`** (10 tests, all green):
- `emptyInputStreakFiresAfterThreshold` — 3 empty → loop_detected
- `emptyInputStreakResetsOnPopulatedBatch` — populated batch in the middle zeroes the streak
- `emptyInputStreakDoesNotFireOnMixedBatch` — mixed batch is not "empty"
- `emptyInputStreakDoesNotFireOnFirstTwoTurns` — grace period
- `emptyInputStreakResetsOnAcknowledge` — user "继续" clears it
- `emptyInputStreakResetsOnNotifyProgress` — successful file_write clears it
- `setEmptyInputStreakThresholdChangesThreshold` / `...RejectsZero` / `...ClearsInFlightTier`
- `v0_2_19_regression_scenario_firesWithin5Turns` — the exact regression shape

The v0.2.19 regression test feeds a normal `file_read` on turn 1, then
4 `bash {}` calls on turns 2-5, and asserts the detector hard-stops
on turn 5 (2 priming + 3 streak). Pre-R174 this scenario ran for 50+
turns without firing.

**Side effect on R136 tests**: The existing `R136` tests use `bash null`
as a priming pattern for the `no_file_write_progress` detector. They
needed a one-line `setEmptyInputStreakThreshold(99)` to opt out of the
new pattern (otherwise the new detector pre-empts the R136 verdict at
streak 3). Pinned in the test code with a comment explaining why.

---

## Fix C: AetherCodeAgent prompt rule

The `AetherCodeAgent.defaultSystemPromptFragment()` already had a "plan
→ batch → don't stop" rule from R173. R174 adds a fourth section:

> **# When a tool returns an error you don't understand**
>
> This is the single most common failure mode. The engine's
> BashTool / glob / file_write will return an error like
> `"command is required (string, e.g. \"ls\")"`. If that
> happens, **do not retry the same call**. Instead:
>
> 1. **Re-read the tool schema** in the "Tools" section of this prompt.
> 2. **Pick a different tool** if the one you tried doesn't fit.
> 3. **Stop and ask the user** if you can't determine the right arguments.
>    The engine has a `loop_detected` hard-stop that fires after 3
>    consecutive empty-input tool calls.
>
> ```
> [your call]   bash  {}
> [tool_result] command is required ...
> [your call]   bash  {}        ← STOP. Same call, no progress.
> ```

Four new pinned tests in `AetherCodeAgentTest.r174_*`:
- `r174_defaultFragmentMentionsStopRetrying` — the rule must say "stop retrying"
- `r174_defaultFragmentNamesRealTools` — BashTool / bash must be named
- `r174_defaultFragmentIncludesConcreteExample` — must have 3+ `{}` examples
- `r174_defaultFragmentMentionsLoopDetected` — must warn about the engine's hard-stop

---

## Files changed

| File                                                                                                  | Change                                                                                                          |
| ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java`                              | Empty-command branch now returns a parameter-by-parameter hint + explicit "stop retrying" instruction.            |
| `aethercode-tools/src/test/java/org/aethercode/tools/shell/BashToolTest.java`                          | +3 R174 tests: `r174_emptyCommand_returnsActionableError`, `r174_blankCommand_returnsActionableError`, `r174_emptyCommand_doesNotSpawnProcess`. |
| `aethercode-core/src/main/java/org/aethercode/core/engine/ProgressLoopDetector.java`                   | New `empty_tool_input` pattern: `emptyInputStreak` field, `emptyInputStreakThreshold` field + Builder + setter, `emptyInputStreak()` accessor, `setEmptyInputStreakThreshold(int)`, `emptyInputStreakThreshold()` accessor, reset hooks in `acknowledge()` and `notifyProgress()`. |
| `aethercode-core/src/test/java/org/aethercode/core/engine/ProgressLoopDetectorR174Test.java` (new)   | 10 tests pinning firing shape, accessors, grace period, reset paths, regression scenario.                       |
| `aethercode-core/src/test/java/org/aethercode/core/engine/ProgressLoopDetectorR136Test.java`           | +1 line per test: `setEmptyInputStreakThreshold(99)` to opt out of the new pattern in tests that intentionally use empty bash as a `no_file_write_progress` primer. |
| `aethercode-core/src/main/java/org/aethercode/core/engine/AetherCodeAgent.java`                        | `defaultSystemPromptFragment()` gains a "When a tool returns an error you don't understand" section with a transcript example. |
| `aethercode-core/src/test/java/org/aethercode/core/engine/AetherCodeAgentTest.java`                    | +4 R174 tests pinning the new section.                                                                          |

---

## Verification

| Check                                              | Result                                                                                |
| -------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `mvn -pl aethercode-core test`                     | **1141/1141 pass** (1109 pre-existing + 22 AetherCodeAgent + 10 R174 = 1141). 0 failure. |
| `mvn -pl aethercode-sdk test`                      | 251/251 pass. 0 regression.                                                            |
| `mvn -pl aethercode-cli test`                      | 42/42 pass. 0 regression.                                                              |
| `mvn -pl aethercode-tools test`                   | 212/212 pass (excl. pre-existing AgentToolTest). 0 regression from R174.               |
| `mvn -pl aethercode-deepagents test`               | 27/27 pass. 0 regression.                                                              |
| `mvn -pl aethercode-cli package`                   | New `aethercode-cli-0.1.0-SNAPSHOT.jar` (55.3 MB) at 17:01:xx.                      |
| `npm run tauri build --no-bundle`                  | New `AetherCode.exe` (3.94 MB) at 17:03:xx.                                           |
| **Release**                                        | `release/aethercode-0.2.20/aethercode-0.2.20.jar` (55.3 MB) + `AetherCode.exe` (3.94 MB). |

---

## Why the v0.2.19 release did NOT catch this

Three independent reasons:
1. **Daemon loaded the wrong jar.** The v0.2.19 release built the jar
   correctly and put it in `release/aethercode-0.2.19/`, but the desktop's
   `spawn_daemon` looks at `aethercode/dist/aethercode-*.jar`, which
   was still v0.2.18 at the time of the user's first run. The R173
   changes (AetherCodeAgent fragment + UI compact mode + right-panel
   hide-by-default) **never reached the daemon** that ran the user's
   prompt.
2. **The loop detector's fingerprint pattern never matched.** An empty
   bash call has a unique fingerprint (`bash|`) and a unique error
   string. `fingerprintThreshold=3` needs the SAME fingerprint 3 times
   — `bash|;` 3 times fires. But the model was emitting `bash {}` with
   literally no key, so the fingerprint was `bash|` (no semicolons).
   The detector should have caught this; what it actually caught was
   `loop_warn_2/2` AFTER 50+ empty batches when the fingerprint finally
   had enough density to fire.
3. **The BashTool error message was too short.** "command is required"
   doesn't tell the model what to do next. A confused model treats
   the error as a parsing problem, not a parameter problem.

R174 addresses (2) and (3) directly. For (1), the release script now
copies the jar to `aethercode/dist/` so the desktop auto-spawn picks
up the new version. Verified: `Get-CimInstance Win32_Process` confirms
PID 2808 is running `release/aethercode-0.2.20\aethercode-0.2.20.jar`
after the kill+restart cycle.

---

## What I did NOT do (deferred / out of scope)

- **A real `Middleware` chain in `QueryEngine`.** R173 added a facade
  class; R174 doesn't change the loop. The detector's pattern is added
  to the existing `ProgressLoopDetector` (one more `if` block in
  `recordBatch`), not to a middleware chain. A future R-round can
  refactor `recordBatch` to walk a `List<LoopPattern>` like deepagents'
  Python port does.
- **SpringAI-side tool_use validation.** R174-A only updates the
  BashTool error message. A future round can add pre-flight
  validation in `SpringAiChatClient.runCall`: parse the tool's
  `inputSchema` from the registered `Tool`, check that every
  `required` field is present in the model's `tool_call.arguments`,
  and if not, replace the tool call with an injected error
  `ToolResultBlock` in the assistant message (so the model sees
  the error WITHOUT actually calling the tool). The BashTool fix
  is the easier first line of defense.
- **Prompt-level `stop retrying` enforcement.** R174-C adds the
  rule to the system prompt but doesn't enforce it. If a future
  LLM ignores the rule, R174-B catches it at the engine layer
  (hard-stop after 3 empty batches). Two layers of defense.

---

## Lessons / takeaways

1. **Always copy the release jar to `aethercode/dist/`.** The Tauri
   exe looks at `dist/aethercode-*.jar` for auto-spawn. Putting the
   jar in `release/` alone is not enough — the desktop will
   silently load an old daemon. R174's release script now does
   both. The next time someone does a release, they should verify
   the daemon is loading the new jar with
   `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select CommandLine`.
2. **Loop detectors need a "weird" pattern bucket.** Fingerprint +
   same-error don't catch every loop. A confused model with broken
   tool-call syntax emits *new* fingerprints and *new* error strings
   on every turn. The `empty_tool_input` pattern catches this by
   ignoring the specific content and just counting "input was empty
   N times in a row". The general pattern: detectors should track
   shapes, not specific tokens.
3. **Actionable error messages start with the schema, not the
   complaint.** "command is required" is correct but useless.
   "command is required. accepted parameters: command (string,
   required), cwd (string, optional), ..." is correct and useful
   — the model can read the parameters list and re-emit a
   correct call without re-reading the tool schema. The BashTool
   error now mirrors the schema in a way the model can map
   directly to the next call.
4. **Real-prompt regression testing is the only test that catches
   the actual failure mode.** Unit tests on `BashTool.call`
   verified the error string. Unit tests on `ProgressLoopDetector`
   verified the firing shape. Neither unit test would have caught
   "the daemon is loading the wrong jar". The v0.2.19 real-prompt
   run caught it in 2 hours. The R174 release script now does a
   `Get-CimInstance` check after building to confirm the daemon
   loaded the new jar.
