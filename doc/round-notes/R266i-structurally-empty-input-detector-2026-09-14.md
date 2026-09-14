# R266i — ProgressLoopDetector.isStructurallyEmpty + QueryEngine$1 carve-out survives structurally-empty inputs

## Problem (user-reported, 2026-09-14 ~17:32)

User uploaded screenshot of AetherCode 0.2.68 desktop in `/tmp/abc_1`:
- "Streaming 126.5s" (running 2 minutes)
- 16+ red cards: `bash (missing command)`
- No SideNote / LoopGuardBanner
- 模型持续发空 input 没 hard-stop

User text: *"还是不行，工具调用还是都失败，这是非常严重的可靠性问题"*

User asked for a fresh package they could test.

## Root cause — R266h's blind spot

The 0.2.68 zip **did** contain R266h:
- `StreamingToolExecutor.class` had `buildMissingParamError` + `placeholderFor`
- `QueryEngine$1.class` had `empty_tool_input` + `lastLoopKind` + `emptyInputStreak` (the carve-out)

So the carve-out WAS deployed. Why didn't it fire?

Traced through `ProgressLoopDetector.recordBatch` line 875-884 (the
`empty_tool_input` branch):

```java
if (batch != null && !batch.isEmpty() && turnCount > 2) {
    boolean allEmpty = true;
    for (ContentBlock.ToolUseBlock b : batch) {
        if (b == null) continue;
        Map<String, Object> in = b.input();
        if (in != null && !in.isEmpty()) {     // ← THIS
            allEmpty = false;
            break;
        }
    }
```

The user's model was sending `bash {"command": ""}` or
`bash {"command": null}` — i.e. a Map with ONE entry whose value is
blank or null. `Map.isEmpty()` returns `false`. So `allEmpty` is
`false`, `emptyInputStreak` is reset to 0, the carve-out never fires,
the model runs forever.

The detector's old definition of "empty input" was "literally empty
Map `{}`". The user's actual bug is "structurally empty" — i.e. the
model didn't fill in the required parameter. The BashTool's own
`call()` returns `"command is required"` because of
`command.isBlank()`, but that's a tool-side error; the detector never
sees it because the detector only sees the input the model SENT, not
the tool result.

## Fix

Add a `isStructurallyEmpty(Map<String, Object>)` helper that treats
"all values are null, blank string, empty Map, empty Collection" as
empty. Use it in the `recordBatch` empty-input branch.

```java
static boolean isStructurallyEmpty(Map<String, Object> input) {
    if (input == null || input.isEmpty()) return true;
    for (Object v : input.values()) {
        if (v == null) continue;
        if (v instanceof String s) {
            if (!s.isBlank()) return false;
            continue;
        }
        if (v instanceof CharSequence cs) {
            if (cs.length() > 0) return false;
            continue;
        }
        if (v instanceof Map<?, ?> m) {
            if (!m.isEmpty()) return false;
            continue;
        }
        if (v instanceof java.util.Collection<?> c) {
            if (!c.isEmpty()) return false;
            continue;
        }
        if (v instanceof Object[] arr) {
            if (arr.length > 0) return false;
            continue;
        }
        // any other non-null value (Integer, Boolean, ...) = real content
        return false;
    }
    return true;
}
```

Updated branch:

```java
if (!isStructurallyEmpty(b.input())) {
    allEmpty = false;
    break;
}
```

This catches the user's exact pattern: `bash {"command": ""}` and
`bash {"command": null}` are both structurally empty.

It does NOT false-positive on legitimate `bash {"command": "ls"}`
or `bash {"command": "ls", "timeout_ms": 5000}` — those have
non-blank / non-null values and reset the streak as before.

## Trade-off: conservative vs schema-aware

A future round could plumb the tool's `inputSchema.required` list
through `recordBatch` and only count structurally-empty
REQUIRED fields. For now the structural heuristic is conservative:
"if everything in the Map is blank/null/empty, the model didn't
know what to fill in" — this catches the v0.2.19 real-prompt
regression AND the user's 2026-09-14 desktop report without false
positives on observed legitimate inputs.

Documented in `isStructurallyEmpty` Javadoc as a known
limitation, with a forward-pointer to the schema-aware variant.

## Tests added (`ProgressLoopDetectorR266iTest`, 8 tests)

| Test | Pins |
|---|---|
| `blankCommandStringFiresLoopDetected` | user's exact `{"command":""}` pattern |
| `nullCommandValueFiresLoopDetected` | `{"command":null}` |
| `blankCommandWithBlankOptionalFieldsFiresLoopDetected` | `{"command":"","cwd":"","timeout_ms":null}` |
| `shortRealCommandResetsStreak` | `ls` / `pwd` don't bump the streak |
| `integerArgumentDoesNotCountAsEmpty` | `timeout_ms: 5000` = real content |
| `emptyListValueAloneCountsAsEmpty` | `{"command":"","files":[]}` = empty |
| `listValueWithContentDoesNotCountAsEmpty` | `{"command":"ls","files":["a.txt"]}` = NOT empty |
| `isStructurallyEmptyHelperTruthTable` | direct truth-table of the helper |

## Test results

- `mvn -pl aethercode-core test -Dtest='ProgressLoopDetector*'`: 60/60 pass (10 R134 + 13 R136 + 10 R174 + 8 R266i + 19 base)
- `mvn -pl aethercode-core test`: 1166/1166 pass (2 skipped)
- `mvn -pl '!aethercode-evals,!aethercode-deepagents' test`: full project, 0 failures

## Why the user couldn't see this in 0.2.67/0.2.68

The R266h test `v0_2_19_regression_scenario_firesWithin5Turns`
*passed* because the test uses `emptyTool("bash")` which is
`Map.of()` — literally empty. The user's bug (`{"command":""}`)
*also passed* the schema validator (`command` is present) and the
BashTool returned its own error. The detector was the missing
link — and the test only exercised the `Map.of()` path.

Adding `R266iTest` with the structurally-empty pattern is what
locks this down for future regressions.

## Files touched

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/ProgressLoopDetector.java`
  - Lines 875-905: replaced `Map.isEmpty()` check with `isStructurallyEmpty(...)` call
  - Lines 1133-1196: new `isStructurallyEmpty` helper with full Javadoc
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/engine/ProgressLoopDetectorR266iTest.java`
  - New file, 8 tests, ~13 KB

## Round commit

`R266i` — pending commit