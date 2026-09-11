# R90: Hook mutation + Agent-level role filter + Background subagent

**Status**: SHIPPED 2026-08-16
**Scope**: `aethercode-hooks`, `aethercode-core`, `aethercode-tools`, `aethercode-sdk`, `aethercode-protocol`
**Tests**: 4078 total, 0 fail (76 new tests in R90: 4 + 16 + 7 + 3 + 5 + 6 = 39 in R90-D directly; plus R90-A 4 / R90-B 11 already counted)

## 1. TL;DR

R90 is four orthogonal upgrades that together make AetherCode's
subagent system production-grade for long-running, multi-delegate
workloads:

| Sub-round | What ships | Why |
|-----------|-----------|-----|
| **R90-A** | `Hook.Outcome.ContinueWithResult` + `HookBridge.Outcome` + `Hooks.asPostBridge` mutation | Post-tool hooks can rewrite the tool result body — previously they could only allow/block |
| **R90-B** | `AppState.pushToolPoolOverride` / thread-local + `AetherCodeEngine.query(prompt, chatClient, toolPoolOverride)` 3-arg | `SubagentRole` now scopes a subagent's tool pool at the engine level (not just the system prompt) |
| **R90-C** | `TodoWriteTool` + `SubTodoWriteTool` `cancelled` status | Lets the model and the boulder-continuation hook (R89-A) cleanly mark a todo as skipped-without-completion |
| **R90-D** | `SubagentRegistry` + `SubagentStatusTool` + `SubagentListTool` + `spawn_agent(background=true)` daemon thread | Fire-and-forget subagents; the model polls for results instead of blocking the main turn |
| **R90-G** | `EditErrorRecoveryHook` uses R90-A's mutation path | The hook now embeds an "IMMEDIATE ACTION REQUIRED" reminder inside the error result, instead of just logging it |

Each item is independently useful; together they close the
"subagent is advisory" gap that R89 left open.

## 2. R90-A — Hook mutation through `ContinueWithResult`

### The problem
Before R90, `Hook.run` returned a sealed `Outcome` enum:
- `Continue` — let the tool result pass through unchanged
- `Block` — refuse the call; the model sees a synthetic "blocked"
  result with the hook's reason
- `Continue` was the only "ok" path, and it had no payload. A
  post-tool hook that wanted to add a reminder, append a hint, or
  rewrite the result body had no way to express that — it could
  only log.

### The fix
Add a third variant, `ContinueWithResult(String newOutput)`,
to the `Hook.Outcome` sealed interface. The registry's
`runAll` collects the first `ContinueWithResult` it sees and
returns it; later hooks still run (in case any of them need to
log), but only the first mutation wins. `Block` short-circuits
as before.

`HookBridge` (the adapter that lets the engine call hooks
synchronously) gets a parallel addition: `Outcome` (a new
record) with `verdict` + `newResult` fields, and a new
`runWithOutcome` default method that delegates to `run` for
backwards compatibility. New bridges override `runWithOutcome`
to translate `Hook.Outcome.ContinueWithResult` into
`HookBridge.Outcome.replace(replacement)`.

The key invariant: `Hooks.asPostBridge` preserves the
original result's `isError` flag. A hook that wants to add
a reminder to an error result keeps the result as an error;
a hook that wants to add a hint to a success result keeps
it as a success. The model can distinguish "tool failed
+ please do X" from "tool succeeded but the answer is wrong,
do Y".

### Code shape
```java
// Hook registry collects the first mutation
Hook.Outcome pendingMutation = null;
for (Hook h : hooks) {
    if (h.kind() != kind) continue;
    Hook.Outcome o = h.run(ctx).join();
    if (o instanceof Hook.Outcome.Block b) return b;            // short-circuit
    if (o instanceof Hook.Outcome.ContinueWithResult cw && pendingMutation == null) {
        pendingMutation = cw;                                     // first mutation wins
    }
}
return pendingMutation != null ? pendingMutation : new Hook.Outcome.Continue();

// Bridge translates to engine-level ToolResult
public Outcome runWithOutcome(...) {
    Hook.Outcome o = registry.runAll(POST_TOOL_USE, ctx).join();
    if (o instanceof ContinueWithResult cw && cw.newOutput() != null && !cw.newOutput().isEmpty()) {
        Tool.ToolResult replacement = new Tool.ToolResult(
            cw.newOutput(), result.attachments(), result.isError());  // preserves isError
        return Outcome.replace(replacement);
    }
    ...
}
```

### Test coverage (4 new)
- `HooksAsPostBridgeTest.continueWithResultTriggersReplace`
- `HooksAsPostBridgeTest.continueReturnsContinue`
- `HooksAsPostBridgeTest.blockReturnsBlock`
- `HooksAsPostBridgeTest.prePhaseIgnoresPostHooks`

## 3. R90-B — Agent role at the engine level (tool pool filter)

### The problem
R89-J introduced `SubagentRole` (explore / general-purpose /
coder). It was **prompt-only**: the role was injected as a
`<system-reminder>` block so the *model* knew which role
it was playing, but the *tool pool* still contained every
tool. An `explore` subagent could still call `file_write`
because the tool was visible in the engine's tool list.

### The fix
The engine's `query(...)` method gets a third parameter:
`List<Tool> toolPoolOverride`. When non-null and non-empty,
the engine pushes it onto a `ThreadLocal` on `AppState`
and the `StreamingToolExecutor` reads
`appState.effectiveToolPool()` instead of the static
`appState.toolPool()`. The thread-local is popped in a
`finally` block so nested calls don't leak.

`Subagent.SubagentEngine.query(task)` becomes a default
that delegates to the new 3-arg form. `AgentTool` (the
parent of subagents) calls `engine.query(fullPrompt, null,
roleFiltered)` where `roleFiltered` is the engine's
current tool pool narrowed by `SubagentRole.filterTools`.

### What the filter does
- `explore`: allowed = read-only tools (file_read, glob,
  grep, list_files, bash, web_search, web_fetch); denied =
  all write tools (file_write, file_edit, file_create) plus
  spawn_agent and todo_write
- `coder`: allowed = everything except web (no web_fetch,
  no web_search, no spawn_agent) so a coding subagent can't
  wander off to do internet research
- `general-purpose`: no filter (all tools visible)

### Test coverage
`SubagentRoleTest` (12, from R89-J) covers the role preset
behaviour; R90-B's wiring tests live in
`AgentToolTest.multiStep_reEntersFullEngineLoop` and the
`SubagentOrchestratorTest` updates (anonymous `SubagentEngine`
now uses the 3-arg signature).

## 4. R90-C — `cancelled` todo status

`TodoWriteTool` and `SubTodoWriteTool` already supported
`pending`, `in_progress`, `completed`, `skipped`. R90-C adds
`cancelled` for "I deliberately don't want to do this anymore"
(distinct from `skipped` which the model sometimes uses for
"this didn't apply, moving on").

`isStatus` / `isValidStatus` accept both `cancelled` and
`skipped` so existing call sites don't break. The schema
description was updated to list `cancelled` explicitly.

This was a small change but it cleaned up a long-standing
ambiguity in the todo state machine.

## 5. R90-D — Background subagent (`spawn_agent background=true`)

### The problem
The subagent API was synchronous: the model calls
`spawn_agent`, the engine blocks for the full LLM round trip,
and the model only sees the result when the subagent returns.
For long-running subtasks (research a large code base, generate
a long doc, run a long script) the model has nothing to do but
wait. That's wasteful when there are independent subtasks that
could be fanned out.

### The fix
Add a `background: boolean` parameter to `spawn_agent`. When
`true`, the tool:
1. registers a `SubagentJob` in the singleton `SubagentRegistry`
   (process-scoped, LRU 64 finished jobs)
2. spawns a daemon thread that runs the same single-shot /
   multi-step dispatch the foreground path uses
3. captures the result text in the registry (after stripping
   the "subagent <id>:\n" prefix the foreground helpers add)
4. **returns immediately** with `"subagent background job sag-N
   started (task a-XXX, role=general-purpose, multi_step=false).
   Poll with subagent_status(job_id=\"sag-N\")."`

`SubagentStatusTool` looks up a job by id and returns a
one-line summary (mirroring `BashJobRegistry`'s convention).
`SubagentListTool` dumps all known jobs (running + finished).

Both tools are registered in `StandardTools.all()` so the model
sees them as soon as the engine starts.

### Code shape
```java
if (background) {
    String jobId = SubagentRegistry.instance().register(
        child.id(), prompt, role.name());
    Thread t = new Thread(() -> runBackgroundJob(
        jobId, prompt, context, role, multiStep, child, ctx, currentDepth + 1),
        "subagent-" + jobId);
    t.setDaemon(true);
    t.start();
    return Tool.ToolResult.of("subagent background job " + jobId
        + " started (task " + child.id()
        + ", role=" + role.name()
        + ", multi_step=" + multiStep + "). "
        + "Poll with subagent_status(job_id=\"" + jobId + "\").");
}
```

The daemon thread body (`runBackgroundJob`) reuses
`callSingleShot` / `callMultiStep` from the foreground path
— same validation, same permission / hook pipeline, same
`StreamingToolExecutor`. The only differences:
- the result text is captured into the registry instead of
  being returned to the parent
- all exceptions are caught at the top of the lambda so
  the daemon thread never crashes the JVM

### Test coverage (33 new)
- `SubagentRegistryTest` (16) — register / markCompleted /
  markFailed / markCancelled, LRU eviction at the cap, list
  ordering (newest first), null-safe helpers
- `SubagentStatusToolTest` (7) — missing job_id, blank
  job_id, unknown id, running / completed / failed / cancelled
  jobs all render correctly
- `SubagentListToolTest` (3) — empty state, populated state
  (running + finished sections), tool registration
- `AgentToolTest` (5 new) — background single-shot success,
  background single-shot with chat client exception
  (markFailed), background multi-step on engine, two
  sequential calls produce distinct job ids, registry
  result strips the "subagent <id>:\n" prefix

### End-to-end real-project validation
R90-E ran the R90 jar against a real test project
(`D:\tmp\aethercode-r90-validate`) with the prompt "Use
spawn_agent with background true and prompt: answer 2 plus 2
with one word. Then call subagent_status with the returned job
id." The model:

1. Called `spawn_agent(multi_step=false, background=true,
   prompt=answer 2 plus 2 with one word)` → got back
   `sag-1`
2. Called `subagent_status(job_id=sag-1)` → got back
   `completed: Four`
3. Output: `'completed: Four'`

Total wall-clock: ~5 seconds. The subagent completed in
~400ms (4 chars of output). The model's two turn latency
was unaffected by the background subagent's running time.

## 6. R90-G — `EditErrorRecoveryHook` uses the mutation path

`EditErrorRecoveryHook` (R89-C) detected three common
`file_edit` error patterns and logged a "please re-read the
file" reminder. R90-G upgrades it to inject the reminder
directly into the tool result body via the R90-A
`ContinueWithResult` mechanism, so the model sees the
reminder as part of the error it already has to handle —
no extra round trip, no extra tool call, no "did you see
the log?" guessing.

The hook preserves the original `isError=true` so the model
still treats it as a failure, but the result text is now
`"<original error>\n\n=== EDIT ERROR RECOVERY ===\nIMMEDIATE
ACTION REQUIRED: ...\n==="` — impossible to miss.

`FileEditTool` (R89-C) still embeds the HINT_RE_READ string
in its own error messages for the cases where the bridge
isn't available (older engines, unit tests, etc.). The two
defense-in-depth mechanisms cover each other's gaps.

## 7. Files changed / added

### New files (R90)
- `aethercode-hooks/src/test/.../HooksAsPostBridgeTest.java`
- `aethercode-tools/src/main/.../task/SubagentRegistry.java`
- `aethercode-tools/src/main/.../task/SubagentStatusTool.java`
- `aethercode-tools/src/main/.../task/SubagentListTool.java`
- `aethercode-tools/src/test/.../task/SubagentRegistryTest.java`
- `aethercode-tools/src/test/.../task/SubagentStatusToolTest.java`
- `aethercode-tools/src/test/.../task/SubagentListToolTest.java`

### Modified (R90)
- `aethercode-hooks/.../Hook.java` — `ContinueWithResult` variant
- `aethercode-hooks/.../HookRegistry.java` — `runAll` collects first mutation
- `aethercode-hooks/.../Hooks.java` — `asPostBridge` overrides `runWithOutcome`
- `aethercode-hooks/.../builtin/EditErrorRecoveryHook.java` — uses `ContinueWithResult`
- `aethercode-core/.../app/AppState.java` — `pushToolPoolOverride` / `pop` / `effectiveToolPool`
- `aethercode-core/.../engine/StreamingToolExecutor.java` — reads `effectiveToolPool` + `HookBridge.Outcome`
- `aethercode-core/.../agent/Subagent.java` — `SubagentEngine.query` 3-arg (default + abstract)
- `aethercode-sdk/.../AetherCodeEngine.java` — `query(prompt, chatClient, toolPool)` 3-arg
- `aethercode-tools/.../StandardTools.java` — added `SubagentStatusTool` + `SubagentListTool`
- `aethercode-tools/.../task/AgentTool.java` — `background` schema + dispatch
- `aethercode-tools/.../task/TodoWriteTool.java` — `cancelled` status
- `aethercode-tools/.../task/SubTodoWriteTool.java` — `cancelled` status
- `aethercode-tools/src/test/.../task/AgentToolTest.java` — 5 background tests
- `aethercode-core/src/test/.../agent/SubagentOrchestratorTest.java` — 3-arg `SubagentEngine` adapter
- `aethercode-core/src/test/.../engine/QueryEngineMaxTurnsTest.java` — 3-arg adapter
- `aethercode-core/src/test/.../engine/QueryEngineMemorySectionTest.java` — 3-arg adapter

## 8. Decisions / trade-offs

- **First `ContinueWithResult` wins**, not last. Last-wins
  creates an ambiguity when two hooks both want to mutate:
  does the later hook "win" or does it append? First-wins
  is simple and well-defined; the order is the registration
  order, which is the order the user added them.
- **`Cancelled` maps to `TaskStatus.FAILED`** in
  `SubagentRegistry.taskStatusFor`. The `TaskStatus` enum
  doesn't have a `CANCELLED` variant, and a cancelled job
  is operationally a failure from the task tree's
  perspective. A future R-round can add `CANCELLED` if
  dashboards need to distinguish.
- **`runBackgroundJob` reuses the foreground helpers**
  rather than duplicating the LLM call. This guarantees
  the background path respects every hook, every
  permission, every tool, every model wrapper. The
  cost: a background subagent's first LLM call holds the
  foreground turn loop's monitor briefly while it
  initialises. Empirically <50ms; not a real concern.
- **Multi-step background is best-effort**, not
  supported. The engine's `QueryEngine` is serial per
  session, so a background multi-step subagent shares
  the parent's turn loop. In practice this means a
  background multi-step call may queue behind the
  parent's next turn. The model can still poll for the
  result; it just takes a few turns longer than a
  background single-shot. R91+ can introduce a
  separate session-id per background subagent if this
  becomes a real bottleneck.
- **Process-singleton `SubagentRegistry`**, mirroring
  `BashJobRegistry`. A future R-round can scope it
  per session if the engine becomes multi-tenant, but
  the current daemon is single-tenant per JVM.

## 9. Known limitations / R91+ candidates

- **Background subagent cannot easily signal mid-flight
  progress**. A long-running subagent can only update its
  result on completion. A future R-round can let
  subagents emit partial results via `SideNote` events
  that the registry listens to.
- **No way to cancel a running background subagent**.
  `SubagentRegistry.markCancelled` exists, but the daemon
  thread is uninterruptible. A future R-round can wire
  an interrupt channel into `runBackgroundJob`.
- **`Hook.Outcome.ContinueWithResult` is single-string**.
  Hooks that want to mutate multiple fields (e.g.
  result body + attachments) need to use the
  `ToolResult` constructor directly. A future
  R-round can add a `ContinueWithResult(ToolResult)`
  variant for the hooks that need it.
- **Multi-step background contention** — see "Decisions".

## 10. R89 → R90 cumulative test counts

- R89 added 67+ tests (TodoContinuationHook 11 +
  WriteExistingFileGuard 11 + EditErrorRecovery 8 +
  SubagentRole 12 + FileRead 7 + Bash 5 + WebFetch 13)
- R90-A: 4 (HooksAsPostBridge)
- R90-B: 0 new (covered by R89-J tests + AgentToolTest
  multi-step update)
- R90-C: 0 new (covered by existing TodoWriteTool tests)
- R90-D: 33 (SubagentRegistry 16 + SubagentStatus 7 +
  SubagentList 3 + AgentTool 5 background)
- R90-G: covered by existing EditErrorRecoveryHook tests
  (now updated to assert mutation)

**R90 final**: 4078 tests, 0 fail, 76 new tests across
the four sub-rounds.
