# R94 — System Prompt Revamp (2026-08-17)

## Why

The main agent's behaviour is bounded by what the system prompt tells the
model. The previous default (~6,400 chars total) was functional but
lacked structure: no operating principles, no four-phase method, no
recovery playbook for tool failures, and only a thin treatment of
permission modes.

R94 brings the default prompt up to a higher bar — closer in
discipline to the main-agent prompts used by the modern
multi-agent IDEs. We deliberately **do not copy or reference vendor
names** in code, comments, or doc text. The R94 contract is captured in
sentinel-phrase tests so future refactors cannot silently drop a
critical section.

## What ships

### R94-A — `defaultIdentity` upgrade

Five operating principles lead the identity, each a single sentence:

1. **Honesty over appearance** — say "I don't know" rather than bluff.
2. **Verify before claiming done** — "it compiled" ≠ "it works".
3. **Conservative with destructive operations** — `rm -rf` guards,
   no secret commits.
4. **Respect the user's working directory** — read the project before
   writing to it.
5. **Be concise, but not silent** — markdown for code, sentences not
   essays.

Identity section is now ~3,029 chars (was ~1,200).

### R94-B — `defaultWorkflow` four-phase method

```
plan → explore → implement → verify
```

The workflow section is now structured as labelled phases plus
guidance:

- **Phase 1 — Plan** (always first for non-trivial work)
- **Phase 2 — Explore** (read before write)
- **Phase 3 — Implement** (minimal, targeted, reversible)
- **Phase 4 — Verify** (no "trust me" claims)

Plus the existing infrastructure that survived the revamp:

- **Boulder continuation** (R89-A) — 2 s countdown, auto-continuation
  marker, "Stop auto-continue" button.
- **Subagent delegation** (R89-J, R94-refined) — the `spawn_agent`
  tool, with the standard three roles:
  `explore` / `coder` / `general-purpose`. **Trust but verify**: when
  a subagent reports done, re-read the file and run the test.

Workflow section is now ~9,717 chars (was ~5,200).

### R94-C — New sections

Three new guidance blocks round out the workflow:

- **Tool failure recovery** — three failure patterns and what to do:
  - *Path not found* — try `file_search` first.
  - *Permission denied* — surface the error, don't silently chmod.
  - *Tool error* — READ THE ERROR, adapt, retry up to 3 times.
- **Edit safety** — `rm -rf` guards, secret-commit guards
  (no `.env` / SSH keys / API tokens in `git add` / `git commit`).
- **Permission-mode guidance** (refined) — three modes:
  - `ACCEPT_TASK` (default) — auto-allow the first tool call in a
    new user turn, then re-ask.
  - `DEFAULT` — every tool call asks.
  - `BYPASS_PERMISSIONS` — every tool call auto-allowed.
  - (`ACCEPT_EDITS` / `PLAN` / `AUTO_READ_ONLY` remain accepted by the
    engine but are no longer taught as defaults in the prompt.)

### R94-D — Snapshot test

`DefaultSystemPromptSnapshotTest` (14 tests) locks the contract by
sentinel phrase, not byte-for-byte. Future refactors that drop
"plan", "explore", "Boulder continuation", "spawn_agent", etc. fail
the test and force the reviewer to read the diff.

Size bounds:

- identity: 1,000 – 6,000 chars
- workflow: 8,000 – 30,000 chars

Cross-cutting assertions cover:

- custom identity preserves default workflow
- custom workflow preserves default identity
- rules layer is sandwiched between identity and environment

### R94-E — TUI prompt preview surface

A new `/prompt` slash command in the TUI shows the current system
prompt as a one-section-per-line table. The wire format is exposed by
`AetherCodeMethods.getSystemPrompt`:

```json
{
  "text": "…full prompt…",
  "totalChars": 12748,
  "sectionCount": 3,
  "sections": [
    { "name": "identity", "length": 3029, "source": "default",
      "firstLine": "You are AetherCode, a local AI coding agent…" },
    { "name": "rules",    "length": 472,  "source": "rules:project+global",
      "firstLine": "# Project rules" },
    { "name": "workflow", "length": 9717, "source": "default",
      "firstLine": "The method: plan → explore → implement → verify…" }
  ]
}
```

The TUI delegates formatting to a pure module
`src/prompt-formatter.ts` so the contract is unit-testable without
spinning up the renderer. Each section row shows: name, source
label, byte count, and the first line truncated to ~80 chars.

Useful for:

- "What's the model actually seeing right now?"
- "Did the rule I just edited take effect after the live reload?"
- "Why is identity 12 KB and not the default 3 KB? Did the
  user override it, or is it the default expanded?"

## File map (R94)

| File | Role |
|---|---|
| `aethercode-prompts/.../prompts/SystemPrompt.java` | `defaultIdentity()` + `defaultWorkflow()` + `renderWithSources()` |
| `aethercode-prompts/src/test/.../prompts/DefaultSystemPromptSnapshotTest.java` | 14-test contract lock |
| `aethercode-prompts/src/test/.../prompts/DefaultPromptSize.java` | CLI helper that prints the section sizes |
| `aethercode-protocol/.../methods/AetherCodeMethods.java` | `getSystemPrompt(params)` JSON-RPC |
| `aethercode-protocol/.../http/HttpJsonRpcServer.java` | HTTP+WS switch arm for `getSystemPrompt` |
| `aethercode-protocol/src/test/.../methods/GetSystemPromptRpcTest.java` | 3 tests for the RPC |
| `aethercode-sdk/.../sdk/AetherCodeEngine.java` | `currentRenderedPrompt()` + `currentSystemPromptText()` accessors; `Builder.systemPrompt(SystemPrompt)` overload; `Builder.systemPrompt(String)` backward-compat shim |
| `aethercode-tui/src/commands.ts` | `/prompt` slash command |
| `aethercode-tui/src/prompt-formatter.ts` | pure formatter for the sideNote |
| `aethercode-tui/src/tui.tsx` | `else if (slash.rpcMethod === "getSystemPrompt")` pretty-printer |
| `aethercode-tui/scripts/test/r94e-prompt.test.mjs` | 11 R94-E tests |

## Tests

| Test class | Count |
|---|---:|
| `DefaultSystemPromptSnapshotTest` | 14 |
| `GetSystemPromptRpcTest` | 3 |
| `r94e-prompt.test.mjs` | 11 |
| **R94 total new** | **28** |

## What is *not* in R94

- **Vendor names in code or comments** — R93+ discipline: the
  default prompt is informed by what the modern multi-agent IDEs do
  (four-phase method, principles, recovery, edit safety) but
  no string in the code or comment names those tools.
- **Per-phase tool budgets** — we don't yet tell the model "use at
  most 5 file reads in explore" etc. That would be a fine-grained
  cost-control layer; out of R94 scope.
- **Prompt auto-tuning** — we don't A/B test prompt variants at
  runtime. The R94 contract is fixed; future work can wire
  variants behind a feature flag.
- **Desktop UI for /prompt** — TUI-only for now. The desktop app
  can call the same `getSystemPrompt` RPC trivially when the
  demand arrives.

## How to verify manually

```bash
# 1. Build everything (in aethercode/).
mvn -B install -pl aethercode-prompts,aethercode-sdk,aethercode-protocol,aethercode-tools -am
Copy-Item aethercode-prompts/target/aethercode-prompts-0.2.1.jar ../aethercode/dist/
Copy-Item aethercode-sdk/target/aethercode-sdk-0.2.1.jar ../aethercode/dist/
Copy-Item aethercode-protocol/target/aethercode-protocol-0.2.1.jar ../aethercode/dist/
Copy-Item aethercode-tools/target/aethercode-tools-0.2.1.jar ../aethercode/dist/

# 2. Run the TUI from the aethercode-tui/ workspace.
cd ../aethercode-tui
node --test scripts/test/r94e-prompt.test.mjs     # 11/11

# 3. Print the default prompt sizes (from aethercode-prompts/).
cd ../aethercode/aethercode-prompts
java -cp target/test-classes;target/classes;…  org.aethercode.prompts.DefaultPromptSize
# identity: 3029 chars
# workflow: 9717 chars
# total:    12748 chars

# 4. Drop a rule and watch the live reload.
mkdir -p .aethercode/rules
echo "- Use tabs for indentation" > .aethercode/rules/style.md
# In the TUI: /prompt
#   system prompt (3 sections, 13220 chars)
#     section  source                  length  first line
#     identity default                   3029  You are AetherCode, a local AI coding agent…
#     rules    rules:project+global      472  # Project rules
#     workflow default                   9717  The method: plan → explore → implement → verify…
```

## Open follow-ups (R95+ candidates)

- **R95-A** — TUI `/prompt` enhancements: rules-path column, color
  the source label by kind (`default` green, `rules:*` blue, `builder`
  yellow), expiring toast to keep the scrollback tidy.
- **R95-B** — Subagent real streaming end-to-end (R93-C plumbing
  is in; the `AgentTool` itself still doesn't pipe partial tokens
  into `SubagentRegistry.updatePartial` because the chat client
  hasn't exposed the stream).
- **R95-C** — `tui-jline` module cleanup (the user has confirmed it
  can be retired; only the Ink-based TUI is the supported surface
  now).
- **R95-D** — `Hook.Outcome` extension: async hooks + more pre/post
  hook kinds.
- **R95-E** — Multi-session daemon (R92-D is renderer-side filter;
  the daemon itself is still process-singleton).
- **R95-F** — Per-phase tool budget (mentioned above; useful for
  cost control on long-horizon tasks).
- **R95-G** — TUI prompt preview drill-down: clicking a section
  opens a full-page view of that section's text.
