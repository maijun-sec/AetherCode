# R109-3: Agent ↔ Model linkage (2026-08-13)

## TL;DR

Agent `agent.md` files now carry a `model: provider/model` frontmatter field.
When the workflow executor's `kind: agent` step spawns an agent as a child
session, it picks up that field and routes the LLM call through the
corresponding provider/model — independent of the engine's main-loop model.
The desktop's Agents tab shows a model badge on each row; the editor exposes
a provider/model dropdown. Empty / missing field = "use the engine's current
default", which is the legacy R107-B behaviour.

## What ships in this round

1. `agent.md` frontmatter: `model: <provider>/<modelId>` (e.g. `glm/glm-4-flash`)
2. `AgentRegistry.AgentMeta.model` field (string, empty = no binding)
3. `AgentRegistry.reload()` parses the new `model:` line from each `agent.md`
4. `AgentRegistry.getMeta(name)` accessor returns the full meta
5. `AetherCodeEngine.getAgentMeta(name)` engine-level accessor
6. `QueryEngine.query(String, ChatClient override)` 2-arg overload — per-call
   ChatClient override; the engine's `chatClient` field is **not** mutated
7. `AetherCodeEngine.queryInChildSession(...)` 6-arg overload that takes a
   `ChatClient` parameter
8. `WorkflowExecutor.SkillInvoker.invoke` 5-arg abstract with `(kind, name,
   prompt, modelOverride, eventSink)`; 4-arg/3-arg are default impls
9. `WorkflowExecutor` 6-arg constructor with `Function<String, String>
   agentModelLookup` callback (called only when `kind == agent`)
10. `AetherCodeMethods` injects `Function<String, ChatClient> chatClientResolver`
    so the protocol layer doesn't import `engine-springai` directly
11. `AetherCodeMethods.listAgents` wire shape includes the new `model` field
12. `AetherCodeMethods.getAgentBody` wire shape returns the full frontmatter
    (description / displayName / model) so the editor can prefill all four
    fields on edit
13. `AetherCodeMethods.switchProvider` bug fix: R109-1 was a stub that only
    updated the cached `currentModelId` field; the engine's actual `ChatClient`
    is now hot-swapped (`engine.setChatClient(newClient)` +
    `engine.mainLoopModelName(modelId)`). The Settings panel's provider picker
    now actually changes which model the next LLM call uses.

## Why this design

The earlier R107-B agent registry stored agent bodies on disk but had no
frontmatter for "what model does this agent want". The user had to switch the
engine's main-loop model in Settings before the workflow's `kind: agent` step
ran, which made agent-step modelling inconsistent: a workflow that wanted a
"fast agent" (Haiku) and a "deep agent" (Opus) couldn't pick two different
models for the two steps without re-running the workflow with the engine
re-configured between steps.

R109-3 fixes this at the data layer (frontmatter), the engine layer
(`query(..., ChatClient override)`), and the protocol layer
(`chatClientResolver` injection). The per-call override is captured into the
`StreamSupport` Spliterator's local variables so the engine's `chatClient`
field is never mutated by a child session — concurrent main-loop + child
session calls do not race on a shared field.

## Frontmatter shape

```markdown
---
name: code-reviewer
description: Reviews PR diffs and flags style issues
displayName: Code Reviewer
model: glm/glm-4-flash       # R109-3
---

You are a senior code reviewer ...
```

The `model:` field is a "provider/modelId" pair. The provider name is the
`id` from the `providers:` block in `~/.aethercode/settings.yaml`; the modelId
is the id exposed by that provider's `/v1/models` (or equivalent). The
empty string `""` (or missing field) means "use the engine's default" — the
exact behaviour pre-R109-3.

## Wire shape

`listAgents()` returns:

```json
{
  "ok": true,
  "count": 2,
  "agents": [
    {
      "name": "code-reviewer",
      "description": "Reviews PR diffs",
      "displayName": "Code Reviewer",
      "model": "glm/glm-4-flash",
      "lastModifiedMs": 1723555200000
    },
    {
      "name": "summarizer",
      "description": "Summarises long docs",
      "displayName": "Summarizer",
      "model": "",
      "lastModifiedMs": 1723555100000
    }
  ]
}
```

`getAgentBody({name: "code-reviewer"})` returns:

```json
{
  "ok": true,
  "name": "code-reviewer",
  "body": "You are a senior code reviewer ...",
  "path": "C:/Users/me/.minimax/agents/code-reviewer/agent.md",
  "description": "Reviews PR diffs",
  "displayName": "Code Reviewer",
  "model": "glm/glm-4-flash",
  "lastModifiedMs": 1723555200000
}
```

The `model` field is empty for legacy agents written before R109-3; the editor
shows `(engine default)` for those rows.

## Engine layer

`QueryEngine.query(String userInput, ChatClient override)` — 2-arg overload.
When `override != null`, every LLM call in this query (and only this query)
uses `override`. The override is captured into a `final ChatClient
effectiveChatClient` local; the Spliterator's `tryAdvance` reads the local,
so the engine's `chatClient` field is not touched. Concurrent callers (e.g.
a main-loop query on one thread and a workflow executor's child session on
another) do not race on a shared field.

`AetherCodeEngine.query(String userInput, ChatClient override)` — same
pattern at the SDK level. This is what the workflow executor's
`SkillInvoker` lambda calls when it has a non-null `modelOverride`.

`AetherCodeEngine.queryInChildSession(...)` 6-arg overload — uses the
override on the child session only; the main loop's `chatClient` is
untouched. The child session runs in its own `AppState` clone.

## Workflow executor layer

`WorkflowExecutor.SkillInvoker.invoke(kind, name, prompt, modelOverride,
eventSink)` — abstract 5-arg method. The default 4-arg / 3-arg overloads
call the 5-arg version with `modelOverride = null` so old callers keep
working.

`WorkflowExecutor` 6-arg constructor:

```java
new WorkflowExecutor(
    state, config, executor, invoker, eventBus,
    agentName -> engine.getAgentMeta(agentName)
                       .map(AgentMeta::model)
                       .orElse("")
)
```

The lookup callback is `Function<String, String>` — `kind=agent` looks up
`agentModelLookup(name)` and uses the result as the model; `kind=skill` does
**not** call the callback (skills don't carry a model binding, they use
the engine default). This is decision #172 in the cross-project ledger.

## Protocol layer

`AetherCodeMethods` doesn't import `engine-springai` directly. Instead the
CLI's `DaemonRunner.runHttp` wires a `Function<String, ChatClient>
chatClientResolver` into the methods object. The methods object calls
`chatClientResolver.apply("glm/glm-4-flash")` and passes the resulting
`ChatClient` to `engine.query(prompt, override)`. The CLI has the
`engine-springai` provider registry; the protocol layer doesn't need to
know about it. This is decision #167.

The `AetherCodeMethods.switchProvider` bug fix piggybacks: R109-1 was a
stub that only updated the cached `currentModelId` field. The new
implementation also calls `engine.setChatClient(resolver.apply(...))` and
`engine.mainLoopModelName(modelId)`, so the next main-loop query actually
uses the new model. Decision #174.

## Desktop UI

`AgentsPanel.tsx` now shows a model badge on each row:

```
Code Reviewer                        [glm/glm-4-flash]
code-reviewer  Reviews PR diffs and flags style issues

Summarizer                           [(default)]
summarizer  Summarises long docs
```

The badge uses accent blue with a thin border when a model is bound; an
italic muted "(default)" when the field is empty. CSS lives in
`AgentsPanel.css` under `agents-panel-row-model` /
`agents-panel-row-model-default`.

`AgentEditor.tsx` has a new "Model" `<select>` between Description and Body.
The dropdown is built from the engine's `availableProviders` (each provider
+ each of its models = one option) plus a top "(engine default)" option.
On create, a `useEffect` sets the default to
`currentProvider/currentProvider.defaultModel` so the user doesn't have to
pick from the full list on a fresh agent.

`AgentEditorLazy` (in `AgentsPanel.tsx`) now reads `r.description`,
`r.displayName`, `r.model` from the new `getAgentBody` response and passes
them to the editor as `initial`. Editing an existing agent opens with all
four frontmatter fields prefilled, not just the body.

## Tests

| Test | Result |
|------|--------|
| `AgentRegistryTest` (14 cases) | PASS |
| `WorkflowExecutorChildSessionTest` (6 cases, includes 3 new for R109-3) | PASS |
| `WorkflowExecutorTest` (19 cases) | PASS |
| `WorkflowReaderTest` (9 cases) | PASS |
| `QueryEngineMaxTurnsTest` (4 cases) | PASS — see "loop test fix" below |
| `AetherCodeMethodsTest` (model field, switchProvider hot-swap) | PASS |
| `SubagentPoolTest#submit_multipleConcurrently` | KNOWN-FLAKY pre-R109-3 |

### Loop test fix

R83 removed the per-query turn cap. The legacy `runawayLoopStopsAtCap`
test assumed the old cap-based stop, with each call using a *different*
`msg` input (so the fingerprint never matched). After R83 the cap is a
no-op and the loop detector (`ProgressLoopDetector` from R32-C, tiered
in R101) is the source of truth for "this is a runaway loop". The
detector's `same_fingerprint` rule requires the input to be **constant**
for it to fire — which the old test didn't satisfy.

R109-3 fixes this by:

1. Using a constant `msg: "tick"` input so the fingerprint is identical
   every turn
2. Asserting `loop_detected` as the new stopReason (was `max_iterations`)
3. Asserting a `SideNote(kind=loop-detected)` is emitted before the
   terminal `RunEnd`, so the UI can show a banner

The test still asserts `llmCalls == 3` — the detector's tier-3 verdict
on hit 3 is the same end-state the old R14-3 cap produced.

## Cross-project lessons (R109-3, decisions 167-184)

1. Use injected `Function<String, ChatClient> chatClientResolver` to
   keep `aethercode-protocol` from depending on `engine-springai`
   (decision #167)
2. Per-call ChatClient override via Spliterator local capture — engine's
   `chatClient` field is volatile but **not** mutated by `query(...)`
   (decisions #168-#169)
3. `SkillInvoker.invoke` 5-arg abstract; 4-arg/3-arg are default impls
   that pass `modelOverride=null` (decision #170)
4. `agentModelLookup` is `Function<String, String>`, **not** `Optional<String>`
   — empty string = no binding, no Optional ceremony (decision #171)
5. `kind=agent` calls `agentModelLookup`; `kind=skill` does not (decision
   #172)
6. `AgentMeta.model` defaults to `""`, not `null` — empty string is
   the "use engine default" signal (decision #173)
7. The R109-1 `switchProvider` hot-swap bug fix piggybacks on R109-3
   because both touch the engine's ChatClient mutation surface
   (decision #174)
8. `setMaxTurnsPerQuery` is a no-op post-R83; the loop detector is the
   source of truth (decision #175, fixed test in R109-3)
9. PowerShell doesn't support `&&` chain — use `;` or split commands
   (decision #184)

## What doesn't ship in R109-3

- **TUI standalone sync**: the TUI's `/agents` view is a separate
  code path; it shows the agent list but not the model column yet.
  R109-3-Sync (this round) adds the model column to the TUI's agent
  list. See `R109-3-SYNC-TUI-2026-08-13.md`.
- **Project-level agents** (R110 candidate): agents in the project's
  `.aethercode/agents/` directory in addition to the user-level
  `~/.minimax/agents/`. The registry already has the
  infrastructure; the loader just needs to add the project dir.
- **Per-step model in workflows** (R110 candidate): a workflow
  `step.model` field that overrides the per-agent binding for that
  one step. Useful for testing a "fast agent" with Opus once without
  editing the agent.

## Files changed

### Backend (aethercode)

- `aethercode-core/src/main/java/org/aethercode/core/agent/AgentRegistry.java`
- `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
- `aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowExecutor.java`
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java`

### Tests (aethercode)

- `aethercode-core/src/test/java/org/aethercode/core/agent/AgentRegistryTest.java`
  (+3 cases)
- `aethercode-core/src/test/java/org/aethercode/core/workflow/WorkflowExecutorChildSessionTest.java`
  (3 existing → 5-arg; +3 new)
- `aethercode-core/src/test/java/org/aethercode/core/engine/QueryEngineMaxTurnsTest.java`
  (runaway test rewritten for R83 loop detector)

### Frontend (aethercode-desktop)

- `aethercode-desktop/src/components/AgentsPanel.tsx` (model badge +
  AgentEditorLazy prefill)
- `aethercode-desktop/src/components/AgentsPanel.css` (model badge
  styles)
- `aethercode-desktop/src/lib/methods.ts` (`AgentMeta.model` field;
  `getAgentBody` return type extended)
- `aethercode-desktop/src/store/index.ts` (getAgentBody pass-through +
  AppState type)

## Migration

Existing agents on disk (pre-R109-3) have no `model:` line. They continue
to work — the `model` field is `""` and the engine uses the main-loop
default. The editor's "(engine default)" label surfaces this state in
the UI so the user knows which agents have an explicit binding and
which inherit.

To bind a model on an existing agent:

1. Open the Settings panel's "Agents" tab
2. Click the agent's row
3. Pick a provider/model in the Model dropdown
4. Click Save

The `model:` line is appended to the on-disk `agent.md` frontmatter.
