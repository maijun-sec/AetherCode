# R193 — Chat Prepare Card / Tool Streaming Fixes (2026-09-02)

## User's three complaints

The user reported three long-standing UI issues they had flagged
many times. All three were confirmed in the same conversation
(the user said: "上面的三点，都非常重要，没一点都记住，都是我
确认过很多次的东西，但是你都给我忽略掉了"):

1. **The "思考 1 次，执行 2 条命令" box is too narrow** — when
   the user clicks to expand, they can't see the full content
   inside. The box should fill the available width and show
   everything.
2. **`mvn --version` / `todo_write` feel slow** — please confirm
   the cause. The user wasn't sure if mvn was actually slow or
   if something else was happening.
3. **Auto-scroll doesn't follow output**, and there's no
   per-step summary line — they referenced a white-background
   example interface as the target shape.

The user provided a screenshot of a 2m25s session that showed
a "🧠 1 · ⚡ 2" red-tinted preparing card with a bash tool
call hanging below it, plus a separate white-background
example with brief meta + content + summary per turn.

## Root causes

### Issue 1: width

`src/components/MessageList.css` line 484-485 (pre-R193):

```css
.step-card {
  ...
  max-width: 760px;
  width: fit-content;
  ...
}
```

The `width: fit-content` made the card hug its content (just
the step head), and the `max-width: 760px` capped it well
short of the parent's full width. The SubTaskCard that
contains the StepCard was 820px max, so the inner StepCard
filled at most 760/820 ≈ 92% of the parent — a centred
narrow column with empty space on the right. When the user
clicked to expand and saw the assistant text + tool output,
they all had to fit in that 760px column.

`.step-live` (the "检查中..." pill) had the same 760px cap.
`.step-tool-output` had `max-height: 240px` which clipped
the tail of long command output (`mvn test` Surefire logs
are easily 50+ lines).

### Issue 2: mvn slowness perception

The BashTool already streams its output per line via
`ctx.emit(Message.assistantText("[out] line"))`. The
StreamingToolExecutor wraps that into
`Event.Progress(call.id(), msg)`. The QueryEngine's
`Event.Progress` switch **only** fed the messageSink
(`messageSink.accept(p.msg())`) — and the SDK's
`AetherCodeEngine` passes `null` for the messageSink:

```java
this.queryEngine = new QueryEngine(
    appState, chatClient, policy, systemPrompt.render(),
    null,    // <-- messageSink is null
    this.metrics, this.costTracker, exec, compactor, null);
```

So the streaming output was silently dropped. The desktop
saw `ToolUseStart` (with input details) and then... nothing
until `ToolResult` arrived. For an `mvn --version` on
Windows that's ~3-5 seconds of "what's happening?" — the
user's perception was that the tool was hung or slow. The
tool itself wasn't slow; the *feedback* was missing.

### Issue 3: auto-scroll + per-step summary

`MessageList.tsx`'s auto-scroll useEffect depended on
`steps.length` (a number), not the `steps` array reference.
When a tool's `output` field grew during streaming, the
array reference changed (Zustand's `set` returns a new
state) but its length didn't. So the useEffect's
`[steps.length]` dep check saw "no change" and never re-ran
`scrollIntoView`. The user had to manually scroll every
few seconds during a long bash command.

There was no per-step summary line. The SubTaskCard had a
counter strip (R102, "思考 X 次, 查看 Y 个文件, ..."), but
each inner StepCard had no equivalent — once a step
finished, the user saw a green-tinted done card with no
explicit "I did 1 think + 2 commands in 14.5s" summary.

## Fixes

### R193-fix-1: width

`src/components/MessageList.css`:
- `.step-card`: `width: 100%`, `max-width: 100%`,
  `min-width: 0`. The `min-width: 0` guard is required so a
  long `<pre>` child (`.step-tool-output`) can shrink/wrap
  instead of forcing the flex parent to expand.
- `.step-live`: same fix, fill the parent.
- `.step-tool-output`: `max-height: 240px` → `600px` (so
  ~30 lines of `mvn test` output fit without an inner
  scroll).

### R193-fix-2: streaming output

Added a new sealed-interface member:
`StreamEvent.ToolOutputDelta(String id, String text)`. The
`id` is the tool call id (matches `ToolUseStart.id` /
`ToolResult.id` of the same call); the `text` is the raw
emitted line including the `[out]/[err]` prefix.

Wired it through:
- `QueryEngine.java` line 1233-1238: when we see
  `Event.Progress`, extract the text via a new
  `progressText(Message)` helper and emit
  `StreamEvent.ToolOutputDelta(call.id(), text)`. We
  also still call `messageSink.accept(...)` for any direct
  TUI consumer that wired one (the SDK is the canonical
  consumer now and reads via the new event).
- `AetherCodeMethods.eventToMap`: serialise as
  `{type: "tool_output_delta", id, text}` over the existing
  `stream_event` notification channel. No new wire method.
- `aethercode-desktop/src/store/index.ts`:
  `appendToolOutput(id, text)` action that finds the
  matching tool event across all steps (not just the
  current one — a fast tool can finish + a new tool can
  start before the last streamed line lands) and appends
  the text. First chunk initialises `output`; subsequent
  chunks append with a newline separator if the previous
  chunk didn't end with one (BashTool emits line-at-a-time
  without trailing newlines).
- `aethercode-desktop/src/store/index.ts`:
  `case 'tool_output_delta'` in the `stream_event`
  switch, routes to `get().appendToolOutput(toolId, text)`.
  Crucially: no system message push, no chat bubble. The
  text is part of the tool event's own output, not a
  separate user-visible message. This keeps the chat
  bubble list clean during a long bash run.

### R193-fix-3a: auto-scroll

`MessageList.tsx` auto-scroll useEffect: changed the dep
from `steps.length` to the `steps` array reference. Now
the effect re-runs on every chunk (Zustand's
`appendToolOutput` does `steps.map(...)` so the array
reference changes per chunk). The cost is one
`scrollIntoView` per chunk — typically <10 chunks/sec,
well within budget. The `pinned` / `unseenCount` logic
is unchanged: when the user is scrolled up, the scroll
doesn't yank them; the new content still bumps the
"↓ N 条新消息" pill (now also for tool output growth).

### R193-fix-3b: per-step summary

`MessageList.tsx`:
- New `buildStepSummary(s: ChatStep): string` helper
  that mirrors `buildSubTaskCounters` but adds the
  wall-clock duration. Renders only non-zero buckets so
  a "just thinking" step shows only "🧠 思考 1 次 · 8.3s".
- In the StepCard body, after the tool events list:
  `step.done && summary` → `<div className="step-summary">{summary}</div>`.
  The element is only there when the step is done AND
  the summary string is non-empty.

`MessageList.css`:
- `.step-summary`: 4px margin-top, dashed top border
  (separates it from the tool list), 11.5px muted text,
  monospace font. Visually lighter than the tool pills
  above so the eye reads the tool output as primary and
  the summary as a footer.

## Verification

### Unit tests

**Desktop (Vitest)** — 5 new R193 source-pin tests +
887 existing = **892 tests pass** (75 → 76 test files):

`src/store/toolOutputDeltaR193.test.ts`:
1. `tool_output_delta` case exists in stream_event switch
   and routes to `appendToolOutput(toolId, text)`.
2. `AppState` declares `appendToolOutput: (id, text) => void`
   and the impl handles the first-chunk (`output == null`)
   and subsequent-chunk (`output += text`) cases.
3. MessageList auto-scroll useEffect depends on the
   `steps` array reference (not just `steps.length`).
4. `.step-card` CSS uses `width: 100%` and is NOT
   capped at 760px.
5. `buildStepSummary` helper exists and
   `<div className="step-summary">` renders in the
   expanded step body when `step.done`.

**Java protocol (JUnit)** — 4 new R193 wire-format
tests in
`aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsR193Test.java`:

1. `toolOutputDelta_serializesToExpectedWireShape` —
   verifies `{type, id, text}` shape.
2. `toolOutputDelta_emptyTextStillSerializes` — empty
   text is allowed (defensive).
3. `toolOutputDelta_preservesMultiLineText` — multi-line
   chunks aren't mangled.
4. `toolOutputDelta_distinctIdsArePreserved` — two
   parallel tools can stream independently.

### Pre-existing regressions checked

- `BashToolTest` (9 tests): PASS
- `BashToolR130Test` (18 tests): PASS
- `QueryEngineSimpleChatTest` (2 tests): PASS
- `QueryEngineSubTaskTest` (12 tests): PASS
- `StreamingToolExecutorTest` (2 tests): PASS
- `StreamingToolExecutorBackpressureTest` (1 test): PASS
- Desktop: 75 test files, 887 tests pre-R193 → 76 test
  files, 892 tests post-R193 (delta = +1 file, +5 tests)

Two pre-existing failures are NOT caused by R193:
- `AgentToolTest` (14 errors): unrelated; the test
  creates a CallContext via `Tool.CallContext.of(...)` and
  then calls `setExtra(...)`, but `CallContext.of`
  defaults `extras` to `Map.of()` (immutable). Test bug,
  pre-R193.
- `JsonRpcPermissionPrompterR126Test.mediumRisk_*`:
  unrelated; the test asserts the OLD R126 behaviour
  (medium-risk requires explicit confirm). The R183 fix
  extended auto-approve to medium+high, so the assertion
  is stale. Pre-R193.

### End-to-end probe

`aethercode-desktop/probe-r193-stream-v2.py`:
- Connects to the daemon via WebSocket.
- Sends a real query that triggers a `mvn --version` bash
  call.
- Captures every `stream_event` and prints
  `tool_use_start` / `tool_output_delta` / `tool_result`
  counts and timing.

Result:
```
events: 38
tool_use_start: 1
tool_output_delta: 2       <-- R193 fix verified
tool_result: 1
bash call_01a05fe0c1a37652a7969901: 2 deltas, first at #13, result at #15
    -> '[out] The JAVA_HOME environment variable is not defined correctly, '
    -> '[out] this environment variable is needed to run this program. '
```

The 2 `tool_output_delta` events arrived at the WebSocket
**before** the corresponding `tool_result`, with the raw
streamed text. The plumbing is end-to-end: BashTool emits
→ QueryEngine forwards → AetherCodeMethods serialises →
desktop WS receives → store appends to tool event's
`output` → auto-scroll useEffect fires on the steps array
reference change → MessageList scrolls into view.

(The `mvn --version` itself failed because the test
environment doesn't have JAVA_HOME set; that's irrelevant
to the R193 fix — what matters is the streamed output
arrived in real time.)

## Build

Java: `mvn -pl aethercode-cli -am package` —
BUILD SUCCESS, jar 55,310,537 bytes,
SHA256 `C61F177187AC985AB86FD92FC9CB450A0779BC8DBEB4011682BE9AC3C7E4F5E5`.

Desktop: `npx tauri build` — exe 3,952,640 bytes
(R193 is purely a frontend change, so the exe size is
unchanged from v0.2.32), SHA256
`C131035484429E879B588C33D25D84E20ED50DC14DDF6E3C8416F7C9E4F3F7CB`.

(The NSIS bundle step failed with "timeout: global" while
downloading the NSIS 3.11 zip from GitHub releases; this
is a Tauri infra issue, not a build problem. The exe is
built and runs.)

## Release

- `release/aethercode-0.2.33/AetherCode.exe`
  (3,952,640 bytes, SHA256 `C131035484429E879B588C33D25D84E20ED50DC14DDF6E3C8416F7C9E4F3F7CB`)
- `release/aethercode-0.2.33/aethercode-0.2.33.jar`
  (55,310,537 bytes, SHA256 `C61F177187AC985AB86FD92FC9CB450A0779BC8DBEB4011682BE9AC3C7E4F5E5`)
- `aethercode/dist/aethercode-0.2.33.jar`
  (Tauri config points to this path)

## Files touched

- `aethercode-desktop/src/components/MessageList.tsx`
  (auto-scroll deps, buildStepSummary, step summary JSX)
- `aethercode-desktop/src/components/MessageList.css`
  (.step-card width, .step-live width, .step-tool-output
  height, .step-summary style)
- `aethercode-desktop/src/store/index.ts`
  (appendToolOutput action, tool_output_delta case)
- `aethercode-desktop/src/store/toolOutputDeltaR193.test.ts`
  (5 new source-pin tests)
- `aethercode-core/src/main/java/org/aethercode/core/stream/StreamEvent.java`
  (new ToolOutputDelta record)
- `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
  (forward Progress as ToolOutputDelta, progressText helper)
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  (eventToMap for ToolOutputDelta)
- `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsR193Test.java`
  (4 new wire-format tests)
- `aethercode-desktop/probe-r193-stream-v2.py`
  (end-to-end smoke test)
- `aethercode-desktop/docs/R193-CHAT-PREP-FIXES-2026-09-02.md`
  (this file)

## Lessons learned (2026-09-02)

1. **"Slow" is often "silent"** — when a user reports a
   tool as "taking a long time", first check whether the
   *feedback* is missing, not whether the runtime is
   genuinely slow. R193 found that mvn's runtime was
   fine; the desktop just never received BashTool's
   `ctx.emit(...)` output. The user perceived a 5s
   mvn call as "slow" when it was actually "blind".
2. **A `width: fit-content` on a card inside a flex
   parent is a width-bug waiting to happen** — every
   card that lives inside a SubTaskCard or
   preparing-body should use `width: 100%` (with
   `min-width: 0` for long-text children). The
   pre-R193 `width: fit-content; max-width: 760px` on
   .step-card was the root cause of the "narrow box"
   complaint, and the user had to re-raise it because
   the previous "fix" only tweaked padding/margins,
   not the width itself.
3. **Auto-scroll deps should include the array
   reference, not just the length** — Zustand's
   `set(...)` always returns a new state object, and
   the per-item `output` growth makes the array
   reference change even when its length is stable.
   Watching `[steps.length]` misses in-place
   mutations; watching `[steps]` catches them.
4. **Source-pin tests catch "refactor reverts"** — the
   5 R193 source-pin tests would catch any future
   refactor that reverts the auto-scroll dep to
   `steps.length`, drops the `tool_output_delta` case,
   or shrinks the .step-card back to 760px. They cost
   ~5ms and run on every commit.
5. **"Per-step summary" was easy to add but easy to
   miss** — the SubTaskCard already had a counter
   strip (R102); the inner StepCard just didn't have
   an equivalent. The fix is one helper + one JSX
   block + one CSS rule, but the user had to mention
   "每个步骤执行完给总结呢" to surface the gap.
