# R19-F — Custom Agent Definitions

**Date**: 2026-08-06
**Status**: DONE — 1199 tests (+6 AgentRegistryTest, +5 from R19-E), 0 net regression
**Goal**: Let users define custom agent profiles in
`~/.aethercode/agents/*.md` and `<cwd>/.aethercode/agents/*.md`,
each with its own system prompt + optional tool whitelist.
`/agents` lists them, `/agent <name>` switches the active one.

---

## Why

AetherCode has a single hard-coded identity: "You are AetherCode,
a local AI coding agent." Power users want to switch identities
mid-session: a code-reviewer agent, a docs-writer agent, a
data-engineer agent. Each with its own tone, focus, and tool
restrictions.

Claude Code and OpenCode both support custom agent definitions.
The TS original uses a YAML/Markdown mix with frontmatter; we
adopt the same shape.

## What changed

### `AgentDefinition` record

A simple value type carrying the agent's metadata: `name`,
`description`, `model` (optional override), `tools` (optional
whitelist), and `system` (the body of the .md file after the
frontmatter).

### `AgentRegistry`

Loads definitions from one or more directories via
`loadFrom(Path)`. Later directories override earlier ones on name
collision (so project-scoped agents win over user-wide agents).
Each `.md` file is parsed as:

```
---
name: <required>
description: <optional>
model: <optional>
tools: [a, b, c]   # optional whitelist
---

<system prompt body>
```

The YAML parser is intentionally minimal — handles scalar `key: value`
lines and a single list-valued key (`tools:`). Comments, nested
maps, and multi-line strings are NOT supported; the user is asked
to keep definitions flat.

### `QueryEngine.setSystemPrompt(String)` + `queryEngine()` getter

The TUI needs to be able to swap the active system prompt when
the user runs `/agent <name>`. The QueryEngine's `systemPrompt`
field is now `volatile` (was `final`); the SDK exposes
`AetherCodeEngine.queryEngine()` so callers (TUI, future
agent-spawning tools) can mutate engine state without going
through the public `query()` path.

### TUI: `/agents` and `/agent <name>`

- `/agents` — lists every agent the registry knows about
  (always shows "default" as the first entry, with a
  "← active" marker on the current one). The first column is
  the name; the second is the description; the tools whitelist
  is shown in dim on a second line if present.
- `/agent <name>` — switches the active agent. With no name,
  prints the current active agent. `default` reverts to the
  built-in identity. If the agent specifies a tool whitelist,
  the tool pool is replaced with the filtered set; the user's
  BashTool, file tools, etc. are then scoped per the whitelist.
- `/agent default` — restore the default identity (re-renders
  the default `SystemPrompt` and switches back).

### `Main.runRepl()` loads the agent registry

On REPL boot, `Main` constructs an `AgentRegistry`, loads from
`$HOME/.aethercode/agents/` (lower priority) and
`<cwd>/.aethercode/agents/` (overrides), and injects it into
the ReplApp. Missing directories are silently ignored.

## File format

```markdown
---
name: code-reviewer
description: Reviews code for quality and security
model: MiniMax-M3
tools: [file_read, glob, grep]
---

You are a meticulous code reviewer. For every change, check:
1. Correctness
2. Security
3. Tests
```

The `tools:` whitelist is the most interesting part — a
code-reviewer agent should NOT have `file_write` (it reads, never
writes). The agent can't accidentally do something outside its
remit.

## Tests

- `aethercode-sdk/.../agent/AgentRegistryTest.java` (6 tests):
  - `parse_minimalFrontmatter` — name + description only
  - `parse_toolsListInlineArray` — `[a, b, c]` form
  - `parse_missingFrontmatterIsSkipped` — no frontmatter → file
    silently dropped
  - `loadFrom_missingDirIsNoOp` — missing dir → empty registry
  - `loadFrom_multipleAgentsAndOrdering` — `all()` is sorted by
    name
  - `loadFrom_silentlyIgnoresNonMdFiles` — only `.md` files are
    loaded

## Files

- `aethercode-sdk/.../agent/AgentDefinition.java` (new)
- `aethercode-sdk/.../agent/AgentRegistry.java` (new) — parser
  + registry
- `aethercode-sdk/.../AetherCodeEngine.java` — `queryEngine()`
  getter
- `aethercode-core/.../engine/QueryEngine.java` —
  `setSystemPrompt(String)` + `systemPrompt()` getter, field
  changed from `final` to `volatile`
- `aethercode-tui/.../ReplApp.java` — `agentRegistry` field,
  `activeAgentName` field, `withAgentRegistry(...)` setter,
  `/agents` and `/agent <name>` commands, help text update
- `aethercode-cli/.../Main.java` — `runRepl` loads the registry
  from `$HOME/.aethercode/agents/` and `<cwd>/.aethercode/agents/`
- `aethercode-sdk/src/test/.../AgentRegistryTest.java` (new, 6
  tests)

## Pitfalls (R19-F)

1. **Tool whitelist permanently replaces the pool** — when the
   user runs `/agent reviewer` (whitelist = file_read, glob,
   grep), the tool pool is replaced with those three. Switching
   back to `default` does NOT restore the original tools
   (we've lost the reference). A future round should snapshot
   the default pool at engine construction time and restore
   from it on `/agent default`.
2. **YAML parser is intentionally minimal** — only scalar
   `key: value` and a single list-valued `tools:` are supported.
   Don't try to use multi-line strings, comments after values,
   or nested maps. If a user pastes a richer YAML file, the
   parse silently fails or returns empty fields. A future
   round could swap in SnakeYAML or similar.
3. **Project agents shadow user agents** — on name collision,
   the later-loaded (project) agent wins. This is the right
   behaviour for "I want to override the global reviewer for
   THIS project" but can be surprising when a user accidentally
   names a project agent the same as a user agent.
4. **`setSystemPrompt` is mutable, not transactional** — the
   `volatile` field write is visible to the next query but
   doesn't roll back. If the user issues a query mid-switch
   (which the UI doesn't allow), they'd see a partial state.
   In practice the switch is synchronous so this never
   happens.
5. **No `/agent` command persistence** — switching agents is
   session-scoped. Next session starts back at the default.
   Could persist via `~/.aethercode/active-agent.txt` if
   requested.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19f\`
(planned)

## Next

R19-G: Better Bash tool. Add streaming stdout (so the user sees
output as the command runs, not just at the end), support
cancelling a long-running command via Ctrl+C, and background
processes (`bash &` style, with a `/jobs` command to list and
inspect them).
