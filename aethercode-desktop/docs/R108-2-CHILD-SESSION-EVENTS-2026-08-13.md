# R108-2: 子 session 事件流到 workflow sink (嵌套进度条)

**Date:** 2026-08-13
**Status:** SHIPPED (backend + minimal frontend)
**Impact:** Workflow `skill` / `agent` steps now bubble their child-session
events to the workflow's stream; the renderer shows compact
`[child ↳ step-id] event` lines so the user sees the nested activity
without leaving the workflow progress bar.

## 背景 (Context)

R103's `WorkflowExecutor` already emits `workflow_step` SideNotes for the
workflow's own step state machine (pending → running → ok / error). When a
step is `type: skill` or `type: agent`, the executor delegates to a
`SkillInvoker` hook (R106) which spawns a child session via
`engine.queryInChildSession`. The child session's StreamEvents were
**dropped** — the executor captured only the final assistant text. The
desktop had no visibility into what the child was doing mid-step; a
multi-step workflow's progress bar would sit on "running" with no
signal of progress inside the step.

R108-2 threads the child session's events back through the workflow's
own `Consumer<StreamEvent>` so they fan out via the daemon's
`stream_event` notification path to every connected client.

## 设计 (Design)

### The new `SkillInvoker` overload

```java
@FunctionalInterface
public interface SkillInvoker {
    String invoke(String kind, String name, String prompt,
                  Consumer<StreamEvent> eventSink) throws Exception;

    default String invoke(String kind, String name, String prompt) throws Exception {
        return invoke(kind, name, prompt, ev -> { /* no-op sink */ });
    }
}
```

The 4-arg form is the canonical abstract method (it's a `@FunctionalInterface`
so exactly one abstract method is allowed). The 3-arg form is preserved
as a default that drops the events — the R103 stub path and any test
mocks that don't need the events keep their old lambda unchanged.

### The new `queryInChildSession` overload

`AetherCodeEngine.queryInChildSession` gains a 4-arg form:

```java
public String queryInChildSession(
    String parentSessionId, String kind, String name, String prompt,
    Consumer<StreamEvent> eventSink) throws IOException
```

The 3-arg form delegates with a no-op sink. The new form forwards every
`StreamEvent` the child session emits to the sink AND captures the
assistant text into the returned String. The sink is best-effort
(try/catch around `accept`) — a broken WS target can't break the child
session.

### The executor's wrapper

The workflow executor wraps each forwarded event in a
`SideNote(kind="child_session_event", message="[step-id] rest")`:

```java
String result = skillInvoker.invoke(kind, name, prompt, ev -> {
    if (sink == null) return;
    var wrapped = new SideNote(
        "child_session_event",
        "[" + step.id() + "] " + kind + " \"" + name + "\" "
            + ev.getClass().getSimpleName());
    try { sink.accept(wrapped); } catch (Exception ignored) {}
});
```

The square-bracket prefix is the desktop's hook for attributing the
event to the parent step. The executor already wraps `sink` in
try/catch (R103), so a misbehaving daemon target doesn't break the
workflow.

### The renderer's handler

The store's `stream_event` SideNote handler now has a new branch for
`kind === 'child_session_event'`. It parses the `[step-id] rest`
prefix and surfaces a compact system line:

```
[child ↳ my-step] skill "my-skill" TextDelta
```

The user sees the nested activity without leaving the workflow progress
bar. A future R-round can render the events inside the step card
itself (the proper "nested progress bar"); R108-2 ships the wire path
that makes that possible.

## 改动 (Changes)

### Backend (Java)

- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`:
  new 4-arg `queryInChildSession` overload. The 3-arg form delegates.
  Each forwarded event is wrapped in try/catch so a broken sink can't
  break the child session.
- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowExecutor.java`:
  - new `SkillInvoker.invoke(kind, name, prompt, eventSink)` abstract
    method (4-arg). 3-arg is now a default that delegates with a
    no-op sink.
  - `runSkillStep` (and the agent-step path) calls the 4-arg form,
    passing a wrapper that emits `child_session_event` SideNotes via
    the workflow's sink.
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`:
  `runWorkflow`'s SkillInvoker lambda now passes the workflow's `sink`
  through to `queryInChildSession`.

### Frontend (TypeScript)

- `aethercode-desktop/src/store/index.ts`:
  new `kind === 'child_session_event'` branch in the
  `stream_event` SideNote handler. Parses the `[step-id] rest`
  prefix and surfaces a compact system line
  (`[child ↳ step-id] rest`).

### Tests

- `aethercode-core/src/test/java/org/aethercode/core/workflow/WorkflowExecutorChildSessionTest.java`:
  new file, 3 tests:
  - `skillStep_forwardsChildEventsThroughWorkflowSink` — verifies the
    4-arg SkillInvoker is called with a non-null eventSink and the
    3 forwarded events each become a `child_session_event` SideNote
    in the workflow's captured list.
  - `threeArgOverload_stillWorksForBackwardCompat` — verifies the
    3-arg form keeps working (R103 stub path, tests).
  - `childSessionEvent_wrapsWithParentStepId` — verifies the
    `[step-id] rest` prefix is included in the wrapped message (the
    desktop's hook for attributing the event to the step card).

## 验证 (Validation)

- aethercode-core: 36/36 tests pass (3 new WorkflowExecutorChildSessionTest
  + 19 WorkflowExecutorTest + 6 MessageTest + 4 AppStateMemoryTest
  + 4 AppStateListenersTest).
- aethercode-protocol: 5/5 HttpJsonRpcServerTest pass.
- aethercode-sdk / aethercode-cli: compile clean.
- aethercode-desktop: `npm run build` clean (471.58 KB JS / 77.07 KB CSS).
- `tsc -b --noEmit`: clean.
- `build.ps1 -SkipTests`: clean (`aethercode-0.2.1.jar` 39.64 MB at
  `dist/`).

## 风险 (Risks) / 已知限制 (Known limitations)

- **System-line rendering**: the renderer shows child events as
  system-line entries, not as nested content inside the step card.
  A future R-round (R108-2+ or R108-3) can render them in-place
  using the `[step-id]` prefix to attribute.
- **No event-type filtering**: every child event becomes a
  `child_session_event` SideNote. A busy child session with many
  `text_delta` events could spam the workflow's stream. The
  executor's wrapper is the right place to filter (e.g. only forward
  `tool_use_start` / `side_note` / `run_end`, drop `text_delta`),
  but R108-2 ships the unwrapped version for transparency. A
  follow-up R-round can add a "verbose" toggle on the workflow YAML
  to opt in.
- **Default 3-arg still drops events**: implementations that
  override only the 3-arg form (the R103 stub path) silently
  swallow child events. The default `invoke(kind, name, prompt)`
  in `SkillInvoker` uses a no-op sink, so the wrapper never sees
  the events. This is intentional — the executor's `runSkillStep`
  calls the 4-arg form, so a 3-arg override would only fire if a
  caller explicitly invokes the 3-arg path. The test
  `threeArgOverload_stillWorksForBackwardCompat` pins this.

## 下一步 (Next)

R108-3: multica-protocol 兼容 (Kanban 风格). 多协议兼容,让 AetherCode 能和
multica (multica-ai/multica 22.7k stars) 的 client 互通;需要先研 multica
的协议形态。
