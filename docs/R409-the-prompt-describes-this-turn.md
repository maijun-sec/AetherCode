# R409 — The prompt must describe THIS turn

> Round: R409
> Date: 2026-11-25
> Touches: `aethercode/aethercode-prompts/src/main/java/org/aethercode/prompts/SystemPrompt.java`,
> `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/TurnPromptRenderer.java` (new),
> `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`,
> `aethercode/aethercode-core/src/main/java/org/aethercode/core/profile/ProfileRegistry.java`,
> `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java`,
> `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`,
> `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`

---

## The ask

R408 was a measurement round: it measured what the agent does when the
user asks for things of different shapes, and it ranked the gaps. Two of
the five P0s had the same root:

| what the prompt said | what the API offered | what the model did |
|---|---|---|
| "you have `bash`" (the full pool) | the pool pruned to the turn's profile (R401) | called `bash`, got "unknown tool", and hunted for the "real" name — 48 calls |
| "current working directory: \<the boot cwd\>" | the session's current cwd | said the prompt and the session pointed at different directories (S2: 124 calls in the wrong directory) |

Both are the same defect: **the system prompt was rendered ONCE, at
construction time, and every turn inherited it** — including the turns
that ran under a different profile, in a different directory, with a
different tool pool.

★ A PROMPT IS A PER-TURN FACT ★ The profile's prune happens per turn
(R401). The cwd changes per session. A prompt rendered once cannot carry
per-turn facts; it can only carry the facts of the moment it was
rendered. That is not a bug in the prompt's content — it is a bug in
WHERE the prompt is rendered.

## What the prompt was

```
AetherCodeEngine constructor
  └── defaultSystemPrompt(builder)
        ├── SystemPrompt.builder()
        │     .environmentFrom(b.cwd, os)      ← the BOOT cwd
        │     .toolingFrom(b.tools)            ← the FULL pool
        │     .rules(...)  .agents(...)  .build()
        └── .render()   →  a STRING
                ↓
        QueryEngine.systemPrompt = that string   ← FROZEN, forever
                ↓
        every turn:  new StringBuilder(systemPrompt) + memory + plan + posture + language
```

The API call, meanwhile, carried the pruned pool:

```
AetherCodeEngine.query(userInput)
  └── ProfileRegistry.prune(appState.toolPool(), profile)   ← per turn
        └── QueryEngine → visibleTools → chatClient.stream(messages, turnSystem, visibleTools)
```

⇒ **Two halves of one fact, disagreeing.** The tool list on the wire was
right; the tool list in the prose was wrong. The model reads the prose.

## The fix

The rendering moves to the turn. Four parts:

### 1. the tool table is rendered from THIS turn's pool

`SystemPrompt.withTooling(tools)` returns a copy whose tooling section
lists exactly those tools. The SDK renders it per turn, with the pool the
profile left behind.

### 2. the working directory is the session's CURRENT one

`SystemPrompt.withEnvironment(cwd, os)` re-renders the environment
section from `appState.cwd()` — the same source of truth the session
anchor and the shell tool use. A `switchProject` now changes the prompt
on the next turn, not the turn after the next one.

### 3. the schema is not sent twice

The tool table used to render each tool's full JSON schema. The API
already carries it: the `ChatClient` hands the pool to the provider,
which sends name + description + parameters per tool (`SpringAiChatClient`
→ `FunctionCallback`). So the prompt's copy was a **second copy of the
same information** — and, being frozen, a *stale* one.

What stays is name + description: the one place the prompt can say "these
are the tools you may call" in prose, and it survives a provider that
ignores the `tools` parameter.

### 4. the `/prompt` preview shows the prompt the model GOT

The preview used to render the base prompt — the full-pool table. After
R409 it shows the **last turn's** prompt, i.e. the one with the pruned
tool table and the current cwd. A preview that shows a tool the model
cannot call is a window that lies (R383's failure mode, one layer down).

## Where the truth lives

The prompt's SECTION FORMAT lives in `aethercode-prompts`
(`SystemPrompt`). The per-turn FACTS (which tools, which cwd) live in the
SDK, which owns both the profile's prune and the session's cwd. Core
cannot depend on `aethercode-prompts`, so core asks:

```java
public interface TurnPromptRenderer {
    String render(List<Tool> turnTools, Path cwd);
}
```

The SDK installs one on the `QueryEngine`:

```java
this.queryEngine.setTurnPromptRenderer(this::renderTurnPrompt);
```

and the engine calls it **eagerly**, at query time — next to
`visibleTools`, in the same block — because the prompt must be a
SNAPSHOT of the turn that started the run. A subagent that re-enters the
engine mid-turn must not change what the parent turn was told.

★ THE ENGINE STILL WORKS WITHOUT ONE ★ A bare `QueryEngine` (tests,
embedders) keeps the frozen string as its base. `setSystemPrompt(String)`
— the `/prompt` RPC — **drops** the renderer: the user asked for one
exact prompt, and the renderer's whole job is to rewrite the prompt under
them.

## The gates

### The tests

| class | cases | what it pins |
|---|---|---|
| `SystemPromptR409Test` (prompts) | 7 | the tool table lists exactly the tools it was given; no schema; the environment follows the cwd; copying keeps the other sections; the base prompt is not mutated |
| `QueryEngineTurnPromptR409Test` (core) | 6 | the prompt lists the TURN's tools; it names the session's current cwd; without a renderer the frozen prompt is used; a broken renderer does not take the turn down; an explicit prompt replaces the rendering; the renderer and the API see the same pool |
| `AetherCodeEngineTurnPromptR409Test` (sdk) | 5 | the prompt and the API carry the same tools; the prompt follows `setCwd`; the preview shows what the model gets; the prompt carries no schema; the preview follows a verbatim prompt replacement |
| `StreamingToolExecutorR410Test` (core) | 6 | the refusal names the constraint and the allowed tools; a read-only profile has no agent-registry write; the deny set is readable; RESEARCH is read-only and refuses to delegate |

★ WHY THE SDK TEST IS THE DECISIVE ONE ★ The prune happens in the SDK and
the rendering happens in the SDK too, so a defect where the two halves
disagree is invisible to a test that can only see one half. The SDK test
asserts the **agreement**, which is the thing that was broken.

★ WHY THE ASSERTIONS READ THE SECTION, NOT THE PROMPT ★ The default
identity text mentions `file_write` ("you MUST call the actual
`file_write` tool"). So "the prompt does not contain file_write" is not
a test of the tool table at all — it is a test that fails for a reason
that has nothing to do with the table. The tooling/environment SECTIONS
are where the facts live, so the assertions read those.

### The mutation witnesses

Ten mutants, each with its own witness test, run against a **private copy**
of the tree (the shared `target/` is never touched):

| # | the mutation | the witness that catches it |
|---|---|---|
| M01 | the prompt is rendered from the session's pool | `thePromptListsTheTurnsToolsNotTheSessionsPool` |
| M02 | the prompt does not follow the cwd | `thePromptNamesTheSessionsCurrentDirectory` |
| M03 | an explicit prompt is rewritten underneath | `anExplicitPromptReplacesThePerTurnRendering` |
| M04 | the tool table is appended, not replaced | `withTooling_listsExactlyTheToolsItWasGiven` |
| M05 | the schema is sent twice | `theToolTable_carriesTheDescriptionButNotTheSchema` |
| M06 | the engine renders the full pool | `thePromptAndTheApiCarryTheSameTools` |
| M07 | the read-only posture lets the agent registry through | `aReadOnlyProfileHasNoAgentRegistryWrite` |
| M08 | the refusal says "unknown" | `theRefusalNamesTheConstraintAndTheAllowedTools` |
| M09 | the refusal does not list the allowed tools | `theRefusalStillListsTheToolsWhenThereIsNoProfile` |
| M10 | the researcher may delegate | `theResearcherProfileIsReadOnlyAndRefusesToDelegate` |

## R410 — the read-only posture, and a refusal that can be acted on

R409 fixed what the prompt SAYS. Two things were still wrong, and R408
had measured both. That round is its own document:
**`docs/R410-the-read-only-posture-and-the-refusal.md`**.

In one line: the read-only profile denied the project's write tools but
not the **agent-registry** tools (the registry is outside the working
tree), and the refusal for a tool the turn did not have said
`unknown tool: bash` — which told the model it had misnamed the tool,
so it went looking for the right name (48 calls in S1, and finally a
guessed name the provider had never heard of).

## What is still open

* The prompt's tool table lists name + description; the schema lives on
  the wire only. A provider that ignores the `tools` parameter would see
  a prompt without argument shapes. The main path (spring-ai) always
  sends them; a third-party `ChatClient` that drops the pool would not.
* The **classifier** still sends some prompts to the wrong profile
  (R408 measured "查一下 Java 25 的最新特性" → UNCERTAIN, and "这条 SQL
  很慢" → RESEARCH). R409 makes the consequence smaller (the prompt and
  the API now agree), but the classification itself is a separate round
  (R411: the capability axis vs the domain axis).
* The `/prompt` preview shows the LAST turn's prompt. Before the first
  turn it shows the session's pool, which is a superset of any turn's.
* The desktop's own tool table (if it renders one) is a separate copy.

## Lessons

- **★ A PROMPT IS A PER-TURN FACT ★** (R409). The profile's prune happens
  per turn; the cwd changes per session. A prompt rendered once can only
  describe the moment it was rendered — and the model reads the prose,
  not the API's tool list.
- **★ THE PROSE AND THE WIRE MUST BE ONE FACT ★** (R409). The API's tool
  list was right and the prompt's was wrong; nothing failed, because
  neither half was wrong on its own. The test that can see the defect
  has to assert the **agreement**, not either half.
- **★ A PREVIEW THAT SHOWS A TOOL THE MODEL CANNOT CALL IS A WINDOW
  THAT LIES ★** (R409). The `/prompt` view is the user's window into the
  prompt; after R409 it shows the last turn's prompt, not a template.
- **★ "READ-ONLY" IS A SET OF TOOL NAMES, AND THE SET HAS MEMBERS
  OUTSIDE THE WORKING TREE ★** (R410). The agent registry is not in the
  project; a read-only posture that only denies the project's write tools
  still lets the user be edited.
- **★ A REFUSAL MUST PRODUCE THE NEXT ACTION ★** (R410). "unknown tool"
  produced "you misnamed it" — and the model then spent 48 calls looking
  for the name it had not misnamed. The refusal now names the
  constraint, lists what IS allowed, and says what to do next.
