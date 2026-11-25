# R410 — The read-only posture, and a refusal that can be acted on

> Round: R410
> Date: 2026-11-25
> Touches: `aethercode/aethercode-core/src/main/java/org/aethercode/core/profile/ProfileRegistry.java`,
> `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java`

---

## The ask

R408 measured two defects that R409 did not touch, both of which are
about **what a refusal says** and **what a posture covers**.

The evidence is R408's S4 (a poem that must land on disk), run against
the read-only `navigator` profile:

```
63 tool calls, and SIX different write channels all answered
"unknown tool: file_write" / "unknown tool: bash" / "unknown tool:
CompatFileWrite4d3b7c" — the model hunting for a name that does not exist.
```

and then, in the middle of that hunt:

```
update_agent(name=end-user, body=probe)   →  EXECUTED
```

⇒ The user's own agent definition
(`~/.aethercode/agents/end-user/agent.md`) was overwritten with 30 bytes
of "probe" **by a read-only turn**.

## Defect 1 — the posture leaked

`READ_ONLY_DENY` (R401) was:

```java
Set.of("file_write", "file_edit", "notebook_edit", "bash")
```

★ THE WRITE TOOLS AND THE SHELL ARE NOT THE WHOLE SET ★ The write tools
change the **project**. The shell changes the project. But the agent
registry lives in `~/.aethercode/agents/` — **outside** the working
tree — and `create_agent` / `update_agent` / `delete_agent` change the
**user's own configuration**. A read-only posture that does not name
them is not read-only; it is "cannot change the project".

R401's design note says the child agent inherits the parent's profile, so
"a read-only parent cannot launder a write through a child". That
argument is sound — and irrelevant to this defect, because the parent
could write the registry itself.

⇒ R410 names the three agent-CRUD tools and puts them in the deny set:

```java
public static final Set<String> AGENT_WRITE_TOOLS = Set.of(
        "create_agent", "update_agent", "delete_agent");

public static final Set<String> READ_ONLY_DENY = Set.of(
        "file_write", "file_edit", "notebook_edit", "bash",
        "create_agent", "update_agent", "delete_agent");
```

★ WHY A DENY LIST AND NOT AN ALLOW LIST ★ An allow-list would be
structurally safer: a write tool added tomorrow is denied by default,
without anybody remembering. But the tool pool is **not a closed set** —
MCP servers add tools at runtime, and an allow-list would silently
disable every one of them, including the read-only MCP tools, in every
read-only posture. The deny list is the honest trade-off, and its cost
is paid in exactly one place: every tool that can change the user's
state has to be **named**. That is why the set is a named constant
(`AGENT_WRITE_TOOLS`) and not a literal buried in a profile: a reader
can see what a read-only posture refuses, and a test can pin it.

★ AND THE SETS MUST NOT DRIFT ★ The `researcher` profile needs the same
denials PLUS `spawn_agent`. Spelled out again, that is a second copy of
the deny set — and a second copy is exactly how a list drifts (R378 /
R380). So it is built FROM the read-only set:

```java
public static final Set<String> RESEARCH_DENY = deny(READ_ONLY_DENY, Set.of(SPAWN_TOOL));
```

## Defect 2 — the refusal said "unknown"

A tool the turn did not have was refused with:

```
unknown tool: file_write
```

★ "UNKNOWN" IS A CLAIM ABOUT THE WORLD ★ It says the tool does not
EXIST. The model read it that way and acted on it: it took the refusal as
evidence that **it had misnamed the tool**, so it went looking for the
right name — 48 calls in R408's S1, and in S4 a name the provider had
never heard of (`CompatFileWrite4d3b7c`).

What the refusal actually had to say is: **this turn does not have that
tool, and here is what it does have.** A refusal that cannot produce a
next action leaves the model to invent one — and it will.

⇒ R410 rewrites the refusal:

```
the tool `file_write` is not available in this turn.

This turn is **读代码答疑** (profile `analyst`) — `file_write` is refused
BY DESIGN for this kind of turn.

The tools you MAY call in this turn are: file_read, grep, glob, list_agents.

Do not go looking for another name for it: there is no other name, and there
is no other way to do it in this turn. If the user wants that change, say so
and stop — the next turn is classified again and the tools come back on their
own.
```

Three things it carries, and each one is a thing the old refusal lacked:

| the refusal says | so the model can conclude |
|---|---|
| the constraint (read-only, profile X) | "I am not ALLOWED to write" — not "I misnamed it" |
| the tools it MAY call | "here is a real one to use instead" |
| the next move | "ask the user; the next turn is classified again" |

★ WHEN THE TOOL WAS REFUSED BY THE PROFILE, IT SAYS SO ★ "BY DESIGN" is
what separates "this turn does not have it" from "it does not exist".
Without it, the model is back to hunting for a name.

## The gates

`StreamingToolExecutorR410Test` (core) — 6 cases:

| case | what it pins |
|---|---|
| `theRefusalNamesTheConstraintAndTheAllowedTools` | the refusal does NOT say "unknown tool"; it names the profile and lists the allowed tools |
| `theRefusalSaysTheToolIsRefusedByDesignWhenTheProfileDeniedIt` | a denied tool is called out as a design decision |
| `theRefusalStillListsTheToolsWhenThereIsNoProfile` | a turn with no posture still gets the tool list |
| `aReadOnlyProfileHasNoAgentRegistryWrite` | ★ the live defect: analyst / reviewer / navigator / researcher keep `file_read`, `grep`, `glob`, `list_agents` and lose the three agent-CRUD tools |
| `theDenySetIsNamedSoItCanBeRead` | the deny set is a named constant, and it contains what it must |
| `theResearcherProfileIsReadOnlyAndRefusesToDelegate` | `RESEARCH_DENY` ⊇ `READ_ONLY_DENY` + `spawn_agent` — built from it, not spelled out again |
| `anActionProfileKeepsTheAgentTools` | the deny set belongs to the READ-ONLY profiles: a turn asked to change something may still set an agent up |

★ THE MUTATION WITNESSES ★ (see the R409 harness)

| # | the mutation | the witness |
|---|---|---|
| M07 | the read-only posture lets the agent registry through | `aReadOnlyProfileHasNoAgentRegistryWrite` |
| M08 | the refusal says "unknown" | `theRefusalNamesTheConstraintAndTheAllowedTools` |
| M09 | the refusal does not list the allowed tools | `theRefusalStillListsTheToolsWhenThereIsNoProfile` |
| M10 | the researcher may delegate | `theResearcherProfileIsReadOnlyAndRefusesToDelegate` |

## What is still open

* The deny list is only as complete as the list of tools that can change
  the user's state. **MCP tools are not in it** — a read-only posture
  will happily call an MCP tool that writes. An allow-list would fix
  that, at the price of disabling every MCP tool (including the reading
  ones) in a read-only turn. The honest answer is probably a per-MCP
  "is this tool read-only" declaration, which the MCP layer does not
  carry yet.
* The refusal is English. The prompt's language lock (R737) applies to
  the model's OUTPUT; the refusal is a tool RESULT, and tool results are
  kept in the provider's language. It is a small inconsistency, not a
  wrong one.
* The postures' own wording ("There is no shell in this turn: read with
  `file_read`, `grep` and `glob`") does not mention the agent registry.
  The refusal covers it at runtime; the prompt could say it up front.

## Lessons

- **★ "READ-ONLY" IS A SET OF TOOL NAMES, AND THE SET HAS MEMBERS
  OUTSIDE THE WORKING TREE ★** (R410). The agent registry is not in the
  project. A read-only posture that only denies the project's write
  tools still lets the user be edited — and the S4 measurement showed
  the edit landing.
- **★ A REFUSAL MUST PRODUCE THE NEXT ACTION ★** (R410). "unknown tool"
  produced "you misnamed it" — and the model then spent 48 calls looking
  for the name it had not misnamed. The refusal now names the
  constraint, lists what IS allowed, and says what to do next.
- **★ TWO SETS THAT MUST AGREE ARE BUILT FROM ONE ★** (R410). The
  researcher's deny set is `READ_ONLY_DENY` plus one name, not a second
  spelling of the same list.
- **★ AN ALLOW LIST IS SAFER AND LESS USABLE ★** (R410). The tool pool
  is open (MCP adds to it at runtime), so an allow-list would disable
  tools nobody audited. The deny list's cost is that every write
  channel must be NAMED — so the set is a named constant with a test,
  not a literal in a profile.
