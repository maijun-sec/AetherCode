# R266h: server-side required-parameter validation + empty-input hard-stop

## Trigger
The user observed the v0.2.66 desktop repeatedly producing
"command is required" / "file_path is required" / "pattern is
required" errors when the LLM (MiniMax-M3 via spring-ai) emitted a
tool call with an empty `input` object. The user said the prompt
should be clearer — "我在使用 MiniMax-M3 的时候，没有遇到过没有参数的
情况，是不是哪里给的还是不够清晰". The 9h · 85 msg transcript at
`D:\tmp\abc_1\.aethercode\sessions\72ffe903-….jsonl` shows the model
self-acknowledging the bug 20+ times in its own `<think>` ("I
literally never wrote `{\"command\": \"dir\"}`") and still emitting
`{"type":"tool_use","name":"bash","input":{}}` on the next turn. The
current behaviour:

  - LLM sends `{}` →
  - engine calls `tool.call(Map.of(), ctx)` →
  - `BashTool.call` returns
    `Tool.ToolResult.error("command is required ...")` (very
    detailed) →
  - transcript gets a `tool_result` with the detailed error →
  - LLM sees the error and... sends `{}` again

The detailed error is not the problem; the model is making a
serialise mistake. Two changes are needed:

  1. validate `input` BEFORE invoking the tool — no side
     effect, no wasted timeout
  2. hard-stop after 2 (was 3) consecutive empty batches so
     a model stuck on a serialise bug does not waste 20+
     tool cards

## Investigation
- `aethercode-core/src/main/java/org/aethercode/core/tool/ToolParamValidator.java`
  is the right validation entry point — it wraps
  `SchemaValidator` and runs the per-tool `validateInput` hook.
  It is **not currently called from `StreamingToolExecutor`**.
- `BashTool` / `GlobTool` / `FileWriteTool` all declare their
  required fields via `Tools.objectSchema(props, "command")` etc.,
  so `ToolParamValidator` has the schema info it needs.
- `ProgressLoopDetector.emptyInputStreakThreshold` is currently 3.
  The v0.2.66 transcript shows the model stuck for 20+ turns, so
  the threshold is too forgiving — the user has already watched
  three hopeless tool cards by the time the detector fires.
- `QueryEngine.query()` was changed in R170 to "soft warn" instead
  of hard-stop on `loop_detected` ("the user explicitly asked for
  a user-confirmation flow"). That policy is right for
  fingerprint-style loops (legitimate iteration can look
  fingerprint-similar) but wrong for the empty-input pattern —
  a model that emits `bash {}` 2+ times in a row is provably
  not making progress.

## Fix

### 1. `StreamingToolExecutor` — early-reject
Both invocation paths (queue-based and sink-based observer) now
run `ToolParamValidator.validate(tool, finalInput)` AFTER
permission resolution and BEFORE `tool.call()`. When the
validation fails:

  - the tool's `call()` is NOT invoked (no shell side effect,
    no file write, no wasted timeout)
  - an `Event.Completed` is emitted with a precise error
    message built by the new `buildMissingParamError` helper
  - `batchAbort.set(true)` cancels sibling tools in the
    same parallel batch — a model that emits N empty calls
    in a row is not expecting the other N-1 to succeed

The error message:

  tool 'bash' was called with missing required parameters.
  missing fields:
    - command (string) — the shell command to run
  expected tool_use shape (fill the placeholders):
  ```
  {"type":"tool_use","id":"call_<unique>","name":"bash","input":{"command":"<value>"}}
  ```
  fix: re-emit the call with the required field(s) filled in. if
  you cannot determine the right value, STOP retrying and ask the
  user.

`buildMissingParamError` walks the schema's `properties` map to
print the field type next to the field name, and the new
`placeholderFor` helper picks the right shape placeholder
(`"<value>"` for strings, `0` for numbers, `[]` for arrays, `{}`
for objects) so the model sees the right JSON shape inline.

### 2. `ProgressLoopDetector` — default threshold 3 → 2
The default `emptyInputStreakThreshold` is lowered from 3 to 2
(per-instance volatile + Builder field). 2 consecutive empty
batches is the right "definitely stuck" signal — by the third
retry the user has wasted two tool cards. Tests that pin the
threshold explicitly (`setEmptyInputStreakThreshold(3)`) are
unaffected.

### 3. `QueryEngine` — `empty_tool_input` carve-out
A new pre-batch branch: when `loopDetector.lastLoopKind() ==
"empty_tool_input"`, the engine hard-stops the run by emitting
a `RunEnd` with stop reason `"loop_detected: empty_tool_input
(N consecutive empty tool calls)"` and a `SideNote` that says
"the run is being ended; please start a new session or
rephrase your prompt". This is the only loop pattern that
ignores R170's "soft warn only" policy — the
fingerprint / research_mode / no_file_write_progress patterns
keep the existing soft-warn behaviour.

The hard-stop goes through the existing Spliterator
`finished = true; action.accept(RunEnd); return true;`
pattern — no API change.

## Tests
- `StreamingToolExecutorTest.missingRequiredParamsRejectedPreInvoke`
  — sends `bash {}`, asserts `call()` was NOT invoked, asserts
  the error message contains the tool name, the missing field,
  the JSON shape, and the `<value>` placeholder.
- `StreamingToolExecutorTest.validInputStillReachesTool` —
  sends `bash {"command": "dir"}`, asserts `call()` ran once
  and the result contains `"ran dir"`.
- `ProgressLoopDetectorR174Test.setEmptyInputStreakThresholdChangesThreshold`
  — updated to assert the new default is 2 (was 3), with a
  R266h comment explaining the lower threshold.
- `ProgressLoopDetectorR174Test.v0_2_19_regression_scenario_firesWithin5Turns`
  — pin the threshold at 3 inside the test so the original
  "5 turns to fire" assertion still holds; production default
  is 2 (the regression test is intentionally a stronger bar).
- `ProgressLoopDetectorR136Test.toolUse` helper — when the
  helper sees `toolUse("bash", null)` it now returns
  `Map.of("command", "echo x")` instead of `Map.of()`. R136
  tests exercise the no_file_write_progress and
  research_mode patterns (NOT empty_input) and were tripping
  the new lower threshold on the bash-only sequence. The
  fix keeps the test's actual assertion while decoupling it
  from the empty_input pattern.

## Verification
```
mvn -B -pl aethercode-core -am test
Tests run: 1156, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

## Risk
- The hard-stop carve-out is intentionally narrow: only
  `empty_tool_input` ignores R170. Other loop patterns still
  warn-then-stop, so legitimate iteration is not affected.
- The lower threshold (2 vs 3) is more aggressive but
  appropriate — a model stuck on a serialise bug burns user
  time on every extra turn.
- The error message contains literal `"<value>"` placeholders
  the model might paste into its next `input` map. The "fix:"
  line tells the model explicitly to substitute, but a
  particularly confused model could still get stuck. The
  hard-stop after 2 catches that case.
