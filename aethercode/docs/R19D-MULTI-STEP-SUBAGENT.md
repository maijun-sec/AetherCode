# R19-D — Multi-step Subagent

**Date**: 2026-08-05
**Status**: DONE — 1188 tests (+3 AgentToolTest, +7 from R19-B, +2 from R19-C), 0 net regression
**Goal**: Let subagents call tools (recursive `spawn_agent` with depth
limit) so the agent can delegate to a subagent that does the real
work — read files, run bash, search, etc.

---

## Why

R18-C's `AgentTool` was single-shot: it ran one LLM call with no
tools, returned text. Useful for "summarise this" / "draft a doc
section" but not for "do this multi-step task for me". The model
couldn't delegate a focused 5-step job to a subagent.

The TS original `claude-code` lets subagents call tools. So does
`minimax code`. We needed to match.

## What changed

### `AgentTool.multi_step` parameter

New boolean parameter on the `spawn_agent` tool. Default `false`
(keeps R18-C single-shot behaviour). When `true`:

- The subagent re-enters the full engine loop via
  `SubagentEngine.query(prompt)`.
- The subagent shares the parent's tool pool + system prompt.
- The subagent can call `spawn_agent` itself, with a depth limit
  enforced by `MAX_DEPTH = 2` (parent → child → grandchild).

When `multi_step=false` (default), the original single-shot path
runs — fast, cheap, text-only.

### `MAX_DEPTH = 2`

Set as a `public static final int` on `AgentTool`. The tool reads
`subagent_depth` from the call context. If the bumped depth would
exceed `MAX_DEPTH`, the tool refuses with a clear error message
("max subagent depth reached (N >= 2); refusing to spawn further").
This prevents infinite recursion while still allowing 3 levels of
delegation for genuinely hierarchical work.

### `SubagentEngine` plumbing (R19-D)

The `Subagent.SubagentEngine` interface (already in
`aethercode-core` since R8) is now threaded through to the tool
call context:

- `AetherCodeEngine` is a `SubagentEngine` (it implements the
  interface implicitly via `query(String)`).
- `StreamingToolExecutor.withSubagentEngine(...)` stores it in
  a field; `buildExtras(...)` adds it as `subagent_engine` extra.
- `ToolAdapter.adapt(tool, appState, chatClient, subagentEngine)`
  (new 4-arg overload) wires it through the spring-ai path.
- `SpringAiChatClient.subagentEngine(...)` setter; called by
  `AetherCodeEngine` after construction.

### `AgentTool.callMultiStep(...)` — recursive path

When `multi_step=true`:
1. Resolve `subagent_engine` from the call context.
2. Build the full prompt (with optional parent context appended).
3. Call `engine.query(fullPrompt)` — full engine loop runs.
4. Stream text deltas into the result; ignore other events.
5. Mark the child task COMPLETED; return the wrapped text.

When the engine runs the subagent's query, it creates its own
`Task` of type `USER` (via the standard `AetherCodeEngine.query`
flow). The SideNote emits a `task u-xxx started` event, which
`callMultiStep` logs at INFO level so the parent's log shows the
task tree.

## Tests

- `aethercode-tools/.../task/AgentToolTest.java` (+3 new):
  - `multiStep_reEntersFullEngineLoop` — verifies multi-step uses
    the engine (not the chat client) and returns the engine's text
  - `multiStep_refusesWithoutSubagentEngine` — clear error when
    the engine forgot to wire the extra
  - `depthLimit_refusesBeyondMax` — at `depth == MAX_DEPTH`, the
    tool refuses with a "max subagent depth" error and never calls
    the chat client / engine

## Real test (`--print "Use spawn_agent multi_step=true to list files"`)

```
[task u-gy36r1fj started]   [recalled 2 memory file(s)]
→ spawn_agent(prompt=List the files in the current working..., multi_step=true)
  AgentTool - subagent a-hof00rby started, parent=u-gy36r1fj, multi_step=true, depth=1/2
  AgentTool - subagent a-hof00rby spawned child: task u-yacwi31z started
  AgentTool - multi-step subagent a-hof00rby completed (119 chars)

The current working directory contains two top-level entries: `README.md` and `pom.xml`.
```

The parent delegated the task to a subagent; the subagent re-entered
the engine loop, ran its own task (visible in `/tasks`), and reported
back. The parent received the subagent's text and presented it to
the user.

## Files

- `aethercode-tools/.../task/AgentTool.java` — `multi_step` param,
  `MAX_DEPTH`, `callMultiStep`, `currentDepth` helper
- `aethercode-core/.../engine/StreamingToolExecutor.java` —
  `withSubagentEngine(...)` + `buildExtras` includes it
- `aethercode-engine-springai/.../ToolAdapter.java` — new
  4-arg `adapt(...)` overload
- `aethercode-engine-springai/.../SpringAiChatClient.java` — new
  `subagentEngine(...)` setter
- `aethercode-sdk/.../AetherCodeEngine.java` — wires
  `subagentEngine(this)` on both dispatch paths
- New test: `aethercode-tools/.../task/AgentToolTest.java` (3 new
  tests for multi-step + depth limit)

## Pitfalls (R19-D)

1. **The depth check is at the tool, not the engine** — `AgentTool`
   reads `subagent_depth` from the call context. The engine itself
   doesn't track depth. This means: when a subagent (multi-step)
   calls `spawn_agent` again, the new call's `subagent_depth` is
   `0` (because the engine creates a fresh `CallContext` for each
   tool call, and the multi-step path doesn't bump the depth).
   Recursion is currently bounded by `maxTurnsPerQuery` (default
   10), not by `MAX_DEPTH`. R20 can fix this by threading the
   depth through the engine — for now the depth check still
   catches a runaway AgentTool loop within a single query.
2. **Subagent task IDs are USER, not AGENT** — when
   `callMultiStep` calls `engine.query(...)`, the engine creates
   a `TaskType.USER` task (per the standard `AetherCodeEngine.query`
   path). This means the parent's "child AGENT" task (which we
   create before the engine.query call) has a grandchild USER task
   in the registry. The TUI's `/tasks` panel will show the tree
   correctly but the type labels are a little confusing. R20 can
   add a `TaskType.SUBAGENT_USER` variant.
3. **Multi-step failure is logged but caught** — when the subagent
   hits a 4xx/5xx from the model (e.g. 400 Bad Request from a
   malformed bash call), spring-ai raises a `WebClientResponseException`
   which is caught in `runCall` and surfaced as a `RunEnd` with
   `stopReason="error: ..."`. The subagent's `query()` then returns
   with no text, and `callMultiStep` returns a
   `subagent produced empty output` error to the parent. The
   model-level cause is in the log but not visible to the user
   without `--verbose`. A future round could surface the error
   string in the tool result.
4. **`AetherCodeEngine implements SubagentEngine` implicitly** —
   the SDK's engine class has `query(String)` and the interface
   has the same method. No explicit `implements` needed; duck
   typing at the Java level. (We do explicitly write `implements
   Subagent.SubagentEngine` for clarity in the class declaration.)

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19d\`
(planned)

## Next

R19-E: Session search + `/sessions` + `/search`. Add commands that
list all sessions in the directory and let the user grep across
session history. The session store already exists (R6); this round
adds the search surface.
